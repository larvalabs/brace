package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final-review fixes for L4 (docs/2026-11-09-security-review-todos.md): page-cache poisoning
 * through {@code HX-Request} values and {@code HEAD}, credentials in cache keys, the reserved key
 * prefix, raw-path spellings, and in-memory eviction under a page flood.
 */
class PageCacheFinalReviewTest {

    private static Request request(String method, String path, Map<String, String> headers) {
        return new Request(method, path, Map.of(), Map.of(), headers, null, Map.of(), "203.0.113.1", null);
    }

    private static Request get(String path, Map<String, String> headers) {
        return request("GET", path, headers);
    }

    // ---- HX-Request: absent, exactly "true", or anything else (bypass) ----

    @Test
    void anHxRequestValueOtherThanTrueDoesNotPoisonTheFullPage() {
        var cache = new Cache();
        try {
            var page = cache.wrap("1h", req -> Result.text(req.hasHeader("HX-Request") ? "PARTIAL" : "FULL"));
            assertEquals("PARTIAL", page.apply(get("/h", Map.of("HX-Request", "1"))).body());
            assertEquals("FULL", page.apply(get("/h", Map.of())).body(),
                "HX-Request: 1 stored the partial under the full-page key");
            assertEquals("PARTIAL", page.apply(get("/h", Map.of("HX-Request", "true"))).body());
            assertEquals("FULL", page.apply(get("/h", Map.of())).body());
            var calls = new AtomicInteger();
            var counted = cache.wrap("1h", req -> Result.text("n=" + calls.incrementAndGet()));
            counted.apply(get("/c", Map.of("HX-Request", "True")));
            counted.apply(get("/c", Map.of("HX-Request", "True")));
            assertEquals(2, calls.get(), "an HX-Request value other than exactly true bypasses the cache");
        } finally {
            cache.close();
        }
    }

    @Test
    void readingTheHxRequestHeaderIsCoveredByTheKey() {
        // With the third state bypassing, every read of HX-Request is determined by the key.
        var cache = new Cache();
        try {
            var page = cache.wrap("1h", req -> Result.text("" + req.header("HX-Request")));
            var strict = get("/s", Map.of("HX-Request", "true"));
            strict.setStrictInputChecks(true);
            assertDoesNotThrow(() -> page.apply(strict));
        } finally {
            cache.close();
        }
    }

    // ---- HEAD never stores ----

    @Test
    void aHeadMissIsServedUncachedAndNeverStored() {
        var cache = new Cache();
        try {
            var calls = new AtomicInteger();
            var page = cache.wrap("1h", req -> {
                calls.incrementAndGet();
                return req.method().equals("HEAD") ? Result.text("") : Result.text("full page");
            });
            assertEquals("", page.apply(request("HEAD", "/p", Map.of())).body());
            assertEquals("full page", page.apply(get("/p", Map.of())).body(),
                "the HEAD response was stored and served to GET");
            int afterGet = calls.get();
            assertEquals("full page", page.apply(request("HEAD", "/p", Map.of())).body(),
                "a HEAD may be served from an existing GET entry");
            assertEquals(afterGet, calls.get());
        } finally {
            cache.close();
        }
    }

    // ---- varyHeader values are hashed in the key ----

    static final class KeyRecordingBackend extends InMemoryBackend {
        final List<String> keys = new ArrayList<>();
        @Override
        public void setObject(String key, Object value, Duration ttl, String[] tags) {
            keys.add(key);
            super.setObject(key, value, ttl, tags);
        }
    }

    @Test
    void varyHeaderValuesAreHashedInTheKey() {
        var backend = new KeyRecordingBackend();
        var cache = new Cache(backend);
        try {
            var page = cache.wrap("1h", req -> Result.text("hi " + req.header("Authorization"))).varyHeader("Authorization");
            assertEquals("hi Bearer s3cr3t-token", page.apply(get("/a", Map.of("Authorization", "Bearer s3cr3t-token"))).body());
            assertEquals("hi Bearer s3cr3t-token", page.apply(get("/a", Map.of("Authorization", "Bearer s3cr3t-token"))).body());
            assertEquals("hi Bearer other", page.apply(get("/a", Map.of("Authorization", "Bearer other"))).body());
            assertEquals(2, backend.keys.size());
            for (var key : backend.keys) {
                assertFalse(key.contains("s3cr3t") || key.contains("other") || key.contains("Bearer"),
                    "the credential is in the stored key: " + key);
            }
        } finally {
            cache.close();
        }
    }

    // ---- the reserved prefix ----

    @Test
    void appKeysOnThePagePrefixAreRefused() {
        var cache = new Cache();
        try {
            assertThrows(IllegalArgumentException.class, () -> cache.set("brace:page:GET:path:/x", "evil"));
            assertThrows(IllegalArgumentException.class, () -> cache.set("brace:page:x", "evil", "1h"));
            assertThrows(IllegalArgumentException.class, () -> cache.set("brace:page:x", "evil", "1h", "t"));
            assertThrows(IllegalArgumentException.class, () -> cache.getOrSet("brace:page:x", "1h", () -> "evil"));
            assertThrows(IllegalArgumentException.class, () -> cache.delete("brace:page:x"));
            assertThrows(IllegalArgumentException.class, () -> cache.deletePrefix(null));
            // Clearing every page by prefix stays possible, as do tags and clear().
            var page = cache.wrap("1h", req -> Result.text("page"));
            page.apply(get("/p", Map.of()));
            assertEquals(1, cache.size());
            cache.deletePrefix("brace:page:");
            assertEquals(0, cache.size());
            cache.set("brace:pages-are-fine", "v");
        } finally {
            cache.close();
        }
    }

    // ---- a raw path spelling never stores ----

    @Test
    void aNonCanonicalRawPathIsServedButNotStored() {
        var cache = new Cache();
        try {
            Handler handler = req -> Result.text("raw " + req.path());
            var page = cache.wrap("1h", handler);
            var route = new Route("GET", "/p/{slug}", handler, null);
            var odd = new Request("GET", "/p/%62", Map.of("slug", "b"), Map.of(), Map.of(), null, Map.of(), "203.0.113.1", null);
            odd.setRoute(route);
            var plain = new Request("GET", "/p/b", Map.of("slug", "b"), Map.of(), Map.of(), null, Map.of(), "203.0.113.1", null);
            plain.setRoute(route);
            assertEquals("raw /p/%62", page.apply(odd).body());
            assertEquals("raw /p/b", page.apply(plain).body(), "the /p/%62 render was stored for /p/b");
            assertEquals("raw /p/b", page.apply(odd).body(), "a non-canonical spelling may be served the stored page");
            for (String path : new String[] {"/p/%7e", "/p/%41", "/p/a%2db", "/p/café"}) {
                assertFalse(Cache.CachedHandler.isNormalPath(path), path);
            }
            for (String path : new String[] {"/p/b", "/p/a%20b", "/p/caf%C3%A9", "/p/a%2Fb", "/p/a+b", "/p/x:y@z!"}) {
                assertTrue(Cache.CachedHandler.isNormalPath(path), path);
            }
        } finally {
            cache.close();
        }
    }

    // ---- in-memory: a page flood does not lock hot pages out ----

    @Test
    void hotPagesKeepTheirHitRateThroughAFlood() {
        // The reviewer's scenario: 50 legitimate pages and a 20,000-key flood on a 1 h route, with
        // the default 10,000-entry cap. Eviction used to take pages in map order and refused new
        // pages once full, so the legitimate pages hit 3% of the time.
        var cache = new Cache(new InMemoryBackend());
        try {
            var renders = new HashMap<String, Integer>();
            var page = cache.wrap("1h", req -> {
                renders.merge(req.path(), 1, Integer::sum);
                return Result.text("page " + req.path());
            });
            for (int i = 0; i < 50; i++) page.apply(get("/legit/" + i, Map.of()));
            int legitRequests = 0, legitHits = 0;
            for (int f = 0; f < 20_000; f++) {
                page.apply(get("/flood/" + f, Map.of()));
                if (f % 100 == 99) {
                    for (int i = 0; i < 50; i++) {
                        String path = "/legit/" + i;
                        int before = renders.getOrDefault(path, 0);
                        page.apply(get(path, Map.of()));
                        legitRequests++;
                        if (renders.getOrDefault(path, 0) == before) legitHits++;
                    }
                }
            }
            double during = (double) legitHits / legitRequests;
            assertTrue(during > 0.9, "hit rate of hot pages during the flood: " + during);
            int after = 0;
            for (int i = 0; i < 50; i++) {
                String path = "/legit/" + i;
                int before = renders.getOrDefault(path, 0);
                page.apply(get(path, Map.of()));
                if (renders.getOrDefault(path, 0) == before) after++;
            }
            assertTrue(after >= 45, "hot pages cached after the flood: " + after + " of 50");
            assertTrue(cache.size() <= InMemoryBackend.DEFAULT_MAX_ENTRIES + 10, "cap holds: " + cache.size());
        } finally {
            cache.close();
        }
    }

    @Test
    void aNewPageDisplacesAColdPageNeverAnAppEntry() {
        var cache = new Cache(new InMemoryBackend(100));
        try {
            for (int i = 0; i < 50; i++) cache.set("app:" + i, "v" + i);
            var page = cache.wrap("1h", req -> Result.text("page"));
            for (int i = 0; i < 500; i++) page.apply(get("/p/" + i, Map.of()));
            for (int i = 0; i < 50; i++) assertEquals("v" + i, cache.get("app:" + i, String.class));
            var calls = new AtomicInteger();
            var newest = cache.wrap("1h", req -> Result.text("n=" + calls.incrementAndGet()));
            newest.apply(get("/newest", Map.of()));
            newest.apply(get("/newest", Map.of()));
            assertEquals(1, calls.get(), "a new page is stored while there are pages to displace");
        } finally {
            cache.close();
        }
    }

    @Test
    void aStoreFullOfAppEntriesDoesNotScanPerPageRequest() {
        var backend = new InMemoryBackend(100);
        var cache = new Cache(backend);
        try {
            for (int i = 0; i < 100; i++) cache.set("app:" + i, "v" + i);
            var page = cache.wrap("1h", req -> Result.text("page"));
            long before = backend.fullScans();
            for (int i = 0; i < 1_000; i++) page.apply(get("/p/" + i, Map.of()));
            assertTrue(backend.fullScans() - before <= 3,
                "full-store scans for 1,000 page requests: " + (backend.fullScans() - before));
            for (int i = 0; i < 100; i++) assertEquals("v" + i, cache.get("app:" + i, String.class));
        } finally {
            cache.close();
        }
    }
}
