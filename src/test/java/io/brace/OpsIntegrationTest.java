package io.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
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

        var response = get("/ops/status?key=test-ops-key");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"app\""));
        assertTrue(response.body().contains("\"http\""));
        assertTrue(response.body().contains("\"memory\""));
        assertTrue(response.body().contains("\"javaVersion\""));
    }

    @Test
    void opsRoutesWithValidKey() throws Exception {
        var response = get("/ops/routes?key=test-ops-key");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("/hello"));
        assertTrue(response.body().contains("GET"));
    }

    @Test
    void statsRecordRequestsAfterTraffic() throws Exception {
        get("/hello");
        var response = get("/ops/status?key=test-ops-key");
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
        assertTrue(response.body().contains("Brace Dashboard"));
    }

    @Test
    void errorTracking() throws Exception {
        // Trigger an error
        get("/error");
        var response = get("/ops/status?key=test-ops-key");
        assertTrue(response.body().contains("\"errors\""));
    }
}
