package com.larvalabs.brace;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * live objects, so it has neither cost nor restriction.
 */
public class Cache {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String[] NO_TAGS = new String[0];

    private final CacheBackend backend;
    private final boolean serializes;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    public Cache() {
        this(new InMemoryBackend());
    }

    public Cache(CacheBackend backend) {
        this.backend = backend;
        this.serializes = backend.requiresSerialization();
        Thread.ofVirtual().name("cache-cleanup").start(() -> {
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

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        if (serializes) {
            byte[] bytes = backend.getBytes(key);
            if (bytes == null) {
                misses.increment();
                return null;
            }
            hits.increment();
            return deserialize(bytes, type);
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
            var computed = ((InMemoryBackend) backend).getOrCompute(key, parseTtl(ttl), supplier);
            if (computed.hit()) hits.increment(); else misses.increment();
            return (T) computed.value();
        }
        byte[] bytes = backend.getBytes(key);
        if (bytes != null) {
            hits.increment();
            return deserialize(bytes, null);
        }
        misses.increment();
        T value = supplier.get();
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

    public int size() { return backend.size(); }
    public int counterCount() { return backend.counterCount(); }
    public int tagCount() { return backend.tagCount(); }
    public long hits() { return hits.sum(); }
    public long misses() { return misses.sum(); }
    public long evictions() { return evictions.sum(); }
    public long drainHits() { return hits.sumThenReset(); }
    public long drainMisses() { return misses.sumThenReset(); }
    public long drainEvictions() { return evictions.sumThenReset(); }

    /** True when the backend serializes values (i.e. a shared backend is configured). */
    boolean serializes() { return serializes; }

    // Route-level page caching

    public CachedHandler wrap(String ttl, Handler handler) {
        return new CachedHandler(this, ttl, handler);
    }

    // Internal

    private void store(String key, Object value, Duration ttl, String[] tags) {
        if (serializes) {
            backend.setBytes(key, serialize(value), ttl, tags);
        } else {
            backend.setObject(key, value, ttl, tags);
        }
    }

    void setInternal(String key, Object value, String ttl, String[] tags) {
        store(key, value, parseTtl(ttl), tags);
    }

    private static byte[] serialize(Object value) {
        try {
            byte[] className = value.getClass().getName().getBytes(StandardCharsets.UTF_8);
            byte[] body = MAPPER.writeValueAsBytes(value);
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
        var buffer = ByteBuffer.wrap(data);
        int len = buffer.getInt();
        byte[] className = new byte[len];
        buffer.get(className);
        int offset = 4 + len;
        String storedName = new String(className, StandardCharsets.UTF_8);
        try {
            Class<?> stored = Class.forName(storedName);
            if (expected != null && !expected.isAssignableFrom(stored)) {
                throw new ClassCastException("Cached value is a " + storedName
                        + " but get(...) requested " + expected.getName());
            }
            return (T) MAPPER.readValue(data, offset, data.length - offset, stored);
        } catch (ClassNotFoundException | java.io.IOException e) {
            throw new RuntimeException("Cache value deserialization failed for " + storedName, e);
        }
    }

    private static final Pattern TTL_PATTERN = Pattern.compile("(\\d+)([smhd])");

    static Duration parseTtl(String ttl) {
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

        CachedHandler(Cache cache, String ttl, Handler handler) {
            this.cache = cache;
            this.ttl = ttl;
            this.handler = handler;
        }

        public CachedHandler tags(String... tags) {
            this.tags = tags;
            return this;
        }

        @Override
        public Result apply(Request request) {
            // Page caching of the Result object only works on the in-memory (object) backend.
            // Shared-backend page caching (caching the rendered response) lands in Phase 2; until
            // then, a serializing backend bypasses the cache rather than fail on Result.
            if (cache.serializes()) {
                return handler.apply(request);
            }
            var key = "page:" + request.method() + ":" + request.path() + queryKey(request);
            var cached = cache.get(key, Result.class);
            if (cached != null) return cached;
            var result = handler.apply(request);
            cache.setInternal(key, result, ttl, tags);
            return result;
        }

        private String queryKey(Request request) {
            var params = request.queryParams();
            if (params.isEmpty()) return "";
            var sb = new StringBuilder("?");
            params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    if (sb.length() > 1) sb.append("&");
                    sb.append(e.getKey()).append("=").append(e.getValue());
                });
            return sb.toString();
        }
    }
}
