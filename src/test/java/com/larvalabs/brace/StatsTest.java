package com.larvalabs.brace;

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
    void routeKeysRedactHighEntropyPathSegments() {
        var stats = new Stats();
        stats.recordRequest("GET", "/password-reset/a3f9Bc2d8eF1g4h5", 200, 500, 0, 0);
        stats.recordRequest("GET", "/password-reset/Zz8x7Ww6Vv5Uu4Tt", 200, 300, 0, 0);

        var routes = stats.routeStats();
        var redacted = routes.get("GET /password-reset/[redacted]");
        assertNotNull(redacted, "token-bearing paths should collapse into one redacted route key");
        assertEquals(2, redacted.count());
        assertTrue(routes.keySet().stream().noneMatch(k -> k.contains("a3f9Bc2d8eF1g4h5")),
            "raw reset token must not appear in /ops/status route keys");
    }

    @Test
    void patternKeyedRequestsCollapseDistinctPaths() {
        // H7: matched requests are recorded under the route pattern, so distinct entity
        // IDs share one key and the routes map stays bounded by the route table.
        var stats = new Stats();
        stats.recordRequestPattern("GET", "/users/{id}", 200, 500, 1, 100);
        stats.recordRequestPattern("GET", "/users/{id}", 200, 300, 1, 100);
        stats.recordRequestPattern("GET", "/users/{id}", 404, 200, 1, 100);

        var routes = stats.routeStats();
        assertEquals(1, routes.size(), "all requests to one route share one key");
        assertEquals(3, routes.get("GET /users/{id}").count());
    }

    @Test
    void errorMessagesRedactedAtSink() {
        var stats = new Stats();
        stats.recordError("RuntimeException",
            "auth failed for bearer sk_live_a3f9Bc2d8eF1g4h5J6k7", "GET /api", "stack", "{}", "[]");

        var errors = stats.recentErrors();
        assertEquals(1, errors.size());
        assertFalse(errors.get(0).message.contains("sk_live_a3f9Bc2d8eF1g4h5J6k7"),
            "embedded secret must be redacted before the message is served on /ops/status");
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
    void gaugeSamplesSupplierAtSnapshotTime() {
        var stats = new Stats();
        var value = new java.util.concurrent.atomic.AtomicLong(42);
        stats.gauge("queue.depth", value::get);
        var snapshot = stats.snapshot();
        assertEquals(42, snapshot.gaugeValues().get("queue.depth"));
        value.set(99);
        snapshot = stats.snapshot();
        assertEquals(99, snapshot.gaugeValues().get("queue.depth"));
    }

    @Test
    void gaugeReplacesSupplierOnReregister() {
        var stats = new Stats();
        stats.gauge("metric", () -> 10L);
        stats.gauge("metric", () -> 20L);
        var snapshot = stats.snapshot();
        assertEquals(20, snapshot.gaugeValues().get("metric"));
    }

    @Test
    void timerRecordsCountAndAverage() {
        var stats = new Stats();
        stats.timer("api.latency", 100);
        stats.timer("api.latency", 200);
        stats.timer("api.latency", 300);
        var snapshot = stats.snapshot();
        var timer = snapshot.timerValues().get("api.latency");
        assertEquals(3, timer.count());
        assertEquals(200.0, timer.avgMs(), 0.01);
        assertEquals(300, timer.maxMs());
    }

    @Test
    void timerResetsAfterSnapshot() {
        var stats = new Stats();
        stats.timer("api.latency", 100);
        stats.snapshot();
        stats.timer("api.latency", 500);
        var snapshot = stats.snapshot();
        var timer = snapshot.timerValues().get("api.latency");
        assertEquals(1, timer.count());
        assertEquals(500.0, timer.avgMs(), 0.01);
        assertEquals(500, timer.maxMs());
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

    @Test
    void requestRateIsLastFullMinuteAndRingAverage() {
        var stats = new Stats();
        assertNull(stats.requestRate(), "no full minute before the first rotation");
        for (int i = 0; i < 6; i++) stats.recordRequestPattern("GET", "/a", 200, 100, 0, 0);
        stats.snapshot();
        for (int i = 0; i < 2; i++) stats.recordRequestPattern("GET", "/a", 200, 100, 0, 0);
        stats.snapshot();
        stats.recordRequestPattern("GET", "/a", 200, 100, 0, 0); // current, unfinished minute

        var rate = stats.requestRate();
        assertEquals(2, rate.lastMinute());
        assertEquals(4.0, rate.avgPerMinute(), 0.001);
        assertEquals(2, rate.windowMinutes());
    }

    @Test
    void minuteSnapshotsCarryPerRouteCountsWithUnmatchedFolded() {
        var stats = new Stats();
        stats.recordRequestPattern("GET", "/users/{id}", 200, 100, 0, 0);
        stats.recordRequestPattern("GET", "/users/{id}", 200, 100, 0, 0);
        stats.recordRequestPattern("POST", "/login", 200, 100, 0, 0);
        stats.recordRequest("GET", "/wp-login.php", 404, 100, 0, 0);
        stats.recordRequest("GET", "/.env", 404, 100, 0, 0);

        var counts = stats.snapshot().routeCounts();
        assertEquals(2L, counts.get("GET /users/{id}"));
        assertEquals(1L, counts.get("POST /login"));
        assertEquals(2L, counts.get(Stats.UNMATCHED_ROUTE), "scanner paths fold into one row");
        assertEquals(3, counts.size(), "raw paths never appear as their own keys: " + counts);

        stats.recordRequestPattern("POST", "/login", 200, 100, 0, 0);
        assertEquals(java.util.Map.of("POST /login", 1L), stats.snapshot().routeCounts(),
            "counts reset at rotation; idle routes are omitted");
        assertEquals(2, stats.routeStats().get("GET /users/{id}").count(), "cumulative count unaffected");
    }

    /**
     * The framework's own non-route buckets (BraceHandler records every response, so every method
     * reaches them): each folds into one method-less row, and the raw-path fold joins the same
     * unmatched row rather than a second one.
     */
    @Test
    void minuteSnapshotsFoldTheNonRouteBucketsAcrossMethods() {
        var stats = new Stats();
        stats.recordRequestPattern("GET", Stats.UNMATCHED_ROUTE, 404, 100, 0, 0);
        stats.recordRequestPattern("POST", Stats.UNMATCHED_ROUTE, 404, 100, 0, 0);
        stats.recordRequest("GET", "/wp-login.php", 404, 100, 0, 0);
        stats.recordRequestPattern("GET", Stats.STATIC_ROUTE, 200, 100, 0, 0);
        stats.recordRequestPattern("HEAD", Stats.STATIC_ROUTE, 200, 100, 0, 0);
        stats.recordRequestPattern("GET", "/users/{id}", 200, 100, 0, 0);

        assertEquals(java.util.Map.of(Stats.UNMATCHED_ROUTE, 3L, Stats.STATIC_ROUTE, 2L, "GET /users/{id}", 1L),
            stats.snapshot().routeCounts());
        // The cumulative per-route table keeps the per-method keys.
        assertEquals(1, stats.routeStats().get("HEAD " + Stats.STATIC_ROUTE).count());
    }

    @Test
    void topRoutesRankByWindowedCount() {
        var stats = new Stats();
        assertTrue(stats.topRoutes(5, 5).isEmpty());
        // Old minute, outside a 2-minute window: /old dominated then.
        for (int i = 0; i < 100; i++) stats.recordRequestPattern("GET", "/old", 200, 100, 0, 0);
        stats.snapshot();
        for (int i = 0; i < 6; i++) stats.recordRequestPattern("GET", "/hot", 200, 100, 0, 0);
        stats.recordRequestPattern("GET", "/old", 200, 100, 0, 0);
        stats.snapshot();
        for (int i = 0; i < 2; i++) stats.recordRequestPattern("GET", "/hot", 200, 100, 0, 0);
        stats.recordRequest("GET", "/wp-admin", 404, 100, 0, 0);
        stats.snapshot();

        var top = stats.topRoutes(2, 5);
        assertEquals(3, top.size());
        assertEquals("GET /hot", top.get(0).route());
        assertEquals(8, top.get(0).count());
        assertEquals(4.0, top.get(0).perMinute(), 0.001);
        assertEquals(0.8, top.get(0).share(), 0.001);
        assertEquals(2, top.get(0).windowMinutes());
        assertEquals(1, stats.topRoutes(2, 1).size(), "limit applies");
    }

    @Test
    void latencyHistogramBucketsAreContiguousAndTight() {
        int prev = -1;
        for (long us = 0; us < 1_000_000; us++) {
            int b = Stats.LatencyHistogram.bucket(us);
            assertTrue(b == prev || b == prev + 1, "buckets are contiguous at " + us);
            assertTrue(us < Stats.LatencyHistogram.upperBound(b), "value below its bucket's upper edge at " + us);
            if (b > 0) assertTrue(us >= Stats.LatencyHistogram.upperBound(b - 1), "value above the previous edge at " + us);
            prev = b;
        }
        assertEquals(Stats.LatencyHistogram.BUCKETS - 1, Stats.LatencyHistogram.bucket(Long.MAX_VALUE), "huge values clamp");
        assertEquals(0, Stats.LatencyHistogram.bucket(-5), "negative durations clamp to 0");
    }

    @Test
    void minuteSnapshotCarriesP95Latency() {
        var stats = new Stats();
        // 1..1000 ms: the true p95 is 950 ms.
        for (int ms = 1; ms <= 1000; ms++) stats.recordRequestPattern("GET", "/a", 200, ms * 1000L, 0, 0);
        var snap = stats.snapshot();
        assertTrue(snap.p95LatencyMs() >= 950 && snap.p95LatencyMs() <= 950 * 1.125,
            "p95 within one bucket (12.5%) above the true value: " + snap.p95LatencyMs());
        assertTrue(snap.p95LatencyUs() <= snap.maxLatencyUs());

        assertEquals(0, stats.snapshot().p95LatencyUs(), "histogram resets at rotation");

        // One sample: p95 is capped at the observed max, not the bucket edge.
        stats.recordRequestPattern("GET", "/a", 200, 1234, 0, 0);
        assertEquals(1234, stats.snapshot().p95LatencyUs());
    }

    @Test
    void minuteSnapshotKeepsPre0110Constructor() {
        var snap = new Stats.MinuteSnapshot(java.time.Instant.EPOCH, 10, 1, 5000, 900, 3, 300, 64,
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        assertEquals(java.util.Map.of(), snap.routeCounts());
        assertEquals(0, snap.p95LatencyUs());
        assertEquals(0.5, snap.avgLatencyMs(), 0.001);
    }
}
