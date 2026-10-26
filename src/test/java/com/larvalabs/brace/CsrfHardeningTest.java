package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 (2026-10 security review): CSRF could be switched off silently on cookie-authenticated
 * routes, was absent entirely in apps without Brace sessions, and htmx requests got no token.
 */
class CsrfHardeningTest {

    static final String SECRET = "csrf-hardening-test-secret-at-least-32-chars";

    // --- csrf(false) on a cookie-session route ---

    @Test
    void csrfOptOutOnASessionHandlerFailsStart() {
        var ex = assertThrows(IllegalStateException.class, () -> Brace.test().sessions(SECRET).start(a ->
            a.post("/transfer", (SessionHandler) (req, s) -> Result.text("moved")).csrf(false)));
        assertTrue(ex.getMessage().contains("POST   /transfer"), ex.getMessage());
        assertTrue(ex.getMessage().contains(".csrf(false, \"reason\")"), ex.getMessage());
    }

    @Test
    void csrfOptOutUnderASessionRequirementFailsStart() {
        // A plain Handler, but the session default makes it cookie-authenticated.
        var ex = assertThrows(IllegalStateException.class, () -> Brace.test().sessions(SECRET).start(a -> {
            a.defaultAccess(Access.session("userId", "/login"));
            a.post("/delete-account", req -> Result.text("gone")).csrf(false);
        }));
        assertTrue(ex.getMessage().contains("/delete-account"), ex.getMessage());
    }

    @Test
    void justifiedOrBearerOptOutsStart() throws Exception {
        Access bearer = Access.checkRequest(req -> "Bearer t".equals(req.header("Authorization")) ? null : Result.unauthorized());
        var app = Brace.test().sessions(SECRET).start(a -> {
            a.defaultAccess(Access.session("userId", "/login"));
            a.post("/logout", (SessionHandler) (req, s) -> { s.clear(); return Result.text("bye"); })
                .publicRoute().csrf(false, "sign-out is idempotent and harmless to force");
            a.post("/api/items", req -> Result.text("created"))
                .access(Access.insteadOfDefault(bearer)).csrf(false);
        });
        try {
            assertEquals(200, app.request("POST", "/api/items").header("Authorization", "Bearer t").send().status());
        } finally {
            app.stop();
        }
    }

    // --- Cross-site requests ---

    @Test
    void crossSiteMutatingRequestsAreRefusedWithoutSessions() throws Exception {
        // No .sessions(): an app with its own auth cookie had no CSRF defence at all.
        var app = Brace.test().start(a -> {
            a.post("/change-email", req -> Result.text("changed"));
            a.get("/page", req -> Result.text("page"));
            a.post("/callback", req -> Result.text("cb")).csrf(false);
        });
        try {
            assertEquals(403, post(app, "/change-email", "Sec-Fetch-Site", "cross-site"));
            assertEquals(200, post(app, "/change-email", "Sec-Fetch-Site", "same-origin"));
            assertEquals(200, post(app, "/change-email", "Sec-Fetch-Site", "same-site"));
            assertEquals(200, post(app, "/change-email", "Sec-Fetch-Site", "none"));
            // Older browsers: Origin instead.
            assertEquals(403, post(app, "/change-email", "Origin", "https://evil.example"));
            assertEquals(403, post(app, "/change-email", "Origin", "null"));
            assertEquals(200, post(app, "/change-email", "Origin", "http://localhost:" + app.app().actualPort()));
            // Non-browser clients send neither.
            assertEquals(200, app.request("POST", "/change-email").send().status());
            // A .csrf(false) route is a deliberate cross-site endpoint; reads are never blocked.
            assertEquals(200, post(app, "/callback", "Sec-Fetch-Site", "cross-site"));
            assertEquals(200, app.request("GET", "/page").header("Sec-Fetch-Site", "cross-site").send().status());
        } finally {
            app.stop();
        }
    }

    @Test
    void crossSiteCheckBacksUpTheTokenCheck() throws Exception {
        var app = Brace.test().sessions(SECRET).start(a ->
            a.postSession("/save", (req, s) -> Result.text("saved")));
        try {
            var session = Session.of("userId", "1");
            Csrf.ensureToken(session);
            String token = Csrf.getToken(session);
            var forged = app.request("POST", "/save").session(session)
                .header("X-CSRF-Token", token).header("Sec-Fetch-Site", "cross-site").send();
            assertEquals(403, forged.status(), "a leaked token alone isn't enough cross-site");
            var ok = app.request("POST", "/save").session(session).header("X-CSRF-Token", token).send();
            assertEquals(200, ok.status());
        } finally {
            app.stop();
        }
    }

    // --- htmx wiring ---

    @Test
    void csrfMetaAndTheBundledHelperCarryTheTokenForHtmx() throws Exception {
        var app = Brace.test().sessions(SECRET).templates("src/test/resources/views").start(a -> {
            a.get("/page", req -> Result.view("csrfMeta"));
            a.post("/like", req -> Result.text("liked"));
        });
        try {
            String page = app.get("/page").body();
            var m = Pattern.compile("<meta name=\"csrf-token\" content=\"([^\"]+)\">").matcher(page);
            assertTrue(m.find(), page);
            var js = app.get("/__brace/csrf-htmx.js");
            assertEquals(200, js.status());
            assertTrue(js.header("Content-Type").startsWith("text/javascript"), js.header("Content-Type"));
            assertTrue(js.body().contains("X-CSRF-Token") && js.body().contains("htmx:configRequest"), js.body());
            // What the helper does: the meta tag's token as X-CSRF-Token on a non-GET htmx request.
            var res = app.request("POST", "/like").header("HX-Request", "true").header("X-CSRF-Token", m.group(1)).send();
            assertEquals(200, res.status());
            assertEquals(403, app.request("POST", "/like").header("HX-Request", "true").send().status());
        } finally {
            app.stop();
            View.setEngine(null);
        }
    }

    private static int post(TestApp app, String path, String header, String value) {
        return app.request("POST", path).header(header, value).send().status();
    }
}
