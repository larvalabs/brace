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

### 1a. Durable job claim → `FOR UPDATE SKIP LOCKED` ✅ partially shipped
- **Location:** `JobPoller.java` poll (`:70-89`) + per-row claim (`:119-130`).
- **Today:** poll `SELECT`s up to 50 claimable rows in one (committed) transaction, then each `executeJob` re-claims its row with `UPDATE … WHERE started_at IS NULL` in a separate transaction. The latent double-run (B7) was that the claim branched on *not throwing* rather than on the affected-row count.
- **Shipped 2026-06-04:** the claim now runs through raw JDBC and proceeds only when `executeUpdate() == 1`, so the 0-row loser under READ COMMITTED no longer falls through and executes the body. That closes B7 with a fully H2-portable change — no migration, no dialect branch.
- **Still on the table (Postgres-native):** fold selection + claim into a *single* transaction whose poll does `… ORDER BY run_at LIMIT 50 FOR UPDATE SKIP LOCKED` and sets `started_at` before releasing the lock. That hands each instance a **disjoint batch** and deletes the per-row re-claim dance entirely (each row is claimed exactly once by construction, not just defended after the fact). The reason this is *not* yet done: H2 does not reliably support `SKIP LOCKED`, so it needs either a dialect branch or a Postgres testcontainer to test the real path — i.e. it is **not** zero-tension, despite the small diff. Park it behind the testcontainer decision.

### 1b. `TIMESTAMP` → `TIMESTAMPTZ`
- **Location:** every framework migration column that stores an instant (`scheduled_jobs.run_at/started_at/completed_at/failed_at`, `ops_timeseries.ts`, error/regression timestamps, `brace_scheduled_runs.last_run_at`).
- **Today:** zone-less `TIMESTAMP`. Because different drivers/dialects hand zone-less timestamps back in different shapes, the framework carries **hand-rolled multi-format timestamp parsers** to normalize them — e.g. `ErrorStore.parseFirstSeen`, `RegressionTracker.parseInstant` — that exist *only* to paper over this. There is also a latent correctness bug: on a server whose JVM/OS zone isn't UTC, zone-less round-trips can shift instants.
- **Win:** store `TIMESTAMPTZ`, read `OffsetDateTime`/`Instant` directly, and **delete the parsers**. Fixes the non-UTC-server bug by construction. H2 tension: H2 has `TIMESTAMP WITH TIME ZONE`; needs a parse-side audit to confirm both dialects return a typed instant so the parsers can actually go. Medium-low tension, high cleanup payoff.

### 1c. `ops_timeseries`: batch inserts, add a rollup, add retention
- **Location:** the `ops-flush-*` jobs (`Brace.java:560-639`) and the `ops_timeseries` table.
- **Today:** each flush does ~21 single-row `INSERT`s; the table has **no reader, no retention, and grows unbounded**. (Multi-server double-counting of this table is already fixed by B1 — the flush jobs now run once cluster-wide.)
- **Win:** batch the per-flush inserts into one multi-row `INSERT`; add a `date_trunc`-based rollup for dashboard reads; add a pruning job that deletes rows past a retention window. Mostly H2-portable (`date_trunc` and multi-row insert both work on H2); the pruning job is plain SQL.

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
2. Tier 1c (ops_timeseries batch + rollup + retention) — H2-portable, removes an unbounded-growth foot-gun, no testcontainer needed.
3. Tier 1b (`TIMESTAMPTZ` + delete parsers) — biggest cleanup payoff; do the parse-side audit first.
4. **Decision point:** stand up a Postgres testcontainer suite. Everything in Tier 2 (and the SKIP LOCKED batch-claim half of 1a) is gated on this. Once it exists, those land with tests proving the real prod statement.
5. Tier 3 as opportunistic follow-ups.

## Open decision

Whether to introduce a Postgres testcontainer suite is the **central fork**. Without it, Tier 2 either ships untested-on-the-real-path (dialect branches whose Postgres side CI never runs) or doesn't ship. With it, the whole "intersection of H2 and Postgres" constraint relaxes and most of this doc becomes mechanical. Recommend introducing it as a *second, smaller* suite (a handful of tests pinned to the Postgres-only statements) rather than migrating the 582-test fast suite off H2.
