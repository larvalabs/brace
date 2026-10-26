package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M10 (2026-10 security review): a sealed cookie session couldn't be revoked server-side, and the
 * secret couldn't be rotated without logging everyone out.
 */
class SessionRevocationTest {

    static final String OLD = "previous-session-secret-at-least-32-chars-aa";
    static final String CURRENT = "current-session-secret-at-least-32-chars-bbb";

    // --- previous secrets ---

    @Test
    void cookieSealedWithAPreviousSecretOpensAndIsResealed() {
        String old = Session.of("userId", "5").toCookie(OLD);
        var session = Session.fromCookie(old, CURRENT, List.of(OLD));
        assertEquals("5", session.get("userId"));
        assertTrue(session.isModified(), "re-sealed with the current secret on this response");
        // Not opened without the previous secret listed.
        assertNull(Session.fromCookie(old, CURRENT, List.of()).get("userId"));
    }

    @Test
    void rotationEndToEnd() throws Exception {
        var app = Brace.test().start(a -> {
            a.sessions(SessionOptions.of(CURRENT).previousSecrets(OLD));
            a.getSession("/me", (req, s) -> Result.text(String.valueOf(s.get("userId"))));
        });
        try {
            var res = app.request("GET", "/me")
                .header("Cookie", "brace_session=" + Session.of("userId", "5").toCookie(OLD)).send();
            assertEquals("5", res.body());
            var reissued = res.headers("Set-Cookie").stream().filter(c -> c.startsWith("brace_session=")).findFirst();
            assertTrue(reissued.isPresent(), "the old-secret cookie is re-sealed");
            String value = reissued.get().substring("brace_session=".length(), reissued.get().indexOf(';'));
            assertEquals("5", Session.fromCookie(value, CURRENT).get("userId"), "now sealed with the current secret");
        } finally {
            app.stop();
        }
    }

    @Test
    void shortPreviousSecretIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SessionOptions.of(CURRENT).previousSecrets("short"));
    }

    // --- validator ---

    @Test
    void validatorRevokesSessionsAndClearsTheCookie() throws Exception {
        var currentVersion = new ConcurrentHashMap<String, String>(Map.of("5", "1"));
        var calls = new AtomicInteger();
        var app = Brace.test().sessions(CURRENT).start(a -> {
            a.sessionValidator(s -> {
                calls.incrementAndGet();
                return String.valueOf(s.get("ver")).equals(currentVersion.get(s.get("userId")));
            });
            a.getSession("/me", (req, s) -> Result.text(String.valueOf(s.get("userId"))));
            a.get("/public", req -> Result.text("hi"));
        });
        try {
            var session = Session.of("userId", "5", "ver", "1");
            assertEquals("5", app.get("/me", session).body());

            // "Log out everywhere": bump the user's version.
            currentVersion.put("5", "2");
            var revoked = app.get("/me", session);
            assertEquals("null", revoked.body(), "the old cookie no longer authenticates");
            assertTrue(revoked.headers("Set-Cookie").stream().anyMatch(c -> c.startsWith("brace_session=")),
                "the client's cookie is replaced");

            // Anonymous requests and routes that never read the session don't call it.
            calls.set(0);
            app.evictSessionCookie();
            app.get("/me");
            app.get("/public", session);
            assertEquals(0, calls.get());
        } finally {
            app.stop();
        }
    }

    @Test
    void validatorAppliesToWebSocketUpgrades() throws Exception {
        var app = Brace.app().port(0).banner(false).sessions(CURRENT)
            .sessionValidator(s -> !"revoked".equals(s.get("userId")))
            .defaultAccess(Access.session("userId", "/login"));
        app.ws("/live", ctx -> new Object());
        app.start();
        try {
            var cookie = "brace_session=" + Session.of("userId", "revoked").toCookie(CURRENT);
            var ex = assertThrows(java.util.concurrent.ExecutionException.class, () ->
                java.net.http.HttpClient.newHttpClient().newWebSocketBuilder().header("Cookie", cookie)
                    .buildAsync(java.net.URI.create("ws://localhost:" + app.actualPort() + "/live"),
                        new java.net.http.WebSocket.Listener() {})
                    .get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(401, ((java.net.http.WebSocketHandshakeException) ex.getCause()).getResponse().statusCode());
        } finally {
            app.stop();
        }
    }
}
