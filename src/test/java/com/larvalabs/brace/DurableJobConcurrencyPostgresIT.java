package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B7 on real Postgres: many durable jobs drained by several concurrent pollers, each job must
 * execute exactly once.
 *
 * <p>On Postgres the poller claims a batch in one transaction with
 * {@code … ORDER BY run_at LIMIT 50 FOR UPDATE SKIP LOCKED}, flipping {@code started_at} in the
 * same statement (postgres-native doc Tier 1a). SKIP LOCKED hands concurrent pollers disjoint
 * batches, so each job is claimed — and run — exactly once by construction. This IT guards that
 * property on real Postgres with real concurrent transactions: the {@code HashSet} size check
 * would catch any double-claim that SKIP LOCKED failed to prevent. H2 in-memory can't express
 * SKIP LOCKED at all (it falls back to a select-then-per-row-claim defended by the B7 row-count
 * guard), so the H2 {@code DurableJobTest} covers functional behavior — schedule/execute/retry/
 * deps — but not this. See {@code docs/2026-06-05-pg-testcontainers.md}.
 */
class DurableJobConcurrencyPostgresIT extends PostgresTestBase {

    static DatabaseFactory factory;

    /** Records every execution by payload; a duplicate payload means a job ran twice. */
    public static class CountingJob implements DurableJob {
        static final List<String> executed = Collections.synchronizedList(new ArrayList<>());
        private final String payload;
        public CountingJob() { this.payload = null; }
        public CountingJob(String payload) { this.payload = payload; }
        @Override public String data() { return payload; }
        @Override public void run(String data, Database db) {
            executed.add(data);
        }
    }

    @BeforeAll
    static void buildFactory() {
        factory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void closeFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("scheduled_jobs");
        CountingJob.executed.clear();
    }

    @Test
    void everyJobRunsExactlyOnceUnderConcurrentPollers() throws Exception {
        int jobCount = 80;
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            for (int i = 0; i < jobCount; i++) {
                Jobs.schedule(db, new CountingJob("job-" + i), Duration.ZERO);
            }
            db.commitTransaction();
        } finally {
            db.close();
        }

        // Four independent pollers = four "instances" racing the claim on one shared table.
        int pollers = 4;
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < pollers; i++) {
            threads.add(Thread.startVirtualThread(() -> {
                var poller = new JobPoller();
                // Drain until two consecutive empty polls (another poller may still be finishing
                // a claimed batch); bounded so a regression can't hang the suite.
                int empties = 0, iterations = 0;
                while (empties < 2 && iterations++ < 200) {
                    int found = poller.pollAndExecute(factory);
                    empties = (found == 0) ? empties + 1 : 0;
                }
            }));
        }
        for (var t : threads) {
            t.join();
        }

        var executed = CountingJob.executed;
        // The B7 property: exactly jobCount executions, each payload exactly once. A double-run
        // would push the size above jobCount and/or duplicate a payload.
        assertEquals(jobCount, executed.size(),
                "each job must execute exactly once across concurrent pollers (no double-run)");
        assertEquals(jobCount, new HashSet<>(executed).size(), "no job payload should appear twice");

        // And the DB agrees: everything completed, nothing stuck pending/running.
        var db2 = new Database(factory.openSession());
        try {
            db2.beginTransaction();
            var stats = JobPoller.getDurableJobStats(db2);
            assertEquals(0, stats.pending(), "no job left pending");
            assertEquals(0, stats.running(), "no job left stuck running");
            assertEquals(jobCount, stats.completed(), "all jobs completed");
            db2.commitTransaction();
        } finally {
            db2.close();
        }
    }
}
