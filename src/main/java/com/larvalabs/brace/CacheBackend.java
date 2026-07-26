package com.larvalabs.brace;

import java.time.Duration;

/**
 * Storage SPI behind {@link Cache}. The facade ({@code Cache}) owns stats, TTL parsing, value
 * serialization, and the page-cache wrapper; a backend owns only storage and the operations that
 * must be atomic at the store (counters, tag invalidation, expiry sweep).
 *
 * <p>Two backends ship:
 * <ul>
 *   <li>{@link #inMemory()} — the default. Per-process {@code ConcurrentHashMap}; stores live
 *       objects with <em>zero serialization</em> ({@link #requiresSerialization()} is false), so
 *       existing apps pay nothing. Not consistent across a multi-server deploy.</li>
 *   <li>{@link #postgres(DatabaseFactory)} — shared, durable, cross-server-consistent. Stores
 *       bytes ({@code requiresSerialization()} is true); the facade serializes values via Jackson.</li>
 * </ul>
 *
 * <p><b>Implementing a custom/shared backend</b> (e.g. Redis): return {@code true} from
 * {@link #requiresSerialization()} and implement the byte pair ({@link #getBytes}/{@link #setBytes})
 * plus the value-agnostic ops. The object pair ({@link #getObject}/{@link #setObject}) exists only
 * for the built-in in-memory backend's zero-copy fast path and should be left at its throwing
 * default. See {@code docs/2026-06-04-brace-shared-cache.md}.
 */
public interface CacheBackend {

    /** True when values are stored as bytes (the facade serializes). Shared/custom backends return true. */
    boolean requiresSerialization();

    /**
     * True when the backend is shared across the fleet (every instance reads/writes one store), so
     * invalidation and {@code clear} affect all servers at once. False for the per-process default.
     * Drives ops wording (e.g. that {@code /ops/cache/clear} is fleet-wide).
     */
    default boolean shared() {
        return false;
    }

    // --- Serialized value ops (shared/custom backends implement these) ---

    /** @return stored bytes, or null on miss/expiry. */
    default byte[] getBytes(String key) {
        throw new UnsupportedOperationException("backend does not store bytes");
    }

    /** Upsert a value. {@code ttl} null = no expiry. */
    default void setBytes(String key, byte[] value, Duration ttl, String[] tags) {
        throw new UnsupportedOperationException("backend does not store bytes");
    }

    // --- Live-object value ops (only the built-in in-memory backend implements these) ---

    /** @return the stored live object, or null on miss/expiry. */
    default Object getObject(String key) {
        throw new UnsupportedOperationException("backend does not store live objects");
    }

    /** Upsert a live object. {@code ttl} null = no expiry. */
    default void setObject(String key, Object value, Duration ttl, String[] tags) {
        throw new UnsupportedOperationException("backend does not store live objects");
    }

    /** Result of {@link #getOrCompute}: the value, plus whether it was already cached. */
    record Computed(Object value, boolean hit) {}

    /**
     * Get-or-compute for a live-object backend, backing {@code Cache.getOrSet} (M9).
     *
     * <p>This is on the SPI rather than being a cast to the built-in backend. {@code Cache.getOrSet}
     * used to branch on {@link #requiresSerialization()} and then cast the backend to the concrete
     * {@code InMemoryBackend} — so any third-party non-serializing backend threw
     * {@code ClassCastException} on the cache call the docs recommend most.
     *
     * <p>The default is a plain get / compute / set, which is what the serializing path does. The
     * built-in in-memory backend overrides it to add per-key single-flight, so concurrent callers
     * for the same cold key run the supplier exactly once. That is an optimization, not a contract:
     * an implementation is free to leave the default and let concurrent misses each compute.
     */
    default Computed getOrCompute(String key, Duration ttl, java.util.function.Supplier<?> supplier) {
        Object existing = getObject(key);
        if (existing != null) {
            return new Computed(existing, true);
        }
        Object computed = supplier.get();
        setObject(key, computed, ttl, new String[0]);
        return new Computed(computed, false);
    }

    // --- Value-agnostic ops (every backend implements these) ---

    void delete(String key);

    void deletePrefix(String prefix);

    /**
     * Remove every entry carrying {@code tag}.
     * @return number of entries removed (for the facade's eviction stat).
     */
    long clearTag(String tag);

    /** Remove all entries (and counters). Stats reset stays in the facade. */
    void clear();

    /** Atomic, server-side increment-by-delta. @return the new counter value. */
    long incr(String key, long delta);

    /** Number of live (non-expired) entries. */
    int size();

    /**
     * Remove expired entries. Called by the facade's background sweep; expiry is also enforced on
     * read, so this is space reclamation only.
     * @return number of entries removed (for the facade's eviction stat).
     */
    long evictExpired();

    /** Number of counter keys. In-memory reports the real count; shared backends may report 0. */
    default int counterCount() {
        return 0;
    }

    /** Number of distinct tags. In-memory reports the real count; shared backends may report 0. */
    default int tagCount() {
        return 0;
    }

    static CacheBackend inMemory() {
        return new InMemoryBackend();
    }

    /**
     * In-memory backend with a custom entry cap (default 10,000). The cap is a heap-safety net:
     * past it, inserting a new key first drops expired entries, then arbitrary ones. Counters and
     * tags are not capped.
     */
    static CacheBackend inMemory(int maxEntries) {
        return new InMemoryBackend(maxEntries);
    }

    static CacheBackend postgres(DatabaseFactory factory) {
        return new PostgresBackend(factory);
    }
}
