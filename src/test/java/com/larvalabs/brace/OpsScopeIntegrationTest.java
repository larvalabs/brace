package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end checks that a read-only ops key cannot reach control endpoints. */
class OpsScopeIntegrationTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @TempDir
    static Path tmpDir;

    static OpsKeys.Keypair controlKey;
    static OpsKeys.Keypair readKey;

    @BeforeAll
    static void startApp() throws Exception {
        controlKey = OpsKeys.generateKeypair();
        readKey = OpsKeys.generateKeypair();

        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile,
            controlKey.publicKey() + "  ops-laptop\n" +
            readKey.publicKey() + "  scope:read  oncall-agent\n");

        var cache = Brace.cache();
        cache.set("k", "v");
        var db = new DatabaseFactory(
            "jdbc:h2:mem:scopedb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null, List.of());

        app = Brace.app().port(0).ops(keysFile.toString()).cache(cache).database(db);
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    /** Mint a token for the given key, optionally requesting a scope. Returns the parsed JSON response. */
    private static JsonResponse authenticate(OpsKeys.Keypair kp, String requestedScope) throws Exception {
        String ts = java.time.Instant.now().toString();
        String sig = OpsKeys.sign(ts, kp.privateKey());
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("publicKey", kp.publicKey());
        body.put("timestamp", ts);
        body.put("signature", sig);
        if (requestedScope != null) body.put("scope", requestedScope);
        var resp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        var node = Json.mapper().readTree(resp.body());
        String token = node.has("token") ? node.get("token").asText() : null;
        String scope = node.has("scope") ? node.get("scope").asText() : null;
        return new JsonResponse(resp.statusCode(), token, scope);
    }

    record JsonResponse(int status, String token, String scope) {}

    private int getStatus(String path, String token) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int postStatus(String path, String token) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void readKeyMintsReadScopedToken() throws Exception {
        var auth = authenticate(readKey, null);
        assertEquals(200, auth.status());
        assertEquals("read", auth.scope(), "a read-ceiling key gets a read token by default");
    }

    @Test
    void readKeyCannotEscalateToControl() throws Exception {
        // Even asking for control, a read-ceiling key is capped at read.
        var auth = authenticate(readKey, "control");
        assertEquals("read", auth.scope(), "requested control must be down-capped to the key's read ceiling");
    }

    @Test
    void readTokenCanReadButNotControl() throws Exception {
        var read = authenticate(readKey, null).token();
        assertEquals(200, getStatus("/ops/status", read), "read token may read status");
        assertEquals(200, getStatus("/ops/cache", read), "read token may read cache stats");
        assertEquals(401, postStatus("/ops/cache/clear", read), "read token must NOT clear the cache");
    }

    @Test
    void controlTokenCanControl() throws Exception {
        var control = authenticate(controlKey, null).token();
        assertEquals("control", authenticate(controlKey, null).scope());
        assertEquals(200, getStatus("/ops/status", control));
        assertEquals(200, postStatus("/ops/cache/clear", control), "control token may clear the cache");
    }

    @Test
    void authenticatedAccessIsAudited() throws Exception {
        LogTap.clear();
        var read = authenticate(readKey, null).token();
        getStatus("/ops/status", read);               // granted
        postStatus("/ops/cache/clear", read);         // authenticated but scope-denied
        String kid = OpsKeys.fingerprint(readKey.publicKey());

        var access = LogTap.snapshot().stream()
            .filter(e -> "ops.access".equals(e.fields().get("event")))
            .toList();

        assertTrue(access.stream().anyMatch(e ->
                "/ops/status".equals(e.fields().get("path"))
                && Boolean.TRUE.equals(e.fields().get("granted"))
                && kid.equals(e.fields().get("kid"))),
            "granted read access must be audited and attributed to the key fingerprint");
        assertTrue(access.stream().anyMatch(e ->
                "/ops/cache/clear".equals(e.fields().get("path"))
                && Boolean.FALSE.equals(e.fields().get("granted"))),
            "an authenticated but scope-denied control attempt must be audited as granted=false");
    }
}
