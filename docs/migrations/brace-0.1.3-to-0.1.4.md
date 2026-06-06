# Migrating from Brace 0.1.3 → 0.1.4

**No breaking API changes.** This release makes the three bundled framework migrations
idempotent. There are no Java API changes, no new CLI commands, and no config changes.
However, there is **one operational concern for existing deployments** — read it before you
upgrade, because it can stop your app from starting.

## Operational concern: Flyway checksum mismatch on the framework history table

The framework migrations introduced in 0.1.3 had their DDL changed:

- `V1__brace_scheduled_jobs.sql` — `CREATE TABLE` → `CREATE TABLE IF NOT EXISTS`, and the
  index → `CREATE INDEX IF NOT EXISTS`.
- `V2__brace_ops_tables.sql` — `ops_errors` and `ops_timeseries` → `CREATE TABLE IF NOT EXISTS`.
- `V3__brace_profiling_tables.sql` — `ops_profiling_snapshots` → `CREATE TABLE IF NOT EXISTS`.

The table, index, and column definitions themselves are **unchanged** — these are pure
idempotency guards. The change exists to fix the pre-0.1.1 upgrade case where an app had
already hand-created `scheduled_jobs`/`ops_*` tables before Brace bundled its own migrations;
`IF NOT EXISTS` turns the framework `CREATE TABLE` into a safe no-op instead of an abort.

**Why this matters on upgrade:** Flyway stores a checksum of each migration's content in its
history table and re-validates those checksums on every `migrate()`. Any deployment that
already applied V1/V2/V3 under 0.1.3 will, on first startup under 0.1.4, find that the stored
checksums no longer match the new `IF NOT EXISTS` file content and **abort startup with a
checksum-mismatch validation error** against `flyway_brace_history`.

Brace does **not** run Flyway `repair()` automatically, so this is not self-healing.

### Who is affected

- **Affected:** real (persistent) deployments — typically Postgres — that already ran the
  framework migrations under 0.1.3.
- **Not affected:** brand-new deployments (migrations applied fresh from 0.1.4 content);
  ephemeral/in-memory test runs (H2) that re-migrate from scratch each boot; and pre-0.1.1
  upgraders who never had these exact files applied (the intended beneficiaries of the fix).

### What to do

Realign the stored checksums by running Flyway `repair` **against the framework history
table** (`flyway_brace_history`, *not* your app's default `flyway_schema_history`). Because
the DDL change is cosmetic — the resulting schema is identical — repair is safe and
data-preserving; no schema rebuild is needed.

The separate framework history table scopes the blast radius: your app's own migrations and
`flyway_schema_history` are unaffected.
