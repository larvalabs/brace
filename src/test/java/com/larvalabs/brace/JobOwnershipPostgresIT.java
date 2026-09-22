package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Durable-job ownership on real Postgres. {@link JobOwnershipTest} covers the model on H2; this
 * class covers what only Postgres exercises: the {@code SKIP LOCKED} batch claim writing
 * {@code claimed_by}, and the sweep's heartbeat comparison against {@code TIMESTAMPTZ} columns.
 */
class JobOwnershipPostgresIT extends PostgresTestBase {

    static DatabaseFactory factory;

    public static class BlockingJob implements DurableJob {
        static final AtomicInteger started = new AtomicInteger();
        static volatile CountDownLatch gate = new CountDownLatch(1);
        public BlockingJob() {}
        @Override public String data() { return null; }
        @Override public void run(String data, Database db) throws Exception {
            started.incrementAndGet();
            gate.await();
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
        truncate("scheduled_jobs", "brace_job_workers");
        BlockingJob.started.set(0);
        BlockingJob.gate = new CountDownLatch(1);
    }

    @AfterEach
    void openGate() {
        BlockingJob.gate.countDown();
    }

    @Test
    void batchClaimRecordsTheOwner() throws Exception {
        long id = schedule();
        var poller = new JobPoller();
        var batch = poller.dispatch(factory);
        awaitUntil(() -> BlockingJob.started.get() == 1);

        assertEquals(poller.workerId(), claimedBy(id));

        BlockingJob.gate.countDown();
        for (var t : batch.threads()) t.join();
        assertEquals(1L, count("SELECT COUNT(*) FROM scheduled_jobs WHERE completed_at IS NOT NULL"));
    }

    @Test
    void sweepRecoversOnlyJobsWhoseOwnerStoppedHeartbeating() throws Exception {
        long live = schedule();
        long dead = schedule();
        long legacy = schedule();
        exec("INSERT INTO brace_job_workers (id, heartbeat_at) VALUES ('live', ?)", ago(Duration.ofSeconds(5)));
        exec("INSERT INTO brace_job_workers (id, heartbeat_at) VALUES ('dead', ?)", ago(Duration.ofMinutes(5)));
        claim(live, "live", Duration.ofHours(10));
        claim(dead, "dead", Duration.ofMinutes(1));
        claim(legacy, null, Duration.ofHours(1)); // pre-0.1.8 claim, inside the legacy cutoff

        var db = new Database(factory.openSession());
        JobPoller.SweepResult result;
        try {
            db.beginTransaction();
            var now = Instant.now();
            result = JobPoller.sweepOrphans(db, now.minus(JobPoller.WORKER_DEAD_AFTER),
                now.minus(JobPoller.LEGACY_CLAIM_CUTOFF));
            db.commitTransaction();
        } finally {
            db.close();
        }

        assertEquals(1, result.reclaimed());
        assertEquals("live", claimedBy(live));
        assertNull(claimedBy(dead));
        assertEquals(1L, count("SELECT COUNT(*) FROM scheduled_jobs WHERE id = " + legacy + " AND started_at IS NOT NULL"));
        assertEquals(0L, count("SELECT COUNT(*) FROM brace_job_workers WHERE id = 'dead'"), "dead worker pruned");
    }

    @Test
    void liveWorkerRecoversACrashedWorkersJob() throws Exception {
        long id = schedule();
        var crashed = new JobPoller(); // claims, never heartbeats
        var crashedBatch = crashed.dispatch(factory);
        awaitUntil(() -> BlockingJob.started.get() == 1);

        var live = new JobPoller();
        live.pollInterval(Duration.ofMillis(100));
        live.heartbeatTiming(Duration.ofMillis(50), Duration.ofMillis(800), Duration.ofMillis(100), Duration.ofMinutes(1));
        live.start(factory);
        try {
            awaitUntil(() -> BlockingJob.started.get() == 2);
            assertEquals(live.workerId(), claimedBy(id));

            BlockingJob.gate.countDown();
            for (var t : crashedBatch.threads()) t.join();
            awaitUntil(() -> count("SELECT COUNT(*) FROM scheduled_jobs WHERE completed_at IS NOT NULL") == 1);
            assertEquals(live.workerId(), claimedBy(id));
        } finally {
            live.stop();
        }
    }

    @Test
    void shutdownReleasesClaimsWithTheAttemptRefunded() throws Exception {
        long id = schedule();
        var poller = new JobPoller();
        poller.pollInterval(Duration.ofMillis(100));
        poller.shutdownTimeout(Duration.ofMillis(200));
        poller.start(factory);
        awaitUntil(() -> BlockingJob.started.get() == 1);

        poller.stop();

        assertEquals(1L, count("SELECT COUNT(*) FROM scheduled_jobs WHERE id = " + id
            + " AND started_at IS NULL AND claimed_by IS NULL AND attempts = 0"));
        assertEquals(0L, count("SELECT COUNT(*) FROM brace_job_workers"));
    }

    // --- Helpers ----------------------------------------------------------------------------

    private static long schedule() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            long id = Jobs.schedule(db, new BlockingJob(), Duration.ZERO);
            db.commitTransaction();
            return id;
        } finally {
            db.close();
        }
    }

    private static Timestamp ago(Duration d) {
        return Timestamp.from(Instant.now().minus(d));
    }

    private static void claim(long id, String owner, Duration age) throws Exception {
        exec("UPDATE scheduled_jobs SET started_at = ?, attempts = 1, claimed_by = ? WHERE id = ?",
            ago(age), owner, id);
    }

    private static void exec(String sql, Object... params) throws Exception {
        try (var conn = connect(); var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    private static String claimedBy(long id) throws Exception {
        try (var conn = connect(); var ps = conn.prepareStatement("SELECT claimed_by FROM scheduled_jobs WHERE id = ?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static long count(String sql) {
        try (var conn = connect(); var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("condition not met within 15s");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }
}
