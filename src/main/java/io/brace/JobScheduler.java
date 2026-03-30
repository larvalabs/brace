package io.brace;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
        int failCount, Instant nextRun
    ) {}

    private record RegisteredJob(String name, String schedule, long periodMs, long initialDelayMs, Job job) {}

    private final CopyOnWriteArrayList<RegisteredJob> registeredJobs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<JobStatus> statuses = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;
    private DatabaseFactory dbFactory;

    public void every(String interval, String name, Job job) {
        long periodMs = parseInterval(interval);
        var rj = new RegisteredJob(name, "every " + interval, periodMs, periodMs, job);
        registeredJobs.add(rj);
        statuses.add(new JobStatus(name, "every " + interval, null, 0, "pending", null, 0, null));

        // If scheduler is already running, schedule immediately
        if (scheduler != null) {
            final int index = registeredJobs.size() - 1;
            scheduler.scheduleAtFixedRate(() -> {
                Thread.startVirtualThread(() -> executeJob(index, rj));
            }, rj.initialDelayMs(), rj.periodMs(), TimeUnit.MILLISECONDS);
        }
    }

    public void daily(String time, String name, Job job) {
        LocalTime targetTime = LocalTime.parse(time);
        long initialDelayMs = computeDelayUntil(targetTime);
        long periodMs = Duration.ofHours(24).toMillis();
        registeredJobs.add(new RegisteredJob(name, "daily at " + time, periodMs, initialDelayMs, job));
        Instant nextRun = Instant.now().plusMillis(initialDelayMs);
        statuses.add(new JobStatus(name, "daily at " + time, null, 0, "pending", null, 0, nextRun));
    }

    public void start(DatabaseFactory dbFactory) {
        this.dbFactory = dbFactory;
        this.scheduler = Executors.newScheduledThreadPool(1);

        for (int i = 0; i < registeredJobs.size(); i++) {
            var rj = registeredJobs.get(i);
            final int index = i;
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
        Instant start = Instant.now();
        org.hibernate.StatelessSession session = null;
        try {
            Database db = null;
            if (dbFactory != null) {
                session = dbFactory.openSession();
                db = new Database(session);
                session.getTransaction().begin();
            }

            rj.job().run(db);

            if (session != null) {
                session.getTransaction().commit();
            }

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            Instant nextRun = Instant.now().plusMillis(rj.periodMs());
            statuses.set(index, new JobStatus(
                rj.name(), rj.schedule(), start, durationMs, "ok", null,
                statuses.get(index).failCount(), nextRun
            ));
        } catch (Exception e) {
            if (session != null) {
                try { session.getTransaction().rollback(); } catch (Exception ignored) {}
            }

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            Instant nextRun = Instant.now().plusMillis(rj.periodMs());
            var prev = statuses.get(index);
            statuses.set(index, new JobStatus(
                rj.name(), rj.schedule(), start, durationMs, "error", e.getMessage(),
                prev.failCount() + 1, nextRun
            ));
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
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
