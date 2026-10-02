package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness review H2: every response leaving the handler must be counted, not just the three
 * paths that happened to have a recording call. Before this fix, stats and the request log ran
 * only on the success path and the two catch blocks — so rate-limiter 429s, CSRF 403s, 413s,
 * static files and unmatched 404s were invisible to {@code /ops/status} entirely, which is
 * precisely the traffic an incident is about.
 */
class ShortCircuitStatsTest {

    static TestApp app;
    static Path assetDir;

    @BeforeAll
    static void setup() throws Exception {
        assetDir = Files.createTempDirectory("brace-h2-assets");
        Files.writeString(assetDir.resolve("app.css"), "body{}");
        app = Brace.test().sessions("h2-short-circuit-secret-at-least-32-chars").start(a -> {
            a.staticFiles("/assets", assetDir.toString());
            a.get("/ok", req -> Result.text("ok"));
            a.before("/blocked", req -> Result.error(429, "Too Many Requests"));
            a.get("/blocked", req -> Result.text("never reached"));
            a.before("/guarded", (req, session) -> Redirect.to("/login"));
            a.get("/guarded", req -> Result.text("never reached"));
            a.post("/mutate", req -> Result.text("mutated"));
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    private static long countOf(int status) {
        return app.app().stats().statusCodeCounts().getOrDefault(status, 0L);
    }

    private static long routeCount(String key) {
        var route = app.app().stats().routeStats().get(key);
        return route == null ? 0 : route.count();
    }

    /**
     * A response is recorded after it is written, so the client can see it before the count moves
     * (see {@link TestWait}). Every test waits for its own requests to land, which also keeps a
     * straggler from one test out of the next test's {@code before} baseline.
     */
    private static void awaitCountOf(int status, long expected) {
        TestWait.until(() -> countOf(status) >= expected,
            () -> "status " + status + " count never reached " + expected + ", was " + countOf(status));
    }

    private static void awaitRouteCount(String key, long expected) {
        TestWait.until(() -> routeCount(key) >= expected,
            () -> key + " count never reached " + expected + ", was " + routeCount(key));
    }

    @Test
    void beforeMiddlewareShortCircuitIsCounted() {
        long before = countOf(429);
        assertEquals(429, app.get("/blocked").status());
        awaitCountOf(429, before + 1);
        assertEquals(before + 1, countOf(429), "a 429 from before-middleware must reach stats");
    }

    @Test
    void sessionMiddlewareShortCircuitIsCounted() {
        long before = countOf(302);
        assertEquals(302, app.get("/guarded").status());
        awaitCountOf(302, before + 1);
        assertEquals(before + 1, countOf(302), "a guard redirect must reach stats");
    }

    @Test
    void csrfRejectionIsCounted() {
        long before = countOf(403);
        // No _csrf param and no X-CSRF-Token: rejected before the handler runs.
        assertEquals(403, app.post("/mutate", Map.of("x", "1")).status());
        awaitCountOf(403, before + 1);
        assertEquals(before + 1, countOf(403), "a CSRF 403 must reach stats");
    }

    @Test
    void unmatchedRouteIsCountedInTheUnmatchedBucket() {
        long before = countOf(404);
        assertEquals(404, app.get("/no-such-route-at-all").status());
        awaitCountOf(404, before + 1);
        assertEquals(before + 1, countOf(404), "an unmatched 404 must reach stats");
        assertTrue(app.app().stats().routeStats()
                .containsKey("GET " + BraceHandler.UNMATCHED_ROUTE_KEY),
            "expected the unmatched bucket, got: " + app.app().stats().routeStats().keySet());
    }

    @Test
    void staticFilesAreCountedUnderTheirOwnBucket() {
        long before = routeCount("GET " + BraceHandler.STATIC_ROUTE_KEY);
        assertEquals(200, app.get("/assets/app.css").status());
        awaitRouteCount("GET " + BraceHandler.STATIC_ROUTE_KEY, before + 1);
        var keys = app.app().stats().routeStats().keySet();
        assertTrue(keys.contains("GET " + BraceHandler.STATIC_ROUTE_KEY),
            "expected the static bucket, got: " + keys);
        // The filename is client-supplied — it must not become a key of its own.
        assertTrue(keys.stream().noneMatch(k -> k.contains("app.css")),
            "asset filenames must not become stats keys: " + keys);
    }

    @Test
    void missingAssetsShareTheStaticBucketRatherThanMintingKeys() {
        long before = countOf(404);
        for (int i = 0; i < 20; i++) {
            app.get("/assets/nope-" + i + ".css");
        }
        // Until all 20 are recorded, a key check below would pass without having seen them.
        awaitCountOf(404, before + 20);
        var keys = app.app().stats().routeStats().keySet();
        assertTrue(keys.stream().noneMatch(k -> k.contains("nope-")),
            "missing-asset URLs must not become stats keys: " + keys);
    }

    @Test
    void shortCircuitedResponsesAreAlsoLogged() {
        // Stats and the http.request log line are recorded together at the choke point, after the
        // response is written, so a line can land a moment after the client has the response.
        long mark = lastLogId();
        app.get("/blocked");
        app.post("/mutate", Map.of("x", "1"));
        app.get("/assets/app.css");
        app.get("/no-such-route-logged");
        TestWait.until(() -> logged(mark, "GET", "/blocked", 429), "before-middleware 429 must be logged");
        TestWait.until(() -> logged(mark, "POST", "/mutate", 403), "CSRF 403 must be logged");
        TestWait.until(() -> logged(mark, "GET", "/assets/app.css", 200), "static file must be logged");
        TestWait.until(() -> logged(mark, "GET", "/no-such-route-logged", 404), "unmatched 404 must be logged");
    }

    private static long lastLogId() {
        var snap = LogTap.snapshot();
        return snap.isEmpty() ? 0 : snap.get(snap.size() - 1).id();
    }

    private static boolean logged(long afterId, String method, String path, int status) {
        return LogTap.since(afterId).stream().map(LogTap.LogEntry::fields).anyMatch(f ->
            "http.request".equals(f.get("event")) && method.equals(f.get("method"))
                && path.equals(f.get("path")) && String.valueOf(status).equals(String.valueOf(f.get("status"))));
    }

    @Test
    void everyResponseIsCountedExactlyOnce() {
        long before = countOf(200);
        for (int i = 0; i < 5; i++) {
            app.get("/ok");
        }
        awaitCountOf(200, before + 5);
        assertEquals(before + 5, countOf(200), "each response must be recorded exactly once");
    }
}
