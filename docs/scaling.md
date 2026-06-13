# Scaling Brace horizontally

Brace runs correctly as **N application instances behind a load balancer sharing one Postgres
database** — no sticky sessions, no single-writer instance, no per-box drift. This page is the
contract: what you must configure, what is correct at scale automatically, what is opt-in, and what
is per-instance by design.

> Single process? You can ignore this page — everything below degrades to the obvious
> single-instance behavior. This matters only when you run more than one instance.

## Requirements (do these before scaling out)

1. **Run on Postgres.** Postgres is the coordination substrate. On Postgres the multi-server-correct
   path activates **automatically** for the subsystems below; on H2 (tests/dev) they stay
   per-process. There is no extra infrastructure to run — no Redis, no ZooKeeper.

2. **Use shared secrets — identical on every instance.** Set the session secret (and, if ops is
   enabled without sessions, the ops secret) from config/env so every box has the *same* value:

   ```java
   app.sessions(config.get("session.secret"))   // 32+ chars, same on all instances
   ```

   Session cookies and CSRF tokens are encrypted/signed with this secret; the ops login secret is
   derived from it. A *per-process* secret (the fallback when none is configured) makes cookies
   minted on one box invalid on another. Brace **warns at startup** if ops is enabled without a
   shared secret.

3. **Set a deploy marker.** So regression detection re-baselines cleanly on each deploy and
   classifies the same error identically on every box of a rolling deploy:

   ```bash
   BRACE_DEPLOY=$GIT_SHA      # or app.deploy("<git-sha-or-release-tag>")
   ```

   Optional — without it everything still works fleet-wide; you just don't get per-deploy
   regression baselines (all instances share one `"default"` marker).

That's it: Postgres + a shared secret + (optionally) a deploy marker. **No sticky sessions required.**

## Correct at scale automatically

These need no code change beyond the requirements above. On Postgres each works fleet-wide; without a
database each is trivially correct (one instance).

| Subsystem | How it's correct across instances |
|---|---|
| **Sessions** | State lives entirely in the AES-256-GCM encrypted cookie (key from the shared secret). Any instance decrypts any cookie. |
| **CSRF** | Token rides in the same cookie — no server-side store. |
| **Durable jobs** (`Jobs`/`JobPoller`) | Claimed via Postgres `SELECT … FOR UPDATE SKIP LOCKED`; the DB is the coordination point. |
| **Recurring scheduler** (`every`/`daily`) | A wall-clock time-slot claim runs each job **once per interval cluster-wide**, not once per instance — so `daily("09:00", …)` sends one digest, not N. |
| **WebSocket broadcast** (`ws.broadcast`) | Fans out across instances via Postgres `LISTEN`/`NOTIFY`, so a room split across boxes still receives every message. |
| **Rate limiter** (`RateLimiter.perIp`/`perKey`) | Counts through one shared atomic counter, so a limit is enforced once across the fleet (not N× too loose). Keys longer than 64 chars are hashed (SHA-256 hex) to prevent unbounded row growth. On DB failure the limiter falls back to per-instance counting (brief over-admission is possible; see the caveats below). See the load note below. |
| **Ops console login** | Stateless HMAC login token + a shared ops secret, so the browser login handshake works on any instance and the session cookie validates fleet-wide. |
| **Regression detection** (`/ops/regressions`) | A shared table (keyed by `type`+`route`+`deploy`) gives one fleet-wide set: notify **exactly once**, consistent list/acknowledge, a stable id, deploy-anchored baseline. |
| **Metrics feed** (`ops_timeseries`) | Each instance writes its own **instance-tagged** rows; an external dashboard sums across instances or filters to one. |
| **Storage** (S3/R2), **assets** (content-hash fingerprints), **config secrets** | External, immutable, or content-derived — identical on every box. |

## Opt-in (not automatic)

- **Shared cache backend.** The cache is **per-process by default even on Postgres**, because a
  shared cache trades latency for consistency and many apps are happy with a fast local cache. Opt in
  per use case when you need cross-server invalidation, a global `incr`, or consistent cached pages:

  ```java
  app.cache(CacheBackend.postgres(dbFactory));   // shared, durable, cross-server-consistent
  ```

  See [`2026-06-04-brace-shared-cache.md`](2026-06-04-brace-shared-cache.md) for choosing per use case.

## Per-instance by design — use external aggregation

The live ops views reflect the box that served the request, so a self-polling dashboard will show
different numbers as the load balancer rotates boxes. This is expected.

- **`/ops/dashboard`, `/ops/status`, custom `stats.counter(...)`** — this box's in-memory stats.
  `/ops/status` includes `app.instanceId` so you can tell which box you hit.
- **`/ops/logs`** — this box's in-memory log ring. Every instance also writes structured JSON to
  **stdout**, so an external log aggregator is the real multi-instance log answer.
- **JFR / heap / CPU** — inherently per-JVM (and per-host for machine CPU).

**The fleet picture comes from external tools:** point Grafana (or similar) at the instance-tagged
`ops_timeseries` feed for metrics, and ship stdout JSON logs to an aggregator. An in-framework
fleet dashboard (instance picker + liveness) is a planned follow-up.

## Caveats & limits

- **Rate-limiter DB load.** Counting on every request is a real write load on a busy server. Brace
  tunes the Postgres path (single-statement upsert, `UNLOGGED` counter tables), but a single very hot
  counter (a global limit, or everyone behind one NAT IP) still serializes on one row. For truly high
  volume or hot keys, Redis is the right tool — see
  [`2026-06-07-rate-limiter-load.md`](2026-06-07-rate-limiter-load.md) (a Redis backend is a
  documented future option, not yet implemented).
- **Rate-limiter DB failure → per-instance fallback.** If the shared counter backend is unavailable
  (connection pool exhausted, Postgres outage), the rate limiter falls back to per-instance counting
  for that request rather than returning a 500. During an outage the effective fleet-wide limit
  becomes approximately `limit × N` (where N = running instances), so a brief burst past the
  intended limit is possible. This is deliberate — fail-open-with-local-limiting is far better than
  making every rate-limited endpoint return a 500. A `WARN` log line is emitted for each fallback
  request; alert on sustained warn-rate spikes to catch DB connectivity problems early.
- **Rate-limiter key cap.** Keys longer than 64 characters (user-controlled header values, bearer
  tokens, usernames) are replaced by their SHA-256 hex digest before being stored in the counter
  table or local map. This prevents a DoS via unbounded key-string storage. Two distinct long keys
  are astronomically unlikely to hash to the same digest, so bucketing is functionally identical to
  using the raw key.
- **IP spoofing and perIp.** `RateLimiter.perIp` uses `req.ip()`, which applies
  rightmost-untrusted semantics when `TrustedProxies` is configured — forged `X-Forwarded-For`
  leftmost entries are ignored. Without `app.trustedProxies(...)`, `req.ip()` is the raw socket
  peer (headers ignored entirely). Configure trusted proxies before deploying IP-based rate limiting.
  See [`SECURITY.md`](SECURITY.md) for details.
- **Clock sync.** The recurring scheduler and rate limiter derive their time slot from wall-clock, so
  instance clocks need to agree within one interval/window — NTP handles this trivially for any real
  interval.
- **Ephemeral counters reset on crash/failover.** Rate-limit and cache counters live in `UNLOGGED`
  tables (speed over durability), so a Postgres crash or failover resets them — at worst a brief
  limit reset. Acceptable by design for this data.
- **TLS/HTTP-2 at the edge.** Brace serves HTTP/1.1 only; terminate TLS and HTTP/2 at your reverse
  proxy / load balancer (see [`SECURITY.md`](SECURITY.md)). Configure `TrustedProxies` so forwarded
  client IPs are honored only from your proxy.
- **Postgres connection budget (pool sizing).** Each instance's HikariCP pool is **fixed-size** —
  `minimumIdle == maximumPoolSize` (default **10**), Hikari's recommended posture (a fixed pool avoids
  connection-storm thundering on load spikes). Budget per instance ≈ `poolSize` **+ 1** dedicated raw
  connection for the Postgres message-bus `LISTEN` listener (only when you register WebSocket routes).
  Fleet total ≈ `N × poolSize + (N if WebSockets)`. Each idle Postgres connection costs ~1–10 MB
  server-side, so on a small PaaS Postgres (e.g. `max_connections = 100`) a few instances at the
  default 10 can approach the ceiling — size `N × poolSize` to leave headroom for migrations, admin
  tools, and `psql`. Tune the pool via the `DatabaseFactory(url, user, password, entities, poolSize)`
  constructor before `app.database(dbFactory)`; `minimumIdle` is not separately configurable by design.
- **Object storage buffers whole objects in heap.** `Storage.put(...)` holds the entire object as a
  `byte[]` (and an uploaded file arrives already buffered via `UploadedFile.bytes()`), so a single
  upload transiently costs ≥2× the object size in heap. Fine for typical avatars/attachments; for
  large media, cap upload size (`app.maxUploadSize(...)`) and size the heap accordingly. A streaming
  variant (temp-file + `BodyPublishers.ofFile`) is a documented future option, not yet implemented.
- **WebSocket broadcast cost on Postgres.** Cross-instance fan-out (`ws.broadcast`) issues a session +
  transaction + `pg_notify` round-trip per broadcast. This is correct and fine at human chat rates;
  if you build a high-fan-in firehose (many messages per tick), coalescing/batching `pg_notify` would
  be the next step — not currently done.
- **Jetty platform thread pool.** Request handlers run on **virtual** threads, so Jetty's
  `QueuedThreadPool` (left at its defaults, max 200) only serves acceptors/selectors and needs very
  few threads in practice — it does not bound request concurrency. The default max is a ceiling, not a
  reservation, so the footprint is small; it is intentionally left unconfigured.

## Quick checklist

- [ ] Postgres (not H2) in production
- [ ] Session secret from config/env, **identical on every instance** (32+ chars)
- [ ] Ops secret shared too if ops is enabled without sessions (`app.opsSecret(...)`)
- [ ] `BRACE_DEPLOY` set to your git sha / release tag (optional but recommended)
- [ ] `TrustedProxies` configured for your load balancer
- [ ] Opt into `CacheBackend.postgres(...)` only where you need cross-server cache consistency
- [ ] Metrics/logs shipped to an external aggregator for the fleet view
