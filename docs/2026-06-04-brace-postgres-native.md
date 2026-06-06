# Plan: Postgres-native simplification

Status: draft
Date: 2026-06-04

## Goal

Brace already requires Postgres in production. This doc inventories the places where the framework currently writes to the **intersection of H2 and Postgres SQL** instead of using Postgres directly, and what gets simpler, faster, or more correct if we commit to Postgres-native SQL. It is a companion to [`docs/2026-06-04-brace-multi-server.md`](2026-06-04-brace-multi-server.md) and [`docs/2026-06-04-brace-shared-cache.md`](2026-06-04-brace-shared-cache.md): several of those plans' fixes land more cleanly on Postgres-native primitives.

## The framing fact that colors everything

**The test suite runs native H2** (`jdbc:h2:mem:…`), not H2's `MODE=PostgreSQL`. So every piece of SQL the framework emits today must parse and execute under *both* the H2 dialect and Postgres. That intersection — not Postgres — is the real constraint the code is written against. It's why we have hand-rolled multi-format timestamp parsers, check-then-insert instead of upsert, and a child-table hedge in the shared-cache design instead of an array column.

Committing to Postgres is therefore not really a SQL decision — it's a decision about **where to pay the H2-test cost** for any feature that wants a Postgres-only primitive. Three options, per-feature:

1. **Dialect branch** — emit Postgres SQL in prod, an H2-portable equivalent in tests. Cheap per call site, but every branch is a second code path that tests only exercise on the H2 side.
2. **Postgres testcontainer** — run (some) tests against real Postgres via Testcontainers. Highest fidelity; lets us use any Postgres feature with the test proving the *actual* prod path. Costs Docker in CI and slower tests; best introduced as a second, smaller suite alongside the fast H2 one.
3. **Generate-in-Java** — keep the DB dumb, do the work in application code (e.g. parse timestamps, dedupe in a map). What we mostly do today; it's why the parsers exist.

The wins below are sorted by how much H2 tension they carry, because that tension *is* the cost.

## Tier 1 — cleanest wins (low H2 tension, do first)

### 1a. Durable job claim → `FOR UPDATE SKIP LOCKED` ✅ shipped
- **Location:** `JobPoller.pollAndExecute` + `claimBatchPostgres` / `selectCandidatesH2` / `claimAndExecute`.
- **Was:** poll `SELECT`ed up to 50 claimable rows in one (committed) transaction, then each `executeJob` re-claimed its row with `UPDATE … WHERE started_at IS NULL` in a separate transaction. The latent double-run (B7) was that the claim branched on *not throwing* rather than on the affected-row count.
- **Shipped 2026-06-04 (B7, H2-portable):** the per-row claim runs through raw JDBC and proceeds only when `executeUpdate() == 1`, so the 0-row loser under READ COMMITTED no longer falls through and executes the body.
- **Shipped 2026-06-06 (Postgres batch claim):** `pollAndExecute` now branches on `DatabaseFactory.isPostgres()`. On Postgres, `claimBatchPostgres` folds selection + claim into a *single* transaction — `UPDATE scheduled_jobs SET started_at = CURRENT_TIMESTAMP, attempts = attempts + 1 WHERE id IN (SELECT id … ORDER BY run_at LIMIT 50 FOR UPDATE SKIP LOCKED) RETURNING …` (raw JDBC; `RETURNING attempts - 1` hands back the pre-increment count so the shared `runJobBody` retry math is unchanged). SKIP LOCKED hands each instance a **disjoint batch**, so every row is claimed exactly once by construction and the per-row re-claim is gone on this path. H2 keeps the portable select-then-per-row-claim (`selectCandidatesH2` + `claimAndExecute`, still defended by the B7 row-count guard) because H2 can't express SKIP LOCKED. Proven by `DurableJobConcurrencyPostgresIT` (80 jobs, 4 concurrent pollers, exactly-once) on the real-Postgres tier; the H2 `DurableJobTest` covers functional behavior.

### 1b. `TIMESTAMP` → `TIMESTAMPTZ` ✅ shipped 2026-06-06
- **Location:** every framework migration column that stores an instant (`scheduled_jobs.run_at/started_at/completed_at/failed_at/created_at`, `ops_errors.first_seen/last_seen/resolved_at`, `ops_timeseries.ts`, `ops_profiling_snapshots.ts`, `brace_scheduled_runs.last_run_at`).
- **Was:** zone-less `TIMESTAMP`. Because different drivers/dialects hand zone-less timestamps back in different shapes, the framework carried **hand-rolled multi-format timestamp parsers** to normalize them — `ErrorStore.parseFirstSeen`, `RegressionTracker.parseInstant` — that existed *only* to paper over this. There was also a latent correctness bug: on a server whose JVM/OS zone isn't UTC, zone-less round-trips can shift instants.
- **Shipped:** a single shared migration `brace/db/migration/V7__brace_timestamptz.sql` ALTERs every instant column to `TIMESTAMP WITH TIME ZONE` using the SQL-standard `SET DATA TYPE` form, which parses on **both** H2 and Postgres (the Postgres-only `USING … AT TIME ZONE` clause is omitted so one file runs on both tiers; correct on the UTC-session deployments Brace targets). The parse-side audit confirmed the only Java-side timestamp readers were the two parsers, both consuming `ops_errors` timestamps via `ErrorStore.list()`. Both are **deleted**: `ErrorStore.list`/`resolve` now put a typed `Instant` in the result map via a small `toInstant(Object)` type-dispatch helper (handles whatever `OffsetDateTime`/`Timestamp`/`Instant` Hibernate surfaces, and throws loudly on anything else instead of silently returning null), the `since` filter compares `Instant`s directly, and `RegressionTracker.seed` casts the map value straight to `Instant`. `Json` already serializes `Instant` as ISO-8601 (JavaTimeModule, dates-as-timestamps disabled), so the `/ops/errors` JSON is now canonical ISO instead of a driver-shaped string. Numbered V7, not V6, because the Postgres-only `migration_pg/V6` already holds version 6 in the shared `flyway_brace_history` sequence on Postgres. Proven on both tiers (`mvn test` H2 + `mvn verify` PG).

### 1c. `ops_timeseries`: batch inserts, add retention ✅ shipped 2026-06-06 (rollup deferred)
- **Location:** the `ops-flush-*` jobs in `Brace.start` + `Brace.insertMetrics`, the new `ops-metrics-prune` job, and the `ops_timeseries` / `ops_profiling_snapshots` tables.
- **Was:** each flush did up to ~21 single-row `INSERT`s (and the profiling flush up to 40); the tables had no retention and grew unbounded. (Multi-server double-counting was already fixed by B1 — the flush jobs run once cluster-wide.)
- **Shipped:**
  - **Batch inserts.** Each flush now emits one multi-row `INSERT`: the `http`/`cache`/`jvm` flushes build a `LinkedHashMap` and go through the `insertMetrics` helper (which renders `VALUES (?,?,?), …` and lets `Database.sql` renumber the `?`s); the profiling flush builds one `INSERT … VALUES (?,?,?,?), …` for its up-to-40 rows. ~21 round-trips/flush → 1; 40 → 1. Covered by `OpsMetricsFlushTest` on H2.
  - **Retention.** New `ops-metrics-prune` daily job deletes `ops_timeseries` and `ops_profiling_snapshots` rows past a 14-day window — closes the unbounded-growth foot-gun. Runs once cluster-wide via B1 coordination.
- **Deferred — the `date_trunc` rollup.** `ops_timeseries` has **no reader** anywhere today (the dashboard sparklines use in-memory `Stats.snapshot()`), so a rollup query would be dead code. It belongs with a future "dashboard reads historical metrics from the table" feature, not this DB-simplification pass; revisit when that consumer exists.

### 1d. Delete the dead MySQL dialect branch ✅ shipped
- **Location:** `DatabaseFactory.detectDialect` (`:137-147`).
- **Shipped 2026-06-04:** removed the `jdbc:mysql:` → `MySQLDialect` branch and updated the error to advertise only `jdbc:h2:`/`jdbc:postgresql:`. The branch implied a portability contract the migrations never honored (they're Postgres/H2 SQL), so a MySQL URL would only fail later at migration time with a more confusing error. Zero tension. (The "Multi-database support testing" TODO line is aspirational and unaffected — re-adding MySQL would mean dialect-specific migrations, not just a dialect string.)

## Tier 2 — high payoff, real H2 cost (want a Postgres testcontainer)

### 2a. `ErrorStore` check-then-insert → `INSERT … ON CONFLICT DO UPDATE` ✅ shipped 2026-06-06
- **Location:** `ErrorStore.java` (`upsertPostgres` / `upsertH2`).
- **Was:** SELECT-then-INSERT-or-UPDATE to fold a recurring error into one row with an occurrence count. Under load two instances (or two threads) could both pass the check and both insert → **duplicate rows + a lost increment**. The race was genuine and worsened with traffic.
- **Shipped:** on Postgres, a single `INSERT … ON CONFLICT (error_type, route) WHERE resolved_at IS NULL DO UPDATE SET occurrence_count = occurrence_count + 1, last_seen = … RETURNING (xmax = 0)` against a **partial unique index** (`ops_errors_unresolved_dedupe`, `migration_pg/V6`) — conditional on `resolved_at IS NULL` so a resolved error recurring still gets a fresh row. Atomic, no race, one round-trip. `ErrorStore` branches on `DatabaseFactory.isPostgres()`; H2 keeps check-then-insert, so the H2 cost (no partial unique index, different upsert syntax) is paid by keeping both branches rather than dialect-translating one statement. The real prod statement is proven by `ErrorStorePostgresIT` (50 concurrent writers fold into exactly one row with an exact count) — the canonical testcontainer payoff. See `docs/2026-06-05-pg-testcontainers.md` Phase 3.

### 2b. Shared cache: `TEXT[]` + GIN for tag indexing
- **Location:** the tag-invalidation design in [`docs/2026-06-04-brace-shared-cache.md`](2026-06-04-brace-shared-cache.md).
- **Today / tension:** that plan hedges toward a child join-table for tag→key membership partly because an array column with a GIN index isn't H2-portable. On Postgres-native, a `tags TEXT[]` column with a `GIN` index lets `clearTag` be a single `DELETE … WHERE tags @> ARRAY[?]`, simpler and faster than the child-table join.
- **Win:** unblocks the array design in the shared-cache plan. Needs the testcontainer (GIN + array operators are Postgres-only).

## Tier 3 — useful, incremental

### 3a. `INSERT … RETURNING id` in `Jobs.schedule`
- **Location:** `Jobs.java:73-92`.
- **Today:** raw-JDBC `getGeneratedKeys` to read the new job id, bypassing the instrumented `Database` (so the insert isn't counted in query stats).
- **Win:** `INSERT … RETURNING id` routed back through `Database`, so the write is counted and the key read is one statement. Postgres supports `RETURNING`; H2 supports it too in recent versions — verify the H2 version in the test pom before relying on it, otherwise a small dialect branch.

### 3b. `JSONB` for `queries_before` / `request_headers` / `job_data`
- **Location:** error capture columns and `scheduled_jobs.job_data` (currently opaque `TEXT`).
- **Today:** these are serialized blobs the DB can't see into.
- **Win:** `JSONB` makes error context and job payloads **queryable** (filter errors by a header, index a job-data field). Writes stay portable (you can write a JSON string into a `JSONB` column); only the read-side queries become Postgres-only, so this can land write-first and add query features behind the testcontainer.

## Explicitly ruled out (not portability scaffolding — leave them)

- **`?` → `?1` positional-param rewriting** (`Database.convertPositionalParams`). This is a **Hibernate requirement**, not a dialect workaround — Hibernate 7 needs ordinal params numbered. It does not go away on Postgres-native. Keep.
- **`DATABASE_URL` parsing / `jdbc:` prefixing / `user:pass@` stripping** (`DatabaseFactory.parseDbConfig`). This is **PaaS plumbing** (Dokploy/Heroku/Render/Railway/Fly inject `postgresql://user:pass@host/db`), not portability scaffolding. It's Postgres-specific *already* and stays. Keep.

## Suggested order

1. ✅ Tier 1d (drop MySQL branch) and the row-count half of 1a (B7) — shipped 2026-06-04.
2. ✅ Tier 1c (ops_timeseries batch inserts + retention) — shipped 2026-06-06; rollup deferred until a reader exists. H2-portable, no testcontainer needed.
3. ✅ Tier 1b (`TIMESTAMPTZ` + delete parsers) — shipped 2026-06-06 (V7 migration; both parsers deleted; proven on the H2 + PG tiers).
4. ✅ **Decision point resolved:** the Postgres testcontainer suite exists (see [`docs/2026-06-05-pg-testcontainers.md`](2026-06-05-pg-testcontainers.md)). On it, shipped 2026-06-06: Tier 2a (`ErrorStore` `ON CONFLICT`), Tier 1b (`TIMESTAMPTZ` + parser deletion), and the SKIP LOCKED batch-claim half of 1a. The rest of Tier 2 (TEXT[]+GIN, JSONB) lands next, with tests proving the real prod statement.
5. Tier 3 as opportunistic follow-ups.

## Open decision

Whether to introduce a Postgres testcontainer suite is the **central fork**. Without it, Tier 2 either ships untested-on-the-real-path (dialect branches whose Postgres side CI never runs) or doesn't ship. With it, the whole "intersection of H2 and Postgres" constraint relaxes and most of this doc becomes mechanical. Recommend introducing it as a *second, smaller* suite (a handful of tests pinned to the Postgres-only statements) rather than migrating the 582-test fast suite off H2.
