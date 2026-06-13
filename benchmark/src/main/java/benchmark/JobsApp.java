package benchmark;

import com.larvalabs.brace.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Mixed web+jobs benchmark app (H4 — see docs/2026-06-11-runtime-performance-review-todos.md,
 * Benchmarks gap #4). Default pool of 10 shared by web handlers and the durable-job poller,
 * exactly the contention H4 is about:
 *
 *   GET /ping-db          — trivial DB query, the "healthy web traffic" under test
 *   GET /burst?n=&ms=     — enqueues n SlowJob(ms) durable jobs (each holds a pooled
 *                           connection for ~ms while it sleeps inside its transaction)
 *   GET /jobs-done        — completed-job count, to verify the burst drained
 *
 * run-jobs-mixed.sh drives wrk against /ping-db and fires /burst mid-run. Pre-H4 the
 * poller runs up to 50 jobs at once: 10 take every pool connection and /ping-db p99
 * spikes toward connectionTimeout. Post-H4 jobs cap at poolSize/2 = 5 connections.
 */
public class JobsApp {

    public static class SlowJob implements DurableJob {
        private final long ms;
        public SlowJob() { this.ms = 0; }
        public SlowJob(long ms) { this.ms = ms; }
        @Override public String data() { return String.valueOf(ms); }
        @Override public void run(String data, Database db) throws Exception {
            Thread.sleep(Long.parseLong(data));
        }
    }

    public static void main(String[] args) throws Exception {
        var db = new DatabaseFactory(
            System.getenv().getOrDefault("JOBS_DB_URL", "jdbc:postgresql://localhost:5433/jobs_bench"),
            System.getenv().getOrDefault("JOBS_DB_USER", "benchmarkdbuser"),
            System.getenv().getOrDefault("JOBS_DB_PASS", "benchmarkdbpass"),
            List.of());

        var app = Brace.app()
            .port(Integer.parseInt(System.getenv().getOrDefault("PORT", "8081")))
            .database(db);

        app.get("/ping-db", (DbHandler) (req, session) ->
            Json.of(Map.of("one", session.sqlQueryLong("SELECT 1"))));

        app.get("/burst", (DbHandler) (req, session) -> {
            int n = Integer.parseInt(req.queryParam("n") == null ? "100" : req.queryParam("n"));
            long ms = Long.parseLong(req.queryParam("ms") == null ? "2000" : req.queryParam("ms"));
            for (int i = 0; i < n; i++) {
                Jobs.schedule(session, new SlowJob(ms), Duration.ZERO);
            }
            return Json.of(Map.of("scheduled", n, "ms", ms));
        });

        app.get("/jobs-done", (DbHandler) (req, session) ->
            Json.of(Map.of(
                "completed", session.sqlQueryLong(
                    "SELECT COUNT(*) FROM scheduled_jobs WHERE name = 'SlowJob' AND completed_at IS NOT NULL"),
                "started", session.sqlQueryLong(
                    "SELECT COUNT(*) FROM scheduled_jobs WHERE name = 'SlowJob' AND started_at IS NOT NULL"))));

        app.start();
    }
}
