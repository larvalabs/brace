package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** {@link DatabaseStats} on H2: the pool figures, minute buckets and counter arithmetic. Postgres: {@code DatabaseStatsPostgresIT}. */
class DatabaseStatsTest {

    static DatabaseFactory factory;

    @BeforeAll
    static void open() {
        factory = new DatabaseFactory("jdbc:h2:mem:dbstats" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
            null, null, List.of(), 2);
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void poolStatsReadHikari() {
        var pool = factory.poolStats();
        assertNotNull(pool);
        assertEquals(2, pool.max());
        assertEquals(0, pool.waiting());
        assertEquals(pool.total(), pool.active() + pool.idle());
    }

    @Test
    void poolStatsCountThreadsWaitingForAConnection() throws Exception {
        var held = new java.util.ArrayList<Database>();
        var waiter = new CountDownLatch(1);
        Thread t = null;
        try {
            for (int i = 0; i < 2; i++) {
                var db = new Database(factory.openSession());
                db.beginTransaction(); // takes a connection from the pool
                held.add(db);
            }
            assertEquals(2, factory.poolStats().active());
            t = Thread.ofVirtual().start(() -> {
                factory.withSession(db -> { db.sqlQueryLong("SELECT 1"); });
                waiter.countDown();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (factory.poolStats().waiting() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, factory.poolStats().waiting(), "the third session queues for a connection");
        } finally {
            for (var db : held) {
                db.rollbackTransaction();
                db.close();
            }
        }
        assertTrue(waiter.await(10, TimeUnit.SECONDS), "the waiter gets a connection once one is released");
        t.join();
        assertEquals(0, factory.poolStats().waiting());
    }

    @Test
    void offPostgresOnlyThePoolIsSampled() {
        var stats = new DatabaseStats(factory, true);
        assertFalse(stats.samplesServer(), "H2 has no pg_stat views");
        var t0 = Instant.parse("2026-11-25T10:00:30Z");
        stats.sample(t0);
        stats.sample(t0.plusSeconds(1));
        var snap = stats.snapshot();
        assertFalse(snap.postgres());
        assertNotNull(snap.pool());
        assertNull(snap.activity());
        assertNull(snap.error());
        assertEquals(1, snap.load().size());
        var m = snap.load().getFirst();
        assertTrue(m.partial());
        assertEquals(0, m.sessionSamples());
        assertEquals(2, m.poolMax());
        stats.close();
    }

    @Test
    void minutesRollOverAndKeepAnHour() {
        var stats = new DatabaseStats(factory, false);
        var t0 = Instant.parse("2026-11-25T10:00:00Z");
        stats.sample(t0);
        stats.sample(t0.plusSeconds(59));
        stats.sample(t0.plusSeconds(60));
        var load = stats.snapshot().load();
        assertEquals(2, load.size());
        assertFalse(load.get(0).partial());
        assertEquals(t0, load.get(0).minute());
        assertTrue(load.get(1).partial());

        for (int i = 2; i < 200; i++) stats.sample(t0.plusSeconds(60L * i));
        load = stats.snapshot().load();
        assertEquals(DatabaseStats.MINUTES, load.size(), "59 complete minutes plus the current one");
        assertEquals(t0.plusSeconds(60L * 199), load.getLast().minute());
        assertTrue(load.getLast().partial());
        assertEquals(t0.plusSeconds(60L * 140), load.getFirst().minute());
    }

    @Test
    void counterRates() {
        var t0 = Instant.parse("2026-11-25T10:00:00Z");
        var a = new DatabaseStats.DbCounters(t0, 1000, 10, 100, 900, 0, 2);
        var b = new DatabaseStats.DbCounters(t0.plusSeconds(30), 1300, 13, 110, 1890, 3072, 3);
        var r = DatabaseStats.DbCounters.rates(a, b, 2);
        assertEquals(10.0, r.commitsPerSecond(), 1e-9);
        assertEquals(0.1, r.rollbacksPerSecond(), 1e-9);
        assertEquals(99.0, r.cacheHitPct(), 1e-9);
        assertEquals(102.4, r.tempBytesPerSecond(), 1e-9);
        assertEquals(1, r.deadlocks());
        assertEquals(30, r.windowSeconds(), 1e-9);

        // No block access in the window: no hit ratio rather than 0% or 100%.
        assertNull(DatabaseStats.DbCounters.rates(a, new DatabaseStats.DbCounters(t0.plusSeconds(30), 1000, 10, 100, 900, 0, 2), 2).cacheHitPct());
        // A statistics reset makes counters go backwards: no rate for that interval.
        assertNull(DatabaseStats.DbCounters.rates(b, a, 2));

        var io0 = new DatabaseStats.IoCounters(t0, 100, 50, 20.0, 0.0, true);
        var io1 = new DatabaseStats.IoCounters(t0.plusSeconds(10), 300, 60, 120.0, 0.0, true);
        var io = DatabaseStats.IoCounters.rates(io0, io1);
        assertEquals(20.0, io.readsPerSecond(), 1e-9);
        assertEquals(1.0, io.writesPerSecond(), 1e-9);
        assertEquals(0.5, io.avgReadMs(), 1e-9);
        assertEquals(0.0, io.avgWriteMs(), 1e-9);
        var untimed = DatabaseStats.IoCounters.rates(
            new DatabaseStats.IoCounters(t0, 100, 50, 0, 0, false), new DatabaseStats.IoCounters(t0.plusSeconds(10), 300, 60, 0, 0, false));
        assertNull(untimed.avgReadMs(), "no average without track_io_timing");
    }

    @Test
    void statusJsonShape() throws Exception {
        var t0 = Instant.parse("2026-11-25T10:00:00Z");
        var load = List.of(
            new DatabaseStats.LoadMinute(t0, false, 12, 1.5, 0.25, 0, 0.25, 3, 0, 10),
            new DatabaseStats.LoadMinute(t0.plusSeconds(60), true, 2, 9, 9, 9, 9, 1, 0, 10));
        var snap = new DatabaseStats.Snapshot(new DatabaseFactory.PoolStats(2, 8, 10, 10, 0), true, "16.4", 160004,
            1024L, new DatabaseStats.Activity(5, 100, 1, 0, 0, 0, 1, 0, 0.123, 2), new DatabaseStats.Throughput(1, 0, null, 0, 0, 30),
            null, List.of(new DatabaseStats.Table("users", 1, 2, 3, 4, 5, null)), load, t0, null);
        var stats = new Stats();
        stats.recordRequestPattern("GET", "/a", 200, 1000, 3, 1500);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
            DatabaseStats.statusJson(snap, stats.snapshot()));
        assertEquals(2, json.at("/pool/active").asInt());
        assertEquals(3, json.at("/requestQueries/lastMinute").asInt());
        assertEquals(0.5, json.at("/requestQueries/avgMs").asDouble());
        assertEquals("16.4", json.at("/postgres/version").asText());
        assertEquals(1, json.at("/postgres/activity/active").asInt());
        assertEquals(0.12, json.at("/postgres/activity/longestQuerySeconds").asDouble());
        assertEquals(2.0, json.at("/postgres/load/avgActive").asDouble(), "the last complete minute, not the partial one");
        assertTrue(json.at("/postgres/throughput/cacheHitPct").isNull());
        assertTrue(json.at("/postgres/io").isMissingNode(), "pg_stat_io absent below 16 or before its second sample");
        assertEquals("users", json.at("/postgres/tables/0/table").asText());
        assertEquals(1, json.at("/pool/recent/minutes").asInt(), "complete minutes only");
        assertEquals(0, json.at("/pool/recent/minutesWithWaits").asInt());
        assertEquals(2.0, json.at("/postgres/loadRecent/avgActive").asDouble());
    }

    /** The server's db block, as brace check reads it: the two must agree on field names. */
    @Test
    void braceCheckReadsTheStatusJsonThisClassWrites() {
        var t0 = Instant.parse("2026-11-25T10:00:00Z");
        var load = new java.util.ArrayList<DatabaseStats.LoadMinute>();
        for (int i = 0; i < 5; i++) {
            load.add(new DatabaseStats.LoadMinute(t0.plusSeconds(60L * i), false, 12, 6, 1, 1.5, 0.5, 10, i % 2 == 0 ? 3 : 0, 10));
        }
        var snap = new DatabaseStats.Snapshot(new DatabaseFactory.PoolStats(10, 0, 10, 10, 2), true, "16.4", 160004, null,
            new DatabaseStats.Activity(95, 100, 6, 1, 1, 1, 0, 0, 3, 0), null, null, List.of(), load, t0, null);
        var status = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
            Map.of("db", DatabaseStats.statusJson(snap, null)));
        var check = CliCheck.checkDatabase(status, CheckThresholds.fromConfig(Map.of("check.db_max_load", "8")), "prod");
        assertEquals("warn", check.status());
        assertEquals("Database: requests waited for a database connection in 3 of the last 5 min (peak 3 waiting, pool of 10); "
            + "1.5 sessions blocked on locks on average over 5 min; "
            + "database load 9.0 active sessions over 5 min (cpu 6.0, io 1.0, lock 1.5, other 0.5); "
            + "95/100 database connections in use", check.message());
    }

    @Test
    void recentSummariesCoverTheLastFiveCompleteMinutes() {
        var t0 = Instant.parse("2026-11-25T10:00:00Z");
        var load = new java.util.ArrayList<DatabaseStats.LoadMinute>();
        // Seven complete minutes: the first two (heavy, with waits) fall outside the window.
        for (int i = 0; i < 7; i++) {
            boolean old = i < 2;
            load.add(new DatabaseStats.LoadMinute(t0.plusSeconds(60L * i), false, 12,
                old ? 9 : 1, 0, i == 6 ? 1 : 0, 0, old ? 10 : 4, old ? 6 : (i == 5 ? 2 : 0), 10));
        }
        load.add(new DatabaseStats.LoadMinute(t0.plusSeconds(420), true, 3, 50, 0, 0, 0, 10, 9, 10));
        var snap = new DatabaseStats.Snapshot(new DatabaseFactory.PoolStats(2, 8, 10, 10, 0), true, "16.4", 160004,
            null, null, null, null, List.of(), load, t0, null);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(DatabaseStats.statusJson(snap, null));
        assertEquals(5, json.at("/pool/recent/minutes").asInt());
        assertEquals(1, json.at("/pool/recent/minutesWithWaits").asInt());
        assertEquals(2, json.at("/pool/recent/peakWaiting").asInt());
        assertEquals(4, json.at("/pool/recent/peakActive").asInt());
        assertEquals(5, json.at("/postgres/loadRecent/minutes").asInt());
        assertEquals(1.2, json.at("/postgres/loadRecent/avgActive").asDouble(), 1e-9);
        assertEquals(0.2, json.at("/postgres/loadRecent/waitingLock").asDouble(), 1e-9);
    }
}
