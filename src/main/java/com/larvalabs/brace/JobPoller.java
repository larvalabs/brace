package com.larvalabs.brace;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;

public class JobPoller {

    /**
     * How long a claimed-but-unfinished job may sit before {@link #reclaimStalledJobs} assumes the
     * instance running it died and returns the row to the queue. See {@link #sweepLoop}.
     */
    static final Duration DEFAULT_LEASE = Duration.ofMinutes(30);

    /** How often the sweeper checks for expired leases. Well under {@link #DEFAULT_LEASE}. */
    private static final long SWEEP_INTERVAL_MS = 60_000;

    /**
     * How long the poller waits before re-polling when the last claim did not fill every free
     * execution slot — i.e. the queue is idle or nearly so. A full batch always re-polls
     * immediately, so this is the pickup latency for work arriving at a quiet app, not a throughput
     * limit.
     *
     * <p>An empty poll is cheap: the claim UPDATE matches no rows, so no tuple is written, no XID
     * is assigned, and the commit needs no fsync — it costs one probe of the {@code V15} partial
     * index plus the round trips. At 1s that is a fraction of a percent of one pooled connection
     * per instance.
     *
     * <p>Two cases where it is not free, both worth knowing before lowering it further:
     * a high-RTT database (the round trips dominate), and a queue with wide {@code depends_on_id}
     * fan-out — dependency-blocked children sit in the claim index with {@code run_at} in the past,
     * so the scan walks them before reaching claimable work, and that cost scales with poll rate.
     */
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    private volatile boolean running;
    private Thread pollerThread;
    private Thread sweeperThread;
    private DatabaseFactory dbFactory;
    private volatile Duration lease = DEFAULT_LEASE;
    private volatile Duration pollInterval = DEFAULT_POLL_INTERVAL;

    // Execution-capacity limiter (perf review H4): jobs share the app's connection pool with web
    // handlers, so at most poolSize/2 jobs run at once — a burst can no longer take every Hikari
    // connection and 500 the request path. Claim batches are sized to the free permits, so rows
    // are only flipped to started_at when a slot can actually run them (matters multi-instance:
    // an over-claimed row is invisible to other pollers). Initialized from the first factory seen.
    private Semaphore limiter;
    private int maxConcurrent;

    private synchronized void initLimiter(DatabaseFactory factory) {
        if (limiter == null) {
            maxConcurrent = Math.max(1, factory.poolSize() / 2);
            limiter = new Semaphore(maxConcurrent);
        }
    }

    public record DurableJobStats(long pending, long running, long completed, long failed) {}

    /**
     * Set the claim lease; {@code null} or non-positive disables stalled-job recovery entirely
     * (the pre-0.1.8 behavior). Must be called before {@link #start}.
     */
    public void lease(Duration lease) {
        this.lease = lease;
    }

    /**
     * Set the idle poll interval (see {@link #DEFAULT_POLL_INTERVAL}). Must be positive — there is
     * no "disable" value, since zero would spin the poll loop against the database. Must be called
     * before {@link #start}.
     */
    public void pollInterval(Duration pollInterval) {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("Job poll interval must be positive, got: " + pollInterval);
        }
        this.pollInterval = pollInterval;
    }

    public void start(DatabaseFactory dbFactory) {
        this.dbFactory = dbFactory;
        this.running = true;
        this.pollerThread = Thread.startVirtualThread(this::pollLoop);
        if (lease != null && !lease.isNegative() && !lease.isZero()) {
            this.sweeperThread = Thread.startVirtualThread(this::sweepLoop);
        }
    }

    public void stop() {
        running = false;
        stopThread(pollerThread);
        stopThread(sweeperThread);
    }

    private static void stopThread(Thread thread) {
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                var batch = dispatch(dbFactory);
                if (batch.claimed() < batch.slots()) {
                    Thread.sleep(pollInterval.toMillis());
                }
                // Full batch: more work is likely queued — poll again immediately. dispatch
                // itself blocks until an execution slot frees, so the loop re-polls as
                // capacity opens up; it no longer joins the whole batch, which let one slow
                // job stall every other queued job for its duration (H4).
                //
                // Anything short of a full batch waits pollInterval. This used to be two tiers
                // (1s after a partial batch, 10s after an empty one); at the 1s default the two
                // coincide, and collapsing them makes a configured interval mean exactly what it
                // says rather than applying only to the idle case.
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
     * Returns rows claimed by an instance that died before writing a terminal mark back to the
     * queue. {@link #claimBatchPostgres} commits {@code started_at} <em>before</em> the job body
     * runs (so other instances skip the row), and every terminal outcome — completed, failed,
     * released-for-retry — is written by {@link #runJobBody} afterwards. If the process dies in
     * between, the row is left {@code started_at IS NOT NULL} with both terminal timestamps NULL,
     * which no claim predicate matches and {@link #purgeFinishedJobs} never deletes (it filters on
     * the terminal timestamps): permanently invisible and permanently undeletable.
     *
     * <p>This is routine, not exotic. {@code stop()} halts the poll loop but never joins the
     * per-job virtual threads, and virtual threads are always daemon — so JVM exit kills in-flight
     * jobs mid-run and every ordinary deploy can strand up to {@code poolSize/2} of them. A
     * stranded parent also blocks its whole {@code depends_on_id} subtree forever, and those
     * blocked children sit at the head of the claim scan's {@code run_at} ordering, taxing every
     * future poll.
     *
     * <p>Runs on its own thread rather than inside {@link #pollLoop} deliberately: when every
     * execution slot is held by a hung job, the poll loop is parked in {@code limiter.acquire()}
     * and would never reach a sweep folded into it — which is exactly the case that most needs one,
     * since recovery has to come from a sibling instance.
     */
    private void sweepLoop() {
        while (running) {
            try {
                Thread.sleep(SWEEP_INTERVAL_MS);
                var db = new Database(dbFactory.openSession());
                try {
                    db.beginTransaction();
                    var swept = reclaimStalledJobs(db, Instant.now().minus(lease));
                    db.commitTransaction();
                    if (swept.reclaimed() > 0 || swept.failed() > 0) {
                        Log.event("jobs_stalled_swept", java.util.Map.of(
                            "reclaimed", swept.reclaimed(),
                            "failed", swept.failed(),
                            "lease_seconds", lease.toSeconds()));
                    }
                } catch (RuntimeException e) {
                    db.rollbackTransaction();
                    throw e;
                } finally {
                    db.close();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Transient DB error — the next sweep retries. Never let it kill the thread.
                System.err.println("JobPoller sweep error: " + e.getMessage());
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

    /**
     * Claims one batch and waits for every job in it to finish before returning; returns the
     * number of jobs claimed. This synchronous form exists for deterministic tests (and the
     * drain loops in {@code DurableJobConcurrencyPostgresIT}) — the live poll loop calls
     * {@link #dispatch} directly and does not wait for the batch.
     */
    public int pollAndExecute(DatabaseFactory factory) {
        try {
            var batch = dispatch(factory);
            for (var thread : batch.threads()) {
                thread.join();
            }
            return batch.claimed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** One claimed batch: how many slots were free when it was claimed, and the threads
     * executing the claimed jobs (one each, already started). */
    record Batch(int slots, List<Thread> threads) {
        int claimed() {
            return threads.size();
        }
    }

    /**
     * Claims up to one batch of pending jobs and starts a virtual thread per job, WITHOUT
     * waiting for them. Blocks until at least one execution slot is free, then sizes the
     * claim to the free capacity. Each job thread releases its slot when the job's terminal
     * mark is written. Package-private for tests.
     *
     * <p>Two claim strategies, by dialect:
     * Postgres — claimBatchPostgres folds selection + claim into a single FOR UPDATE
     * SKIP LOCKED transaction, so concurrent instances get disjoint batches and each row is
     * claimed exactly once by construction (no per-row re-claim). Rows come back pre-claimed.
     * H2 (tests) — H2 doesn't reliably support SKIP LOCKED, so it keeps the portable path:
     * an unlocked candidate select, then each row defends its own claim in claimAndExecute
     * (the row-count-checked B7 guard) before executing.
     */
    Batch dispatch(DatabaseFactory factory) throws InterruptedException {
        initLimiter(factory);
        limiter.acquire();
        int slots = 1;
        while (slots < maxConcurrent && limiter.tryAcquire()) {
            slots++;
        }

        boolean preClaimed = factory.isPostgres();
        List<Object[]> jobs = List.of();
        try {
            jobs = preClaimed ? claimBatchPostgres(factory, slots) : selectCandidatesH2(factory, slots);
        } finally {
            // Give back the slots this batch won't fill (all of them if the claim threw).
            for (int i = jobs.size(); i < slots; i++) {
                limiter.release();
            }
        }

        var threads = new java.util.ArrayList<Thread>(jobs.size());
        for (var row : jobs) {
            threads.add(Thread.startVirtualThread(() -> {
                try {
                    if (preClaimed) runJobBody(factory, row);
                    else claimAndExecute(factory, row);
                } finally {
                    limiter.release();
                }
            }));
        }
        return new Batch(slots, threads);
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
    private List<Object[]> claimBatchPostgres(DatabaseFactory factory, int limit) {
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
                        "  ORDER BY j.run_at LIMIT ? FOR UPDATE SKIP LOCKED) " +
                        "RETURNING id, name, job_class, job_data, attempts - 1, max_attempts, backoff_seconds")) {
                    ps.setInt(1, limit);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Object[]{
                                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                    rs.getInt(5), rs.getInt(6), rs.getLong(7)});
                        }
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
    private List<Object[]> selectCandidatesH2(DatabaseFactory factory, int limit) {
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
                "LIMIT ?", limit);
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

        // One session for the job AND its terminal mark, each in its own transaction (perf
        // review H4): a separate mark session per job previously doubled — tripled on the
        // failure path — pool demand under bursts.
        var db = new Database(factory.openSession());
        try {
            try {
                db.beginTransaction();
                Class<?> loadedClass = Class.forName(jobClass, false, Thread.currentThread().getContextClassLoader());
                if (!DurableJob.class.isAssignableFrom(loadedClass)) {
                    throw new ClassCastException("Class " + jobClass + " does not implement DurableJob");
                }
                DurableJob job = (DurableJob) loadedClass.getDeclaredConstructor().newInstance();
                job.run(jobData, db);
                db.commitTransaction();
            } catch (Exception e) {
                db.rollbackTransaction();

                int newAttempts = attempts + 1;
                try {
                    db.beginTransaction();
                    if (newAttempts >= maxAttempts) {
                        db.sql("UPDATE scheduled_jobs SET failed_at = CURRENT_TIMESTAMP, error = ? WHERE id = ?",
                            e.getMessage(), id);
                    } else {
                        // Push run_at forward by backoff * attempts
                        var newRunAt = Timestamp.from(Instant.now().plus(Duration.ofSeconds(backoffSeconds).multipliedBy(newAttempts)));
                        db.sql("UPDATE scheduled_jobs SET started_at = NULL, error = ?, run_at = ? WHERE id = ?",
                            e.getMessage(), newRunAt, id);
                    }
                    db.commitTransaction();
                } catch (Exception e2) {
                    db.rollbackTransaction();
                }
                return;
            }

            // Mark completed
            db.beginTransaction();
            db.sql("UPDATE scheduled_jobs SET completed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
            db.commitTransaction();
        } finally {
            db.close();
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

    /** Outcome of one stalled-job sweep: rows returned to the queue, and rows failed outright. */
    public record SweepResult(int reclaimed, int failed) {}

    /**
     * Recover jobs claimed before {@code cutoff} that never reached a terminal state — see
     * {@link #sweepLoop} for how rows get stranded. Returns what it did.
     *
     * <p>Two disjoint outcomes, split on the attempt budget the claim already spent:
     * <ul>
     *   <li><b>Attempts remain</b> — clear {@code started_at}, returning the row to the normal
     *       claimable set. {@code attempts} is deliberately left as-is: the claim incremented it
     *       before the job ran, so a job that strands repeatedly burns its budget and eventually
     *       lands in the branch below rather than looping forever. That guard already exists in
     *       both claim predicates ({@code attempts < max_attempts}), so no new state is needed.
     *   <li><b>Attempts exhausted</b> — set {@code failed_at}, matching what {@link #runJobBody}
     *       writes when a live job exhausts its retries. Without this the row would clear the
     *       lease but fail {@code attempts < max_attempts}, stranding it a second way.
     * </ul>
     *
     * <p>Clearing {@code started_at} rather than widening the claim predicate to
     * {@code OR started_at < ...} keeps the hot claim query and its {@code V15} partial index
     * exactly as the perf review left them — recovered rows re-enter through the ordinary path,
     * and the H2 per-row re-claim guard ({@code AND started_at IS NULL}) still holds unchanged.
     *
     * <p><b>Semantics.</b> A lease cannot distinguish a dead instance from a job that is merely
     * slower than the lease, so a job still running past {@code cutoff} will be picked up again
     * elsewhere. That is the at-least-once contract {@link DurableJob} already carries — jobs must
     * be idempotent — but it means the lease must exceed the longest job you expect to run
     * ({@link Brace#jobLease}).
     *
     * <p>{@code cutoff} is computed from the app clock while {@code started_at} is written from the
     * database clock ({@code CURRENT_TIMESTAMP}), same as {@link #purgeFinishedJobs}. Skew of
     * seconds is immaterial against a lease of minutes.
     */
    public static SweepResult reclaimStalledJobs(Database db, Instant cutoff) {
        var ts = Timestamp.from(cutoff);
        return db.jdbc(conn -> {
            int reclaimed;
            try (var ps = conn.prepareStatement(
                    "UPDATE scheduled_jobs SET started_at = NULL, error = ? " +
                    "WHERE started_at < ? AND completed_at IS NULL AND failed_at IS NULL " +
                    "AND attempts < max_attempts")) {
                ps.setString(1, "Reclaimed: claimed but never finished (lease expired)");
                ps.setTimestamp(2, ts);
                reclaimed = ps.executeUpdate();
            }
            int failed;
            try (var ps = conn.prepareStatement(
                    "UPDATE scheduled_jobs SET failed_at = CURRENT_TIMESTAMP, error = ? " +
                    "WHERE started_at < ? AND completed_at IS NULL AND failed_at IS NULL " +
                    "AND attempts >= max_attempts")) {
                ps.setString(1, "Failed: claimed but never finished (lease expired, attempts exhausted)");
                ps.setTimestamp(2, ts);
                failed = ps.executeUpdate();
            }
            return new SweepResult(reclaimed, failed);
        });
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
