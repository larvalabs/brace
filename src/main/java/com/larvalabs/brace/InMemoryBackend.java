package com.larvalabs.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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

    static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> tagIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    // M15: in-flight single-flight computations, keyed by cache key. The supplier runs while this
    // future is the placeholder — NOT inside a store map operation — so a blocking supplier holds no
    // ConcurrentHashMap bin lock.
    private final ConcurrentHashMap<String, CompletableFuture<Entry>> inFlight = new ConcurrentHashMap<>();
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
     * Single-flight compute-if-absent on the live-object store: concurrent callers for the same cold
     * key see exactly one supplier run, and the per-server single-flight guarantee the shared-backend
     * path deliberately drops is preserved.
     *
     * <p>M15: unlike the old {@link ConcurrentHashMap#compute} version, the supplier runs OUTSIDE any
     * map operation, gated by a per-key {@link CompletableFuture} placeholder in {@link #inFlight}. A
     * blocking supplier (typically a DB query) therefore holds no {@code ConcurrentHashMap} bin lock,
     * so it neither pins a virtual thread's carrier (JDK 21–24, JEP 491) nor stalls unrelated keys that
     * hash to the same bin. Single-flight is now per exact key rather than per bin.
     *
     * <p>Failure semantics: if the supplier throws, nothing is cached, the in-flight slot is cleared,
     * and the exception propagates to every caller currently awaiting this key (each retries on its
     * next call). The non-concurrent case is identical to before — throw, cache nothing, retry next time.
     */
    @Override
    public Computed getOrCompute(String key, Duration ttl, Supplier<?> supplier) {
        var current = store.get(key);
        if (current != null && !current.expired()) {
            return new Computed(current.value(), true);
        }

        var myFuture = new CompletableFuture<Entry>();
        var existing = inFlight.putIfAbsent(key, myFuture);
        if (existing != null) {
            // Another caller is already computing this key — await its result (shared single-flight).
            return new Computed(await(existing).value(), true);
        }

        // We won the race: this thread runs the supplier, others await myFuture.
        try {
            // Re-check the store — a previous leader may have stored between our get() above and our
            // winning putIfAbsent. (Its inFlight slot is gone, so we wouldn't have seen its future.)
            var fresh = store.get(key);
            if (fresh != null && !fresh.expired()) {
                myFuture.complete(fresh);
                return new Computed(fresh.value(), true);
            }
            var expiry = ttl == null ? null : Instant.now().plus(ttl);
            var entry = new Entry(supplier.get(), expiry, new String[0]);
            ensureCapacity(key);
            store.put(key, entry);
            myFuture.complete(entry);
            return new Computed(entry.value(), false);
        } catch (RuntimeException | Error e) {
            myFuture.completeExceptionally(e); // wake awaiters with the failure, not a hang
            throw e;
        } finally {
            inFlight.remove(key, myFuture);
        }
    }

    /** Awaits an in-flight computation, unwrapping {@link CompletionException} so an awaiting caller
     *  sees the supplier's original exception (as the leader does), not a wrapped one. */
    private static Entry await(CompletableFuture<Entry> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
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
