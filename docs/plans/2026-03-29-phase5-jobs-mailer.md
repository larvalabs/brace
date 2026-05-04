# Phase 5: Jobs & Mailer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add in-memory recurring job scheduler, database-backed durable job queue with adaptive poller, and a mailer with dev-mode email capture. At the end, a developer can schedule recurring tasks, queue durable one-off jobs with retry and dependencies, and send emails using JTE templates.

**Architecture:** In-memory jobs use `ScheduledExecutorService` on virtual threads. Durable jobs use a `scheduled_jobs` database table with an adaptive poller (10s idle, continuous when busy, parallel execution). Mailer wraps Jakarta Mail with JTE template rendering.

**Tech Stack:** java.util.concurrent (scheduler), Jakarta Mail (already a transitive dependency), JUnit 5

---

## File Structure

```
src/main/java/com/larvalabs/brace/
├── Job.java                    # Functional interface for recurring jobs
├── JobScheduler.java           # In-memory recurring scheduler
├── DurableJob.java             # Interface for persistent jobs
├── JobOptions.java             # Options: maxAttempts, backoff, after(jobId)
├── Jobs.java                   # Static API for scheduling durable jobs + parallel helper
├── JobPoller.java              # Adaptive poller for durable job queue
├── Mailer.java                 # Email sending with JTE templates
├── Brace.java                  # Updated — every(), daily(), cron(), mailer()
src/test/java/com/larvalabs/brace/
├── JobSchedulerTest.java
├── DurableJobTest.java
├── MailerTest.java
src/test/resources/
├── db/migration/
│   └── V2__create_scheduled_jobs.sql
```

---

### Task 1: Job Interface + In-Memory Scheduler

**Files:**
- Create: `src/main/java/com/larvalabs/brace/Job.java`
- Create: `src/main/java/com/larvalabs/brace/JobScheduler.java`
- Modify: `src/main/java/com/larvalabs/brace/Brace.java`
- Create: `src/test/java/com/larvalabs/brace/JobSchedulerTest.java`

- [ ] **Step 1: Create Job interface**

```java
package com.larvalabs.brace;

@FunctionalInterface
public interface Job {
    void run(Database db) throws Exception;
}
```

- [ ] **Step 2: Create JobScheduler**

JobScheduler manages in-memory recurring jobs. It:
- Uses a `ScheduledExecutorService` with virtual threads
- Supports `every(duration, name, job)`, `daily(time, name, job)`, `cron(expr, name, job)`
- Parses duration strings: "5m" → 5 minutes, "2h" → 2 hours, "30s" → 30 seconds
- Tracks job status: name, last run time, last duration, last status (ok/error), fail count, next run
- Provides `getJobStatuses()` for the ops layer
- Each job execution: opens a Database session (if DatabaseFactory available), runs the job, commits/rollbacks, closes

For cron parsing, use a simple approach — parse basic cron expressions or defer to a tiny library. Actually, for Phase 5 keep it simple: support `every()` and `daily()` only. Cron can come later. Daily uses a scheduled task that checks if it's time.

Duration parsing:
```java
private static Duration parseDuration(String s) {
    var value = Integer.parseInt(s.substring(0, s.length() - 1));
    var unit = s.charAt(s.length() - 1);
    return switch (unit) {
        case 's' -> Duration.ofSeconds(value);
        case 'm' -> Duration.ofMinutes(value);
        case 'h' -> Duration.ofHours(value);
        default -> throw new IllegalArgumentException("Unknown duration unit: " + unit);
    };
}
```

Job status tracking:
```java
public record JobStatus(String name, String schedule, Instant lastRun,
    Duration lastDuration, String lastStatus, String lastError, int failCount, Instant nextRun) {}
```

- [ ] **Step 3: Update Brace with job registration**

```java
private final JobScheduler jobScheduler = new JobScheduler();

public Brace every(String interval, String name, Job job) {
    jobScheduler.every(interval, name, job);
    return this;
}

public Brace daily(String time, String name, Job job) {
    jobScheduler.daily(time, name, job);
    return this;
}
```

In `start()`, pass the DatabaseFactory to JobScheduler so jobs can get DB sessions.
In `stop()`, shut down the scheduler.

- [ ] **Step 4: Write tests**

Test that jobs execute on schedule (use short intervals like "1s"), verify status tracking.

- [ ] **Step 5: Commit**

```
git commit -m "Phase 5 Task 1: In-memory recurring job scheduler"
```

---

### Task 2: Durable Job Queue

**Files:**
- Create: `src/main/java/com/larvalabs/brace/DurableJob.java`
- Create: `src/main/java/com/larvalabs/brace/JobOptions.java`
- Create: `src/main/java/com/larvalabs/brace/Jobs.java`
- Create: `src/main/java/com/larvalabs/brace/JobPoller.java`
- Create: `src/test/resources/db/migration/V2__create_scheduled_jobs.sql`
- Create: `src/test/java/com/larvalabs/brace/DurableJobTest.java`
- Modify: `src/main/java/com/larvalabs/brace/Brace.java`

- [ ] **Step 1: Create migration**

```sql
-- V2__create_scheduled_jobs.sql
CREATE TABLE scheduled_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    job_class VARCHAR(255) NOT NULL,
    job_data TEXT,
    run_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    failed_at TIMESTAMP,
    error TEXT,
    attempts INT DEFAULT 0,
    max_attempts INT DEFAULT 3,
    depends_on_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (depends_on_id) REFERENCES scheduled_jobs(id)
);

CREATE INDEX idx_scheduled_jobs_pending ON scheduled_jobs(run_at)
    WHERE completed_at IS NULL AND failed_at IS NULL AND started_at IS NULL;
```

Note: H2 may not support partial indexes (WHERE clause on CREATE INDEX). If so, create a regular index instead.

- [ ] **Step 2: Create DurableJob interface**

```java
package com.larvalabs.brace;

public interface DurableJob {
    String data();
    void run(String data, Database db) throws Exception;
}
```

- [ ] **Step 3: Create JobOptions**

```java
package com.larvalabs.brace;

import java.time.Duration;

public class JobOptions {
    private int maxAttempts = 3;
    private Duration backoff = Duration.ofMinutes(1);
    private Long afterJobId = null;

    public static JobOptions maxAttempts(int n) {
        var opts = new JobOptions();
        opts.maxAttempts = n;
        return opts;
    }

    public JobOptions backoff(Duration d) {
        this.backoff = d;
        return this;
    }

    public static JobOptions after(long jobId) {
        var opts = new JobOptions();
        opts.afterJobId = jobId;
        return opts;
    }

    public int maxAttempts() { return maxAttempts; }
    public Duration backoff() { return backoff; }
    public Long afterJobId() { return afterJobId; }
}
```

- [ ] **Step 4: Create Jobs static API**

```java
package com.larvalabs.brace;

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
        var runAt = Instant.now().plus(delay);
        // Insert into scheduled_jobs table via native SQL
        db.sql("INSERT INTO scheduled_jobs (name, job_class, job_data, run_at, max_attempts, depends_on_id) VALUES (?, ?, ?, ?, ?, ?)",
            job.getClass().getSimpleName(),
            job.getClass().getName(),
            job.data(),
            java.sql.Timestamp.from(runAt),
            options.maxAttempts(),
            options.afterJobId());
        // Return the generated ID — need to query for it
        // Use a native query to get last insert id
        // This is H2-specific... use SCOPE_IDENTITY() or similar
        // Actually, simplest: return the ID via a query
        var result = db.hql("SELECT MAX(id) FROM ScheduledJob"); // won't work — no entity
        // Use native SQL instead
        // This is getting complex. Let's use a simpler approach.
        return -1; // TODO: return actual ID
    }

    public static <T> void parallel(List<T> items, int concurrency, Consumer<T> action) {
        var semaphore = new Semaphore(concurrency);
        var threads = new java.util.ArrayList<Thread>();
        for (var item : items) {
            try { semaphore.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
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
            try { thread.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public static void updateData(Database db, long jobId, String data) {
        db.sql("UPDATE scheduled_jobs SET job_data = ? WHERE id = ?", data, jobId);
    }
}
```

Note: The `schedule()` method needs to return the generated ID for job dependencies. Use native SQL with H2's `CALL SCOPE_IDENTITY()` or use JDBC's `getGeneratedKeys()`. Since Database wraps StatelessSession, this is tricky.

Simplest approach: after inserting, query for the max ID with matching name and run_at. Not perfect for concurrency but works for now.

Or better: use Database's underlying session to execute a native query that returns the ID. Add a `sqlReturningLong()` method to Database.

- [ ] **Step 5: Create JobPoller**

JobPoller runs as an in-memory job on a 10-second interval. It:
1. Claims pending jobs: UPDATE ... SET started_at = NOW(), attempts = attempts + 1 WHERE ready
2. For each claimed job: instantiate the class by name, call run(data, db), mark complete or handle failure
3. Adaptive behavior: if batch was full (50 jobs), immediately poll again

The poller needs access to DatabaseFactory to open sessions for each job.

- [ ] **Step 6: Wire into Brace**

Start the poller as an internal scheduled job when a database is configured.

- [ ] **Step 7: Write tests**

Test: schedule a job, run poller, verify it executes. Test retry on failure. Test job dependencies.

- [ ] **Step 8: Commit**

```
git commit -m "Phase 5 Task 2: Durable job queue with adaptive poller"
```

---

### Task 3: Mailer

**Files:**
- Create: `src/main/java/com/larvalabs/brace/Mailer.java`
- Modify: `src/main/java/com/larvalabs/brace/Brace.java`
- Create: `src/test/java/com/larvalabs/brace/MailerTest.java`

- [ ] **Step 1: Implement Mailer**

Mailer has two modes:
- **Dev mode (default when no SMTP configured):** Captures emails in a list, doesn't send
- **Prod mode:** Sends via Jakarta Mail SMTP

```java
package com.larvalabs.brace;

import java.util.*;

public class Mailer {

    private final String smtpUrl;  // null = dev mode (capture only)
    private String defaultFrom;
    private String defaultReplyTo;
    private final List<CapturedEmail> captured = new ArrayList<>();

    public Mailer(String smtpUrl) {
        this.smtpUrl = smtpUrl;
    }

    public Mailer from(String from) { this.defaultFrom = from; return this; }
    public Mailer replyTo(String replyTo) { this.defaultReplyTo = replyTo; return this; }

    public EmailBuilder to(String address) {
        return new EmailBuilder(this, address);
    }

    // Dev mode: capture emails
    public List<CapturedEmail> sent() { return List.copyOf(captured); }
    public CapturedEmail last() { return captured.isEmpty() ? null : captured.get(captured.size() - 1); }
    public void clearCaptured() { captured.clear(); }

    void send(EmailBuilder email) {
        var captured = new CapturedEmail(
            email.to, email.cc, email.subject, email.textBody, email.htmlBody, email.from != null ? email.from : defaultFrom
        );
        this.captured.add(captured);

        if (smtpUrl != null) {
            sendSmtp(email);
        }
    }

    private void sendSmtp(EmailBuilder email) {
        // Jakarta Mail SMTP sending
        // Parse smtpUrl: smtp://user:pass@host:port or smtps://...
        // Create Session, MimeMessage, Transport.send()
        // Implement in a straightforward way
    }

    public record CapturedEmail(String to, String cc, String subject, String text, String html, String from) {}

    public static class EmailBuilder {
        private final Mailer mailer;
        String to;
        String cc;
        String from;
        String subject;
        String textBody;
        String htmlBody;

        EmailBuilder(Mailer mailer, String to) {
            this.mailer = mailer;
            this.to = to;
        }

        public EmailBuilder cc(String cc) { this.cc = cc; return this; }
        public EmailBuilder from(String from) { this.from = from; return this; }
        public EmailBuilder subject(String subject) { this.subject = subject; return this; }
        public EmailBuilder text(String body) { this.textBody = body; return this; }
        public EmailBuilder html(String html) { this.htmlBody = html; return this; }

        public void send() {
            mailer.send(this);
        }
    }
}
```

For the SMTP implementation, parse the URL and use Jakarta Mail. But for Phase 5, focus on the capture mode (dev mode) which is what tests use. The SMTP sending can be basic — it only needs to work, not be optimized.

- [ ] **Step 2: Update Brace**

```java
private Mailer mailer;

public Brace mailer(Mailer mailer) {
    this.mailer = mailer;
    return this;
}

public static Mailer mailer(String smtpUrl) {
    return new Mailer(smtpUrl);
}
```

- [ ] **Step 3: Write tests**

Test capture mode: send emails, verify they're captured. Test EmailBuilder API. Test View.render() for HTML emails.

- [ ] **Step 4: Commit**

```
git commit -m "Phase 5 Task 3: Mailer with dev-mode email capture"
```

---

## Phase 5 Complete

At this point:
- In-memory recurring jobs (`every`, `daily`)
- Durable job queue with adaptive poller, retry, dependencies
- `Jobs.parallel()` helper for concurrent processing
- Mailer with dev-mode capture and SMTP sending
- All integrated into Brace builder

**Next:** Phase 6 adds the AI ops layer (diagnostics endpoint, structured logging, dashboard, deploy hooks).
