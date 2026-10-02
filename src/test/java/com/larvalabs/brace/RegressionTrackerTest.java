package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
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
        t.onRepeat("RuntimeException", "GET /a", 1);

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

        String id = t.list().get(0).id();
        assertFalse(t.list().get(0).acknowledged());
        assertTrue(t.acknowledge(id));
        assertTrue(t.list().get(0).acknowledged());
        assertFalse(t.acknowledge("nonexistent-id"), "unknown id returns false");
    }

    @Test
    void seedCoversMoreKindsThanListLimitSoNoneReNotify() {
        // seed() must read the uncapped since-filtered list: with the unfiltered one (capped at
        // LIST_LIMIT) the oldest kinds go unseeded, and one that is resolved and recurs reaches
        // onNew as a fresh insert and notifies again.
        var dbFactory = new DatabaseFactory(
            "jdbc:h2:mem:regressionseed" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
            List.of(Post.class));
        var errorStore = new ErrorStore(dbFactory, 1000);
        try {
            var startedAt = Instant.now().minusSeconds(3600);
            int total = ErrorStore.LIST_LIMIT + 1;
            var db = new Database(dbFactory.openSession());
            db.beginTransaction();
            for (int i = 0; i < total; i++) {
                var ts = java.sql.Timestamp.from(startedAt.plusSeconds(i));
                db.sql("INSERT INTO ops_errors (error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    "E" + i, "m", "s", "GET /r" + i, "req", ts, ts, 1);
            }
            db.commitTransaction();
            db.close();

            var n = new CapturingNotifier();
            var t = new RegressionTracker(startedAt, 0, List.of(n));
            t.seed(errorStore);
            assertEquals(total, t.list().size(), "every kind since startup is seeded");

            for (int i = 0; i < total; i++) {
                t.onNew("E" + i, "GET /r" + i, "m", Instant.now());
            }
            assertEquals(0, n.received.size(), "seeded kinds never re-notify");
        } finally {
            errorStore.close();
            dbFactory.close();
        }
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
