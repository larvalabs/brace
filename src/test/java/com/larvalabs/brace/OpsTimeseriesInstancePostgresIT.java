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
 * P3 on real Postgres: each instance writes its own instance-tagged {@code ops_timeseries} rows, the
 * widened primary key {@code (ts, metric, instance_id)} lets two instances record the same metric at
 * the same instant, and a reader can aggregate across the fleet or filter to one instance. The
 * H2-keyed {@code (ts, metric)} would reject the second instance's row — only real Postgres (with
 * V14) exercises this.
 */
class OpsTimeseriesInstancePostgresIT extends PostgresTestBase {

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
        truncate("ops_timeseries");
    }

    @Test
    void twoInstancesWriteSameMetricSameInstantUnderDistinctIds() {
        Timestamp ts = Timestamp.from(Instant.parse("2026-06-07T12:00:00Z"));

        // Same (ts, metric) from two instances — would violate the old (ts, metric) PK; the widened
        // key (ts, metric, instance_id) lets both coexist.
        q(db -> { Brace.insertMetrics(db, ts, "web-1:8080-aaaa", metric(10L)); return null; });
        q(db -> { Brace.insertMetrics(db, ts, "web-2:8080-bbbb", metric(7L)); return null; });

        // Per-instance breakdown.
        assertEquals(10L, valueFor(ts, "web-1:8080-aaaa"));
        assertEquals(7L, valueFor(ts, "web-2:8080-bbbb"));

        // Fleet aggregate at that timestamp.
        long total = q(db -> db.sqlQueryLong(
            "SELECT SUM(val) FROM ops_timeseries WHERE ts = ? AND metric = 'http.requests'", ts));
        assertEquals(17L, total, "reader sums across instances for the fleet total");

        // Two distinct instances are recorded.
        long instances = q(db -> db.sqlQueryLong(
            "SELECT COUNT(DISTINCT instance_id) FROM ops_timeseries"));
        assertEquals(2L, instances);
    }

    private static Map<String, Object> metric(long requests) {
        var m = new LinkedHashMap<String, Object>();
        m.put("http.requests", requests);
        return m;
    }

    private long valueFor(Timestamp ts, String instanceId) {
        return q(db -> db.sqlQueryLong(
            "SELECT val FROM ops_timeseries WHERE ts = ? AND metric = 'http.requests' AND instance_id = ?",
            ts, instanceId));
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
