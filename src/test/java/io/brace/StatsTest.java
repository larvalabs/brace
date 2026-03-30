package io.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatsTest {

    @Test
    void recordsRequests() {
        var stats = new Stats();
        stats.recordRequest("GET", "/hello", 200, 500, 1, 100);
        stats.recordRequest("GET", "/hello", 200, 300, 2, 200);
        stats.recordRequest("POST", "/submit", 500, 1000, 0, 0);

        var codes = stats.statusCodeCounts();
        assertEquals(2, codes.get(200));
        assertEquals(1, codes.get(500));
    }

    @Test
    void tracksRouteStats() {
        var stats = new Stats();
        stats.recordRequest("GET", "/hello", 200, 500, 0, 0);
        stats.recordRequest("GET", "/hello", 200, 300, 0, 0);

        var routes = stats.routeStats();
        var helloStats = routes.get("GET /hello");
        assertNotNull(helloStats);
        assertEquals(2, helloStats.count());
        assertEquals(0.4, helloStats.avgLatencyMs(), 0.01); // (500+300)/2 = 400us = 0.4ms
    }

    @Test
    void deduplicatesErrors() {
        var stats = new Stats();
        stats.recordError("NullPointerException", "oops", "GET /test", "stack1", "{}", "[]");
        stats.recordError("NullPointerException", "oops", "GET /test", "stack2", "{}", "[]");

        var errors = stats.recentErrors();
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).count);
    }

    @Test
    void differentErrorTypesNotDeduplicated() {
        var stats = new Stats();
        stats.recordError("NullPointerException", "oops", "GET /test", "stack", "{}", "[]");
        stats.recordError("IllegalArgumentException", "bad", "GET /test", "stack", "{}", "[]");

        assertEquals(2, stats.recentErrors().size());
    }

    @Test
    void rotateMinuteSnapshotsCurrent() {
        var stats = new Stats();
        stats.recordRequest("GET", "/a", 200, 500, 1, 100);
        stats.recordRequest("GET", "/b", 200, 300, 2, 200);

        var snapshot = stats.rotateMinute();
        assertEquals(2, snapshot.requests());
        assertEquals(0, snapshot.errors());
        assertTrue(snapshot.avgLatencyMs() > 0);

        // After rotation, counters should be reset
        var snapshot2 = stats.rotateMinute();
        assertEquals(0, snapshot2.requests());
    }

    @Test
    void minuteBufferStoresSnapshots() {
        var stats = new Stats();
        stats.recordRequest("GET", "/a", 200, 500, 0, 0);
        stats.rotateMinute();
        stats.recordRequest("GET", "/b", 200, 300, 0, 0);
        stats.rotateMinute();

        var snapshots = stats.minuteSnapshots();
        assertEquals(2, snapshots.size());
    }

    @Test
    void startedAtIsSet() {
        var stats = new Stats();
        assertNotNull(stats.startedAt());
    }
}
