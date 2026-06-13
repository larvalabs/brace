package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B6 on real Postgres: two {@link RegressionTracker} instances sharing one database (standing in for
 * two boxes behind a load balancer) behave as one fleet — a new error kind notifies exactly once,
 * acknowledge on one instance is visible on the other, the regression set is identical across
 * instances, and the deploy marker scopes the baseline.
 */
class RegressionPostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;

    static class CapturingNotifier implements Notifier {
        final List<RegressionTracker.Regression> received = new CopyOnWriteArrayList<>();
        @Override public void notifyRegression(RegressionTracker.Regression r) { received.add(r); }
    }

    @BeforeAll
    static void buildFactory() {
        dbFactory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void closeFactory() {
        if (dbFactory != null) dbFactory.close();
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("brace_regressions");
    }

    /** Warmed-up tracker on a given deploy, sharing the Postgres-backed store. */
    private RegressionTracker tracker(CapturingNotifier n, String deploy) {
        return new RegressionTracker(Instant.now().minusSeconds(3600), 30, List.of(n), deploy, dbFactory);
    }

    @Test
    void newKindNotifiesExactlyOnceAcrossInstances() {
        var nA = new CapturingNotifier();
        var nB = new CapturingNotifier();
        var a = tracker(nA, "deploy-1");
        var b = tracker(nB, "deploy-1");

        // The same new error kind observed on both instances must alert only once fleet-wide.
        a.onNew("RuntimeException", "GET /x", "boom", Instant.now());
        b.onNew("RuntimeException", "GET /x", "boom", Instant.now());

        assertEquals(1, nA.received.size() + nB.received.size(),
            "a new kind should notify exactly once across the fleet");
        // And both instances see the regression in their list.
        assertEquals(1, a.list().size());
        assertEquals(1, b.list().size());
        assertEquals(a.list().get(0).id(), b.list().get(0).id(), "stable id is identical across instances");
    }

    @Test
    void acknowledgeOnOneInstanceIsVisibleOnTheOther() {
        var a = tracker(new CapturingNotifier(), "deploy-1");
        var b = tracker(new CapturingNotifier(), "deploy-1");

        a.onNew("NullPointerException", "POST /y", "npe", Instant.now());
        String id = a.list().get(0).id();
        assertFalse(b.list().get(0).acknowledged());

        assertTrue(b.acknowledge(id), "acknowledge on instance B");
        assertTrue(a.list().get(0).acknowledged(), "instance A sees the acknowledgement");
    }

    @Test
    void recurrenceBumpsCountFleetWide() {
        var a = tracker(new CapturingNotifier(), "deploy-1");
        var b = tracker(new CapturingNotifier(), "deploy-1");

        a.onNew("E", "GET /z", "m", Instant.now()); // count 1, claimed by A
        b.onRepeat("E", "GET /z", 1);               // +1 from B
        a.onRepeat("E", "GET /z", 1);               // +1 from A
        assertEquals(3, a.list().get(0).count(), "count accumulates across instances");
    }

    @Test
    void deployMarkerScopesTheBaseline() {
        var d1 = tracker(new CapturingNotifier(), "deploy-1");
        var d2 = tracker(new CapturingNotifier(), "deploy-2");

        d1.onNew("RuntimeException", "GET /x", "boom", Instant.now());
        // A different deploy starts from a clean baseline — same error is a fresh regression there,
        // and d1's list doesn't include d2's and vice versa.
        assertEquals(1, d1.list().size());
        assertEquals(0, d2.list().size(), "deploy-2 sees none of deploy-1's regressions");

        var n2 = new CapturingNotifier();
        var d2b = tracker(n2, "deploy-2");
        d2b.onNew("RuntimeException", "GET /x", "boom", Instant.now());
        assertEquals(1, d2b.list().size(), "same error under a new deploy is a fresh regression");
        assertEquals(1, n2.received.size(), "and it notifies under the new deploy");
    }
}
