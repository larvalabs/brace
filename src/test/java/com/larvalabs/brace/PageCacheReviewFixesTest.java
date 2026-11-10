package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review fixes for L4 (docs/2026-11-09-security-review-todos.md): page-cache rows evicting the
 * app's own entries (F5), and request input outside the cache key reaching a cached page (F6).
 */
class PageCacheReviewFixesTest {

    static Request request(String path, String rawQuery, Map<String, String> headers) {
        var single = Request.parseSingleValues(rawQuery, true);
        var req = new Request("GET", path, Map.of(), single, headers, null, Map.of(), "203.0.113.1", null);
        req.setRawQuery(rawQuery);
        return req;
    }

    static Request request(String path) {
        return request(path, null, Map.of());
    }

    // ---- F5: page-cache growth never evicts the app's own entries ----

    /** Shared with {@link PageCacheCapPostgresIT}: 50 app entries, then 300 cached pages. */
    static void pageFloodLeavesAppEntries(Cache cache, Runnable trim) {
        for (int i = 0; i < 50; i++) {
            cache.set("app:" + i, "value " + i, "1h");
        }
        var pages = cache.wrap("2h", req -> Result.text("page " + req.path()));
        for (int i = 0; i < 300; i++) {
            pages.apply(request("/p/" + i));
        }
        trim.run();
        int survived = 0;
        for (int i = 0; i < 50; i++) {
            if (("value " + i).equals(cache.get("app:" + i, String.class))) survived++;
        }
        assertEquals(50, survived, "app entries evicted by page-cache growth");
    }

    @Test
    void inMemoryPageFloodLeavesAppEntries() {
        var cache = new Cache(new InMemoryBackend(100));
        try {
            pageFloodLeavesAppEntries(cache, () -> {});
            assertTrue(cache.size() <= 100, "the cap still holds: " + cache.size());
        } finally {
            cache.close();
        }
    }

    // ---- F6 (1): a declared vary param is keyed on all its values ----

    @Test
    void varyParamIsKeyedOnEveryValue() {
        var cache = new Cache();
        try {
            var page = cache.wrap("1h", req -> Result.text(String.join(",", req.queryParams("t")))).vary("t");
            assertEquals("evil,ok", page.apply(request("/v", "t=evil&t=ok", Map.of())).body());
            assertEquals("ok", page.apply(request("/v", "t=ok", Map.of())).body(),
                "?t=evil&t=ok was keyed as t=ok and served to ?t=ok");
        } finally {
            cache.close();
        }
    }

    // ---- F6 (2): requests carrying per-request headers bypass the cache ----

    @Test
    void htmxTargetAndAuthorizationBypassTheCache() {
        var cache = new Cache();
        try {
            var calls = new AtomicInteger();
            var page = cache.wrap("1h", req -> {
                calls.incrementAndGet();
                return Result.text("target=" + req.header("HX-Target") + " auth=" + req.header("Authorization"));
            });
            assertEquals("target=evil auth=null",
                page.apply(request("/h", null, Map.of("HX-Request", "true", "HX-Target", "evil"))).body());
            assertEquals("target=null auth=null", page.apply(request("/h", null, Map.of("HX-Request", "true"))).body(),
                "an HX-Target response was cached and served to the next htmx client");
            assertEquals("target=null auth=Bearer x",
                page.apply(request("/h", null, Map.of("Authorization", "Bearer x"))).body());
            assertEquals("target=null auth=null", page.apply(request("/h")).body());
            for (var header : new String[]{"HX-Trigger", "HX-Trigger-Name", "HX-Boosted", "HX-Prompt"}) {
                int before = calls.get();
                page.apply(request("/h", null, Map.of(header, "x")));
                assertEquals(before + 1, calls.get(), header + " must bypass the cache");
            }
        } finally {
            cache.close();
        }
    }

    @Test
    void declaredVaryHeaderKeysTheCacheInsteadOfBypassing() {
        var cache = new Cache();
        try {
            var calls = new AtomicInteger();
            var page = cache.wrap("1h", req -> {
                calls.incrementAndGet();
                return Result.text("target=" + req.header("HX-Target"));
            }).varyHeader("HX-Target");
            assertEquals("target=a", page.apply(request("/h", null, Map.of("HX-Target", "a"))).body());
            assertEquals("target=a", page.apply(request("/h", null, Map.of("hx-target", "a"))).body());
            assertEquals(1, calls.get(), "same declared header value: served from the cache");
            assertEquals("target=b", page.apply(request("/h", null, Map.of("HX-Target", "b"))).body());
            assertEquals("target=null", page.apply(request("/h")).body());
        } finally {
            cache.close();
        }
    }

    // ---- F6 (3): dev/test detection of undeclared reads ----

    private static Request strict(String rawQuery, Map<String, String> headers) {
        var req = request("/d", rawQuery, headers);
        req.setStrictInputChecks(true);
        return req;
    }

    @Test
    void undeclaredReadsThrowUnderTheTestHarness() {
        var cache = new Cache();
        try {
            var reads = Map.<String, Handler>of(
                "query", req -> Result.text("" + req.queryParam("msg")),
                "header", req -> Result.text("" + req.header("Accept-Language")),
                "cookie", req -> Result.text("" + req.cookie("theme")),
                "url", req -> Result.text(req.url()),
                "ip", req -> Result.text(req.ip()));
            for (var entry : reads.entrySet()) {
                var page = cache.wrap("1h", entry.getValue());
                var e = assertThrows(IllegalStateException.class,
                    () -> page.apply(strict("msg=x", Map.of("Accept-Language", "fr", "Cookie", "theme=dark"))),
                    entry.getKey() + " read must be reported");
                assertTrue(e.getMessage().contains("vary"), e.getMessage());
            }
            // Declared varies, path params and HX-Request are fine.
            var ok = cache.wrap("1h", req -> Result.text(req.queryParam("page") + req.header("X-Variant") + req.isHtmx()))
                .vary("page").varyHeader("X-Variant");
            assertDoesNotThrow(() -> ok.apply(strict("page=2&utm=x", Map.of("X-Variant", "b", "HX-Request", "true"))));
            // Production: nothing is recorded and nothing thrown.
            var prod = cache.wrap("1h", req -> Result.text("" + req.queryParam("msg")));
            assertEquals("x", prod.apply(request("/d", "msg=x", Map.of())).body());
        } finally {
            cache.close();
        }
    }

    @Test
    void undeclaredReadsWarnOncePerRouteInDevMode() {
        var previous = System.getProperty("brace.mode");
        System.setProperty("brace.mode", "dev");
        var cache = new Cache();
        try {
            var page = cache.wrap("1h", req -> Result.text("" + req.queryParam("msg")));
            assertEquals("x", page.apply(strict("msg=x", Map.of())).body(), "dev mode warns, it does not throw");
            assertTrue(page.warnedUndeclaredReads());
        } finally {
            cache.close();
            if (previous == null) System.clearProperty("brace.mode"); else System.setProperty("brace.mode", previous);
        }
    }

    @Test
    void testHarnessWiresTheCheckEndToEnd() throws Exception {
        var cache = new Cache();
        var app = Brace.test().start(a -> {
            a.defaultAccess(Access.none());
            a.get("/cached", cache.wrap("1h", req -> Result.text("hello " + req.queryParam("msg"))));
        });
        try {
            assertEquals(500, app.get("/cached?msg=%3Cb%3EPWNED%3C/b%3E").status());
        } finally {
            app.stop();
            cache.close();
        }
    }

    // ---- F6 (4): a response that sets a cookie is never stored, and keeps its cookie ----

    @Test
    void responseThatSetsACookieIsNotCachedAndKeepsIt() {
        var cache = new Cache();
        try {
            var calls = new AtomicInteger();
            var page = cache.wrap("1h", req -> Result.text("n=" + calls.incrementAndGet())
                .header("Set-Cookie", "seen=1; Path=/"));
            var first = page.apply(request("/c"));
            assertEquals(java.util.List.of("seen=1; Path=/"), first.setCookies(), "the miss response lost its cookie");
            var second = page.apply(request("/c"));
            assertEquals("n=2", second.body(), "a cookie-setting response must not be stored");
            assertEquals(1, second.setCookies().size());
        } finally {
            cache.close();
        }
    }
}
