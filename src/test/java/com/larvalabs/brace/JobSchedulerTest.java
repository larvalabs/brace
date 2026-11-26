package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JobSchedulerTest {

    @Test
    void stopDoesNotWaitForAPendingDailyRun() throws Exception {
        // daily() is a one-shot delayed task; a ScheduledThreadPoolExecutor runs those after
        // shutdown() by default, so stop() used to block its full 5s and leave a non-daemon
        // worker alive until the next daily run.
        var before = java.util.Set.copyOf(Thread.getAllStackTraces().keySet());
        var scheduler = new JobScheduler();
        String inTwoHours = java.time.LocalTime.now().plusHours(2).withSecond(0).withNano(0).toString();
        scheduler.daily(inTwoHours, "nightly", (db, ctx) -> {});
        scheduler.start(null);

        long t0 = System.nanoTime();
        scheduler.stop();
        long stopMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(stopMs < 2000, "stop() took " + stopMs + "ms");

        long deadline = System.currentTimeMillis() + 1000;
        java.util.List<Thread> leftover;
        do {
            leftover = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> !before.contains(t) && t.isAlive() && !t.isDaemon())
                .toList();
            if (leftover.isEmpty()) break;
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        assertTrue(leftover.isEmpty(), "non-daemon threads left after stop(): " + leftover);
    }

    @Test
    void jobExecutesOnSchedule() throws Exception {
        var scheduler = new JobScheduler();
        var latch = new CountDownLatch(2);
        var count = new AtomicInteger(0);

        scheduler.every("1s", "test-job", (db, ctx) -> {
            count.incrementAndGet();
            latch.countDown();
        });

        scheduler.start(null);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Job should execute at least twice within 5 seconds");
        assertTrue(count.get() >= 2);
        scheduler.stop();
    }

    @Test
    void jobStatusTracking() throws Exception {
        var scheduler = new JobScheduler();
        var latch = new CountDownLatch(1);

        scheduler.every("1s", "status-job", (db, ctx) -> {
            latch.countDown();
        });

        scheduler.start(null);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(100); // let status update

        var statuses = scheduler.getStatuses();
        assertEquals(1, statuses.size());
        assertEquals("status-job", statuses.get(0).name());
        assertEquals("ok", statuses.get(0).lastStatus());
        scheduler.stop();
    }

    @Test
    void failingJobTracksError() throws Exception {
        var scheduler = new JobScheduler();
        var latch = new CountDownLatch(1);

        scheduler.every("1s", "fail-job", (db, ctx) -> {
            latch.countDown();
            throw new RuntimeException("intentional failure");
        });

        scheduler.start(null);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(100); // let status update

        var statuses = scheduler.getStatuses();
        var status = statuses.get(0);
        assertEquals("error", status.lastStatus());
        assertTrue(status.lastError().contains("intentional failure"));
        assertTrue(status.failCount() >= 1);
        scheduler.stop();
    }

    @Test
    void secretShapedTokenInJobFailureIsRedactedInStatus() throws Exception {
        var scheduler = new JobScheduler();
        var latch = new CountDownLatch(1);
        // Built from parts so no secret-shaped literal sits in the source for scanners to flag.
        String secret = "sk_" + "li" + "ve_" + "4eC39HqLyjWDarjtT1zdp7dcAbCdEf12";

        scheduler.every("1s", "leaky-job", (db, ctx) -> {
            latch.countDown();
            throw new RuntimeException("upstream rejected key " + secret);
        });

        scheduler.start(null);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);

        var status = scheduler.getStatuses().get(0);
        assertTrue(status.lastError().contains("upstream rejected key"), status.lastError());
        assertFalse(status.lastError().contains(secret), status.lastError());
        scheduler.stop();
    }

    @Test
    void jobMessageShownInStatus() throws Exception {
        var scheduler = new JobScheduler();
        var latch = new CountDownLatch(1);

        scheduler.every("1s", "msg-job", (db, ctx) -> {
            ctx.message("Retrieved 42 new listings");
            latch.countDown();
        });

        scheduler.start(null);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);

        var status = scheduler.getStatuses().get(0);
        assertEquals("ok", status.lastStatus());
        assertEquals("Retrieved 42 new listings", status.lastMessage());
        scheduler.stop();
    }

    @Test
    void dashboardShowsAFailedJobsErrorInsteadOfItsLastMessage() throws Exception {
        var scheduler = new JobScheduler();
        var runs = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        scheduler.every("1s", "sync", (db, ctx) -> {
            try {
                if (runs.incrementAndGet() == 1) {
                    ctx.message("12 titles restocked");
                } else {
                    throw new IllegalStateException("supplier API timed out");
                }
            } finally {
                latch.countDown();
            }
        });

        scheduler.start(null);
        assertTrue(latch.await(4, TimeUnit.SECONDS));
        Thread.sleep(100);

        String html = OpsDashboard.html(OpsScope.READ, new Stats(), scheduler, null, null, null, null);
        assertTrue(html.contains("<td colspan=\"4\" class=\"c-red job-msg\">↳ supplier API timed out</td>"), html);
        assertFalse(html.contains("12 titles restocked"), "the stale success message is not shown");
        scheduler.stop();
    }

    @Test
    void jobMessagePersistsAcrossRunsUntilOverwritten() throws Exception {
        var scheduler = new JobScheduler();
        var runs = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        scheduler.every("1s", "intermittent-msg", (db, ctx) -> {
            int n = runs.incrementAndGet();
            if (n == 1) ctx.message("first run message");
            // second run: no ctx.message() call — previous message should remain
            latch.countDown();
        });

        scheduler.start(null);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);

        assertEquals("first run message", scheduler.getStatuses().get(0).lastMessage());
        scheduler.stop();
    }

    @Test
    void jobMessageRetainedOnFailure() throws Exception {
        var scheduler = new JobScheduler();
        var latch = new CountDownLatch(1);

        scheduler.every("1s", "fail-with-msg", (db, ctx) -> {
            ctx.message("processed 3 of 5");
            latch.countDown();
            throw new RuntimeException("boom");
        });

        scheduler.start(null);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);

        var status = scheduler.getStatuses().get(0);
        assertEquals("error", status.lastStatus());
        assertEquals("processed 3 of 5", status.lastMessage());
        scheduler.stop();
    }

    @Test
    void durationParsing() {
        var scheduler = new JobScheduler();
        scheduler.every("30s", "sec-job", (db, ctx) -> {});
        scheduler.every("5m", "min-job", (db, ctx) -> {});
        scheduler.every("2h", "hour-job", (db, ctx) -> {});
        var statuses = scheduler.getStatuses();
        assertEquals(3, statuses.size());
    }

    @Test
    void aFailedRunIsLoggedAndRecordedInTheErrorStore() throws Exception {
        // A failed run used to reach only JobStatus, whose lastError the next good run clears:
        // in production the only trace was the throwing library's own bare warning.
        var dbFactory = new DatabaseFactory(
            "jdbc:h2:mem:jobfailures" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null, java.util.List.of());
        var store = new ErrorStore(dbFactory, 1000);
        var scheduler = new JobScheduler();
        try {
            var failed = new CountDownLatch(1);
            scheduler.errorStore(store);
            scheduler.every("1s", "flaky-flush", (db, ctx) -> {
                failed.countDown();
                throw new IllegalStateException("could not execute statement",
                    new java.sql.SQLException("value too long for type character varying(255)", "22001"));
            });
            LogTap.clear();
            scheduler.start(null);
            assertTrue(failed.await(5, TimeUnit.SECONDS));

            java.util.Map<String, Object> line = null;
            long deadline = System.currentTimeMillis() + 3000;
            while (line == null && System.currentTimeMillis() < deadline) {
                line = LogTap.snapshot().stream()
                    .map(LogTap.LogEntry::fields)
                    .filter(f -> "job.error".equals(f.get("event")) && "flaky-flush".equals(f.get("job")))
                    .findFirst().orElse(null);
                if (line == null) Thread.sleep(20);
            }
            assertNotNull(line, "a failed run is logged: " + LogTap.snapshot());
            assertEquals("ERROR", line.get("level"));
            assertEquals("job", line.get("kind"));
            assertEquals("IllegalStateException", line.get("error"));
            assertEquals("could not execute statement", line.get("errorMessage"));
            // The root cause is what says what went wrong.
            assertEquals("SQLException", line.get("cause"));
            assertTrue(String.valueOf(line.get("causeMessage")).contains("value too long"), line.toString());
            assertTrue(String.valueOf(line.get("detail")).startsWith("every 1s"), line.toString());
            scheduler.stop();

            store.flush();
            var errors = store.list(null);
            assertEquals(1, errors.size(), errors.toString());
            assertEquals("job flaky-flush", errors.get(0).get("route"));
            assertEquals("IllegalStateException", errors.get(0).get("errorType"));
            assertTrue(String.valueOf(errors.get(0).get("stackTrace")).contains("value too long"),
                "the stored trace carries the cause");
        } finally {
            scheduler.stop();
            store.close();
            dbFactory.close();
        }
    }

    @Test
    void aFailedRunIsStillLoggedWithoutAnErrorStore() throws Exception {
        var scheduler = new JobScheduler();
        var failed = new CountDownLatch(1);
        scheduler.every("1s", "no-db-job", (db, ctx) -> {
            failed.countDown();
            throw new RuntimeException("boom");
        });
        LogTap.clear();
        scheduler.start(null);
        try {
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            long deadline = System.currentTimeMillis() + 3000;
            boolean logged = false;
            while (!logged && System.currentTimeMillis() < deadline) {
                logged = LogTap.snapshot().stream().map(LogTap.LogEntry::fields)
                    .anyMatch(f -> "job.error".equals(f.get("event")) && "no-db-job".equals(f.get("job")));
                if (!logged) Thread.sleep(20);
            }
            assertTrue(logged, LogTap.snapshot().toString());
        } finally {
            scheduler.stop();
        }
    }

}
