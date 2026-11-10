package com.larvalabs.brace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L6 verification fix (docs/2026-11-09-security-review-todos.md): the returning-key fleet read
 * held a monitor across the database call, which on JDK 21 pins a virtual thread's carrier, so a
 * slow database starved every other virtual thread of the server.
 */
class RateLimiterPinningTest {

    @AfterEach
    void reset() {
        RateLimiter.disableSharedBackend();
        RateLimiter.fleetReadsPerSecond = RateLimiter.DEFAULT_FLEET_READS_PER_SECOND;
    }

    /** An in-memory store whose single-row writes take 300 ms once {@code slow} is set; no monitor. */
    static final class SlowCounters extends Counters {
        final Map<String, Long> store = new ConcurrentHashMap<>();
        volatile boolean slow;

        SlowCounters() { super(null); }

        @Override
        Map<String, Long> incrementBatch(List<CounterUpdate> updates, long deadlineNanos) {
            if (slow && !(updates instanceof java.util.ArrayList)) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var result = new HashMap<String, Long>();
            for (var u : updates) result.put(u.key(), store.merge(u.key(), u.delta(), Long::sum));
            return result;
        }
    }

    private static Request from(String user) {
        return new Request("POST", "/login", Map.of(), Map.of(), Map.of("X-User", user), null, Map.of(),
            "198.51.100.1", null);
    }

    @Test
    void aSlowFleetReadDoesNotPinCarrierThreads() throws Exception {
        var counters = new SlowCounters();
        RateLimiter.useSharedBackend(counters);
        RateLimiter.fleetReadsPerSecond = 10_000;
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 100_000, "1d").maxKeys(10);
        int keys = 100;
        for (int i = 0; i < keys; i++) limiter.handle(from("returning-" + i));
        for (int i = 0; i < 200; i++) limiter.handle(from("filler-" + i)); // evicts every returning key
        counters.slow = true;

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(keys);
        for (int i = 0; i < keys; i++) {
            String user = "returning-" + i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    limiter.handle(from(user));
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        long t0 = System.nanoTime();
        start.countDown();
        Thread.sleep(50);
        long[] scheduledAfter = {-1};
        long asked = System.nanoTime();
        var unrelated = Thread.ofVirtual().start(() -> scheduledAfter[0] = System.nanoTime() - asked);
        assertTrue(done.await(30, TimeUnit.SECONDS));
        long totalMillis = (System.nanoTime() - t0) / 1_000_000;
        unrelated.join();
        long waitedMillis = scheduledAfter[0] / 1_000_000;
        assertTrue(waitedMillis < 250, "an unrelated virtual thread waited " + waitedMillis + " ms to run");
        assertTrue(totalMillis < 1_500, keys + " reads of 300 ms took " + totalMillis + " ms");
    }
}
