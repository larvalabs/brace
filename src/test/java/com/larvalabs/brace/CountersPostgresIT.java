package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F2 counter on real Postgres, exercising the single-statement {@code ON CONFLICT} upsert path
 * ({@code Counters.incrementPostgres}) that {@code CountersTest} (H2) cannot — including the
 * expiry-reset folded into the {@code CASE}. See {@code docs/2026-06-07-rate-limiter-load.md}.
 */
class CountersPostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;
    Counters counters;

    @BeforeAll
    static void buildFactory() {
        dbFactory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void closeFactory() {
        if (dbFactory != null) dbFactory.close();
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("brace_counters");
        counters = new Counters(dbFactory);
    }

    @Test
    void upsertAccumulates() {
        assertEquals(1, counters.incrementAndGet("a", 1, null));
        assertEquals(2, counters.incrementAndGet("a", 1, null));
        assertEquals(7, counters.incrementAndGet("a", 5, null));
        assertEquals(7, counters.get("a"));
    }

    @Test
    void keysAreIndependent() {
        counters.incrementAndGet("x", 3, null);
        counters.incrementAndGet("y", 10, null);
        assertEquals(3, counters.get("x"));
        assertEquals(10, counters.get("y"));
    }

    @Test
    void incrementBatchUpsertsManyRowsInOneStatement() {
        // The multi-row INSERT ... ON CONFLICT ... RETURNING path (incrementBatchPostgres).
        var first = counters.incrementBatch(List.of(
            new Counters.CounterUpdate("a", 2, null),
            new Counters.CounterUpdate("b", 5, null),
            new Counters.CounterUpdate("c", 1, null)));
        assertEquals(2L, first.get("a"));
        assertEquals(5L, first.get("b"));
        assertEquals(1L, first.get("c"));

        // A second batch accumulates onto existing rows and seeds new ones in the same statement.
        var second = counters.incrementBatch(List.of(
            new Counters.CounterUpdate("a", 3, null),
            new Counters.CounterUpdate("d", 9, null)));
        assertEquals(5L, second.get("a"));
        assertEquals(9L, second.get("d"));
        assertEquals(5, counters.get("b"));
    }

    @Test
    void incrementBatchExpiredRowResets() {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant future = Instant.now().plus(1, ChronoUnit.MINUTES);
        counters.incrementBatch(List.of(new Counters.CounterUpdate("k", 5, past)));
        // The CASE must reset an already-expired row to delta, not continue from 5.
        var reset = counters.incrementBatch(List.of(new Counters.CounterUpdate("k", 2, future)));
        assertEquals(2L, reset.get("k"));
        assertEquals(2, counters.get("k"));
    }

    @Test
    void expiredRowResetsOnIncrement() {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        assertEquals(5, counters.incrementAndGet("win", 5, past)); // created already expired
        assertEquals(0, counters.get("win"));                      // read treats it as gone
        Instant future = Instant.now().plus(1, ChronoUnit.MINUTES);
        // The upsert's CASE must reset to delta, not continue from 5.
        assertEquals(2, counters.incrementAndGet("win", 2, future));
        assertEquals(2, counters.get("win"));
    }
}
