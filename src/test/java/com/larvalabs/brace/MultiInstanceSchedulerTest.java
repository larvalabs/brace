package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies B1 fix: the in-memory recurring scheduler coordinates through the
 * shared database so a recurring job fires once per interval cluster-wide,
 * instead of once per instance (which would duplicate side effects N-fold).
 */
class MultiInstanceSchedulerTest {

    static DatabaseFactory dbFactory;

    @BeforeAll
    static void setup() {
        dbFactory = new DatabaseFactory(
            "jdbc:h2:mem:schedrundb;DB_CLOSE_DELAY=-1", null, null, List.of());
    }

    @AfterAll
    static void teardown() {
        dbFactory.close();
    }

    @BeforeEach
    void clean() {
        var db = new Database(dbFactory.openSession());
        db.beginTransaction();
        db.sql("DELETE FROM brace_scheduled_runs");
        db.commitTransaction();
        db.close();
    }

    @Test
    void recurringJobRunsOncePerIntervalAcrossInstances() throws Exception {
        var runs = new AtomicInteger(0);
        Job job = (db, ctx) -> runs.incrementAndGet();

        var a = new JobScheduler();
        var b = new JobScheduler();
        a.every("1s", "shared-job", job);
        b.every("1s", "shared-job", job);

        a.start(dbFactory);
        b.start(dbFactory);

        Thread.sleep(3500); // ~3 one-second intervals
        a.stop();
        b.stop();

        int total = runs.get();
        // Two instances ticking every second for ~3.5s run ~6 times WITHOUT
        // coordination; with cluster-wide dedupe it should be once per interval (~3).
        assertTrue(total >= 2, "job should run on schedule, got " + total);
        assertTrue(total <= 4, "job should run once per interval across instances, not once per instance; got " + total);
    }

    @Test
    void dailyJobRegisteredAfterStartIsScheduled() throws Exception {
        // Regression: daily() lacked register()'s "scheduler already running" branch, so a daily job
        // added AFTER start() was tracked in statuses but never handed to the executor — it silently
        // never ran. Register one targeting ~1-2s out and confirm it actually fires.
        var runs = new AtomicInteger(0);
        var a = new JobScheduler();
        a.start(dbFactory); // start with no jobs registered yet

        // plusSeconds(2) then truncate to whole seconds → target is ~1-2s in the future (daily() parses
        // HH:mm:ss). computeDelayUntil rolls to tomorrow only if the target is already past, which this
        // isn't except in a ~1s window at midnight — acceptable for a timing test.
        var target = java.time.LocalTime.now().plusSeconds(2).withNano(0);
        a.daily(target.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
            "late-daily", (db, ctx) -> runs.incrementAndGet());

        Thread.sleep(4000);
        a.stop();

        assertTrue(runs.get() >= 1,
            "a daily job registered after start() should be scheduled and run; got " + runs.get());
    }

    @Test
    void singleInstanceStillRunsEveryInterval() throws Exception {
        var runs = new AtomicInteger(0);
        var a = new JobScheduler();
        a.every("1s", "solo-job", (db, ctx) -> runs.incrementAndGet());

        a.start(dbFactory);
        Thread.sleep(3500);
        a.stop();

        assertTrue(runs.get() >= 2, "single instance should still run each interval, got " + runs.get());
    }
}
