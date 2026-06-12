package benchmark;

import com.larvalabs.brace.*;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Job-queue claim-latency benchmark (H3 — see
 * docs/2026-06-11-runtime-performance-review-todos.md, Benchmarks gap #4).
 *
 * Seeds scheduled_jobs with a large dead-row history (completed/failed rows, oldest-first
 * in run_at order — exactly the prefix the pre-H3 claim query walks on every poll), then
 * measures:
 *   1. Empty poll  — pollAndExecute with zero pending jobs: the steady-state cost every
 *                    instance pays every 10 seconds, forever.
 *   2. Job stats   — getDurableJobStats, paid per /ops/status render.
 *   3. Drain       — enqueue PENDING_JOBS no-op jobs and poll until done. Batch sizes
 *                    differ pre/post H4 (50 vs poolSize/2), so this line is indicative,
 *                    not apples-to-apples; the claim-cost signal is lines 1 and 2.
 *
 * Run via run-jobs-claim.sh. Compare jars built against pre-H3 and post-H4 framework
 * versions (BENCH_JAR override, same procedure as run-session.sh — verify JobPoller.class
 * differs between the two shaded jars).
 */
public class JobsBench {

    /** Executed during the drain phase; must be a no-op so drain time ≈ claim+dispatch time. */
    public static class NoopJob implements DurableJob {
        public NoopJob() {}
        @Override public String data() { return null; }
        @Override public void run(String data, Database db) {}
    }

    static final int SEED_DEAD = Integer.parseInt(env("SEED_DEAD", "1000000"));
    static final int PENDING_JOBS = Integer.parseInt(env("PENDING_JOBS", "1000"));
    static final int EMPTY_POLL_WARMUP = 5;
    static final int EMPTY_POLL_ITERS = 30;
    static final int STATS_WARMUP = 3;
    static final int STATS_ITERS = 20;

    static final String URL = env("JOBS_DB_URL", "jdbc:postgresql://localhost:5433/jobs_bench");
    static final String USER = env("JOBS_DB_USER", "benchmarkdbuser");
    static final String PASS = env("JOBS_DB_PASS", "benchmarkdbpass");

    public static void main(String[] args) throws Exception {
        System.out.println("Jobs claim-latency benchmark");
        System.out.println("  url=" + URL + " seedDead=" + SEED_DEAD + " pending=" + PENDING_JOBS);

        // Framework migrations run here: a pre-H3 jar stops at V14, a post-H3 jar applies the
        // V15 partial claim index (built over the already-seeded rows on the second run).
        var factory = new DatabaseFactory(URL, USER, PASS, List.of(), 10);

        seedDeadRows();

        var poller = new JobPoller();

        // 1. Empty poll: no pending work, full dead-row history.
        long[] pollNs = new long[EMPTY_POLL_ITERS];
        for (int i = 0; i < EMPTY_POLL_WARMUP + EMPTY_POLL_ITERS; i++) {
            long t0 = System.nanoTime();
            int found = poller.pollAndExecute(factory);
            long t1 = System.nanoTime();
            if (found != 0) throw new IllegalStateException("expected empty poll, claimed " + found);
            if (i >= EMPTY_POLL_WARMUP) pollNs[i - EMPTY_POLL_WARMUP] = t1 - t0;
        }
        report("empty poll", pollNs);

        // 2. Job stats (the /ops/status path).
        long[] statsNs = new long[STATS_ITERS];
        var db = new Database(factory.openSession());
        try {
            for (int i = 0; i < STATS_WARMUP + STATS_ITERS; i++) {
                db.beginTransaction();
                long t0 = System.nanoTime();
                JobPoller.getDurableJobStats(db);
                long t1 = System.nanoTime();
                db.commitTransaction();
                if (i >= STATS_WARMUP) statsNs[i - STATS_WARMUP] = t1 - t0;
            }
        } finally {
            db.close();
        }
        report("job stats", statsNs);

        // 3. Drain PENDING_JOBS no-op jobs (indicative only — batch size differs pre/post H4).
        var seedDb = new Database(factory.openSession());
        try {
            seedDb.beginTransaction();
            for (int i = 0; i < PENDING_JOBS; i++) {
                Jobs.schedule(seedDb, new NoopJob(), Duration.ZERO);
            }
            seedDb.commitTransaction();
        } finally {
            seedDb.close();
        }
        long t0 = System.nanoTime();
        int polls = 0, drained = 0;
        while (drained < PENDING_JOBS) {
            int found = poller.pollAndExecute(factory);
            polls++;
            drained += found;
            if (found == 0) throw new IllegalStateException("queue stalled at " + drained);
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("drain         %d jobs in %d polls, %d ms total, %.2f ms/job%n",
            drained, polls, totalMs, (double) totalMs / drained);

        factory.close();
    }

    /** Bulk-insert dead rows oldest-first if the table doesn't already hold them. */
    static void seedDeadRows() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASS)) {
            long existing;
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM scheduled_jobs")) {
                rs.next();
                existing = rs.getLong(1);
            }
            if (existing >= SEED_DEAD) {
                System.out.println("  seed: " + existing + " rows already present, skipping");
                return;
            }
            System.out.println("  seed: inserting " + (SEED_DEAD - existing) + " dead rows...");
            long start = System.currentTimeMillis();
            conn.setAutoCommit(false);
            // 95% completed / 5% failed, run_at spread over the 30 days before now so the
            // dead rows form the oldest prefix of idx_scheduled_jobs_run_at.
            try (var ps = conn.prepareStatement(
                    "INSERT INTO scheduled_jobs (name, job_class, run_at, started_at, completed_at, failed_at, attempts, max_attempts, backoff_seconds) " +
                    "SELECT 'dead-' || n, 'benchmark.JobsBench$NoopJob', " +
                    "  ts, ts, CASE WHEN n % 20 <> 0 THEN ts + interval '1 minute' END, " +
                    "  CASE WHEN n % 20 = 0 THEN ts + interval '1 minute' END, 1, 3, 60 " +
                    "FROM (SELECT n, CURRENT_TIMESTAMP - interval '30 days' + (n * interval '2 seconds') AS ts " +
                    "      FROM generate_series(?, ?) AS n) gen")) {
                long remaining = SEED_DEAD - existing;
                ps.setLong(1, 1);
                ps.setLong(2, remaining);
                ps.executeUpdate();
            }
            conn.commit();
            System.out.println("  seed: done in " + (System.currentTimeMillis() - start) / 1000 + "s");
        }
    }

    static void report(String label, long[] ns) {
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        double avg = Arrays.stream(ns).average().orElse(0) / 1_000_000.0;
        System.out.printf("%-13s n=%d  min=%.1fms  p50=%.1fms  avg=%.1fms  max=%.1fms%n",
            label, ns.length,
            sorted[0] / 1_000_000.0,
            sorted[sorted.length / 2] / 1_000_000.0,
            avg,
            sorted[sorted.length - 1] / 1_000_000.0);
    }

    static String env(String key, String def) {
        return System.getenv().getOrDefault(key, def);
    }
}
