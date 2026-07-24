package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DurableJobTest {

    static DatabaseFactory factory;

    public static class TestJob implements DurableJob {
        static final AtomicInteger runCount = new AtomicInteger(0);
        static final List<String> dataReceived = Collections.synchronizedList(new ArrayList<>());

        private final String payload;
        public TestJob() { this.payload = null; }
        public TestJob(String payload) { this.payload = payload; }

        @Override public String data() { return payload; }
        @Override public void run(String data, Database db) {
            dataReceived.add(data);
            runCount.incrementAndGet();
        }
    }

    public static class FailingJob implements DurableJob {
        static final AtomicInteger attempts = new AtomicInteger(0);

        public FailingJob() {}
        @Override public String data() { return null; }
        @Override public void run(String data, Database db) throws Exception {
            attempts.incrementAndGet();
            throw new RuntimeException("intentional failure");
        }
    }

    /**
     * A class that does NOT implement DurableJob — used to test that the
     * JobPoller rejects it with a clear error before instantiation.
     */
    public static class NotADurableJob {
        public NotADurableJob() {}
    }

    /** Blocks until {@link #gate} is opened — simulates a slow job for the H4 dispatch tests. */
    public static class GatedJob implements DurableJob {
        static volatile java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(0);
        public GatedJob() {}
        @Override public String data() { return null; }
        @Override public void run(String data, Database db) throws Exception {
            gate.await();
        }
    }

    @BeforeAll
    static void setup() {
        factory = new DatabaseFactory(
            "jdbc:h2:mem:durabletest;DB_CLOSE_DELAY=-1", null, null,
            List.of(Post.class));
    }

    @AfterAll
    static void teardown() {
        factory.close();
    }

    @BeforeEach
    void resetCounters() {
        TestJob.runCount.set(0);
        TestJob.dataReceived.clear();
        FailingJob.attempts.set(0);

        // Clean up scheduled_jobs table
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            db.sql("DELETE FROM scheduled_jobs");
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void scheduleAndExecuteJob() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            long id = Jobs.schedule(db, new TestJob("hello"), Duration.ZERO);
            db.commitTransaction();
            assertTrue(id > 0, "Should return a positive job ID");
        } finally {
            db.close();
        }

        var poller = new JobPoller();
        poller.pollAndExecute(factory);

        assertEquals(1, TestJob.runCount.get());
        assertEquals(1, TestJob.dataReceived.size());
        assertEquals("hello", TestJob.dataReceived.get(0));

        // Verify job is marked completed
        var db2 = new Database(factory.openSession());
        try {
            db2.beginTransaction();
            var stats = JobPoller.getDurableJobStats(db2);
            assertEquals(0, stats.pending());
            assertEquals(0, stats.running());
            assertEquals(1, stats.completed());
            assertEquals(0, stats.failed());
            db2.commitTransaction();
        } finally {
            db2.close();
        }
    }

    @Test
    void jobWithDelayDoesNotRunEarly() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            Jobs.schedule(db, new TestJob("delayed"), Duration.ofHours(1));
            db.commitTransaction();
        } finally {
            db.close();
        }

        var poller = new JobPoller();
        poller.pollAndExecute(factory);

        assertEquals(0, TestJob.runCount.get(), "Job with future run_at should not execute yet");
    }

    @Test
    void failingJobRetriesUpToMaxAttempts() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            // Use zero backoff and 3 max attempts
            // We need to set run_at to now so retries are immediately eligible
            Jobs.schedule(db, new FailingJob(), Duration.ZERO, JobOptions.maxAttempts(3).backoff(Duration.ZERO));
            db.commitTransaction();
        } finally {
            db.close();
        }

        var poller = new JobPoller();

        // First attempt
        poller.pollAndExecute(factory);
        assertEquals(1, FailingJob.attempts.get());

        // Second attempt — run_at was pushed forward with zero backoff, so should be eligible
        poller.pollAndExecute(factory);
        assertEquals(2, FailingJob.attempts.get());

        // Third attempt — should fail permanently
        poller.pollAndExecute(factory);
        assertEquals(3, FailingJob.attempts.get());

        // No more attempts
        poller.pollAndExecute(factory);
        assertEquals(3, FailingJob.attempts.get(), "Should not exceed max attempts");

        // Verify job is marked failed
        var db2 = new Database(factory.openSession());
        try {
            db2.beginTransaction();
            var stats = JobPoller.getDurableJobStats(db2);
            assertEquals(0, stats.pending());
            assertEquals(1, stats.failed());
            db2.commitTransaction();
        } finally {
            db2.close();
        }
    }

    @Test
    void jobDependencyWaitsForCompletion() {
        var db = new Database(factory.openSession());
        long jobAId;
        try {
            db.beginTransaction();
            jobAId = Jobs.schedule(db, new TestJob("A"), Duration.ZERO);
            Jobs.schedule(db, new TestJob("B"), Duration.ZERO, JobOptions.after(jobAId));
            db.commitTransaction();
        } finally {
            db.close();
        }

        var poller = new JobPoller();

        // First poll: only A should run (B depends on A)
        poller.pollAndExecute(factory);
        assertEquals(1, TestJob.runCount.get());
        assertEquals("A", TestJob.dataReceived.get(0));

        // Second poll: A is completed, B should now run
        poller.pollAndExecute(factory);
        assertEquals(2, TestJob.runCount.get());
        assertEquals("B", TestJob.dataReceived.get(1));
    }

    @Test
    void scheduleReturnsUniqueSequentialIds() {
        var db = new Database(factory.openSession());
        long id1, id2;
        try {
            db.beginTransaction();
            id1 = Jobs.schedule(db, new TestJob("first"), Duration.ZERO);
            id2 = Jobs.schedule(db, new TestJob("second"), Duration.ZERO);
            db.commitTransaction();
        } finally {
            db.close();
        }

        assertTrue(id1 > 0, "First job ID should be positive");
        assertTrue(id2 > 0, "Second job ID should be positive");
        assertTrue(id2 > id1, "Second job ID should be greater than first");
    }

    @Test
    void parallelExecution() {
        var items = List.of("a", "b", "c", "d", "e");
        var results = Collections.synchronizedList(new ArrayList<String>());

        Jobs.parallel(items, 2, item -> {
            results.add(item);
        });

        assertEquals(5, results.size());
        assertTrue(results.containsAll(items));
    }

    @Test
    void durableJobStats() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            Jobs.schedule(db, new TestJob("s1"), Duration.ZERO);
            Jobs.schedule(db, new TestJob("s2"), Duration.ofHours(1));
            db.commitTransaction();
        } finally {
            db.close();
        }

        var db2 = new Database(factory.openSession());
        try {
            db2.beginTransaction();
            var stats = JobPoller.getDurableJobStats(db2);
            assertEquals(2, stats.pending());
            assertEquals(0, stats.completed());
            assertEquals(0, stats.failed());
            db2.commitTransaction();
        } finally {
            db2.close();
        }
    }

    @Test
    void claimsSizedToCapacityAndSlowJobsDontStallNewBatches() throws Exception {
        // Pool 10 → execution capacity poolSize/2 = 5. Six ready jobs with distinct run_at
        // ordering: four slow (gated) jobs first, then two fast ones.
        GatedJob.gate = new java.util.concurrent.CountDownLatch(1);
        long now = System.currentTimeMillis();
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            long[] ids = new long[6];
            for (int i = 0; i < 4; i++) {
                ids[i] = Jobs.schedule(db, new GatedJob(), Duration.ZERO);
            }
            ids[4] = Jobs.schedule(db, new TestJob("fast1"), Duration.ZERO);
            ids[5] = Jobs.schedule(db, new TestJob("fast2"), Duration.ZERO);
            for (int i = 0; i < 6; i++) {
                // Force a deterministic claim order regardless of schedule-time timestamp ties
                db.sql("UPDATE scheduled_jobs SET run_at = ? WHERE id = ?",
                    new java.sql.Timestamp(now - 60_000 + i * 1_000), ids[i]);
            }
            db.commitTransaction();
        } finally {
            db.close();
        }

        var poller = new JobPoller();
        try {
            // First batch: capacity-limited to 5 (4 gated + fast1), not the old fixed 50.
            var first = poller.dispatch(factory);
            assertEquals(5, first.claimed(), "claim batch must be sized to execution capacity");

            // Second dispatch blocks only until ONE slot frees (fast1 finishing), not until the
            // whole first batch is done — the gated jobs are still holding their slots.
            var second = poller.dispatch(factory);
            assertEquals(1, second.claimed(), "a freed slot admits a new claim while slow jobs run");
            for (var t : second.threads()) {
                t.join();
            }
            // fast2 completed while the four gated jobs are still blocked: no head-of-line stall.
            assertEquals(2, TestJob.runCount.get(), "both fast jobs done despite running slow jobs");
        } finally {
            GatedJob.gate.countDown();
        }

        // Drain: wait for the gated jobs, then verify everything completed exactly once.
        int settled = 0;
        while (settled < 50) {
            var db2 = new Database(factory.openSession());
            try {
                db2.beginTransaction();
                var stats = JobPoller.getDurableJobStats(db2);
                db2.commitTransaction();
                if (stats.completed() == 6 && stats.running() == 0) {
                    return;
                }
            } finally {
                db2.close();
            }
            settled++;
            Thread.sleep(100);
        }
        fail("gated jobs did not complete after release");
    }

    @Test
    void purgeDeletesOldFinishedJobsButKeepsReferencedParents() {
        long oldDoneId, parentId, recentId;
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            oldDoneId = Jobs.schedule(db, new TestJob("old"), Duration.ZERO);
            parentId = Jobs.schedule(db, new TestJob("parent"), Duration.ZERO);
            recentId = Jobs.schedule(db, new TestJob("recent"), Duration.ZERO);
            db.commitTransaction();
        } finally {
            db.close();
        }

        new JobPoller().pollAndExecute(factory);
        assertEquals(3, TestJob.runCount.get());

        var tenDaysAgo = java.sql.Timestamp.from(
            java.time.Instant.now().minus(Duration.ofDays(10)));
        var db2 = new Database(factory.openSession());
        try {
            db2.beginTransaction();
            // A pending child referencing parent: the purge must keep the parent despite its age.
            Jobs.schedule(db2, new TestJob("child"), Duration.ofHours(1), JobOptions.after(parentId));
            // Age two completed jobs past the cutoff; "recent" keeps its real completed_at.
            db2.sql("UPDATE scheduled_jobs SET completed_at = ? WHERE id = ?", tenDaysAgo, oldDoneId);
            db2.sql("UPDATE scheduled_jobs SET completed_at = ? WHERE id = ?", tenDaysAgo, parentId);
            // An old permanently-failed job: purged like completed ones.
            db2.sql(
                "INSERT INTO scheduled_jobs (name, job_class, run_at, failed_at, max_attempts, backoff_seconds) " +
                "VALUES (?, ?, ?, ?, 1, 60)",
                "old-failed", FailingJob.class.getName(), tenDaysAgo, tenDaysAgo);
            db2.commitTransaction();
        } finally {
            db2.close();
        }

        var db3 = new Database(factory.openSession());
        try {
            db3.beginTransaction();
            int deleted = JobPoller.purgeFinishedJobs(db3, java.time.Instant.now().minus(Duration.ofDays(7)));
            db3.commitTransaction();
            assertEquals(2, deleted, "old completed + old failed purged; referenced parent and recent kept");

            db3.beginTransaction();
            var stats = JobPoller.getDurableJobStats(db3);
            db3.commitTransaction();
            assertEquals(1, stats.pending(), "child still pending");
            assertEquals(2, stats.completed(), "parent (referenced) and recent retained");
            assertEquals(0, stats.failed(), "old failed job purged");
            assertNull(db3.sqlQueryLong("SELECT id FROM scheduled_jobs WHERE id = " + oldDoneId));
            assertNotNull(db3.sqlQueryLong("SELECT id FROM scheduled_jobs WHERE id = " + parentId));
            assertNotNull(db3.sqlQueryLong("SELECT id FROM scheduled_jobs WHERE id = " + recentId));
        } finally {
            db3.close();
        }
    }

    @Test
    void jobWithNonDurableJobClassIsFailed() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            // Manually insert a row with a non-DurableJob class name
            db.sql(
                "INSERT INTO scheduled_jobs (name, job_class, job_data, run_at, max_attempts, backoff_seconds) " +
                "VALUES (?, ?, ?, CURRENT_TIMESTAMP, 1, 60)",
                "bad-job", NotADurableJob.class.getName(), null
            );
            db.commitTransaction();
        } finally {
            db.close();
        }

        var poller = new JobPoller();
        // This should mark the job as failed because the class does not implement DurableJob
        poller.pollAndExecute(factory);

        // Verify job is marked failed
        var db2 = new Database(factory.openSession());
        try {
            db2.beginTransaction();
            var stats = JobPoller.getDurableJobStats(db2);
            assertEquals(1, stats.failed(), "Job with non-DurableJob class should be marked failed");
            assertEquals(0, stats.pending());
            assertEquals(0, stats.completed());
            db2.commitTransaction();
        } finally {
            db2.close();
        }
    }

    // --- Stalled-claim recovery -------------------------------------------------------------
    //
    // A row claimed by an instance that died mid-run keeps started_at set with both terminal
    // timestamps NULL. No claim predicate matches it and purgeFinishedJobs never deletes it, so
    // before JobPoller.reclaimStalledJobs it was invisible and undeletable forever.

    private static long scheduleJob(DurableJob job, JobOptions options) {
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

    /** Reproduce a row stranded by a killed instance: claimed {@code age} ago, attempts spent,
     *  no terminal mark ever written. */
    private static void strandClaim(long jobId, Duration age, int attempts) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            db.sql("UPDATE scheduled_jobs SET started_at = ?, attempts = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(age)), attempts, jobId);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    /** {started_at, completed_at, failed_at, attempts, error} for one job. */
    private static Object[] jobRow(long id) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var rows = db.sqlQuery(
                "SELECT started_at, completed_at, failed_at, attempts, error FROM scheduled_jobs WHERE id = ?", id);
            db.commitTransaction();
            return rows.get(0);
        } finally {
            db.close();
        }
    }

    private static JobPoller.SweepResult sweep(Duration lease) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var result = JobPoller.reclaimStalledJobs(db, java.time.Instant.now().minus(lease));
            db.commitTransaction();
            return result;
        } finally {
            db.close();
        }
    }

    @Test
    void stalledClaimIsReclaimedAndRunsAgain() {
        long id = scheduleJob(new TestJob("stranded"), new JobOptions());
        strandClaim(id, Duration.ofMinutes(30), 1);

        // Stranded: the claim query cannot see it, so it would never run again.
        assertEquals(0, new JobPoller().pollAndExecute(factory));
        assertEquals(0, TestJob.runCount.get());

        var swept = sweep(Duration.ofMinutes(15));
        assertEquals(1, swept.reclaimed());
        assertEquals(0, swept.failed());
        assertNull(jobRow(id)[0], "started_at cleared, so the row re-enters the normal claim path");

        assertEquals(1, new JobPoller().pollAndExecute(factory));
        assertEquals(1, TestJob.runCount.get());
    }

    @Test
    void stalledClaimKeepsSpentAttemptsSoItCannotLoopForever() {
        long id = scheduleJob(new TestJob("repeat-stranded"), new JobOptions());
        strandClaim(id, Duration.ofMinutes(30), 1);

        sweep(Duration.ofMinutes(15));
        // The claim already spent attempt 1; reclaiming must not refund it, or a job that strands
        // every time would be reclaimed forever.
        assertEquals(1, ((Number) jobRow(id)[3]).intValue());
    }

    @Test
    void stalledClaimWithExhaustedAttemptsFailsInsteadOfReclaiming() {
        long id = scheduleJob(new TestJob("doomed"), new JobOptions());
        strandClaim(id, Duration.ofMinutes(30), 3); // default max_attempts is 3

        var swept = sweep(Duration.ofMinutes(15));
        assertEquals(0, swept.reclaimed());
        assertEquals(1, swept.failed());

        var row = jobRow(id);
        assertNotNull(row[2], "failed_at set, so the row is terminal and purgeable");
        assertTrue(((String) row[4]).contains("attempts exhausted"));

        // Clearing the lease alone would have stranded it a second way: claimable-shaped but
        // excluded by attempts < max_attempts.
        assertEquals(0, new JobPoller().pollAndExecute(factory));
    }

    @Test
    void sweepLeavesUnexpiredClaimsAlone() {
        long id = scheduleJob(new TestJob("still-running"), new JobOptions());
        strandClaim(id, Duration.ofMinutes(1), 1);

        var swept = sweep(Duration.ofMinutes(15));
        assertEquals(0, swept.reclaimed());
        assertEquals(0, swept.failed());
        assertNotNull(jobRow(id)[0], "a job inside its lease keeps its claim");
    }

    @Test
    void sweepLeavesFinishedJobsAlone() {
        long id = scheduleJob(new TestJob("finished"), new JobOptions());
        assertEquals(1, new JobPoller().pollAndExecute(factory));
        assertNotNull(jobRow(id)[1]);

        var swept = sweep(Duration.ofMinutes(0)); // cutoff = now, so every started_at qualifies on age
        assertEquals(0, swept.reclaimed());
        assertEquals(0, swept.failed());
        assertNotNull(jobRow(id)[1], "completed_at untouched");
    }

    @Test
    void jobLeaseAcceptsIntervalStrings() {
        assertEquals(Duration.ofMinutes(15), Brace.app().jobLease("15m").jobLease());
        assertEquals(Duration.ofHours(2), Brace.app().jobLease("2h").jobLease());
        assertEquals(Duration.ofSeconds(90), Brace.app().jobLease("90s").jobLease());
        assertEquals(Duration.ofMinutes(30), Brace.app().jobLease(), "default when never set");
    }

    @Test
    void jobLeaseStringDisablesOnlyOnExplicitZero() {
        assertNull(Brace.app().jobLease("0s").jobLease());

        // A missing config key must not silently strand jobs — config.get("jobs.lease") with no
        // default returns null, and that has to leave the default in place rather than disable.
        assertEquals(Duration.ofMinutes(30), Brace.app().jobLease((String) null).jobLease());
        assertEquals(Duration.ofMinutes(30), Brace.app().jobLease("   ").jobLease());

        // Disabling deliberately still works through the Duration overload.
        assertNull(Brace.app().jobLease((Duration) null).jobLease());
    }

    @Test
    void jobLeaseRejectsMalformedIntervals() {
        assertThrows(IllegalArgumentException.class, () -> Brace.app().jobLease("15"));
        assertThrows(IllegalArgumentException.class, () -> Brace.app().jobLease("15d"));
        assertThrows(NumberFormatException.class, () -> Brace.app().jobLease("abcm"));
    }

    @Test
    void reclaimingStrandedParentUnblocksItsDependents() {
        long parent = scheduleJob(new TestJob("parent"), new JobOptions());
        scheduleJob(new TestJob("child"), JobOptions.after(parent));
        strandClaim(parent, Duration.ofMinutes(30), 1);

        // The child is blocked behind a parent that will never complete — one killed job strands
        // its whole dependent subtree.
        assertEquals(0, new JobPoller().pollAndExecute(factory));

        sweep(Duration.ofMinutes(15));

        assertEquals(1, new JobPoller().pollAndExecute(factory)); // parent
        assertEquals(1, new JobPoller().pollAndExecute(factory)); // child, now unblocked
        assertEquals(2, TestJob.runCount.get());
    }
}
