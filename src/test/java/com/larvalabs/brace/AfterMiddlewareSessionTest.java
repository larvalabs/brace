package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M6: after-middleware returning a new Result instance must not drop the session Set-Cookie.
 *
 * Previously the session cookie was attached to the Result before after-middleware ran.
 * Any middleware that returned a new Result instance (e.g. wrapping/replacing the body)
 * silently discarded the cookie, so logins behind such middleware would not stick.
 *
 * Fix: run after-middleware first, then attach the session cookie to the surviving Result.
 */
class AfterMiddlewareSessionTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app()
            .port(0)
            .sessions("m6-test-secret-at-least-32-characters-x");

        // After-middleware that returns a BRAND NEW Result — simulates a body-rewriting wrapper.
        app.after((req, result) -> Result.text("[wrapped] " + result.body()));

        // Login handler: modifies the session, returns a result whose body the middleware wraps.
        // csrf(false) because this test is specifically about session-cookie persistence through
        // after-middleware, not about CSRF — avoid needing a preflight GET just for the token.
        app.post("/login", (SessionHandler) (req, session) -> {
            session.set("user", "alice");
            return Result.text("ok");
        }).csrf(false);

        // Protected handler: reads from session
        app.get("/whoami", (SessionHandler) (req, session) -> {
            String user = session.get("user");
            return Result.text(user != null ? user : "anonymous");
        });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    /**
     * Core regression for M6: after-middleware returns a new Result on the login request,
     * but the session cookie must still be present on the response so that the follow-up
     * request sees the session value.
     */
    @Test
    void sessionCookieSurvivesAfterMiddlewareThatReplacesResult() throws Exception {
        var cookieManager = new java.net.CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

        // POST /login — middleware will return a new Result wrapping the body
        var loginResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/login"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, loginResp.statusCode());

        // The Set-Cookie header must be present despite middleware returning a new Result
        List<String> setCookieHeaders = loginResp.headers().allValues("Set-Cookie");
        assertTrue(
            setCookieHeaders.stream().anyMatch(v -> v.startsWith("brace_session=")),
            "Expected brace_session Set-Cookie on login response even when after-middleware " +
            "returned a new Result instance; got Set-Cookie headers: " + setCookieHeaders);

        // Follow-up GET must see the session value (cookie manager replays it automatically)
        var whoamiResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/whoami"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, whoamiResp.statusCode());
        // Note: /whoami body is also wrapped by after-middleware
        assertTrue(whoamiResp.body().contains("alice"),
            "Expected session to contain 'alice' but got: " + whoamiResp.body());
    }
}
