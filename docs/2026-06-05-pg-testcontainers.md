# Plan: Postgres testcontainer test tier

Status: draft
Date: 2026-06-05

## Goal

Add a second, smaller test tier that runs against **real Postgres** (via Testcontainers), alongside the existing fast H2 suite, so the framework can use Postgres-native SQL and *prove the actual prod path* — especially the concurrency behaviors H2 in-memory physically cannot reproduce. Companion to [`docs/2026-06-04-brace-postgres-native.md`](2026-06-04-brace-postgres-native.md) (the simplifications this unblocks) and [`docs/2026-06-04-brace-multi-server.md`](2026-06-04-brace-multi-server.md) (the concurrency bugs that motivate it).

## Why (the forcing function)

The multi-server correctness work (B1, B7, the ErrorStore upsert race, the durable-job lease/reaper) is all about Postgres **locking and isolation semantics**. H2 in-memory has no `SKIP LOCKED`, no advisory locks, no partial indexes, and doesn't reproduce READ COMMITTED row-visibility races or lock-wait convoys. So today we write concurrency fixes and validate them on a DB that *cannot exhibit the bug they fix* — `MultiInstanceSchedulerTest` proves the slot-claim logic, not the real locking. A Postgres tier closes that gap and unblocks Tier 2 of the postgres-native doc (`ON CONFLICT` + partial unique index, `TEXT[]`+GIN, JSONB) plus the full `SKIP LOCKED` batch claim.

Separately: framework migrations (`V1`–`V5`) are currently validated **only** against H2 — a Postgres-only DDL bug ships green. The PG tier tests migrations as actual prod DDL.

## Non-goals

- **Not replacing H2.** `mvn test` stays on H2: fast, offline, Docker-free, trivially parallel. Most of the ~67 test files are HTTP/routing/forms/sessions/CSRF/templates and don't touch SQL dialect at all — they gain nothing from real Postgres and shouldn't pay container cost.
- **Not migrating all DB tests at once.** Start with a pilot, move integration/concurrency tests over deliberately.
- **Not a per-test container.** That's the anti-pattern (resource-intensive). One shared container per run.

## Research findings — tricks/tips to adopt

Surveyed how teams actually run Postgres integration tests (Testcontainers docs/Docker, rieckpil, Vlad Mihalcea, IntegreSQL, several speed write-ups — sources at bottom). The adoptable ones:

### 1. Singleton container, manual lifecycle — not `@Container`
A `@Container`/`@Testcontainers`-managed container is torn down per test class. For a shared container, use a `static` field started once in a static initializer on a base class (or a JUnit 5 extension), and let it live for the whole JVM. Per-class containers are the recommended *balance*, but for an expensive resource like Postgres the singleton-per-run is the right call.

### 2. `.withReuse(true)` for local dev speed (not CI)
`.withReuse(true)` + `testcontainers.reuse.enable=true` in `~/.testcontainers.properties` keeps the container alive **across** `mvn test` runs, so local iteration doesn't pay startup each time. Caveats: the container config must match byte-for-byte or Testcontainers starts a fresh one; Ryuk does **not** reap reused containers, so they linger until manually `docker rm`'d. In CI the runner is ephemeral — don't reuse, just singleton-per-run. Always use dynamic port mapping (`getJdbcUrl()`), never fixed ports.

### 3. Aggressive speed knobs — safe because the test DB is disposable
A test Postgres can trade all durability for speed (never do any of this in prod):
- **tmpfs** the data dir: mount `/var/lib/postgresql/data` on tmpfs → RAM-backed, eliminates disk I/O (reports of 17 min → 4 min).
- **Command flags:** `-c fsync=off -c full_page_writes=off -c synchronous_commit=off`.
- **Faster initdb:** `POSTGRES_INITDB_ARGS=--nosync`.

  In Testcontainers (Java):
  ```java
  new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
      .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
      .withCommand("postgres", "-c", "fsync=off",
                   "-c", "full_page_writes=off", "-c", "synchronous_commit=off")
      .withEnv("POSTGRES_INITDB_ARGS", "--nosync")
      .withReuse(true);
  ```

### 4. Isolation strategy: truncation, **not** transaction-rollback (for Brace)
The three common strategies and their fit here:
- **Transaction-per-test + rollback** — fastest (~2–4 ms/test) and popular, **but a poor fit for Brace.** It wraps each test in one outer transaction the test never commits. Brace manages its *own* per-request transactions (open/commit/rollback in `BraceHandler`), so an outer wrapper fights the framework — and, decisively, the concurrency tests *require* really-committed rows visible across **multiple connections**, which rollback isolation forbids by construction.
- **Truncation between tests** (~40–60 ms/test) — `TRUNCATE … RESTART IDENTITY CASCADE` for the framework tables. Simple, robust, works with real commits and multiple connections. **This is our default.** It maps cleanly from today's habit of a unique `jdbc:h2:mem:<name>` per test.
- **Template database / warm pool** (IntegreSQL-style `CREATE DATABASE … TEMPLATE …`) — near-rollback speed *with* full isolation and real commits. Overkill for the pilot; the scalable endgame if the PG tier grows large.

### 5. Concurrency test pattern (the whole point)
Prove `SKIP LOCKED` / row-lock behavior with **two real JDBC connections**:
1. Seed and **commit** claimable rows.
2. Conn A: `BEGIN`; claim a batch with `… FOR UPDATE SKIP LOCKED`; **do not commit** (hold the locks).
3. Conn B: run the same claim; assert it gets a **disjoint** batch (it skipped A's locked rows) rather than blocking or double-claiming.
4. Commit/rollback A; assert totals.

This is exactly the property H2 can't express, and it must run on committed rows across two connections — reinforcing truncation over rollback isolation.

### 6. Run migrations through Brace's own Flyway
Don't seed schema with Testcontainers init scripts — boot the framework's normal Flyway path against the container's `getJdbcUrl()` so the real `V1`–`V5` DDL is what's tested. Brace already runs Flyway at startup, so an IT that constructs a `DatabaseFactory` pointed at the container gets migration coverage for free.

### 7. Keep the tiers physically separate in Maven
Two clean options:
- **Failsafe + naming:** PG tests as `*IT.java` run in the `integration-test` phase (failsafe); `mvn test` (surefire, H2) stays fast. CI runs `mvn verify`.
- **JUnit 5 tags + profile:** tag PG tests `@Tag("pg")`, gate with a `-Ppg` Maven profile (surefire `groups`/`excludedGroups`).

  Either way, `mvn test` must remain H2-only and Docker-free; the PG tier runs in CI (`publish.yml` is on `ubuntu-latest`, which has Docker; `mvn deploy` already runs tests) and locally on demand.

### 8. Minimal-wiring shortcut for the pilot
Testcontainers' JDBC-URL support (`jdbc:tc:postgresql:16:///bracetest`) auto-starts a container from the URL alone — handy to stand up the first IT with almost no harness code before investing in the base-class/lifecycle plumbing.

## Plan

**Phase 0 — Pilot (de-risk the toolchain). ✅ Built + verified green 2026-06-05** — `PostgresSkipLockedClaimIT` (+ pom: `org.testcontainers:postgresql` test dep, maven-failsafe-plugin for the `*IT` tier). Spins up a tmpfs/fsync-off `postgres:16-alpine` singleton, runs the framework Flyway migrations against it, asserts V1–V5 applied, and proves `FOR UPDATE SKIP LOCKED` hands disjoint batches to two concurrent JDBC connections (Tip 5). `mvn test` (surefire/H2) is unchanged (582 green) and excludes the IT; `mvn verify` runs the tier. Guarded by `assumeTrue(Docker available)` so it skips cleanly without Docker. **Confirmed green against a live container:** `mvn verify` → surefire 582 on H2 + failsafe `PostgresSkipLockedClaimIT` 2/2 on real Postgres (~1.9s after image cache).

  **It immediately earned its keep — caught a latent _production_ bug on its first real-Postgres run:** Flyway 10 split per-database support out of `flyway-core`. Core bundles only a few handlers (H2 among them — *why the H2 suite always passed*), but PostgreSQL needs the separate `flyway-database-postgresql` module. Brace declared only `flyway-core`, so **any app deploying on Postgres would fail at startup** when `DatabaseFactory` runs migrations: `FlywayException: No database found to handle jdbc:postgresql://…`. The H2-only suite could never have seen this — it's exactly the real-prod-path gap this tier exists to close. Fixed by adding the module at runtime scope (commit `b21eaa3`).

  **Second finding (lower stakes):** the test-app fixture `db/migration/V1__create_posts.sql` used H2/MySQL-only `BIGINT AUTO_INCREMENT` (fails on Postgres) — switched to portable `BIGINT GENERATED BY DEFAULT AS IDENTITY`. This unblocks running `DatabaseFactory`-based tests (which run that fixture) on the PG tier in Phase 1.

**Phase 1 — Harness. ✅ done 2026-06-05.** Extracted `PostgresTestBase` (singleton container shared across all `*IT` classes via one static field, Docker-guarded lazy start, tmpfs/fsync-off speed knobs, `truncate(...)` isolation, raw `connect()`). `PostgresSkipLockedClaimIT` refactored onto it. Added `DatabaseFactoryPostgresIT` — exercises the framework's `DatabaseFactory` (Flyway + Hibernate `StatelessSession`) on real Postgres: migrations apply, an IDENTITY id round-trips. That's the payoff of the `create_posts` fix and the template Phase 2 ports onto. ~~Wire the Maven tier (failsafe `*IT` or `-Ppg`).~~ ✅ Phase 0 (failsafe). ~~Add the PG tier to CI.~~ ✅ `.github/workflows/ci.yml` runs `mvn verify` on PRs + feature branches; `publish.yml`'s `mvn deploy` traverses the verify phase, so main/tags run the IT before publishing. ubuntu-latest has Docker.

  *Note: the missing `flyway-database-postgresql` module (caught by Phase 0) means real Brace+Postgres apps had been working around it per-app — e.g. `benchmark/pom.xml` declares the module itself. Now that Brace ships it transitively (runtime scope), those explicit declarations are redundant; the generated-app template (`ProjectGenerator`) never needed changing. Documented for upgraders in [`docs/migrations/brace-0.1.6-to-0.1.7.md`](migrations/brace-0.1.6-to-0.1.7.md) (the agent-facing migration-guide system described in `BRACE-AGENTS.md`).*

**Phase 2 — Migrate the DB-sensitive tests.** Port the integration tests whose value depends on real DB semantics to also run on the PG tier. Leave the dialect-agnostic majority on H2.

  - ✅ **Concurrency tests done 2026-06-05** (the ones H2 couldn't truly validate): `MultiInstanceSchedulerPostgresIT` (B1 — coordination over a real `SELECT … FOR UPDATE` row lock) and `DurableJobConcurrencyPostgresIT` (B7 — 80 jobs drained by 4 concurrent pollers, each must run exactly once; this is the real READ COMMITTED claim race). The H2 `MultiInstanceSchedulerTest`/`DurableJobTest` stay as fast Docker-free smoke/functional tests; the ITs add the real-locking fidelity.
  - ⬜ **Remaining ports:** `ErrorStoreTest` (esp. the check-then-insert that becomes `ON CONFLICT` in postgres-native Tier 2a — a duplicate-row race H2 can't show), `RegressionIntegrationTest`, the ops integration tests. Lower urgency — these are more CRUD/logic than concurrency.

**Phase 3 — Cash in the postgres-native simplifications.** With the tier proving the real path, land the Tier 2 items from the postgres-native doc: full `SKIP LOCKED` batch claim, `ErrorStore` `ON CONFLICT` + partial unique index, `TIMESTAMPTZ` (delete the hand-rolled timestamp parsers), `TEXT[]`+GIN for shared-cache tags, JSONB columns.

## Open decisions

- **Failsafe `*IT` vs `-Ppg` tag profile.** Lean failsafe — it's the conventional Maven slow/fast split and needs no profile flag in CI (`mvn verify`).
- **Pin Postgres version** to the prod target (whatever Dokploy provisions). Pin the exact minor for reproducibility.
- **Truncation now, template-DB later?** Start with truncation; revisit the IntegreSQL template-pool approach only if the PG tier grows enough that truncation overhead bites.

## Sources

- [Testcontainers best practices (Docker)](https://www.docker.com/blog/testcontainers-best-practices/) · [container lifecycle (Testcontainers guide)](https://testcontainers.com/guides/testcontainers-container-lifecycle/)
- [Reuse containers for fast integration tests (rieckpil)](https://rieckpil.de/reuse-containers-with-testcontainers-for-fast-integration-tests/)
- [Optimize Postgres containers for testing (Babak K. Shandiz)](https://babakks.github.io/article/2024/01/26/re-015-optimize-postgres-containers-for-testing.html) · [Speeding up PostgreSQL in containers (miry, DEV)](https://dev.to/miry/speeding-up-postgresql-in-containers-1eeg)
- [Database test isolation (Eric Radman)](https://eradman.com/posts/database-test-isolation.html) · [IntegreSQL — template-DB pooling](https://github.com/allaboutapps/integresql) · [Transaction rollback isolation (DEV)](https://dev.to/miry/from-4-minutes-to-3-seconds-how-database-transaction-rollback-revolutionized-test-suite-4olh)
- [Postgres job queue with SKIP LOCKED (Vlad Mihalcea)](https://vladmihalcea.com/database-job-queue-skip-locked/)
