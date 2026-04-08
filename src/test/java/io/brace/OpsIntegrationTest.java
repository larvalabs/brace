package io.brace;

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

    /** Authenticate via POST /ops/auth and return a Bearer token. */
    private static String authenticate() throws Exception {
        return authenticate(port, keypair);
    }

    private static String authenticate(int targetPort, OpsKeys.Keypair kp) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = OpsKeys.sign(timestamp, kp.privateKey());
        String body = "{\"publicKey\":\"" + kp.publicKey() + "\",\"timestamp\":\"" + timestamp + "\",\"signature\":\"" + signature + "\"}";
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + targetPort + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Auth should succeed: " + response.body());
        // Extract token from {"token":"..."}
        String respBody = response.body();
        int start = respBody.indexOf("\"token\":\"") + 9;
        int end = respBody.indexOf("\"", start);
        return respBody.substring(start, end);
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
        // Trigger an error
        get("/error");
        var response = getWithToken("/ops/status");
        assertTrue(response.body().contains("\"errors\""));
    }

    @Test
    void opsStatusJvmSectionHasExpectedFields() throws Exception {
        var response = getWithToken("/ops/status");
        var body = response.body();
        assertTrue(body.contains("\"heap\""));
        assertTrue(body.contains("\"cpu\""));
        assertTrue(body.contains("\"threads\""));
        assertTrue(body.contains("\"gc\""));
        assertTrue(body.contains("\"profiling\""));
        assertTrue(body.contains("\"usedMB\""));
        assertTrue(body.contains("\"maxMB\""));
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
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = OpsKeys.sign(timestamp, unknownKp.privateKey());
        String body = "{\"publicKey\":\"" + unknownKp.publicKey() + "\",\"timestamp\":\"" + timestamp + "\",\"signature\":\"" + signature + "\"}";
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void authRejectsStaleTimestamp() throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000 - 120); // 2 minutes ago
        String signature = OpsKeys.sign(timestamp, keypair.privateKey());
        String body = "{\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp + "\",\"signature\":\"" + signature + "\"}";
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void authRejectsInvalidSignature() throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String body = "{\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp + "\",\"signature\":\"badsignature\"}";
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
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
        Thread.sleep(200);
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
        assertTrue(response.body().contains("[clear all]"));
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
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + jfrPort + "/ops/status"))
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
}
