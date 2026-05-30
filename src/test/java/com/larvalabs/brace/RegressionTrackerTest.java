package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RegressionTrackerTest {

    static class CapturingNotifier implements Notifier {
        final List<RegressionTracker.Regression> received = new CopyOnWriteArrayList<>();
        @Override public void notifyRegression(RegressionTracker.Regression r) { received.add(r); }
    }

    /** Tracker whose warmup is already over (started an hour ago). */
    private RegressionTracker warmTracker(CapturingNotifier n) {
        return new RegressionTracker(Instant.now().minusSeconds(3600), 30, List.of(n));
    }

    @Test
    void newKindNotifiesOnceAndRepeatBumpsCount() {
        var n = new CapturingNotifier();
        var t = warmTracker(n);

        t.onNew("RuntimeException", "GET /a", "boom", Instant.now());
        t.onNew("RuntimeException", "GET /a", "boom again", Instant.now()); // same kind — dedup
        t.onRepeat("RuntimeException", "GET /a");

        assertEquals(1, n.received.size(), "a new kind notifies exactly once; recurrences don't");
        var list = t.list();
        assertEquals(1, list.size());
        assertEquals(2, list.get(0).count(), "onNew(dup)+onRepeat both increment the count");
    }

    @Test
    void warmupSuppressesEarlyErrors() {
        var n = new CapturingNotifier();
        // Started now with a 30s warmup; an error at startup is within the window.
        var t = new RegressionTracker(Instant.now(), 30, List.of(n));
        t.onNew("RuntimeException", "GET /a", "cold boot", Instant.now());
        assertTrue(t.list().isEmpty(), "errors during warmup are not flagged");
        assertEquals(0, n.received.size());
    }

    @Test
    void acknowledgeMarksAndIsIdempotentlySafe() {
        var n = new CapturingNotifier();
        var t = warmTracker(n);
        t.onNew("NullPointerException", "POST /x", "npe", Instant.now());

        long id = t.list().get(0).id();
        assertFalse(t.list().get(0).acknowledged());
        assertTrue(t.acknowledge(id));
        assertTrue(t.list().get(0).acknowledged());
        assertFalse(t.acknowledge(99999), "unknown id returns false");
    }

    @Test
    void listIsNewestFirst() {
        var n = new CapturingNotifier();
        var t = warmTracker(n);
        t.onNew("E1", "GET /a", "m", Instant.now().minusSeconds(10));
        t.onNew("E2", "GET /b", "m", Instant.now());
        var list = t.list();
        assertEquals("E2", list.get(0).type(), "most recently first-seen comes first");
        assertEquals("E1", list.get(1).type());
    }
}
