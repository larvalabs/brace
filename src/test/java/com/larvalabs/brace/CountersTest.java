package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F2 coordination counter, on H2. True cross-instance atomicity is proven by the two-instance
 * Postgres IT under B4; here we verify single-process correctness, expiry semantics, and sweep.
 */
class CountersTest {

    static DatabaseFactory dbFactory;
    Counters counters;

    @BeforeAll
    static void setup() {
        // Framework migrations (incl. V9 brace_counters) run in the DatabaseFactory constructor.
        dbFactory = new DatabaseFactory(
            "jdbc:h2:mem:countersdb;DB_CLOSE_DELAY=-1", null, null, List.of());
    }

    @BeforeEach
    void freshCounters() {
        counters = new Counters(dbFactory);
        // Isolate tests from each other.
        var db = new Database(dbFactory.openSession());
        db.beginTransaction();
        db.sql("DELETE FROM brace_counters");
        db.commitTransaction();
        db.close();
    }

    @Test
    void incrementAccumulates() {
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
    void getReturnsZeroForAbsentKey() {
        assertEquals(0, counters.get("missing"));
    }

    @Test
    void expiredRowResetsOnIncrement() {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        // Row created already expired.
        assertEquals(5, counters.incrementAndGet("win", 5, past));
        // get() treats it as gone...
        assertEquals(0, counters.get("win"));
        // ...and the next increment starts fresh rather than continuing from 5.
        Instant future = Instant.now().plus(1, ChronoUnit.MINUTES);
        assertEquals(2, counters.incrementAndGet("win", 2, future));
        assertEquals(2, counters.get("win"));
    }

    @Test
    void sweepRemovesOnlyExpired() {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant future = Instant.now().plus(1, ChronoUnit.MINUTES);
        counters.incrementAndGet("old", 1, past);
        counters.incrementAndGet("live", 1, future);
        counters.incrementAndGet("forever", 1, null);

        assertEquals(1, counters.sweepExpired(), "only the expired row should be removed");
        assertEquals(0, counters.get("old"));
        assertEquals(1, counters.get("live"));
        assertEquals(1, counters.get("forever"));
    }
}
