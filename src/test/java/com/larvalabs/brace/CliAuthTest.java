package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CliAuthTest {

    static Brace app;
    static int port;
    static OpsKeys.Keypair keypair;
    @TempDir static Path tmp;

    @BeforeAll
    static void start() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");
        // readKeyFile expects: line 0 = private key, line 1 = public key
        Files.writeString(tmp.resolve("ops-private.key"), keypair.privateKey() + "\n" + keypair.publicKey() + "\n");

        app = Brace.app().port(0).ops(keysFile.toString());
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception { app.stop(); }

    @BeforeEach
    void clearCache() throws Exception {
        Path cache = tmp.resolve("target").resolve(".brace-token");
        Files.deleteIfExists(cache);
    }

    @Test
    void freshFetchReturnsBearerToken() throws Exception {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("ops-private.key").toString(),
            "authorized-keys", "local", Map.of());
        String token = CliAuth.bearer(cfg, tmp);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void cachedTokenReused() throws Exception {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("ops-private.key").toString(),
            "authorized-keys", "local", Map.of());
        String first = CliAuth.bearer(cfg, tmp);
        Path tokenFile = tmp.resolve("target/.brace-token");
        assertTrue(Files.exists(tokenFile), "token should be cached on disk");
        String second = CliAuth.bearer(cfg, tmp);
        assertEquals(first, second);

        // Token cache file must be written with owner-only permissions
        assertTokenFileHasOwnerOnlyPermissions(tokenFile);
    }

    @Test
    void sendAuthenticatedRetriesOnStaleToken() throws Exception {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("ops-private.key").toString(),
            tmp.resolve("authorized-keys").toString(), "local", Map.of());

        // Prime the on-disk cache with a valid token.
        String valid = CliAuth.bearer(cfg, tmp);

        // Simulate a server-side restart: replace the cached token body with a
        // bogus one but keep the expiresAt timestamp in the future, so the
        // client will trust the cache and present the stale token.
        Path cachePath = tmp.resolve("target/.brace-token");
        String cached = Files.readString(cachePath);
        Files.writeString(cachePath, cached.replace(valid, "bogus.token.value"));

        var builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ops/status"))
            .header("Accept", "application/json")
            .GET();
        HttpResponse<String> response = CliAuth.sendAuthenticated(cfg, tmp, builder);

        assertEquals(200, response.statusCode(),
            "helper should clear the stale token, re-auth, and retry");
    }

    @Test
    void fallsBackToV1AgainstPre017Server() throws Exception {
        // Simulate a 0.1.6 /ops/auth: its OpsAuthRequest record has no v/nonce fields,
        // so Jackson fails on the unknown properties and the endpoint answers a plain
        // 401. A v1 body (signature over the timestamp only) is verified and accepted.
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ops/auth", exchange -> {
            int status;
            byte[] resp;
            try {
                JsonNode n = Json.mapper().readTree(exchange.getRequestBody().readAllBytes());
                if (n.has("v") || n.has("nonce")) {
                    status = 401;
                    resp = "Authentication failed".getBytes();
                } else if (keypair.publicKey().equals(n.get("publicKey").asText())
                        && OpsKeys.verify(n.get("timestamp").asText(),
                            n.get("signature").asText(), n.get("publicKey").asText())) {
                    status = 200;
                    resp = Json.mapper().writeValueAsString(Map.of(
                        "token", "v1-fallback-token",
                        "expiresAt", Instant.now().plusSeconds(3600).toString())).getBytes();
                } else {
                    status = 401;
                    resp = "Invalid signature".getBytes();
                }
            } catch (Exception e) {
                status = 500;
                resp = new byte[0];
            }
            exchange.sendResponseHeaders(status, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            var cfg = new CliConfig("http://localhost:" + server.getAddress().getPort(),
                tmp.resolve("ops-private.key").toString(),
                "authorized-keys", "local", Map.of());
            assertEquals("v1-fallback-token", CliAuth.bearer(cfg, tmp),
                "CLI should fall back to v1 auth against a pre-0.1.7 server");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unauthorizedClientThrowsOpsAuthFailureWithCode() throws Exception {
        // Run a second app whose authorized-keys file does NOT include this client's key.
        var serverKey = OpsKeys.generateKeypair();
        Path serverKeysFile = tmp.resolve("server-only-keys");
        Files.writeString(serverKeysFile, serverKey.publicKey() + " server-only\n");

        var unauthorizedApp = Brace.app().port(0).ops(serverKeysFile.toString());
        unauthorizedApp.start();
        try {
            var cfg = new CliConfig("http://localhost:" + unauthorizedApp.actualPort(),
                tmp.resolve("ops-private.key").toString(),
                "authorized-keys", "local", Map.of());

            var ex = assertThrows(CliAuth.OpsAuthFailure.class,
                () -> CliAuth.bearer(cfg, tmp));
            assertEquals(401, ex.status);
            assertNotNull(ex.body);
            assertTrue(ex.getMessage().contains("401"), ex.getMessage());
        } finally {
            unauthorizedApp.stop();
        }
    }

    @Test
    void opsAuthFailureParsesStructuredErrorCode() {
        var ex = new CliAuth.OpsAuthFailure(403, "{\"error\":\"csrf_required\"}");
        assertEquals(403, ex.status);
        assertEquals("csrf_required", ex.code);
        assertTrue(ex.getMessage().contains("403"));
    }

    @Test
    void opsAuthFailureToleratesPlainTextBody() {
        var ex = new CliAuth.OpsAuthFailure(403, "Forbidden");
        assertEquals(403, ex.status);
        assertNull(ex.code, "non-JSON body should not yield a code");
        assertEquals("Forbidden", ex.body);
    }

    @Test
    void opsAuthFailureToleratesEmptyBody() {
        var ex = new CliAuth.OpsAuthFailure(401, "");
        assertNull(ex.code);
    }

    @Test
    void missingKeyFileThrows() {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("does-not-exist.key").toString(),
            "authorized-keys", "local", Map.of());
        var ex = assertThrows(Exception.class, () -> CliAuth.bearer(cfg, tmp));
        assertTrue(ex.getMessage().toLowerCase().contains("key"));
    }

    private void assertTokenFileHasOwnerOnlyPermissions(Path tokenFile) {
        Assumptions.assumeTrue(isPosixFileSystem(), "test requires POSIX file system support");
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tokenFile);
            // Owner-only permissions should be: OWNER_READ, OWNER_WRITE (no others)
            assertEquals(PosixFilePermissions.fromString("rw-------"), perms,
                "Token file must have owner-only permissions (rw-------)");
        } catch (Exception e) {
            Assumptions.abort("POSIX file system not supported");
        }
    }

    private boolean isPosixFileSystem() {
        try {
            Files.getPosixFilePermissions(tmp);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
