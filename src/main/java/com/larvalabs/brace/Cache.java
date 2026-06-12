package com.larvalabs.brace;

import java.net.URLEncoder;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Cache facade. Owns hit/miss/eviction stats, TTL parsing, value serialization, and the page-cache
 * wrapper, and delegates storage to a {@link CacheBackend}.
 *
 * <p>Default is in-process ({@link CacheBackend#inMemory()}) with zero serialization — no behavior
 * change for existing apps. Opt into a shared, cross-server-consistent backend with one line:
 * {@code app.cache(CacheBackend.postgres(dbFactory))}. See
 * {@code docs/2026-06-04-brace-shared-cache.md}.
 *
 * <p>When the backend stores bytes ({@link CacheBackend#requiresSerialization()}), values are
 * serialized via Jackson with a class-name header, so a {@code get} with the wrong type fails loudly
 * and a non-Jackson-round-trippable value throws at {@code set} time. The in-memory backend stores
 * live objects, so it has neither cost nor restriction. Values must be non-null on either backend.
 */
public class Cache {

    private static final String[] NO_TAGS = new String[0];

    private final CacheBackend backend;
    private final boolean serializes;
    private final Thread cleanupThread;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    public Cache() {
        this(new InMemoryBackend());
    }

    public Cache(CacheBackend backend) {
        this.backend = backend;
        this.serializes = backend.requiresSerialization();
        this.cleanupThread = Thread.ofVirtual().name("cache-cleanup").start(() -> {
            while (true) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    break;
                }
                evictions.add(backend.evictExpired());
            }
        });
    }

    /** Stop the background expiry-sweep thread. Called by {@code Brace.stop()}. */
    public void close() {
        cleanupThread.interrupt();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        if (serializes) {
            byte[] bytes = backend.getBytes(key);
            if (bytes == null) {
                misses.increment();
                return null;
            }
            try {
                T value = deserialize(bytes, type);
                hits.increment();
                return value;
            } catch (CacheCorruptionException e) {
                // Unreadable entry (corruption, truncation, or a class removed across deploys):
                // treat it as a miss rather than crashing the request. A wrong-but-valid type still
                // fails loudly via ClassCastException (not caught here).
                misses.increment();
                return null;
            }
        }
        Object value = backend.getObject(key);
        if (value == null) {
            misses.increment();
            return null;
        }
        hits.increment();
        return (T) value;
    }

    public void set(String key, Object value) {
        store(key, value, null, NO_TAGS);
    }

    public void set(String key, Object value, String ttl) {
        store(key, value, parseTtl(ttl), NO_TAGS);
    }

    public void set(String key, Object value, String ttl, String... tags) {
        store(key, value, parseTtl(ttl), tags);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrSet(String key, String ttl, Supplier<T> supplier) {
        if (!serializes) {
            var computed = ((InMemoryBackend) backend)
                    .getOrCompute(key, parseTtl(ttl), () -> requireValue(key, supplier.get()));
            if (computed.hit()) hits.increment(); else misses.increment();
            return (T) computed.value();
        }
        byte[] bytes = backend.getBytes(key);
        if (bytes != null) {
            try {
                T cached = deserialize(bytes, null);
                hits.increment();
                return cached;
            } catch (CacheCorruptionException e) {
                // Fall through and recompute, treating the unreadable entry as a miss.
            }
        }
        misses.increment();
        T value = requireValue(key, supplier.get());
        backend.setBytes(key, serialize(value), parseTtl(ttl), NO_TAGS);
        return value;
    }

    public void delete(String key) {
        backend.delete(key);
    }

    public void deletePrefix(String prefix) {
        backend.deletePrefix(prefix);
    }

    public void clear() {
        backend.clear();
        hits.reset();
        misses.reset();
        evictions.reset();
    }

    public long incr(String key) {
        return backend.incr(key, 1);
    }

    public long decr(String key) {
        return backend.incr(key, -1);
    }

    public void clearTag(String tag) {
        evictions.add(backend.clearTag(tag));
    }

    /** True when a shared (cross-server) backend is configured — invalidation and clear are fleet-wide. */
    public boolean shared() { return backend.shared(); }

    public int size() { return backend.size(); }
    public int counterCount() { return backend.counterCount(); }
    public int tagCount() { return backend.tagCount(); }
    public long hits() { return hits.sum(); }
    public long misses() { return misses.sum(); }
    public long evictions() { return evictions.sum(); }
    public long drainHits() { return hits.sumThenReset(); }
    public long drainMisses() { return misses.sumThenReset(); }
    public long drainEvictions() { return evictions.sumThenReset(); }

    // Route-level page caching

    public CachedHandler wrap(String ttl, Handler handler) {
        return new CachedHandler(this, ttl, handler);
    }

    /**
     * A fully-materialized HTTP response — the unit the page cache stores. Unlike a {@code Result}
     * (which can be lazy or hold non-serializable state), this is a trivially serializable snapshot,
     * so a page cached by one server can be replayed by any other across a shared backend. It's
     * Jackson-round-trippable ({@code byte[]} body encodes as base64), so it works on both the
     * in-memory and serializing backends.
     */
    public record RenderedResponse(int status, String contentType, Map<String, String> headers, byte[] body) {
        static RenderedResponse from(Result result) {
            byte[] body;
            if (result.rawBytes() != null) {
                body = result.rawBytes();
            } else if (result.body() != null) {
                body = result.body().getBytes(StandardCharsets.UTF_8);
            } else {
                body = new byte[0];
            }
            return new RenderedResponse(result.status(), result.contentType(),
                    new LinkedHashMap<>(result.headers()), body);
        }

        Result toResult() {
            return Result.raw(status, contentType, body, headers);
        }
    }

    // Internal

    private void store(String key, Object value, Duration ttl, String[] tags) {
        requireValue(key, value);
        if (serializes) {
            backend.setBytes(key, serialize(value), ttl, tags);
        } else {
            backend.setObject(key, value, ttl, tags);
        }
    }

    void setInternal(String key, Object value, String ttl, String[] tags) {
        store(key, value, parseTtl(ttl), tags);
    }

    /** Cache values must be non-null — null is reserved for "missing" and would diverge between backends. */
    private static <T> T requireValue(String key, T value) {
        if (value == null) {
            throw new IllegalArgumentException("Cache value for key '" + key + "' must not be null");
        }
        return value;
    }

    private static byte[] serialize(Object value) {
        try {
            byte[] className = value.getClass().getName().getBytes(StandardCharsets.UTF_8);
            byte[] body = Json.mapper().writeValueAsBytes(value);
            return ByteBuffer.allocate(4 + className.length + body.length)
                    .putInt(className.length).put(className).put(body).array();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cache value of type " + value.getClass().getName() + " is not serializable for "
                    + "the configured shared cache backend (values must be Jackson-round-trippable)", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserialize(byte[] data, Class<T> expected) {
        if (data.length < 4) {
            throw new CacheCorruptionException("cached value too short to hold a header");
        }
        var buffer = ByteBuffer.wrap(data);
        int len = buffer.getInt();
        if (len < 0 || len > data.length - 4) {
            throw new CacheCorruptionException("invalid class-name length " + len + " for " + data.length + " bytes");
        }
        byte[] className = new byte[len];
        try {
            buffer.get(className);
        } catch (BufferUnderflowException e) {
            throw new CacheCorruptionException("truncated class-name header");
        }
        int offset = 4 + len;
        String storedName = new String(className, StandardCharsets.UTF_8);
        Class<?> stored;
        try {
            stored = Class.forName(storedName, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new CacheCorruptionException("cached class no longer present: " + storedName);
        }
        if (expected != null && !expected.isAssignableFrom(stored)) {
            // Wrong-but-valid type: fail loudly, this is a programming error, not corruption.
            throw new ClassCastException("Cached value is a " + storedName
                    + " but get(...) requested " + expected.getName());
        }
        try {
            return (T) Json.mapper().readValue(data, offset, data.length - offset, stored);
        } catch (java.io.IOException e) {
            throw new CacheCorruptionException("cached value body did not deserialize as " + storedName);
        }
    }

    /** Thrown internally when a stored byte payload is unreadable; the facade treats it as a miss. */
    private static final class CacheCorruptionException extends RuntimeException {
        CacheCorruptionException(String message) { super(message); }
    }

    private static final Pattern TTL_PATTERN = Pattern.compile("(\\d+)([smhd])");

    // TTL strings are code-site literals ("10m", "1h") with tiny cardinality, but parseTtl
    // runs on every cache call (and on every cached-page request) — memoize the regex parse.
    // Failures propagate out of computeIfAbsent and are never cached, preserving the
    // throw-per-call behavior for invalid strings.
    private static final java.util.concurrent.ConcurrentHashMap<String, Duration> TTL_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    static Duration parseTtl(String ttl) {
        Duration cached = TTL_CACHE.get(ttl);
        if (cached != null) return cached;
        return TTL_CACHE.computeIfAbsent(ttl, Cache::parseTtlUncached);
    }

    private static Duration parseTtlUncached(String ttl) {
        var matcher = TTL_PATTERN.matcher(ttl);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid TTL format: " + ttl);
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Unknown TTL unit: " + matcher.group(2));
        };
    }

    // Cached handler wrapper

    public static class CachedHandler implements Handler {
        private final Cache cache;
        private final String ttl;
        private final Handler handler;
        private String[] tags = new String[0];
        private String[] vary = new String[0];

        CachedHandler(Cache cache, String ttl, Handler handler) {
            this.cache = cache;
            this.ttl = ttl;
            this.handler = handler;
        }

        public CachedHandler tags(String... tags) {
            this.tags = tags;
            return this;
        }

        /**
         * Declares which query params key the cache. Only declared params contribute to the cache
         * key; everything else is ignored, so clients can't mint entries with junk params
         * ({@code ?utm_source=...}, {@code ?fbclid=...}, or deliberate {@code ?x=<random>} floods).
         * By default NOTHING varies — a cached route serves one entry per path regardless of query
         * string. If the page's content depends on a param ({@code ?page=}, {@code ?sort=}),
         * declare it here or stale-wrong responses will be served for it.
         */
        public CachedHandler vary(String... params) {
            this.vary = params.clone();
            Arrays.sort(this.vary); // canonical key order regardless of declaration order
            return this;
        }

        @Override
        public Result apply(Request request) {
            // Cache the rendered response, not the Result object: a RenderedResponse is a
            // serializable snapshot, so a page rendered on one server can be replayed by any other
            // across a shared backend. (Result is eagerly rendered by the time it reaches here —
            // View.of renders at construction — so the snapshot is just its materialized fields.)
            var key = pageKey(request);
            var cached = cache.get(key, RenderedResponse.class);
            if (cached != null) return cached.toResult();
            var rendered = RenderedResponse.from(handler.apply(request));
            cache.setInternal(key, rendered, ttl, tags);
            // Replay the snapshot (not the original Result) so a miss and a hit return the same
            // materialized shape — downstream code sees identical bytes either way.
            return rendered.toResult();
        }

        private String pageKey(Request request) {
            // Vary on HX-Request: a handler may return a partial for htmx and a full page otherwise
            // (the Vary: HX-Request the framework sets), so the two must not share a cache entry.
            var prefix = request.isHtmx() ? "page:hx:" : "page:";
            return prefix + request.method() + ":" + request.path() + queryKey(request);
        }

        /**
         * The query component of the cache key: declared {@link #vary} params only, in sorted
         * order, present-or-absent distinguished. Undeclared params never reach the key — the
         * request side must not control cache cardinality (H8: one full rendered page is stored
         * per distinct key, so attacker-minted keys are a memory-exhaustion vector).
         */
        private String queryKey(Request request) {
            if (vary.length == 0) return "";
            var sb = new StringBuilder();
            for (var param : vary) { // pre-sorted by vary()
                var value = request.queryParam(param);
                if (value == null) continue; // absent param: contributes nothing (≠ empty value)
                sb.append(sb.isEmpty() ? '?' : '&');
                sb.append(percentEncode(param)).append('=').append(percentEncode(value));
            }
            return sb.toString();
        }

        /**
         * Encodes a query parameter for use in cache keys, preventing collisions when
         * values contain separator characters (&amp;, =, %) or non-ASCII text. Standard
         * application/x-www-form-urlencoded via {@link URLEncoder} — UTF-8 byte-wise
         * and therefore injective: distinct values can never encode to the same key.
         * (A hand-rolled {@code %%%02X} formatter here previously emitted 4-hex-digit
         * escapes for non-ASCII chars, which collided with adjacent-character escapes.)
         */
        private static String percentEncode(String value) {
            if (value == null || value.isEmpty()) return value;
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
