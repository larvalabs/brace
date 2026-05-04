package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JobsAsyncTest {

    @Test
    void runExecutesOffCallerThread() throws Exception {
        var callerThread = Thread.currentThread();
        var taskThreadName = new String[1];
        var latch = new CountDownLatch(1);

        Jobs.run(() -> {
            taskThreadName[0] = Thread.currentThread().getName();
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotEquals(callerThread.getName(), taskThreadName[0]);
    }

    @Test
    void runSwallowsExceptionAndIncrementsFailedCount() throws Exception {
        long failedBefore = Jobs.asyncFailed();
        var latch = new CountDownLatch(1);

        Jobs.run(() -> {
            try {
                throw new RuntimeException("planned failure");
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertTrue(Jobs.asyncFailed() > failedBefore);
    }

    @Test
    void submitReturnsFutureWithResult() throws Exception {
        var future = Jobs.submit(() -> 21 * 2);
        assertEquals(42, future.get(2, TimeUnit.SECONDS));
    }

    @Test
    void submitPropagatesExceptionsThroughFuture() {
        var future = Jobs.submit(() -> {
            throw new IllegalStateException("nope");
        });
        var ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("nope", ex.getCause().getMessage());
    }

    @Test
    void submittedCountIncrements() {
        long before = Jobs.asyncSubmitted();
        var n = 5;
        var latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            Jobs.run(latch::countDown);
        }
        assertTrue(Jobs.asyncSubmitted() >= before + n);
    }

    @Test
    void manyConcurrentTasksRunInParallel() throws Exception {
        var n = 50;
        var latch = new CountDownLatch(n);
        var counter = new AtomicInteger(0);

        for (int i = 0; i < n; i++) {
            Jobs.run(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                counter.incrementAndGet();
                latch.countDown();
            });
        }

        // 50 tasks at 50ms each would be 2500ms serially; virtual-thread-per-task
        // should finish within ~500ms.
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(n, counter.get());
    }
}
