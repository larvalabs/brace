package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * H7 guard through the real request path (BraceHandler → Stats), not Stats directly: matched
 * requests must be keyed by route pattern so the per-route map stays bounded by the route
 * table. This wiring was silently lost once in a merge; StatsTest alone could not catch it.
 */
class RouteStatsWiringTest {

    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test().start(app -> {
            // An unmatched path that fails in middleware: a 500 with no route to key on.
            app.before(req -> {
                if (req.path().startsWith("/scanner/")) throw new RuntimeException("middleware failure");
                return null;
            });
            app.get("/users/{id}", req -> Result.text("user " + req.pathParam("id")));
            app.get("/missing/{id}", req -> { throw new NotFoundException(); });
            app.get("/boom/{id}", req -> { throw new RuntimeException("handler failure"); });
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
    }

    @Test
    void perRouteStatsAreKeyedByPatternAndBounded() {
        var stats = testApp.app().stats();
        stats.snapshot(); // start a clean minute for the windowed counts below

        assertEquals(200, testApp.get("/users/1").status());
        assertEquals(200, testApp.get("/users/2").status());
        assertEquals(200, testApp.get("/users/3").status());
        assertEquals(404, testApp.get("/missing/9").status());   // handler-thrown 404
        assertEquals(500, testApp.get("/boom/9").status());      // handler-thrown 500
        assertEquals(404, testApp.get("/wp-login.php").status()); // no route
        assertEquals(500, testApp.get("/scanner/x1").status());  // no route, middleware failure

        // Requests are recorded after the response is written; wait for all seven (see TestWait).
        TestWait.until(() -> recorded(stats) >= 7, () -> "only " + recorded(stats) + " of 7 requests recorded");
        var routes = stats.routeStats();
        assertNotNull(routes.get("GET /users/{id}"), "matched requests must be keyed by pattern: " + routes.keySet());
        assertEquals(3,routes.get("GET /users/{id}").count());
        assertEquals(1, routes.keySet().stream().filter(k -> k.contains("/users/")).count(),
            "one key for all user IDs: " + routes.keySet());
        assertEquals(1, routes.get("GET /missing/{id}").count(), "handler 404s keep the pattern key");
        assertEquals(1, routes.get("GET /boom/{id}").count(), "handler 500s keep the pattern key");
        assertTrue(routes.keySet().stream().noneMatch(k -> k.contains("/wp-login.php")),
            "a plain no-route 404 adds no raw-path key: " + routes.keySet());
        // Both no-route responses (the 404 and the middleware 500) are counted, in the one
        // bounded unmatched bucket (correctness H2: every response reaches the choke point).
        assertEquals(2, routes.get("GET " + Stats.UNMATCHED_ROUTE).count(), "unmatched bucket: " + routes.keySet());
        assertTrue(routes.keySet().stream().noneMatch(k -> k.contains("/scanner/")),
            "a middleware failure on an unmatched path adds no raw-path key: " + routes.keySet());

        // Minute snapshot: patterns as-is, both unmatched requests folded into one bucket.
        var counts = stats.snapshot().routeCounts();
        assertEquals(3L, counts.get("GET /users/{id}"));
        assertEquals(2L, counts.get(Stats.UNMATCHED_ROUTE), "unmatched requests fold into (unmatched): " + counts);
        assertFalse(counts.containsKey("GET " + Stats.UNMATCHED_ROUTE), "no per-method unmatched entry: " + counts);
        assertTrue(counts.keySet().stream().noneMatch(k -> k.contains("/scanner/")), "no raw-path keys: " + counts);
    }

    private static long recorded(Stats stats) {
        return stats.routeStats().values().stream().mapToLong(r -> r.count()).sum();
    }
}
