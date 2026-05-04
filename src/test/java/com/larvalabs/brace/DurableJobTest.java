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
}
