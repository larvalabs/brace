# Plan: Multi-server / horizontal-scaling readiness

Status: draft
Date: 2026-06-04

## Goal

Make Brace correct under horizontal scaling — N application instances behind a load balancer sharing one database. Today Brace runs correctly as a single process; several convenience subsystems silently assume that "one process = the whole app" and break, duplicate work, or diverge when scaled out. This doc inventories every such assumption (audited 2026-06-04 across the framework source), classifies each as a correctness bug, an observability gap, or already-correct, and lays out the path to fix the blockers.

## The reassuring headline

The **request path is already stateless and scales cleanly**:

- **Sessions** — all state in the AES-256-GCM encrypted cookie, key derived from the shared secret via PBKDF2 (`Session.java:135-203,323-337`). Any instance decrypts any cookie. **No sticky sessions required.**
- **CSRF** — token rides inside the same cookie, no server-side store (`Csrf.java:17-31`). Stateless.
- **Durable jobs** — `JobPoller` claims work via `UPDATE … WHERE started_at IS NULL` (`JobPoller.java:123`); the DB is the coordination point. Designed for horizontal scale.
- **Storage (S3), TrustedProxies, Config secrets, Assets fingerprinting, BraceHandler flash/CSRF handoff** — external, immutable-config, or request-scoped. Secrets come from config/env (not per-process random), so the cookie scheme holds across boxes.

What breaks is the **stateful sidecar machinery** bolted on for single-process convenience: the in-memory recurring scheduler, the in-process cache, the WebSocket registry, the per-process rate limiter, the ops-console login handshake, regression detection, and the entire stats/observability layer.

## Unifying insight

Three of the correctness bugs (cache invalidation, rate limiter, regression alert dedupe) and one "run exactly once cluster-wide" need (recurring scheduler) all reduce to the **same missing primitive: a shared coordination/state backend** — and we've decided that backend is **Postgres** (see Decisions below). The `CacheBackend` SPI from [`docs/2026-06-04-brace-shared-cache.md`](2026-06-04-brace-shared-cache.md) is the natural home for most of it — a shared cache backend can carry the rate-limiter counter and a `runOnce`/leader-lock primitive too. WebSocket fan-out is the one that needs genuine pub/sub rather than a key/value store, which Postgres `LISTEN`/`NOTIFY` provides.

---

## Correctness bugs (wrong/duplicated behavior under N instances)

Ordered by blast radius. The first two are **must-fix-before-scaling blockers**.

### B1. Recurring `JobScheduler` runs on every instance — no leader election ⛔ BLOCKER
- **Location:** `Brace.java:545` (unconditional `jobScheduler.start()`); `JobScheduler.java:53-64,82-127` (in-process timer, in-memory status list, no lock); framework ops-flush jobs at `Brace.java:560-639`.
- **Failure mode:** Every instance fires every `every()`/`daily()` job independently. `daily("09:00", "send-digest")` sends N digests; any job with external side effects (email, billing, webhooks, file writes) is duplicated N-fold. **Also corrupts Brace's own metrics:** the `ops-flush-http/cache/mailer/jvm` jobs each `INSERT INTO ops_timeseries` from every box, so the dashboard timeseries is silently summed across instances with no instance label.
- **Fix direction:** Gate each recurring tick behind a coordination point — `pg_try_advisory_lock(hashtext(jobname))` acquired at the top of `executeJob` (only the lock winner runs), or move recurring work onto the durable `JobPoller` queue, which is already multi-instance-safe. For ops-flush specifically: either elect a single flusher or add an `instance_id` dimension to `ops_timeseries` and aggregate at read time.

### B2. WebSocket rooms / broadcast fragment by instance ⛔ BLOCKER
- **Location:** `WsContext.java:17` (`static` room→connections map), `:38-67` (join/leave/broadcast over the process-local map).
- **Failure mode:** WebSocket connections for one logical room spread across instances (the LB picks where each upgrade lands). `broadcast("lobby", msg)` only reaches sockets terminating on the *same* instance, so a chat/live-update room silently splits into N disjoint sub-rooms — no error, messages just appear to drop.
- **Fix direction:** Back room membership / broadcast with Postgres `LISTEN`/`NOTIFY` (see Decisions): publish room messages to a channel, each instance's listener delivers to its locally-connected members. Keep the in-memory map as the local-delivery leg only. Behind a `MessageBus` interface so Redis stays a future opt-in.

### B3. Cache invalidation does not cross instances
- **Location:** `Cache.java:88-126` (`delete`/`deletePrefix`/`clearTag`/`clear` mutate only the local map).
- **Failure mode:** After a write on box A calls `cache.clearTag("user:42")`, boxes B…N keep serving the stale value until their own TTL expires. Users see updated data on one request and stale data on the next depending on LB routing.
- **Fix direction:** Subsumed by the shared-cache plan ([`docs/2026-06-04-brace-shared-cache.md`](2026-06-04-brace-shared-cache.md)) — a Postgres-backed `CacheBackend` makes invalidation global. This is the correctness half of that work.

### B4. Cache `incr`/`decr` and `RateLimiter` window are per-process → limits N× too loose
- **Location:** `Cache.java:109-115` (`incr`/`decr` over a per-process `AtomicLong`); `RateLimiter.java:18,50-71` (per-process sliding window).
- **Failure mode:** Each instance counts only its own traffic. A limit of 100/min across 4 boxes allows ~400/min cluster-wide; sticky IP-hash routing masks it but resets on rebalance/instance loss. Any rate limit or quota built on these is wrong at scale.
- **Fix direction:** Shared atomic counter — Redis `INCR` or Postgres `UPDATE … SET n = n + ? RETURNING n` with a TTL/window column. Natural fit for the `CacheBackend` SPI's `incr`.

### B5. Ops console login is per-process — browser login bounces behind an LB
- **Location:** `OpsHandler.java:21,144-150` (single-use login token in an in-memory map); `Brace.java:441` (`OpsToken` signing secret is per-process random via `generateSecret()`).
- **Failure mode:** Two compounding issues. (a) `POST /ops/auth/login-token` stores the token in box A's heap; the follow-up `GET /ops/auth/exchange` routed to box B finds nothing → 401. (b) Even a successfully issued ops session cookie is signed with a per-process secret, so it won't validate on a different box. The login flow fails ~(N−1)/N of the time.
- **Fix direction:** Make the login token a short-lived stateless HMAC value (or store in shared cache), and derive the ops-token signing secret from shared config instead of `generateSecret()`. (The app session secret is already shared config — mirror that.)

### B6. `RegressionTracker` is per-process — duplicate alerts + inconsistent baselines
- **Location:** `RegressionTracker.java:30-33,53,56,123-131` (in-heap seen-set, notify-once dedupe, acknowledge state, per-JVM `AtomicLong` id); seeded per-process at `Brace.java:452-455`.
- **Failure mode:** (a) A new error hitting 3 boxes fires 3 duplicate Slack/email alerts — no shared "already notified" record. (b) `/ops/regressions/{id}/acknowledge` only acks the box that served it, and the numeric id means different things on different boxes. (c) In a rolling deploy, staggered start times make the same error a "regression" on late-starting boxes and "pre-existing" on early ones. The errors themselves are DB-backed and fine — only the *detection state* is in-memory.
- **Fix direction:** Persist baseline + notified-set + acknowledge to a shared table keyed by a stable `(type, route, deploy)` id (the `seed()` path already reads from the DB), with an atomic claim-to-notify so exactly one instance alerts. Anchor the baseline to a shared deploy marker, not per-JVM `Instant.now()`.

### B7. (Latent) `JobPoller` claim branches on exception, not affected-row count
- **Location:** `JobPoller.java:122-130`.
- **Failure mode:** The claim `UPDATE … WHERE started_at IS NULL` is correct, but the Java code proceeds based on *not catching an exception* rather than checking the UPDATE's affected-row count. Under READ COMMITTED two instances can both commit (one 1-row, one 0-row) without either throwing, so the losing claimant can still fall through and execute the job body → double-run.
- **Fix direction:** Proceed only when the claim UPDATE reports exactly 1 affected row, or switch to `SELECT … FOR UPDATE SKIP LOCKED`. Connects to the stuck-claimed-job durability bug already filed under `## Bugs`.

---

## Observability gaps (internally correct per box; dashboard shows only one instance)

Not data corruption, but misleading during an incident: the dashboard self-polls every ~5s with a Bearer token, so consecutive refreshes land on different boxes and the numbers visibly flicker; an overloaded instance is invisible if you keep hitting a healthy one.

- **All of `Stats`** (`Stats.java` throughout) — request/error counts, latency, status-code histogram, per-route stats, the 60-minute sparkline ring buffer, custom `counter()`/`timer()`/`gauge()` totals, and the recent-errors list. A user-defined `stats.counter("orders.placed")` returns *this box's share*, easily mistaken for a global total. The recent-errors list is the most incident-critical: the same exception appears as N undercounted records, one per box.
- **`JfrProfiler` + heap gauge** (`JfrProfiler.java` whole class; `Stats.java:136`) — heap/CPU/GC/hot-methods/allocations are inherently per-JVM; `machineCpu` is per-host. Can't see a GC-thrashing or CPU-pegged peer from a healthy box.
- **`/ops/logs` ring buffer** (`LogTap.java:27` static deque) — `brace logs` returns ~1/N of log volume and the `since(id)` cursor jumps as the LB rotates boxes. stdout JSON still goes everywhere, so an **external log aggregator is the real multi-instance answer**; document `/ops/logs` as per-instance.
- **Cache hit/miss/eviction stats, Mailer dev-capture buffer, `Jobs` async counters** — all per-process; acceptable if exported tagged-by-instance and summed downstream.

**Direction for the whole layer:** support a fleet view by either (a) scraping each instance's `/ops/status` and aggregating (Prometheus-style), or (b) pushing instance-tagged metrics to a shared sink. Lowest-effort interim: label the dashboard as single-instance and add an instance picker. Out of scope for the correctness blockers; track as a separate observability workstream.

---

## Verified correct under multi-server (no action)

Stated explicitly so future work doesn't re-litigate these: **Sessions** (stateless encrypted cookie), **CSRF** (token in cookie), **JobPoller** (DB claim — modulo B7), **Storage** (external S3/R2), **TrustedProxies** (immutable config), **Config secrets** (from config/env, not per-process random), **Assets fingerprinting** (content-derived hash; identical URLs across boxes even with differing mtimes), and **BraceHandler** flash/CSRF/session handoff (request-scoped, carried in the shared-secret cookie).

---

## Phasing

- **Phase 0 — Document the scaling contract.** A "Scaling Brace horizontally" page: what's already safe (sessions/CSRF/jobs), the shared-secret requirement, and the current blockers. Prevents users from scaling onto the B1/B2 footguns unaware. Lowest effort, highest immediate value.
- **Phase 1 — Blockers.** B1 (recurring-scheduler leader lock) and B2 (WebSocket pub/sub fan-out). These are the two that *silently* misbehave; nothing else can be trusted at scale until they're fixed. B1's advisory-lock fix is small; B2 needs the pub/sub bus decision.
- **Phase 2 — Shared-state correctness.** B3 (cache invalidation) + B4 (shared counters/rate limiter) land together on the `CacheBackend` SPI from the shared-cache plan. B5 (ops login) and B6 (regression dedupe) — both move per-process state to shared store / stateless HMAC.
- **Phase 3 — Hardening + observability.** B7 (JobPoller affected-row claim). Fleet-aware ops/stats (scrape-and-aggregate or instance-tagged push), or at minimum label the dashboard single-instance with an instance picker.

## Decisions — resolved: standardize on Postgres

Decided 2026-06-04: **Postgres is the single coordination/state substrate for all of the above.** No Redis, no new infra — Brace already requires Postgres, and this matches the batteries-included bet of the shared-cache plan ([`docs/2026-06-04-brace-shared-cache.md`](2026-06-04-brace-shared-cache.md)). Concretely:

1. **Pub/sub for B2 (WebSocket fan-out)** → **Postgres `LISTEN`/`NOTIFY`.** One listener connection per instance subscribes to a room-message channel; `broadcast` issues `NOTIFY` (or `pg_notify(channel, payload)` for payloads over the 8 KB identifier limit / from inside a txn). Each instance delivers to its locally-connected members. Caveats to design around: `NOTIFY` payloads cap at 8000 bytes (spill large messages to a row + notify the id), delivery is at-most-once and only to *currently-connected* listeners (fine for ephemeral broadcast, not for missed-message replay), and each listener needs a dedicated connection held outside the request pool. Keep the bus behind a `MessageBus` interface so a Redis impl can drop in later if latency demands it.
2. **Leader lock for B1 (recurring scheduler)** → **Postgres advisory lock.** `pg_try_advisory_lock(hashtext(jobname))` at the top of each tick; only the winner runs, lease-free (the lock releases automatically if the holding session dies, so a crashed leader doesn't wedge the schedule). No schema, no reaper. If leadership *visibility* is later wanted (which box holds it), add a `job_leader` row, but it's not needed for correctness.
3. **Shared counters for B4 (rate limiter / `incr`)** → Postgres `UPDATE … SET n = n + ? RETURNING n` with a window/expiry column, exposed via the `CacheBackend` SPI's `incr` (same backend as the shared cache).
4. **Shared state for B5/B6 (ops login token, regression dedupe/baseline)** → Postgres tables with atomic claim-inserts (`INSERT … ON CONFLICT DO NOTHING` gates the single notifier), plus a stateless HMAC for the ops login token (no store needed at all).
5. **Ops/stats fleet aggregation (Phase 3)** — still open, and the one place Postgres isn't the obvious answer. Options: instance-tagged rows in `ops_timeseries` aggregated at read time (Postgres-native, consistent with everything else), vs. scrape-and-sum each `/ops/status`. Leaning instance-tagged Postgres rows to keep one substrate, decided at Phase 3.

Net effect: B1, B3, B4, B5, B6 all land on Postgres primitives already in the stack; B2 is the only one needing a new Postgres capability (LISTEN/NOTIFY) but still no new infra. The `MessageBus` / `CacheBackend` interfaces keep Redis as a future opt-in without rework.
