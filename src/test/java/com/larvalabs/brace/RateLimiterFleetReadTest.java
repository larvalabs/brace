package com.larvalabs.brace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L6 verification fixes (docs/2026-11-09-security-review-todos.md): the single-row read of a
 * returning evicted key's fleet count is budgeted per second and its Bloom filter rotates, so a
 * long window of evictions cannot put every new key on the database.
 */
class RateLimiterFleetReadTest {

    @AfterEach
    void reset() {
        RateLimiter.disableSharedBackend();
        RateLimiter.fleetReadsPerSecond = RateLimiter.DEFAULT_FLEET_READS_PER_SECOND;
    }

    private static Request from(String user) {
        return new Request("POST", "/login", Map.of(), Map.of(), Map.of("X-User", user), null, Map.of(),
            "198.51.100.1", null);
    }

    private static RateLimiter.PerKeyLimit perUser(int limit) {
        return RateLimiter.perKey(req -> req.header("X-User"), limit, "1d");
    }

    /** Counts the single-row writes a returning-key read makes; a flush passes a mutable list. */
    static final class ReadCountingCounters extends RateLimiterTest.CountingCounters {
        final AtomicInteger singleReads = new AtomicInteger();

        @Override
        synchronized Map<String, Long> incrementBatch(List<CounterUpdate> updates, long deadlineNanos) {
            if (!(updates instanceof java.util.ArrayList)) singleReads.incrementAndGet();
            return super.incrementBatch(updates, deadlineNanos);
        }
    }

    @Test
    void returningKeyReadsStayWithinTheirBudgetPerSecond() {
        var counters = new ReadCountingCounters();
        RateLimiter.useSharedBackend(counters);
        RateLimiter.fleetReadsPerSecond = 5;
        var limiter = perUser(100_000).maxKeys(10); // flush every 10,000 requests: no flush here
        // 100 keys cycling through a 10-key table: nearly every request is a returning, evicted key.
        long start = System.nanoTime();
        for (int i = 0; i < 5_000; i++) limiter.handle(from("k" + (i % 100)));
        long seconds = (System.nanoTime() - start) / 1_000_000_000L + 1;
        assertTrue(counters.singleReads.get() <= 5 * (seconds + 1),
            counters.singleReads.get() + " single-row reads in about " + seconds + " s");
    }

    @Test
    void aLongFloodOfFreshKeysDoesNotSaturateTheEvictedKeyFilter() {
        var counters = new ReadCountingCounters();
        RateLimiter.useSharedBackend(counters);
        RateLimiter.fleetReadsPerSecond = Integer.MAX_VALUE; // measure the filter, not the budget
        var limiter = perUser(1_000).maxKeys(1_000);
        for (int i = 0; i < 260_000; i++) limiter.handle(from("fresh-" + i));
        int before = counters.singleReads.get();
        for (int i = 0; i < 10_000; i++) limiter.handle(from("late-" + i));
        int reads = counters.singleReads.get() - before;
        // Fresh keys never come back: every read is a false positive of the filter.
        assertTrue(reads < 150, reads + " of 10,000 new keys read their fleet count");
    }

    @Test
    void aFleetBlockedKeyIsRefusedWithinOneFlushWhenNoReadIsAvailable() {
        var counters = new ReadCountingCounters();
        RateLimiter.useSharedBackend(counters);
        RateLimiter.fleetReadsPerSecond = 0;
        var a = perUser(100).maxKeys(50); // flush every 10 buffered requests
        var b = perUser(100).maxKeys(50);
        // Twenty through A, flushed, so A holds nothing buffered for the victim and can evict it.
        for (int i = 0; i < 20; i++) assertNull(a.handle(from("victim")));
        Result last = null;
        for (int i = 0; i < 120; i++) last = b.handle(from("victim"));
        assertNotNull(last, "blocked fleet-wide through B");
        for (int i = 0; i < 400; i++) a.handle(from("other-" + i));
        int allowed = 0;
        for (int i = 0; i < 20; i++) if (a.handle(from("victim")) == null) allowed++;
        assertEquals(0, counters.singleReads.get(), "no read budget");
        assertTrue(allowed <= 10, "refused from the first flush after it came back: " + allowed + " allowed");
        assertNotNull(a.handle(from("victim")));
    }
}
