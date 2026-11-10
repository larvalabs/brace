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
        Map<String, Long> incrementBatch(List<CounterUpdate> updates) {
            if (outage) {
                throw new RuntimeException(new java.sql.SQLException("connection refused", "08001"));
            }
            statements.incrementAndGet();
            var real = new ArrayList<CounterUpdate>(updates.size());
            for (var u : updates) {
                real.add(u.key().contains("poison") ? new CounterUpdate(u.key() + "\u0000", u.delta(), u.expiresAt()) : u);
            }
            return super.incrementBatch(real);
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
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 100, "1m");
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

    /** L6 review decision B: an evicted key's row is deleted, so a key flood leaves the table near the cap. */
    @Test
    void evictedKeysRowsAreDeleted() throws Exception {
        RateLimiter.useSharedBackend(new Counters(dbFactory));
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 5, "1m").maxKeys(10); // flush every request
        for (int i = 0; i < 200; i++) {
            assertNull(limiter.handle(from("flood-" + i)));
        }
        assertTrue(rows() <= 12, "rows: " + rows());
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
