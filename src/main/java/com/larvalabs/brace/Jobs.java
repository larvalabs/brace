package com.larvalabs.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

public class Jobs {

    private static final ExecutorService ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final LongAdder ASYNC_SUBMITTED = new LongAdder();
    private static final LongAdder ASYNC_FAILED = new LongAdder();

    /**
     * Fire-and-forget background work on a virtual thread. Exceptions are caught and
     * logged via {@link Log#error(String, Throwable)} so a failed task never silently
     * eats the calling thread.
     *
     * Non-durable: if the JVM exits before the task completes, the work is lost.
     * For at-least-once delivery use {@link #schedule(Database, DurableJob, Duration)}.
     */
    public static void run(Runnable task) {
        ASYNC_SUBMITTED.increment();
        ASYNC_EXECUTOR.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                ASYNC_FAILED.increment();
                Log.error("async-task-failed", t);
            }
        });
    }

    /**
     * Submit a background task and get a {@link Future} for its result. Exceptions
     * propagate through the future via {@link java.util.concurrent.ExecutionException}.
     * Non-durable; see {@link #run(Runnable)} for the trade-offs.
     */
    public static <T> Future<T> submit(Callable<T> task) {
        ASYNC_SUBMITTED.increment();
        return ASYNC_EXECUTOR.submit(() -> {
            try {
                return task.call();
            } catch (Throwable t) {
                ASYNC_FAILED.increment();
                throw t;
            }
        });
    }

    /** Total async tasks submitted via {@link #run} or {@link #submit}. */
    public static long asyncSubmitted() { return ASYNC_SUBMITTED.sum(); }

    /** Total async tasks that threw an exception. */
    public static long asyncFailed() { return ASYNC_FAILED.sum(); }

    public static long schedule(Database db, DurableJob job, Duration delay) {
        return schedule(db, job, delay, new JobOptions());
    }

    public static long schedule(Database db, DurableJob job, Duration delay, JobOptions options) {
        var runAt = java.sql.Timestamp.from(Instant.now().plus(delay));
        var name = job.getClass().getSimpleName();
        var jobClass = job.getClass().getName();
        var data = job.data();

        return db.jdbc(conn -> {
            var ps = conn.prepareStatement(
                "INSERT INTO scheduled_jobs (name, job_class, job_data, run_at, max_attempts, backoff_seconds, depends_on_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, jobClass);
            ps.setString(3, data);
            ps.setTimestamp(4, runAt);
            ps.setInt(5, options.maxAttempts());
            ps.setLong(6, options.backoff().toSeconds());
            if (options.afterJobId() != null) {
                ps.setLong(7, options.afterJobId());
            } else {
                ps.setNull(7, java.sql.Types.BIGINT);
            }
            ps.executeUpdate();
            var rs = ps.getGeneratedKeys();
            rs.next();
            return rs.getLong(1);
        });
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
