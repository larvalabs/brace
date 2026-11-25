package com.larvalabs.brace;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Database load and health for {@code /ops/dashboard} and {@code /ops/status}.
 *
 * <p>Two sources, both portable:
 * <ul>
 *   <li><b>The connection pool</b> (any database): connections in use and threads waiting for
 *       one, read from HikariCP's in-memory counters once a second. Threads waiting means
 *       requests are queuing for the database.</li>
 *   <li><b>Postgres's own statistics views</b> ({@code pg_stat_activity}, {@code pg_stat_database},
 *       {@code pg_stat_io} on 16+, {@code pg_stat_user_tables}). Core views on every Postgres
 *       install, readable by an ordinary role: no extension, no superuser, nothing specific to
 *       how or where the server runs.</li>
 * </ul>
 *
 * <p>The load figure is sampled the way "active session history" tools do it: every
 * {@link #ACTIVITY_EVERY} seconds, count the sessions running a query and classify each by
 * what it is waiting on. No wait event means it is on CPU; {@code IO} means it is reading or
 * writing data files or WAL; {@code Lock} means blocked by another transaction's lock;
 * everything else (lightweight locks, IPC, client, timeouts) is "other". The per-minute average
 * of those counts is the database's load. On CPU above the server's core count means the
 * database is CPU-bound; mostly IO means it is disk-bound. Postgres cannot report its host's
 * CPU or disk utilisation through SQL, so this is the portable measure of database load.
 *
 * <p>A role that is not a superuser (and lacks {@code pg_read_all_stats}) sees the state and
 * wait events of its own sessions only; other roles' sessions are counted in
 * {@link Activity#hidden()} and in {@link Activity#connections()}, but not in the load.
 *
 * <p>Postgres is queried on one connection opened outside the pool
 * ({@link DatabaseFactory#openRawConnection()}), so the statistics stay readable when the pool
 * is exhausted, which is when they matter most. Each sample is one short read-only transaction
 * whose settings ({@code statement_timeout}, {@code application_name}) are {@code SET LOCAL},
 * so nothing outlives it even behind a transaction-pooling proxy. Samples are taken whether or
 * not anyone has the dashboard open, so the last hour is there when someone does.
 */
public final class DatabaseStats implements AutoCloseable {

    /** {@code application_name} of the sampling transaction; other instances' samples are excluded from the load. */
    static final String APPLICATION_NAME = "brace-ops-stats";
    /** Seconds between pool samples (the sampler's tick). */
    static final int TICK_SECONDS = 1;
    /** Ticks between {@code pg_stat_activity} samples. */
    static final int ACTIVITY_EVERY = 5;
    /** Ticks between {@code pg_stat_database} / {@code pg_stat_io} / table samples. */
    static final int COUNTERS_EVERY = 30;
    /** Ticks between {@code pg_database_size} calls, which stat every file of the database. */
    static final int SIZE_EVERY = 300;
    /** Minutes of load history kept, matching the request sparklines. */
    static final int MINUTES = 60;
    /** Tables listed. */
    static final int TABLES = 5;

    private final DatabaseFactory factory;
    private final boolean sampleServer;
    private ScheduledExecutorService executor;

    // Sampler-thread state.
    private Connection conn;
    private long tick;
    private DbCounters prevDb;
    private IoCounters prevIo;
    private long deadlockBase = -1;

    // Published state, guarded by lock.
    private final Object lock = new Object();
    private final ArrayDeque<LoadMinute> minutes = new ArrayDeque<>();
    private Bucket current;
    private String serverVersion;
    private int serverVersionNum;
    private Long sizeBytes;
    private Activity activity;
    private Throughput throughput;
    private Io io;
    private List<Table> tables = List.of();
    private Instant sampledAt;
    private String error;

    /**
     * @param sampleServer query Postgres's statistics views. Ignored (treated as false) when the
     *                     factory is not Postgres; the pool is sampled either way.
     */
    DatabaseStats(DatabaseFactory factory, boolean sampleServer) {
        this.factory = factory;
        this.sampleServer = sampleServer && factory.isPostgres();
    }

    /** Start the background sampler (one daemon thread). */
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "brace-db-stats");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                sample(Instant.now());
            } catch (Throwable t) {
                // An exception escaping a scheduled task cancels every later run.
                synchronized (lock) {
                    error = "sampler failed: " + Redactor.redactMessage(String.valueOf(t.getMessage()));
                }
            }
        }, 0, TICK_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Closing from here also unblocks a sampler stuck in a socket read.
        closeConnection();
    }

    /** True when Postgres's statistics views are being sampled. */
    public boolean samplesServer() {
        return sampleServer;
    }

    // ---------------------------------------------------------------- sampling

    /** One tick. Package-private so tests can drive the sampler without its thread or clock. */
    void sample(Instant now) {
        var pool = factory.poolStats();
        Activity act = null;
        String err = null;
        boolean sampledActivity = false;
        if (sampleServer && (tick % ACTIVITY_EVERY == 0 || tick % COUNTERS_EVERY == 0)) {
            sampledActivity = true;
            try {
                act = sampleServer(now);
            } catch (SQLException | RuntimeException e) {
                closeConnection();
                err = "Postgres statistics unavailable: " + Redactor.redactMessage(String.valueOf(e.getMessage()));
            }
        }
        tick++;
        synchronized (lock) {
            var minute = now.truncatedTo(ChronoUnit.MINUTES);
            if (current == null || !current.minute.equals(minute)) {
                if (current != null) {
                    minutes.addLast(current.toLoadMinute(false));
                    while (minutes.size() >= MINUTES) minutes.removeFirst(); // room for the current one
                }
                current = new Bucket(minute);
            }
            current.addPool(pool);
            if (act != null) current.addActivity(act);
            if (sampledActivity) {
                activity = act;
                error = err;
                sampledAt = now;
            }
        }
    }

    /** Query the statistics views in one read-only transaction. Returns the activity sample. */
    private Activity sampleServer(Instant now) throws SQLException {
        var c = connection();
        try {
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL statement_timeout = '3s'");
                st.execute("SET LOCAL lock_timeout = '1s'");
                st.execute("SET LOCAL application_name = '" + APPLICATION_NAME + "'");
            }
            var act = queryActivity(c);
            if (tick % COUNTERS_EVERY == 0) {
                sampleCounters(c, now);
            }
            c.commit();
            return act;
        } catch (SQLException | RuntimeException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // The connection is closed by the caller.
            }
            throw e;
        }
    }

    private Connection connection() throws SQLException {
        if (conn == null) {
            var c = factory.openRawConnection();
            try {
                c.setAutoCommit(false);
                c.setReadOnly(true);
                // Bounds a read on a dead socket; statement_timeout bounds a slow query.
                c.setNetworkTimeout(Runnable::run, 10_000);
            } catch (SQLException | RuntimeException e) {
                c.close();
                throw e;
            }
            conn = c;
        }
        return conn;
    }

    private void closeConnection() {
        var c = conn;
        conn = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // Already broken; nothing to release.
            }
        }
    }

    // Without superuser or pg_read_all_stats, another role's session shows only its pid, user and
    // database: backend_type, state and wait events are NULL. So the load counts visible client
    // backends; "hidden" counts the rest that are connected to a database; and the connection
    // total comes from pg_stat_database.numbackends, which every role sees in full.
    static final String ACTIVITY_SQL = """
        SELECT
          count(*) FILTER (WHERE client AND state = 'active' AND wait_event_type IS NULL),
          count(*) FILTER (WHERE client AND state = 'active' AND wait_event_type = 'IO'),
          count(*) FILTER (WHERE client AND state = 'active' AND wait_event_type = 'Lock'),
          count(*) FILTER (WHERE client AND state = 'active' AND wait_event_type NOT IN ('IO', 'Lock')),
          count(*) FILTER (WHERE client AND state IN ('idle in transaction', 'idle in transaction (aborted)')),
          (SELECT coalesce(sum(numbackends), 0) FROM pg_stat_database)::int,
          count(*) FILTER (WHERE backend_type IS NULL AND datname IS NOT NULL),
          coalesce(extract(epoch FROM max(clock_timestamp() - query_start) FILTER (WHERE client AND state = 'active')), 0)::float8,
          coalesce(extract(epoch FROM max(clock_timestamp() - state_change)
            FILTER (WHERE client AND state IN ('idle in transaction', 'idle in transaction (aborted)'))), 0)::float8,
          current_setting('max_connections')::int
        FROM (
          SELECT *, backend_type = 'client backend' AND application_name IS DISTINCT FROM '%s' AS client
          FROM pg_stat_activity
          WHERE pid <> pg_backend_pid()
        ) a
        """.formatted(APPLICATION_NAME);

    private static Activity queryActivity(Connection c) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery(ACTIVITY_SQL)) {
            rs.next();
            return new Activity(rs.getInt(6), rs.getInt(10), rs.getInt(1), rs.getInt(2), rs.getInt(3),
                rs.getInt(4), rs.getInt(5), rs.getInt(7), rs.getDouble(8), rs.getDouble(9));
        }
    }

    static final String DATABASE_SQL = """
        SELECT xact_commit, xact_rollback, blks_read, blks_hit, temp_bytes, deadlocks,
               current_setting('server_version'), current_setting('server_version_num')::int
        FROM pg_stat_database WHERE datname = current_database()
        """;

    // reads/writes are I/O operations; read_time/write_time are milliseconds, and stay 0 unless
    // track_io_timing is on. The columns used here exist in pg_stat_io from 16 on.
    static final String IO_SQL = """
        SELECT coalesce(sum(reads), 0)::bigint, coalesce(sum(writes), 0)::bigint,
               coalesce(sum(read_time), 0)::float8, coalesce(sum(write_time), 0)::float8,
               current_setting('track_io_timing') = 'on'
        FROM pg_stat_io
        """;

    static final String TABLES_SQL = """
        SELECT schemaname, relname, seq_scan, seq_tup_read, coalesce(idx_scan, 0),
               n_live_tup, n_dead_tup, greatest(last_autovacuum, last_vacuum)
        FROM pg_stat_user_tables
        ORDER BY seq_tup_read DESC, relname
        LIMIT %d
        """.formatted(TABLES);

    /** The cumulative-counter views, turned into rates against the previous sample. */
    private void sampleCounters(Connection c, Instant now) throws SQLException {
        DbCounters db;
        String version;
        int versionNum;
        try (var st = c.createStatement(); var rs = st.executeQuery(DATABASE_SQL)) {
            if (!rs.next()) throw new SQLException("current database missing from pg_stat_database");
            db = new DbCounters(now, rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6));
            version = rs.getString(7);
            versionNum = rs.getInt(8);
        }
        Throughput tp = null;
        if (deadlockBase < 0 || db.deadlocks < deadlockBase) deadlockBase = db.deadlocks;
        if (prevDb != null) tp = DbCounters.rates(prevDb, db, deadlockBase);
        prevDb = db;

        Io ioRates = null;
        if (versionNum >= 160000) {
            IoCounters ioNow;
            try (var st = c.createStatement(); var rs = st.executeQuery(IO_SQL)) {
                rs.next();
                ioNow = new IoCounters(now, rs.getLong(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4), rs.getBoolean(5));
            }
            if (prevIo != null) ioRates = IoCounters.rates(prevIo, ioNow);
            prevIo = ioNow;
        }

        Long size = null;
        if (tick % SIZE_EVERY == 0) {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT pg_database_size(current_database())")) {
                rs.next();
                size = rs.getLong(1);
            }
        }

        var tbls = new ArrayList<Table>();
        try (var st = c.createStatement(); var rs = st.executeQuery(TABLES_SQL)) {
            while (rs.next()) tbls.add(table(rs));
        }

        synchronized (lock) {
            serverVersion = version == null ? null : version.split(" ", 2)[0];
            serverVersionNum = versionNum;
            if (tp != null || throughput == null) throughput = tp;
            if (versionNum < 160000) io = null;
            else if (ioRates != null) io = ioRates;
            if (size != null) sizeBytes = size;
            tables = List.copyOf(tbls);
        }
    }

    private static Table table(ResultSet rs) throws SQLException {
        String schema = rs.getString(1);
        String name = "public".equals(schema) ? rs.getString(2) : schema + "." + rs.getString(2);
        Timestamp vac = rs.getTimestamp(8);
        return new Table(name, rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
            vac == null ? null : vac.toInstant());
    }

    // ---------------------------------------------------------------- reading

    /** Everything the dashboard shows, consistent as of one moment. The pool is read live. */
    public Snapshot snapshot() {
        var pool = factory.poolStats();
        synchronized (lock) {
            var load = new ArrayList<LoadMinute>(minutes);
            if (current != null) load.add(current.toLoadMinute(true));
            return new Snapshot(pool, sampleServer, serverVersion, serverVersionNum, sizeBytes, activity,
                throughput, io, tables, List.copyOf(load), sampledAt, error);
        }
    }

    /**
     * Everything the dashboard shows. {@code load} is oldest first, at most {@link #MINUTES}
     * entries, the last one the minute in progress. With {@code postgres} false only
     * {@code pool} and the pool figures of {@code load} are filled.
     */
    public record Snapshot(DatabaseFactory.PoolStats pool, boolean postgres, String serverVersion,
                           int serverVersionNum, Long sizeBytes, Activity activity, Throughput throughput,
                           Io io, List<Table> tables, List<LoadMinute> load, Instant sampledAt, String error) {}

    /**
     * One {@code pg_stat_activity} sample. {@code connections} counts the backends connected to
     * any database on the server, every role's (from {@code pg_stat_database.numbackends}, so
     * it includes the pool, this sampler and autovacuum workers), to compare with
     * {@code maxConnections}. {@code cpu}/{@code io}/{@code lock}/{@code other} are the visible
     * client sessions running a query, by what they wait on; {@code hidden} are sessions whose
     * state this role may not see (other roles', without {@code pg_read_all_stats}). Ages are in
     * seconds.
     */
    public record Activity(int connections, int maxConnections, int cpu, int io, int lock, int other,
                           int idleInTransaction, int hidden, double longestQuerySeconds,
                           double longestIdleInTransactionSeconds) {
        public int active() {
            return cpu + io + lock + other;
        }
    }

    /** Per-second rates over the last counter interval. {@code deadlocks} counts since sampling began. */
    public record Throughput(double commitsPerSecond, double rollbacksPerSecond, Double cacheHitPct,
                             double tempBytesPerSecond, long deadlocks, double windowSeconds) {}

    /**
     * Server-wide I/O operations per second from {@code pg_stat_io} (Postgres 16+). Reads are of
     * blocks not in Postgres's buffer cache, so served by the OS page cache or the disk. The
     * average times are null unless {@code track_io_timing} is on.
     */
    public record Io(double readsPerSecond, double writesPerSecond, Double avgReadMs, Double avgWriteMs) {}

    /** One table's cumulative scan counts and dead rows, since the server's statistics were last reset. */
    public record Table(String name, long seqScans, long seqRowsRead, long indexScans, long liveRows,
                        long deadRows, Instant lastVacuum) {}

    /**
     * One minute of samples. Session figures are averages over the minute's activity samples
     * ({@code sessionSamples} of them; zero off Postgres): average active sessions by wait class.
     * Pool figures are the peaks of the once-a-second pool samples.
     */
    public record LoadMinute(Instant minute, boolean partial, int sessionSamples, double cpu, double io,
                             double lock, double other, int poolActivePeak, int poolWaitingPeak, int poolMax) {
        public double total() {
            return cpu + io + lock + other;
        }
    }

    // ---------------------------------------------------------------- internals

    private static final class Bucket {
        final Instant minute;
        int sessionSamples;
        long cpu, io, lock, other;
        int poolActivePeak, poolWaitingPeak, poolMax;

        Bucket(Instant minute) {
            this.minute = minute;
        }

        void addPool(DatabaseFactory.PoolStats pool) {
            if (pool == null) return;
            poolActivePeak = Math.max(poolActivePeak, pool.active());
            poolWaitingPeak = Math.max(poolWaitingPeak, pool.waiting());
            poolMax = pool.max();
        }

        void addActivity(Activity a) {
            sessionSamples++;
            cpu += a.cpu();
            io += a.io();
            lock += a.lock();
            other += a.other();
        }

        LoadMinute toLoadMinute(boolean partial) {
            double n = Math.max(1, sessionSamples);
            return new LoadMinute(minute, partial, sessionSamples, cpu / n, io / n, lock / n, other / n,
                poolActivePeak, poolWaitingPeak, poolMax);
        }
    }

    record DbCounters(Instant at, long commits, long rollbacks, long blocksRead, long blocksHit,
                      long tempBytes, long deadlocks) {
        /** Null when the interval is empty or a counter went backwards (statistics reset). */
        static Throughput rates(DbCounters a, DbCounters b, long deadlockBase) {
            double secs = Duration.between(a.at, b.at).toMillis() / 1000.0;
            long commits = b.commits - a.commits, rollbacks = b.rollbacks - a.rollbacks;
            long read = b.blocksRead - a.blocksRead, hit = b.blocksHit - a.blocksHit;
            long temp = b.tempBytes - a.tempBytes;
            if (secs <= 0 || commits < 0 || rollbacks < 0 || read < 0 || hit < 0 || temp < 0) return null;
            Double hitPct = read + hit > 0 ? hit * 100.0 / (read + hit) : null;
            return new Throughput(commits / secs, rollbacks / secs, hitPct, temp / secs,
                b.deadlocks - deadlockBase, secs);
        }
    }

    record IoCounters(Instant at, long reads, long writes, double readMs, double writeMs, boolean timing) {
        static Io rates(IoCounters a, IoCounters b) {
            double secs = Duration.between(a.at, b.at).toMillis() / 1000.0;
            long reads = b.reads - a.reads, writes = b.writes - a.writes;
            if (secs <= 0 || reads < 0 || writes < 0) return null;
            boolean timed = a.timing && b.timing;
            Double readMs = timed && reads > 0 ? (b.readMs - a.readMs) / reads : null;
            Double writeMs = timed && writes > 0 ? (b.writeMs - a.writeMs) / writes : null;
            return new Io(reads / secs, writes / secs, readMs, writeMs);
        }
    }

    // ---------------------------------------------------------------- /ops/status

    /**
     * The {@code db} block of {@code /ops/status}: the pool, the last full minute of request
     * queries (from {@code stats}), and the Postgres figures when they are sampled.
     */
    static Map<String, Object> statusJson(Snapshot s, Stats.MinuteSnapshot lastMinute) {
        var db = new LinkedHashMap<String, Object>();
        if (s.pool() != null) {
            var p = new LinkedHashMap<String, Object>();
            p.put("active", s.pool().active());
            p.put("idle", s.pool().idle());
            p.put("total", s.pool().total());
            p.put("max", s.pool().max());
            p.put("waiting", s.pool().waiting());
            db.put("pool", p);
        }
        if (lastMinute != null) {
            var q = new LinkedHashMap<String, Object>();
            q.put("lastMinute", lastMinute.queries());
            q.put("avgMs", lastMinute.queries() > 0
                ? round2(lastMinute.queryUs() / 1000.0 / lastMinute.queries()) : 0.0);
            db.put("requestQueries", q);
        }
        if (s.postgres()) {
            var pg = new LinkedHashMap<String, Object>();
            pg.put("version", s.serverVersion());
            if (s.sizeBytes() != null) pg.put("sizeBytes", s.sizeBytes());
            if (s.sampledAt() != null) pg.put("sampledAt", s.sampledAt().toString());
            if (s.error() != null) pg.put("error", s.error());
            var a = s.activity();
            if (a != null) {
                var act = new LinkedHashMap<String, Object>();
                act.put("connections", a.connections());
                act.put("maxConnections", a.maxConnections());
                act.put("active", a.active());
                act.put("onCpu", a.cpu());
                act.put("waitingIo", a.io());
                act.put("waitingLock", a.lock());
                act.put("waitingOther", a.other());
                act.put("idleInTransaction", a.idleInTransaction());
                act.put("hidden", a.hidden());
                act.put("longestQuerySeconds", round2(a.longestQuerySeconds()));
                act.put("longestIdleInTransactionSeconds", round2(a.longestIdleInTransactionSeconds()));
                pg.put("activity", act);
            }
            LoadMinute last = null;
            for (var m : s.load()) if (!m.partial() && m.sessionSamples() > 0) last = m;
            if (last != null) {
                var load = new LinkedHashMap<String, Object>();
                load.put("minute", last.minute().toString());
                load.put("avgActive", round2(last.total()));
                load.put("onCpu", round2(last.cpu()));
                load.put("waitingIo", round2(last.io()));
                load.put("waitingLock", round2(last.lock()));
                load.put("waitingOther", round2(last.other()));
                pg.put("load", load);
            }
            var t = s.throughput();
            if (t != null) {
                var tp = new LinkedHashMap<String, Object>();
                tp.put("commitsPerSecond", round2(t.commitsPerSecond()));
                tp.put("rollbacksPerSecond", round2(t.rollbacksPerSecond()));
                tp.put("cacheHitPct", t.cacheHitPct() == null ? null : round2(t.cacheHitPct()));
                tp.put("tempBytesPerSecond", Math.round(t.tempBytesPerSecond()));
                tp.put("deadlocks", t.deadlocks());
                pg.put("throughput", tp);
            }
            var io = s.io();
            if (io != null) {
                var i = new LinkedHashMap<String, Object>();
                i.put("readsPerSecond", round2(io.readsPerSecond()));
                i.put("writesPerSecond", round2(io.writesPerSecond()));
                i.put("avgReadMs", io.avgReadMs() == null ? null : round2(io.avgReadMs()));
                i.put("avgWriteMs", io.avgWriteMs() == null ? null : round2(io.avgWriteMs()));
                pg.put("io", i);
            }
            if (!s.tables().isEmpty()) {
                var list = new ArrayList<Map<String, Object>>();
                for (var tb : s.tables()) {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("table", tb.name());
                    m.put("seqScans", tb.seqScans());
                    m.put("seqRowsRead", tb.seqRowsRead());
                    m.put("indexScans", tb.indexScans());
                    m.put("liveRows", tb.liveRows());
                    m.put("deadRows", tb.deadRows());
                    m.put("lastVacuum", tb.lastVacuum() == null ? null : tb.lastVacuum().toString());
                    list.add(m);
                }
                pg.put("tables", list);
            }
            db.put("postgres", pg);
        }
        return db;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
