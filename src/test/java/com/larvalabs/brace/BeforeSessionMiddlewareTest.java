package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BeforeSessionMiddlewareTest {

    static final String SECRET = "test-secret-for-before-session-mw-32chars!";
    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .sessions(SECRET)
            .start(app -> {
                // Plain before middleware must run first (records its visit in a header check route).
                app.before("/admin/*", req ->
                    "1".equals(req.queryParam("blockPlain")) ? Result.forbidden("plain-first") : null);

                // The one-line auth guard.
                app.requireSession("/admin/*", "userId", "/login");

                // A session-aware guard that MUTATES the session before passing through —
                // the handler must observe the same instance.
                app.before("/admin/*", (req, session) -> {
                    session.set("guardTouched", "yes");
                    return null;
                });

                // A guard that mutates and then short-circuits: the early response must
                // carry the session cookie (flash survives to the next request).
                app.before("/flashout", (req, session) -> {
                    session.flash("notice", "guard says hi");
                    return Redirect.to("/login");
                });

                app.getSession("/admin/home", (req, session) ->
                    Result.text(session.get("userId") + ":" + session.get("guardTouched")));

                // Guarded route whose handler takes no session at all — the guard still applies.
                app.get("/admin/plainhandler", req -> Result.text("plain-ok"));

                // Mutating guarded route: CSRF interplay must be unchanged.
                app.postSession("/admin/save", (req, session) -> Result.text("saved"));

                app.getSession("/login", (req, session) ->
                    Result.text("login:" + session.flash("notice")));
            });
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
    }

    @Test
    void anonymousIsRedirectedToLogin() {
        var response = testApp.get("/admin/home");
        assertEquals(302, response.status());
        assertEquals("/login", response.redirectedTo());
    }

    @Test
    void bareGuardPrefixIsCoveredToo() {
        var response = testApp.get("/admin");
        assertEquals(302, response.status());
    }

    @Test
    void authenticatedPassesAndSeesGuardMutation() {
        var response = testApp.get("/admin/home", Session.of("userId", "42"));
        assertEquals(200, response.status());
        assertEquals("42:yes", response.body());
        // The guard mutated the session, so the response must persist it.
        assertFalse(response.headers("Set-Cookie").isEmpty(),
            "guard mutation must produce a session cookie");
    }

    @Test
    void guardProtectsSessionlessHandlers() {
        assertEquals(302, testApp.get("/admin/plainhandler").status());
        assertEquals("plain-ok", testApp.get("/admin/plainhandler", Session.of("userId", "7")).body());
    }

    @Test
    void plainBeforeRunsBeforeSessionAware() {
        // Anonymous + blockPlain: if plain before runs first we get its 403,
        // not the guard's 302.
        var response = testApp.get("/admin/home?blockPlain=1");
        assertEquals(403, response.status());
    }

    @Test
    void shortCircuitWithMutationPersistsViaCookie() {
        var redirect = testApp.request("GET", "/flashout").send();
        assertEquals(302, redirect.status());
        assertFalse(redirect.headers("Set-Cookie").isEmpty(),
            "flash set by a short-circuiting guard must be written to the cookie");

        // The shared cookie jar carries the flashed session to the next request.
        var login = testApp.get("/login");
        assertEquals("login:guard says hi", login.body());
    }

    @Test
    void requireSessionWithoutSessionsThrowsAtStartup() {
        // Without .sessions(secret) every request gets an empty session, so the guard
        // redirects forever — a provable infinite loop whose runtime symptom
        // (ERR_TOO_MANY_REDIRECTS) points nowhere. Startup must fail, not warn.
        var ex = assertThrows(IllegalStateException.class, () ->
            Brace.test().start(app ->
                app.requireSession("/x/*", "userId", "/login")));
        assertTrue(ex.getMessage().contains("/x/*"), ex.getMessage());
        assertTrue(ex.getMessage().contains(".sessions(secret)"), ex.getMessage());
    }

    @Test
    void genericBeforeSessionWithoutSessionsWarnsAtStartup() throws Exception {
        // A generic BeforeSession may be read-only or tolerant of an empty session,
        // so it keeps the loud warning instead of the requireSession throw.
        LogTap.clear();
        var noSessions = Brace.test().start(app ->
            app.before("/x/*", (req, session) -> null));
        try {
            boolean warned = LogTap.snapshot().stream().anyMatch(e ->
                String.valueOf(e.fields().get("message")).contains("sessions are not enabled"));
            assertTrue(warned, "expected a startup warning about session-aware middleware without sessions");
        } finally {
            noSessions.stop();
        }
    }

    @Test
    void csrfInterplayUnchangedOnGuardedMutatingRoutes() {
        var session = Session.of("userId", "9");
        // Without a token: still CSRF-rejected (the guard passing must not bypass CSRF).
        var noToken = testApp.post("/admin/save", Map.of("x", "1"), session);
        assertEquals(403, noToken.status());
        // With a token: passes guard and CSRF.
        var withToken = testApp.postWithCsrf("/admin/save", Map.of("x", "1"), session);
        assertEquals(200, withToken.status());
        assertEquals("saved", withToken.body());
        // Anonymous with a valid-token session but no userId: guard rejects first.
        var anonymous = testApp.postWithCsrf("/admin/save", Map.of("x", "1"), Session.of("other", "1"));
        assertEquals(302, anonymous.status());
    }
}
