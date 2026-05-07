package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;

public class CliAuth {

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final int DEFAULT_TTL_SECONDS = 3600;

    private CliAuth() {}

    /**
     * Thrown by {@link #bearer} when /ops/auth returns a non-200. Carries the raw
     * status code, body, and (if the body is JSON with an "error" field) a parsed
     * machine-readable code so callers can route remediation.
     */
    public static class OpsAuthFailure extends RuntimeException {
        public final int status;
        public final String body;
        public final String code;

        OpsAuthFailure(int status, String body) {
            super("Authentication failed (" + status + "): " + body);
            this.status = status;
            this.body = body;
            this.code = parseCode(body);
        }

        private static String parseCode(String body) {
            if (body == null || body.isEmpty()) return null;
            try {
                JsonNode n = Json.mapper().readTree(body);
                if (n.has("error")) return n.get("error").asText();
            } catch (Exception ignored) {}
            return null;
        }
    }

    public static String bearer(CliConfig cfg, Path projectDir) throws Exception {
        return bearer(cfg, projectDir, false);
    }

    /**
     * Send an authenticated HTTP request, transparently refreshing the bearer
     * token if the server rejects it. The supplied builder must not carry an
     * Authorization header — the helper adds one. On a 401 response the cached
     * token is discarded, a fresh one is minted, and the request is sent once
     * more. A second 401 is returned to the caller.
     */
    public static HttpResponse<String> sendAuthenticated(
            CliConfig cfg, Path projectDir, HttpRequest.Builder builder) throws Exception {
        String token = bearer(cfg, projectDir);
        var response = http.send(
            builder.copy().header("Authorization", "Bearer " + token).build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            clearCache(projectDir);
            token = bearer(cfg, projectDir);
            response = http.send(
                builder.copy().header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    private static String bearer(CliConfig cfg, Path projectDir, boolean retried) throws Exception {
        var cached = readCache(projectDir);
        if (cached != null) return cached;

        var kp = loadKeypair(cfg);

        String timestamp = Instant.now().toString();
        String signature = OpsKeys.sign(timestamp, kp.privateKey());
        var body = Map.of(
            "publicKey", kp.publicKey(),
            "timestamp", timestamp,
            "signature", signature,
            "ttlSeconds", DEFAULT_TTL_SECONDS);

        var response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 && !retried) {
            clearCache(projectDir);
            return bearer(cfg, projectDir, true);
        }
        if (response.statusCode() != 200) {
            throw new OpsAuthFailure(response.statusCode(), response.body());
        }

        JsonNode parsed = Json.mapper().readTree(response.body());
        String token = parsed.get("token").asText();
        String expiresAt = parsed.get("expiresAt").asText();

        writeCache(projectDir, token, expiresAt);
        return token;
    }

    public static void clearCache(Path projectDir) throws Exception {
        Files.deleteIfExists(projectDir.resolve("target").resolve(".brace-token"));
    }

    private static OpsKeys.Keypair loadKeypair(CliConfig cfg) throws Exception {
        Path keyFile = Path.of(cfg.keyPath());
        if (Files.exists(keyFile)) {
            return OpsKeys.readKeyFile(cfg.keyPath());
        }
        String envKey = System.getenv("OPS_PRIVATE_KEY");
        if (envKey == null || envKey.isEmpty()) {
            throw new RuntimeException("Private key not found at " + cfg.keyPath()
                + " and OPS_PRIVATE_KEY env var not set.");
        }
        Path authPath = Path.of(cfg.authorizedKeysPath());
        if (!Files.exists(authPath)) {
            throw new RuntimeException("Cannot match OPS_PRIVATE_KEY without "
                + cfg.authorizedKeysPath());
        }
        var authorizedKeys = OpsKeys.loadAuthorizedKeys(authPath.toString());
        var testSig = OpsKeys.sign("test", envKey);
        for (var pub : authorizedKeys) {
            if (OpsKeys.verify("test", testSig, pub)) {
                return new OpsKeys.Keypair(pub, envKey);
            }
        }
        throw new RuntimeException("OPS_PRIVATE_KEY does not match any authorized key.");
    }

    private static String readCache(Path projectDir) {
        try {
            Path file = projectDir.resolve("target").resolve(".brace-token");
            if (!Files.exists(file)) return null;
            JsonNode node = Json.mapper().readTree(Files.readString(file));
            String expiresAt = node.get("expiresAt").asText();
            if (Instant.parse(expiresAt).minusSeconds(60).isBefore(Instant.now())) return null;
            return node.get("token").asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Path projectDir, String token, String expiresAt) {
        try {
            Path target = projectDir.resolve("target");
            Files.createDirectories(target);
            String json = Json.mapper().writeValueAsString(Map.of("token", token, "expiresAt", expiresAt));
            Files.writeString(target.resolve(".brace-token"), json);
        } catch (Exception e) {
            // Caching is best-effort
        }
    }
}
