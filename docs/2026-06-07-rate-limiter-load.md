# Shared rate limiting & counters: load, tradeoffs, and when to reach for Redis

Date: 2026-06-07
Status: shipped (B4, 0.1.7) — Redis backend is a documented future option, **not yet implemented**

## What this covers

Brace's multi-server rate limiter (B4) and the cache `incr`/`decr` both count through a shared
Postgres-backed counter (`Counters`, table `brace_counters`; the cache uses `brace_cache_counters`).
Doing this on the database you already have — instead of standing up Redis — keeps the
batteries-included promise, but counting on every request is a real write load. This documents what
that load is, the Postgres features we use to make it cheap, the durability we deliberately trade
away, and the point at which you should move counting to Redis.

## The per-request cost

When the shared backend is active (automatic on Postgres), each rate-limited request performs one
atomic counter increment. On Postgres that is now a **single statement**:

```sql
INSERT INTO brace_counters AS c (counter_key, n, expires_at) VALUES (?, ?, ?)
ON CONFLICT (counter_key) DO UPDATE
  SET n = CASE WHEN c.expires_at IS NOT NULL AND c.expires_at <= ? THEN ? ELSE c.n + ? END,
      expires_at = EXCLUDED.expires_at
RETURNING n;
```

So the unit of load is: borrow a pooled connection → one upsert (one network round trip, a brief
row lock) → commit. At **R** requests/sec through rate-limited routes you add ~R such operations/sec.
On a busy server that can exceed the app's own query volume — the limiter must be cheap or it
becomes the bottleneck it was meant to prevent.

(The non-Postgres path — H2 in tests, or any other database — uses a portable `SELECT … FOR UPDATE`
+ `UPDATE`/`INSERT`, two round trips. Single-process apps don't use the shared backend at all and
keep an in-process counter.)

## Making Postgres behave more like Redis for this workload

This data is **ephemeral, best-effort, and windowed**: none of WAL durability, crash safety, or
replication are protecting anything we can't afford to lose. So we trade them for speed. Each
feature targets a specific cost (migration `V11`, Postgres-only):

| Cost | Postgres feature | Effect |
|---|---|---|
| WAL write + fsync on every commit | **`UNLOGGED` table** | No WAL at all; writes hit shared_buffers (RAM) and flush lazily via the checkpointer. The big lever. |
| MVCC dead-tuple bloat from repeated `UPDATE`s | **`fillfactor = 70` + HOT updates** | The PK never changes on increment, so updates are HOT: new version in the same page, no index churn, pruned on page access. Free space keeps them HOT. |
| Bloat outpacing cleanup | **aggressive per-table autovacuum** | Flat low threshold so cleanup keeps pace with churn. |
| Two round trips + long lock hold | **single-statement `ON CONFLICT` upsert** | One round trip; lock held only for the statement; expiry reset folded into the `CASE`. |
| (optional, not enabled) commit latency | `synchronous_commit = off` | Async commit; bounded loss, no corruption. Largely redundant once `UNLOGGED`. |

The mental model: an `UNLOGGED` table with a tiny hot working set is essentially an in-memory
counter that Postgres spills to disk on its own schedule — conceptually close to Redis.

### Durability we deliberately give up

- **Crash:** `UNLOGGED` tables are **truncated on crash recovery**. Counters reset → at worst a brief
  burst past a limit. Acceptable.
- **Failover:** an `UNLOGGED` table is **empty on a streaming standby**, so after failover counters
  start fresh. Same brief-reset consequence.
- **Async window (if `synchronous_commit=off` is used):** up to ~600ms of increments lost on crash,
  never corrupted.

All three are fine for rate limiting and ephemeral counters. We do **not** apply `UNLOGGED` to the
cache *value* table (`brace_cache`): losing cached values en masse on restart causes a cold-cache
stampede, which is a different and worse tradeoff. Only the counters are unlogged.

## The residual cost — and the ceiling vs. Redis

Even fully tuned, each increment still pays a client→Postgres **network round trip**, a transaction,
and a **row lock**. Two regimes:

- **Dispersed keys** (per-IP / per-user across many clients): different keys are different rows, so
  increments parallelize. Throughput is bounded by pool size × 1/RTT and CPU — a tuned Postgres
  handles very busy servers here.
- **Hot key** (a single global limit, everyone behind one NAT/proxy IP, or a coarse `perKey`): every
  request serializes on **one row**. Postgres and Redis both serialize a hot counter, but Redis does
  it in-memory with no lock/MVCC/round-trip-to-a-row, so it's cheaper. A single Postgres counter row
  tops out around a few thousand updates/sec; `UNLOGGED` + upsert raises that, but won't match Redis.

There is also **connection-pool coupling**: the limiter borrows from the same HikariCP pool as your
handlers, so under hot-key contention it can hold connections while waiting on the row lock. Keep
rate limiting bound to specific routes (it's before-middleware — don't put it on `/*`), and size the
pool with this in mind (see below).

## Recommendation: when to use Redis (future, not yet implemented)

Use the Postgres-backed counters (the default) when:
- traffic is low-to-moderate, **or**
- limits are keyed on dispersed values (per-IP / per-user), **and**
- you'd rather not run Redis.

Move counting to **Redis** when:
- you have a **single very hot counter** (global limit, NAT-heavy traffic), **or**
- request volume to rate-limited routes is high enough that one DB write per request is material
  load on your primary, **or**
- you don't want rate limiting sharing the application's connection pool at all.

Redis `INCR`/`INCRBY` with `EXPIRE` is one in-memory op per increment, no MVCC, no row lock, no
shared pool. It is the right tool for truly high-volume or hot-key limiting.

**Status:** a Redis `Counters` backend is **not implemented yet**. The `Counters` increment path is
internal today; the intended shape mirrors the pluggable `MessageBus` (B2) and `CacheBackend` —
extract a small counter SPI and add a Redis implementation, selected by config, so Redis stays an
opt-in with no infra forced on anyone. Tracked as a post-0.1.7 follow-up.

## Connection pool sizing (related)

Brace's pool defaults to **10** connections (fixed: `minimumIdle == maximumPoolSize`), configurable
via the 5-arg `DatabaseFactory(url, user, password, entities, poolSize)` constructor.

A common instinct with virtual threads is "we have thousands of concurrent requests, so raise the
pool to match." **Resist it.** The pool should be sized to what the *database* can do, not to app
concurrency. Postgres connections are real backend processes (memory + scheduler + lock contention);
past a modest count, more connections *reduce* throughput. The long-standing guidance lands around
`(cores × 2) + effective_spindles` — typically tens, not hundreds.

Virtual threads actually make a **small** pool the right call: cheap-to-park VTs queue on pool
checkout, so the pool becomes your admission-control / backpressure valve. The shared rate limiter
adds load to that pool, which argues for two things, neither of them "make it huge":
- size the pool deliberately (and expose it more conveniently — see the follow-up task), and
- consider a **separate small pool** for framework/ops/rate-limit work so a counter hot spot can't
  starve application queries (this is the clean fix for the coupling noted above).

(Relevant aside: under load, virtual threads pinned on `synchronized` during JDBC/Hibernate calls
hurt; JDK 25's JEP 491 removes that pinning, which is why Brace recommends JDK 25 — see `AGENTS.md`.)
