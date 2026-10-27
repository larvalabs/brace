package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M13: {@code GET /__brace/health} is a liveness probe that answers before routing and
 * middleware, stays out of stats, and is what {@link HealthCheck} polls; {@code app.host(...)}
 * sets the listen address.
 */
class HealthRouteTest {

    static Brace app;
    static int port;
    static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        app = Brace.app().port(0).host("127.0.0.1").banner(false);
        // An app-wide guard that refuses everything: the probe must still answer.
        app.before(req -> Result.forbidden("denied"));
        app.get("/", req -> Result.text("home")).publicRoute();
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        app.stop();
    }

    private static HttpResponse<String> send(String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAnswersEvenWhenMiddlewareRejectsEverything() throws Exception {
        assertEquals(403, send("GET", "/").statusCode(), "the guard is in force");

        var response = send("GET", "/__brace/health");
        assertEquals(200, response.statusCode());
        assertEquals("ok\n", response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
    }

    @Test
    void headIsAnsweredWithoutABody() throws Exception {
        var response = send("HEAD", "/__brace/health");
        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void onlyGetAndHeadAreTheProbe() throws Exception {
        // Anything else goes through the normal pipeline, where the guard refuses it.
        assertEquals(403, send("POST", "/__brace/health").statusCode());
    }

    @Test
    void probesAreNotCountedAsRequests() throws Exception {
        long before = app.stats().totalRequests();
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send("GET", "/__brace/health").statusCode());
        }
        assertEquals(before, app.stats().totalRequests());
    }

    @Test
    void healthCheckProbeReportsUpAndDown() throws Exception {
        assertTrue(HealthCheck.check(port));
        int unused;
        try (var socket = new ServerSocket(0)) {
            unused = socket.getLocalPort();
        }
        assertFalse(HealthCheck.check(unused));
    }

    @Test
    void hostIsTheListenAddress() {
        assertEquals("127.0.0.1", app.host());
        assertNull(Brace.app().host(" ").host(), "blank restores the default (every interface)");
        assertNull(Brace.app().host(), "default: every interface");
    }
}
