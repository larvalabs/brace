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

    @Test
    void beforeMiddlewareShortCircuitIsCounted() {
        long before = countOf(429);
        assertEquals(429, app.get("/blocked").status());
        assertEquals(before + 1, countOf(429), "a 429 from before-middleware must reach stats");
    }

    @Test
    void sessionMiddlewareShortCircuitIsCounted() {
        long before = countOf(302);
        assertEquals(302, app.get("/guarded").status());
        assertEquals(before + 1, countOf(302), "a guard redirect must reach stats");
    }

    @Test
    void csrfRejectionIsCounted() {
        long before = countOf(403);
        // No _csrf param and no X-CSRF-Token: rejected before the handler runs.
        assertEquals(403, app.post("/mutate", Map.of("x", "1")).status());
        assertEquals(before + 1, countOf(403), "a CSRF 403 must reach stats");
    }

    @Test
    void unmatchedRouteIsCountedInTheUnmatchedBucket() {
        long before = countOf(404);
        assertEquals(404, app.get("/no-such-route-at-all").status());
        assertEquals(before + 1, countOf(404), "an unmatched 404 must reach stats");
        assertTrue(app.app().stats().routeStats()
                .containsKey("GET " + BraceHandler.UNMATCHED_ROUTE_KEY),
            "expected the unmatched bucket, got: " + app.app().stats().routeStats().keySet());
    }

    @Test
    void staticFilesAreCountedUnderTheirOwnBucket() {
        assertEquals(200, app.get("/assets/app.css").status());
        var keys = app.app().stats().routeStats().keySet();
        assertTrue(keys.contains("GET " + BraceHandler.STATIC_ROUTE_KEY),
            "expected the static bucket, got: " + keys);
        // The filename is client-supplied — it must not become a key of its own.
        assertTrue(keys.stream().noneMatch(k -> k.contains("app.css")),
            "asset filenames must not become stats keys: " + keys);
    }

    @Test
    void missingAssetsShareTheStaticBucketRatherThanMintingKeys() {
        for (int i = 0; i < 20; i++) {
            app.get("/assets/nope-" + i + ".css");
        }
        var keys = app.app().stats().routeStats().keySet();
        assertTrue(keys.stream().noneMatch(k -> k.contains("nope-")),
            "missing-asset URLs must not become stats keys: " + keys);
    }

    @Test
    void everyResponseIsCountedExactlyOnce() {
        long before = countOf(200);
        for (int i = 0; i < 5; i++) {
            app.get("/ok");
        }
        assertEquals(before + 5, countOf(200), "each response must be recorded exactly once");
    }
}
