package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness review H1: {@code Stats.routes} is a cumulative map that is never reset, so it must
 * be keyed by the matched route PATTERN, never the concrete URL. Keyed by path it leaks one entry
 * per distinct URL ever requested — unbounded in the app's own ids, and unbounded in whatever an
 * attacker types on the 404 path.
 *
 * <p>This invariant was established once by the runtime-performance review (H7,
 * {@code recordRequestPattern}) and silently reverted: the method was added but never wired into
 * {@code BraceHandler}, and nothing in the suite noticed. That is what these tests are for.
 */
class RouteStatsKeyTest {

    static TestApp app;

    @BeforeAll
    static void setup() throws Exception {
        app = Brace.test().start(a -> {
            a.get("/users/{id}", req -> Result.text("user " + req.pathParam("id")));
            a.get("/boom/{id}", req -> { throw new IllegalStateException("kaboom"); });
            a.get("/missing/{id}", req -> Result.notFoundIfNull(null));
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    @Test
    void manyIdsUnderOnePatternCollapseToOneKey() {
        var stats = app.app().stats();
        for (int i = 0; i < 25; i++) {
            app.get("/users/" + i);
        }
        assertTrue(stats.routeStats().containsKey("GET /users/{id}"),
            "expected the route pattern as the key, got: " + stats.routeStats().keySet());
        assertEquals(25, stats.routeStats().get("GET /users/{id}").count());
        assertTrue(stats.routeStats().keySet().stream().noneMatch(k -> k.matches("GET /users/\\d+")),
            "concrete URLs must never become stats keys: " + stats.routeStats().keySet());
    }

    /**
     * Unmatched URLs are attacker-controlled, so they must never reach the map. Note this asserts
     * only the negative: whether an unmatched request is recorded <em>at all</em> is H2's business
     * (today it is not — the no-route path returns before any recording), and the companion
     * assertion that it lands in {@link BraceHandler#UNMATCHED_ROUTE_KEY} lives with that fix.
     */
    @Test
    void unmatchedUrlsNeverBecomeStatsKeys() {
        var stats = app.app().stats();
        for (int i = 0; i < 25; i++) {
            app.get("/no-such-route-" + i);
        }
        assertTrue(stats.routeStats().keySet().stream().noneMatch(k -> k.contains("no-such-route")),
            "unmatched URLs must not become stats keys: " + stats.routeStats().keySet());
    }

    /**
     * A malformed percent-escape in the query string throws out of {@code parseQuery} — which runs
     * <em>before</em> {@code router.match} — so this is the live path where the 500 handler has no
     * match to key on. It must fall back to the unmatched bucket, not to the raw URL.
     */
    @Test
    void throwBeforeRoutingFallsBackToTheUnmatchedBucket() throws Exception {
        var stats = app.app().stats();
        // Sent over a raw socket: java.net.URI rejects "%zz" client-side, so the JDK HTTP client
        // can't produce this request at all.
        rawGet("/users/1?bad=%zz");
        assertTrue(stats.routeStats().containsKey("GET " + BraceHandler.UNMATCHED_ROUTE_KEY),
            "expected the unmatched bucket, got: " + stats.routeStats().keySet());
        assertTrue(stats.routeStats().keySet().stream().noneMatch(k -> k.contains("%zz")),
            "the raw URL must not become a stats key: " + stats.routeStats().keySet());
    }

    /** Minimal HTTP/1.1 GET over a socket, for request lines the JDK client refuses to build. */
    private static void rawGet(String target) throws Exception {
        try (var socket = new java.net.Socket("localhost", app.port())) {
            socket.getOutputStream().write(
                ("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.getInputStream().readAllBytes(); // drain so the server finishes the exchange
        }
    }

    @Test
    void thrownNotFoundIsKeyedByPatternNotPath() {
        var stats = app.app().stats();
        app.get("/missing/7");
        app.get("/missing/8");
        // A handler on a real route choosing to 404 still has a pattern — use it.
        assertEquals(2, stats.routeStats().get("GET /missing/{id}").count());
    }

    @Test
    void serverErrorIsKeyedByPatternNotPath() {
        var stats = app.app().stats();
        app.get("/boom/7");
        app.get("/boom/8");
        assertEquals(2, stats.routeStats().get("GET /boom/{id}").count());
    }
}
