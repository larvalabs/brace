package io.brace;

import org.junit.jupiter.api.*;

import java.util.Map;
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

        assertEquals("response", result1.body());
        assertEquals("response", result2.body());
        assertEquals(1, counter.get());
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
        assertEquals("v1", cached.apply(req).body());
        assertEquals("v1", cached.apply(req).body());
        assertEquals(1, counter.get());

        cache.clearTag("simulation");

        assertEquals("v2", cached.apply(req).body());
        assertEquals(2, counter.get());
    }

    @Test
    void wrapDifferentiatesByQueryString() {
        var counter = new AtomicInteger();
        Handler handler = req -> {
            counter.incrementAndGet();
            return Result.text("page" + counter.get());
        };

        var cached = cache.wrap("5m", handler);

        var req1 = new Request("GET", "/items", Map.of(), Map.of("page", "1"), Map.of(), null);
        var req2 = new Request("GET", "/items", Map.of(), Map.of("page", "2"), Map.of(), null);

        assertEquals("page1", cached.apply(req1).body());
        assertEquals("page2", cached.apply(req2).body());
        assertEquals(2, counter.get());
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
}
