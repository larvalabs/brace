package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end checks for response Set-Cookie multiplicity and request-body read ordering.
 * Uses a raw HttpClient (no CookieManager) so every Set-Cookie header is observable.
 */
class HeadersCookiesIntegrationTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0).sessions("test-secret-at-least-32-chars-long");

        // Two application cookies on one response — both must reach the wire.
        app.get("/two-cookies", req -> Result.text("ok")
            .cookie("a", "1", 3600, false, false, "Lax")
            .cookie("b", "2", 3600, false, false, "Lax"));

        // App cookie AND a session mutation: the framework's session cookie must not clobber
        // the handler's application cookie (the headline bug in the risk assessment).
        app.get("/app-cookie-plus-session", (SessionHandler) (req, session) -> {
            session.set("userId", 7);
            return Result.text("ok").cookie("theme", "dark", 3600, false, false, "Lax");
        });

        app.get("/ok", req -> Result.text("ok"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Test
    void twoApplicationCookiesBothSurvive() throws Exception {
        var resp = client().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/two-cookies")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        var cookies = resp.headers().allValues("Set-Cookie");
        assertEquals(2, cookies.size(), "Both cookies should be present, got: " + cookies);
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("a=1")));
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("b=2")));
    }

    @Test
    void sessionCookieDoesNotClobberAppCookie() throws Exception {
        var resp = client().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/app-cookie-plus-session")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        var cookies = resp.headers().allValues("Set-Cookie");
        assertEquals(2, cookies.size(), "App + session cookie should both be present, got: " + cookies);
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("theme=dark")), "app cookie lost: " + cookies);
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("brace_session=")), "session cookie lost: " + cookies);
    }

    @Test
    void postToUnmatchedRouteWithBodyStillReturns404() throws Exception {
        // Body is no longer read before route matching; an unmatched POST must still 404 cleanly.
        var resp = client().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/does-not-exist"))
                .POST(HttpRequest.BodyPublishers.ofString("some=body&more=data")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }
}
