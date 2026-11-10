package com.larvalabs.brace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review fixes for the rate-limiter findings L6 and M4 (docs/2026-11-09-security-review-todos.md).
 */
class RateLimiterReviewFixesTest {

    @AfterEach
    void reset() {
        RateLimiter.disableSharedBackend();
        RateLimiter.flushTimeBudgetMillis = RateLimiter.DEFAULT_FLUSH_TIME_BUDGET_MILLIS;
    }

    private static Request from(String ip, String user) {
        var headers = user == null ? Map.<String, String>of() : Map.of("X-User", user);
        return new Request("GET", "/test", Map.of(), Map.of(), headers, null, Map.of(), ip, null);
    }

    // ---- L6 decision B: a full key table evicts an under-limit key instead of overflowing ----

    /**
     * Before: once 1,000 keys were tracked, every new key shared one global overflow bucket, so a
     * flood of fresh keys (here 20 more, draining that bucket) locked out every new legitimate key.
     */
    @Test
    void newKeyGetsItsOwnBudgetAfterAFloodOfUnderLimitKeys() {
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 3, "1d").maxKeys(1000);
        for (int i = 0; i < 1020; i++) {
            limiter.handle(from("198.51.100.1", "flood-" + i));
        }
        for (int i = 0; i < 3; i++) {
            assertNull(limiter.handle(from("203.0.113.9", "legit")), "legit request " + (i + 1));
        }
        assertNotNull(limiter.handle(from("203.0.113.9", "legit")), "and then legit is limited as usual");
        assertTrue(limiter.windowCount() <= 1001, "state stays bounded: " + limiter.windowCount());
    }

    @Test
    void sharedModeAlsoEvictsUnderLimitKeys() {
        RateLimiter.useSharedBackend(new RateLimiterTest.CountingCounters());
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 3, "1d").maxKeys(1000);
        for (int i = 0; i < 1020; i++) {
            limiter.handle(from("198.51.100.1", "flood-" + i));
        }
        for (int i = 0; i < 3; i++) {
            assertNull(limiter.handle(from("203.0.113.9", "legit")), "legit request " + (i + 1));
        }
        assertNotNull(limiter.handle(from("203.0.113.9", "legit")));
    }

    @Test
    void blockedKeysSurviveAFlood() {
        for (boolean shared : new boolean[]{false, true}) {
            if (shared) RateLimiter.useSharedBackend(new RateLimiterTest.CountingCounters());
            var limiter = RateLimiter.perKey(req -> req.header("X-User"), 2, "1d").maxKeys(100);
            var victim = from("203.0.113.9", "victim");
            assertNull(limiter.handle(victim));
            assertNull(limiter.handle(victim));
            assertNotNull(limiter.handle(victim));
            for (int i = 0; i < 1000; i++) {
                limiter.handle(from("198.51.100.1", "flood-" + i));
            }
            assertNotNull(limiter.handle(victim), "a blocked key stays blocked until its window ends (shared=" + shared + ")");
        }
    }

    /** A table full of blocked keys: a new key falls back to a bucket per client address. */
    @Test
    void tableFullOfBlockedKeysSendsNewKeysToAPerIpOverflow() {
        for (boolean shared : new boolean[]{false, true}) {
            if (shared) RateLimiter.useSharedBackend(new RateLimiterTest.CountingCounters());
            var limiter = RateLimiter.perKey(req -> req.header("X-User"), 1, "1d").maxKeys(10);
            for (int i = 0; i < 10; i++) {
                limiter.handle(from("198.51.100.1", "blocked-" + i));
                assertNotNull(limiter.handle(from("198.51.100.1", "blocked-" + i)));
            }
            assertNull(limiter.handle(from("203.0.113.1", "new-1")), "first new key from A (shared=" + shared + ")");
            assertNotNull(limiter.handle(from("203.0.113.1", "new-2")), "A's overflow bucket is used up");
            assertNull(limiter.handle(from("203.0.113.2", "new-3")), "B has its own overflow bucket (shared=" + shared + ")");
        }
    }

    // ---- M4 decision A: a missing key is counted per client address, not in one global bucket ----

    private static Request get(String ip, String method) {
        return new Request(method, "/login", Map.of(), Map.of(), Map.of(), null, Map.of(), ip, null);
    }

    /**
     * The previously documented {@code app.before("/login", perKey(req -> req.formParam("email"),
     * 5, "15m"))} counted every {@code GET /login} in one global bucket: five page views from five
     * addresses locked the sixth visitor out with no attacker involved.
     */
    @Test
    void keylessRequestsFromDifferentAddressesDoNotLockEachOtherOut() {
        for (boolean shared : new boolean[]{false, true}) {
            if (shared) RateLimiter.useSharedBackend(new RateLimiterTest.CountingCounters());
            var limiter = RateLimiter.perKey(req -> req.header("X-Email"), 5, "15m");
            for (int i = 1; i <= 5; i++) {
                assertNull(limiter.handle(get("203.0.113." + i, "GET")));
            }
            assertNull(limiter.handle(get("203.0.113.6", "GET")), "a sixth visitor is not locked out (shared=" + shared + ")");
        }
    }

    @Test
    void oneAddressIsStillLimitedAfterLimitKeylessRequests() {
        for (boolean shared : new boolean[]{false, true}) {
            if (shared) RateLimiter.useSharedBackend(new RateLimiterTest.CountingCounters());
            var limiter = RateLimiter.perKey(req -> req.header("X-Email"), 3, "15m");
            for (int i = 0; i < 3; i++) {
                assertNull(limiter.handle(get("203.0.113.7", "POST")));
            }
            var blocked = limiter.handle(get("203.0.113.7", "POST"));
            assertNotNull(blocked, "keyless requests are limited per address, not exempt (shared=" + shared + ")");
            assertEquals(429, blocked.status());
            // An IPv6 client rotating inside its /64 is one address here too.
            for (int i = 0; i < 3; i++) {
                assertNull(limiter.handle(get("2001:db8:5:6::" + (i + 1), "POST")));
            }
            assertNotNull(limiter.handle(get("2001:db8:5:6::99", "POST")));
        }
    }

    @Test
    void clientSentLiteralCannotReachAnotherAddressesMissingKeyBucket() {
        var victimIp = "203.0.113.9";
        for (var literal : new String[]{"(none):" + victimIp, " (none):" + victimIp,
                RateLimiter.MISSING_KEY + ":" + victimIp, "(none)", RateLimiter.MISSING_KEY}) {
            var limiter = RateLimiter.perKey(req -> req.header("X-User"), 1, "1d");
            var attacker = from("198.51.100.1", literal);
            assertNull(limiter.handle(attacker));
            assertNotNull(limiter.handle(attacker));
            assertNull(limiter.handle(get(victimIp, "POST")), "'" + literal + "' did not drain the victim's bucket");
            assertNotNull(limiter.handle(get(victimIp, "POST")));
        }
    }

    @Test
    void devModeWarnsOnceAboutAKeylessGet() {
        var previous = System.getProperty("brace.mode");
        System.setProperty("brace.mode", "dev");
        try {
            var postOnly = RateLimiter.perKey(req -> req.header("X-Email"), 5, "15m");
            postOnly.handle(get("203.0.113.1", "POST"));
            assertFalse(postOnly.warnedKeylessRead(), "a keyless POST is what the limiter is for");
            var onGet = RateLimiter.perKey(req -> req.header("X-Email"), 5, "15m");
            onGet.handle(get("203.0.113.1", "GET"));
            assertTrue(onGet.warnedKeylessRead());
            var onHead = RateLimiter.perKey(req -> req.header("X-Email"), 5, "15m");
            onHead.handle(get("203.0.113.1", "HEAD"));
            assertTrue(onHead.warnedKeylessRead());
            var exempt = RateLimiter.perKey(req -> req.header("X-Email"), 5, "15m").exemptMissingKey();
            exempt.handle(get("203.0.113.1", "GET"));
            assertFalse(exempt.warnedKeylessRead(), "an exempt missing key is not counted");
        } finally {
            if (previous == null) System.clearProperty("brace.mode"); else System.setProperty("brace.mode", previous);
        }
        var prod = RateLimiter.perKey(req -> req.header("X-Email"), 5, "15m");
        prod.handle(get("203.0.113.1", "GET"));
        assertFalse(prod.warnedKeylessRead(), "dev mode only");
    }

    // ---- L6 F4: an oversized counter batch ----

    /**
     * Counters stand-in that, like Postgres, refuses a statement with more than 65,535 parameters
     * (three per row), refuses a key containing "poison" with a data error, and records the size
     * of every statement.
     */
    static class RecordingCounters extends RateLimiterTest.PickyCounters {
        final List<Integer> statementSizes = new ArrayList<>();
        volatile long sleepMillis;

        @Override
        synchronized Map<String, Long> incrementBatch(List<CounterUpdate> updates, long deadlineNanos) {
            if (!outage) statementSizes.add(updates.size());
            if (sleepMillis > 0) {
                try { Thread.sleep(sleepMillis); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            if (!outage && updates.size() * 3 > 65_535) {
                throw new RuntimeException(new java.sql.SQLException("PreparedStatement can have at most 65,535 "
                    + "parameters. Please consider using arrays, or splitting the query in several ones, or using "
                    + "COPY. Given query has " + updates.size() * 3 + " parameters", "22023"));
            }
            return super.incrementBatch(updates, deadlineNanos);
        }
    }

    /** Build up {@code keys} pending keys during an outage, then end it; the next request flushes. */
    private static RateLimiter.PerKeyLimit pendingKeysThenRecover(RecordingCounters counters, int keys,
                                                                 String poison) throws Exception {
        RateLimiter.useSharedBackend(counters);
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 100, "1d");
        counters.outage = true;
        for (int i = 0; i < keys; i++) {
            limiter.handle(from("198.51.100.1", i == keys / 2 && poison != null ? poison : "user-" + i));
        }
        counters.outage = false;
        Thread.sleep(1_100); // past the flush retry cooldown
        return limiter;
    }

    @Test
    void largeFlushIsChunkedWithoutAPerKeyFallback() throws Exception {
        var counters = new RecordingCounters();
        var limiter = pendingKeysThenRecover(counters, 25_000, null);
        limiter.handle(from("198.51.100.1", "trigger"));
        assertTrue(counters.statementSizes.size() <= 6, "statements: " + counters.statementSizes.size());
        assertTrue(counters.statementSizes.stream().allMatch(n -> n <= 5_000), counters.statementSizes.toString());
        assertEquals(25_001, counters.store.size());
    }

    @Test
    void poisonKeyIsIsolatedByBisection() throws Exception {
        var counters = new RecordingCounters();
        var limiter = pendingKeysThenRecover(counters, 10_000, "poison");
        limiter.handle(from("198.51.100.1", "trigger"));
        assertTrue(counters.statementSizes.size() < 40,
            "O(log n) statements to find one poison key, was " + counters.statementSizes.size());
        assertEquals(10_000, counters.store.size(), "every key but the poison one was written");
        assertTrue(counters.store.keySet().stream().noneMatch(k -> k.contains("poison")));
    }

    @Test
    void flushStopsAtItsTimeBudgetAndKeepsTheRest() throws Exception {
        var counters = new RecordingCounters();
        var limiter = pendingKeysThenRecover(counters, 10_000, "poison");
        RateLimiter.flushTimeBudgetMillis = 250;
        counters.sleepMillis = 100;
        long start = System.nanoTime();
        limiter.handle(from("198.51.100.1", "trigger"));
        long tookMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(tookMillis < 1_000, "the flush held the request for " + tookMillis + " ms");
        assertTrue(counters.statementSizes.size() <= 4, "statements: " + counters.statementSizes.size());
        // What wasn't written is kept for the next flush (after the retry cooldown the overrun
        // armed): nothing but the poison key is lost.
        counters.sleepMillis = 0;
        RateLimiter.flushTimeBudgetMillis = RateLimiter.DEFAULT_FLUSH_TIME_BUDGET_MILLIS;
        for (int i = 0; i < 3 && counters.store.size() < 10_000; i++) {
            Thread.sleep(1_100);
            limiter.handle(from("198.51.100.1", "trigger"));
        }
        assertEquals(10_000, counters.store.size());
    }

    @Test
    void parameterCountErrorIsNotAKeyProblem() {
        var e = new RuntimeException(new java.sql.SQLException(
            "PreparedStatement can have at most 65,535 parameters. Please consider using arrays", "22023"));
        assertFalse(Counters.isDataError(e));
        assertTrue(Counters.isDataError(new RuntimeException(new java.sql.SQLException(
            "Zero bytes may not occur in string parameters.", "22023"))));
    }
}
