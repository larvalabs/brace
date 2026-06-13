package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H5 — the CSRF token is minted lazily, and CSRF-exempt routes pay no session work.
 *
 * Before H5 the token-ensure block ran for every matched route when sessions were
 * enabled: it decrypted the session cookie, minted a token if absent, and therefore
 * wrote a Set-Cookie on every response to a cookieless client — including bearer-token
 * API routes that had explicitly opted out with .csrf(false), and JSON/redirect
 * responses that never render a form. Now the cookie is decrypted at most once per
 * request, only on csrf-required routes, and the token is minted only when something
 * consumes the hidden field (View.of / View.getCsrfField).
 */
class CsrfLazyMintTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app()
            .port(0)
            .banner(false)
            .sessions("h5-test-secret-at-least-32-characters-xx");

        // CSRF-exempt API route (bearer-token style).
        app.post("/api/thing", req -> Json.of(Map.of("ok", true))).csrf(false);
        app.get("/api/thing", req -> Json.of(Map.of("ok", true))).csrf(false);

        // CSRF-required (default) route that never renders a form — JSON out.
        app.get("/data", req -> Json.of(Map.of("n", 1)));

        // CSRF-required route that consumes the hidden field (simulates a form render).
        app.get("/form", req -> {
            String field = View.getCsrfField();
            return Result.text(field != null ? field : "no-csrf-field");
        });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        // Fresh client, no cookie store replay — simulates a cookieless client.
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> sessionCookies(HttpResponse<String> resp) {
        return resp.headers().allValues("Set-Cookie").stream()
            .filter(v -> v.startsWith("brace_session=")).toList();
    }

    @Test
    void csrfExemptRouteSetsNoSessionCookie() throws Exception {
        var resp = get("/api/thing");
        assertEquals(200, resp.statusCode());
        assertTrue(sessionCookies(resp).isEmpty(),
            ".csrf(false) route must not mint a token or set a session cookie; got: "
                + sessionCookies(resp));
    }

    @Test
    void csrfExemptPostNeedsNoToken() throws Exception {
        var resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/thing"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(sessionCookies(resp).isEmpty());
    }

    @Test
    void csrfRequiredRouteWithoutFormRenderSetsNoSessionCookie() throws Exception {
        // Lazy mint: a JSON response on a csrf-required route never consumes the hidden
        // field, so no token is minted and no Set-Cookie goes out (previously: one per
        // request to any cookieless client, forever).
        var resp = get("/data");
        assertEquals(200, resp.statusCode());
        assertTrue(sessionCookies(resp).isEmpty(),
            "no form was rendered, so no token should be minted; got: " + sessionCookies(resp));
    }

    @Test
    void csrfRequiredRouteConsumingFieldMintsAndSetsCookie() throws Exception {
        // The M5c invariant survives laziness: consuming the field mints the token AND
        // persists it as a cookie, so the rendered token is never orphaned.
        var resp = get("/form");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("name=\"_csrf\""), "hidden field should render");
        assertEquals(1, sessionCookies(resp).size(),
            "minted token must be persisted via Set-Cookie (M5c); got: " + sessionCookies(resp));
    }
}
