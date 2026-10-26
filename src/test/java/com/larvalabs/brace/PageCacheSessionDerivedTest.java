package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1 (2026-10 security review): the page cache must not store a render that carries the visitor's
 * CSRF token or pending flash — it would replay user A's token and flash text to every other
 * visitor. Pages with neither are still cached as before.
 */
class PageCacheSessionDerivedTest {

    static final String SECRET = "page-cache-m1-test-secret-at-least-32-chars";
    static TestApp sessionApp;
    static TestApp plainApp;
    static final AtomicInteger formRenders = new AtomicInteger();
    static final AtomicInteger plainRenders = new AtomicInteger();
    static final AtomicInteger noFormRenders = new AtomicInteger();
    static Cache plainCache;

    @BeforeAll
    static void setup() throws Exception {
        sessionApp = Brace.test()
            .sessions(SECRET)
            .templates("src/test/resources/views")
            .start(app -> {
                app.getSession("/setflash", (req, session) -> {
                    session.flash("notice", "reset link sent to alice@example.com");
                    return Result.text("ok");
                });
                app.get("/cached-form", Brace.cache().wrap("5m", req -> {
                    formRenders.incrementAndGet();
                    return View.of("cachedForm");
                }));
                app.post("/cached-form", req -> Result.text("saved"));
                // Same app (sessions on, so every render is handed CSRF tokens), but the
                // template renders none and there is no flash: still cacheable.
                app.get("/cached-noform", Brace.cache().wrap("5m", req -> {
                    noFormRenders.incrementAndGet();
                    return View.of("hello");
                }));
            });
        plainCache = Brace.cache();
        plainApp = Brace.test()
            .templates("src/test/resources/views")
            .start(app -> app.get("/cached-plain", plainCache.wrap("5m", req -> {
                plainRenders.incrementAndGet();
                return View.of("hello");
            })));
    }

    @AfterAll
    static void teardown() throws Exception {
        sessionApp.stop();
        plainApp.stop();
        // Static engine is process-wide; reset so stub-mode tests (ResultTest) are unaffected.
        View.setEngine(null);
    }

    @BeforeEach
    void clearJar() {
        sessionApp.evictSessionCookie();
    }

    @Test
    void secondVisitorGetsOwnTokenAndNoFlashFromTheFirst() {
        // User A: pending flash, then the cached route.
        sessionApp.get("/setflash");
        String pageA = sessionApp.get("/cached-form").body();
        assertTrue(pageA.contains("notice=reset link sent to alice@example.com"), pageA);
        String tokenA = extract(pageA, "<meta name=\"csrf-token\" content=\"([^\"]+)\">");
        String fieldA = extract(pageA, "name=\"_csrf\" value=\"([^\"]+)\"");

        // User B: different session.
        sessionApp.evictSessionCookie();
        String pageB = sessionApp.get("/cached-form").body();
        assertTrue(pageB.contains("notice=none"), "B must not see A's flash: " + pageB);
        assertFalse(pageB.contains("alice@example.com"), pageB);
        String tokenB = extract(pageB, "<meta name=\"csrf-token\" content=\"([^\"]+)\">");
        String fieldB = extract(pageB, "name=\"_csrf\" value=\"([^\"]+)\"");
        assertNotEquals(tokenA, tokenB);
        assertNotEquals(fieldA, fieldB);

        // B's rendered token validates on B's own session; A's does not.
        assertEquals(403, sessionApp.post("/cached-form", Map.of("_csrf", tokenA)).status());
        assertEquals(200, sessionApp.post("/cached-form", Map.of("_csrf", tokenB)).status());
        assertEquals(200, sessionApp.post("/cached-form", Map.of("_csrf", fieldB)).status());
    }

    @Test
    void sessionDerivedRenderIsNotStored() {
        int before = formRenders.get();
        sessionApp.get("/cached-form");
        sessionApp.get("/cached-form");
        assertEquals(2, formRenders.get() - before, "a render carrying a CSRF token must not be served from cache");
    }

    @Test
    void pageWithoutCsrfOrFlashIsStillCached() {
        long hitsBefore = plainCache.hits();
        int before = plainRenders.get();
        var first = plainApp.get("/cached-plain");
        var second = plainApp.get("/cached-plain");
        assertEquals(200, second.status());
        assertEquals(first.body(), second.body());
        assertEquals(1, plainRenders.get() - before, "second request must be served from cache");
        assertEquals(1, plainCache.hits() - hitsBefore);
    }

    @Test
    void pageWithoutAFormIsCachedEvenWithSessionsOn() {
        sessionApp.evictSessionCookie();
        int before = noFormRenders.get();
        var first = sessionApp.get("/cached-noform");
        var second = sessionApp.get("/cached-noform");
        assertEquals(200, second.status());
        assertEquals(first.body(), second.body());
        assertEquals(1, noFormRenders.get() - before, "a page that renders no token is still cached");
    }

    private static String extract(String body, String regex) {
        var m = Pattern.compile(regex).matcher(body);
        assertTrue(m.find(), "no match for " + regex + " in " + body);
        return m.group(1);
    }
}
