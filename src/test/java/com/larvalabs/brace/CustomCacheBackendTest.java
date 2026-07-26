package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Correctness review M9: {@code CacheBackend} is a public SPI, so every {@code Cache} operation
 * must work through it. {@code getOrSet} used to branch on {@code requiresSerialization()} and
 * then cast the backend to the concrete built-in {@code InMemoryBackend}, so a third-party
 * live-object backend threw {@code ClassCastException} on the call the docs recommend most.
 */
class CustomCacheBackendTest {

    /** A minimal third-party live-object backend — implements the SPI and nothing else. */
    static final class SimpleBackend implements CacheBackend {
        final Map<String, Object> store = new ConcurrentHashMap<>();

        @Override public boolean requiresSerialization() { return false; }
        @Override public Object getObject(String key) { return store.get(key); }
        @Override public void setObject(String key, Object value, Duration ttl, String[] tags) {
            store.put(key, value);
        }
        @Override public void delete(String key) { store.remove(key); }
        @Override public void deletePrefix(String prefix) {
            store.keySet().removeIf(k -> k.startsWith(prefix));
        }
        @Override public long clearTag(String tag) { return 0; }
        @Override public void clear() { store.clear(); }
        @Override public long incr(String key, long delta) { return delta; }
        @Override public int size() { return store.size(); }
        @Override public long evictExpired() { return 0; }
    }

    @Test
    void getOrSetWorksOnAThirdPartyLiveObjectBackend() {
        var cache = new Cache(new SimpleBackend());
        try {
            var computeCount = new AtomicInteger();
            String first = cache.getOrSet("k", "1m", () -> {
                computeCount.incrementAndGet();
                return "computed";
            });
            String second = cache.getOrSet("k", "1m", () -> {
                computeCount.incrementAndGet();
                return "recomputed";
            });

            assertEquals("computed", first);
            assertEquals("computed", second, "second call must hit the cache");
            assertEquals(1, computeCount.get(), "the supplier must run once");
            assertEquals(1, cache.hits());
            assertEquals(1, cache.misses());
        } finally {
            cache.close();
        }
    }

    @Test
    void theRestOfTheFacadeAlsoWorksThroughTheSpi() {
        var cache = new Cache(new SimpleBackend());
        try {
            cache.set("a", "1");
            assertEquals("1", cache.get("a", String.class));
            cache.delete("a");
            assertNull(cache.get("a", String.class));
        } finally {
            cache.close();
        }
    }

    @Test
    void builtInBackendStillSingleFlightsConcurrentMisses() throws Exception {
        var cache = new Cache();
        try {
            var computeCount = new AtomicInteger();
            var threads = new Thread[8];
            var results = new String[threads.length];
            for (int i = 0; i < threads.length; i++) {
                final int idx = i;
                threads[i] = Thread.ofVirtual().unstarted(() ->
                    results[idx] = cache.getOrSet("cold", "1m", () -> {
                        computeCount.incrementAndGet();
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        return "once";
                    }));
            }
            for (var t : threads) t.start();
            for (var t : threads) t.join();

            assertEquals(1, computeCount.get(),
                "the in-memory backend's single-flight must survive the move to the SPI");
            for (var r : results) {
                assertEquals("once", r);
            }
        } finally {
            cache.close();
        }
    }
}
