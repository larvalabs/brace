package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for explicit CSRF opt-out mechanism.
 * Verifies that routes require CSRF by default and can explicitly opt out.
 */
class CsrfExplicitTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app()
            .port(0)
            .sessions("test-csrf-explicit-secret-32-chars");

        // Regular POST endpoint (CSRF required by default)
        app.post("/form-submit", (SessionHandler) (req, session) -> Result.text("ok"));

        // JSON API endpoint with explicit CSRF opt-out (e.g., bearer token auth)
        app.post("/api/public", req -> Json.of(java.util.Map.of("status", "ok"))).csrf(false);

        // Cookie-authenticated JSON endpoint (CSRF still required)
        app.post("/api/private", (SessionHandler) (req, session) -> {
            String username = session.get("username");
            return Json.of(java.util.Map.of("user", username != null ? username : "guest"));
        });

        // Session initialization endpoint
        app.get("/init", (SessionHandler) (req, session) -> {
            session.set("username", "alice");
            Csrf.ensureToken(session);
            return Result.text("token:" + Csrf.getToken(session));
        });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @Test
    void formPostRequiresCsrfByDefault() throws Exception {
        var client = HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager()).build();

        // Initialize session
        client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/init"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        // POST without CSRF token should fail
        var response = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/form-submit"))
            .POST(HttpRequest.BodyPublishers.ofString("data=hello"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    void jsonApiWithCsrfFalseSkipsValidation() throws Exception {
        // No session, no CSRF token - should succeed because csrf(false)
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/public"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"data\":\"value\"}"))
            .header("Content-Type", "application/json")
            .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"ok\""));
    }

    @Test
    void cookieAuthenticatedJsonStillRequiresCsrf() throws Exception {
        var client = HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager()).build();

        // Initialize session and get CSRF token
        var initResponse = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/init"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        var token = initResponse.body().replace("token:", "");

        // POST to cookie-authenticated JSON endpoint without CSRF should fail
        var response = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/private"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"data\":\"value\"}"))
            .header("Content-Type", "application/json")
            .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    void cookieAuthenticatedJsonWithCsrfSucceeds() throws Exception {
        var client = HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager()).build();

        // Initialize session and get CSRF token
        var initResponse = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/init"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        var token = initResponse.body().replace("token:", "");

        // POST with X-CSRF-Token header should succeed
        var response = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/private"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"data\":\"value\"}"))
            .header("Content-Type", "application/json")
            .header("X-CSRF-Token", token)
            .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"user\":\"alice\""));
    }
}
