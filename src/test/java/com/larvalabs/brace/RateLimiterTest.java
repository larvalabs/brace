package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private Request fakeRequest(String ip) {
        return new Request("GET", "/test", Map.of(), Map.of(),
            Map.of(), null, Map.of(), ip, null);
    }

    private Request fakeRequestWithHeader(String headerName, String headerValue) {
        return new Request("GET", "/test", Map.of(), Map.of(),
            Map.of(headerName, headerValue), null, Map.of(), "1.2.3.4", null);
    }

    @AfterEach
    void resetSharedBackend() {
        RateLimiter.disableSharedBackend();
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

    /**
     * Null key → request is not rate-limited (intentional exemption).
     *
     * <p>A null key means "no identity established yet" (e.g., a GET to a login page before the
     * user has entered an email). Bucketing all such requests together would cause site-wide
     * lockout — the documented login example {@code RateLimiter.perKey(req -> req.param("email"), …)}
     * would drain the "(none)" bucket on every unauthenticated GET and lock out the page.
     */
    @Test
    void nullKeyIsAllowed() {
        var limiter = RateLimiter.perKey(req -> req.header("X-Missing"), 2, "1m");
        var req = fakeRequest("10.0.0.4");
        // All requests pass — null key is exempted, no bucket is consumed.
        for (int i = 0; i < 10; i++) {
            assertNull(limiter.handle(req), "null-key request should be exempt from rate limiting");
        }
    }

    /**
     * Blank key (all whitespace) → request is not rate-limited (same exemption as null).
     */
    @Test
    void blankKeyIsAllowed() {
        var limiter = RateLimiter.perKey(req -> "   ", 1, "1m");
        var req = fakeRequest("10.0.0.5");
        // All requests pass — blank key is treated like null.
        for (int i = 0; i < 5; i++) {
            assertNull(limiter.handle(req), "blank-key request should be exempt from rate limiting");
        }
    }

    // ---- Key normalization (M7a) ----

    @Test
    void normalizeKey_nullReturnsNull() {
        // null/blank keys are exempted by check() before normalizeKey() is called;
        // normalizeKey handles them defensively by returning null.
        assertNull(RateLimiter.normalizeKey(null));
    }

    @Test
    void normalizeKey_blankReturnsNull() {
        assertNull(RateLimiter.normalizeKey("   "));
    }

    @Test
    void normalizeKey_shortKeyPassesThrough() {
        var key = "user:42";
        assertEquals(key, RateLimiter.normalizeKey(key));
    }

    @Test
    void normalizeKey_exactlyMaxLengthPassesThrough() {
        var key = "x".repeat(RateLimiter.MAX_KEY_LENGTH);
        assertEquals(key, RateLimiter.normalizeKey(key));
    }

    @Test
    void normalizeKey_longKeyIsHashed() {
        // A key longer than MAX_KEY_LENGTH must produce a fixed-length (64 char) hex digest.
        var longKey = "a".repeat(RateLimiter.MAX_KEY_LENGTH + 1);
        var normalized = RateLimiter.normalizeKey(longKey);
        assertNotEquals(longKey, normalized);
        assertEquals(64, normalized.length(), "SHA-256 hex digest is always 64 chars");
        assertTrue(normalized.matches("[0-9a-f]{64}"), "must be hex");
    }

    @Test
    void normalizeKey_sameLongKeyProducesSameHash() {
        // Rate limiting must be idempotent: same long key → same bucket across requests.
        var longKey = "Bearer " + "x".repeat(200);
        assertEquals(RateLimiter.normalizeKey(longKey), RateLimiter.normalizeKey(longKey));
    }

    @Test
    void normalizeKey_differentLongKeysDontCollide() {
        // Two distinct long keys must not map to the same bucket.
        var keyA = "a".repeat(100);
        var keyB = "b".repeat(100);
        assertNotEquals(RateLimiter.normalizeKey(keyA), RateLimiter.normalizeKey(keyB));
    }

    /**
     * End-to-end: a long key (>64 chars) is still rate-limited — the hash produces a consistent
     * bucket so the limit is enforced correctly across multiple requests with the same long key.
     */
    @Test
    void longKeyIsRateLimited() {
        var longToken = "Bearer " + "x".repeat(200); // well over 64 chars
        var limiter = RateLimiter.perKey(req -> req.header("Authorization"), 2, "1m");
        var req = fakeRequestWithHeader("Authorization", longToken);

        assertNull(limiter.handle(req));
        assertNull(limiter.handle(req));
        var result = limiter.handle(req);
        assertNotNull(result, "long key must still be rate-limited (consistent hash bucket)");
        assertEquals(429, result.status());
    }

    // ---- DB-failure fallback (M7b) ----

    /**
     * A broken shared-counter backend must not propagate as a 500. The limiter should fall back
     * to per-instance counting so the request is still served (and still locally limited).
     */
    @Test
    void sharedBackendFailureFallsBackToLocal() {
        // Install a Counters stub that always throws to simulate a DB outage.
        var broken = new Counters(null) {
            @Override
            long incrementAndGet(String key, long delta, Instant expiresAt) {
                throw new RuntimeException("simulated DB outage");
            }
        };
        RateLimiter.useSharedBackend(broken);

        var limiter = RateLimiter.perKey(req -> "user-1", 3, "1m");
        var req = fakeRequest("10.0.0.1");

        // Requests are still served (no 500) and locally limited to 3.
        assertNull(limiter.handle(req));
        assertNull(limiter.handle(req));
        assertNull(limiter.handle(req));
        var result = limiter.handle(req);
        assertNotNull(result, "local fallback must still enforce the limit");
        assertEquals(429, result.status());
    }

    /**
     * After a DB failure the local fallback correctly limits; a *different* long key on the same
     * limiter gets its own bucket under local counting too (collision sanity check under fallback).
     */
    @Test
    void sharedBackendFailureLocalFallbackIsolatesKeys() {
        var broken = new Counters(null) {
            @Override
            long incrementAndGet(String key, long delta, Instant expiresAt) {
                throw new RuntimeException("simulated DB outage");
            }
        };
        RateLimiter.useSharedBackend(broken);

        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 1, "1m");
        var reqA = fakeRequestWithHeader("X-User", "alice");
        var reqB = fakeRequestWithHeader("X-User", "bob");

        // Each user gets their own budget of 1 under local fallback.
        assertNull(limiter.handle(reqA));
        assertNull(limiter.handle(reqB));
        assertNotNull(limiter.handle(reqA), "alice's local bucket exhausted");
        assertNotNull(limiter.handle(reqB), "bob's local bucket exhausted");
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
