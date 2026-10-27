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
        var cached = readCache(cacheFile(projectDir, cfg));
        if (cached != null) return cached;

        var kp = loadKeypair(cfg);

        // Ops auth protocol v3: sign the server's origin, our key, a timestamp and a fresh nonce.
        // The origin binds the signature to the server this config points at (M14); the nonce
        // lets the server reject a replay of a captured request (see OpsHandler.auth).
        String origin = originOf(cfg);
        noteOrigin(cfg, projectDir, origin);
        String timestamp = Instant.now().toString();
        byte[] nonceBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(nonceBytes);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String signature = OpsKeys.sign(OpsKeys.v3AuthMessage(origin, kp.publicKey(), timestamp, nonce), kp.privateKey());
        var body = Map.of(
            "v", "3",
            "origin", origin,
            "publicKey", kp.publicKey(),
            "timestamp", timestamp,
            "nonce", nonce,
            "signature", signature,
            "ttlSeconds", DEFAULT_TTL_SECONDS);

        var response = postAuth(cfg, body);

        if (response.statusCode() == 401 && !retried) {
            clearCache(projectDir);
            return bearer(cfg, projectDir, true);
        }
        // The v1 fallback that used to live here (retry with a timestamp-only signature when a
        // pre-0.1.7 server 401s the v2 body) went away with server-side v1 support in 0.1.8 (M5).
        // A 401 now means what it says.
        if (response.statusCode() != 200) {
            throw new OpsAuthFailure(response.statusCode(), response.body());
        }
        return cacheToken(cacheFile(projectDir, cfg), response);
    }

    private static HttpResponse<String> postAuth(CliConfig cfg, Map<String, ?> body) throws Exception {
        return http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String cacheToken(Path file, HttpResponse<String> response) throws Exception {
        JsonNode parsed = Json.mapper().readTree(response.body());
        String token = parsed.get("token").asText();
        String expiresAt = parsed.get("expiresAt").asText();
        writeCache(file, token, expiresAt);
        return token;
    }

    /**
     * The token cache for the server {@code cfg} points at (M14). One file per origin, so a token
     * minted for prod is never sent to another URL: the URL comes from the committed {@code .brace},
     * which a pull request can change.
     */
    static Path cacheFile(Path projectDir, CliConfig cfg) {
        String origin = originOf(cfg);
        String id = OpsKeys.fingerprint(origin); // base64url: safe in a file name
        return projectDir.resolve("target").resolve(".brace-token-" + id);
    }

    /** {@code ~/.brace}, or {@code -Dbrace.user.dir} (the framework's own tests point it at a temp dir). */
    static Path userBraceDir() {
        String override = System.getProperty("brace.user.dir");
        return override != null ? Path.of(override) : Path.of(System.getProperty("user.home"), ".brace");
    }

    static String originOf(CliConfig cfg) {
        String url = cfg.url();
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new IllegalArgumentException("ops." + cfg.env() + ".url must start with https:// (or http:// for local): " + url);
        }
        return Brace.normalizeOrigin(url);
    }

    /** Removes every cached token for this project, whatever server it was for. */
    public static void clearCache(Path projectDir) throws Exception {
        Path target = projectDir.resolve("target");
        Files.deleteIfExists(target.resolve(".brace-token")); // pre-0.1.11, not keyed by server
        if (!Files.isDirectory(target)) return;
        try (var files = Files.newDirectoryStream(target, ".brace-token-*")) {
            for (Path f : files) Files.deleteIfExists(f);
        }
    }

    /**
     * Warn when this project's {@code ops.<env>.url} points somewhere other than the last server
     * the CLI logged in to for it (M14): a changed {@code .brace} in a pull request is how a
     * signature or token would be sent to the wrong place. Remembered under the user's
     * {@code ~/.brace}, so a {@code mvn clean} doesn't forget it.
     */
    static void noteOrigin(CliConfig cfg, Path projectDir, String origin) {
        try {
            Path file = userBraceDir().resolve("ops-origins.json");
            var known = new java.util.TreeMap<String, String>();
            if (Files.exists(file)) {
                Json.mapper().readTree(Files.readString(file)).fields()
                    .forEachRemaining(e -> known.put(e.getKey(), e.getValue().asText()));
            }
            String key = projectDir.toAbsolutePath().normalize() + "#" + cfg.env();
            String previous = known.get(key);
            if (previous != null && !previous.equals(origin)) {
                System.err.println("warning: ops." + cfg.env() + ".url is now " + origin + "; you last logged in to "
                    + previous + " from this project. If you didn't change it, check .brace and .brace.local "
                    + "before going on: this login is signed for " + origin + " only.");
            }
            if (!origin.equals(previous)) {
                known.put(key, origin);
                Files.createDirectories(file.getParent());
                SecretFiles.writeStringWithOwnerOnlyPermissions(file, Json.mapper().writeValueAsString(known));
            }
        } catch (Exception e) {
            // Best-effort: a warning, never a reason to fail the command.
        }
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
        for (var pub : authorizedKeys.keySet()) {
            if (OpsKeys.verify("test", testSig, pub)) {
                return new OpsKeys.Keypair(pub, envKey);
            }
        }
        throw new RuntimeException("OPS_PRIVATE_KEY does not match any authorized key.");
    }

    private static String readCache(Path file) {
        try {
            if (!Files.exists(file)) return null;
            JsonNode node = Json.mapper().readTree(Files.readString(file));
            String expiresAt = node.get("expiresAt").asText();
            if (Instant.parse(expiresAt).minusSeconds(60).isBefore(Instant.now())) return null;
            return node.get("token").asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Path file, String token, String expiresAt) {
        try {
            Files.createDirectories(file.getParent());
            String json = Json.mapper().writeValueAsString(Map.of("token", token, "expiresAt", expiresAt));
            SecretFiles.writeStringWithOwnerOnlyPermissions(file, json);
        } catch (Exception e) {
            // Caching is best-effort
        }
    }
}
