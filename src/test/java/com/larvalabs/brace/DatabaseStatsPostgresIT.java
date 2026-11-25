package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DatabaseStats} against real Postgres, as an ordinary (non-superuser) role would see it:
 * sessions waiting on a lock, idle in a transaction and sleeping are classified; the counter
 * views turn into rates on the second sample; a dropped connection is reported and replaced.
 */
class DatabaseStatsPostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;
    Instant clock = Instant.parse("2026-11-25T10:00:00Z");

    @BeforeAll
    static void setUp() throws Exception {
        dbFactory = new DatabaseFactory(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
        try (var c = connect(); var st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS dbstats_scan");
            st.execute("CREATE TABLE dbstats_scan (id int PRIMARY KEY, v int NOT NULL)");
            st.execute("INSERT INTO dbstats_scan SELECT g, 0 FROM generate_series(1, 50000) g");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        try (var c = connect(); var st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS dbstats_scan");
        }
        if (dbFactory != null) dbFactory.close();
    }

    /** {@code n} sampler ticks, a second apart. */
    void ticks(DatabaseStats stats, int n) {
        for (int i = 0; i < n; i++) {
            stats.sample(clock);
            clock = clock.plusSeconds(DatabaseStats.TICK_SECONDS);
        }
    }

    @Test
    void classifiesLockWaitsIdleTransactionsAndOtherWaits() throws Exception {
        try (Connection holder = connect(); Connection blocked = connect(); Connection sleeper = connect();
             var stats = new DatabaseStats(dbFactory, true)) {
            holder.setAutoCommit(false);
            try (var st = holder.createStatement()) {
                st.executeUpdate("UPDATE dbstats_scan SET v = 1 WHERE id = 1"); // holds the row lock, then sits idle
            }
            var blockedThread = Thread.ofVirtual().start(() -> {
                try (var st = blocked.createStatement()) {
                    st.executeUpdate("UPDATE dbstats_scan SET v = 2 WHERE id = 1");
                } catch (Exception ignored) {
                    // Released below.
                }
            });
            var sleeperThread = Thread.ofVirtual().start(() -> {
                try (var st = sleeper.createStatement()) {
                    st.execute("SELECT pg_sleep(30)");
                } catch (Exception ignored) {
                    // Cancelled below.
                }
            });
            awaitSessions("wait_event_type = 'Lock'", 1);
            awaitSessions("wait_event = 'PgSleep'", 1);

            ticks(stats, 1);
            var snap = stats.snapshot();
            assertNull(snap.error());
            var a = snap.activity();
            assertNotNull(a);
            assertEquals(1, a.lock(), "the blocked UPDATE");
            assertTrue(a.other() >= 1, "pg_sleep waits on a timeout: " + a);
            assertEquals(1, a.idleInTransaction(), "the lock holder");
            assertTrue(a.longestIdleInTransactionSeconds() > 0);
            assertTrue(a.longestQuerySeconds() > 0);
            assertTrue(a.connections() >= 3, a.toString());
            assertTrue(a.maxConnections() > 0);
            assertTrue(snap.serverVersionNum() >= 160000, "the IT container is Postgres 16");
            assertTrue(snap.serverVersion().startsWith("16"), snap.serverVersion());
            assertTrue(snap.sizeBytes() > 0);
            assertEquals(1, snap.load().getLast().sessionSamples());
            assertEquals(1.0, snap.load().getLast().lock());

            holder.rollback();
            blockedThread.join(TimeUnit.SECONDS.toMillis(10));
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE wait_event = 'PgSleep'");
            }
            sleeperThread.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    /**
     * The IT's own role is the container's superuser, which sees every session. An ordinary role
     * sees only its own sessions' state: another role's busy session is not load, but it is
     * counted as hidden and as a connection.
     */
    @Test
    void anotherRolesSessionsAreHiddenButCounted() throws Exception {
        try (var c = connect(); var st = c.createStatement()) {
            st.execute("DROP ROLE IF EXISTS dbstats_reader");
            st.execute("CREATE ROLE dbstats_reader LOGIN PASSWORD 'reader'");
        }
        try (Connection sleeper = connect();
             Connection reader = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "dbstats_reader", "reader")) {
            var sleeperThread = Thread.ofVirtual().start(() -> {
                try (var st = sleeper.createStatement()) {
                    st.execute("SELECT pg_sleep(30)");
                } catch (Exception ignored) {
                    // Cancelled below.
                }
            });
            awaitSessions("wait_event = 'PgSleep'", 1);
            try (var st = reader.createStatement(); var rs = st.executeQuery(DatabaseStats.ACTIVITY_SQL)) {
                rs.next();
                assertEquals(0, rs.getInt(4), "another role's sleeping query is not visible as load");
                assertTrue(rs.getInt(7) >= 1, "it is counted as hidden");
                assertTrue(rs.getInt(6) >= 2, "and in the connection total (it and the reader)");
            }
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE wait_event = 'PgSleep'");
            }
            sleeperThread.join(TimeUnit.SECONDS.toMillis(10));
        } finally {
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("DROP ROLE IF EXISTS dbstats_reader");
            }
        }
    }

    @Test
    void ownSamplingSessionIsNotCountedAsLoad() {
        try (var stats = new DatabaseStats(dbFactory, true)) {
            ticks(stats, 1);
            var a = stats.snapshot().activity();
            assertNotNull(a);
            assertEquals(0, a.cpu(), "the sampler's own query is excluded: " + a);
        }
    }

    @Test
    void countersBecomeRatesOnTheSecondSampleAndTablesAreListed() throws Exception {
        try (var stats = new DatabaseStats(dbFactory, true)) {
            ticks(stats, 1);
            assertNull(stats.snapshot().throughput(), "one sample is not a rate");
            try (var c = connect(); var st = c.createStatement()) {
                for (int i = 0; i < 5; i++) st.executeQuery("SELECT count(*) FROM dbstats_scan WHERE v = 7").close();
            }
            // Backends publish their table counters at most once a second.
            Thread.sleep(1500);
            ticks(stats, DatabaseStats.COUNTERS_EVERY);
            var snap = stats.snapshot();
            assertNull(snap.error());
            var t = snap.throughput();
            assertNotNull(t);
            assertTrue(t.commitsPerSecond() > 0, t.toString());
            assertEquals(30, t.windowSeconds(), 1e-9);
            assertNotNull(snap.io(), "pg_stat_io on 16+");
            var scan = snap.tables().stream().filter(tb -> tb.name().equals("dbstats_scan")).findFirst();
            assertTrue(scan.isPresent(), "the most-scanned table is listed: " + snap.tables());
            assertTrue(scan.get().seqRowsRead() >= 250_000, scan.get().toString());
            assertTrue(scan.get().liveRows() > 0 || scan.get().seqScans() > 0);
        }
    }

    @Test
    void aDroppedConnectionIsReportedThenReplaced() throws Exception {
        try (var stats = new DatabaseStats(dbFactory, true)) {
            ticks(stats, 1);
            assertNull(stats.snapshot().error());
            // Kill every other session of this role: the sampler's (and the pool's idle ones).
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE usename = current_user AND pid <> pg_backend_pid() AND backend_type = 'client backend'");
            }
            ticks(stats, DatabaseStats.ACTIVITY_EVERY);
            var failed = stats.snapshot();
            assertNotNull(failed.error());
            assertTrue(failed.error().startsWith("Postgres statistics unavailable: "), failed.error());
            assertNotNull(failed.pool(), "the pool figures don't depend on the stats connection");

            ticks(stats, DatabaseStats.ACTIVITY_EVERY);
            var recovered = stats.snapshot();
            assertNull(recovered.error(), "a new connection on the next sample");
            assertNotNull(recovered.activity());
        }
    }

    @Test
    void statusJsonCarriesThePostgresBlock() {
        try (var stats = new DatabaseStats(dbFactory, true)) {
            ticks(stats, 1);
            var json = DatabaseStats.statusJson(stats.snapshot(), null);
            @SuppressWarnings("unchecked")
            var pg = (java.util.Map<String, Object>) json.get("postgres");
            assertNotNull(pg);
            assertNotNull(pg.get("activity"));
            assertNull(pg.get("error"));
            assertNotNull(json.get("pool"));
        }
    }

    private static void awaitSessions(String condition, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var c = connect(); var st = c.createStatement()) {
            while (true) {
                try (var rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE " + condition)) {
                    rs.next();
                    if (rs.getInt(1) >= count) return;
                }
                if (System.nanoTime() > deadline) fail("no session matched " + condition);
                Thread.sleep(20);
            }
        }
    }
}
