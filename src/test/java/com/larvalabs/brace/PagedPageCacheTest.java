package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification fix for L4 (docs/2026-11-09-security-review-todos.md): {@code Paged} links under
 * the page cache. Building links read {@code req.path()}, which the raw-path check reports, so the
 * documented {@code cache.wrap(...).vary("page")} example answered 500 under {@code Brace.test()};
 * and the links copied every query pair, so an undeclared {@code ?ref=} was stored in a cached
 * page and served to the next visitor.
 */
class PagedPageCacheTest {

    private static final List<Integer> ALL = new ArrayList<>();
    static {
        for (int i = 1; i <= 95; i++) ALL.add(i);
    }

    private static String links(Paged<?> paged) {
        return paged.links().stream().filter(l -> !l.gap()).map(Paged.Link::url).collect(Collectors.joining(" "));
    }

    @Test
    void theDocumentedExampleAnswers200UnderTheTestHarness() throws Exception {
        var cache = new Cache();
        var app = Brace.test().templates("src/test/resources/views").start(a -> {
            a.defaultAccess(Access.none());
            a.get("/numbers", cache.wrap("1h", req -> View.of("pagedLinks", "numbers", Paged.slice(ALL, req, 10))).vary("page"));
        });
        try {
            var first = app.get("/numbers?page=2");
            assertEquals(200, first.status(), first.body());
            assertTrue(first.body().contains("/numbers?page=3"), first.body());
            var again = app.get("/numbers?page=2");
            assertEquals(200, again.status());
            assertEquals(first.body(), again.body());
        } finally {
            app.stop();
            cache.close();
        }
    }

    @Test
    void anUndeclaredParamIsNotCopiedIntoACachedPagesLinks() throws Exception {
        var cache = new Cache();
        var app = Brace.test().start(a -> {
            a.defaultAccess(Access.none());
            a.get("/list", cache.wrap("1h", req -> Result.text(links(Paged.slice(ALL, req, 10)))).vary("page", "sort"));
        });
        try {
            var poisoned = app.get("/list?page=2&ref=evil.example&sort=name");
            assertEquals(200, poisoned.status(), poisoned.body());
            assertFalse(poisoned.body().contains("evil"), poisoned.body());
            assertTrue(poisoned.body().contains("/list?sort=name&page=3"), "declared params are kept: " + poisoned.body());
            var clean = app.get("/list?page=2&sort=name");
            assertFalse(clean.body().contains("evil"), "the stored page carries the undeclared ref: " + clean.body());
        } finally {
            app.stop();
            cache.close();
        }
    }

    @Test
    void inProductionModeAnUndeclaredParamIsNotStoredEither() {
        // No strict checks (production): nothing is recorded, so Paged itself must drop it.
        var cache = new Cache();
        try {
            var page = cache.wrap("1h", req -> Result.text(links(Paged.slice(ALL, req, 10)))).vary("page");
            var poisoned = new Request("GET", "/list", Map.of(), Map.of("page", "2", "ref", "evil.example"),
                Map.of(), null, Map.of(), "203.0.113.1", null);
            poisoned.setRawQuery("page=2&ref=evil.example");
            assertFalse(page.apply(poisoned).body().contains("evil"));
            var clean = new Request("GET", "/list", Map.of(), Map.of("page", "2"), Map.of(), null, Map.of(), "203.0.113.1", null);
            clean.setRawQuery("page=2");
            assertFalse(page.apply(clean).body().contains("evil"));
        } finally {
            cache.close();
        }
    }

    @Test
    void outsideThePageCacheEveryParamIsStillCopied() {
        var req = new Request("GET", "/list", Map.of(), Map.of("page", "2", "ref", "x"), Map.of(), null, Map.of(), "203.0.113.1", null);
        req.setRawQuery("ref=x&page=2");
        assertEquals("/list?ref=x&page=3", Paged.slice(ALL, req, 10).nextUrl());
    }
}
