package com.larvalabs.brace;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class JobScheduler {

    public record JobStatus(
        String name, String schedule, Instant lastRun,
        long lastDurationMs, String lastStatus, String lastError,
        int failCount, Instant nextRun, String lastMessage
    ) {}

    private record RegisteredJob(String name, String schedule, long periodMs, long initialDelayMs,
                                Job job, boolean local) {}

    private final CopyOnWriteArrayList<RegisteredJob> registeredJobs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<JobStatus> statuses = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;
    private DatabaseFactory dbFactory;

    public void every(String interval, String name, Job job) {
        register(interval, name, job, false);
    }

    /**
     * Like {@link #every}, but runs on <em>every</em> instance each interval rather than once
     * cluster-wide (no leader claim). For framework per-instance work — notably the ops metric
     * flush (P3), where each instance must write its own instance-tagged {@code ops_timeseries}
     * rows. Not for user jobs with external side effects (those want the deduped {@link #every}).
     */
    void everyLocal(String interval, String name, Job job) {
        register(interval, name, job, true);
    }

    private void register(String interval, String name, Job job, boolean local) {
        long periodMs = parseInterval(interval);
        var rj = new RegisteredJob(name, "every " + interval, periodMs, periodMs, job, local);
        registeredJobs.add(rj);
        statuses.add(new JobStatus(name, "every " + interval, null, 0, "pending", null, 0, null, null));

        // If scheduler is already running, schedule immediately
        if (scheduler != null) {
            final int index = registeredJobs.size() - 1;
            if (!local) seedRunRow(name);
            scheduler.scheduleAtFixedRate(() -> {
                Thread.startVirtualThread(() -> executeJob(index, rj));
            }, rj.initialDelayMs(), rj.periodMs(), TimeUnit.MILLISECONDS);
        }
    }

    public void daily(String time, String name, Job job) {
        LocalTime targetTime = LocalTime.parse(time);
        long initialDelayMs = computeDelayUntil(targetTime);
        long periodMs = Duration.ofHours(24).toMillis();
        var rj = new RegisteredJob(name, "daily at " + time, periodMs, initialDelayMs, job, false);
        registeredJobs.add(rj);
        Instant nextRun = Instant.now().plusMillis(initialDelayMs);
        statuses.add(new JobStatus(name, "daily at " + time, null, 0, "pending", null, 0, nextRun, null));

        // If the scheduler is already running, schedule immediately — same late-registration branch
        // register() has. Without this a daily job added after start() was tracked in statuses but
        // never handed to the executor, so it silently never ran. daily is always cluster-deduped.
        if (scheduler != null) {
            final int index = registeredJobs.size() - 1;
            seedRunRow(name);
            scheduler.scheduleAtFixedRate(() -> {
                Thread.startVirtualThread(() -> executeJob(index, rj));
            }, rj.initialDelayMs(), rj.periodMs(), TimeUnit.MILLISECONDS);
        }
    }

    public void start(DatabaseFactory dbFactory) {
        this.dbFactory = dbFactory;
        this.scheduler = Executors.newScheduledThreadPool(1);

        for (int i = 0; i < registeredJobs.size(); i++) {
            var rj = registeredJobs.get(i);
            final int index = i;
            if (!rj.local()) seedRunRow(rj.name());
            scheduler.scheduleAtFixedRate(() -> {
                Thread.startVirtualThread(() -> executeJob(index, rj));
            }, rj.initialDelayMs(), rj.periodMs(), TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
    }

    public List<JobStatus> getStatuses() {
        return List.copyOf(statuses);
    }

    private void executeJob(int index, RegisteredJob rj) {
        // Cluster-wide dedupe: when a database is configured, only the instance
        // that claims this interval runs the job. Without a database (single
        // process, e.g. tests) the scheduler runs unconditionally as before.
        if (!rj.local() && dbFactory != null && !claimRun(rj)) {
            return; // another instance already ran this interval (cluster-wide dedupe)
        }

        Instant start = Instant.now();
        var ctx = new JobContext();
        org.hibernate.StatelessSession session = null;
        try {
            Database db = null;
            if (dbFactory != null) {
                session = dbFactory.openSession();
                db = new Database(session);
                session.getTransaction().begin();
            }

            rj.job().run(db, ctx);

            if (session != null) {
                session.getTransaction().commit();
            }

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            Instant nextRun = Instant.now().plusMillis(rj.periodMs());
            var msg = ctx.consumeMessage();
            statuses.set(index, new JobStatus(
                rj.name(), rj.schedule(), start, durationMs, "ok", null,
                statuses.get(index).failCount(), nextRun,
                msg != null ? msg : statuses.get(index).lastMessage()
            ));
        } catch (Exception e) {
            if (session != null) {
                try { session.getTransaction().rollback(); } catch (Exception ignored) {}
            }

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            Instant nextRun = Instant.now().plusMillis(rj.periodMs());
            var prev = statuses.get(index);
            var msg = ctx.consumeMessage();
            statuses.set(index, new JobStatus(
                rj.name(), rj.schedule(), start, durationMs, "error", e.getMessage(),
                prev.failCount() + 1, nextRun,
                msg != null ? msg : prev.lastMessage()
            ));
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Ensures a coordination row exists for {@code name} before its timer starts,
     * so the hot-path {@link #claimRun} only ever does SELECT … FOR UPDATE + UPDATE
     * (never an INSERT that could hit a primary-key race mid-transaction). Idempotent
     * and best-effort: a concurrent seed from another instance that loses the
     * primary-key race, or a transient error, is harmless — the row exists either way.
     */
    private void seedRunRow(String name) {
        if (dbFactory == null) return;
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            db.sql("INSERT INTO brace_scheduled_runs (job_name) " +
                   "SELECT ? WHERE NOT EXISTS (SELECT 1 FROM brace_scheduled_runs WHERE job_name = ?)",
                   name, name);
            db.commitTransaction();
        } catch (Exception e) {
            db.rollbackTransaction();
        } finally {
            db.close();
        }
    }

    /**
     * Returns true if THIS instance should run {@code rj} for the current interval.
     * Coordinates across instances by claiming the current time-slot under a row lock
     * on the job's brace_scheduled_runs row: slot = floor(now / period). The first
     * instance to reach a new slot advances last_run_slot and runs; any instance still
     * on an already-claimed slot reads the advanced value and skips. Because every
     * instance derives the same slot number from wall-clock, this is exactly-once per
     * interval regardless of tick stagger (clocks need only be synced within one
     * period). Uses a row lock — portable to H2 for tests — not a Postgres-only
     * advisory lock.
     */
    private boolean claimRun(RegisteredJob rj) {
        long currentSlot = Instant.now().toEpochMilli() / rj.periodMs();

        // Fast path (M14): a NON-locking read. On a multi-instance cluster the slot is almost always
        // already claimed — by another instance, or by this one on an earlier tick within the same slot
        // — and that case must not take a row lock. Previously every tick on every instance ran
        // SELECT … FOR UPDATE, so an every("1s") job on N instances was N write-lock acquisitions per
        // second serialized on one row. Now it's N cheap snapshot reads, and the lock is taken only by
        // the (rare) instances that find the slot genuinely unclaimed. A stale read can only under-report
        // the claim (READ COMMITTED never shows an uncommitted newer slot), so the worst case is falling
        // through to the locked path and re-checking there — never a missed skip, never a duplicate run.
        try {
            var db = new Database(dbFactory.openSession());
            try {
                db.beginTransaction();
                Long lastSlot = db.jdbc(conn -> {
                    try (var ps = conn.prepareStatement(
                            "SELECT last_run_slot FROM brace_scheduled_runs WHERE job_name = ?")) {
                        ps.setString(1, rj.name());
                        try (var rs = ps.executeQuery()) {
                            if (!rs.next()) return null; // row missing → let the locked path decide
                            long v = rs.getLong(1);
                            return rs.wasNull() ? null : v; // null slot (freshly seeded) → not yet claimed
                        }
                    }
                });
                db.commitTransaction();
                if (lastSlot != null && lastSlot >= currentSlot) {
                    return false; // already claimed this slot — no lock needed
                }
            } finally {
                db.close();
            }
        } catch (Exception e) {
            // A transient read error shouldn't drop a run — fall through and decide under the lock.
            Log.warn("scheduler-claim-read-failed job=" + rj.name() + " error=" + e.getMessage());
        }

        // Slow path: the slot looks unclaimed (or the row is missing). Take the row lock and re-check
        // under it — another instance may have claimed between our unlocked read and acquiring the lock.
        return claimUnderLock(rj, currentSlot);
    }

    /**
     * The authoritative claim: SELECT … FOR UPDATE on the job's coordination row, re-check the slot under
     * the lock, and advance it iff still unclaimed. Reached only when {@link #claimRun}'s unlocked read
     * found the slot open, so this — and its row lock — runs at most once per slot per instance that races
     * for it, not on every tick. The exactly-once guarantee lives here, unchanged from the original.
     */
    private boolean claimUnderLock(RegisteredJob rj, long currentSlot) {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            var nowTs = Timestamp.from(Instant.now());
            boolean claimed = db.jdbc(conn -> {
                try (var ps = conn.prepareStatement(
                        "SELECT last_run_slot FROM brace_scheduled_runs WHERE job_name = ? FOR UPDATE")) {
                    ps.setString(1, rj.name());
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            // Row missing (seed failed/raced). Run rather than silently skip;
                            // the seed normally guarantees a row so this is a rare fallback.
                            return true;
                        }
                        long lastSlot = rs.getLong(1);
                        if (!rs.wasNull() && lastSlot >= currentSlot) {
                            return false; // claimed by another instance since our unlocked read
                        }
                    }
                }
                try (var ps = conn.prepareStatement(
                        "UPDATE brace_scheduled_runs SET last_run_slot = ?, last_run_at = ? WHERE job_name = ?")) {
                    ps.setLong(1, currentSlot);
                    ps.setTimestamp(2, nowTs);
                    ps.setString(3, rj.name());
                    ps.executeUpdate();
                }
                return true;
            });
            db.commitTransaction();
            return claimed;
        } catch (Exception e) {
            db.rollbackTransaction();
            // Coordination failed (e.g. transient DB error). Skip this tick rather than
            // risk a duplicate side effect — the job runs again next interval. Preventing
            // N-fold duplicate runs is the whole point, so favor skip over duplicate.
            Log.warn("scheduler-claim-failed job=" + rj.name() + " error=" + e.getMessage());
            return false;
        } finally {
            db.close();
        }
    }

    static long parseInterval(String interval) {
        if (interval == null || interval.length() < 2) {
            throw new IllegalArgumentException("Invalid interval: " + interval);
        }
        String numberPart = interval.substring(0, interval.length() - 1);
        char unit = interval.charAt(interval.length() - 1);
        long value = Long.parseLong(numberPart);
        return switch (unit) {
            case 's' -> value * 1000;
            case 'm' -> value * 60 * 1000;
            case 'h' -> value * 60 * 60 * 1000;
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit + " in interval: " + interval);
        };
    }

    static long computeDelayUntil(LocalTime targetTime) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime target = now.with(targetTime);
        if (target.isBefore(now) || target.isEqual(now)) {
            target = target.plusDays(1);
        }
        return Duration.between(now, target).toMillis();
    }
}
