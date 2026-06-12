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
