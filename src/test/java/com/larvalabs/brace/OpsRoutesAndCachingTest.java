package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Follow-ups from the 2026-10 review's whole-branch pass: ops responses stay {@code no-store}
 * whatever the app's after-middleware sets, a session cookie never rides a {@code public}
 * response, WebSocket routes appear in {@code /ops/routes}, and a WebSocket path can't be
 * registered twice.
 */
class OpsRoutesAndCachingTest {

    static Brace app;
    static int port;
    static OpsKeys.Keypair keypair;
    static final HttpClient client = HttpClient.newHttpClient();
    @TempDir static Path tmp;

    public static class Echo {
        public Echo(WsContext ws) {}
    }

    @BeforeAll
    static void start() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keys = tmp.resolve("authorized-keys");
        Files.writeString(keys, keypair.publicKey() + " test\n");
        Path assets = Files.createDirectories(tmp.resolve("assets"));
        Files.writeString(assets.resolve("app.css"), "body{}");

        app = Brace.app().port(0).banner(false).ops(keys.toString())
            .sessions("ops-routes-caching-test-secret-0123456789")
            .staticFiles("/assets", assets.toString());
        // An app that marks everything publicly cacheable.
        app.after((req, res) -> res.header("Cache-Control", "public, max-age=300"));
        // A session-touching middleware: every response carries the session cookie.
        app.before("/*", (Middleware.BeforeSession) (req, session) -> {
            session.set("lastSeen", "now");
            return null;
        });
        app.ws("/live", Echo::new).publicRoute();
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        app.stop();
    }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String bearer() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        byte[] nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        String n = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String signature = OpsKeys.sign(
            OpsKeys.v3AuthMessage("http://localhost", keypair.publicKey(), timestamp, n), keypair.privateKey());
        String body = "{\"v\":\"3\",\"origin\":\"http://localhost\",\"publicKey\":\"" + keypair.publicKey()
            + "\",\"timestamp\":\"" + timestamp + "\",\"nonce\":\"" + n + "\",\"signature\":\"" + signature + "\"}";
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ops/auth"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null), "the token response");
        return Json.mapper().readTree(response.body()).get("token").asText();
    }

    @Test
    void opsResponsesAreNoStoreWhateverTheAppSets() throws Exception {
        var unauthorized = get("/ops/status", null);
        assertEquals(401, unauthorized.statusCode());
        assertEquals("no-store", unauthorized.headers().firstValue("Cache-Control").orElse(null));
        var routes = get("/ops/routes", bearer());
        assertEquals(200, routes.statusCode());
        assertEquals("no-store", routes.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void opsRoutesListsWebSocketRoutes() throws Exception {
        var body = get("/ops/routes", bearer()).body();
        assertTrue(body.contains("\"method\":\"WS\",\"pattern\":\"/live\",\"access\":\"public\""), body);
    }

    @Test
    void aSessionCookieNeverRidesAPublicResponse() throws Exception {
        var css = get("/assets/app.css", null);
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("Set-Cookie").isPresent(), "the middleware touched the session");
        String cacheControl = css.headers().firstValue("Cache-Control").orElse("");
        assertFalse(cacheControl.toLowerCase().contains("public"), cacheControl);
        assertTrue(cacheControl.contains("private"), cacheControl);
    }

    @Test
    void aWebSocketPathCantBeRegisteredTwice() {
        var other = Brace.app();
        other.ws("/chat", Echo::new);
        var e = assertThrows(IllegalStateException.class, () -> other.ws("/chat/", Echo::new));
        assertTrue(e.getMessage().contains("registered twice"), e.getMessage());
    }
}
