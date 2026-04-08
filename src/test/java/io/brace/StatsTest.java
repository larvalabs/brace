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
    void snapshotsCurrent() {
        var stats = new Stats();
        stats.recordRequest("GET", "/a", 200, 500, 1, 100);
        stats.recordRequest("GET", "/b", 200, 300, 2, 200);

        var snapshot = stats.snapshot();
        assertEquals(2, snapshot.requests());
        assertEquals(0, snapshot.errors());
        assertTrue(snapshot.avgLatencyMs() > 0);

        // After rotation, counters should be reset
        var snapshot2 = stats.snapshot();
        assertEquals(0, snapshot2.requests());
    }

    @Test
    void minuteBufferStoresSnapshots() {
        var stats = new Stats();
        stats.recordRequest("GET", "/a", 200, 500, 0, 0);
        stats.snapshot();
        stats.recordRequest("GET", "/b", 200, 300, 0, 0);
        stats.snapshot();

        var snapshots = stats.minuteSnapshots();
        assertEquals(2, snapshots.size());
    }

    @Test
    void startedAtIsSet() {
        var stats = new Stats();
        assertNotNull(stats.startedAt());
    }

    @Test
    void minuteSnapshotIncludesHeapUsedMB() {
        var stats = new Stats();
        stats.recordRequest("GET", "/test", 200, 1000, 0, 0);
        var snap = stats.snapshot();
        assertTrue(snap.heapUsedMB() > 0, "heapUsedMB should be captured from runtime");
    }

    @Test
    void counterIncrementsByOne() {
        var stats = new Stats();
        stats.counter("talks.created");
        stats.counter("talks.created");
        stats.counter("talks.created");
        var snapshot = stats.snapshot();
        assertEquals(3, snapshot.counterDeltas().get("talks.created"));
    }

    @Test
    void counterIncrementsByN() {
        var stats = new Stats();
        stats.counter("bytes.uploaded", 4096);
        stats.counter("bytes.uploaded", 2048);
        var snapshot = stats.snapshot();
        assertEquals(6144, snapshot.counterDeltas().get("bytes.uploaded"));
    }

    @Test
    void counterResetsAfterSnapshot() {
        var stats = new Stats();
        stats.counter("events");
        stats.counter("events");
        stats.snapshot();
        stats.counter("events");
        var snapshot = stats.snapshot();
        assertEquals(1, snapshot.counterDeltas().get("events"));
    }

    @Test
    void counterTotalIsCumulative() {
        var stats = new Stats();
        stats.counter("events");
        stats.counter("events");
        stats.snapshot();
        stats.counter("events");
        assertEquals(3, stats.counterTotal("events"));
    }

    @Test
    void minuteSnapshotsReturnCapturedHeap() {
        var stats = new Stats();
        stats.recordRequest("GET", "/test", 200, 1000, 0, 0);
        stats.snapshot();
        var snapshots = stats.minuteSnapshots();
        assertFalse(snapshots.isEmpty());
        assertTrue(snapshots.getFirst().heapUsedMB() > 0);
    }
}
