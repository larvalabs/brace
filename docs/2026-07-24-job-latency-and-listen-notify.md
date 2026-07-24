# Plan: Job pickup latency and LISTEN/NOTIFY cost

Status: Proposed — no code changes yet
Date: 2026-07-24

## Origin

Prompted by [DBOS, "Postgres LISTEN/NOTIFY doesn't scale (but it can)"](https://www.dbos.dev/blog/postgres-listen-notify-scalability). The article's finding: a transaction that issues `NOTIFY` takes `NotifyQueueLock` exclusively in `PreCommit_Notify` and holds it across the commit's WAL flush, so notification order matches commit order. Only notify-carrying transactions take that lock, but they cannot group-commit, so throughput is bounded at roughly one fsync each. They measured **2.9K notify-carrying commits/sec with idle CPU, memory and I/O** — it presents as a hang, not as saturation. Their fix was to buffer notifications and flush one batched `NOTIFY` periodically, with low-frequency polling as the safety net; that reached **60K/s at 15–100ms latency**.

Reviewing Brace against it produced two separate conclusions, which is why this doc covers two components:

- **`JobPoller` uses no `NOTIFY` at all.** It is immune to the article's bottleneck. Its issue is pickup *latency*, and the article is a strong argument against the obvious fix.
- **`PostgresMessageBus` is the article's "before" case, structurally verbatim.** One `NOTIFY`, in its own transaction, per broadcast.

## Non-goals

- Adding `NOTIFY` to the job enqueue path. Section 2 argues against it directly.
- Replacing the polling claim (`FOR UPDATE SKIP LOCKED` + the `V15` partial index). That design is sound and the article validates it.
- Changing `MessageBus` delivery semantics. At-most-once, currently-connected-members-only stays as documented.

---

# Part 1 — Job pickup latency

## Current behavior

`JobPoller.pollLoop` (`src/main/java/com/larvalabs/brace/JobPoller.java:50-77`) is a three-step function, not a curve:

| Last poll claimed | Sleep before next poll |
|---|---|
| Full batch (== free slots) | 0 — immediate re-poll |
| Partial batch | 1s |
| Nothing | **10s** |

`dispatch` blocks on `limiter.acquire()` *before* claiming, so the poller can never poll while all its execution slots are busy. That means the 10s branch is reached only when the queue is genuinely idle — which is exactly the "app is quiet, a user does something, how long until the job starts" case. Worst-case pickup for a newly enqueued job on an idle app is ~10s.

The interval is a hardcoded literal. There is no config key for it (`Config` has no `jobs.*` keys today).

## Option A — shorten the idle sleep to 1–2s

**This is the recommended first move**, and the analysis below is mostly an attempt to falsify it. It largely survives.

### What an empty poll actually costs

Not zero, but close, and the cost is round trips rather than database work:

1. Hikari checkout from the app's shared pool (default `poolSize` 10 — `DatabaseFactory.java:29-31`).
2. `BEGIN`.
3. The claim `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING …`. When it matches zero rows, no tuple is updated and no row is locked, so **no XID is assigned, no WAL is written, and the commit needs no fsync.**
4. `COMMIT`, return connection.

So an empty poll is an index probe plus ~3–4 network round trips. Call it **~1–2ms against a local database, ~20–40ms against a remote one at 5–10ms RTT.** At a 1s interval that is a 0.1%–4% occupancy of one connection out of ten. It scales linearly with instance count: 20 instances at 1s is 20 trivially cheap queries/sec against the database.

Verdict on cost: negligible on a local/VPC database, and still fine but no longer nothing on a high-RTT one. The remote case is the reason to make the interval configurable rather than to leave a different hardcoded literal.

### The one case where "free" genuinely breaks down

The `V15` partial index is `ON scheduled_jobs (run_at) WHERE completed_at IS NULL AND failed_at IS NULL AND started_at IS NULL`. It does **not** filter on `run_at` or on dependency state.

- **Future-scheduled backlog is fine.** The index is ordered on `run_at` and the query filters `run_at <= CURRENT_TIMESTAMP`, so the scan stops at the first future row. A large backlog of jobs scheduled for later costs nothing per poll.
- **Dependency-blocked backlog is not.** Rows with `run_at <= now()` whose `depends_on_id` parent is unfinished sit in the index and pass the `run_at` filter. The `LIMIT` applies *after* filtering, so the planner must walk them and evaluate the `NOT EXISTS` PK probe on each until it finds `slots` (≤ `poolSize/2`, default 5) that qualify — or exhausts the range. A fan-out of 10k children waiting on one unfinished parent means ~10k index entries plus ~10k PK probes **per poll**. Going 10s → 1s multiplies that by ten.

This is the honest caveat: for a queue that uses wide `depends_on_id` fan-out, shortening the interval is not free and the cost is proportional to blocked-row count. Mitigations, if that shape ever shows up in practice: index `depends_on_id`, or track blocked jobs out of the claimable index entirely. Not worth doing preemptively — noting it so the symptom is recognizable.

### Where 1s still doesn't get you all the way: dependency chains

Each link in a `depends_on_id` chain costs roughly one idle interval, because the child is invisible to the claim query until the parent's `completed_at` commits, and on an idle app the poller has typically just gone to sleep by then. A 5-deep chain costs ~50s today, ~5s at a 1s interval, ~0 with an event-driven wake. So the interval multiplies by chain depth. A 10× improvement is still a 10× improvement, but "1s pickup" is a per-link number, not an end-to-end one.

### Tradeoffs

| | |
|---|---|
| **Effort** | One constant, ideally promoted to a config key with a 1–2s default |
| **Risk** | Very low. No new failure modes; `SKIP LOCKED` already makes concurrent pollers get disjoint batches, so more frequent polling does not introduce contention |
| **Gets you** | 10× worst-case latency cut, 10s → 1s |
| **Costs you** | ~1 trivial query/sec/instance; materially more only under wide dependency fan-out (above) or high DB RTT |
| **Doesn't get you** | Sub-second pickup; end-to-end latency on deep chains |

**Recommendation: do this, and make it configurable rather than re-hardcoding.** A batch app polling every 1s across 20 instances for a queue that gets work twice a day is pure waste; an interactive app wants 500ms. Hardcoding 1s just relocates the arbitrary number.

## Option B — exponential idle backoff

Start at ~200ms, double to a 5–10s cap, reset to the floor whenever a poll finds work.

Attractive because it self-tunes: during an active burst the interval stays short, so chained and follow-on jobs get picked up quickly, while a genuinely idle app settles to a low steady-state query rate.

But it is **not strictly better than a flat interval**, and it is worth being precise about why: an app that receives one job every five minutes is always at the cap when that job arrives. Compared to flat 1s, exponential-capped-at-5s has a *worse* worst case and a *better* idle cost. That is a trade, not a win. It only dominates for bursty or chain-heavy workloads.

**Recommendation: defer.** Refinement on top of Option A, justified by a real workload, not up front.

## Option C — in-process wake on enqueue

Correcting an earlier characterization of this as "zero DB cost, ~10 lines": it is meaningfully more subtle than that, and the subtlety is what makes Option A the better effort/reward trade.

`Jobs.schedule()` (`Jobs.java:67-93`) inserts the row inside **the caller's** transaction, which is typically a web request transaction that `BraceHandler` commits later. Unparking the poller at insert time means the poller very likely polls *before* that commit, sees nothing under READ COMMITTED, and goes back to sleep for a full interval — so the naive wake is not merely ineffective, it can consume the poll you were relying on and make latency worse than not waking at all.

Doing it correctly needs one of:

- **A post-commit hook on `Database`.** Correct, and the right long-term shape, but it is real plumbing that does not exist today and touches the request lifecycle.
- **A defensive heuristic** — wake, sleep briefly, poll, and stay on a short interval for a few cycles if the first poll comes back empty. Cheap but imprecise, and it converges toward Option B anyway.

Also needs a guard so `Jobs.schedule(db, job, Duration.ofHours(1))` doesn't wake the poller for work that isn't due.

Upside if built: near-zero pickup and near-zero chain latency for single-instance apps, which is most Brace deployments, with no database traffic at all.

**Recommendation: defer.** Revisit if sub-second pickup becomes a requirement; prefer the post-commit-hook version over the heuristic if so.

## Option D — LISTEN/NOTIFY on enqueue

**Recommendation: do not do this.** This is the option the article argues against most directly.

Job enqueues happen inside user request transactions. Adding `pg_notify` to `Jobs.schedule()` would move **every request that schedules a job** into the serialized notify commit path — adding fsync-serialized commit latency to the request path and capping enqueue throughput near the article's 2.9K/s, in exchange for saving polling latency that Options A–C address for far less. That is "every stream write includes a call to `NOTIFY`" verbatim.

If cross-instance sub-second pickup is ever genuinely required, the article's own prescription applies: a *coalesced* notify — one notification per flush tick from a buffer, never one per enqueue — with polling retained as the safety net. That is strictly more complex than Option C and should not be reached for first.

---

# Part 2 — `PostgresMessageBus`

This is where the article actually bites.

## Current behavior

`publish()` (`PostgresMessageBus.java:77-96`) opens a pooled session, begins a transaction, issues `SELECT pg_notify(...)`, and commits. **One `NOTIFY` per broadcast, one commit per broadcast** — structurally identical to the article's 2.9K/s baseline.

The call is synchronous on the caller's thread: `WsContext.broadcast` → `WsRegistry.broadcast` (`WsRegistry.java:57-58`) → `bus.publish`. For a WebSocket app that is the Jetty message-handling thread, so once the notify lock is contended, application threads block on it directly.

Three things make our version somewhat worse than the article's baseline:

1. **Pool pressure.** Each broadcast checks out a Hikari connection and does `BEGIN`/`COMMIT` round trips. Because those commits serialize, connections are held *longer* as load rises — a broadcast storm competes with web handlers for a default pool of 10 while making no forward progress.
2. **Per-message reap on the spill path.** `spillAndNotify` runs `DELETE FROM brace_ws_messages WHERE created_at < now() - INTERVAL '60 seconds'` on **every** spilled broadcast (`PostgresMessageBus.java:122-125`), inside the same notify-carrying transaction, concurrently across all instances.
3. **Listener head-of-line blocking.** `deliver()` calls `fetchSpill` synchronously (`:195-199`) — a full DB round trip per spilled message on the single listener thread. A slow listener is precisely how the Postgres async queue backs up; once it can't be truncated, Postgres logs "NOTIFY queue is N% full" and eventually errors on `NOTIFY` for every backend on the instance.

**Severity is entirely a function of broadcast rate.** A dashboard pushing occasional updates will never notice. A chat-style app broadcasting per message hits the ceiling, and it presents as unexplained latency against an idle-looking database — the article's exact symptom.

## Proposed changes

Ordered by value:

1. **Buffer and coalesce publishes.** A bounded queue plus a background flusher on a ~5–20ms tick, emitting one `NOTIFY` carrying a JSON array of `{r,m}` entries. This is the article's 20× change. Must also flush on size, since batching reaches the 8000-byte payload cap far sooner than single messages do. Tradeoffs: adds up to one tick of latency to every broadcast (acceptable — the article ran at 15–100ms); makes `publish` non-blocking for callers, which is an improvement in itself; needs a defined overflow policy on the bounded queue (drop-oldest fits the documented at-most-once, ephemeral-broadcast semantics).
2. **Move the spill reap to a scheduled job.** `brace-jobs-prune` (`Brace.java:789`) is the existing pattern. Removes a per-message range delete from the serialized commit window. Low risk, small change — worth doing even if batching is deferred.
3. **Batch spill fetches.** One `SELECT … WHERE id = ANY(?)` per notification batch instead of a round trip per message, so a spilled payload can't stall delivery of everything behind it.

Items 2 and 3 are independently useful and much smaller than item 1; item 1 is the one that moves the ceiling.

## Deliberately not proposed

Moving off LISTEN/NOTIFY entirely (Redis, a dedicated bus). The article's whole point is that LISTEN/NOTIFY *does* scale once notifications are batched, and keeping Postgres as the only dependency is a core Brace property.

---

# Summary

| Change | Effort | Risk | Value |
|---|---|---|---|
| **Job idle interval 10s → 1–2s, configurable** | Very low | Very low | 10× worst-case pickup latency |
| Message bus: move spill reap to scheduled job | Low | Low | Removes per-message delete from commit path |
| Message bus: batch spill fetches | Low | Low | Removes listener head-of-line blocking |
| **Message bus: buffer + coalesce NOTIFY** | Medium | Medium | The 20× throughput change |
| Job exponential idle backoff | Low | Low | Situational; not strictly better than flat |
| Job in-process wake on enqueue | Medium | Medium | Near-zero pickup, single-instance only |
| Job LISTEN/NOTIFY on enqueue | — | High | **Rejected** — regresses the request path |

## Adjacent finding — FIXED (2026-07-24)

No stale-claim recovery existed. If an instance died between `claimBatchPostgres` setting `started_at` and the terminal mark being written, the row was left with `started_at` set and both `completed_at` and `failed_at` null. No claim path would ever select it again (`started_at IS NULL` is in both claim predicates), and `purgeFinishedJobs` wouldn't remove it either, since it filters on the terminal timestamps. The job was stranded permanently — and since `Brace.stop()` never joins the per-job virtual threads, every ordinary deploy could strand up to `poolSize/2` jobs per instance.

Surfaced by the article's "polling as the safety net for lost work" framing.

Fixed by `JobPoller.reclaimStalledJobs` plus a background sweeper, with the lease configurable via `Brace.jobLease(Duration)` (default 15 minutes). Implementation note relevant to Part 1: recovery **clears `started_at`** rather than widening the claim predicate to `OR started_at < …`. That keeps the hot claim query and its `V15` index exactly as the perf review left them — including the ordered-scan early termination that Option A depends on — and leaves the H2 per-row re-claim guard unchanged. The sweep's own scan is served by a new `V16` partial index over currently-claimed rows.

The sweeper runs on its own thread rather than inside `pollLoop` deliberately: when every execution slot is held by a hung job the poll loop is parked in `limiter.acquire()`, which is exactly the case that most needs a sweep, since recovery has to come from a sibling instance.
