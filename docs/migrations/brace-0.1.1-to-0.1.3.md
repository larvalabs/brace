# Migrating from Brace 0.1.1 → 0.1.3

> **Note on the version jump:** there is no 0.1.2 release. `0.1.2-SNAPSHOT` was a
> development-cycle version only and was never tagged or published, so the upgrade path
> goes directly from 0.1.1 to 0.1.3. This is one combined step.

This release **bundles the framework's own database migrations into the jar** for the first
time, adds automatic parsing of PaaS-style `DATABASE_URL`s, and tightens up the CLI/ops
flow. There are **breaking changes** — most importantly around the database — so read the
first section carefully if you have an already-deployed app.

## Breaking changes

### Framework Flyway migrations are now bundled and run automatically

This is the change most likely to affect an existing deployment.

In 0.1.1, the framework's tables (`scheduled_jobs`, `ops_errors`, `ops_timeseries`,
`ops_profiling_snapshots`) lived only under `src/test/resources` — they were **not** shipped
in the jar. If your app used jobs or the ops dashboard, you had to create those tables
yourself, typically as your own `V*` app migrations.

In 0.1.3 the framework ships these migrations inside the jar at
`classpath:brace/db/migration` (`V1__brace_scheduled_jobs.sql`, `V2__brace_ops_tables.sql`,
`V3__brace_profiling_tables.sql`) and runs them automatically on startup. They are tracked
in a **separate Flyway history table, `flyway_brace_history`**, independent of your app's
`flyway_schema_history`. This means the framework and your app each have their own version
space — both can have a `V1`.

**Impact:** if your app previously hand-created any of these tables (e.g. you wrote your own
`scheduled_jobs` migration to use durable jobs), the bundled framework migration will now
also try to `CREATE TABLE` them and fail against the non-empty schema.

**What to do:**

- If you hand-rolled `scheduled_jobs` / `ops_*` tables, drop those statements from your app
  migrations (or the tables, and let the framework recreate them) so the framework owns them.
- Brand-new databases need no action — the framework migrations apply cleanly from V1.

> Brace 0.1.4 makes these three framework migrations idempotent (`CREATE TABLE IF NOT
> EXISTS`) specifically to smooth this pre-0.1.1 upgrade case. See the 0.1.3 → 0.1.4 guide.

### App migration baseline is now `0`

Because framework migrations run first, your app's schema is no longer empty when *its*
migrations run. To keep your own `V1` from being silently skipped, `DatabaseFactory` now sets
`.baselineVersion("0")` on the app Flyway instance (0.1.1 used Flyway's default of `1`).

For most apps this is invisible and correct. If you were relying on the old default baseline
behavior, be aware your first app migration is now guaranteed to run.

### Ops POST endpoints no longer require CSRF

Ops POST routes (`/ops/auth`, `/ops/auth/login-token`, `/ops/errors/{id}/resolve`,
`/ops/cache/clear`) are now registered with CSRF disabled. They authenticate with a signed
payload / bearer token, not a session cookie, so CSRF never applied to them — but on any app
that also called `.sessions(...)`, CSRF enforcement was blocking the CLI from reaching them.

**Impact:** for the CLI ops commands to authenticate against an app that uses sessions, the
**server must be running >= 0.1.3**.

### CSRF rejection responses are now JSON

A CSRF failure now returns `{"error":"csrf_required"}` with status 403 instead of the old
plaintext `Forbidden` body. If you have tests or clients asserting on the old plaintext 403
body, update them. (The CLI uses this `error` code to print a targeted remediation message.)

### `brace --env <name>` with an unconfigured URL now fails loudly

Previously any `--env` whose `ops.<env>.url` was unset silently fell back to the default local
URL. Now only `local` falls back; any other env (e.g. `prod`) without `ops.<env>.url` in
`.brace` errors:

```
ops.prod.url is not set in .brace. Add `ops.prod.url=https://your-app` to .brace, or pass --url <url>.
```

**Mechanical fix:** if you ran ops commands against a named env without setting its URL and
relied on the localhost fallback, set `ops.<env>.url` in `.brace` (or pass `--url`).

## New APIs and capabilities (additive — no migration required)

- **PaaS `DATABASE_URL` parsing** — `DatabaseFactory` now accepts bare PaaS-style URLs from
  Dokploy/Heroku/Render/Railway/Fly directly:
  - `postgresql://user:pass@host:5432/db` — `jdbc:` prefix is added and `user:pass@` is split
    out into separate credentials.
  - `postgres://...` is rewritten to `jdbc:postgresql://...`.
  - URL-encoded password characters are decoded. Explicit `user`/`password` args still win
    over URL-embedded ones.

  In 0.1.1 a raw `postgresql://...` URL failed (Flyway "No database found to handle <url>").
  In 0.1.3 it works as-is. New public `DatabaseFactory.DbConfig` record and
  `DatabaseFactory.parseDbConfig(...)`.

- **`brace version`** — new CLI command (also `--version` / `-v`) prints the framework
  version, backed by new public `BraceVersion.get()` (reads a Maven-filtered
  `META-INF/brace-version.txt`). The usage banner now shows the real running version instead
  of a hardcoded `v0.1.0`.

  ```
  $ brace version
  0.1.3
  ```

- **`brace new` pins to the running CLI version** — the generated `pom.xml` now emits
  `<brace.version>` + `BraceVersion.get()` instead of a hardcoded `0.1.0`, so a project
  scaffolded by a 0.1.3 CLI pins to 0.1.3.

- **`brace new <name>` guards against existing directories** — if the target directory already
  exists, the command now exits 1 with `Failed to create project: <path> already exists.`
  rather than writing into / partially overwriting it.

- **Better ops auth diagnostics** — `brace init` now reports `ops.prod.url` status and prints
  a "Remote (prod)" section, and ops auth failures surface a structured
  `CliAuth.OpsAuthFailure` (`status` / `body` / `code`) with smarter remediation messages.

See `BRACE-AGENTS.md` for full documentation of each new API.
