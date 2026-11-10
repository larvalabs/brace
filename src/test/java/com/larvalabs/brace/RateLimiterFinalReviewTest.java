package com.larvalabs.brace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final review fixes for L6 (docs/2026-11-09-security-review-todos.md): the key-cap eviction in
 * shared (Postgres) mode and under concurrency, and what a saturated key table does.
 */
class RateLimiterFinalReviewTest {

    @AfterEach
    void reset() {
        RateLimiter.disableSharedBackend();
        RateLimiter.flushTimeBudgetMillis = RateLimiter.DEFAULT_FLUSH_TIME_BUDGET_MILLIS;
    }

    private static Request from(String ip, String user) {
        var headers = user == null ? Map.<String, String>of() : Map.of("X-User", user);
        return new Request("POST", "/login", Map.of(), Map.of(), headers, null, Map.of(), ip, null);
    }

    private static RateLimiter.PerKeyLimit perUser(int limit) {
        return RateLimiter.perKey(req -> req.header("X-User"), limit, "1d");
    }

    // ---- M-B: eviction on one instance must not un-block a key the fleet has blocked ----

    /**
     * Two limiters with the same label over one counter store are two instances of one app.
     * Before: instance A evicted the victim under a flood of other keys and deleted its
     * brace_counters row, so the victim, blocked through B, got a fresh budget through A.
     */
    @Test
    void evictionOnOneInstanceDoesNotReleaseAKeyBlockedFleetWide() {
        var counters = new RateLimiterTest.CountingCounters();
        RateLimiter.useSharedBackend(counters);
        var a = perUser(5).maxKeys(100);
        var b = perUser(5).maxKeys(100);
        assertNull(a.handle(from("203.0.113.9", "victim")), "A has seen the victim once");
        Result last = null;
        for (int i = 0; i < 6; i++) last = b.handle(from("203.0.113.9", "victim"));
        assertNotNull(last, "the victim is over the limit through B");
        for (int i = 0; i < 400; i++) a.handle(from("198.51.100.1", "other-" + i));
        assertNotNull(a.handle(from("203.0.113.9", "victim")),
            "A evicted its entry for the victim, but the fleet count is what decides");
        assertTrue(counters.store.keySet().stream().anyMatch(k -> k.contains(":victim:")),
            "the victim's shared row is never deleted by an eviction");
    }

    /**
     * The same with a limit large enough that the instance batches its writes (flush every 10):
     * a re-admitted key's fleet count is read on its first check, not at some later flush.
     */
    @Test
    void reAdmittedKeyReadsItsFleetCountOnItsFirstCheck() {
        var counters = new RateLimiterTest.CountingCounters();
        RateLimiter.useSharedBackend(counters);
        var a = perUser(100).maxKeys(50);
        var b = perUser(100).maxKeys(50);
        for (int i = 0; i < 20; i++) assertNull(a.handle(from("203.0.113.9", "victim")));
        Result last = null;
        for (int i = 0; i < 120; i++) last = b.handle(from("203.0.113.9", "victim"));
        assertNotNull(last);
        for (int i = 0; i < 400; i++) a.handle(from("198.51.100.1", "other-" + i));
        assertNotNull(a.handle(from("203.0.113.9", "victim")), "blocked on the first check after re-admission");
    }

    // ---- L-H: a key in use is not the eviction candidate, and eviction never resets the fleet count ----

    @Test
    void hotKeyIsNotResetByAFloodOfFreshKeys() {
        for (boolean shared : new boolean[] {false, true}) {
            if (shared) RateLimiter.useSharedBackend(new RateLimiterTest.CountingCounters());
            var limiter = perUser(100).maxKeys(50);
            int allowedHot = 0;
            for (int i = 0; i < 2_000; i++) {
                if (limiter.handle(from("203.0.113.9", "hot")) == null) allowedHot++;
                for (int j = 0; j < 3; j++) limiter.handle(from("198.51.100.1", "flood-" + i + "-" + j));
            }
            assertEquals(100, allowedHot, "the hot key gets its limit and no more (shared=" + shared + ")");
        }
    }

    // ---- M-C, L-F: bounded state and sound flush accounting under concurrency ----

    @Test
    void stateStaysBoundedAndFlushesKeepRunningUnderConcurrentKeyChurn() throws Exception {
        var counters = new RateLimiterTest.CountingCounters();
        RateLimiter.useSharedBackend(counters);
        int cap = 200, threads = 16, perThread = 20_000;
        var limiter = perUser(100).maxKeys(cap); // flush every 10 buffered requests
        var failed = new AtomicBoolean();
        var minUnflushed = new AtomicLong(Long.MAX_VALUE);
        var maxEntries = new AtomicInteger();
        var maxQueued = new AtomicInteger();
        var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread.ofPlatform().start(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        limiter.handle(from("198.51.100." + id, "t" + id + "-" + i));
                        if ((i & 255) == 0) {
                            minUnflushed.accumulateAndGet(limiter.unflushed(), Math::min);
                            maxEntries.accumulateAndGet(limiter.trackedEntries(), Math::max);
                            maxQueued.accumulateAndGet(limiter.queuedForFlush(), Math::max);
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                    failed.set(true);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(120, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(failed.get());
        maxEntries.accumulateAndGet(limiter.trackedEntries(), Math::max);
        // Slack: the size check and the insert are separate steps, so each of the 16 threads can
        // be one entry past the cap at a time; nothing accumulates beyond that.
        assertTrue(maxEntries.get() <= cap + threads,
            "every per-limiter table stays near the cap, largest was " + maxEntries.get());
        // The flush queue (keys counted since the last flush, evicted ones included) is the one
        // collection not tied to the cap alone: it has its own fixed bound.
        assertTrue(maxQueued.get() <= limiter.flushQueueBound(),
            "flush queue " + maxQueued.get() + " over its bound " + limiter.flushQueueBound());
        // Sixteen threads can queue keys faster than this in-memory stand-in flushes them; the
        // next flush after they stop takes everything that is queued.
        for (int i = 0; i < 10; i++) limiter.handle(from("198.51.100.200", "after-" + i));
        assertTrue(limiter.queuedForFlush() <= 10, "and it drains: " + limiter.queuedForFlush()
            + " (unflushed " + limiter.unflushed() + ", flushes " + counters.flushCalls.get() + ")");
        assertTrue(minUnflushed.get() >= 0, "the unflushed count never goes negative: " + minUnflushed.get());
        assertTrue(limiter.unflushed() <= 10 + threads, "nothing is stuck unflushed: " + limiter.unflushed());
        long requests = (long) threads * perThread;
        assertTrue(counters.flushCalls.get() >= requests / 10 / 4,
            "flushes kept running: " + counters.flushCalls.get() + " for " + requests + " requests");
    }

    /** A limiter one request under its flush threshold must still flush once the threshold is reached. */
    @Test
    void evictionDoesNotStallTheFlush() {
        var counters = new RateLimiterTest.CountingCounters();
        RateLimiter.useSharedBackend(counters);
        var limiter = perUser(1_000).maxKeys(20); // flush every 100 buffered requests
        for (int i = 0; i < 5_000; i++) limiter.handle(from("198.51.100.1", "k" + i));
        assertTrue(counters.flushCalls.get() >= 45, "flushes: " + counters.flushCalls.get());
        assertTrue(limiter.unflushed() >= 0 && limiter.unflushed() < 100, "unflushed: " + limiter.unflushed());
        // Evicting a key does not lose what it had buffered: every request reaches the shared store.
        limiter.handle(from("198.51.100.1", "last"));
        long stored = counters.store.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(stored >= 4_900, "counted requests written: " + stored);
    }

    // ---- M-D: what a table full of pinned keys does ----

    /** Pin {@code keys} ordinary keys (each at its limit) from one address. */
    private static void pinTable(RateLimiter.PerKeyLimit limiter, int keys, int limit) {
        for (int i = 0; i < keys; i++) {
            for (int j = 0; j <= limit; j++) limiter.handle(from("198.51.100.1", "pinned-" + i));
        }
    }

    @Test
    void perAddressOverflowBucketsAreEvictableSoTheyAreBoundedByTheCapNotATenthOfIt() {
        for (boolean shared : new boolean[] {false, true}) {
            if (shared) RateLimiter.useSharedBackend(new RateLimiterTest.CountingCounters());
            var limiter = perUser(2).maxKeys(10);
            pinTable(limiter, 10, 2);
            // 200 addresses, one new key each: under the old cap of maxKeys/10 = 1 overflow bucket
            // every address after the first fell into the single global bucket and was refused.
            for (int i = 0; i < 200; i++) {
                assertNull(limiter.handle(from("203.0.113." + i, "new-" + i)),
                    "address " + i + " has its own overflow budget (shared=" + shared + ")");
            }
            // One address is still limited across all its new keys together.
            assertNull(limiter.handle(from("203.0.113.199", "another")));
            assertNotNull(limiter.handle(from("203.0.113.199", "a-third")), "shared=" + shared);
            assertTrue(limiter.trackedEntries() <= 2 * 10 + 1, "bounded: " + limiter.trackedEntries());
        }
    }

    /** Pin the ordinary table, then the per-address overflow table too. */
    private static void saturate(RateLimiter.PerKeyLimit limiter, int maxKeys, int limit) {
        pinTable(limiter, maxKeys, limit);
        for (int i = 0; i < maxKeys; i++) {
            for (int j = 0; j <= limit; j++) limiter.handle(from("192.0.2." + i, "x-" + i + "-" + j));
        }
    }

    @Test
    void saturatedTableFailsClosedByDefault() {
        for (boolean shared : new boolean[] {false, true}) {
            if (shared) RateLimiter.useSharedBackend(new RateLimiterTest.CountingCounters());
            var limiter = perUser(2).maxKeys(10);
            saturate(limiter, 10, 2);
            // New keys from new addresses now share one last-resort bucket with the limiter's limit.
            int allowed = 0;
            for (int i = 0; i < 50; i++) {
                if (limiter.handle(from("203.0.113." + i, "new-" + i)) == null) allowed++;
            }
            assertEquals(2, allowed, "one shared budget for everyone untracked (shared=" + shared + ")");
            assertNotNull(limiter.handle(from("198.51.100.1", "pinned-3")), "tracked keys stay blocked");
            assertTrue(limiter.trackedEntries() <= 2 * 10 + 1, "bounded: " + limiter.trackedEntries());
        }
    }

    @Test
    void saturatedTableCanFailOpenForUntrackedKeysOnly() {
        for (boolean shared : new boolean[] {false, true}) {
            if (shared) RateLimiter.useSharedBackend(new RateLimiterTest.CountingCounters());
            var limiter = perUser(2).maxKeys(10).onSaturation(RateLimiter.Saturation.FAIL_OPEN);
            saturate(limiter, 10, 2);
            for (int i = 0; i < 50; i++) {
                assertNull(limiter.handle(from("203.0.113." + i, "new-" + i)), "untracked keys pass (shared=" + shared + ")");
            }
            assertNotNull(limiter.handle(from("198.51.100.1", "pinned-3")), "a tracked, blocked key stays blocked");
            assertNotNull(limiter.handle(from("192.0.2.4", "anything")), "a blocked address's overflow bucket too");
            assertTrue(limiter.trackedEntries() <= 2 * 10 + 1, "bounded: " + limiter.trackedEntries());
        }
    }

    @Test
    void perIpLimiterHasTheSaturationOptionToo() {
        var limiter = RateLimiter.perIp(1, "1d").maxKeys(5).onSaturation(RateLimiter.Saturation.FAIL_OPEN);
        for (int i = 0; i < 10; i++) {
            limiter.handle(from("203.0.113." + i, null));
            limiter.handle(from("203.0.113." + i, null));
        }
        assertNull(limiter.handle(from("198.51.100.77", null)));
        assertNull(limiter.handle(from("198.51.100.77", null)), "saturated and failing open");
        assertNotNull(limiter.handle(from("203.0.113.1", null)), "a tracked client stays blocked");
        assertThrows(NullPointerException.class, () -> RateLimiter.perIp(1, "1d").onSaturation(null));
    }

    // ---- nit: the dev warning redacts the path ----

    @Test
    void keylessReadWarningRedactsThePath() {
        String secret = "a3f9Bc2d8eF1g4h5J6k7L8m9N0p1Q2r3";
        String message = RateLimiter.keylessReadWarning("perKey(5/15m)", "GET", "/reset/" + secret);
        assertFalse(message.contains(secret), message);
        assertTrue(message.contains("/reset/[redacted]"), message);
    }
}
