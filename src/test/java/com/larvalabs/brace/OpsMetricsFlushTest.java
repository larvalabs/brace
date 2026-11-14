package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
        tx(db -> db.sql("DELETE FROM ops_profiling_snapshots"));
    }

    @Test
    void batchInsertWritesEveryMetricInOneStatement() {
        var ts = Timestamp.from(Instant.parse("2026-06-06T12:00:00Z"));
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("http.requests", 10L);
        metrics.put("http.errors", 2L);
        metrics.put("http.queries", 5L);

        tx(db -> Brace.insertMetrics(db, ts, "test-instance", metrics));

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

        tx(db -> Brace.insertMetrics(db, Timestamp.from(Instant.now()), "test-instance", metrics));

        assertEquals(1L, count());
    }

    @Test
    void emptyMetricsIsANoOp() {
        tx(db -> Brace.insertMetrics(db, Timestamp.from(Instant.now()), "test-instance", new LinkedHashMap<>()));

        assertEquals(0L, count(), "an empty flush must not emit an INSERT");
    }

    /**
     * The shape of a real Hibernate access-optimizer class: one generated name per entity, with
     * every mapped field spelled out. hockeystats' longest was 243 characters; one past 255 failed
     * the whole multi-row INSERT with SQLState 22001 and lost that five-minute snapshot.
     */
    private static String accessOptimizer(String entity, int fields) {
        var sb = new StringBuilder("app.entities.").append(entity).append("$HibernateAccessOptimizer");
        for (int i = 0; i < fields; i++) sb.append("5field").append(i);
        return sb.toString();
    }

    @Test
    void profilingSnapshotStoresNamesLongerThanTheColumn() {
        String longA = accessOptimizer("PlayerGameEvents", 60) + "A";
        String longB = accessOptimizer("PlayerGameEvents", 60) + "B"; // same 255-char prefix
        assertTrue(longA.length() > 300);
        var ts = Timestamp.from(Instant.parse("2026-11-14T04:10:52Z"));
        List<Map.Entry<String, Long>> methods = List.of(
            Map.entry(longA + ".get", 40L), Map.entry(longB + ".get", 30L), Map.entry("app.Short.run", 5L));
        List<Map.Entry<String, Long>> allocations = List.of(Map.entry(longA, 4096L), Map.entry("byte[]", 1024L));

        tx(db -> Brace.insertProfiling(db, ts, methods, allocations));

        var rows = read(db -> db.sqlQuery(
            "SELECT \"type\", \"name\", \"value\" FROM ops_profiling_snapshots ORDER BY \"type\", \"value\" DESC"));
        assertEquals(5, rows.size(), "every row lands, the long ones included");
        assertEquals("byte[]", rows.get(1)[1]);
        assertEquals("app.Short.run", rows.get(4)[1], "short names are stored as they are");
        String storedA = (String) rows.get(2)[1], storedB = (String) rows.get(3)[1];
        assertEquals(255, storedA.length());
        assertTrue(storedA.startsWith("app.entities.PlayerGameEvents$HibernateAccessOptimizer5field0"));
        assertNotEquals(storedA, storedB, "names sharing a prefix stay distinct in the key");
        assertEquals(JfrProfiler.storedName(longA + ".get"), storedA);
    }

    @Test
    void emptyProfilingSnapshotIsANoOp() {
        tx(db -> Brace.insertProfiling(db, Timestamp.from(Instant.now()), List.of(), List.of()));
        Long n = read(db -> db.sqlQueryLong("SELECT COUNT(*) FROM ops_profiling_snapshots"));
        assertEquals(0L, n == null ? 0L : n.longValue());
    }

    @Test
    void storedNameKeepsShortNamesAndHashesLongOnes() {
        assertEquals("java.lang.String", JfrProfiler.storedName("java.lang.String"));
        String exactly255 = "x".repeat(255);
        assertSame(exactly255, JfrProfiler.storedName(exactly255));

        String long1 = "y".repeat(256);
        String stored = JfrProfiler.storedName(long1);
        assertEquals(255, stored.length());
        assertTrue(stored.matches("y{238}~[0-9a-f]{16}"), stored);
        assertEquals(stored, JfrProfiler.storedName(long1), "stable across snapshots");
        assertNotEquals(stored, JfrProfiler.storedName("y".repeat(257)));

        // Never cuts a surrogate pair in half.
        String emoji = "a".repeat(237) + "\uD83D\uDE00" + "b".repeat(40);
        String cut = JfrProfiler.storedName(emoji);
        assertTrue(cut.length() <= 255);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.indexOf('~') - 1)), cut);
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
