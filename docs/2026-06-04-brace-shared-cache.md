# Plan: Shared cache backend for multi-server consistency

Status: Phases 1–3 implemented (2026-06-06); Phase 4 deferred
Date: 2026-06-04 (finalized 2026-06-06)

## Goal

Make `Cache` consistent across a horizontally-scaled deploy *without taking the fast path away from anyone who doesn't need it*. Today `Cache` (`src/main/java/com/larvalabs/brace/Cache.java`) is a per-process `ConcurrentHashMap`: every instance keeps its own copy of every entry, counter, and tag index. On a multi-server deploy that means:

- A `cache.delete(key)` / `cache.clearTag("posts")` on the box that handled the write invalidates only *that* box. The other N−1 keep serving stale data until TTL expiry.
- `cache.incr("rate:user:42")` counts per-instance, so a rate limiter or counter is wrong by a factor of N.
- A cached page (`cache.wrap(...)`) differs between servers depending on who rendered it first.
- `POST /ops/cache/clear` clears one instance; an operator has to hit it once per box.

After this plan, an app that needs cross-server consistency opts into a shared backend with one line:

```java
app.cache(CacheBackend.postgres(dbFactory));   // shared, durable, batteries-included
// default stays in-process if you never call this
```

## Framing: the choice is per-use-case, not per-deployment

The shared backend is **not "better."** It trades latency for cross-server *consistency*, and only some cache uses need consistency. The decision is per-use-case:

| Deployment / use | Right mode |
|---|---|
| Single server | in-memory (default) |
| Read-through of expensive compute, per-server copies OK | in-memory (even multi-server) |
| Counters / rate limits / invalidation correctness, multi-server | shared (Postgres) |
| Cached pages that must match across the fleet | shared (Postgres) |
| Perf-critical **and** multi-server **and** consistent | near-cache (deferred — see Phase 4) |
| Immutable / content-addressed keys, multi-server | near-cache, no invalidation machinery (Phase 4a) |

This is why the plan keeps in-memory **first-class**, not as a deprecated default:

- The perf gap is structural. An in-memory hit is a hashmap `get` (~ns, no serialization); a Postgres hit is a DB round trip plus Jackson de/serialize (~ms) **and adds load to the very database the cache exists to protect**. For a perf-critical hot path, routing reads to Postgres can be slower than the computation cached and pushes load back onto the resource the cache was shielding.
- Because `Cache` is just an object you `new` with a backend, a single app can run **both** — a default in-memory `Cache` for hot read-through pages *and* a separate Postgres-backed `Cache` for rate-limit counters and invalidation. It is not all-or-nothing per deployment. (`app.cache(...)`, route-level `wrap`, and ops integration assume one app-wide instance; that stays the convenient default, with "instantiate a second `Cache` for the consistency-critical subset" documented as the escape hatch.)

## Non-goals

- **Not Redis (for v1).** Postgres-backed is the default shared backend and the only one this plan ships — it reuses the DB Brace already requires, adds zero infra, and deploys trivially on Dokploy. The SPI leaves the door open for `CacheBackend.redis(...)` later, but it's out of scope here. (See "Why Postgres, not Redis".)
- **Not the near-cache / two-tier mode in v1.** See "Why not go straight to the near-cache" below — it is deliberately sequenced as a later composition over the two simple backends, not the starting point.
- **Not changing the default.** Apps that never call `app.cache(backend)` keep the current in-process behavior with the no-serialization fast path: no new dependency, no DB table, no behavior change.
- **Not distributed locking for `getOrSet`.** Cold-key dogpile across servers is accepted (it's a stampede, not a correctness bug). Documented, not solved, in v1.

## Why not go straight to the near-cache

A near-cache (local L1 in front of shared L2) looks like it dominates — fast local reads, consistent shared store. It does **not** eliminate the downsides; it trades strong invalidation consistency for a weaker contract and adds the most complex machinery in the feature. Walking through the actual cache uses:

- **Counters never use L1.** An L1 copy of `rate:user:42` is wrong by a factor of N — the point is a single atomic `incr`. They go straight to L2 regardless, so the near-cache does nothing for them.
- **Invalidation-sensitive values get staleness back.** When box A runs `delete`/`clearTag`, box B's L1 still serves the old value until B's L1 expires — the exact per-server-staleness bug the shared cache was built to fix, now hidden behind a layer that *looks* consistent. Closing that gap requires cross-server invalidation propagation (Postgres `LISTEN/NOTIFY` or pub/sub): a dedicated listening connection per box, a dispatcher, missed-notification handling on reconnect/restart, payload limits, and a fallback for boxes that were down when the NOTIFY fired. The alternative — short L1 TTLs — only *bounds* staleness; it doesn't remove it. Either way the contract degrades from "strongly consistent on invalidate" to "eventually consistent."
- **Single-server deploys** get nothing from L2 but overhead (serialization + a useless round trip on cold keys). They want pure L1.
- **Immutable / content-addressed keys** are the one case where near-cache is pure win, because staleness is impossible by construction and no invalidation machinery is needed.

So the near-cache is a third mode with its own correctness contract and strictly more failure modes ("L1 and L2 disagree," "NOTIFY missed → stale after a fleet-wide invalidate," "L2 down — serve L1 or fail?"). Those bugs erode trust in a cache and should be introduced deliberately and in isolation, not baked into v1's default path.

Crucially, the near-cache **is** a composition of the two simple modes — `CacheBackend.nearCache(local, shared)`, a decorator over the SPI. You cannot build it cleanly without a clean L1 (in-memory backend) and a clean L2 (shared backend). Shipping the two simple modes first is therefore building the parts before the assembly, and each is independently the correct minimal answer for a real deployment class (see the table above). Deferring the near-cache costs nothing architecturally — the seam exists the moment Phase 1 lands — and avoids optimizing a cost (the L2 round trip) before it's measured. A ~1ms DB cache hit is still vastly cheaper than the uncached computation, so for many apps the single-tier shared backend is already fast *enough*.

## Design

### The `CacheBackend` SPI

Extract the storage operations behind an interface; `Cache` becomes a thin facade that owns stats, TTL parsing, value serialization, and the page-cache wrapper, and delegates storage to a backend.

```java
public interface CacheBackend {
    byte[] get(String key);                                  // null = miss
    void   set(String key, byte[] value, Duration ttl, String[] tags);  // ttl null = no expiry
    void   delete(String key);
    void   deletePrefix(String prefix);
    void   clear();
    void   clearTag(String tag);
    long   incr(String key, long delta);                     // atomic, server-side
    int    size();

    /** True if the backend stores bytes (so the facade must serialize values). */
    boolean requiresSerialization();

    static CacheBackend inMemory()                  { return new InMemoryBackend(); }   // default, today's behavior
    static CacheBackend postgres(DatabaseFactory f) { return new PostgresBackend(f); }
    // CacheBackend.nearCache(local, shared) — Phase 4, deferred
}
```

Key shift: the SPI traffics in `byte[]`, not `Object`. Serialization moves up into `Cache`, which is where the type information lives (`get(key, Class<T>)` already names the type). `InMemoryBackend` keeps storing live `Object`s and reports `requiresSerialization() == false`, so the default path pays **zero** serialization cost — this is the resolution of the draft's open question #2, and the reason in-memory stays first-class. `PostgresBackend` stores `byte[]` and reports `true`, so the facade serializes via Jackson only when a byte backend is configured.

Signature reconciliation: the current public facade methods are `incr(String key)` / `decr(String key)` (delta of 1) — these stay, delegating to the SPI's `incr(key, +1)` / `incr(key, -1)`.

### Value serialization

`Cache.set(key, value, ...)` / `getOrSet` deal in arbitrary objects. When the configured backend `requiresSerialization()`:

- **POJOs / records / collections** → Jackson (already a dependency). `get(key, Class<T>)` passes the type, so deserialization is unambiguous. Store a small header (type tag) alongside the bytes so a `get` with the wrong `Class` fails loudly instead of mis-binding.
- **Primitives / String** → direct encoding.
- **Counters** (`incr`/`decr`) → never serialized; they're a server-side numeric column, see below.

A non-Jackson-round-trippable value (a live handle, a stream) is unsupported on a byte backend. It throws a clear error **at `set` time, only when the configured backend requires bytes** (draft open question #3 resolved this way) — so in-memory apps keep storing arbitrary objects with no new restriction.

### Page caching — serialize the rendered response

`CachedHandler` (`Cache.java:191-230`) currently caches the `Result` *object*. The draft worried this was "wrong twice over" because a `View` renders lazily — **that is no longer true.** `View.of()` renders eagerly today (`View.java:44-45`): it calls `engine.render(...)` at construction and stores the HTML in `Result.body`. Every `Result` that reaches `CachedHandler` is already fully materialized (`body` String or `rawBytes`). So there is no render seam to factor out of `BraceHandler`; the only "rendering" left in `BraceHandler.writeResult` (`BraceHandler.java:387-402`) is `body.getBytes(UTF_8)` plus header/status copying.

Page caching therefore reduces to snapshotting the four fields the `Result` already holds into a serializable record:

- Add an internal `RenderedResponse(int status, String contentType, Map<String,String> headers, byte[] body)`.
- On a miss, run the handler, snapshot its materialized `Result` into a `RenderedResponse`, cache that, and return.
- On a hit, rebuild a `Result` from the `RenderedResponse` directly — no handler, no re-render.

This makes page caching genuinely cross-server: any box can serve a page another box rendered. The one small piece of plumbing: `Result` today only exposes `Result.bytes()` (hardcoded status 200) and has private constructors — add a package-private factory/constructor to rebuild a `Result` with arbitrary status + headers + content-type + bytes.

Caveat to document: a cached page captures the rendered per-request output. Do **not** `wrap` pages carrying per-user CSRF tokens or flash messages — they would be shared across users (and now across the fleet). This is a pre-existing concern, newly fleet-wide.

### Postgres backend

A single table, applied via the framework's bundled Flyway instance. **The cache table is only ever touched by the Postgres backend; H2/in-memory apps never create or read it.** So it goes in the Postgres-only migration tier (`src/main/resources/brace/db/migration_pg/`, alongside `V6__brace_ops_errors_dedupe_index.sql`), and we use native `TEXT[]` + GIN freely. This resolves the draft's open question #1 (array vs. portable child table for H2 parity): there is no H2 parity to preserve, because H2 never gets the table — exactly like V6 today. The two migration locations share one `flyway_brace_history` sequence; the latest version is `V7`, so the cache migration is **`V8`**.

```sql
-- src/main/resources/brace/db/migration_pg/V8__brace_cache.sql
CREATE TABLE IF NOT EXISTS brace_cache (
    cache_key   TEXT PRIMARY KEY,
    value       BYTEA,
    counter     BIGINT,          -- non-null only for incr/decr keys
    tags        TEXT[],          -- Postgres array; GIN-indexed for clearTag
    expires_at  TIMESTAMPTZ      -- null = no expiry
);
CREATE INDEX IF NOT EXISTS idx_brace_cache_expires ON brace_cache (expires_at);
CREATE INDEX IF NOT EXISTS idx_brace_cache_tags    ON brace_cache USING GIN (tags);
```

`IF NOT EXISTS` guards per the framework-migration idempotency convention.

Operation mapping (native SQL via the established `Database.jdbc(conn -> …)` / `db.sql(...)` patterns from `JobPoller`):

| `Cache` op | SQL |
|---|---|
| `get` | `SELECT value FROM brace_cache WHERE cache_key=? AND (expires_at IS NULL OR expires_at > now())` |
| `set` | `INSERT ... ON CONFLICT (cache_key) DO UPDATE SET value=?, tags=?, expires_at=?` (upsert) |
| `incr`/`decr` | `INSERT ... ON CONFLICT (cache_key) DO UPDATE SET counter = brace_cache.counter + ? RETURNING counter` — **atomic, no read-modify-write** |
| `delete` | `DELETE WHERE cache_key=?` |
| `deletePrefix` | `DELETE WHERE cache_key LIKE ? || '%'` |
| `clearTag` | `DELETE WHERE tags @> ARRAY[?]` (GIN index) |
| `clear` | `TRUNCATE brace_cache` |
| expiry sweep | the existing 30s cleanup virtual thread, but DB-side: `DELETE WHERE expires_at < now()` |

#### Why `TEXT[]` + GIN for tags

Tags exist to serve one operation: `clearTag("posts")` — "evict every entry tagged `posts`." Storing tags as an array column in the same row as the value wins over a child `brace_cache_tags(cache_key, tag)` table on every axis that matters here:

- **One row per entry, no write fan-out.** `set(k, v, ttl, "posts", "user:42")` is a single upsert writing value + tags + expiry atomically; the child table needs the upsert *plus* N tag-row inserts (and delete-then-reinsert on update).
- **No join, no cascade bookkeeping.** `get` reads one row; deleting an entry drops its tags for free. The child table needs `ON DELETE CASCADE` and a join/subquery for `clearTag`.
- **GIN makes containment an indexed lookup.** A GIN index is an inverted index mapping each distinct tag → the rows containing it — exactly the structure for `tags @> ARRAY['posts']`. Without GIN, `@>` is a sequential scan; a btree cannot index array containment.

The child table's only real advantage was H2 portability, which is moot now that the table is Postgres-only. GIN's write cost (heavier than btree, softened by the `fastupdate` pending list) is acceptable: cache writes aren't the bottleneck and rows expire.

Expiry is enforced **on read** (the `expires_at > now()` predicate) so a missed sweep never serves stale data; the sweep is just space reclamation.

### Stats

hits/misses/evictions stay per-instance `LongAdder` in `Cache` — each server honestly reports its own hit rate, which is what you want on the ops dashboard. No shared stat aggregation in v1. `size()` becomes a `SELECT count(*)` for the shared backend (cheap enough for the dashboard; cache it for a few seconds if it shows up hot). The `drainHits/Misses/Evictions` flush to `ops_timeseries` (`Brace.java:578-586`) is unchanged.

### `getOrSet` dogpile

Across servers, a cold key can have N suppliers run concurrently before the first `set` lands. Accepted in v1. The in-memory backend keeps today's `store.compute` single-flight; the shared backend degrades to per-server single-flight (still better than nothing). A specific expensive supplier that needs global single-flight is a follow-up using an advisory lock (`pg_advisory_xact_lock(hashtext(key))`) — noted, not built.

### Ops integration

`POST /ops/cache/clear` against a shared backend clears it once for the whole fleet (`TRUNCATE`, not per-process) — a strict improvement over today's clear-one-box behavior (`OpsHandler.java:489-497`). The dashboard cache stats remain per-instance.

## Why Postgres, not Redis

Redis is the textbook shared cache (sub-ms, native TTL/`INCR`/pub-sub). It's deliberately *not* v1:

- **Zero new infra.** Brace already requires Postgres; a cache table adds no moving parts, no new container on Dokploy, no new client dependency. That's the batteries-included bet.
- **Consistency over raw latency.** The problem being solved is correctness on a multi-server deploy, not microseconds. A ~1ms DB cache hit is still vastly cheaper than the uncached computation it replaces.
- **Durability is a free bonus.** A Postgres cache survives a full-fleet restart; a Redis cache (without persistence config) doesn't.

The `CacheBackend` SPI is the hedge: an app that outgrows the DB cache adds `CacheBackend.redis(...)` later without touching `Cache` or app code.

## Phases

Sequenced simple → composed: ship the two independently-useful single-tier backends first, add the near-cache as a deferred decorator over them.

- **Phase 1 — SPI + Postgres backend.** ✅ Done. Extract `CacheBackend`; refactor `Cache` to delegate while keeping `InMemoryBackend` as the default with its no-serialization fast path (`requiresSerialization() == false`). Add `incr(key, delta)` to the SPI, keep `incr`/`decr` on the facade. Add Jackson value serialization with type header (only on byte backends). Add `migration_pg/V8__brace_cache.sql`, `PostgresBackend` (atomic `incr`, GIN-backed `clearTag`, read-time expiry). Add `app.cache(CacheBackend)` overload; keep `app.cache(Cache)`. **Tests:** backend conformance suite run against both backends; cross-server consistency proven by pointing **two `Cache` facades at one backend**. Postgres tests named `*IT` (Testcontainers, `mvn verify`).

- **Phase 2 — Rendered page caching.** ✅ Done. Add the internal `RenderedResponse` record and the package-private `Result` rebuild factory. Change `CachedHandler` to snapshot the already-materialized `Result` and replay it (no `BraceHandler` surgery — `View` is eager). Verify a page cached by one facade is served by another. Document the per-user-content caveat.

- **Phase 3 — Ops + docs.** ✅ Done (added a `shared` flag to `/ops/cache` + `/ops/status`, a
  `scope: instance|fleet` field on the clear response, and a shared/in-process indicator with a
  `[clear fleet]` button on the dashboard). Confirm fleet-wide `/ops/cache/clear`, `size()` via count, dashboard wording. Document in `BRACE-AGENTS.md` and `README.md` (per the CLAUDE.md "update docs on public API change" rule): the per-use-case framing, the opt-in line, the two-instance escape hatch, the Jackson-round-trippable-values constraint, and the dogpile caveat. Add a migration-guide note.

- **Phase 4 (deferred) — near-cache + Redis.** `CacheBackend.nearCache(local, shared)` decorator over the SPI, with cross-server L1 invalidation (Postgres `LISTEN/NOTIFY` or short L1 TTL) — built only if profiling shows the L2 round trip is a real hot-path cost. `CacheBackend.redis(...)` alongside.
  - **Phase 4a — immutable-key near-cache.** The carve-out worth pulling forward if a perf need appears first: a near-cache restricted to immutable / content-addressed keys (long or infinite TTL, value never changes per key). Staleness is impossible by construction, so it needs **no invalidation machinery** and incurs **no correctness downgrade** — the cheap, safe slice of Phase 4.

## Resolved questions (were open in the draft)

1. **Tags storage** — `TEXT[]` + GIN, in the Postgres-only `migration_pg/` tier. The H2-parity objection that favored a child table is moot: H2 never gets the table.
2. **Fast object path for in-memory** — yes, kept. `InMemoryBackend` stores live `Object`s and reports `requiresSerialization() == false`; only byte backends serialize. This is what keeps in-memory first-class for perf-critical use.
3. **Non-serializable `set`** — throws eagerly, but **only when the configured backend requires bytes**. In-memory apps keep storing arbitrary objects.
