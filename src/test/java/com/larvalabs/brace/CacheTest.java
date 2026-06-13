package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CacheTest {

    Cache cache;

    @BeforeEach
    void setup() {
        cache = new Cache();
    }

    @AfterEach
    void teardown() {
        cache.clear();
    }

    @Test
    void getAndSet() {
        cache.set("key", "hello");
        assertEquals("hello", cache.get("key", String.class));
    }

    @Test
    void getReturnsNullForMissing() {
        assertNull(cache.get("missing", String.class));
    }

    @Test
    void setWithTtl() {
        cache.set("key", "value", "5m");
        assertEquals("value", cache.get("key", String.class));
    }

    @Test
    void ttlExpiry() throws InterruptedException {
        cache.set("key", "value", "1s");
        assertEquals("value", cache.get("key", String.class));
        Thread.sleep(1100);
        assertNull(cache.get("key", String.class));
    }

    @Test
    void getOrSetComputesOnMiss() {
        var counter = new AtomicInteger();
        var result = cache.getOrSet("key", "5m", () -> {
            counter.incrementAndGet();
            return "computed";
        });
        assertEquals("computed", result);
        assertEquals(1, counter.get());
    }

    @Test
    void getOrSetReturnsCachedOnHit() {
        var counter = new AtomicInteger();
        cache.getOrSet("key", "5m", () -> { counter.incrementAndGet(); return "first"; });
        var result = cache.getOrSet("key", "5m", () -> { counter.incrementAndGet(); return "second"; });
        assertEquals("first", result);
        assertEquals(1, counter.get());
    }

    @Test
    void getOrSetIsSingleFlightUnderContention() throws Exception {
        // M15: many threads hit the same cold key at once; the supplier (a slow "DB query") must run
        // exactly once and every caller gets that one value. The supplier now runs outside any
        // ConcurrentHashMap bin lock, but the per-server single-flight guarantee must survive.
        int threads = 16;
        var supplierRuns = new AtomicInteger();
        var startGate = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var results = new ConcurrentLinkedQueue<String>();
        var pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    results.add(cache.getOrSet("cold", "5m", () -> {
                        supplierRuns.incrementAndGet();
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        return "value";
                    }));
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown(); // release all at once
        assertTrue(done.await(10, TimeUnit.SECONDS), "all callers should finish");
        pool.shutdownNow();
        assertEquals(1, supplierRuns.get(), "supplier must run exactly once across concurrent cold-key callers");
        assertEquals(threads, results.size());
        assertTrue(results.stream().allMatch("value"::equals), "all callers see the single computed value");
    }

    @Test
    void getOrSetSupplierExceptionDoesNotCacheAndRetries() {
        // A throwing supplier propagates (original type, not wrapped), caches nothing, and the key
        // recovers on the next call.
        var runs = new AtomicInteger();
        assertThrows(IllegalStateException.class, () ->
            cache.getOrSet("k", "5m", () -> { runs.incrementAndGet(); throw new IllegalStateException("boom"); }));
        var v = cache.getOrSet("k", "5m", () -> { runs.incrementAndGet(); return "ok"; });
        assertEquals("ok", v);
        assertEquals(2, runs.get(), "first call ran and threw, second re-ran — nothing was cached in between");
        assertEquals("ok", cache.get("k", String.class));
    }

    @Test
    void getOrSetConcurrentCallersSeeUnwrappedSupplierException() throws Exception {
        // M15: callers awaiting a failed in-flight computation must observe the supplier's actual
        // exception type, not a CompletionException wrapper — and nothing is cached.
        int threads = 8;
        var startGate = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var thrown = new ConcurrentLinkedQueue<Class<?>>();
        var pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    cache.getOrSet("boom-key", "5m", () -> {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        throw new IllegalStateException("boom");
                    });
                } catch (Throwable t) {
                    thrown.add(t.getClass());
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "all callers should finish");
        pool.shutdownNow();
        assertEquals(threads, thrown.size());
        assertTrue(thrown.stream().allMatch(c -> c == IllegalStateException.class),
            "every caller should see the original exception type, got " + thrown);
        assertNull(cache.get("boom-key", String.class), "a failed computation caches nothing");
    }

    @Test
    void delete() {
        cache.set("key", "value");
        cache.delete("key");
        assertNull(cache.get("key", String.class));
    }

    @Test
    void deletePrefix() {
        cache.set("team:TOR", "Toronto");
        cache.set("team:NYR", "New York");
        cache.set("player:1", "Gretzky");
        cache.deletePrefix("team:");
        assertNull(cache.get("team:TOR", String.class));
        assertNull(cache.get("team:NYR", String.class));
        assertEquals("Gretzky", cache.get("player:1", String.class));
    }

    @Test
    void clearRemovesEverything() {
        cache.set("a", "1");
        cache.set("b", "2");
        cache.clear();
        assertNull(cache.get("a", String.class));
        assertNull(cache.get("b", String.class));
    }

    @Test
    void incrAndDecr() {
        assertEquals(1, cache.incr("counter"));
        assertEquals(2, cache.incr("counter"));
        assertEquals(3, cache.incr("counter"));
        assertEquals(2, cache.decr("counter"));
    }

    @Test
    void decrFromZero() {
        assertEquals(-1, cache.decr("counter"));
    }

    @Test
    void tagBasedInvalidation() {
        cache.set("page:home", "home content", "1h", "simulation");
        cache.set("page:api", "api content", "1h", "simulation", "api");
        cache.set("page:about", "about content", "1h", "static");

        cache.clearTag("simulation");

        assertNull(cache.get("page:home", String.class));
        assertNull(cache.get("page:api", String.class));
        assertEquals("about content", cache.get("page:about", String.class));
    }

    @Test
    void clearTagLeavesOtherTags() {
        cache.set("key1", "v1", "1h", "tagA", "tagB");
        cache.set("key2", "v2", "1h", "tagB");

        cache.clearTag("tagA");

        assertNull(cache.get("key1", String.class));
        assertEquals("v2", cache.get("key2", String.class));
    }

    @Test
    void wrapCachesHandlerResult() {
        var counter = new AtomicInteger();
        Handler handler = req -> {
            counter.incrementAndGet();
            return Result.text("response");
        };

        var cached = cache.wrap("5m", handler);

        var req = new Request("GET", "/test", Map.of(), Map.of(), Map.of(), null);
        var result1 = cached.apply(req);
        var result2 = cached.apply(req);

        // A cache hit replays a materialized RenderedResponse (raw bytes), so compare the effective
        // response body rather than the Result.body() String field.
        assertEquals("response", bodyOf(result1));
        assertEquals("response", bodyOf(result2));
        assertEquals(1, counter.get());
    }

    /** The effective response body: raw bytes if materialized (a cache-hit replay), else the String body. */
    private static String bodyOf(Result result) {
        return result.rawBytes() != null
                ? new String(result.rawBytes(), java.nio.charset.StandardCharsets.UTF_8)
                : result.body();
    }

    @Test
    void wrapWithTagsInvalidation() {
        var counter = new AtomicInteger();
        Handler handler = req -> {
            counter.incrementAndGet();
            return Result.text("v" + counter.get());
        };

        var cached = cache.wrap("5m", handler).tags("simulation");

        var req = new Request("GET", "/", Map.of(), Map.of(), Map.of(), null);
        assertEquals("v1", bodyOf(cached.apply(req)));
        assertEquals("v1", bodyOf(cached.apply(req)));
        assertEquals(1, counter.get());

        cache.clearTag("simulation");

        assertEquals("v2", bodyOf(cached.apply(req)));
        assertEquals(2, counter.get());
    }

    @Test
    void wrapDifferentiatesByDeclaredVaryParams() {
        var counter = new AtomicInteger();
        Handler handler = req -> {
            counter.incrementAndGet();
            return Result.text("page" + counter.get());
        };

        var cached = cache.wrap("5m", handler).vary("page");

        var req1 = new Request("GET", "/items", Map.of(), Map.of("page", "1"), Map.of(), null);
        var req2 = new Request("GET", "/items", Map.of(), Map.of("page", "2"), Map.of(), null);

        assertEquals("page1", bodyOf(cached.apply(req1)));
        assertEquals("page2", bodyOf(cached.apply(req2)));
        assertEquals(2, counter.get());
    }

    @Test
    void wrapIgnoresQueryParamsByDefault() {
        // H8: undeclared params must not key the cache — ?x=<random> would otherwise mint one
        // full page copy per request.
        var counter = new AtomicInteger();
        Handler handler = req -> Result.text("v" + counter.incrementAndGet());
        var cached = cache.wrap("5m", handler);

        var plain = new Request("GET", "/items", Map.of(), Map.of(), Map.of(), null);
        var junk1 = new Request("GET", "/items", Map.of(), Map.of("utm_source", "tw"), Map.of(), null);
        var junk2 = new Request("GET", "/items", Map.of(), Map.of("x", "random123"), Map.of(), null);

        assertEquals("v1", bodyOf(cached.apply(plain)));
        assertEquals("v1", bodyOf(cached.apply(junk1)));
        assertEquals("v1", bodyOf(cached.apply(junk2)));
        assertEquals(1, counter.get(), "query params must not create cache entries unless declared");
    }

    @Test
    void wrapVaryIgnoresUndeclaredParamsAlongsideDeclaredOnes() {
        var counter = new AtomicInteger();
        Handler handler = req -> Result.text("v" + counter.incrementAndGet());
        var cached = cache.wrap("5m", handler).vary("page");

        var page2 = new Request("GET", "/items", Map.of(), Map.of("page", "2"), Map.of(), null);
        var page2Junk = new Request("GET", "/items", Map.of(),
            Map.of("page", "2", "utm_source", "newsletter"), Map.of(), null);
        var page2Empty = new Request("GET", "/items", Map.of(), Map.of("page", ""), Map.of(), null);

        assertEquals("v1", bodyOf(cached.apply(page2)));
        assertEquals("v1", bodyOf(cached.apply(page2Junk)), "undeclared param must not split the entry");
        assertEquals("v2", bodyOf(cached.apply(page2Empty)), "empty value is distinct from page=2");
        assertEquals(2, counter.get());
    }

    @Test
    void inMemoryBackendCapsEntriesDropOldestArbitrary() {
        var small = new Cache(CacheBackend.inMemory(5));
        try {
            for (int i = 1; i <= 8; i++) {
                small.set("k" + i, "v" + i);
            }
            assertTrue(small.size() <= 5, "store must stay bounded, was " + small.size());
            assertEquals("v8", small.get("k8", String.class), "newest insert must be present");
        } finally {
            small.close();
        }
    }

    @Test
    void getOrSetRespectsEntryCap() {
        var small = new Cache(CacheBackend.inMemory(3));
        try {
            for (int i = 1; i <= 6; i++) {
                final int n = i;
                small.getOrSet("g" + n, "5m", () -> "v" + n);
            }
            assertTrue(small.size() <= 3, "getOrSet inserts must respect the cap, was " + small.size());
        } finally {
            small.close();
        }
    }

    @Test
    void setNullValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> cache.set("k", null));
        assertThrows(IllegalArgumentException.class, () -> cache.set("k", null, "5m"));
    }

    @Test
    void valueAndCounterShareKeyIndependently() {
        // A key used as both a value and a counter must not clobber either (separate namespaces).
        cache.set("k", "v");
        assertEquals(1, cache.incr("k"));
        assertEquals("v", cache.get("k", String.class));
        cache.set("k", "v2");
        assertEquals(2, cache.incr("k"));
        assertEquals("v2", cache.get("k", String.class));
    }

    @Test
    void htmxAndNonHtmxDoNotShareCacheEntry() {
        var counter = new AtomicInteger();
        Handler handler = req -> {
            counter.incrementAndGet();
            return Result.text(req.isHtmx() ? "partial" : "full");
        };
        var cached = cache.wrap("5m", handler);
        var full = new Request("GET", "/p", Map.of(), Map.of(), Map.of(), null);
        var htmx = new Request("GET", "/p", Map.of(), Map.of(), Map.of("HX-Request", "true"), null);

        assertEquals("full", bodyOf(cached.apply(full)));      // miss
        assertEquals("partial", bodyOf(cached.apply(htmx)));   // separate miss (varies on HX-Request)
        assertEquals("full", bodyOf(cached.apply(full)));      // hit
        assertEquals("partial", bodyOf(cached.apply(htmx)));   // hit
        assertEquals(2, counter.get(), "htmx and non-htmx must not share a cache entry");
    }

    @Test
    void closeStopsCleanlyAndIsSafe() {
        var c = new Cache();
        c.close();
        // Idempotent / safe to call; no exception.
        c.close();
    }

    @Test
    void parseTtlFormats() {
        assertEquals(30, Cache.parseTtl("30s").toSeconds());
        assertEquals(5 * 60, Cache.parseTtl("5m").toSeconds());
        assertEquals(3600, Cache.parseTtl("1h").toSeconds());
        assertEquals(86400, Cache.parseTtl("1d").toSeconds());
    }

    @Test
    void parseTtlInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> Cache.parseTtl("abc"));
        assertThrows(IllegalArgumentException.class, () -> Cache.parseTtl("10x"));
    }

    @Test
    void setWithoutTtlNeverExpires() {
        cache.set("key", "forever");
        assertEquals("forever", cache.get("key", String.class));
    }

    @Test
    void clearAlsoResetsCounters() {
        cache.incr("hits");
        cache.incr("hits");
        cache.clear();
        assertEquals(1, cache.incr("hits"));
    }

    @Test
    void hitOnGetExistingKey() {
        cache.set("key", "value");
        cache.get("key", String.class);
        assertEquals(1, cache.hits());
        assertEquals(0, cache.misses());
    }

    @Test
    void missOnGetMissingKey() {
        cache.get("missing", String.class);
        assertEquals(0, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void missOnGetExpiredKey() throws InterruptedException {
        cache.set("key", "value", "1s");
        Thread.sleep(1100);
        cache.get("key", String.class);
        assertEquals(0, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void getOrSetTracksHitAndMiss() {
        // First call: miss
        cache.getOrSet("key", "5m", () -> "computed");
        assertEquals(0, cache.hits());
        assertEquals(1, cache.misses());

        // Second call: hit
        cache.getOrSet("key", "5m", () -> "ignored");
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void clearTagTracksEvictions() {
        cache.set("a", "1", "1h", "mytag");
        cache.set("b", "2", "1h", "mytag");
        cache.clearTag("mytag");
        assertEquals(2, cache.evictions());
    }

    @Test
    void clearResetsStats() {
        cache.set("key", "value");
        cache.get("key", String.class);
        cache.get("missing", String.class);
        cache.clear();
        assertEquals(0, cache.hits());
        assertEquals(0, cache.misses());
        assertEquals(0, cache.evictions());
    }

    @Test
    void drainResetsCounters() {
        cache.set("key", "value");
        cache.get("key", String.class);
        cache.get("missing", String.class);
        assertEquals(1, cache.drainHits());
        assertEquals(1, cache.drainMisses());
        // After drain, counters are reset
        assertEquals(0, cache.hits());
        assertEquals(0, cache.misses());
    }

    @Test
    void decomposeAndRecomposeCacheEntry() {
        // This test ensures that Class.forName with initialize=false works correctly
        // by caching and retrieving a value without triggering static initializers.
        cache.set("record", new TestRecord("hello", 42));
        TestRecord retrieved = cache.get("record", TestRecord.class);
        assertNotNull(retrieved);
        assertEquals("hello", retrieved.name);
        assertEquals(42, retrieved.id);
    }

    @Test
    void pageKeyWithSpecialCharactersInParamValues() {
        // Test that param values with special characters (&, =, %) don't collide with other keys.
        // For example, "a=b&c=d" and "a=b%26c=d" should produce different cache keys.
        var counter = new AtomicInteger();
        Handler handler = req -> {
            counter.incrementAndGet();
            return Result.text("response" + counter.get());
        };
        var cached = cache.wrap("5m", handler).vary("a", "c", "d");

        // First request: a=b, c=d
        var req1 = new Request("GET", "/test", Map.of(), Map.of("a", "b", "c", "d"), Map.of(), null);
        assertEquals("response1", bodyOf(cached.apply(req1))); // miss, counter=1

        // Second request: a="b&c", d=d (note the & in the value)
        var req2 = new Request("GET", "/test", Map.of(), Map.of("a", "b&c", "d", "d"), Map.of(), null);
        assertEquals("response2", bodyOf(cached.apply(req2))); // miss, counter=2

        // Verify cache differentiation
        assertEquals(2, counter.get(), "Requests with different param values (one with &) should not share cache");

        // Re-request first should hit cache
        assertEquals("response1", bodyOf(cached.apply(req1)));
        assertEquals(2, counter.get(), "Re-requesting should hit cache");
    }

    @Test
    void pageKeyWithEqualsSignInParamValue() {
        // Test that an = sign in a param value doesn't collide with other keys.
        var counter = new AtomicInteger();
        Handler handler = req -> {
            counter.incrementAndGet();
            return Result.text("v" + counter.get());
        };
        var cached = cache.wrap("5m", handler).vary("q", "b");

        // Request with key=value containing = sign
        var req1 = new Request("GET", "/search", Map.of(), Map.of("q", "a=b"), Map.of(), null);
        assertEquals("v1", bodyOf(cached.apply(req1))); // miss

        var req2 = new Request("GET", "/search", Map.of(), Map.of("q", "a", "b", ""), Map.of(), null);
        assertEquals("v2", bodyOf(cached.apply(req2))); // miss (different params)

        assertEquals(2, counter.get(), "Different params should create different cache keys");
    }

    @Test
    void pageKeyWithPercentSignInParamValue() {
        // Test that a percent sign in a param value doesn't create ambiguity.
        var counter = new AtomicInteger();
        Handler handler = req -> {
            counter.incrementAndGet();
            return Result.text("v" + counter.get());
        };
        var cached = cache.wrap("5m", handler).vary("filter");

        var req1 = new Request("GET", "/items", Map.of(), Map.of("filter", "50%"), Map.of(), null);
        assertEquals("v1", bodyOf(cached.apply(req1))); // miss

        var req2 = new Request("GET", "/items", Map.of(), Map.of("filter", "50%25"), Map.of(), null);
        assertEquals("v2", bodyOf(cached.apply(req2))); // miss (different because one has literal %, other has literal %2 and 5)

        assertEquals(2, counter.get(), "Literal % and encoded %25 should produce different cache keys");
    }

    @Test
    void pageKeyWithNonAsciiParamValuesDoesNotCollide() {
        // The old hand-rolled encoder emitted variable-width hex escapes for non-ASCII
        // chars: '中' (U+4E2D) → "%4E2D", and "ӢD" ('Ӣ' U+04E2 → "%4E2", then literal
        // 'D') → "%4E2D" — identical keys. URLEncoder is UTF-8 byte-wise and injective.
        var counter = new AtomicInteger();
        Handler handler = req -> {
            counter.incrementAndGet();
            return Result.text("v" + counter.get());
        };
        var cached = cache.wrap("5m", handler).vary("q");

        var req1 = new Request("GET", "/search", Map.of(), Map.of("q", "中"), Map.of(), null);
        assertEquals("v1", bodyOf(cached.apply(req1))); // miss

        var req2 = new Request("GET", "/search", Map.of(), Map.of("q", "ӢD"), Map.of(), null);
        assertEquals("v2", bodyOf(cached.apply(req2))); // miss — must not share req1's key

        assertEquals(2, counter.get(), "Distinct non-ASCII values must produce distinct cache keys");

        assertEquals("v1", bodyOf(cached.apply(req1)));
        assertEquals(2, counter.get(), "Re-requesting should hit cache");
    }

    /** A simple test record for cache serialization. */
    public static class TestRecord {
        public String name;
        public int id;

        public TestRecord() {} // Jackson needs a no-arg constructor
        public TestRecord(String name, int id) {
            this.name = name;
            this.id = id;
        }
    }
}
