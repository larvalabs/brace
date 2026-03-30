package io.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

public class Jobs {

    public static long schedule(Database db, DurableJob job, Duration delay) {
        return schedule(db, job, delay, new JobOptions());
    }

    public static long schedule(Database db, DurableJob job, Duration delay, JobOptions options) {
        var runAt = java.sql.Timestamp.from(Instant.now().plus(delay));
        var name = job.getClass().getSimpleName();
        var jobClass = job.getClass().getName();
        var data = job.data();

        db.sql("INSERT INTO scheduled_jobs (name, job_class, job_data, run_at, max_attempts, backoff_seconds, depends_on_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
            name, jobClass, data, runAt, options.maxAttempts(), options.backoff().toSeconds(), options.afterJobId());

        return db.sqlQueryLong("SELECT MAX(id) FROM scheduled_jobs");
    }

    public static <T> void parallel(List<T> items, int concurrency, Consumer<T> action) {
        var semaphore = new Semaphore(concurrency);
        var threads = new java.util.ArrayList<Thread>();
        for (var item : items) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            var thread = Thread.startVirtualThread(() -> {
                try {
                    action.accept(item);
                } finally {
                    semaphore.release();
                }
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
    }
}
