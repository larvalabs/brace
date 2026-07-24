package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OpsIntegrationTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @TempDir
    static Path tmpDir;

    static OpsKeys.Keypair keypair;
    static String keysFilePath;

    @BeforeAll
    static void startApp() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test-key\n");
        keysFilePath = keysFile.toString();

        app = Brace.app().port(0).ops(keysFilePath);

        app.get("/hello", req -> Result.text("Hello!"));
        app.get("/error", req -> { throw new RuntimeException("test error"); });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    /** Authenticate via POST /ops/auth (protocol v2) and return a Bearer token. */
    private static String authenticate() throws Exception {
        return authenticate(port, keypair);
    }

    private static String authenticate(int targetPort, OpsKeys.Keypair kp) throws Exception {
        var response = postAuth(targetPort, v2AuthBody(kp));
        assertEquals(200, response.statusCode(), "Auth should succeed: " + response.body());
        // Extract token from {"token":"..."}
        String respBody = response.body();
        int start = respBody.indexOf("\"token\":\"") + 9;
        int end = respBody.indexOf("\"", start);
        return respBody.substring(start, end);
    }

    /** Build a v2 auth request body: signature over publicKey + "\n" + timestamp + "\n" + nonce. */
    private static String v2AuthBody(OpsKeys.Keypair kp) {
        String timestamp = java.time.Instant.now().toString();
        String nonce = freshNonce();
        String signature = OpsKeys.sign(OpsKeys.v2AuthMessage(kp.publicKey(), timestamp, nonce), kp.privateKey());
        return "{\"v\":\"2\",\"publicKey\":\"" + kp.publicKey() + "\",\"timestamp\":\"" + timestamp
            + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + signature + "\"}";
    }

    private static String freshNonce() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static HttpResponse<String> postAuth(int targetPort, String body) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + targetPort + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithToken(String path) throws Exception {
        String token = authenticate();
        return getWithToken(path, token);
    }

    private HttpResponse<String> getWithToken(String path, String token) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void opsStatusRequiresAuth() throws Exception {
        var response = get("/ops/status");
        assertEquals(401, response.statusCode());
    }

    @Test
    void opsStatusWithValidToken() throws Exception {
        // Make a few requests first to generate stats
        get("/hello");
        get("/hello");

        var response = getWithToken("/ops/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"app\""));
        assertTrue(response.body().contains("\"http\""));
        assertTrue(response.body().contains("\"jvm\""));
        assertTrue(response.body().contains("\"javaVersion\""));
    }

    @Test
    void opsRoutesWithValidToken() throws Exception {
        var response = getWithToken("/ops/routes");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("/hello"));
        assertTrue(response.body().contains("GET"));
    }

    @Test
    void statsRecordRequestsAfterTraffic() throws Exception {
        get("/hello");
        var response = getWithToken("/ops/status");
        assertTrue(response.body().contains("\"statusCodes\""));
    }

    @Test
    void opsDashboardRequiresAuth() throws Exception {
        var response = get("/ops/dashboard");
        assertEquals(401, response.statusCode());
    }

    @Test
    void opsDashboardWithValidToken() throws Exception {
        var token = authenticate();
        var response = getWithToken("/ops/dashboard", token);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(response.body().contains("Brace Ops"));
    }

    @Test
    void errorTracking() throws Exception {
        // Trigger an error — this app has no database, so the count falls back to the
        // in-memory Stats list. Summary entries carry no stack trace (H6).
        get("/error");
        var root = Json.mapper().readTree(getWithToken("/ops/status").body());
        var errors = root.path("errors");
        assertTrue(errors.path("count").asLong(0) >= 1, errors.toString());
        var first = errors.path("recent").get(0);
        assertNotNull(first, errors.toString());
        assertTrue(first.has("errorType"), first.toString());
        assertTrue(first.has("message"), first.toString());
        assertTrue(first.has("route"), first.toString());
        assertTrue(first.has("occurrenceCount"), first.toString());
        assertTrue(first.has("lastSeen"), first.toString());
        assertFalse(first.has("stackTrace"), "status summaries must not carry stackTrace: " + first);
    }

    @Test
    void opsStatusJvmSectionHasExpectedFields() throws Exception {
        // Profiler is attached (ops enabled), so heap/cpu/threads/gc are real data — but
        // profiling (hot methods + allocations) is opt-in via ?include=profiling (H6).
        var jvm = Json.mapper().readTree(getWithToken("/ops/status").body()).path("jvm");
        assertTrue(jvm.has("heap"), jvm.toString());
        assertTrue(jvm.has("cpu"), jvm.toString());
        assertTrue(jvm.has("threads"), jvm.toString());
        assertTrue(jvm.has("gc"), jvm.toString());
        assertFalse(jvm.has("profiling"), "profiling must be opt-in: " + jvm);
        assertTrue(jvm.path("heap").has("usedMB"), jvm.toString());
        assertTrue(jvm.path("heap").has("maxMB"), jvm.toString());
    }

    @Test
    void opsStatusTimeseriesIsOptIn() throws Exception {
        var defaultBody = Json.mapper().readTree(getWithToken("/ops/status").body());
        assertFalse(defaultBody.has("timeseries"), "timeseries must be opt-in");

        var withInclude = Json.mapper().readTree(getWithToken("/ops/status?include=timeseries").body());
        assertTrue(withInclude.has("timeseries"), withInclude.toString());
        assertTrue(withInclude.path("timeseries").has("minutes"), withInclude.toString());
    }

    @Test
    void opsStatusWithoutProfilerOmitsCpuGcProfilingStubs() throws Exception {
        // Direct handler construction is the only no-profiler path — Brace always attaches a
        // JfrProfiler when ops is enabled. Pre-H6 this branch emitted all-zeros cpu/gc/profiling
        // stubs; now the keys are simply absent and `brace check` tolerates that (.path defaults).
        String secret = OpsToken.generateSecret();
        var handler = new OpsHandler(new Stats(), null, null, new Router(), java.util.Map.of(), secret);
        String token = OpsToken.create(secret, 60, OpsScope.READ, "test");
        var req = new Request("GET", "/ops/status", java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of("Authorization", "Bearer " + token), null);
        var result = handler.status(req);
        var root = Json.mapper().readTree(result.body());
        var jvm = root.path("jvm");
        assertTrue(jvm.has("heap"), jvm.toString());
        assertTrue(jvm.has("threads"), jvm.toString());
        assertFalse(jvm.has("cpu"), "no profiler -> no cpu stub: " + jvm);
        assertFalse(jvm.has("gc"), "no profiler -> no gc stub: " + jvm);
        assertFalse(jvm.has("profiling"), "no profiler -> no profiling stub: " + jvm);
        assertEquals(0, root.path("errors").path("count").asLong(-1), root.path("errors").toString());
    }

    // --- Auth endpoint tests ---

    @Test
    void authEndpointReturnsToken() throws Exception {
        String token = authenticate();
        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(token.contains("."));
    }

    @Test
    void authRejectsUnknownPublicKey() throws Exception {
        var unknownKp = OpsKeys.generateKeypair();
        var response = postAuth(port, v2AuthBody(unknownKp));
        assertEquals(401, response.statusCode());
    }

    @Test
    void authRejectsStaleTimestamp() throws Exception {
        String timestamp = java.time.Instant.now().minusSeconds(60).toString(); // 1 minute ago, outside ±30s window
        String nonce = freshNonce();
        String signature = OpsKeys.sign(OpsKeys.v2AuthMessage(keypair.publicKey(), timestamp, nonce), keypair.privateKey());
        String body = "{\"v\":\"2\",\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp
            + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + signature + "\"}";
        var response = postAuth(port, body);
        assertEquals(401, response.statusCode());
    }

    @Test
    void authRejectsInvalidSignature() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        String body = "{\"v\":\"2\",\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp
            + "\",\"nonce\":\"" + freshNonce() + "\",\"signature\":\"badsignature\"}";
        var response = postAuth(port, body);
        assertEquals(401, response.statusCode());
    }

    @Test
    void authV2ReplayRejected() throws Exception {
        // The exact same signed body succeeds once, then is rejected — the nonce is single-use
        // on this instance (M3 replay suppression).
        String body = v2AuthBody(keypair);
        var first = postAuth(port, body);
        assertEquals(200, first.statusCode(), "first use should succeed: " + first.body());
        var replay = postAuth(port, body);
        assertEquals(401, replay.statusCode(), "replayed nonce must be rejected");
        assertTrue(replay.body().contains("Nonce already used"), replay.body());
    }

    @Test
    void authV2SignatureMustBindPublicKey() throws Exception {
        // Sign a v2 message embedding a DIFFERENT public key, then present it with the
        // authorized key — the signature no longer verifies because the key is part of
        // the signed message (M3 cross-key binding).
        var other = OpsKeys.generateKeypair();
        String timestamp = java.time.Instant.now().toString();
        String nonce = freshNonce();
        String signature = OpsKeys.sign(OpsKeys.v2AuthMessage(other.publicKey(), timestamp, nonce), keypair.privateKey());
        String body = "{\"v\":\"2\",\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp
            + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + signature + "\"}";
        var response = postAuth(port, body);
        assertEquals(401, response.statusCode());
    }

    @Test
    void authV2RequiresNonce() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        // v2 envelope, v1-style signature over the timestamp alone, no nonce — must be rejected.
        String signature = OpsKeys.sign(timestamp, keypair.privateKey());
        String body = "{\"v\":\"2\",\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp
            + "\",\"signature\":\"" + signature + "\"}";
        var response = postAuth(port, body);
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("nonce"), response.body());
    }

    @Test
    void authV1IsRejected() throws Exception {
        // v1 (no "v" field, signature over the timestamp alone) is not bound to the key and
        // carries no nonce, so a captured request was replayable verbatim inside the +/-30s
        // window to mint a fresh token at the key's full scope ceiling. It shipped in 0.1.6,
        // was deprecated in 0.1.7, and is removed in 0.1.8 (M5).
        String timestamp = java.time.Instant.now().toString();
        String signature = OpsKeys.sign(timestamp, keypair.privateKey());
        String body = "{\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp
            + "\",\"signature\":\"" + signature + "\"}";
        var response = postAuth(port, body);
        assertEquals(401, response.statusCode(), "v1 auth must be rejected: " + response.body());
        assertFalse(response.body().contains("\"token\""));
        assertTrue(response.body().contains("v1"), "the 401 should name the cause: " + response.body());
    }

    @Test
    void authV1WithExplicitVersionFieldIsAlsoRejected() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        String signature = OpsKeys.sign(timestamp, keypair.privateKey());
        String body = "{\"v\":\"1\",\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp
            + "\",\"signature\":\"" + signature + "\"}";
        assertEquals(401, postAuth(port, body).statusCode());
    }

    @Test
    void authRejectsUnsupportedProtocolVersion() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        String nonce = freshNonce();
        String signature = OpsKeys.sign(OpsKeys.v2AuthMessage(keypair.publicKey(), timestamp, nonce), keypair.privateKey());
        String body = "{\"v\":\"3\",\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp
            + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + signature + "\"}";
        var response = postAuth(port, body);
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("Unsupported ops auth protocol version"), response.body());
    }

    @Test
    void oldStyleOpsKeyRejected() throws Exception {
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/status"))
                .header("X-Ops-Key", "some-secret")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    // --- Cache ops tests (separate app with cache registered) ---

    static Brace cacheApp;
    static int cachePort;
    static OpsKeys.Keypair cacheKeypair;

    @BeforeAll
    static void startCacheApp() throws Exception {
        cacheKeypair = OpsKeys.generateKeypair();
        Path cacheKeysFile = tmpDir.resolve("cache-authorized-keys");
        Files.writeString(cacheKeysFile, cacheKeypair.publicKey() + "\n");

        var cache = Brace.cache();
        cache.set("key1", "value1");
        cache.set("key2", "value2", "5m");

        var db = new DatabaseFactory(
            "jdbc:h2:mem:cacheopsdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
            List.of());
        cacheApp = Brace.app().port(0).ops(cacheKeysFile.toString()).cache(cache).database(db);
        cacheApp.get("/cacheboom", req -> { throw new RuntimeException("cache test error"); });
        cacheApp.start();
        cachePort = cacheApp.actualPort();

        // Trigger an error so error tracking section shows Resolve button
        client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + cachePort + "/cacheboom")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        cacheApp.errorStore().flush(); // persist the H9-buffered error deterministically
    }

    @AfterAll
    static void stopCacheApp() throws Exception {
        cacheApp.stop();
    }

    private HttpResponse<String> cacheGet(String path) throws Exception {
        String token = authenticate(cachePort, cacheKeypair);
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + path))
                .header("Authorization", "Bearer " + token)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> cachePost(String path) throws Exception {
        String token = authenticate(cachePort, cacheKeypair);
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + path))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void statusIncludesCacheStats() throws Exception {
        var response = cacheGet("/ops/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"cache\""));
        assertTrue(response.body().contains("\"entries\""));
    }

    @Test
    void clearCacheRequiresAuth() throws Exception {
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + "/ops/cache/clear"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void clearCacheWithValidToken() throws Exception {
        var response = cachePost("/ops/cache/clear");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Brace Ops"));

        // Verify cache is empty in status
        var status = cacheGet("/ops/status");
        assertTrue(status.body().contains("\"entries\":0"));
    }

    @Test
    void dashboardIncludesCacheSection() throws Exception {
        var response = cacheGet("/ops/dashboard");
        assertEquals(200, response.statusCode());
        // In-process (default) cache: clear button is instance-scoped; a shared backend would
        // render "[clear fleet]" and a "shared" mode label instead.
        assertTrue(response.body().contains("[clear]"));
        assertTrue(response.body().contains("in-process"));
    }

    @Test
    void dashboardIncludesErrorTracking() throws Exception {
        var response = cacheGet("/ops/dashboard");
        assertTrue(response.body().contains("Unresolved"));
        assertTrue(response.body().contains("resolve"));
    }

    @Test
    void dashboardIncludesHtmxScript() throws Exception {
        var token = authenticate();
        var response = getWithToken("/ops/dashboard", token);
        assertTrue(response.body().contains("/__brace/htmx.min.js"));
    }

    @Test
    void dashboardHasHtmxPolling() throws Exception {
        var token = authenticate();
        var response = getWithToken("/ops/dashboard", token);
        assertTrue(response.body().contains("hx-get="));
        assertTrue(response.body().contains("hx-trigger=\"every 5s\""));
    }

    @Test
    void dashboardUsesBearerTokenNotQueryParam() throws Exception {
        var token = authenticate();
        var response = getWithToken("/ops/dashboard", token);
        var body = response.body();
        assertTrue(body.contains("Authorization"));
        assertTrue(body.contains("Bearer"));
        assertFalse(body.contains("?key="));
    }

    @Test
    void dashboardIncludesJvmSection() throws Exception {
        var token = authenticate();
        var response = getWithToken("/ops/dashboard", token);
        var body = response.body();
        assertTrue(body.contains("Heap"));
        assertTrue(body.contains("CPU"));
        assertTrue(body.contains("Threads"));
        assertTrue(body.contains("GC Avg"));
    }

    @Test
    void statusIncludesCacheHitMissStats() throws Exception {
        var response = cacheGet("/ops/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"hits\""));
        assertTrue(response.body().contains("\"misses\""));
        assertTrue(response.body().contains("\"evictions\""));
    }

    // --- Custom metrics tests (separate app) ---

    static Brace metricsApp;
    static int metricsPort;
    static OpsKeys.Keypair metricsKeypair;

    @BeforeAll
    static void startMetricsApp() throws Exception {
        metricsKeypair = OpsKeys.generateKeypair();
        Path metricsKeysFile = tmpDir.resolve("metrics-authorized-keys");
        Files.writeString(metricsKeysFile, metricsKeypair.publicKey() + "\n");

        metricsApp = Brace.app().port(0).ops(metricsKeysFile.toString());
        metricsApp.get("/ping", req -> Result.text("pong"));
        metricsApp.start();
        metricsPort = metricsApp.actualPort();

        // Register custom metrics
        var stats = metricsApp.stats();
        stats.counter("talks.created");
        stats.counter("talks.created");
        stats.counter("bytes.uploaded", 4096);
        stats.gauge("queue.depth", () -> 42L);
        stats.timer("api.latency", 150);
        stats.timer("api.latency", 250);
        stats.snapshot(); // capture metrics into ring buffer
    }

    @AfterAll
    static void stopMetricsApp() throws Exception {
        metricsApp.stop();
    }

    private HttpResponse<String> metricsGet(String path) throws Exception {
        var token = authenticate(metricsPort, metricsKeypair);
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + metricsPort + path))
                .header("Authorization", "Bearer " + token)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void statusIncludesCustomMetrics() throws Exception {
        var response = metricsGet("/ops/status");
        assertEquals(200, response.statusCode());
        var body = response.body();
        assertTrue(body.contains("\"metrics\""), "should have metrics section");
        assertTrue(body.contains("\"counters\""), "should have counters");
        assertTrue(body.contains("\"gauges\""), "should have gauges");
        assertTrue(body.contains("\"timers\""), "should have timers");
        assertTrue(body.contains("talks.created"), "should include counter name");
        assertTrue(body.contains("queue.depth"), "should include gauge name");
        assertTrue(body.contains("api.latency"), "should include timer name");
    }

    @Test
    void dashboardIncludesCustomMetricSparklines() throws Exception {
        var response = metricsGet("/ops/dashboard");
        assertEquals(200, response.statusCode());
        var body = response.body();
        assertTrue(body.contains("talks.created"), "dashboard should show counter name");
        assertTrue(body.contains("queue.depth"), "dashboard should show gauge name");
        assertTrue(body.contains("api.latency"), "dashboard should show timer name");
    }

    // --- JFR profiler integration tests (separate app) ---

    static Brace jfrApp;
    static int jfrPort;
    static OpsKeys.Keypair jfrKeypair;

    @BeforeAll
    static void startJfrApp() throws Exception {
        jfrKeypair = OpsKeys.generateKeypair();
        Path jfrKeysFile = tmpDir.resolve("jfr-authorized-keys");
        Files.writeString(jfrKeysFile, jfrKeypair.publicKey() + "\n");

        jfrApp = Brace.app().port(0).ops(jfrKeysFile.toString());
        jfrApp.get("/work", req -> {
            long sum = 0;
            for (int i = 0; i < 10000; i++) sum += i;
            return Result.text("done:" + sum);
        });
        jfrApp.start();
        jfrPort = jfrApp.actualPort();
        Thread.sleep(1500);
    }

    @AfterAll
    static void stopJfrApp() throws Exception {
        jfrApp.stop();
    }

    @Test
    void jfrStatusHasFullJvmSection() throws Exception {
        for (int i = 0; i < 5; i++) {
            client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + jfrPort + "/work")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        }
        String token = authenticate(jfrPort, jfrKeypair);
        // Profiling (hot methods + allocations) is opt-in via ?include=profiling (H6).
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + jfrPort + "/ops/status?include=profiling"))
                .header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var body = response.body();
        assertTrue(body.contains("\"jvm\""));
        assertTrue(body.contains("\"heap\""));
        assertTrue(body.contains("\"cpu\""));
        assertTrue(body.contains("\"threads\""));
        assertTrue(body.contains("\"gc\""));
        assertTrue(body.contains("\"profiling\""));
        assertTrue(body.contains("\"hotMethods\""));
        assertTrue(body.contains("\"topAllocations\""));

        var withoutInclude = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + jfrPort + "/ops/status"))
                .header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        var jvm = Json.mapper().readTree(withoutInclude.body()).path("jvm");
        assertFalse(jvm.has("profiling"), "profiling must be opt-in: " + jvm);
    }

    @Test
    void jvmFlushJobsRegistered() throws Exception {
        String token = authenticate(cachePort, cacheKeypair);
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + "/ops/status"))
                .header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        var body = response.body();
        assertTrue(body.contains("ops-flush-jvm"), "should have ops-flush-jvm job");
        assertTrue(body.contains("ops-flush-jvm-profiling"), "should have ops-flush-jvm-profiling job");
        assertTrue(body.contains("ops-metrics-prune"), "should have ops-metrics-prune retention job");
    }

    @Test
    void jfrDashboardHasJvmSection() throws Exception {
        String token = authenticate(jfrPort, jfrKeypair);
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + jfrPort + "/ops/dashboard"))
                .header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hot Methods"));
        assertTrue(response.body().contains("Top Allocations"));
    }

    @Test
    void browserLoginTokenFlow() throws Exception {
        // Step 1: Authenticate and request a login token
        String token = authenticate();
        var loginTokenResponse = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/login-token"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginTokenResponse.statusCode());

        // Extract loginToken from JSON
        String body = loginTokenResponse.body();
        int start = body.indexOf("\"loginToken\":\"") + 14;
        int end = body.indexOf("\"", start);
        String loginToken = body.substring(start, end);
        assertNotNull(loginToken);
        assertFalse(loginToken.isEmpty());

        // Step 2: Exchange login token for session cookie
        var exchangeResponse = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/exchange?token=" + loginToken))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

        // Should redirect to dashboard
        assertEquals(302, exchangeResponse.statusCode());
        assertEquals("/ops/dashboard", exchangeResponse.headers().firstValue("Location").orElse(""));

        // Should set httpOnly cookie with 8h TTL (M4: shortened from 24h)
        String setCookie = exchangeResponse.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.contains("__brace_ops_session="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Max-Age=28800"));

        // Extract cookie value for Step 3
        int cookieStart = setCookie.indexOf("=") + 1;
        int cookieEnd = setCookie.indexOf(";");
        String sessionCookie = setCookie.substring(cookieStart, cookieEnd);

        // Step 3: Access dashboard with cookie (no token in URL)
        var dashboardResponse = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/dashboard"))
                .header("Cookie", "__brace_ops_session=" + sessionCookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, dashboardResponse.statusCode());
        assertTrue(dashboardResponse.body().contains("Brace Ops"));

        // Step 4: The login token is now a stateless short-lived HMAC token (no server-side store,
        // so the flow works behind a load balancer — B5). It is reusable within its short TTL...
        var exchangeAgain = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/exchange?token=" + loginToken))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(302, exchangeAgain.statusCode());

        // ...but a tampered/garbage token is rejected.
        var exchangeBad = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/exchange?token=not-a-real-token"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, exchangeBad.statusCode());
    }

    // --- M4: ?token= query-param auth removed from general endpoints ---

    @Test
    void queryParamTokenRejectedOnGeneralEndpoints() throws Exception {
        // A valid token in ?token= must be rejected (401) on general endpoints (M4).
        // Tokens belong in the Authorization: Bearer header, not the URL.
        String token = authenticate();
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/status?token=" + token))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode(),
            "?token= query param must not authenticate general ops endpoints (M4)");
    }

    @Test
    void sameBearerTokenAuthenticatesViaHeader() throws Exception {
        // Sanity: the same token accepted as Bearer header proves the token itself is valid —
        // the 401 above is from the removed fallback, not an invalid token.
        String token = authenticate();
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/status"))
                .header("Authorization", "Bearer " + token)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(),
            "the same token must succeed via Authorization: Bearer header");
    }

    @Test
    void exchangeResponseHasSecurityHeaders() throws Exception {
        // The exchange redirect carries Referrer-Policy and Cache-Control to prevent the
        // token-bearing URL leaking to outbound links or caches (M4).
        String token = authenticate();
        var loginTokenResponse = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/login-token"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginTokenResponse.statusCode());

        String body = loginTokenResponse.body();
        int start = body.indexOf("\"loginToken\":\"") + 14;
        int end = body.indexOf("\"", start);
        String loginToken = body.substring(start, end);

        var exchangeResponse = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/exchange?token=" + loginToken))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(302, exchangeResponse.statusCode());
        assertEquals("no-referrer",
            exchangeResponse.headers().firstValue("Referrer-Policy").orElse(""),
            "exchange response must set Referrer-Policy: no-referrer to prevent token URL leaking via Referer");
        assertEquals("no-store",
            exchangeResponse.headers().firstValue("Cache-Control").orElse(""),
            "exchange response must set Cache-Control: no-store to prevent token URL being cached");
    }

    @Test
    void opsLogsReturnsRecentEntries() throws Exception {
        LogTap.clear();
        Log.info("hello from test");
        Log.warn("warning from test");

        String token = authenticate();
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/logs"))
                .header("Authorization", "Bearer " + token)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("hello from test"), body);
        assertTrue(body.contains("warning from test"), body);
    }

    @Test
    void opsLogsSinceFiltersById() throws Exception {
        LogTap.clear();
        Log.info("first");
        long firstId = LogTap.snapshot().stream()
            .filter(e -> "first".equals(e.fields().get("message")))
            .findFirst()
            .orElseThrow()
            .id();
        Log.info("second");

        String token = authenticate();
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/logs?since=" + firstId))
                .header("Authorization", "Bearer " + token)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("second"));
        assertFalse(response.body().contains("\"first\""));
    }

    @Test
    void opsLogsLevelFilter() throws Exception {
        LogTap.clear();
        Log.info("info-line");
        Log.warn("warn-line");
        Log.error("error-line");

        String token = authenticate();
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/logs?level=warn"))
                .header("Authorization", "Bearer " + token)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("info-line"));
        assertTrue(response.body().contains("warn-line"));
        assertTrue(response.body().contains("error-line"));
    }

    @Test
    void opsLogsRequiresAuth() throws Exception {
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/logs"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void opsErrorsAcceptsSinceQueryParam() throws Exception {
        String token = authenticate();
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/errors?since=2099-01-01T00:00:00Z"))
                .header("Authorization", "Bearer " + token)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body().trim());
    }

    @Test
    void noDbErrorsListDetailAndResolve() throws Exception {
        // This app has no database: /ops/errors must serve the in-memory Stats records
        // (a remote path to the stack trace), and resolve must remove them so
        // errors.count — and the brace status exit code — can recover.
        get("/error");   // recorded synchronously into Stats
        String token = authenticate();

        var list = Json.mapper().readTree(getWithToken("/ops/errors", token).body());
        assertTrue(list.size() > 0, list.toString());
        var summary = list.get(0);
        assertTrue(summary.has("id"), summary.toString());
        assertEquals("RuntimeException", summary.path("errorType").asText());
        assertTrue(summary.has("at"), "summary should carry the first app frame: " + summary);
        assertFalse(summary.has("stackTrace"), "summary must not carry stackTrace: " + summary);

        long id = summary.path("id").asLong();
        var detail = Json.mapper().readTree(getWithToken("/ops/errors/" + id, token).body());
        assertTrue(detail.path("stackTrace").asText().contains("test error"), detail.toString());
        assertTrue(detail.has("requestDetail"), detail.toString());

        var full = Json.mapper().readTree(getWithToken("/ops/errors?include=detail", token).body());
        assertTrue(full.get(0).has("stackTrace"), full.toString());

        var resolveResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/errors/" + id + "/resolve"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resolveResp.statusCode());
        assertTrue(resolveResp.body().contains("resolvedAt"), resolveResp.body());

        var after = Json.mapper().readTree(getWithToken("/ops/errors", token).body());
        for (var n : after) {
            assertNotEquals(id, n.path("id").asLong(), "resolved error must disappear: " + after);
        }
        assertEquals(404, getWithToken("/ops/errors/" + id, token).statusCode());
    }

    @Test
    void resolveWithMalformedIdIs404Not500() throws Exception {
        // A 500 here would record a fresh framework error — re-reddening the count the
        // resolve endpoint exists to clear.
        String token = authenticate();
        var resp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/errors/abc/resolve"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    @Test
    void opsCacheReturnsStats() throws Exception {
        var response = cacheGet("/ops/cache");

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"enabled\":true"), body);
        assertTrue(body.contains("\"shared\":false"), body); // in-process default
        assertTrue(body.contains("\"size\""), body);
        assertTrue(body.contains("\"hits\""), body);
        assertTrue(body.contains("\"misses\""), body);
        assertTrue(body.contains("\"hitRate\""), body);
        assertTrue(body.contains("\"evictions\""), body);
    }

    @Test
    void clearCacheReturnsJsonWhenAcceptIsJson() throws Exception {
        String token = authenticate(cachePort, cacheKeypair);
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + "/ops/cache/clear"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        assertTrue(response.body().contains("cleared"));
    }

    @Test
    void resolveErrorReturnsJsonWhenAcceptIsJson() throws Exception {
        String token = authenticate(cachePort, cacheKeypair);
        var errorsResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + "/ops/errors"))
                .header("Authorization", "Bearer " + token)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertTrue(errorsResp.body().contains("errorType"), errorsResp.body());
        int idStart = errorsResp.body().indexOf("\"id\":") + 5;
        int idEnd = errorsResp.body().indexOf(",", idStart);
        String errorId = errorsResp.body().substring(idStart, idEnd).trim();

        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + "/ops/errors/" + errorId + "/resolve"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        assertTrue(response.body().contains("resolvedAt"), response.body());
    }

    // --- /ops/errors summary + detail (H5) ---

    /**
     * Trigger /cacheboom and wait for the (async) error record to land, then return the
     * unresolved errors list. Resilient to test order: other tests may have resolved the
     * seeded error, so this always provokes a fresh occurrence.
     */
    private com.fasterxml.jackson.databind.JsonNode awaitUnresolvedErrors() throws Exception {
        client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + cachePort + "/cacheboom")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        for (int i = 0; i < 50; i++) {
            var response = cacheGet("/ops/errors");
            assertEquals(200, response.statusCode());
            var root = Json.mapper().readTree(response.body());
            if (root.size() > 0) return root;
            Thread.sleep(100);
        }
        throw new AssertionError("error never appeared on /ops/errors");
    }

    @Test
    void opsErrorsReturnsSummaryShapeWithoutStackTrace() throws Exception {
        var root = awaitUnresolvedErrors();
        var e = root.get(0);
        assertTrue(e.has("id"), e.toString());
        assertTrue(e.has("errorType"), e.toString());
        assertTrue(e.has("message"), e.toString());
        assertTrue(e.has("route"), e.toString());
        assertTrue(e.has("occurrenceCount"), e.toString());
        assertTrue(e.has("firstSeen"), e.toString());
        assertTrue(e.has("lastSeen"), e.toString());
        assertTrue(e.has("at"), "summary should carry the first app frame: " + e);
        assertFalse(e.has("stackTrace"), "summary must not carry stackTrace: " + e);
        assertFalse(e.has("requestDetail"), e.toString());
        assertFalse(e.has("queriesBefore"), e.toString());
        assertFalse(e.has("requestHeaders"), e.toString());
    }

    @Test
    void opsErrorsFullParamReturnsLegacyDetailShape() throws Exception {
        awaitUnresolvedErrors();
        var response = cacheGet("/ops/errors?include=detail");
        assertEquals(200, response.statusCode());
        var root = Json.mapper().readTree(response.body());
        assertTrue(root.size() > 0, response.body());
        var e = root.get(0);
        assertTrue(e.has("stackTrace"), e.toString());
        assertTrue(e.has("requestDetail"), e.toString());
        assertTrue(e.has("queriesBefore"), e.toString());
        assertTrue(e.has("requestHeaders"), e.toString());
    }

    @Test
    void opsErrorDetailByIdReturnsFullRecord() throws Exception {
        var root = awaitUnresolvedErrors();
        long id = root.get(0).path("id").asLong();

        var response = cacheGet("/ops/errors/" + id);
        assertEquals(200, response.statusCode());
        var e = Json.mapper().readTree(response.body());
        assertEquals(id, e.path("id").asLong());
        assertTrue(e.has("stackTrace"), e.toString());
        assertTrue(e.has("requestDetail"), e.toString());
        assertTrue(e.has("queriesBefore"), e.toString());
        assertTrue(e.has("requestHeaders"), e.toString());
        assertTrue(e.path("stackTrace").asText().contains("cache test error"), e.toString());
    }

    @Test
    void opsStatusErrorsCountIsDbBackedAndSummaryShaped() throws Exception {
        // This app has a database, so errors.count comes from the ErrorStore's unresolved
        // count and errors.recent from its top-5 summary query (H6).
        awaitUnresolvedErrors();
        var root = Json.mapper().readTree(cacheGet("/ops/status").body());
        var errors = root.path("errors");
        assertTrue(errors.path("count").asLong(0) >= 1, errors.toString());
        var first = errors.path("recent").get(0);
        assertNotNull(first, errors.toString());
        assertTrue(first.has("id"), "DB-backed rows carry the id for /ops/errors/{id}: " + first);
        assertTrue(first.has("errorType"), first.toString());
        assertTrue(first.has("message"), first.toString());
        assertTrue(first.has("route"), first.toString());
        assertTrue(first.has("occurrenceCount"), first.toString());
        assertTrue(first.has("firstSeen"),
            "DB-backed rows carry firstSeen like the in-memory shape: " + first);
        assertTrue(first.has("lastSeen"), first.toString());
        assertFalse(first.has("stackTrace"), "status summaries must not carry stackTrace: " + first);
    }

    @Test
    void opsErrorDetailUnknownIdIs404() throws Exception {
        var response = cacheGet("/ops/errors/999999999");
        assertEquals(404, response.statusCode());
    }

    @Test
    void opsErrorDetailRequiresAuth() throws Exception {
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + "/ops/errors/1"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void dashboardEscapesSingleQuotesInToken() throws Exception {
        // Regression test for L2: OpsDashboard.esc() must escape single quotes
        // to safely embed the token in single-quoted hx-headers attributes.
        var token = authenticate();
        var response = getWithToken("/ops/dashboard", token);
        assertEquals(200, response.statusCode());

        String body = response.body();
        // The token is embedded in hx-headers='{...}' attributes.
        // Look for the hx-headers attribute that carries the Bearer token.
        assertTrue(body.contains("hx-headers='{\"Authorization\": \"Bearer "),
                   "Dashboard should have hx-headers with Authorization Bearer");

        // Verify the hx-headers attribute has proper closing quote.
        // This ensures single quotes (if any were in the token) were escaped
        // and didn't break the attribute.
        assertTrue(body.contains("}'"), "hx-headers attribute should have closing single quote");
    }
}
