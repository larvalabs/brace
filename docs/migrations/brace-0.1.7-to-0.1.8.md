# Migrating from Brace 0.1.7 → 0.1.8

**This release has no breaking changes.** No application code needs to change.

It fixes two ways a durable job could be lost or wedged forever, both of which apply to
apps that never touched the relevant configuration, and cuts durable-job pickup latency:

- **Durable jobs claimed by an instance that dies are now recovered** instead of being
  stranded permanently.
- **`Mailer` now bounds its SMTP timeouts**, so a wedged relay fails the send instead of
  hanging the calling thread forever.
- **Durable jobs now start within ~1 second** on an idle app, down from up to 10.

All three ship as new *defaults*. The only reason to touch your code is if you run jobs longer
than 30 minutes, talk to an unusually slow SMTP relay, or want to tune the poll rate — see below.

---

## Stalled durable jobs are recovered (new default)

### What was wrong

`JobPoller` commits `started_at` before running a job body, so other instances skip the row.
Every terminal outcome — completed, failed, released-for-retry — is written *after* the body
runs. If the process died in between, the row was left with `started_at` set and both terminal
timestamps NULL. No claim predicate matched it (all of them require `started_at IS NULL`), and
`purgeFinishedJobs` never deleted it (it filters on the terminal timestamps). The job was
permanently invisible and permanently undeletable.

This was not limited to crashes. `Brace.stop()` halts the poll loop but never joins the per-job
virtual threads, and virtual threads are always daemon — so **JVM exit kills in-flight jobs
mid-run, and every ordinary deploy could strand up to `poolSize / 2` jobs per instance.**

The knock-on effects compounded: a stranded parent blocked its entire `depends_on_id` subtree
forever, and those permanently-blocked children sat at the head of the claim query's `run_at`
ordering, taxing every subsequent poll on every instance.

### What changed

Jobs now hold their claim under a **lease**, default **30 minutes**. A background sweeper returns
expired claims to the queue:

- **Attempts remain** → `started_at` is cleared and the job is claimable again. The attempt the
  original claim spent is *not* refunded, so a job that strands repeatedly exhausts its budget
  rather than looping forever.
- **Attempts exhausted** → `failed_at` is set, matching what a live job writes when it runs out of
  retries. The row becomes terminal and prunable.

Nothing in the claim path changed — recovered rows re-enter through the ordinary query and index.

### What you may need to do

**If all your jobs finish well within 30 minutes: nothing.**

A lease cannot distinguish a dead instance from a job that is merely slow, so a job still running
when its lease expires **will be picked up again elsewhere**. This is consistent with the
at-least-once contract `DurableJob` already carried, but it makes idempotency matter in a case
where it previously didn't come up.

If you have long-running jobs, raise the lease above your longest expected runtime. It takes either
an interval string (`"30s"`, `"15m"`, `"2h"` — the same format `every()` uses) or a `Duration`:

```java
// Before (0.1.7): no lease existed; a killed job was stranded forever.
var app = Brace.app()
    .database(dbFactory);

// After (0.1.8): default is 30 minutes. Raise it if jobs run longer.
var app = Brace.app()
    .database(dbFactory)
    .jobLease("2h");                    // nightly report job takes ~90 min
```

Because it accepts a string, the lease can be tuned per environment from your existing config
without a code change — `Config.get` falls back to an environment variable, so this reads
`jobs.lease` from the conf file or `JOBS_LEASE` from the environment:

```java
var app = Brace.app()
    .database(dbFactory)
    .jobLease(config.get("jobs.lease", "30m"));
```

```
# brace.conf — a longer lease in production, where the nightly rollup runs
jobs.lease=30m
%prod.jobs.lease=2h
```

Keep the `"30m"` fallback in the `config.get` call. A null or blank string is treated as "keep the
default" rather than "disable", specifically so a missing key can't silently turn off recovery —
but relying on that is less clear than stating the default at the call site.

To keep the 0.1.7 behavior exactly — no recovery, stranded jobs stay stranded:

```java
var app = Brace.app()
    .database(dbFactory)
    .jobLease("0s");        // or jobLease((Duration) null)
```

### Cleaning up rows stranded before the upgrade

Jobs stranded by earlier deploys are still in `scheduled_jobs`. On startup the sweeper picks them
up automatically — they are indistinguishable from a fresh stall — so they will be retried (or
failed, if their attempts were spent) within a sweep interval of the first 0.1.8 boot.

**If you'd rather inspect them first**, before upgrading:

```sql
SELECT id, name, started_at, attempts, max_attempts
FROM scheduled_jobs
WHERE started_at IS NOT NULL AND completed_at IS NULL AND failed_at IS NULL
ORDER BY started_at;
```

Anything with an old `started_at` is stranded work. If some of it is stale enough that re-running
would be wrong (an expired promotional email, say), mark those rows failed before upgrading:

```sql
UPDATE scheduled_jobs SET failed_at = CURRENT_TIMESTAMP, error = 'abandoned before 0.1.8 upgrade'
WHERE started_at < '2026-01-01' AND completed_at IS NULL AND failed_at IS NULL;
```

### New framework migration

`V16__brace_scheduled_jobs_stalled_index.sql` (Postgres only) adds a partial index over
currently-claimed, unfinished rows so the sweep is an index scan rather than a sequential one. It
applies automatically on startup. In steady state the index holds at most `poolSize / 2` rows per
instance, so it costs effectively nothing to maintain.

### Manual sweeps

`JobPoller.reclaimStalledJobs(db, cutoff)` is public if you want to run recovery on your own
schedule (it returns `SweepResult(reclaimed, failed)`), the same way `purgeFinishedJobs` is public
for custom retention.

---

## Durable jobs are picked up in ~1 second (new default)

### What changed

The poller's idle wait dropped from **10 seconds to 1**. A job enqueued on an otherwise quiet app
now starts about 10× sooner. Under load nothing changes — a full batch has always re-polled
immediately, and the poller is paced by job completion, so the idle wait was never a throughput
limit.

The wait after a *partial* batch (previously a separate 1-second tier) is now the same configured
interval. At the 1-second default the two coincide; if you raise the interval, it applies to both,
so the setting means what it says rather than applying only to the fully-idle case.

### Why this is affordable

An empty poll matches no rows, so no tuple is written, no transaction ID is assigned, and the
commit needs no fsync. It costs one probe of the claim index plus the round trips — a fraction of
a percent of one pooled connection per instance, per second.

### What you may need to do

**For most apps: nothing.** The extra query volume is roughly one trivial query per second per
instance.

Two cases are worth a look before leaving it at the default:

- **A high-RTT database.** If your app and database are far apart (say 5–10ms), an idle poll is
  tens of milliseconds of connection hold rather than one or two. Still small, but no longer
  nothing at 1s across many instances.
- **Wide `depends_on_id` fan-out.** Dependency-blocked children sit in the claim index with
  `run_at` in the past, and the scan walks them before reaching claimable work. If you routinely
  have thousands of jobs blocked behind one unfinished parent, that per-poll cost now recurs 10×
  as often.

In either case, raise the interval:

```java
var app = Brace.app()
    .database(dbFactory)
    .jobPollInterval("5s");                                 // or a Duration

// or from config, like the lease:
var app = Brace.app()
    .database(dbFactory)
    .jobPollInterval(config.get("jobs.poll-interval", "1s"));
```

To restore the 0.1.7 idle behavior exactly, use `"10s"` — note this also makes partial batches wait
10 seconds, which 0.1.7 did not.

The interval must be positive; there is no disable value, since zero would spin the poll loop
against the database.

### What this does *not* fix

Pickup latency is per *link*, not end-to-end. Each step in a `depends_on_id` chain still costs
about one poll interval, because a child is invisible to the claim query until its parent's
completion commits. A 5-deep chain on an idle app goes from ~50s to ~5s — a real improvement, but
not to zero.

---

## Mailer SMTP timeouts are bounded (new default)

### What was wrong

`Mailer` set no SMTP timeouts, and Jakarta Mail defaults every one of them to **infinite**. A
blackholed or wedged relay hung `Transport.send` forever.

That was worse than a slow send. `send()` is synchronous, and the common case is sending from a
`DurableJob` — where the hung job held both its `JobPoller` execution slot and a pooled database
connection for the entire hang. With the default pool of 10 (so 5 job slots), five hung sends
wedged the whole job system permanently, with no recovery short of a restart.

### What changed

Defaults are now **10s connect** and **30s per read/write**, matching `Http`. A wedged relay now
produces an ordinary send failure — which `sendAsync()` logs and counts, and which the durable job
queue retries with backoff.

### What you may need to do

**For most apps: nothing.** Typical SMTP sends complete in 100ms–2s.

If you use a slow relay or send large attachments, raise the timeouts:

```java
// Before (0.1.7): unbounded — a wedged relay hung the caller forever.
var mail = new Mailer(config.get("smtp.url")).from("noreply@app.com");

// After (0.1.8): 10s connect / 30s per operation by default. Raise if your relay is slow.
var mail = new Mailer(config.get("smtp.url"))
    .from("noreply@app.com")
    .connectTimeout(Duration.ofSeconds(20))
    .timeout(Duration.ofMinutes(2));
```

Note `timeout()` bounds each individual socket read/write, not the send as a whole, so it does not
need to cover total transfer time for a large message — only the slowest single operation.

If a previously-hanging send now surfaces as a failure, that is the fix working: the error was
always there, it just used to present as a stuck thread instead of an exception.

---

## Upgrading

Bump `<brace.version>` to `0.1.8` and re-run `brace agents-md` to regenerate `BRACE-AGENTS.md`
from the new jar.
