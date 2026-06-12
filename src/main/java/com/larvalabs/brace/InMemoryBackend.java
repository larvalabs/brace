package com.larvalabs.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Default {@link CacheBackend}: per-process {@code ConcurrentHashMap} storing live objects with no
 * serialization. This is today's {@code Cache} behavior, extracted unchanged behind the SPI — so
 * apps that never opt into a shared backend pay nothing. Not consistent across a multi-server
 * deploy (each instance keeps its own copy of every entry, counter, and tag index).
 */
class InMemoryBackend implements CacheBackend {

    private record Entry(Object value, Instant expiry, String[] tags) {
        boolean expired() {
            return expiry != null && Instant.now().isAfter(expiry);
        }
    }

    /** Result of {@link #getOrCompute}: the value plus whether it was already cached. */
    record Computed(Object value, boolean hit) {}

    static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> tagIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final AtomicLong capacityEvictions = new AtomicLong();

    InMemoryBackend() {
        this(DEFAULT_MAX_ENTRIES);
    }

    InMemoryBackend(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * Entry-count safety net (perf review H8), not an LRU: before inserting a NEW key into a full
     * store, drop expired entries, then — still full — arbitrary ones ({@code ConcurrentHashMap}
     * iteration order), until there's room. Sized so a runaway keyspace degrades the hit rate
     * instead of exhausting the heap. The bound is approximate under concurrent inserts (racing
     * writers may briefly overshoot by a few entries); counters and the tag index are not capped.
     */
    private void ensureCapacity(String newKey) {
        if (store.size() < maxEntries || store.containsKey(newKey)) {
            return;
        }
        sweepExpired();
        var it = store.keySet().iterator();
        while (store.size() >= maxEntries && it.hasNext()) {
            remove(it.next());
            capacityEvictions.incrementAndGet();
        }
    }

    @Override
    public boolean requiresSerialization() {
        return false;
    }

    @Override
    public Object getObject(String key) {
        var entry = store.get(key);
        if (entry == null) return null;
        if (entry.expired()) {
            remove(key);
            return null;
        }
        return entry.value();
    }

    @Override
    public void setObject(String key, Object value, Duration ttl, String[] tags) {
        ensureCapacity(key);
        var expiry = ttl == null ? null : Instant.now().plus(ttl);
        store.put(key, new Entry(value, expiry, tags));
        for (var tag : tags) {
            tagIndex.computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    /**
     * Single-flight compute-if-absent on the live-object store: concurrent callers for the same
     * cold key see exactly one supplier run (via {@link ConcurrentHashMap#compute}). This is the
     * per-server single-flight guarantee the shared-backend path deliberately drops.
     */
    Computed getOrCompute(String key, Duration ttl, Supplier<?> supplier) {
        var current = store.get(key);
        if (current != null && !current.expired()) {
            return new Computed(current.value(), true);
        }
        ensureCapacity(key);
        boolean[] computed = {false};
        var entry = store.compute(key, (k, existing) -> {
            if (existing != null && !existing.expired()) return existing;
            computed[0] = true;
            var expiry = ttl == null ? null : Instant.now().plus(ttl);
            return new Entry(supplier.get(), expiry, new String[0]);
        });
        return new Computed(entry.value(), !computed[0]);
    }

    @Override
    public void delete(String key) {
        remove(key);
    }

    @Override
    public void deletePrefix(String prefix) {
        for (var key : store.keySet()) {
            if (key.startsWith(prefix)) {
                remove(key);
            }
        }
    }

    @Override
    public long clearTag(String tag) {
        var keys = tagIndex.remove(tag);
        if (keys == null) return 0;
        long removed = 0;
        for (var key : keys) {
            if (store.remove(key) != null) removed++;
        }
        return removed;
    }

    @Override
    public void clear() {
        store.clear();
        tagIndex.clear();
        counters.clear();
    }

    @Override
    public long incr(String key, long delta) {
        return counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public long evictExpired() {
        // Fold in capacity evictions since the last sweep so the facade's eviction stat sees them.
        return sweepExpired() + capacityEvictions.getAndSet(0);
    }

    private long sweepExpired() {
        var now = Instant.now();
        long removed = 0;
        for (var entry : store.entrySet()) {
            if (entry.getValue().expiry() != null && now.isAfter(entry.getValue().expiry())) {
                remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int counterCount() {
        return counters.size();
    }

    @Override
    public int tagCount() {
        return tagIndex.size();
    }

    private void remove(String key) {
        var entry = store.remove(key);
        if (entry != null && entry.tags().length > 0) {
            for (var tag : entry.tags()) {
                var keys = tagIndex.get(tag);
                if (keys != null) keys.remove(key);
            }
        }
    }
}
