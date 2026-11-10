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

    private static final class Entry {
        private final Object value;
        private final Instant expiry;
        private final String[] tags;
        // Set on every hit; cleared as the page clock passes (L4 final review). Only page entries
        // are on the clock, so for app entries it is just an unread flag.
        volatile boolean referenced;

        Entry(Object value, Instant expiry, String[] tags) {
            this.value = value;
            this.expiry = expiry;
            this.tags = tags;
        }

        Object value() { return value; }
        Instant expiry() { return expiry; }
        String[] tags() { return tags; }

        boolean expired() {
            return expiry != null && Instant.now().isAfter(expiry);
        }

        Object hit() {
            if (!referenced) referenced = true; // read first: no write traffic on a hot entry
            return value;
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

    // Page entries (Cache.isPageKey) on a CLOCK (second-chance) ring, oldest first (L4 final
    // review). A key may appear more than once or after its entry is gone; both are skipped when
    // the hand reaches them, and the ring is rebuilt when such leftovers dominate it.
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pageClock = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger pageClockSize = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pageCount = new java.util.concurrent.atomic.AtomicInteger();
    // Inline expiry sweeps on a full store, at most one a second: the background sweep runs every
    // 30 s, and a sweep per insert into a store full of live entries was O(n) per request.
    private static final long INLINE_SWEEP_INTERVAL_NANOS = 1_000_000_000L;
    private final AtomicLong lastInlineSweep = new AtomicLong(System.nanoTime() - INLINE_SWEEP_INTERVAL_NANOS);

    InMemoryBackend() {
        this(DEFAULT_MAX_ENTRIES);
    }

    InMemoryBackend(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * Entry-count safety net (perf review H8): before inserting a NEW key into a full store, drop
     * expired entries (at most once a second), then evict a cold page-cache entry
     * ({@link Cache#isPageKey}), and only for a new app entry with no page left, an arbitrary one.
     * Page entries go first (L4 review F5): a client can mint them cheaply (any {@code vary} value
     * or path parameter), and they must never push out the app's own entries.
     *
     * <p>Among pages the victim is chosen by CLOCK, an approximate LRU (L4 final review): the hand
     * walks pages oldest first, gives one that was hit since it last passed a second chance, and
     * evicts the first one that was not. A flood of pages nobody reads again is evicted before the
     * hot pages, and a new page always finds room while any page is stored. Before, eviction took
     * pages in map order and a full store refused new pages, so a flood of twice the cap locked the
     * real pages out for the flood's TTL. A new page into a store with no page entries is not
     * stored, in O(1). The bound is approximate under concurrent inserts (racing writers may
     * briefly overshoot by a few entries); counters and the tag index are not capped.
     */
    private boolean ensureCapacity(String newKey) {
        if (store.size() < maxEntries || store.containsKey(newKey)) {
            return true;
        }
        long now = System.nanoTime();
        long last = lastInlineSweep.get();
        if (now - last >= INLINE_SWEEP_INTERVAL_NANOS && lastInlineSweep.compareAndSet(last, now)) {
            sweepExpired();
        }
        boolean newPage = Cache.isPageKey(newKey);
        while (store.size() >= maxEntries) {
            if (evictOnePage()) continue;
            if (newPage) return false; // a new page never displaces an app entry (L4 review F5)
            var it = store.keySet().iterator();
            if (!it.hasNext()) break;
            remove(it.next());
            capacityEvictions.incrementAndGet();
        }
        return true;
    }

    /** Evicts one page entry by CLOCK; false when there is none to evict. */
    private boolean evictOnePage() {
        // Two passes are enough: the first clears every referenced flag it meets.
        int budget = 2 * pageClockSize.get() + 16;
        for (int i = 0; i < budget && pageCount.get() > 0; i++) {
            String key = pageClock.poll();
            if (key == null) return false;
            pageClockSize.decrementAndGet();
            var entry = store.get(key);
            if (entry == null) continue; // removed already: a leftover
            if (entry.referenced && !entry.expired()) {
                entry.referenced = false;
                pageClock.add(key);
                pageClockSize.incrementAndGet();
                continue;
            }
            if (remove(key)) {
                capacityEvictions.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    /** Stores {@code entry}, keeping the page count and clock in step. */
    private void put(String key, Entry entry) {
        var previous = store.put(key, entry);
        if (previous == null && Cache.isPageKey(key)) {
            pageCount.incrementAndGet();
            pageClock.add(key);
            if (pageClockSize.incrementAndGet() > 2 * Math.max(pageCount.get(), 0) + 1024) {
                rebuildPageClock();
            }
        }
    }

    /** Drops leftovers (removed or duplicate keys) from the clock; amortized by the 1,024 slack. */
    private synchronized void rebuildPageClock() {
        var seen = new java.util.HashSet<String>();
        int kept = 0;
        for (int n = pageClockSize.get(); n > 0; n--) {
            String key = pageClock.poll();
            if (key == null) break;
            pageClockSize.decrementAndGet();
            if (store.containsKey(key) && seen.add(key)) {
                pageClock.add(key);
                kept++;
            }
        }
        pageClockSize.addAndGet(kept);
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
        return entry.hit();
    }

    @Override
    public void setObject(String key, Object value, Duration ttl, String[] tags) {
        if (!ensureCapacity(key)) {
            return; // a page entry into a store full of the app's own entries: not cached (L4 review F5)
        }
        var expiry = ttl == null ? null : Instant.now().plus(ttl);
        put(key, new Entry(value, expiry, tags));
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
            return new Computed(current.hit(), true);
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
            if (ensureCapacity(key)) {
                put(key, entry);
            }
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
            var entry = store.remove(key);
            if (entry != null) {
                removed++;
                if (Cache.isPageKey(key)) pageCount.decrementAndGet();
            }
        }
        return removed;
    }

    @Override
    public synchronized void clear() {
        store.clear();
        tagIndex.clear();
        counters.clear();
        pageCount.set(0);
        pageClock.clear();
        pageClockSize.set(0);
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

    private final AtomicLong fullScans = new AtomicLong();
    long fullScans() { return fullScans.get(); }

    private long sweepExpired() {
        fullScans.incrementAndGet();
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

    /** Removes {@code key}; true if it was there. Its clock slot, if any, is skipped later. */
    private boolean remove(String key) {
        var entry = store.remove(key);
        if (entry == null) return false;
        if (Cache.isPageKey(key)) pageCount.decrementAndGet();
        if (entry.tags().length > 0) {
            for (var tag : entry.tags()) {
                var keys = tagIndex.get(tag);
                if (keys != null) keys.remove(key);
            }
        }
        return true;
    }
}
