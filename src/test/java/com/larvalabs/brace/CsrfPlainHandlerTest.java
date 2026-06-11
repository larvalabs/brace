package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M5 security fixes — three CSRF gaps.
 *
 * (a) PATCH is now treated as a mutating method (alongside POST/PUT/DELETE).
 * (b) SECURITY.md doc fix (no code test needed — the wrong "JSON exemption" claim was
 *     removed from the docs; existing CsrfExplicitTest already covers the behaviour).
 * (c) When a CSRF token is minted for a plain Handler (no Session param), the token is
 *     now persisted to a Set-Cookie so the subsequent POST can validate it.
 */
class CsrfPlainHandlerTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app()
            .port(0)
            .sessions("m5-test-secret-at-least-32-characters-xx");

        // Plain Handler (no Session param) — renders the CSRF hidden field.
        // Before M5c this minted a fresh token but never wrote the cookie, so the
        // follow-up POST always 403'd.
        app.get("/form-plain", req -> {
            // csrfField is auto-populated by the framework via View.setCsrfField().
            // We return it in the body so the test can extract it without a real template.
            String field = View.getCsrfField();
            return Result.text(field != null ? field : "no-csrf-field");
        });

        // POST endpoint that requires CSRF (default) — SessionHandler to show it works
        // end-to-end with a session that was started by the CSRF token mint.
        app.post("/submit-plain", (SessionHandler) (req, session) -> {
            return Result.text("ok:" + session.get("user"));
        });

        // SessionHandler route — CSRF token stored in handler's own session (existing behaviour)
        app.get("/form-session", (SessionHandler) (req, session) -> {
            Csrf.ensureToken(session);
            return Result.text(Csrf.getToken(session));
        });

        app.post("/submit-session", (SessionHandler) (req, session) -> {
            return Result.text("session-ok");
        });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    // -------------------------------------------------------------------------
    // M5c — plain Handler CSRF token must be persisted as a cookie
    // -------------------------------------------------------------------------

    @Test
    void getViaPlainHandlerSetsSessionCookie() throws Exception {
        var cookieManager = new java.net.CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

        var getResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/form-plain"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, getResp.statusCode());

        // A Set-Cookie with brace_session must be on the response — this was missing before M5c.
        List<String> setCookies = getResp.headers().allValues("Set-Cookie");
        assertTrue(
            setCookies.stream().anyMatch(v -> v.startsWith("brace_session=")),
            "Expected brace_session Set-Cookie on GET /form-plain (M5c); got: " + setCookies);
    }

    @Test
    void postWithCsrfTokenFromPlainHandlerSucceeds() throws Exception {
        var cookieManager = new java.net.CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

        // GET the form — framework mints token and sends brace_session cookie
        var getResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/form-plain"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, getResp.statusCode());

        // Extract the CSRF token value from the hidden input field
        String body = getResp.body();
        // body looks like: <input type="hidden" name="_csrf" value="TOKEN">
        String token = extractCsrfToken(body);
        assertNotNull(token, "Expected a CSRF token in the form body, got: " + body);

        // POST with the extracted token — the cookie manager replays brace_session automatically
        var postResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/submit-plain"))
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + token + "&data=hello"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, postResp.statusCode(),
            "Expected 200 but got " + postResp.statusCode() +
            " — CSRF token not accepted (M5c regression)");
    }

    @Test
    void postWithoutTokenFromPlainHandlerStill403() throws Exception {
        var cookieManager = new java.net.CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

        // GET to establish session+token
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/form-plain"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        // POST without the CSRF token — must still 403
        var postResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/submit-plain"))
                .POST(HttpRequest.BodyPublishers.ofString("data=hello"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(403, postResp.statusCode());
    }

    // -------------------------------------------------------------------------
    // Regression: SessionHandler routes still work as before
    // -------------------------------------------------------------------------

    @Test
    void sessionHandlerRouteStillWorksAfterM5c() throws Exception {
        var cookieManager = new java.net.CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

        // GET via SessionHandler
        var getResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/form-session"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, getResp.statusCode());
        String token = getResp.body().trim();
        assertFalse(token.isEmpty(), "Expected a CSRF token from /form-session");

        // POST with the token
        var postResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/submit-session"))
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + token + "&data=hello"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, postResp.statusCode());
        assertEquals("session-ok", postResp.body());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static String extractCsrfToken(String hiddenField) {
        // Parses: <input type="hidden" name="_csrf" value="TOKEN">
        int valueIndex = hiddenField.indexOf("value=\"");
        if (valueIndex < 0) return null;
        int start = valueIndex + "value=\"".length();
        int end = hiddenField.indexOf('"', start);
        if (end < 0) return null;
        return hiddenField.substring(start, end);
    }
}
