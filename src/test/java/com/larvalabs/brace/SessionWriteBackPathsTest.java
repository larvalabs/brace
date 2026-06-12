package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Write-back contract on the non-handler response paths, and flash lifetime when a
 * session-aware before middleware matches routes whose handlers can't render flash.
 *
 * <ul>
 *   <li>A pass-through guard mutation must persist on the 404 (no-route) path, not just
 *       on handler responses.</li>
 *   <li>buildSession must NOT consume flash: a guard matching a plain-Handler route
 *       (JSON/polling) or short-circuiting to a redirect must leave pending flash in the
 *       cookie for the Session-taking page that will actually render it.</li>
 * </ul>
 */
class SessionWriteBackPathsTest {

    static final String SECRET = "write-back-paths-test-secret-32-chars-ok!";
    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .sessions(SECRET)
            .start(app -> {
                // Pass-through guard that mutates the session on a prefix with NO routes:
                // requests under /tracked/* always take the 404 path.
                app.before("/tracked/*", (req, session) -> {
                    session.set("seen", "yes");
                    return null;
                });

                app.getSession("/whoseen", (req, session) ->
                    Result.text("seen:" + session.get("seen")));

                // Flash producer, auth guard, a guarded plain-Handler polling route, and
                // the login page that renders flash.
                app.getSession("/setflash", (req, session) -> {
                    session.flash("notice", "pending");
                    return Result.text("ok");
                });
                app.requireSession("/members/*", "userId", "/login");
                app.get("/members/poll", req -> Result.text("data"));
                app.getSession("/loginas42", (req, session) -> {
                    session.set("userId", "42");
                    return Result.text("ok");
                });
                app.getSession("/login", (req, session) ->
                    Result.text("login:" + session.flash("notice")));

                // Pass-through guard over routes that exercise the non-handler response
                // paths: CSRF 403, thrown NotFoundException, and the 500 catch.
                app.before("/guarded/*", (req, session) -> {
                    session.set("seen", "yes");
                    return null;
                });
                app.post("/guarded/submit", req -> Result.text("ok"));
                app.get("/guarded/missing", req -> { throw new NotFoundException(); });
                app.get("/guarded/boom", req -> { throw new RuntimeException("boom"); });

                // Session mutation + explicit caching directive from the handler.
                app.getSession("/cached", (req, session) -> {
                    session.set("seen", "yes");
                    return Result.text("cached").header("Cache-Control", "no-store");
                });
            });
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
    }

    @BeforeEach
    void clearJar() {
        // Tests share the TestApp cookie jar; start each from an anonymous state.
        testApp.evictSessionCookie();
    }

    @Test
    void guardMutationPersistsOnNoRoutePath() {
        var miss = testApp.get("/tracked/anything");
        assertEquals(404, miss.status());
        assertFalse(miss.headers("Set-Cookie").isEmpty(),
            "guard mutation must be written back even when no route matches");
        // The jar carries the mutated session to the next request.
        assertEquals("seen:yes", testApp.get("/whoseen").body());
    }

    @Test
    void sessionCookieResponseIsCacheControlPrivate() {
        // A response carrying a session Set-Cookie with no explicit Cache-Control must
        // not be heuristically cacheable — a force-cache proxy would replay user A's
        // cookie to everyone (e.g. statics under a lastSeen-touch middleware).
        var miss = testApp.get("/tracked/anything");
        assertFalse(miss.headers("Set-Cookie").isEmpty());
        assertEquals("private", miss.header("Cache-Control"));
    }

    @Test
    void explicitCacheControlWinsOverPrivateDefault() {
        var cached = testApp.get("/cached");
        assertFalse(cached.headers("Set-Cookie").isEmpty());
        assertEquals("no-store", cached.header("Cache-Control"));
    }

    @Test
    void guardMutationPersistsOnCsrf403() {
        // POST without a CSRF token: the guard runs (and mutates) before CSRF validation
        // rejects — the mutation must still be written back with the 403.
        var rejected = testApp.post("/guarded/submit");
        assertEquals(403, rejected.status());
        assertFalse(rejected.headers("Set-Cookie").isEmpty(),
            "guard mutation must be written back on the CSRF-403 path");
        assertEquals("seen:yes", testApp.get("/whoseen").body());
    }

    @Test
    void guardMutationPersistsOnThrownNotFound() {
        var miss = testApp.get("/guarded/missing");
        assertEquals(404, miss.status());
        assertEquals("seen:yes", testApp.get("/whoseen").body());
    }

    @Test
    void guardMutationPersistsOn500() {
        var boom = testApp.get("/guarded/boom");
        assertEquals(500, boom.status());
        assertEquals("seen:yes", testApp.get("/whoseen").body());
    }

    @Test
    void requireSessionRedirectPreservesPendingFlash() {
        testApp.get("/setflash");
        // Anonymous poll of a guarded route: redirected — and the pending flash must
        // survive (before the fix, buildSession consumed it and the guard's redirect
        // persisted the flash-stripped cookie).
        var redirect = testApp.get("/members/poll");
        assertEquals(302, redirect.status());
        assertEquals("login:pending", testApp.get("/login").body());
    }

    @Test
    void plainHandlerRouteUnderGuardDoesNotEatFlash() {
        testApp.get("/loginas42");
        testApp.get("/setflash");
        // Logged-in poll of a plain-Handler route: guard passes through, handler takes
        // no Session — flash must stay in the cookie for the page that renders it.
        var poll = testApp.get("/members/poll");
        assertEquals(200, poll.status());
        assertEquals("data", poll.body());
        assertEquals("login:pending", testApp.get("/login").body());
    }
}
