package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L4 (2026-11 review, post-merge): the page cache keys a route on its pattern and decoded path
 * parameters, so two spellings of one path ({@code /p/!} and {@code /p/%21}, both normal form)
 * share an entry. A cached handler that branches on the raw {@code req.path()} can store a page
 * for one spelling that the other is served. The undeclared-read check now reports raw-path reads.
 */
class PageCacheRawPathReadTest {

    @Test
    void aCachedHandlerBranchingOnTheRawPathFailsUnderTheTestHarness() throws Exception {
        var cache = new Cache();
        var app = Brace.test().start(a -> {
            a.defaultAccess(Access.none());
            a.get("/raw/{slug}", cache.wrap("1h",
                req -> Result.text(req.path().contains("%") ? "encoded" : "plain")));
            a.get("/param/{slug}", cache.wrap("1h",
                req -> Result.text("slug " + req.pathParam("slug"))));
        });
        try {
            assertEquals(500, app.get("/raw/hello").status(), "a raw req.path() read must be reported");
            var ok = app.get("/param/hello");
            assertEquals(200, ok.status(), ok.body());
            assertEquals("slug hello", ok.body());
        } finally {
            app.stop();
            cache.close();
        }
    }

    @Test
    void theReportNamesTheRawPathAndSuggestsPathParam() {
        var cache = new Cache();
        try {
            var req = new Request("GET", "/d/x", Map.of(), Map.of(), Map.of(), null, Map.of(), "203.0.113.1", null);
            req.setStrictInputChecks(true);
            var page = cache.wrap("1h", r -> Result.text(r.path()));
            var e = assertThrows(IllegalStateException.class, () -> page.apply(req));
            assertTrue(e.getMessage().contains("req.path()"), e.getMessage());
            assertTrue(e.getMessage().contains("req.pathParam("), e.getMessage());
        } finally {
            cache.close();
        }
    }
}
