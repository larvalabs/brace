package com.larvalabs.brace;

import java.net.URLEncoder;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
    // Class<T> to check a stored entry against, and a get with a supertype (Object, an interface)
    // does not pin the class, so either would otherwise bean-deserialize into whatever class name
    // the row carries; anyone able to write the shared store could pick the class. A stored class
    // is read only when it is the exact type requested, in here, a built-in JDK value or container
    // (BUILT_IN_VALUE_CLASSES), or registered with allowType; otherwise it is a miss with a WARN
    // (defense in depth). Process-wide so every Cache in the JVM agrees; filled on write.
    private static final Set<String> STORED_CLASSES = ConcurrentHashMap.newKeySet();

    /** Tests: make this JVM look like a fresh process that has stored nothing. */
    static void forgetStoredClasses() { STORED_CLASSES.clear(); }

    /**
     * JDK classes always accepted as the stored class of a shared-backend entry, whatever this
     * process has stored (L15 final review). Without them a fresh instance (every node after a
     * deploy, or one that only reads) missed on {@code get("denylist", List.class)} for an
     * {@code ArrayList} another instance wrote, so a denylist or revocation set read through its
     * interface failed open, and {@code getOrSet} recomputed and overwrote the shared value.
     *
     * <p>Each is a plain value or container that Jackson rebuilds without calling app code, so a
     * row naming one cannot make Jackson bind an arbitrary class (the threat the stored-class check
     * closes). Elements inside a container carry no type information: they come back as Jackson's
     * natural types ({@code String}, {@code Integer}, {@code LinkedHashMap} for an object), exactly
     * as on the instance that wrote them. The immutable {@code List.of}/{@code Map.of}/
     * {@code Set.of} and {@code Collections.unmodifiable*} classes are stored under their JDK
     * names and read back as {@code ArrayList} or an unmodifiable wrapper. {@code EnumMap},
     * {@code EnumSet} and {@code Collections.unmodifiableCollection} do not round-trip through
     * Jackson at all and are not listed.
     */
    static final Set<String> BUILT_IN_VALUE_CLASSES = Set.of(
        // Containers
        "java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque",
        "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
        "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
        "java.util.ImmutableCollections$List12", "java.util.ImmutableCollections$ListN",
        "java.util.ImmutableCollections$Set12", "java.util.ImmutableCollections$SetN",
        "java.util.ImmutableCollections$Map1", "java.util.ImmutableCollections$MapN",
        "java.util.Arrays$ArrayList",
        "java.util.Collections$UnmodifiableList",
        "java.util.Collections$UnmodifiableRandomAccessList", "java.util.Collections$UnmodifiableSet",
        "java.util.Collections$UnmodifiableSortedSet", "java.util.Collections$UnmodifiableMap",
        "java.util.Collections$UnmodifiableSortedMap",
        "java.util.Collections$EmptyList", "java.util.Collections$EmptySet", "java.util.Collections$EmptyMap",
        "java.util.Collections$SingletonList", "java.util.Collections$SingletonSet", "java.util.Collections$SingletonMap",
        // Values
        "java.lang.String", "java.lang.Boolean", "java.lang.Character", "java.lang.Byte", "java.lang.Short",
        "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
        "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID",
        "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
        "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.ZonedDateTime", "java.time.Duration",
        "java.time.Period", "java.time.Year", "java.time.YearMonth", "java.time.MonthDay",
        "java.time.DayOfWeek", "java.time.Month", "java.time.ZoneOffset");

    /** True if {@code className} is a built-in value class, or an array (of any depth) of one or of a primitive. */
    static boolean isBuiltInValueClass(String className) {
        if (!className.startsWith("[")) return BUILT_IN_VALUE_CLASSES.contains(className);
        String component = className.substring(className.lastIndexOf('[') + 1);
        if (component.length() == 1) return "ZBCSIJFD".contains(component);
        return component.startsWith("L") && component.endsWith(";")
            && BUILT_IN_VALUE_CLASSES.contains(component.substring(1, component.length() - 1));
    }

    // Class names the app registered with allowType, per Cache.
    private final Set<String> allowedTypes = ConcurrentHashMap.newKeySet();

    /**
     * Registers app classes that may be read from a shared backend through a supertype
     * ({@code get(key, Object.class)}, an interface, a base class) or with {@code getOrSet},
     * on an instance that has not itself stored them. <b>Register at startup on every instance</b>
     * (every server of the fleet), next to {@code app.cache(...)}: the instance that reads is
     * often not the one that wrote, and after a deploy no instance has written anything yet.
     *
     * <p>Not needed for a read with the stored class itself ({@code get(key, Rule.class)} for a
     * {@code Rule}), which is always allowed, nor for the JDK containers and values in
     * {@link #BUILT_IN_VALUE_CLASSES}. Pass the concrete class that is stored, not the type it is
     * read as: an interface or abstract class throws {@code IllegalArgumentException}. A class
     * registered here is bound by Jackson from whatever a row names it with, so register only
     * plain data classes (records, POJOs) whose constructors and setters do nothing but assign.
     * The in-memory backend stores live objects and needs none of this.
     */
    public Cache allowType(Class<?>... types) {
        for (Class<?> type : types) {
            if (type.isInterface() || (!type.isArray() && !type.isPrimitive()
                    && java.lang.reflect.Modifier.isAbstract(type.getModifiers()))) {
                throw new IllegalArgumentException("allowType takes the concrete class that is stored, not "
                    + type.getName() + ", which no stored value can be: register the class you pass to set(...)");
            }
            allowedTypes.add(type.getName());
        }
        return this;
    }

    // (stored class, requested type) pairs already warned about; bounded, as the stored class
    // name comes from the row.
    private static final Set<String> WARNED_REFUSALS = ConcurrentHashMap.newKeySet();
    private static final int MAX_WARNED_REFUSALS = 1000;

    static Set<String> warnedRefusals() { return WARNED_REFUSALS; }

    static String refusalMessage(String storedName, String requested) {
        return "cache: an entry stored as " + storedName + " was read as " + requested + " and treated as a miss: "
            + "this instance has not stored that class itself, and it is neither the type requested nor registered. "
            + "Register it at startup on every instance with cache.allowType(" + simpleName(storedName) + ".class), "
            + "or read it with its concrete type. Logged once per stored class and requested type.";
    }

    private static String simpleName(String className) {
        int cut = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        return cut >= 0 ? className.substring(cut + 1) : className;
    }

    private static void warnRefusal(String storedName, String requested) {
        String clipped = storedName.length() > 200 ? storedName.substring(0, 200) : storedName;
        String pair = clipped + " as " + requested;
        if (WARNED_REFUSALS.size() < MAX_WARNED_REFUSALS && WARNED_REFUSALS.add(pair)) {
            Log.warn(refusalMessage(clipped, requested));
        }
    }

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

    /**
     * Prefix of every key the page cache ({@link #wrap}) stores (L4 review F5). The backends treat
     * keys with it as page entries: they are capped on their own and evicted before any other
     * entry, so page-cache growth (cheap for a client to cause) never pushes out the app's own
     * values. It survives hashing ({@link #storageKey} keeps a key's first characters). Use another
     * prefix for the app's own keys.
     */
    static final String PAGE_KEY_PREFIX = "brace:page:";

    static boolean isPageKey(String storedKey) {
        return storedKey != null && storedKey.startsWith(PAGE_KEY_PREFIX);
    }

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
    private <T> T deserialize(byte[] data, Class<T> expected) {
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
        // A class the row names is trusted when this process stored it, it is a built-in JDK value
        // or container, or the app registered it with allowType (L15, L15 final review).
        boolean trusted = STORED_CLASSES.contains(storedName) || isBuiltInValueClass(storedName)
            || allowedTypes.contains(storedName);
        if (expected == null && !trusted) {
            // No caller-supplied type to check against (getOrSet), and the class is not trusted:
            // don't load or bind to a class named by the row. A miss; the caller recomputes and
            // the write registers the class.
            warnRefusal(storedName, "getOrSet (no type)");
            throw new CacheCorruptionException("cached class not trusted by this process: " + storedName);
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
        if (expected != null && stored != expected && !trusted) {
            // A strict subtype of the requested type (Object, an interface, a base class) that is
            // not trusted: the row, not the caller, would choose the class Jackson binds (L15).
            // Only the exact type the caller named, or a trusted class, is read. A WARN rather than
            // a silent miss (L15 final review): a security read through an interface failing open
            // must be visible.
            warnRefusal(storedName, expected.getName());
            throw new CacheCorruptionException("cached class not trusted by this process: " + storedName);
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
         * declare it here or stale-wrong responses will be served for it. A param given more than
         * once ({@code ?t=a&t=b}) is keyed on all its values, in order (L4 review F6).
         *
         * <p>A cached handler must depend only on the route's path parameters and what it declares
         * here and in {@link #varyHeader}. In dev mode and under {@code Brace.test()} a read of
         * anything else through the {@code Request} is reported (logged once per route in dev
         * mode, thrown in tests).
         */
        public CachedHandler vary(String... params) {
            this.vary = params.clone();
            Arrays.sort(this.vary); // canonical key order regardless of declaration order
            return this;
        }

        /**
         * Declares request headers that key the cache, as {@link #vary} does for query params: a
         * page that differs by {@code Accept-Language}, or an htmx endpoint that answers per
         * {@code HX-Target}. Names are case-insensitive; an absent header and an empty one are
         * different keys. Declaring one of the headers that otherwise make a request bypass the
         * cache ({@code Authorization}, {@code HX-Target}, {@code HX-Trigger},
         * {@code HX-Trigger-Name}, {@code HX-Boosted}, {@code HX-Prompt}) keys on it instead.
         * {@code HX-Request} is always part of the key.
         */
        public CachedHandler varyHeader(String... names) {
            var sorted = new java.util.TreeSet<String>();
            for (var name : names) sorted.add(name.toLowerCase(java.util.Locale.ROOT));
            this.varyHeaders = sorted.toArray(new String[0]);
            return this;
        }

        private String[] varyHeaders = new String[0];

        /**
         * Headers that make a request skip the cache, unless declared with {@link #varyHeader}
         * (L4 review F6): credentials, and the htmx headers that say which element asked and how,
         * which a handler commonly answers per value. Such a request is served by the handler and
         * its response is not stored.
         */
        static final List<String> BYPASS_HEADERS = List.of(
            "Authorization", "HX-Target", "HX-Trigger", "HX-Trigger-Name", "HX-Boosted", "HX-Prompt");

        /** Reads the framework itself makes, or that the key already covers. */
        private static final Set<String> ALWAYS_KEYED_HEADERS = Set.of("hx-request");

        boolean warnedUndeclaredReads() { return !warnedReads.isEmpty(); }

        @Override
        public Result apply(Request request) {
            // L4 review F6: a request carrying a per-request header the key does not cover is
            // served by the handler and nothing is stored.
            if (bypassesCache(request)) {
                return handler.apply(request);
            }
            // Cache the rendered response, not the Result object: a RenderedResponse is a
            // serializable snapshot, so a page rendered on one server can be replayed by any other
            // across a shared backend. RenderedResponse.from reads result.body(), which materializes a
            // deferred View render (M12) right here — fine, since cached routes are plain no-DB Handlers,
            // so no connection is held to begin with.
            var key = pageKey(request);
            var cached = cache.get(key, RenderedResponse.class);
            if (cached != null) return cached.toResult();
            var views = new ArrayList<View>();
            // L4 review F6: in dev mode and tests, record what the handler reads (nothing in production).
            Request.InputReads reads = request.strictInputChecks() ? new Request.InputReads() : null;
            Result result;
            request.recordReads(reads);
            try {
                result = View.recordingViews(views, () -> handler.apply(request));
                if (reads != null && !result.isStreaming()) result.rawBytes(); // run a deferred render while recording
            } finally {
                request.recordReads(null);
            }
            if (reads != null) reportUndeclaredReads(request, reads);
            if (!result.setCookies().isEmpty()) {
                // L4 review F6: the snapshot keeps headers but not Set-Cookie, so the cookie was
                // dropped even from this response, and a per-visitor cookie must not be replayed
                // anyway. Served uncached, intact.
                warnSetCookie(request);
                return result;
            }
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
            var prefix = request.isHtmx() ? PAGE_KEY_PREFIX + "hx:" : PAGE_KEY_PREFIX;
            // A HEAD reaches a GET route's handler and its response differs only in the body the
            // framework drops on the way out, so it shares the GET's entry rather than storing a
            // second copy of the page.
            var method = request.method().equals("HEAD") ? "GET" : request.method();
            return prefix + method + ":" + pathKey(request) + queryKey(request) + headerKey(request);
        }

        private boolean bypassesCache(Request request) {
            for (var name : BYPASS_HEADERS) {
                if (request.hasHeader(name) && Arrays.binarySearch(varyHeaders, name.toLowerCase(java.util.Locale.ROOT)) < 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The header component of the key: each {@link #varyHeader} in sorted order, present or
         * absent distinguished. Starts with {@code '#'}, which {@link #percentEncode} never emits,
         * so it cannot be confused with the path or query components.
         */
        private String headerKey(Request request) {
            if (varyHeaders.length == 0) return "";
            var sb = new StringBuilder();
            for (var name : varyHeaders) {
                var value = request.hasHeader(name) ? request.header(name) : null;
                if (value == null) continue;
                sb.append(sb.isEmpty() ? '#' : '&').append(percentEncode(name)).append('=').append(percentEncode(value));
            }
            return sb.toString();
        }

        // Routes (or request paths, for hand-built requests) already warned about, dev mode only.
        private final Set<String> warnedReads = ConcurrentHashMap.newKeySet();
        private final Set<String> warnedCookies = ConcurrentHashMap.newKeySet();

        private static String routeLabel(Request request) {
            var route = request.route();
            return route != null ? route.method() + " " + route.pattern() : request.method() + " " + request.path();
        }

        /**
         * L4 review F6: the handler read request input the cache key does not cover, so the page
         * it stored for this key may be wrong (or attacker-chosen) for the next visitor. Dev mode
         * logs it once per route; {@code Brace.test()} throws, so the test fails.
         */
        private void reportUndeclaredReads(Request request, Request.InputReads reads) {
            var undeclared = new ArrayList<String>();
            for (var name : reads.query) {
                if (name.equals("*")) undeclared.add("the whole query string (queryParams(), url(), urlWith() or form())");
                else if (Arrays.binarySearch(vary, name) < 0) undeclared.add("query param '" + name + "'");
            }
            for (var name : reads.headers) {
                var lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!ALWAYS_KEYED_HEADERS.contains(lower) && Arrays.binarySearch(varyHeaders, lower) < 0) {
                    undeclared.add("header '" + name + "'");
                }
            }
            for (var name : reads.cookies) undeclared.add("cookie '" + name + "'");
            if (reads.ip) undeclared.add("the client address (req.ip())");
            if (reads.body) undeclared.add("the request body");
            if (undeclared.isEmpty()) return;
            var label = routeLabel(request);
            var message = "Page-cached route " + label + " read " + String.join(", ", undeclared)
                + ", which is not part of its cache key, so the page stored for one visitor is served to "
                + "the next whatever they send. A cached handler may depend only on its path parameters and "
                + "what it declares: .vary(\"param\") for a query param, .varyHeader(\"Name\") for a header. "
                + "Anything else (cookies, the client address, the body) means the route should not be page cached.";
            if (!"dev".equals(System.getProperty("brace.mode"))) {
                throw new IllegalStateException(message + " (Thrown under Brace.test(); logged once per route in dev mode.)");
            }
            if (warnedReads.size() < 100 && warnedReads.add(label)) {
                Log.warn(message + " Logged once per route.");
            }
        }

        private void warnSetCookie(Request request) {
            if (!"dev".equals(System.getProperty("brace.mode"))) return;
            var label = routeLabel(request);
            if (warnedCookies.size() < 100 && warnedCookies.add(label)) {
                Log.warn("Page-cached route " + label + " set a cookie, so its response was not cached (a "
                    + "cookie belongs to one visitor). Set cookies on an uncached route, or drop the cache wrapper.");
            }
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
         * per distinct key, so attacker-minted keys are a memory-exhaustion vector). A repeated
         * param contributes every value in order (L4 review F6: keying on the last value let
         * {@code ?t=evil&t=ok} fill the entry for {@code ?t=ok}).
         */
        private String queryKey(Request request) {
            if (vary.length == 0) return "";
            var sb = new StringBuilder();
            for (var param : vary) { // pre-sorted by vary()
                for (var value : request.queryValues(param)) { // absent param: contributes nothing
                    sb.append(sb.isEmpty() ? '?' : '&');
                    sb.append(percentEncode(param)).append('=').append(percentEncode(value));
                }
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
