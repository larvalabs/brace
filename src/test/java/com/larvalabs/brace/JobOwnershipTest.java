package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Durable-job ownership (0.1.8): who holds a claim, how a dead owner's jobs are recovered, and
 * what graceful shutdown and the optional timeout do. See {@link JobPoller}'s class Javadoc for
 * the model these tests pin down.
 */
class JobOwnershipTest {

    static DatabaseFactory factory;

    public static class CountingJob implements DurableJob {
        static final AtomicInteger runs = new AtomicInteger();
        public CountingJob() {}
        @Override public String data() { return null; }
        @Override public void run(String data, Database db) { runs.incrementAndGet(); }
    }

    /** Blocks on {@link #gate} until released or interrupted. */
    public static class BlockingJob implements DurableJob {
        static final AtomicInteger started = new AtomicInteger();
        static final AtomicInteger interrupted = new AtomicInteger();
        static volatile CountDownLatch gate = new CountDownLatch(1);
        public BlockingJob() {}
        @Override public String data() { return null; }
        @Override public void run(String data, Database db) throws Exception {
            started.incrementAndGet();
            try {
                gate.await();
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                throw e;
            }
        }
    }

    @BeforeAll
    static void setup() {
        factory = new DatabaseFactory("jdbc:h2:mem:jobownership;DB_CLOSE_DELAY=-1", null, null, List.of());
    }

    @AfterAll
    static void teardown() {
        factory.close();
    }

    @BeforeEach
    void reset() {
        CountingJob.runs.set(0);
        BlockingJob.started.set(0);
        BlockingJob.interrupted.set(0);
        BlockingJob.gate = new CountDownLatch(1);
        exec("DELETE FROM scheduled_jobs");
        exec("DELETE FROM brace_job_workers");
    }

    @AfterEach
    void openGate() {
        BlockingJob.gate.countDown(); // never leave a job thread parked across tests
    }

    // --- Claims record their owner ----------------------------------------------------------

    @Test
    void claimRecordsTheOwningWorker() throws Exception {
        long id = schedule(new BlockingJob(), new JobOptions());
        var poller = new JobPoller();
        var batch = poller.dispatch(factory);
        awaitUntil(() -> BlockingJob.started.get() == 1);

        assertEquals(poller.workerId(), row(id).claimedBy());

        BlockingJob.gate.countDown();
        for (var t : batch.threads()) t.join();
        assertNotNull(row(id).completedAt());
    }

    @Test
    void staleCopyCannotOverwriteTheRunThatReplacedIt() throws Exception {
        long id = schedule(new BlockingJob(), new JobOptions());
        var poller = new JobPoller();
        var batch = poller.dispatch(factory);
        awaitUntil(() -> BlockingJob.started.get() == 1);

        // While it runs, the claim passes to another worker — what the sweep does when this
        // instance is presumed dead.
        exec("UPDATE scheduled_jobs SET claimed_by = 'replacement' WHERE id = " + id);

        BlockingJob.gate.countDown();
        for (var t : batch.threads()) t.join();

        var r = row(id);
        assertNull(r.completedAt(), "the stale copy's completion must not land on a row it no longer owns");
        assertEquals("replacement", r.claimedBy());
    }

    // --- Dead-owner sweep -------------------------------------------------------------------

    @Test
    void jobOwnedByAMissingWorkerIsRecoveredAndRunsAgain() {
        long id = schedule(new CountingJob(), new JobOptions());
        claim(id, "vanished-worker", Duration.ofMinutes(1), 1);

        // Stranded: no claim predicate can see it.
        assertEquals(0, new JobPoller().pollAndExecute(factory));

        var swept = sweep();
        assertEquals(1, swept.reclaimed());
        assertEquals(0, swept.failed());
        var r = row(id);
        assertNull(r.startedAt());
        assertNull(r.claimedBy());

        assertEquals(1, new JobPoller().pollAndExecute(factory));
        assertEquals(1, CountingJob.runs.get());
    }

    @Test
    void jobOwnedByAStaleWorkerIsRecoveredAndTheWorkerPruned() {
        long id = schedule(new CountingJob(), new JobOptions());
        worker("stale-worker", Duration.ofMinutes(5));
        claim(id, "stale-worker", Duration.ofMinutes(10), 1);

        assertEquals(1, sweep().reclaimed());
        assertEquals(0, count("SELECT COUNT(*) FROM brace_job_workers WHERE id = 'stale-worker'"));
    }

    @Test
    void jobOwnedByALiveWorkerIsNeverRecoveredHoweverLongItRuns() {
        long id = schedule(new CountingJob(), new JobOptions());
        worker("live-worker", Duration.ofSeconds(5));
        claim(id, "live-worker", Duration.ofHours(10), 1);

        var swept = sweep();
        assertEquals(0, swept.reclaimed());
        assertEquals(0, swept.failed());
        assertEquals("live-worker", row(id).claimedBy(), "a slow job on a live instance keeps its claim");
        assertEquals(1, count("SELECT COUNT(*) FROM brace_job_workers WHERE id = 'live-worker'"));
    }

    @Test
    void recoveryDoesNotRefundTheAttempt() {
        long id = schedule(new CountingJob(), new JobOptions());
        claim(id, "vanished-worker", Duration.ofMinutes(1), 1);

        sweep();
        // The job may be what killed its instance; the spent attempt is what stops a poison job
        // from taking down instance after instance.
        assertEquals(1, row(id).attempts());
    }

    @Test
    void recoveryWithAttemptsExhaustedFailsTheJob() {
        long id = schedule(new CountingJob(), new JobOptions());
        claim(id, "vanished-worker", Duration.ofMinutes(1), 3); // default max_attempts is 3

        var swept = sweep();
        assertEquals(0, swept.reclaimed());
        assertEquals(1, swept.failed());
        var r = row(id);
        assertNotNull(r.failedAt(), "terminal, so it is prunable");
        assertTrue(r.error().contains("attempts exhausted"), r.error());
    }

    @Test
    void unownedLegacyClaimIsRecoveredOnlyAfterTheLegacyCutoff() {
        // Claimed by a pre-0.1.8 instance (no claimed_by). During a rolling upgrade that instance
        // may still be running it, so an hour-old claim is left alone...
        long recent = schedule(new CountingJob(), new JobOptions());
        claim(recent, null, Duration.ofHours(1), 1);
        // ...but a claim older than any rolling deploy is recovered.
        long ancient = schedule(new CountingJob(), new JobOptions());
        claim(ancient, null, Duration.ofHours(25), 1);

        assertEquals(1, sweep().reclaimed());
        assertNotNull(row(recent).startedAt());
        assertNull(row(ancient).startedAt());
    }

    @Test
    void sweepLeavesFinishedJobsAlone() {
        long id = schedule(new CountingJob(), new JobOptions());
        assertEquals(1, new JobPoller().pollAndExecute(factory)); // owner never registered: "dead"

        var swept = sweep();
        assertEquals(0, swept.reclaimed());
        assertEquals(0, swept.failed());
        assertNotNull(row(id).completedAt());
    }

    @Test
    void recoveringAStrandedParentUnblocksItsDependents() {
        long parent = schedule(new CountingJob(), new JobOptions());
        schedule(new CountingJob(), JobOptions.after(parent));
        claim(parent, "vanished-worker", Duration.ofMinutes(1), 1);

        assertEquals(0, new JobPoller().pollAndExecute(factory), "child blocked behind a dead parent");

        sweep();

        assertEquals(1, new JobPoller().pollAndExecute(factory)); // parent
        assertEquals(1, new JobPoller().pollAndExecute(factory)); // child, now unblocked
        assertEquals(2, CountingJob.runs.get());
    }

    // --- Live heartbeat ---------------------------------------------------------------------

    @Test
    void runningPollerRegistersHeartbeatsAndDeregistersOnStop() {
        var poller = fastPoller();
        poller.instanceLabel("web-1:8080-abcd");
        poller.start(factory);
        try {
            assertEquals(1, count("SELECT COUNT(*) FROM brace_job_workers WHERE id = '" + poller.workerId()
                + "' AND instance_id = 'web-1:8080-abcd'"));

            // A sweep elsewhere pruned it (this instance looked dead): the next heartbeat re-registers.
            exec("DELETE FROM brace_job_workers");
            awaitUntil(() -> count("SELECT COUNT(*) FROM brace_job_workers") == 1);
        } finally {
            poller.stop();
        }
        assertEquals(0, count("SELECT COUNT(*) FROM brace_job_workers"));
    }

    @Test
    void crashedWorkersJobIsRecoveredByALiveOneAndTheStaleCopyLosesItsClaim() throws Exception {
        long id = schedule(new BlockingJob(), new JobOptions());

        // Worker A claims and starts the job, but never heartbeats — as if its process had died
        // right after the claim. (Here the thread lives on, which is the worst case: a "dead"
        // instance that later wakes up and tries to record its outcome.)
        var crashed = new JobPoller();
        var crashedBatch = crashed.dispatch(factory);
        awaitUntil(() -> BlockingJob.started.get() == 1);

        var live = fastPoller();
        live.start(factory);
        try {
            // Live worker's sweep sees A has no heartbeat, recovers the job, and runs it itself.
            awaitUntil(() -> BlockingJob.started.get() == 2);
            assertEquals(live.workerId(), row(id).claimedBy());

            BlockingJob.gate.countDown();
            for (var t : crashedBatch.threads()) t.join();
            awaitUntil(() -> row(id).completedAt() != null);
            assertEquals(live.workerId(), row(id).claimedBy(), "the recovered run owns the outcome");
        } finally {
            live.stop();
        }
    }

    @Test
    void ownRowWhoseTerminalWriteFailedIsReleased() {
        var poller = fastPoller();
        poller.start(factory);
        try {
            // Owned by this live worker but not running here — its outcome was never written.
            long id = schedule(new CountingJob(), new JobOptions());
            claim(id, poller.workerId(), Duration.ofMinutes(5), 1);

            poller.releaseOwnOrphans();
            // Back in the queue; this same poller picks it up.
            awaitUntil(() -> row(id).completedAt() != null);
            assertEquals(1, CountingJob.runs.get());
        } finally {
            poller.stop();
        }
    }

    @Test
    void ownRowThatIsStillRunningIsNotReleased() {
        var poller = fastPoller();
        poller.start(factory);
        try {
            long id = schedule(new BlockingJob(), new JobOptions());
            awaitUntil(() -> BlockingJob.started.get() == 1);

            poller.releaseOwnOrphans(); // grace is zero in fastPoller: only the in-flight set protects it
            var r = row(id);
            assertNotNull(r.startedAt());
            assertEquals(poller.workerId(), r.claimedBy());
            assertEquals(1, BlockingJob.started.get());
        } finally {
            BlockingJob.gate.countDown();
            poller.stop();
        }
    }

    // --- Timeout ----------------------------------------------------------------------------

    @Test
    void timeoutIsOffByDefault() {
        assertNull(Brace.app().jobTimeout());
    }

    @Test
    void jobExceedingTheTimeoutIsInterruptedAndTheAttemptFails() {
        var poller = fastPoller();
        poller.timeout(Duration.ofMillis(300));
        poller.start(factory);
        try {
            long id = schedule(new BlockingJob(), JobOptions.maxAttempts(1));
            awaitUntil(() -> row(id).failedAt() != null);

            assertEquals(1, BlockingJob.interrupted.get());
            assertTrue(row(id).error().startsWith("Timed out after 300ms"), row(id).error());
            assertEquals(1, BlockingJob.started.get(), "never handed to a second runner");
        } finally {
            poller.stop();
        }
    }

    @Test
    void timedOutJobWithAttemptsLeftRetriesUnderBackoff() {
        var poller = fastPoller();
        poller.timeout(Duration.ofMillis(300));
        poller.start(factory);
        try {
            long id = schedule(new BlockingJob(), JobOptions.maxAttempts(3).backoff(Duration.ofHours(1)));
            awaitUntil(() -> row(id).error() != null);

            var r = row(id);
            assertNull(r.startedAt(), "released for retry");
            assertNull(r.failedAt());
            assertEquals(1, r.attempts());
            assertTrue(r.error().startsWith("Timed out"), r.error());
        } finally {
            poller.stop();
        }
    }

    @Test
    void jobTimeoutAcceptsIntervalStrings() {
        assertEquals(Duration.ofHours(2), Brace.app().jobTimeout("2h").jobTimeout());
        assertEquals(Duration.ofSeconds(90), Brace.app().jobTimeout("90s").jobTimeout());
        assertNull(Brace.app().jobTimeout("2h").jobTimeout("0s").jobTimeout(), "0s turns it off");
        assertNull(Brace.app().jobTimeout(Duration.ZERO).jobTimeout());
        // A missing config key leaves the setting alone.
        assertEquals(Duration.ofHours(2), Brace.app().jobTimeout("2h").jobTimeout((String) null).jobTimeout());
        assertNull(Brace.app().jobTimeout("  ").jobTimeout());
        assertThrows(IllegalArgumentException.class, () -> Brace.app().jobTimeout("15"));
        assertThrows(IllegalArgumentException.class, () -> Brace.app().jobTimeout("15d"));
    }

    // --- Graceful shutdown ------------------------------------------------------------------

    @Test
    void shutdownLetsAJobThatFinishesInTimeComplete() throws Exception {
        var poller = fastPoller();
        poller.shutdownTimeout(Duration.ofSeconds(10));
        poller.start(factory);
        long id = schedule(new BlockingJob(), new JobOptions());
        awaitUntil(() -> BlockingJob.started.get() == 1);

        var stopper = Thread.startVirtualThread(poller::stop);
        Thread.sleep(200);
        BlockingJob.gate.countDown();
        stopper.join();

        var r = row(id);
        assertNotNull(r.completedAt());
        assertEquals(0, BlockingJob.interrupted.get());
    }

    @Test
    void shutdownReleasesAnUnfinishedJobWithTheAttemptRefunded() {
        var poller = fastPoller();
        poller.shutdownTimeout(Duration.ofMillis(200));
        poller.start(factory);
        long id = schedule(new BlockingJob(), new JobOptions());
        awaitUntil(() -> BlockingJob.started.get() == 1);

        poller.stop();

        assertEquals(1, BlockingJob.interrupted.get());
        var r = row(id);
        assertNull(r.startedAt(), "back in the queue");
        assertNull(r.claimedBy());
        assertNull(r.failedAt());
        assertEquals(0, r.attempts(), "an interrupted job did not fail — the attempt is refunded");
        assertTrue(r.error().startsWith("Released"), r.error());
        assertEquals(0, count("SELECT COUNT(*) FROM brace_job_workers"));
    }

    @Test
    void braceShutdownHookStopsTheAppAndReleasesRunningJobs() throws Exception {
        var app = Brace.app().port(0).database(factory).jobShutdownTimeout(Duration.ofMillis(200));
        app.start();
        long id = schedule(new BlockingJob(), new JobOptions()); // wakes the app's poller
        awaitUntil(() -> BlockingJob.started.get() == 1);

        var hook = app.shutdownHook();
        assertNotNull(hook, "start() registers a JVM shutdown hook");
        hook.run(); // what SIGTERM does

        var r = row(id);
        assertNull(r.startedAt());
        assertEquals(0, r.attempts());
        app.stop(); // idempotent after the hook ran
    }

    @Test
    void explicitStopRemovesTheShutdownHook() throws Exception {
        var app = Brace.app().port(0).database(factory);
        app.start();
        var hook = app.shutdownHook();
        app.stop();
        assertNull(app.shutdownHook());
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "already deregistered");
    }

    @Test
    void jobShutdownTimeoutDefaultsToThreeSeconds() {
        assertEquals(Duration.ofSeconds(3), Brace.app().jobShutdownTimeout());
        assertEquals(Duration.ofSeconds(25), Brace.app().jobShutdownTimeout("25s").jobShutdownTimeout());
        assertEquals(Duration.ofSeconds(3), Brace.app().jobShutdownTimeout((String) null).jobShutdownTimeout());
        assertThrows(IllegalArgumentException.class, () -> Brace.app().jobShutdownTimeout(Duration.ofSeconds(-1)));
    }

    // --- Helpers ----------------------------------------------------------------------------

    /** A poller with heartbeat timing shrunk to milliseconds. */
    private static JobPoller fastPoller() {
        var poller = new JobPoller();
        poller.pollInterval(Duration.ofMillis(100));
        poller.heartbeatTiming(Duration.ofMillis(50), Duration.ofMillis(800), Duration.ofMillis(100), Duration.ZERO);
        return poller;
    }

    record Row(Timestamp startedAt, Timestamp completedAt, Timestamp failedAt, int attempts, String error,
               String claimedBy) {}

    private static Row row(long id) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var r = db.sqlQuery("SELECT started_at, completed_at, failed_at, attempts, error, claimed_by "
                + "FROM scheduled_jobs WHERE id = ?", id).get(0);
            db.commitTransaction();
            return new Row(ts(r[0]), ts(r[1]), ts(r[2]), ((Number) r[3]).intValue(), (String) r[4], (String) r[5]);
        } finally {
            db.close();
        }
    }

    private static Timestamp ts(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t;
        if (o instanceof java.time.OffsetDateTime odt) return Timestamp.from(odt.toInstant());
        if (o instanceof Instant i) return Timestamp.from(i);
        throw new IllegalStateException("unexpected timestamp type " + o.getClass());
    }

    private static long schedule(DurableJob job, JobOptions options) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            long id = Jobs.schedule(db, job, Duration.ZERO, options);
            db.commitTransaction();
            return id;
        } finally {
            db.close();
        }
    }

    /** Put a row into the claimed state: owned by {@code owner}, claimed {@code age} ago. */
    private static void claim(long id, String owner, Duration age, int attempts) {
        update("UPDATE scheduled_jobs SET started_at = ?, attempts = ?, claimed_by = ? WHERE id = ?",
            Timestamp.from(Instant.now().minus(age)), attempts, owner, id);
    }

    private static void worker(String id, Duration sinceHeartbeat) {
        update("INSERT INTO brace_job_workers (id, heartbeat_at) VALUES (?, ?)",
            id, Timestamp.from(Instant.now().minus(sinceHeartbeat)));
    }

    private static JobPoller.SweepResult sweep() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var now = Instant.now();
            var result = JobPoller.sweepOrphans(db, now.minus(JobPoller.WORKER_DEAD_AFTER),
                now.minus(JobPoller.LEGACY_CLAIM_CUTOFF));
            db.commitTransaction();
            return result;
        } finally {
            db.close();
        }
    }

    private static void exec(String sql) {
        update(sql);
    }

    private static void update(String sql, Object... params) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            db.jdbc(conn -> {
                try (var ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                    ps.executeUpdate();
                }
            });
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    private static long count(String sql) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            long n = db.jdbc(conn -> {
                try (var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
                    rs.next();
                    return rs.getLong(1);
                }
            });
            db.commitTransaction();
            return n;
        } finally {
            db.close();
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("condition not met within 10s");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }
}
