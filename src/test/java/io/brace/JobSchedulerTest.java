package io.brace;

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

        scheduler.every("1s", "test-job", db -> {
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

        scheduler.every("1s", "status-job", db -> {
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

        scheduler.every("1s", "fail-job", db -> {
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
    void durationParsing() {
        var scheduler = new JobScheduler();
        scheduler.every("30s", "sec-job", db -> {});
        scheduler.every("5m", "min-job", db -> {});
        scheduler.every("2h", "hour-job", db -> {});
        var statuses = scheduler.getStatuses();
        assertEquals(3, statuses.size());
    }
}
