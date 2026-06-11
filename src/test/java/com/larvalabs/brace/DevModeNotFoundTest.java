package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L3: in dev mode (brace.mode=dev, read at handler construction) the no-route-matched 404
 * body lists near-miss registered routes; production behavior is unchanged ("Not Found").
 */
class DevModeNotFoundTest {

    static Brace devApp;
    static Brace prodApp;
    static int devPort;
    static int prodPort;
    static String originalMode;
    static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void startApps() throws Exception {
        originalMode = System.getProperty("brace.mode");

        // Dev app: brace.mode=dev must be set when the handler is constructed (app.start()).
        System.setProperty("brace.mode", "dev");
        devApp = Brace.app().port(0);
        registerRoutes(devApp);
        devApp.start();
        devPort = devApp.actualPort();

        // Prod app: same routes, property cleared before construction.
        System.clearProperty("brace.mode");
        prodApp = Brace.app().port(0);
        registerRoutes(prodApp);
        prodApp.start();
        prodPort = prodApp.actualPort();
    }

    static void registerRoutes(Brace app) {
        app.get("/users/{id}", req -> Result.text("user"));
        app.get("/users", req -> Result.text("users"));
        app.get("/admin", req -> Result.text("admin"));
        app.post("/users", (Handler) req -> Result.text("created")).csrf(false);
        // Seven /api routes sharing a prefix — for the cap-of-5 test.
        for (int i = 1; i <= 7; i++) {
            String path = "/api/route" + i;
            app.get(path, req -> Result.text("api"));
        }
        // Existing route whose handler throws NotFoundException deliberately.
        app.get("/gone", req -> { throw new NotFoundException(); });
    }

    @AfterAll
    static void stopApps() throws Exception {
        if (originalMode != null) {
            System.setProperty("brace.mode", originalMode);
        } else {
            System.clearProperty("brace.mode");
        }
        if (devApp != null) devApp.stop();
        if (prodApp != null) prodApp.stop();
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void devModeNotFoundListsNearMissRoutes() throws Exception {
        var response = get(devPort, "/user/42");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().startsWith("Not Found: GET /user/42"), response.body());
        assertTrue(response.body().contains("GET /users/{id}"), response.body());
        assertTrue(response.body().contains("GET /users"), response.body());
    }

    @Test
    void devModePrefersPrefixSharingPatterns() throws Exception {
        // /users patterns share the "/user" prefix; /admin and /api do not qualify once
        // prefix-sharing candidates exist.
        var body = get(devPort, "/user/42").body();
        assertFalse(body.contains("/admin"), body);
        assertFalse(body.contains("/api"), body);
    }

    @Test
    void devModeOnlySuggestsSameMethodRoutes() throws Exception {
        // POST /users is registered, but a GET request only gets GET suggestions.
        var body = get(devPort, "/user/42").body();
        assertFalse(body.contains("POST"), body);
    }

    @Test
    void devModeCapsSuggestionsAtFive() throws Exception {
        var body = get(devPort, "/api/nope");
        assertEquals(404, body.statusCode());
        long suggested = body.body().split("GET /api/route", -1).length - 1;
        assertEquals(5, suggested, body.body());
    }

    @Test
    void devModeFallsBackToSameMethodPatternsWhenNoPrefixMatch() throws Exception {
        // No registered pattern shares more than "/" with /zzz — fall back, still capped.
        var response = get(devPort, "/zzz");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().startsWith("Not Found: GET /zzz"), response.body());
        assertTrue(response.body().contains("registered:"), response.body());
    }

    @Test
    void devModeNotFoundExceptionPathStaysPlain() throws Exception {
        // A handler on an existing route deliberately threw 404 — no route suggestions.
        var response = get(devPort, "/gone");
        assertEquals(404, response.statusCode());
        assertEquals("Not Found", response.body());
    }

    @Test
    void productionNotFoundIsUnchanged() throws Exception {
        var response = get(prodPort, "/user/42");
        assertEquals(404, response.statusCode());
        assertEquals("Not Found", response.body());
    }
}
