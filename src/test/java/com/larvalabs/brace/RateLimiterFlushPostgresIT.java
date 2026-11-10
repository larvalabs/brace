package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L6 review F4 on real Postgres: a counter batch of more than 21,845 keys exceeded Postgres's
 * 65,535-parameter limit, the refusal (SQLState 22023) was taken for a bad key, and the flush
 * retried every key on its own while holding the request. Now batches are chunked and a refused
 * key is found by bisection.
 */
class RateLimiterFlushPostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;

    /** Real counters that count statements, can simulate an outage, and turn a "poison" key into one Postgres refuses. */
    static class InstrumentedCounters extends Counters {
        final AtomicInteger statements = new AtomicInteger();
        volatile boolean outage;

        InstrumentedCounters(DatabaseFactory f) { super(f); }

        @Override
        Map<String, Long> incrementBatch(List<CounterUpdate> updates, long deadlineNanos) {
            if (outage) {
                throw new RuntimeException(new java.sql.SQLException("connection refused", "08001"));
            }
            statements.incrementAndGet();
            var real = new ArrayList<CounterUpdate>(updates.size());
            for (var u : updates) {
                real.add(u.key().contains("poison") ? new CounterUpdate(u.key() + "\u0000", u.delta(), u.expiresAt()) : u);
            }
            var counts = super.incrementBatch(real, deadlineNanos);
            // Callers look a result up by the key they sent.
            var byRequestedKey = new java.util.HashMap<String, Long>();
            for (var u : updates) {
                Long n = counts.get(u.key());
                if (n != null) byRequestedKey.put(u.key(), n);
            }
            return byRequestedKey;
        }
    }

    @BeforeAll
    static void factory() {
        dbFactory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void close() {
        if (dbFactory != null) dbFactory.close();
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("brace_counters");
    }

    @AfterEach
    void reset() {
        RateLimiter.disableSharedBackend();
        RateLimiter.flushTimeBudgetMillis = RateLimiter.DEFAULT_FLUSH_TIME_BUDGET_MILLIS;
    }

    private static long rows() throws Exception {
        try (var c = connect(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM brace_counters")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Request from(String user) {
        return new Request("GET", "/t", Map.of(), Map.of(), Map.of("X-User", user), null, Map.of(), "198.51.100.1", null);
    }

    private static RateLimiter.PerKeyLimit pendingThenRecover(InstrumentedCounters counters, int keys, boolean poison)
            throws Exception {
        RateLimiter.useSharedBackend(counters);
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 100, "1d");
        counters.outage = true;
        for (int i = 0; i < keys; i++) {
            limiter.handle(from(poison && i == keys / 2 ? "poison" : "user-" + i));
        }
        counters.outage = false;
        Thread.sleep(1_100);
        return limiter;
    }

    @Test
    void counterBatchAboveTheParameterLimitSucceeds() throws Exception {
        var updates = new ArrayList<Counters.CounterUpdate>();
        var expires = Instant.now().plusSeconds(60);
        for (int i = 0; i < 25_000; i++) updates.add(new Counters.CounterUpdate("k" + i, 1, expires));
        assertEquals(25_000, new Counters(dbFactory).incrementBatch(updates).size());
        assertEquals(25_000, rows());
    }

    @Test
    void twentyFiveThousandPendingKeysFlushInChunks() throws Exception {
        var counters = new InstrumentedCounters(dbFactory);
        var limiter = pendingThenRecover(counters, 25_000, false);
        limiter.handle(from("trigger"));
        assertTrue(counters.statements.get() <= 6, "statements: " + counters.statements.get());
        assertEquals(25_001, rows());
    }

    /**
     * L6 final review M-B: an eviction never deletes a counter row (the review round before
     * deleted them, which let an instance with a stale view release a key the fleet had blocked).
     * Rows live until their windows end, so a key flood leaves one row per key for the window.
     */
    @Test
    void evictionNeverDeletesACounterRow() throws Exception {
        RateLimiter.useSharedBackend(new Counters(dbFactory));
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 5, "1d").maxKeys(10); // flush every request
        for (int i = 0; i < 200; i++) {
            assertNull(limiter.handle(from("flood-" + i)));
        }
        assertEquals(200, rows());
        assertTrue(limiter.trackedEntries() <= 11, "the instance's own state is capped: " + limiter.trackedEntries());
    }

    private static long count(String user) throws Exception {
        try (var c = connect(); var ps = c.prepareStatement(
                "SELECT COALESCE(SUM(n), 0) FROM brace_counters WHERE counter_key LIKE ?")) {
            ps.setString(1, "%:" + user + ":%");
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * M-B as reproduced: two limiters with one label are two instances of an app. The victim is
     * blocked through B; a flood of other keys through A evicts A's entry for the victim; the
     * victim's next attempt through A must still be refused.
     */
    @Test
    void evictionOnOneInstanceDoesNotReleaseAKeyBlockedFleetWide() throws Exception {
        RateLimiter.useSharedBackend(new Counters(dbFactory));
        var a = RateLimiter.perKey(req -> req.header("X-User"), 5, "1d").maxKeys(100);
        var b = RateLimiter.perKey(req -> req.header("X-User"), 5, "1d").maxKeys(100);
        assertNull(a.handle(from("victim")));
        Result last = null;
        for (int i = 0; i < 6; i++) last = b.handle(from("victim"));
        assertNotNull(last, "blocked through B");
        for (int i = 0; i < 400; i++) a.handle(from("other-" + i));
        for (int i = 0; i < 5; i++) {
            assertNotNull(a.handle(from("victim")), "attempt " + (i + 1) + " through A after the flood");
        }
        assertTrue(count("victim") >= 6, "the victim's row survived: " + count("victim"));
    }

    /** The same with batched writes (flush every 10): the fleet count is read on re-admission. */
    @Test
    void reAdmittedKeyReadsItsFleetCountOnItsFirstCheck() throws Exception {
        RateLimiter.useSharedBackend(new Counters(dbFactory));
        var a = RateLimiter.perKey(req -> req.header("X-User"), 100, "1d").maxKeys(50);
        var b = RateLimiter.perKey(req -> req.header("X-User"), 100, "1d").maxKeys(50);
        for (int i = 0; i < 20; i++) assertNull(a.handle(from("victim")));
        Result last = null;
        for (int i = 0; i < 120; i++) last = b.handle(from("victim"));
        assertNotNull(last);
        for (int i = 0; i < 400; i++) a.handle(from("other-" + i));
        assertNotNull(a.handle(from("victim")));
    }

    /**
     * L6 final review L-G: the flush budget bounded the gaps between statements, not a statement.
     * Here the flush's statement waits on a row lock held by another connection for 6 s; it must
     * be cancelled at about the budget, and the count kept for the next flush.
     */
    @Test
    void statementStuckBehindALockIsCancelledAtTheFlushBudget() throws Exception {
        RateLimiter.useSharedBackend(new Counters(dbFactory));
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 5, "1d"); // flush every request
        assertNull(limiter.handle(from("alice")));
        RateLimiter.flushTimeBudgetMillis = 1_000;
        long tookMillis;
        try (var locker = connect()) {
            locker.setAutoCommit(false);
            try (var st = locker.createStatement()) {
                st.executeUpdate("UPDATE brace_counters SET n = n");
            }
            var release = Thread.ofPlatform().start(() -> {
                try {
                    Thread.sleep(6_000);
                    locker.rollback();
                } catch (Exception ignored) {
                }
            });
            long start = System.nanoTime();
            assertNull(limiter.handle(from("alice")), "served on the instance's own count");
            tookMillis = (System.nanoTime() - start) / 1_000_000;
            release.join();
        }
        assertTrue(tookMillis < 3_500, "the request was held " + tookMillis + " ms by a 6 s statement");
        Thread.sleep(1_100); // past the flush retry cooldown
        assertNull(limiter.handle(from("alice")));
        assertEquals(3, count("alice"), "the cancelled write was kept and flushed later");
    }

    @Test
    void poisonKeyAmongTenThousandIsIsolatedInLogarithmicStatements() throws Exception {
        var counters = new InstrumentedCounters(dbFactory);
        var limiter = pendingThenRecover(counters, 10_000, true);
        limiter.handle(from("trigger"));
        assertTrue(counters.statements.get() < 40, "statements: " + counters.statements.get());
        assertEquals(10_000, rows(), "every key but the refused one is stored");
    }
}
