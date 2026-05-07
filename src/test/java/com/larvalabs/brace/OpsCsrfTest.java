package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: ops POST routes used to inherit the framework's CSRF default,
 * so any host app that called .sessions(...) returned 403 Forbidden on every
 * CLI request — the framework rejected the body before OpsHandler.auth ran.
 * Discovered against wendell during a prod debugging session.
 */
class OpsCsrfTest {

    static final HttpClient client = HttpClient.newHttpClient();

    @TempDir Path tmp;

    private Brace app;
    private OpsKeys.Keypair keypair;
    private int port;

    @BeforeEach
    void start() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");

        // Sessions enabled is the trigger for the original bug.
        app = Brace.app()
            .port(0)
            .sessions("ops-csrf-test-secret-at-least-32-characters")
            .ops(keysFile.toString());
        app.start();
        port = app.actualPort();
    }

    @AfterEach
    void stop() throws Exception {
        if (app != null) app.stop();
    }

    private HttpResponse<String> postAuth() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        String signature = OpsKeys.sign(timestamp, keypair.privateKey());
        String body = "{\"publicKey\":\"" + keypair.publicKey() + "\","
            + "\"timestamp\":\"" + timestamp + "\","
            + "\"signature\":\"" + signature + "\"}";
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void opsAuthSucceedsEvenWhenSessionsEnabled() throws Exception {
        var response = postAuth();
        assertEquals(200, response.statusCode(),
            "POST /ops/auth must not be CSRF-blocked on apps with sessions enabled. body=" + response.body());
        assertTrue(response.body().contains("\"token\""), response.body());
    }

    @Test
    void csrfRejectionUsesStructuredJsonBody() throws Exception {
        // Register a normal POST route that isn't csrf(false). Sessions are on, so it should
        // be blocked by CSRF — and the body should be machine-readable.
        app.post("/normal-mutating-route", req -> Result.text("ok"));

        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/normal-mutating-route"))
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),
            "csrf rejection should be JSON, got: " + response.headers().firstValue("Content-Type"));
        assertTrue(response.body().contains("\"error\""), response.body());
        assertTrue(response.body().contains("csrf_required"), response.body());
    }

    @Test
    void resolveErrorAndCacheClearAreNotCsrfBlocked() throws Exception {
        // Authenticate to get a bearer token.
        var authResp = postAuth();
        assertEquals(200, authResp.statusCode());
        int tokenStart = authResp.body().indexOf("\"token\":\"") + 9;
        int tokenEnd = authResp.body().indexOf("\"", tokenStart);
        String token = authResp.body().substring(tokenStart, tokenEnd);

        // /ops/cache/clear: cache is unconfigured here so handler returns 404, but the key
        // assertion is "not 403" — i.e. the CSRF gate didn't fire before the handler ran.
        var clearResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/cache/clear"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertNotEquals(403, clearResp.statusCode(),
            "POST /ops/cache/clear must not be CSRF-blocked. body=" + clearResp.body());

        // /ops/errors/{id}/resolve: the id won't match, but again the test is "not 403".
        var resolveResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/errors/0/resolve"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertNotEquals(403, resolveResp.statusCode(),
            "POST /ops/errors/{id}/resolve must not be CSRF-blocked. body=" + resolveResp.body());
    }
}
