package com.larvalabs.brace;

import java.net.URLEncoder;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    // Value classes this process has itself serialized into a shared backend. getOrSet has no
    // Class<T> to check a stored entry against, so it would otherwise bean-deserialize into
    // whatever class name the row carries; anyone able to write the shared store could pick the
    // class. A stored class not in here is treated as a miss instead (defense in depth). Process-
    // wide so every Cache in the JVM agrees; filled on write, so a fresh instance misses once,
    // recomputes, writes, and from then on reads its own class normally.
    private static final Set<String> STORED_CLASSES = ConcurrentHashMap.newKeySet();

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

    /**
     * A key longer than this many UTF-8 bytes is stored hashed (L5). Postgres refuses a btree entry
     * over about 2.7 KB, so a page path or {@code vary} value near Jetty's 8 KB URI limit failed the
     * insert; 512 keeps every stored key well clear of that on both backends.
     */
    static final int MAX_KEY_BYTES = 512;

    /** How much of a hashed key is kept readable in front of the digest, for prefix clears. */
    static final int RETAINED_PREFIX_CHARS = 128;

    /**
     * Separates a hashed key's retained prefix from its digest. A key that itself contains this is
     * hashed too, so a stored hashed form can never be produced by passing some other key raw.
     */
    static final String HASHED_KEY_MARKER = "#sha256:";

    /**
     * The key as the backend stores it (L5). A key with an ISO control character (Postgres refuses
     * a NUL in a text parameter), longer than {@link #MAX_KEY_BYTES} UTF-8 bytes, or containing
     * {@link #HASHED_KEY_MARKER} becomes its leading characters, up to the first of those
     * characters or {@link #RETAINED_PREFIX_CHARS}, then the marker, then the SHA-256 hex digest of
     * the whole key. Applied in this facade for every key and tag, so both backends store the same
     * thing, the mapping is invisible to callers, and {@link #deletePrefix} still reaches a hashed
     * key through its retained prefix.
     */
    static String storageKey(String key) {
        if (key == null) return null;
        if (!hasControlCharacter(key) && !hasUnpairedSurrogate(key) && !key.contains(HASHED_KEY_MARKER)
                && !exceedsKeyBytes(key)) {
            return key;
        }
        return key.substring(0, retainedLength(key)) + HASHED_KEY_MARKER + sha256Hex(key);
    }

    /**
     * The part of {@code prefix} a hashed key starting with it keeps readable: {@code prefix} cut
     * at its first control character, unpaired surrogate or {@link #HASHED_KEY_MARKER}, or at
     * {@link #RETAINED_PREFIX_CHARS} characters. Equal to {@code prefix} when nothing had to be cut.
     */
    static String storagePrefix(String prefix) {
        if (prefix == null) return null;
        int cut = retainedLength(prefix);
        if (cut == RETAINED_PREFIX_CHARS && Character.isHighSurrogate(prefix.charAt(cut - 1))) {
            cut--; // a key continuing this prefix had its cut moved back off the surrogate pair
        }
        return prefix.substring(0, cut);
    }

    /**
     * True if {@code s} has a surrogate that is not half of a pair (L5 review F11). pgjdbc sends
     * one as {@code ?}, so two such keys that differ only there were one row on Postgres.
     */
    static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++;
                    continue;
                }
                return true;
            }
            if (Character.isLowSurrogate(c)) return true;
        }
        return false;
    }

    /**
     * Length of the part of {@code s} kept in front of a digest: up to its first control
     * character, its first unpaired surrogate, its first {@link #HASHED_KEY_MARKER}, or
     * {@link #RETAINED_PREFIX_CHARS} characters, whichever comes first, never splitting a
     * surrogate pair.
     */
    private static int retainedLength(String s) {
        int limit = Math.min(s.length(), RETAINED_PREFIX_CHARS);
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++; // a pair; a cut between its halves is moved back below
                continue;
            }
            if (Character.isISOControl(c) || Character.isSurrogate(c)) {
                limit = i;
                break;
            }
        }
        int marker = s.indexOf(HASHED_KEY_MARKER);
        if (marker >= 0 && marker < limit) {
            limit = marker;
        }
        if (limit > 0 && limit < s.length()
                && Character.isHighSurrogate(s.charAt(limit - 1)) && Character.isLowSurrogate(s.charAt(limit))) {
            limit--;
        }
        return limit;
    }

    private static boolean hasControlCharacter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) return true;
        }
        return false;
    }

    private static boolean exceedsKeyBytes(String s) {
        if (s.length() * 3 <= MAX_KEY_BYTES) return false; // fast path: at most 3 UTF-8 bytes per char
        int bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) bytes += 1;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c)) { bytes += 4; i++; }
            else bytes += 3;
            if (bytes > MAX_KEY_BYTES) return true;
        }
        return false;
    }

    private static String sha256Hex(String s) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(digestInput(s)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /**
     * The bytes a key is hashed from: its UTF-8 encoding, which is injective for well-formed text.
     * UTF-8 encoding replaces an unpaired surrogate with {@code ?} (so {@code "a\uD800"} and
     * {@code "a\uD801"} would hash alike), so such a key is hashed from a 0xFF byte, which never
     * occurs in UTF-8, followed by its UTF-16 code units (L5 review F11).
     */
    static byte[] digestInput(String s) {
        if (!hasUnpairedSurrogate(s)) return s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + 2 * s.length()];
        out[0] = (byte) 0xFF;
        for (int i = 0; i < s.length(); i++) {
            out[1 + 2 * i] = (byte) (s.charAt(i) >>> 8);
            out[2 + 2 * i] = (byte) s.charAt(i);
        }
        return out;
    }

    private static String[] storageTags(String[] tags) {
        if (tags == null || tags.length == 0) return tags;
        String[] out = null;
        for (int i = 0; i < tags.length; i++) {
            String stored = storageKey(tags[i]);
            if (stored != tags[i]) {
                if (out == null) out = tags.clone();
                out[i] = stored;
            }
        }
        return out == null ? tags : out;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        key = storageKey(key);
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
        String original = key;
        key = storageKey(key);
        if (!serializes) {
            // M9: through the SPI, not a cast to the built-in backend. getOrSet is the cache call
            // the docs recommend most, and casting here meant any third-party non-serializing
            // backend hit a ClassCastException on it.
            var computed = backend.getOrCompute(
                    key, parseTtl(ttl), () -> requireValue(original, supplier.get()));
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
        T value = requireValue(original, supplier.get());
        backend.setBytes(key, serialize(value), parseTtl(ttl), NO_TAGS);
        return value;
    }

    public void delete(String key) {
        backend.delete(storageKey(key));
    }

    /**
     * Delete every entry whose key starts with {@code prefix}.
     *
     * <p>A key that is stored hashed (one with a control character, an unpaired surrogate or
     * {@link #HASHED_KEY_MARKER}, or over {@value #MAX_KEY_BYTES} UTF-8 bytes) keeps only its
     * first characters readable, so a prefix that reaches past them cannot be matched exactly.
     * For such a prefix (one with any of those characters, or longer than
     * {@value #RETAINED_PREFIX_CHARS} characters) this deletes the keys stored as-is that start
     * with the whole prefix, and the hashed keys whose readable part is exactly the prefix's
     * readable part, which every hashed key starting with the prefix has (L5 review F7). What can
     * still be cleared beyond the prefix: hashed keys that share that readable part without
     * continuing the prefix. For example {@code deletePrefix("search:\u0001")} also clears
     * {@code "search:\u0002"} and {@code "search:#sha256:x"}, never {@code "search:alpha"}; a
     * 200-character prefix also clears hashed keys that share its first 128 characters. A prefix
     * that ends in the first half of a surrogate pair clears every key starting with the rest of
     * it. Over-deletion is a cache miss, never a stale entry.
     */
    public void deletePrefix(String prefix) {
        if (prefix == null) {
            backend.deletePrefix(null);
            return;
        }
        if (!prefix.isEmpty() && Character.isHighSurrogate(prefix.charAt(prefix.length() - 1))) {
            // A key can complete the pair, so its stored form may hold a character the prefix
            // cannot express (pgjdbc would send the lone half as "?"): clear by the rest.
            deletePrefix(prefix.substring(0, prefix.length() - 1));
            return;
        }
        String readable = storagePrefix(prefix);
        if (readable.length() == prefix.length()) {
            backend.deletePrefix(prefix); // matches plain keys and hashed ones' readable parts alike
            return;
        }
        backend.deletePrefix(readable + HASHED_KEY_MARKER);
        if (!hasControlCharacter(prefix) && !hasUnpairedSurrogate(prefix) && !prefix.contains(HASHED_KEY_MARKER)) {
            backend.deletePrefix(prefix); // cut only for length: keys stored as-is may still start with it
        }
    }

    public void clear() {
        backend.clear();
        hits.reset();
        misses.reset();
        evictions.reset();
    }

    public long incr(String key) {
        return backend.incr(storageKey(key), 1);
    }

    public long decr(String key) {
        return backend.incr(storageKey(key), -1);
    }

    public void clearTag(String tag) {
        evictions.add(backend.clearTag(storageKey(tag)));
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
    public record RenderedResponse(int status, String contentType, Map<String, String> headers, byte[] body,
                                   boolean untrusted) {
        public RenderedResponse(int status, String contentType, Map<String, String> headers, byte[] body) {
            this(status, contentType, headers, body, false);
        }

        static RenderedResponse from(Result result) {
            if (result.isStreaming()) {
                // Silently caching it would store an empty body and then serve that empty body to
                // every subsequent request — a page cache turning a working download into a 0-byte
                // one, with nothing in the logs. Fail at the point the mistake is made instead.
                throw new IllegalStateException(
                    "A streaming response cannot be page cached: its body is never materialized, so "
                        + "there is nothing to store. Cache the underlying data and build the "
                        + "response per request, or drop the cache wrapper from this route.");
            }
            byte[] body;
            if (result.rawBytes() != null) {
                body = result.rawBytes();
            } else if (result.body() != null) {
                body = result.body().getBytes(StandardCharsets.UTF_8);
            } else {
                body = new byte[0];
            }
            return new RenderedResponse(result.status(), result.contentType(),
                    new LinkedHashMap<>(result.headers()), body, result.untrusted);
        }

        Result toResult() {
            var result = Result.raw(status, contentType, body, headers);
            result.untrusted = untrusted; // M7: replays enforce the untrusted headers too
            return result;
        }

        /** Body and header values as one string, for View.sessionDerived's token search (M1). */
        String textForTokenScan() {
            var text = new StringBuilder(new String(body, StandardCharsets.UTF_8));
            for (String value : headers.values()) text.append('\n').append(value);
            return text.toString();
        }
    }

    // Internal

    private void store(String key, Object value, Duration ttl, String[] tags) {
        requireValue(key, value);
        key = storageKey(key);
        tags = storageTags(tags);
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
            byte[] data = ByteBuffer.allocate(4 + className.length + body.length)
                    .putInt(className.length).put(className).put(body).array();
            STORED_CLASSES.add(value.getClass().getName());
            return data;
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
        if (expected == null && !STORED_CLASSES.contains(storedName)) {
            // No caller-supplied type to check against (getOrSet), and this process never stored
            // this class: don't load or bind to a class named by the row. A miss; the caller
            // recomputes and the write registers the class.
            Log.debug("cache entry names a class this process has not stored; treating as a miss",
                    Map.of("class", storedName.length() > 200 ? storedName.substring(0, 200) : storedName));
            throw new CacheCorruptionException("cached class not stored by this process: " + storedName);
        }
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
            // across a shared backend. RenderedResponse.from reads result.body(), which materializes a
            // deferred View render (M12) right here — fine, since cached routes are plain no-DB Handlers,
            // so no connection is held to begin with.
            var key = pageKey(request);
            var cached = cache.get(key, RenderedResponse.class);
            if (cached != null) return cached.toResult();
            var views = new ArrayList<View>();
            var result = View.recordingViews(views, () -> handler.apply(request));
            if (result.status() < 200 || result.status() > 299) {
                // L4: only a success is the page. An error, a redirect or a 304 depends on this
                // request (a query param the key ignores, a missing record, a login bounce), and
                // storing it would replay it to every visitor for the TTL. Served uncached, as is.
                return result;
            }
            var rendered = RenderedResponse.from(result);
            if (!views.isEmpty() && View.sessionDerived(views, rendered.textForTokenScan())) {
                // M1: this response carries one visitor's CSRF token and/or pending flash. Storing it
                // would replay them to every other visitor, so serve it uncached.
                warnSessionDerived(request.path());
                return rendered.toResult();
            }
            cache.setInternal(key, rendered, ttl, tags);
            // Replay the snapshot (not the original Result) so a miss and a hit return the same
            // materialized shape — downstream code sees identical bytes either way.
            return rendered.toResult();
        }

        // Request paths already warned about (dev mode only), so a hot route logs once, not per request.
        private final Set<String> warnedPaths = ConcurrentHashMap.newKeySet();

        private void warnSessionDerived(String path) {
            if (!"dev".equals(System.getProperty("brace.mode"))) return;
            // Bounded: the path is request-controlled on parameterized routes.
            if (warnedPaths.size() < 100 && warnedPaths.add(path)) {
                Log.warn("page cache skipped " + path + ": the render includes a CSRF token or flash message, "
                    + "which are per-visitor. Cache pages without forms or flash, or drop the cache wrapper.");
            }
        }

        private String pageKey(Request request) {
            // Vary on HX-Request: a handler may return a partial for htmx and a full page otherwise
            // (the Vary: HX-Request the framework sets), so the two must not share a cache entry.
            var prefix = request.isHtmx() ? "page:hx:" : "page:";
            // A HEAD reaches a GET route's handler and its response differs only in the body the
            // framework drops on the way out, so it shares the GET's entry rather than storing a
            // second copy of the page.
            var method = request.method().equals("HEAD") ? "GET" : request.method();
            return prefix + method + ":" + pathKey(request) + queryKey(request);
        }

        /**
         * The path component of the key (L4): the matched route's pattern plus its decoded path
         * parameters, in pattern order, each URL-encoded. Keying on the raw path made
         * {@code /posts/hello} and {@code /posts/hell%6f} two entries for one page, so one route
         * could mint unlimited entries. Encoding keeps it injective: no parameter value can spell
         * a separator, another parameter or a query component, and the pattern itself names the
         * route, so two routes never share a key. A request built by hand (no matched route)
         * keys on its raw path, as before.
         */
        private static String pathKey(Request request) {
            var route = request.route();
            if (route == null) {
                return "path:" + request.path();
            }
            var sb = new StringBuilder("route:").append(percentEncode(route.pattern()));
            for (var name : route.paramNames()) {
                var value = request.pathParam(name);
                sb.append('/').append(value == null ? "" : percentEncode(value));
            }
            return sb.toString();
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
