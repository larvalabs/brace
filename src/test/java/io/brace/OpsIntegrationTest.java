package io.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OpsIntegrationTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0).ops("test-ops-key");

        app.get("/hello", req -> Result.text("Hello!"));
        app.get("/error", req -> { throw new RuntimeException("test error"); });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithKey(String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Ops-Key", "test-ops-key")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void opsStatusRequiresKey() throws Exception {
        var response = get("/ops/status");
        assertEquals(401, response.statusCode());
    }

    @Test
    void opsStatusWithValidKey() throws Exception {
        // Make a few requests first to generate stats
        get("/hello");
        get("/hello");

        var response = getWithKey("/ops/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"app\""));
        assertTrue(response.body().contains("\"http\""));
        assertTrue(response.body().contains("\"jvm\""));
        assertTrue(response.body().contains("\"javaVersion\""));
    }

    @Test
    void opsRoutesWithValidKey() throws Exception {
        var response = getWithKey("/ops/routes");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("/hello"));
        assertTrue(response.body().contains("GET"));
    }

    @Test
    void statsRecordRequestsAfterTraffic() throws Exception {
        get("/hello");
        var response = getWithKey("/ops/status");
        assertTrue(response.body().contains("\"statusCodes\""));
    }

    @Test
    void opsDashboardRequiresKey() throws Exception {
        var response = get("/ops/dashboard");
        assertEquals(401, response.statusCode());
    }

    @Test
    void opsDashboardWithValidKey() throws Exception {
        var response = get("/ops/dashboard?key=test-ops-key");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(response.body().contains("Brace Ops"));
    }

    @Test
    void errorTracking() throws Exception {
        // Trigger an error
        get("/error");
        var response = getWithKey("/ops/status");
        assertTrue(response.body().contains("\"errors\""));
    }

    @Test
    void opsStatusJvmSectionHasExpectedFields() throws Exception {
        var response = getWithKey("/ops/status");
        var body = response.body();
        assertTrue(body.contains("\"heap\""));
        assertTrue(body.contains("\"cpu\""));
        assertTrue(body.contains("\"threads\""));
        assertTrue(body.contains("\"gc\""));
        assertTrue(body.contains("\"profiling\""));
        assertTrue(body.contains("\"usedMB\""));
        assertTrue(body.contains("\"maxMB\""));
    }

    // --- Cache ops tests (separate app with cache registered) ---

    static Brace cacheApp;
    static int cachePort;

    @BeforeAll
    static void startCacheApp() throws Exception {
        var cache = Brace.cache();
        cache.set("key1", "value1");
        cache.set("key2", "value2", "5m");

        var db = new DatabaseFactory(
            "jdbc:h2:mem:cacheopsdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
            List.of());
        cacheApp = Brace.app().port(0).ops("cache-key").cache(cache).database(db);
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
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + path))
                .header("X-Ops-Key", "cache-key")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> cachePost(String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + path))
                .header("X-Ops-Key", "cache-key")
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
    void clearCacheRequiresKey() throws Exception {
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + "/ops/cache/clear"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void clearCacheWithValidKey() throws Exception {
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
        var response = get("/ops/dashboard?key=test-ops-key");
        assertTrue(response.body().contains("/__brace/htmx.min.js"));
    }

    @Test
    void dashboardHasHtmxPolling() throws Exception {
        var response = get("/ops/dashboard?key=test-ops-key");
        assertTrue(response.body().contains("hx-get="));
        assertTrue(response.body().contains("hx-trigger=\"every 5s\""));
    }

    @Test
    void dashboardIncludesJvmSection() throws Exception {
        var response = get("/ops/dashboard?key=test-ops-key");
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

    @BeforeAll
    static void startJfrApp() throws Exception {
        jfrApp = Brace.app().port(0).ops("jfr-key");
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
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + jfrPort + "/ops/status"))
                .header("X-Ops-Key", "jfr-key").GET().build(),
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
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + cachePort + "/ops/status"))
                .header("X-Ops-Key", "cache-key").GET().build(),
            HttpResponse.BodyHandlers.ofString());
        var body = response.body();
        assertTrue(body.contains("ops-flush-jvm"), "should have ops-flush-jvm job");
        assertTrue(body.contains("ops-flush-jvm-profiling"), "should have ops-flush-jvm-profiling job");
    }

    @Test
    void jfrDashboardHasJvmSection() throws Exception {
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + jfrPort + "/ops/dashboard?key=jfr-key")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hot Methods"));
        assertTrue(response.body().contains("Top Allocations"));
    }
}
