package com.larvalabs.brace;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Claims and runs {@link DurableJob}s from {@code scheduled_jobs}.
 *
 * <h2>Ownership and recovery</h2>
 *
 * A claim commits {@code started_at} <em>before</em> the job body runs (so other instances skip
 * the row), and every terminal outcome — completed, failed, released-for-retry — is written after
 * it. A process that dies in between leaves a row no claim predicate matches and no prune deletes.
 * Three mechanisms keep that from stranding work, each covering what the one before cannot:
 *
 * <ol>
 *   <li><b>Graceful release</b> ({@link #stop}). On shutdown the poller stops claiming, gives
 *       running jobs {@link #shutdownTimeout} to finish, interrupts the rest, and returns every row
 *       it still owns to the queue <em>with the attempt refunded</em> — the job did not fail, it was
 *       interrupted. {@link Brace} calls {@code stop()} from a JVM shutdown hook, so an ordinary
 *       deploy (SIGTERM) strands nothing and re-runs nothing that finished.</li>
 *   <li><b>Worker heartbeat.</b> Each poller registers a row in {@code brace_job_workers} and
 *       refreshes it every {@link #HEARTBEAT_INTERVAL}; every claim records its owner in
 *       {@code scheduled_jobs.claimed_by}. A sweep on every instance returns a claimed, unfinished
 *       job to the queue only once its owner has missed heartbeats for {@link #WORKER_DEAD_AFTER}.
 *       This covers what graceful release can't reach — SIGKILL, OOM kills, a lost host — and it
 *       never takes a job from a live instance, however long that job runs. The attempt is
 *       <em>not</em> refunded here: the job may be what killed the process, and the attempt budget
 *       is what stops a poison job from taking down instance after instance.</li>
 *   <li><b>Optional timeout</b> ({@link #timeout}, off by default). Enforced by the instance that
 *       owns the job: it interrupts the job's thread, and the attempt fails and retries under the
 *       normal backoff. A timed-out job is never handed to a second instance while the first copy
 *       is still running.</li>
 * </ol>
 *
 * Every terminal write is conditional on {@code claimed_by} still naming this worker, so a copy
 * whose claim was taken over (its instance was presumed dead and then recovered) cannot overwrite
 * the outcome of the run that replaced it.
 */
public class JobPoller {

    /**
     * How long the poller waits before re-polling when it did not fill every free execution slot —
     * i.e. the queue is idle or nearly so. A full batch always re-polls immediately, so this is the
     * pickup latency for work arriving at a quiet app, not a throughput limit.
     *
     * <p>This is a safety net rather than the primary path: {@link Jobs#schedule} wakes the local
     * poller as soon as the enqueuing transaction commits, so a job scheduled with no delay starts
     * almost immediately. Polling covers everything a wake cannot reach — jobs with a future
     * {@code run_at}, retries whose backoff has expired, rows recovered by the sweep, work
     * enqueued on a <em>different</em> instance, and anything already queued at startup.
     *
     * <p>An empty poll is cheap: the claim UPDATE matches no rows, so no tuple is written, no XID
     * is assigned, and the commit needs no fsync — it costs one probe of the {@code V15} partial
     * index plus the round trips. Two cases where polling is not free, both worth knowing before
     * lowering it: a high-RTT database (the round trips dominate), and a queue with wide
     * {@code depends_on_id} fan-out — dependency-blocked children sit in the claim index with
     * {@code run_at} in the past, so the scan walks them before reaching claimable work, and that
     * cost scales with poll rate.
     */
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    /**
     * How long {@link #stop} waits for running jobs before interrupting them and releasing their
     * claims. Deliberately short: it has to fit inside the platform's own kill timeout (Docker 10s,
     * Kubernetes 30s, Fly.io 5s) along with the rest of shutdown, or the process is SIGKILLed before
     * it can release anything and recovery falls back to the heartbeat. A job that doesn't finish
     * in time loses nothing — it is released with its attempt refunded and runs again elsewhere.
     */
    static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(3);

    /** How often a running poller refreshes its {@code brace_job_workers} row. */
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /**
     * How long a worker may go without a heartbeat before its jobs are recovered — eight missed
     * heartbeats. Generous on purpose: this path runs only for a process that died without a
     * graceful shutdown, so recovery latency matters far less than never declaring a live instance
     * dead over a long GC pause or a slow connection pool.
     */
    static final Duration WORKER_DEAD_AFTER = Duration.ofMinutes(2);

    /** How often each instance sweeps for jobs owned by dead workers. */
    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

    /**
     * Rows with {@code claimed_by IS NULL} were claimed by a pre-0.1.8 instance, which has no
     * heartbeat to check. During a rolling upgrade such an instance may still be running the job,
     * so these rows are recovered only once their claim is older than this — longer than any
     * rolling deploy, and longer than any job a 0.1.7 app could have been relying on.
     */
    static final Duration LEGACY_CLAIM_CUTOFF = Duration.ofHours(24);

    /**
     * A row this worker owns but is not running (its terminal write failed — a database blip at
     * the moment the job finished) is released once its claim is older than this. The grace period
     * covers the instant between a batch claim committing and its jobs being registered as running.
     */
    static final Duration OWN_ORPHAN_GRACE = Duration.ofMinutes(1);

    /** The heartbeat thread's tick: timeout enforcement granularity. In-memory work only. */
    private static final long TICK_MS = 1_000;

    /** After interrupting jobs at shutdown, how long to let them unwind before releasing claims. */
    private static final long INTERRUPT_UNWIND_MS = 1_000;

    private final String workerId = UUID.randomUUID().toString();
    private volatile String instanceLabel;

    private volatile boolean running;
    private Thread pollerThread;
    private Thread heartbeatThread;
    private DatabaseFactory dbFactory;
    private volatile Duration pollInterval = DEFAULT_POLL_INTERVAL;
    private volatile Duration timeout;
    private volatile Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

    // Heartbeat timing — the constants above, overridable by tests.
    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;
    private Duration workerDeadAfter = WORKER_DEAD_AFTER;
    private Duration sweepInterval = SWEEP_INTERVAL;
    private Duration ownOrphanGrace = OWN_ORPHAN_GRACE;

    /** Nanotime of the last successful heartbeat; 0 = none yet. */
    private volatile long lastHeartbeatNanos;
    /** Nanotime since which every heartbeat has succeeded; 0 = the last one failed. */
    private volatile long healthySinceNanos;

    /** Jobs this poller is running, by id. The own-orphan check releases owned rows not in here. */
    private final ConcurrentHashMap<Long, InFlight> inFlight = new ConcurrentHashMap<>();

    /**
     * Wake signal for {@link #wake()}. A permit released while the poller is mid-poll is retained,
     * so a job enqueued during a poll that missed it still triggers an immediate re-poll instead of
     * waiting out the interval — the lost-wakeup case.
     */
    private final Semaphore wakeSignal = new Semaphore(0);

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

    /** A job this poller is running. */
    private static final class InFlight {
        final String jobClass;
        final long startedNanos = System.nanoTime();
        volatile Thread thread;
        /** Interrupted for exceeding {@link #timeout}: the failure is recorded as a timeout. */
        volatile boolean timedOut;
        /** Interrupted by {@link #stop}: the row is released by {@link #releaseOwnClaims}, not failed. */
        volatile boolean released;

        InFlight(String jobClass) {
            this.jobClass = jobClass;
        }
    }

    public record DurableJobStats(long pending, long running, long completed, long failed) {}

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

    /**
     * Maximum run time for one attempt; {@code null} or non-positive means no limit (the default).
     * A job still running when it expires is interrupted, and the attempt fails and retries under
     * the job's normal backoff. Interruption is cooperative: blocking I/O and {@code sleep} respond
     * to it, a tight CPU loop that never checks does not — such a job keeps its claim (and is
     * logged) rather than being run a second time alongside itself.
     */
    public void timeout(Duration timeout) {
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? null : timeout;
    }

    /** How long {@link #stop} waits for running jobs; see {@link #DEFAULT_SHUTDOWN_TIMEOUT}. */
    public void shutdownTimeout(Duration shutdownTimeout) {
        if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("Job shutdown timeout must be zero or positive, got: " + shutdownTimeout);
        }
        this.shutdownTimeout = shutdownTimeout;
    }

    /** Label stored alongside the worker row (the app's instance id) — for humans reading the table. */
    void instanceLabel(String label) {
        this.instanceLabel = label;
    }

    /** Test hook: shrink the heartbeat timing so liveness can be exercised in milliseconds. */
    void heartbeatTiming(Duration interval, Duration deadAfter, Duration sweep, Duration ownOrphanGrace) {
        this.heartbeatInterval = interval;
        this.workerDeadAfter = deadAfter;
        this.sweepInterval = sweep;
        this.ownOrphanGrace = ownOrphanGrace;
    }

    /** This poller's row id in {@code brace_job_workers}, and the value it writes to {@code claimed_by}. */
    String workerId() {
        return workerId;
    }

    /**
     * Poll again now instead of waiting out the interval. Called after a {@link Jobs#schedule}
     * transaction commits, so the row is visible before the poller looks.
     *
     * <p>Best-effort by design: it only wakes <em>this</em> instance, and a missed wake costs
     * latency, never correctness — polling still finds the job. That is what lets it stay this
     * cheap, and why the poll interval is a safety net rather than dead weight.
     *
     * <p>The permit is capped at one. Bulk-scheduling collapses to a single extra poll rather than
     * one per job, and the benign race where two threads both observe zero permits costs at most
     * one additional poll.
     */
    void wake() {
        if (wakeSignal.availablePermits() == 0) {
            wakeSignal.release();
        }
    }

    /** Whether a wake is queued but not yet consumed by the poll loop. For tests. */
    boolean wakePending() {
        return wakeSignal.availablePermits() > 0;
    }

    /** Queued wake permits; capped at one by {@link #wake()}. For tests. */
    int wakePermits() {
        return wakeSignal.availablePermits();
    }

    public synchronized void start(DatabaseFactory dbFactory) {
        this.dbFactory = dbFactory;
        this.running = true;
        // Register before the first claim, so no row ever names an owner a sweep can't find. If the
        // database is unreachable right now, the poll loop waits for the heartbeat thread to succeed.
        heartbeat();
        // A platform thread, not a virtual one: CPU-bound or pinned jobs can occupy every carrier
        // thread, and a heartbeat that can't get scheduled makes a healthy instance look dead.
        this.heartbeatThread = Thread.ofPlatform().daemon().name("brace-job-heartbeat").start(this::heartbeatLoop);
        this.pollerThread = Thread.startVirtualThread(this::pollLoop);
    }

    /**
     * Graceful shutdown: stop claiming, let running jobs finish for up to {@link #shutdownTimeout},
     * interrupt whatever is left, then return every row this worker still owns to the queue with the
     * attempt refunded, and deregister. Idempotent.
     *
     * <p>Best-effort by nature — a SIGKILL never gets here — which is why the heartbeat exists.
     * What this buys is the common case: a deploy neither strands a job for the heartbeat to find
     * nor charges it an attempt it didn't fail.
     *
     * <p>On an H2 <em>file</em> database the release usually fails: H2 registers its own JVM
     * shutdown hook that closes the database, and hooks run concurrently. The heartbeat recovers
     * those jobs instead. (Postgres, the production target, has no such hook; H2 in-memory — the
     * dev and test default — disappears with the process anyway.)
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            stopThread(pollerThread);
            drainInFlight(shutdownTimeout);
            stopThread(heartbeatThread);
        } finally {
            // Whatever went wrong above (this runs inside a JVM shutdown hook, where surprises
            // happen), the claims must still be released — that is the point of stopping gracefully.
            if (dbFactory != null) {
                releaseOwnClaims();
                deregister();
            }
        }
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

    /** Wait for running jobs until {@code grace} expires, then interrupt the rest and let them unwind. */
    private void drainInFlight(Duration grace) {
        long deadline = System.nanoTime() + grace.toNanos();
        try {
            for (var flight : List.copyOf(inFlight.values())) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs > 0 && flight.thread != null) {
                    flight.thread.join(remainingMs);
                }
            }
            var left = List.copyOf(inFlight.values());
            if (left.isEmpty()) {
                return;
            }
            Log.event("jobs_shutdown_released", Map.of(
                "jobs", left.size(),
                "shutdown_timeout_seconds", grace.toSeconds()));
            for (var flight : left) {
                flight.released = true;
                if (flight.thread != null) flight.thread.interrupt();
            }
            long unwindDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INTERRUPT_UNWIND_MS);
            for (var flight : left) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(unwindDeadline - System.nanoTime());
                if (remainingMs > 0 && flight.thread != null) {
                    flight.thread.join(remainingMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                // Don't claim while this worker might look dead to a sweep: a claim whose owner has
                // no fresh heartbeat can be recovered out from under the job that just started.
                if (!heartbeatFresh()) {
                    Thread.sleep(Math.min(pollInterval.toMillis(), heartbeatInterval.toMillis()));
                    continue;
                }
                var batch = dispatch(dbFactory);
                if (batch.claimed() < batch.slots()) {
                    // Interruptible wait: returns early if wake() fired (a job was enqueued on
                    // this instance), otherwise falls through on the poll interval.
                    wakeSignal.tryAcquire(pollInterval.toMillis(), TimeUnit.MILLISECONDS);
                }
                // Full batch: more work is likely queued — poll again immediately. dispatch
                // itself blocks until an execution slot frees, so the loop re-polls as
                // capacity opens up; it no longer joins the whole batch, which let one slow
                // job stall every other queued job for its duration (H4).
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

    /** Whether the last heartbeat is recent enough that no sweep could consider this worker dead. */
    private boolean heartbeatFresh() {
        long last = lastHeartbeatNanos;
        return last != 0 && System.nanoTime() - last < workerDeadAfter.toNanos() / 2;
    }

    // --- Heartbeat, sweep, timeouts ---------------------------------------------------------

    private void heartbeatLoop() {
        long nextBeat = System.nanoTime() + heartbeatInterval.toNanos();
        long nextSweep = System.nanoTime() + sweepInterval.toNanos();
        while (running) {
            try {
                Thread.sleep(Math.min(TICK_MS, heartbeatInterval.toMillis()));
                enforceTimeouts();
                long now = System.nanoTime();
                if (now >= nextBeat) {
                    heartbeat();
                    nextBeat = now + heartbeatInterval.toNanos();
                }
                if (now >= nextSweep) {
                    sweep();
                    nextSweep = now + sweepInterval.toNanos();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Never let a transient error kill the thread — a dead heartbeat looks like a dead instance.
                System.err.println("JobPoller heartbeat error: " + e.getMessage());
            }
        }
    }

    /**
     * Refresh this worker's row, re-registering if it is missing — either the first heartbeat, or
     * a sweep pruned it because this instance went quiet for longer than {@link #WORKER_DEAD_AFTER}
     * (its jobs have then already been recovered elsewhere; any still running here will find their
     * claim gone when they finish).
     */
    void heartbeat() {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            boolean existed = update(db,
                "UPDATE brace_job_workers SET heartbeat_at = CURRENT_TIMESTAMP WHERE id = ?", workerId) == 1;
            if (!existed) {
                if (lastHeartbeatNanos != 0) {
                    Log.warn("Durable-job worker " + workerId + " was presumed dead after missing heartbeats; "
                        + "its jobs were recovered by another instance. Re-registering.");
                }
                update(db, "INSERT INTO brace_job_workers (id, instance_id, started_at, heartbeat_at) "
                    + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", workerId, instanceLabel);
            }
            db.commitTransaction();
            long now = System.nanoTime();
            if (healthySinceNanos == 0) {
                healthySinceNanos = now;
            }
            lastHeartbeatNanos = now;
        } catch (Exception e) {
            rollbackQuietly(db);
            healthySinceNanos = 0;
            System.err.println("JobPoller heartbeat failed: " + e.getMessage());
        } finally {
            db.close();
        }
    }

    /**
     * One sweep: release this worker's own orphaned rows, then — only if this instance has itself
     * been continuously healthy for two heartbeat intervals — recover jobs owned by dead workers.
     * The health gate matters after a database outage: every instance missed heartbeats, and the
     * first to reconnect must not declare the others dead before they have had a chance to beat.
     */
    private void sweep() {
        releaseOwnOrphans();
        long healthySince = healthySinceNanos;
        if (healthySince == 0 || System.nanoTime() - healthySince < 2 * heartbeatInterval.toNanos()) {
            return;
        }
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            var now = dbNow(db);
            var swept = sweepOrphans(db, now.minus(workerDeadAfter), now.minus(LEGACY_CLAIM_CUTOFF));
            db.commitTransaction();
            if (swept.reclaimed() > 0 || swept.failed() > 0) {
                Log.event("jobs_orphans_recovered", Map.of(
                    "reclaimed", swept.reclaimed(),
                    "failed", swept.failed()));
            }
        } catch (Exception e) {
            rollbackQuietly(db);
            System.err.println("JobPoller sweep error: " + e.getMessage());
        } finally {
            db.close();
        }
    }

    /**
     * Release rows this worker owns but is no longer running — the job finished but its terminal
     * write failed. Without this, a live instance would keep such a row claimed (and heartbeat on
     * its behalf) forever.
     */
    void releaseOwnOrphans() {
        var runningIds = new ArrayList<>(inFlight.keySet());
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            var predicate = new StringBuilder("claimed_by = ? AND started_at < ?");
            var params = new ArrayList<Object>(List.of(workerId, Timestamp.from(dbNow(db).minus(ownOrphanGrace))));
            if (!runningIds.isEmpty()) {
                predicate.append(" AND id NOT IN (").append("?,".repeat(runningIds.size() - 1)).append("?)");
                params.addAll(runningIds);
            }
            var result = recover(db, predicate.toString(), "claimed but never finished (terminal write failed)",
                params.toArray());
            db.commitTransaction();
            if (result.reclaimed() > 0 || result.failed() > 0) {
                Log.event("jobs_own_orphans_released", Map.of(
                    "reclaimed", result.reclaimed(),
                    "failed", result.failed()));
            }
        } catch (Exception e) {
            rollbackQuietly(db);
            System.err.println("JobPoller own-orphan check error: " + e.getMessage());
        } finally {
            db.close();
        }
    }

    /** Interrupt jobs that have exceeded {@link #timeout}. Runs every tick; in-memory only. */
    private void enforceTimeouts() {
        var limit = timeout;
        if (limit == null) {
            return;
        }
        long now = System.nanoTime();
        for (var entry : inFlight.entrySet()) {
            var flight = entry.getValue();
            if (!flight.timedOut && flight.thread != null && now - flight.startedNanos > limit.toNanos()) {
                flight.timedOut = true;
                Log.event("job_timeout", Map.of(
                    "job_id", entry.getKey(),
                    "job_class", flight.jobClass,
                    "timeout_seconds", limit.toSeconds()));
                flight.thread.interrupt();
            }
        }
    }

    /**
     * Shutdown: return every unfinished row this worker owns to the queue, refunding the attempt
     * its claim spent — an interrupted job did not fail.
     */
    private void releaseOwnClaims() {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            int released = update(db,
                "UPDATE scheduled_jobs SET started_at = NULL, claimed_by = NULL, error = ?, "
                    + "attempts = CASE WHEN attempts > 0 THEN attempts - 1 ELSE 0 END "
                    + "WHERE claimed_by = ? AND completed_at IS NULL AND failed_at IS NULL",
                "Released: instance shut down before the job finished", workerId);
            db.commitTransaction();
            if (released > 0) {
                Log.event("jobs_released_on_shutdown", Map.of("released", released));
            }
        } catch (Exception e) {
            rollbackQuietly(db);
            System.err.println("JobPoller could not release claims on shutdown (the heartbeat sweep "
                + "will recover them): " + e.getMessage());
        } finally {
            db.close();
        }
    }

    private void deregister() {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            update(db, "DELETE FROM brace_job_workers WHERE id = ?", workerId);
            db.commitTransaction();
        } catch (Exception e) {
            rollbackQuietly(db); // a stale row is pruned by the next sweep anywhere
        } finally {
            db.close();
        }
    }

    // --- Claim and execute ------------------------------------------------------------------

    /**
     * Claims and executes a batch of pending jobs. Returns the number of jobs found.
     * Visible for testing. Claims are recorded under this poller's worker id; on a poller that was
     * never {@link #start}ed no heartbeat backs them, so a sweep elsewhere treats them as orphaned.
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

        var threads = new ArrayList<Thread>(jobs.size());
        for (var row : jobs) {
            long id = ((Number) row[0]).longValue();
            var flight = new InFlight((String) row[2]);
            if (inFlight.putIfAbsent(id, flight) != null) {
                // H2 only: an earlier batch's thread hasn't claimed this candidate yet, so the
                // unlocked select returned it again. That thread owns it; skip rather than track
                // two runs under one id (the own-orphan check relies on the map being exact).
                limiter.release();
                continue;
            }
            var thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    if (preClaimed) runJobBody(factory, row, flight);
                    else claimAndExecute(factory, row, flight);
                } finally {
                    inFlight.remove(id, flight);
                    limiter.release();
                }
            });
            flight.thread = thread;
            thread.start();
            threads.add(thread);
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
                var out = new ArrayList<Object[]>();
                // Dependency check is NOT EXISTS (an unfinished parent blocks the child) rather
                // than IN (SELECT all completed ids): the IN form hashes every completed job in
                // the table on every poll; NOT EXISTS is a single PK probe per candidate. The FK
                // on depends_on_id guarantees the parent row exists, so the two are equivalent.
                try (var ps = conn.prepareStatement(
                        "UPDATE scheduled_jobs SET started_at = CURRENT_TIMESTAMP, attempts = attempts + 1, " +
                        "claimed_by = ? " +
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
                    ps.setString(1, workerId);
                    ps.setInt(2, limit);
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
    private void claimAndExecute(DatabaseFactory factory, Object[] row, InFlight flight) {
        long id = ((Number) row[0]).longValue();
        var claimDb = new Database(factory.openSession());
        boolean claimed;
        try {
            claimDb.beginTransaction();
            claimed = update(claimDb,
                "UPDATE scheduled_jobs SET started_at = CURRENT_TIMESTAMP, attempts = attempts + 1, claimed_by = ? "
                    + "WHERE id = ? AND started_at IS NULL", workerId, id) == 1;
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
        runJobBody(factory, row, flight);
    }

    /**
     * Execute an already-claimed job (started_at set, attempts incremented), then mark it completed
     * or — on failure — fail it permanently (attempts exhausted) or release it for retry with a
     * backoff-pushed run_at. {@code row} carries the pre-increment attempts, which both claim paths
     * supply.
     */
    private void runJobBody(DatabaseFactory factory, Object[] row, InFlight flight) {
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
            Exception failure = null;
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
                failure = e;
                rollbackQuietly(db);
            }
            // A timeout or shutdown may have interrupted this thread. Clear the flag, or the
            // terminal write below would be interrupted too.
            Thread.interrupted();

            if (failure == null) {
                markTerminal(factory, db, id,
                    "UPDATE scheduled_jobs SET completed_at = CURRENT_TIMESTAMP WHERE id = ? AND claimed_by = ?",
                    id, workerId);
                return;
            }
            if (flight.released) {
                return; // Interrupted by shutdown: releaseOwnClaims returns the row, attempt refunded.
            }

            String error = flight.timedOut
                ? "Timed out after " + describe(timeout) + " (" + failure + ")"
                : failure.getMessage();
            int newAttempts = attempts + 1;
            if (newAttempts >= maxAttempts) {
                markTerminal(factory, db, id,
                    "UPDATE scheduled_jobs SET failed_at = CURRENT_TIMESTAMP, error = ? WHERE id = ? AND claimed_by = ?",
                    error, id, workerId);
            } else {
                // Push run_at forward by backoff * attempts
                var newRunAt = Timestamp.from(Instant.now().plus(Duration.ofSeconds(backoffSeconds).multipliedBy(newAttempts)));
                markTerminal(factory, db, id,
                    "UPDATE scheduled_jobs SET started_at = NULL, claimed_by = NULL, error = ?, run_at = ? "
                        + "WHERE id = ? AND claimed_by = ?",
                    error, newRunAt, id, workerId);
            }
        } finally {
            db.close();
        }
    }

    /**
     * Write a job's terminal outcome, guarded by {@code claimed_by}. Tries the job's own session
     * first, then a fresh one — an interrupted job can leave its connection closed. If both fail
     * the row stays claimed by this worker and {@link #releaseOwnOrphans} recovers it.
     */
    private void markTerminal(DatabaseFactory factory, Database jobDb, long id, String sql, Object... params) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Database db = attempt == 0 ? jobDb : new Database(factory.openSession());
            try {
                db.beginTransaction();
                int updated = update(db, sql, params);
                db.commitTransaction();
                if (updated == 0) {
                    // This worker was presumed dead and the job recovered elsewhere; that run owns
                    // the row now, and its outcome must not be overwritten by this stale copy.
                    Log.event("job_claim_lost", Map.of("job_id", id, "worker", workerId));
                }
                return;
            } catch (Exception e) {
                rollbackQuietly(db);
                Thread.interrupted();
                if (attempt == 1) {
                    System.err.println("JobPoller could not record the outcome of job " + id
                        + " (it will be released for retry): " + e.getMessage());
                }
            } finally {
                if (db != jobDb) db.close();
            }
        }
    }

    // --- Recovery ---------------------------------------------------------------------------

    /** Outcome of one recovery pass: rows returned to the queue, and rows failed outright. */
    record SweepResult(int reclaimed, int failed) {}

    /**
     * Recover claimed, unfinished jobs whose owner is dead: its {@code brace_job_workers} row is
     * missing or last beat before {@code workerDeadBefore}. Rows with no owner (claimed by a
     * pre-0.1.8 instance) are recovered only when claimed before {@code legacyClaimedBefore} —
     * see {@link #LEGACY_CLAIM_CUTOFF}. Also prunes dead worker rows. Package-private for tests;
     * the live path is {@link #sweep}.
     */
    static SweepResult sweepOrphans(Database db, Instant workerDeadBefore, Instant legacyClaimedBefore) {
        var deadCutoff = Timestamp.from(workerDeadBefore);
        var result = recover(db,
            "(claimed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM brace_job_workers w "
                + "WHERE w.id = scheduled_jobs.claimed_by AND w.heartbeat_at >= ?)) "
                + "OR (claimed_by IS NULL AND started_at < ?)",
            "claimed by an instance that stopped heartbeating",
            deadCutoff, Timestamp.from(legacyClaimedBefore));
        // After recovery, so a worker is never pruned while it still appears to own anything. A
        // missing row counts as dead anyway, so pruning is safe whenever it happens.
        update(db, "DELETE FROM brace_job_workers WHERE heartbeat_at < ?", deadCutoff);
        return result;
    }

    /**
     * Return claimed, unfinished rows matching {@code predicate} to the queue, split on the attempt
     * budget the claim already spent:
     * <ul>
     *   <li><b>Attempts remain</b> — clear {@code started_at} and {@code claimed_by}, returning the
     *       row to the normal claimable set. {@code attempts} is deliberately left as-is: the claim
     *       incremented it before the job ran, so a job that dies with its instance every time burns
     *       its budget and lands in the branch below rather than looping forever.</li>
     *   <li><b>Attempts exhausted</b> — set {@code failed_at}, matching what {@link #runJobBody}
     *       writes when a live job exhausts its retries.</li>
     * </ul>
     * Clearing {@code started_at} rather than widening the claim predicate keeps the hot claim
     * query and its {@code V15} partial index exactly as they are.
     */
    private static SweepResult recover(Database db, String predicate, String reason, Object... params) {
        String unfinished = "started_at IS NOT NULL AND completed_at IS NULL AND failed_at IS NULL";
        var reclaimParams = new ArrayList<Object>();
        reclaimParams.add("Reclaimed: " + reason);
        reclaimParams.addAll(List.of(params));
        int reclaimed = update(db,
            "UPDATE scheduled_jobs SET started_at = NULL, claimed_by = NULL, error = ? WHERE "
                + unfinished + " AND attempts < max_attempts AND (" + predicate + ")",
            reclaimParams.toArray());
        var failParams = new ArrayList<Object>();
        failParams.add("Failed: " + reason + ", attempts exhausted");
        failParams.addAll(List.of(params));
        int failed = update(db,
            "UPDATE scheduled_jobs SET failed_at = CURRENT_TIMESTAMP, error = ? WHERE "
                + unfinished + " AND attempts >= max_attempts AND (" + predicate + ")",
            failParams.toArray());
        return new SweepResult(reclaimed, failed);
    }

    /**
     * The database's clock. Every timestamp the sweep compares against — {@code heartbeat_at},
     * {@code started_at} — is written with {@code CURRENT_TIMESTAMP}, so cutoffs are computed from
     * the same clock. An app server whose clock ran ahead of the database by more than
     * {@link #WORKER_DEAD_AFTER} would otherwise see every live instance as dead.
     */
    private static Instant dbNow(Database db) {
        return db.jdbc(conn -> {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT CURRENT_TIMESTAMP")) {
                rs.next();
                return rs.getObject(1, java.time.OffsetDateTime.class).toInstant();
            }
        });
    }

    private static String describe(Duration d) {
        return d.toMillis() % 1000 == 0 ? d.toSeconds() + "s" : d.toMillis() + "ms";
    }

    private static int update(Database db, String sql, Object... params) {
        return db.jdbc(conn -> {
            try (var ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                return ps.executeUpdate();
            }
        });
    }

    private static void rollbackQuietly(Database db) {
        try {
            db.rollbackTransaction();
        } catch (Exception ignored) {
            // The connection may already be gone (an interrupted job); nothing left to undo.
        }
    }

    // --- Stats and retention ----------------------------------------------------------------

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
