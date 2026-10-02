package com.larvalabs.brace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {

    @BeforeEach
    @AfterEach
    void clean() {
        Metrics.reset();
    }

    @Test
    void recordsIntoTheAppsStats() {
        var app = Brace.app();
        Metrics.counter("talks.created");
        Metrics.counter("bytes.uploaded", 4096);
        Metrics.gauge("queue.depth", () -> 42L);
        Metrics.timer("api.latency", 150);

        var stats = app.stats();
        assertEquals(1, stats.counterTotal("talks.created"));
        assertEquals(4096, stats.counterTotal("bytes.uploaded"));
        assertEquals(42L, stats.currentGaugeValues().get("queue.depth"));
        assertEquals(1, stats.snapshot().timerValues().get("api.latency").count());
    }

    @Test
    void metricsRecordedBeforeTheAppExistsAreAdoptedByIt() {
        // A service built ahead of Brace.app() in main(), registering a gauge in its constructor.
        Metrics.gauge("queue.depth", () -> 7L);
        Metrics.counter("early");

        var app = Brace.app();
        assertEquals(7L, app.stats().currentGaugeValues().get("queue.depth"));
        assertEquals(1, app.stats().counterTotal("early"));
    }

    @Test
    void withoutAnAppCallsDoNotThrow() {
        assertDoesNotThrow(() -> {
            Metrics.counter("unit.test");
            Metrics.timer("unit.test", 5);
            Metrics.gauge("unit.test", () -> 1L);
        });
    }

    @Test
    void followsTheMostRecentlyConstructedApp() {
        var first = Brace.app();
        Metrics.counter("hits");
        var second = Brace.app();
        Metrics.counter("hits");
        Metrics.counter("hits");

        assertNotSame(first.stats(), second.stats(), "each app owns its own Stats");
        assertEquals(1, first.stats().counterTotal("hits"));
        assertEquals(2, second.stats().counterTotal("hits"));

        // The instance API still targets one specific app.
        first.stats().counter("hits");
        assertEquals(2, first.stats().counterTotal("hits"));
        assertEquals(2, second.stats().counterTotal("hits"));
    }

    @Test
    void handlerCallsReachTheRunningApp() throws Exception {
        var testApp = Brace.test().start(app ->
            app.get("/create", req -> {
                Metrics.counter("talks.created");
                return Result.text("ok");
            }));
        try {
            testApp.get("/create");
            testApp.get("/create");
            assertEquals(2, testApp.app().stats().counterTotal("talks.created"));
        } finally {
            testApp.stop();
        }
    }
}
