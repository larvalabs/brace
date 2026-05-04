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
        var db = new Database(factory.openSession());
        List<Object[]> pendingJobs;
        try {
            db.beginTransaction();
            pendingJobs = db.sqlQuery(
                "SELECT id, name, job_class, job_data, attempts, max_attempts, backoff_seconds " +
                "FROM scheduled_jobs " +
                "WHERE run_at <= CURRENT_TIMESTAMP " +
                "AND completed_at IS NULL " +
                "AND failed_at IS NULL " +
                "AND started_at IS NULL " +
                "AND attempts < max_attempts " +
                "AND (depends_on_id IS NULL " +
                "     OR depends_on_id IN (SELECT id FROM scheduled_jobs WHERE completed_at IS NOT NULL)) " +
                "ORDER BY run_at " +
                "LIMIT 50"
            );
            db.commitTransaction();
        } finally {
            db.close();
        }

        if (pendingJobs.isEmpty()) {
            return 0;
        }

        var threads = new java.util.ArrayList<Thread>();
        for (var row : pendingJobs) {
            var thread = Thread.startVirtualThread(() -> executeJob(factory, row));
            threads.add(thread);
        }
        for (var thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return pendingJobs.size();
    }

    private void executeJob(DatabaseFactory factory, Object[] row) {
        long id = ((Number) row[0]).longValue();
        String jobClass = (String) row[2];
        String jobData = (String) row[3];
        int attempts = ((Number) row[4]).intValue();
        int maxAttempts = ((Number) row[5]).intValue();
        long backoffSeconds = ((Number) row[6]).longValue();

        // Claim the job
        var claimDb = new Database(factory.openSession());
        try {
            claimDb.beginTransaction();
            claimDb.sql("UPDATE scheduled_jobs SET started_at = CURRENT_TIMESTAMP, attempts = attempts + 1 WHERE id = ? AND started_at IS NULL", id);
            claimDb.commitTransaction();
        } catch (Exception e) {
            claimDb.rollbackTransaction();
            return; // Another poller claimed it
        } finally {
            claimDb.close();
        }

        // Execute the job
        var execDb = new Database(factory.openSession());
        try {
            execDb.beginTransaction();
            DurableJob job = (DurableJob) Class.forName(jobClass).getDeclaredConstructor().newInstance();
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
        long pending = countWithStatus(db, "completed_at IS NULL AND failed_at IS NULL AND started_at IS NULL");
        long running = countWithStatus(db, "started_at IS NOT NULL AND completed_at IS NULL AND failed_at IS NULL");
        long completed = countWithStatus(db, "completed_at IS NOT NULL");
        long failed = countWithStatus(db, "failed_at IS NOT NULL");
        return new DurableJobStats(pending, running, completed, failed);
    }

    private static long countWithStatus(Database db, String condition) {
        var result = db.sqlQueryLong("SELECT COUNT(*) FROM scheduled_jobs WHERE " + condition);
        return result != null ? result : 0;
    }
}
