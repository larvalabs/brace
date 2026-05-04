package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class CsrfTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app()
            .port(0)
            .sessions("test-csrf-secret-at-least-32-characters");

        // GET endpoint that shows csrf token
        app.get("/form", (SessionHandler) (req, session) -> {
            Csrf.ensureToken(session);
            return Result.text("token:" + Csrf.getToken(session));
        });

        // POST endpoint protected by CSRF
        app.post("/submit", (SessionHandler) (req, session) -> {
            return Result.text("submitted");
        });

        // POST JSON endpoint (explicitly opt out of CSRF for bearer-token auth)
        app.post("/api/data", req -> Json.of(java.util.Map.of("ok", true))).csrf(false);

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @Test
    void postWithoutCsrfTokenReturns403() throws Exception {
        // First GET to establish session
        var client = HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager()).build();
        client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/form")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        // POST without CSRF token
        var response = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/submit"))
            .POST(HttpRequest.BodyPublishers.ofString("data=hello"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
    }

    @Test
    void postWithValidCsrfTokenSucceeds() throws Exception {
        var client = HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager()).build();

        // GET to get token
        var formResponse = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/form")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        var token = formResponse.body().replace("token:", "");

        // POST with valid token
        var response = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/submit"))
            .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + token + "&data=hello"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("submitted", response.body());
    }

    @Test
    void jsonPostSkipsCsrf() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/data"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"value\"}"))
            .header("Content-Type", "application/json")
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }
}
