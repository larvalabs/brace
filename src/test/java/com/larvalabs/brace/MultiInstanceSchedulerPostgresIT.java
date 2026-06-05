package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 on real Postgres: the in-memory recurring scheduler coordinates through the shared DB so a
 * recurring job fires once per interval cluster-wide, not once per instance.
 *
 * <p>The H2 sibling ({@code MultiInstanceSchedulerTest}, kept as a fast Docker-free smoke test)
 * proves the slot-claim <em>logic</em>, but two "instances" against one in-memory H2 can't
 * exercise the mechanism the fix actually relies on: a {@code SELECT … FOR UPDATE} row lock that
 * genuinely serializes the two ticks so the late instance blocks until the early one commits the
 * advanced slot, then reads it and skips. This runs the same scenario against real Postgres,
 * where that locking is real. See {@code docs/2026-06-05-pg-testcontainers.md}.
 */
class MultiInstanceSchedulerPostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;

    @BeforeAll
    static void buildFactory() {
        // Base @BeforeAll started the shared container (skips the class if Docker is absent).
        dbFactory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void closeFactory() {
        if (dbFactory != null) {
            dbFactory.close();
        }
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("brace_scheduled_runs");
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
        // Two instances ticking every second for ~3.5s would run ~6 times WITHOUT coordination;
        // with cluster-wide dedupe over the real Postgres row lock it's once per interval (~3).
        assertTrue(total >= 2, "job should run on schedule, got " + total);
        assertTrue(total <= 4,
                "job should run once per interval across instances, not once per instance; got " + total);
    }
}
