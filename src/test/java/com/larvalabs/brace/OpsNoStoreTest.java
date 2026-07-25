package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 (2026-07 security review): only {@code /ops/auth/exchange} set {@code Cache-Control:
 * no-store}. Every other ops endpoint relied on the caller's credential channel to suppress
 * caching — which RFC 9111 guarantees for an {@code Authorization} header but not for the
 * {@code __brace_ops_session} cookie the browser uses. {@code /ops/dashboard} embeds a live
 * bearer token in its HTML, so a caching intermediary could hand one operator's token to the
 * next requester.
 *
 * <p>The coverage test loops over the registered routes rather than naming endpoints, so a
 * new ops endpoint is covered without anyone remembering to add it here.
 */
class OpsNoStoreTest {

    static Brace app;
    static int port;
    static OpsKeys.Keypair keypair;
    @TempDir static Path tmp;

    @BeforeAll
    static void startApp() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");

        app = Brace.app().port(0)
            .sessions("ops-nostore-secret-at-least-32-characters")
            .ops(keysFile.toString());
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.stop();
    }

    private static String bearer() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        byte[] nonceBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(nonceBytes);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String signature = OpsKeys.sign(
            OpsKeys.v2AuthMessage(keypair.publicKey(), timestamp, nonce), keypair.privateKey());
        String body = "{\"v\":\"2\",\"publicKey\":\"" + keypair.publicKey() + "\","
            + "\"timestamp\":\"" + timestamp + "\",\"nonce\":\"" + nonce + "\","
            + "\"signature\":\"" + signature + "\"}";
        var resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "auth failed: " + resp.body());
        return Json.mapper().readTree(resp.body()).get("token").asText();
    }

    @Test
    void everyOpsGetEndpointIsNoStore() throws Exception {
        String token = bearer();
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        int checked = 0;
        for (var route : app.routes()) {
            if (!route.pattern().startsWith("/ops/") || !route.method().equals("GET")) continue;
            // Substitute a concrete id for the one parameterised GET route.
            String path = route.pattern().replace("{id}", "1");
            var resp = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals("no-store", resp.headers().firstValue("Cache-Control").orElse(null),
                "missing no-store on " + route.method() + " " + path
                    + " (status " + resp.statusCode() + ")");
            checked++;
        }
        assertTrue(checked >= 7, "expected to check the ops GET surface, only saw " + checked);
    }

    @Test
    void dashboardEmbeddingATokenIsNoStore() throws Exception {
        var resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ops/dashboard"))
                .header("Authorization", "Bearer " + bearer()).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("Bearer "), "dashboard should embed a token");
        assertEquals("no-store", resp.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void authEndpointIsNoStore() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        var resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"publicKey\":\"nope\",\"timestamp\":\"" + timestamp + "\",\"signature\":\"x\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals("no-store", resp.headers().firstValue("Cache-Control").orElse(null),
            "even the 401 must not be cached");
    }
}
