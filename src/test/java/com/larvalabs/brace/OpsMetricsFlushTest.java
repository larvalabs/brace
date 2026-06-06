package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the Tier 1c batched flush write ({@link Brace#insertMetrics}) — the one piece of that
 * change with non-trivial dynamic SQL (a multi-row {@code INSERT} whose {@code ?} placeholders get
 * renumbered to {@code ?1, ?2…}). The flush jobs themselves are wired in {@code Brace.start}; this
 * exercises the SQL directly against H2 so a malformed VALUES list or mis-bound param fails loudly.
 */
class OpsMetricsFlushTest {

    static DatabaseFactory dbFactory;

    @BeforeAll
    static void setup() {
        dbFactory = new DatabaseFactory(
            "jdbc:h2:mem:opsmetricsdb;DB_CLOSE_DELAY=-1", null, null, List.of());
    }

    @AfterAll
    static void teardown() {
        dbFactory.close();
    }

    @BeforeEach
    void clean() {
        tx(db -> db.sql("DELETE FROM ops_timeseries"));
    }

    @Test
    void batchInsertWritesEveryMetricInOneStatement() {
        var ts = Timestamp.from(Instant.parse("2026-06-06T12:00:00Z"));
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("http.requests", 10L);
        metrics.put("http.errors", 2L);
        metrics.put("http.queries", 5L);

        tx(db -> Brace.insertMetrics(db, ts, metrics));

        var rows = read(db -> db.sqlQuery("SELECT metric, val FROM ops_timeseries ORDER BY metric"));
        assertEquals(3, rows.size(), "all three metrics land from one multi-row INSERT");
        assertEquals("http.errors", rows.get(0)[0]);
        assertEquals(2L, ((Number) rows.get(0)[1]).longValue());
        assertEquals("http.queries", rows.get(1)[0]);
        assertEquals(5L, ((Number) rows.get(1)[1]).longValue());
        assertEquals("http.requests", rows.get(2)[0]);
        assertEquals(10L, ((Number) rows.get(2)[1]).longValue());
    }

    @Test
    void singleMetricBatchInserts() {
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("mailer.failures", 1L);

        tx(db -> Brace.insertMetrics(db, Timestamp.from(Instant.now()), metrics));

        assertEquals(1L, count());
    }

    @Test
    void emptyMetricsIsANoOp() {
        tx(db -> Brace.insertMetrics(db, Timestamp.from(Instant.now()), new LinkedHashMap<>()));

        assertEquals(0L, count(), "an empty flush must not emit an INSERT");
    }

    private long count() {
        Long c = read(db -> db.sqlQueryLong("SELECT COUNT(*) FROM ops_timeseries"));
        return c != null ? c : 0;
    }

    private void tx(java.util.function.Consumer<Database> work) {
        var db = new Database(dbFactory.openSession());
        db.beginTransaction();
        try {
            work.accept(db);
            db.commitTransaction();
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } finally {
            db.close();
        }
    }

    private <T> T read(java.util.function.Function<Database, T> work) {
        var db = new Database(dbFactory.openSession());
        try {
            return work.apply(db);
        } finally {
            db.close();
        }
    }
}
