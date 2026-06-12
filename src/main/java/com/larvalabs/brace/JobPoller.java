package com.larvalabs.brace;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class JobPoller {

    private volatile boolean running;
    private Thread pollerThread;
    private DatabaseFactory dbFactory;

    public record DurableJobStats(long pending, long running, long completed, long failed) {}

    public void start(DatabaseFactory dbFactory) {
        this.dbFactory = dbFactory;
        this.running = true;
        this.pollerThread = Thread.startVirtualThread(this::pollLoop);
    }

    public void stop() {
        running = false;
        if (pollerThread != null) {
            pollerThread.interrupt();
            try {
                pollerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                int processed = pollAndExecute();
                if (processed == 0) {
                    Thread.sleep(10_000);
                } else if (processed < 50) {
                    Thread.sleep(1_000);
                }
                // if batch was full (50), immediately poll again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log and continue
                System.err.println("JobPoller error: " + e.getMessage());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Claims and executes a batch of pending jobs. Returns the number of jobs found.
     * Visible for testing.
     */
    public int pollAndExecute() {
        return pollAndExecute(dbFactory);
    }

    public int pollAndExecute(DatabaseFactory factory) {
        // Two claim strategies, by dialect:
        //   Postgres — claimBatchPostgres folds selection + claim into a single FOR UPDATE
        //   SKIP LOCKED transaction, so concurrent instances get disjoint batches and each row is
        //   claimed exactly once by construction (no per-row re-claim). Rows come back pre-claimed.
        //   H2 (tests) — H2 doesn't reliably support SKIP LOCKED, so it keeps the portable path:
        //   an unlocked candidate select, then each row defends its own claim in claimAndExecute
        //   (the row-count-checked B7 guard) before executing.
        boolean preClaimed = factory.isPostgres();
        List<Object[]> jobs = preClaimed ? claimBatchPostgres(factory) : selectCandidatesH2(factory);

        if (jobs.isEmpty()) {
            return 0;
        }

        var threads = new java.util.ArrayList<Thread>();
        for (var row : jobs) {
            var thread = Thread.startVirtualThread(() -> {
                if (preClaimed) runJobBody(factory, row);
                else claimAndExecute(factory, row);
            });
            threads.add(thread);
        }
        for (var thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return jobs.size();
    }

    /**
     * Postgres batch claim (postgres-native doc, Tier 1a): one transaction selects the oldest
     * claimable jobs with {@code FOR UPDATE SKIP LOCKED} and flips {@code started_at} in the same
     * UPDATE, returning the claimed rows. SKIP LOCKED makes concurrent pollers step over each
     * other's locked rows, so every instance gets a disjoint batch and each job is claimed exactly
     * once — the per-row re-claim the H2 path needs is unnecessary here. Runs through raw JDBC so
     * the lock clause reaches Postgres intact. {@code RETURNING attempts - 1} hands back the
     * pre-increment attempts, so {@link #runJobBody}'s retry math is identical to the H2 path.
     */
    private List<Object[]> claimBatchPostgres(DatabaseFactory factory) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            List<Object[]> rows = db.jdbc(conn -> {
                var out = new java.util.ArrayList<Object[]>();
                // Dependency check is NOT EXISTS (an unfinished parent blocks the child) rather
                // than IN (SELECT all completed ids): the IN form hashes every completed job in
                // the table on every poll; NOT EXISTS is a single PK probe per candidate. The FK
                // on depends_on_id guarantees the parent row exists, so the two are equivalent.
                try (var ps = conn.prepareStatement(
                        "UPDATE scheduled_jobs SET started_at = CURRENT_TIMESTAMP, attempts = attempts + 1 " +
                        "WHERE id IN (" +
                        "  SELECT id FROM scheduled_jobs j " +
                        "  WHERE j.run_at <= CURRENT_TIMESTAMP " +
                        "  AND j.completed_at IS NULL AND j.failed_at IS NULL AND j.started_at IS NULL " +
                        "  AND j.attempts < j.max_attempts " +
                        "  AND (j.depends_on_id IS NULL " +
                        "       OR NOT EXISTS (SELECT 1 FROM scheduled_jobs d " +
                        "                      WHERE d.id = j.depends_on_id AND d.completed_at IS NULL)) " +
                        "  ORDER BY j.run_at LIMIT 50 FOR UPDATE SKIP LOCKED) " +
                        "RETURNING id, name, job_class, job_data, attempts - 1, max_attempts, backoff_seconds");
                     var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Object[]{
                                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getInt(6), rs.getLong(7)});
                    }
                }
                return out;
            });
            db.commitTransaction();
            return rows;
        } catch (Exception e) {
            db.rollbackTransaction();
            System.err.println("JobPoller batch claim error: " + e.getMessage());
            return List.of();
        } finally {
            db.close();
        }
    }

    /** H2 path: select claimable candidates without locking; each row claims itself in {@link #claimAndExecute}. */
    private List<Object[]> selectCandidatesH2(DatabaseFactory factory) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            // Same NOT EXISTS dependency form as claimBatchPostgres — see the comment there.
            var rows = db.sqlQuery(
                "SELECT id, name, job_class, job_data, attempts, max_attempts, backoff_seconds " +
                "FROM scheduled_jobs j " +
                "WHERE j.run_at <= CURRENT_TIMESTAMP " +
                "AND j.completed_at IS NULL " +
                "AND j.failed_at IS NULL " +
                "AND j.started_at IS NULL " +
                "AND j.attempts < j.max_attempts " +
                "AND (j.depends_on_id IS NULL " +
                "     OR NOT EXISTS (SELECT 1 FROM scheduled_jobs d " +
                "                    WHERE d.id = j.depends_on_id AND d.completed_at IS NULL)) " +
                "ORDER BY j.run_at " +
                "LIMIT 50");
            db.commitTransaction();
            return rows;
        } finally {
            db.close();
        }
    }

    /**
     * H2 path: claim a single candidate row, then run it. Proceeds only if THIS poller flipped
     * started_at — i.e. the claim UPDATE affected exactly 1 row. Branching on the affected-row
     * count (not merely on "no exception was thrown") closes a latent double-run (B7): under READ
     * COMMITTED two instances can both commit this UPDATE without either throwing — one updates 1
     * row, the other 0 — and the 0-row loser would otherwise fall through and execute the body too.
     * (The Postgres path needs none of this: {@link #claimBatchPostgres} already hands out disjoint,
     * exactly-once batches.)
     */
    private void claimAndExecute(DatabaseFactory factory, Object[] row) {
        long id = ((Number) row[0]).longValue();
        var claimDb = new Database(factory.openSession());
        boolean claimed;
        try {
            claimDb.beginTransaction();
            claimed = claimDb.jdbc(conn -> {
                try (var ps = conn.prepareStatement(
                        "UPDATE scheduled_jobs SET started_at = CURRENT_TIMESTAMP, attempts = attempts + 1 WHERE id = ? AND started_at IS NULL")) {
                    ps.setLong(1, id);
                    return ps.executeUpdate() == 1;
                }
            });
            claimDb.commitTransaction();
        } catch (Exception e) {
            claimDb.rollbackTransaction();
            return; // Transient error claiming — a later poll retries
        } finally {
            claimDb.close();
        }
        if (!claimed) {
            return; // Another poller won the claim for this row
        }
        runJobBody(factory, row);
    }

    /**
     * Execute an already-claimed job (started_at set, attempts incremented), then mark it completed
     * or — on failure — fail it permanently (attempts exhausted) or release it for retry with a
     * backoff-pushed run_at. {@code row} carries the pre-increment attempts, which both claim paths
     * supply.
     */
    private void runJobBody(DatabaseFactory factory, Object[] row) {
        long id = ((Number) row[0]).longValue();
        String jobClass = (String) row[2];
        String jobData = (String) row[3];
        int attempts = ((Number) row[4]).intValue();
        int maxAttempts = ((Number) row[5]).intValue();
        long backoffSeconds = ((Number) row[6]).longValue();

        // Execute the job
        var execDb = new Database(factory.openSession());
        try {
            execDb.beginTransaction();
            Class<?> loadedClass = Class.forName(jobClass, false, Thread.currentThread().getContextClassLoader());
            if (!DurableJob.class.isAssignableFrom(loadedClass)) {
                throw new ClassCastException("Class " + jobClass + " does not implement DurableJob");
            }
            DurableJob job = (DurableJob) loadedClass.getDeclaredConstructor().newInstance();
            job.run(jobData, execDb);
            execDb.commitTransaction();

            // Mark completed
            var markDb = new Database(factory.openSession());
            try {
                markDb.beginTransaction();
                markDb.sql("UPDATE scheduled_jobs SET completed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
                markDb.commitTransaction();
            } finally {
                markDb.close();
            }
        } catch (Exception e) {
            execDb.rollbackTransaction();

            int newAttempts = attempts + 1;
            var failDb = new Database(factory.openSession());
            try {
                failDb.beginTransaction();
                if (newAttempts >= maxAttempts) {
                    failDb.sql("UPDATE scheduled_jobs SET failed_at = CURRENT_TIMESTAMP, error = ? WHERE id = ?",
                        e.getMessage(), id);
                } else {
                    // Push run_at forward by backoff * attempts
                    var newRunAt = Timestamp.from(Instant.now().plus(Duration.ofSeconds(backoffSeconds).multipliedBy(newAttempts)));
                    failDb.sql("UPDATE scheduled_jobs SET started_at = NULL, error = ?, run_at = ? WHERE id = ?",
                        e.getMessage(), newRunAt, id);
                }
                failDb.commitTransaction();
            } catch (Exception e2) {
                failDb.rollbackTransaction();
            } finally {
                failDb.close();
            }
        } finally {
            execDb.close();
        }
    }

    public static DurableJobStats getDurableJobStats(Database db) {
        // One table scan instead of four COUNT(*) passes; SUMs are NULL on an empty table.
        var rows = db.sqlQuery(
            "SELECT " +
            "SUM(CASE WHEN completed_at IS NULL AND failed_at IS NULL AND started_at IS NULL THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN started_at IS NOT NULL AND completed_at IS NULL AND failed_at IS NULL THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN completed_at IS NOT NULL THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN failed_at IS NOT NULL THEN 1 ELSE 0 END) " +
            "FROM scheduled_jobs");
        if (rows.isEmpty()) {
            return new DurableJobStats(0, 0, 0, 0);
        }
        Object[] row = rows.get(0);
        return new DurableJobStats(toCount(row[0]), toCount(row[1]), toCount(row[2]), toCount(row[3]));
    }

    private static long toCount(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /**
     * Delete completed/failed jobs whose terminal timestamp is older than {@code cutoff}. Rows
     * another job still references via {@code depends_on_id} are kept — the FK would reject the
     * delete, and an unfinished child must still see its parent's state; a finished parent is
     * removed by a later purge once its children are themselves purged. Returns rows deleted.
     * Called by the framework's daily {@code brace-jobs-prune} job (see {@code Brace.start});
     * public so apps with a custom retention schedule can run it themselves.
     */
    public static int purgeFinishedJobs(Database db, Instant cutoff) {
        var ts = Timestamp.from(cutoff);
        return db.jdbc(conn -> {
            try (var ps = conn.prepareStatement(
                    "DELETE FROM scheduled_jobs " +
                    "WHERE (completed_at < ? OR failed_at < ?) " +
                    "AND NOT EXISTS (SELECT 1 FROM scheduled_jobs c WHERE c.depends_on_id = scheduled_jobs.id)")) {
                ps.setTimestamp(1, ts);
                ps.setTimestamp(2, ts);
                return ps.executeUpdate();
            }
        });
    }
}
