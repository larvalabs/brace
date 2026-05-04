package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JobSchedulerTest {

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
}
