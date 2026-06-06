# Plan: Shared cache backend for multi-server consistency

Status: draft
Date: 2026-06-04

## Goal

Make `Cache` consistent across a horizontally-scaled deploy. Today `Cache` (`src/main/java/com/larvalabs/brace/Cache.java`) is a per-process `ConcurrentHashMap`: every instance keeps its own copy of every entry, counter, and tag index. On a multi-server deploy that means:

- A `cache.delete(key)` / `cache.clearTag("posts")` on the box that handled the write invalidates only *that* box. The other N−1 keep serving stale data until TTL expiry.
- `cache.incr("rate:user:42")` counts per-instance, so a rate limiter or counter is wrong by a factor of N.
- A cached page (`cache.wrap(...)`) differs between servers depending on who rendered it first.
- `POST /ops/cache/clear` clears one instance; an operator has to hit it once per box.

After this plan, an app opts into a shared backend with one line and gets cross-server-consistent reads, invalidation, and counters:

```java
app.cache(CacheBackend.postgres(dbFactory));   // shared, durable, batteries-included
// default stays in-process if you never call this
```

## Non-goals

- **Not Redis (for v1).** Postgres-backed is the default and the only backend this plan ships — it reuses the DB Brace already requires, adds zero infra, and deploys trivially on Dokploy. The SPI leaves the door open for a `CacheBackend.redis(...)` later, but it's explicitly out of scope here. (See "Why Postgres, not Redis".)
- **Not a near-cache / two-tier mode in v1.** A local L1 in front of the shared L2 would restore hot-path speed but reintroduces per-server staleness on L1, which is the exact bug we're fixing. Ship the single-tier shared backend first; add an opt-in near-cache with short TTLs or pub/sub invalidation only if profiling demands it.
- **Not changing the default.** Apps that never call `app.cache(backend)` keep the current in-process behavior with no new dependency, no DB table, no behavior change.
- **Not distributed locking for `getOrSet`.** Cold-key dogpile across servers is accepted (it's a stampede, not a correctness bug). Documented, not solved, in v1.

## Design

### The `CacheBackend` SPI

Extract the storage operations behind an interface; `Cache` becomes a thin facade that owns stats, TTL parsing, and the `CachedHandler` wrapper, and delegates storage to a backend.

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

    static CacheBackend inMemory()              { return new InMemoryBackend(); }      // default, today's behavior
    static CacheBackend postgres(DatabaseFactory f) { return new PostgresBackend(f); }
}
```

Key shift: the SPI traffics in `byte[]`, not `Object`. Serialization moves up into `Cache`, which is where the type information lives (`get(key, Class<T>)` already names the type). The current `InMemoryBackend` can keep storing live objects to avoid a serialization cost on the default path — so backends declare whether they serialize, or `Cache` keeps a fast in-memory path and a serialize-on-write shared path. Simplest correct version: `InMemoryBackend` holds `Object`; `PostgresBackend` holds `byte[]` and `Cache` serializes via Jackson when the backend requires bytes.

### Value serialization

`Cache.set(key, value, ...)` / `getOrSet` deal in arbitrary objects. For a byte-oriented backend:

- **POJOs / records / collections** → Jackson (already a dependency). `get(key, Class<T>)` passes the type, so deserialization is unambiguous. Store a small header (type tag) alongside the bytes so a `get` with the wrong `Class` fails loudly instead of mis-binding.
- **Primitives / String** → direct encoding.
- **Counters** (`incr`/`decr`) → never serialized; they're a server-side numeric column / `INCR`, see below.

Anything not Jackson-round-trippable (a live handle, a stream) is unsupported on a shared backend and should throw a clear error at `set` time rather than fail mysteriously on `get` from another box.

### Page caching — serialize the rendered response

`CachedHandler` (`Cache.java:191-230`) currently caches the `Result` *object*. `Result`/`View` don't serialize cleanly, and a `View` is lazy (renders later), so storing it shared is wrong twice over. Fix: cache the **rendered response**, not the result object.

- Add an internal `RenderedResponse(int status, Map<String,String> headers, byte[] body, String contentType)` — a fully-materialized, trivially-serializable snapshot.
- On a cache miss, run the handler, render the `Result` to bytes (the same render the response writer would do), store the `RenderedResponse`, and also return it.
- On a hit, replay the `RenderedResponse` directly — no handler, no re-render.
- This makes page caching genuinely cross-server: any box can serve a page another box rendered. It also makes `cache.wrap` faster on the local-hit path (skips re-render).

This needs a seam to render a `Result` to bytes outside the normal response-writing path (factor out of `BraceHandler`'s response writer). That seam is the one piece of real plumbing in this plan.

### Postgres backend

A single table, applied via the framework's bundled Flyway instance (next version is `V4`, alongside `V1__brace_scheduled_jobs.sql` … `V3__brace_profiling_tables.sql` in `src/main/resources/brace/db/migration/`). Use `IF NOT EXISTS` guards per the idempotency lesson already learned for framework migrations (TODO: "Make framework migrations idempotent").

```sql
-- V4__brace_cache.sql
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

Operation mapping:

| `Cache` op | SQL |
|---|---|
| `get` | `SELECT value FROM brace_cache WHERE cache_key=? AND (expires_at IS NULL OR expires_at > now())` |
| `set` | `INSERT ... ON CONFLICT (cache_key) DO UPDATE SET value=?, tags=?, expires_at=?` (upsert) |
| `incr`/`decr` | `INSERT ... ON CONFLICT DO UPDATE SET counter = brace_cache.counter + ? RETURNING counter` — **atomic, no read-modify-write** |
| `delete` | `DELETE WHERE cache_key=?` |
| `deletePrefix` | `DELETE WHERE cache_key LIKE ? || '%'` |
| `clearTag` | `DELETE WHERE tags @> ARRAY[?]` (GIN index) |
| `clear` | `TRUNCATE brace_cache` |
| expiry sweep | background `DELETE WHERE expires_at < now()` (the existing 30s cleanup virtual thread, but DB-side) |

H2 parity (tests run on H2): H2 lacks native array + GIN. Two options — (a) gate the Postgres backend out of the H2 test path and test it against a Postgres testcontainer, or (b) model tags as a child table (`brace_cache_tags(cache_key, tag)`) which is portable and indexes fine on both. Lean toward the child table for test parity unless the array form measurably wins. Decide during Phase 1.

Expiry is enforced **on read** (the `expires_at > now()` predicate) so a missed sweep never serves stale data; the sweep is just space reclamation.

### Stats

hits/misses/evictions stay per-instance `LongAdder` in `Cache` — each server honestly reports its own hit rate, which is what you want on the ops dashboard. No shared stat aggregation in v1. `size()` becomes a `SELECT count(*)` for the shared backend (cheap enough for the dashboard; cache it for a few seconds if it shows up hot).

### `getOrSet` dogpile

Across servers, a cold key can have N suppliers run concurrently before the first `set` lands. Accepted in v1. The single-flight protection that `store.compute` gives today degrades to per-server single-flight (still better than nothing). If a specific expensive supplier needs global single-flight, that's a follow-up using an advisory lock (`pg_advisory_xact_lock(hashtext(key))`) — noted, not built.

### Ops integration

`POST /ops/cache/clear` against a shared backend clears it once for the whole fleet (it's `TRUNCATE`, not per-process) — a strict improvement over today's clear-one-box behavior. The dashboard cache stats remain per-instance.

## Why Postgres, not Redis

Redis is the textbook shared cache (sub-ms, native TTL/`INCR`/pub-sub). It's deliberately *not* v1:

- **Zero new infra.** Brace already requires Postgres; a cache table adds no moving parts, no new container on Dokploy, no new client dependency. That's the batteries-included bet.
- **Consistency over raw latency.** The problem being solved is correctness on a multi-server deploy, not microseconds. A ~1ms DB cache hit is still vastly cheaper than the uncached computation it replaces.
- **Durability is a free bonus.** A Postgres cache survives a full-fleet restart; a Redis cache (without persistence config) doesn't.

The `CacheBackend` SPI is the hedge: a high-throughput app that outgrows the DB cache adds `CacheBackend.redis(...)` later without touching `Cache` or app code.

## Phases

- **Phase 1 — SPI + Postgres backend.** Extract `CacheBackend`, refactor `Cache` to delegate, keep `InMemoryBackend` as default (no behavior change for existing apps). Add `V4__brace_cache.sql`, `PostgresBackend`, Jackson value serialization with type header, atomic `incr`. Decide array-vs-child-table for tags. Tests: backend conformance suite run against both in-memory and Postgres (testcontainer), asserting cross-"instance" consistency by pointing two `Cache` facades at one backend.
- **Phase 2 — Rendered page caching.** Factor a `Result → RenderedResponse` render seam out of `BraceHandler`, change `CachedHandler` to store/replay `RenderedResponse`. Verify a page cached by one facade is served by another. This phase also speeds up the existing in-memory page cache (skips re-render on hit).
- **Phase 3 — Ops + docs.** Confirm fleet-wide `/ops/cache/clear`, `size()` via count, dashboard wording. Document the shared-cache opt-in, the serialization constraint (Jackson-round-trippable values only), and the dogpile caveat in `BRACE-AGENTS.md` and `README.md` (per the CLAUDE.md "update docs on public API change" rule). Add a migration-guide note.
- **Phase 4 (optional, deferred) — near-cache + Redis.** Two-tier L1/L2 with short L1 TTL or invalidation signal; `CacheBackend.redis(...)`. Only if profiling shows the DB round-trip is a real hot-path cost.

## Open questions

1. Tags as Postgres `TEXT[]` + GIN vs. a portable `brace_cache_tags` child table — resolve in Phase 1 on the H2-parity axis.
2. Does `Cache` keep a fast object path for `InMemoryBackend` (no serialization) while shared backends get bytes, or does everything serialize uniformly for simplicity? Leaning: keep the fast path for the default so existing apps pay nothing.
3. Should `set` of a non-serializable value throw eagerly (fail-fast at write) or only when a shared backend is configured? Leaning: throw only when the configured backend requires bytes, so in-memory apps keep storing arbitrary objects.
