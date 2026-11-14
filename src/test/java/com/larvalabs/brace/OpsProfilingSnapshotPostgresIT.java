package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ops-flush-jvm-profiling write on real Postgres, where it failed in production: a Hibernate
 * access-optimizer class name past 255 characters made the multi-row INSERT fail with SQLState
 * 22001 ("value too long for type character varying(255)"), and that whole snapshot was lost.
 */
class OpsProfilingSnapshotPostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;

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
        truncate("ops_profiling_snapshots");
    }

    @Test
    void aSnapshotWithNamesLongerThanTheColumnIsStoredWhole() {
        var sb = new StringBuilder("app.entities.PlayerGameEvents$HibernateAccessOptimizer");
        for (int i = 0; i < 40; i++) sb.append("5blocks").append(i);
        String longA = sb + "A", longB = sb + "B";
        assertTrue(longA.length() > 255);
        var ts = Timestamp.from(Instant.parse("2026-11-14T04:10:52Z"));

        q(db -> {
            Brace.insertProfiling(db, ts,
                List.of(Map.entry(longA + ".get", 40L), Map.entry(longB + ".get", 30L)),
                List.of(Map.entry(longA, 4096L), Map.entry("byte[]", 1024L)));
            return null;
        });

        long rows = q(db -> db.sqlQueryLong("SELECT COUNT(*) FROM ops_profiling_snapshots WHERE ts = ?", ts));
        assertEquals(4L, rows, "the long names no longer cost the snapshot");
        long distinctMethods = q(db -> db.sqlQueryLong(
            "SELECT COUNT(DISTINCT \"name\") FROM ops_profiling_snapshots WHERE \"type\" = 'method'"));
        assertEquals(2L, distinctMethods);
        long maxLen = q(db -> db.sqlQueryLong("SELECT MAX(LENGTH(\"name\")) FROM ops_profiling_snapshots"));
        assertEquals(255L, maxLen);
    }

    private <T> T q(java.util.function.Function<Database, T> fn) {
        var db = new Database(dbFactory.openSession());
        db.beginTransaction();
        try {
            T r = fn.apply(db);
            db.commitTransaction();
            return r;
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } finally {
            db.close();
        }
    }
}
