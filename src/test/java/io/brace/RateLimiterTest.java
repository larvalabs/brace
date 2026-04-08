package io.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private Request fakeRequest(String ip) {
        return new Request("GET", "/test", Map.of(), Map.of(),
            Map.of("Remote-Addr", ip), null);
    }

    private Request fakeRequestWithHeader(String headerName, String headerValue) {
        return new Request("GET", "/test", Map.of(), Map.of(),
            Map.of("Remote-Addr", "1.2.3.4", headerName, headerValue), null);
    }

    @Test
    void requestsUnderLimitAreAllowed() {
        var limiter = RateLimiter.perIp(5, "1m");
        var req = fakeRequest("10.0.0.1");
        for (int i = 0; i < 5; i++) {
            assertNull(limiter.handle(req), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void requestsOverLimitReturn429() {
        var limiter = RateLimiter.perIp(3, "1m");
        var req = fakeRequest("10.0.0.2");
        for (int i = 0; i < 3; i++) {
            assertNull(limiter.handle(req));
        }
        var result = limiter.handle(req);
        assertNotNull(result);
        assertEquals(429, result.status());
        assertEquals("Too Many Requests", result.body());
    }

    @Test
    void retryAfterHeaderIsPresent() {
        var limiter = RateLimiter.perIp(1, "1m");
        var req = fakeRequest("10.0.0.3");
        assertNull(limiter.handle(req));
        var result = limiter.handle(req);
        assertNotNull(result);
        assertEquals(429, result.status());
        var retryAfter = result.header("Retry-After");
        assertNotNull(retryAfter, "Retry-After header should be present");
        assertTrue(Integer.parseInt(retryAfter) > 0, "Retry-After should be positive");
    }

    @Test
    void differentIpsHaveSeparateLimits() {
        var limiter = RateLimiter.perIp(2, "1m");
        var req1 = fakeRequest("10.0.0.10");
        var req2 = fakeRequest("10.0.0.11");

        // Both IPs can make 2 requests
        assertNull(limiter.handle(req1));
        assertNull(limiter.handle(req1));
        assertNull(limiter.handle(req2));
        assertNull(limiter.handle(req2));

        // Both IPs are now at limit
        assertNotNull(limiter.handle(req1));
        assertNotNull(limiter.handle(req2));
    }

    @Test
    void perKeyWithCustomKeyFunction() {
        var limiter = RateLimiter.perKey(req -> req.header("Authorization"), 2, "1m");

        var reqA = fakeRequestWithHeader("Authorization", "token-A");
        var reqB = fakeRequestWithHeader("Authorization", "token-B");

        assertNull(limiter.handle(reqA));
        assertNull(limiter.handle(reqA));
        // token-A is at limit
        assertNotNull(limiter.handle(reqA));
        // token-B still has quota
        assertNull(limiter.handle(reqB));
    }

    @Test
    void nullKeyIsAllowed() {
        var limiter = RateLimiter.perKey(req -> req.header("X-Missing"), 1, "1m");
        var req = fakeRequest("10.0.0.4");
        // Key is null, so request should pass through
        assertNull(limiter.handle(req));
        assertNull(limiter.handle(req));
    }

    // Integration test with a real Brace app

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0);
        app.before("/limited", RateLimiter.perIp(3, "1m"));
        app.get("/limited", req -> Result.text("ok"));
        app.get("/unlimited", req -> Result.text("ok"));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void integrationRateLimitReturns429() throws Exception {
        // First 3 requests should succeed
        for (int i = 0; i < 3; i++) {
            var response = get("/limited");
            assertEquals(200, response.statusCode(), "Request " + (i + 1) + " should succeed");
        }
        // 4th request should be rate limited
        var response = get("/limited");
        assertEquals(429, response.statusCode());
        assertTrue(response.headers().firstValue("Retry-After").isPresent());
    }

    @Test
    void integrationUnlimitedEndpointNotAffected() throws Exception {
        for (int i = 0; i < 10; i++) {
            var response = get("/unlimited");
            assertEquals(200, response.statusCode());
        }
    }
}
