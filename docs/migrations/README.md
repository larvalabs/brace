# Brace migration guides

One guide per released version step, named `brace-FROM-to-TO.md`, listing every user-visible
change for that step with before/after examples. This is the upgrade path agents and humans follow
when bumping `<brace.version>` — see the "Upgrading" section of `BRACE-AGENTS.md`, and the authoring
rules in `CLAUDE.md` ("Migration guides (per version step)").

A guide is required for **every** step, even one with no breaking changes — it should say so
explicitly. Agents are instructed to read every guide between two versions in order, so a *missing*
file is indistinguishable from a *lost* one. Keep the in-progress guide (ending at the current
`-SNAPSHOT`) current as changes land; don't wait for tag time.

## Released steps and guide status

| Step | Guide |
|---|---|
| 0.1.0 → 0.1.1 | ✅ `brace-0.1.0-to-0.1.1.md` |
| 0.1.1 → 0.1.3 | ✅ `brace-0.1.1-to-0.1.3.md` (no 0.1.2 release; 0.1.2 was SNAPSHOT-only) |
| 0.1.3 → 0.1.4 | ✅ `brace-0.1.3-to-0.1.4.md` |
| 0.1.4 → 0.1.5 | ✅ `brace-0.1.4-to-0.1.5.md` |
| 0.1.5 → 0.1.6 | ✅ `brace-0.1.5-to-0.1.6.md` |
| 0.1.6 → 0.1.7 | ✅ `brace-0.1.6-to-0.1.7.md` (in progress — 0.1.7 is the current `-SNAPSHOT`) |

## Backfill complete

The four `0.1.1 → 0.1.6` guides were backfilled by diffing the public API between each tag pair
(`v0.1.1..v0.1.3`, `v0.1.3..v0.1.4`, `v0.1.4..v0.1.5`, `v0.1.5..v0.1.6`) to recover the
user-visible changes. Notable findings worth flagging for upgraders:

- **0.1.1 → 0.1.3** bundles the framework's Flyway migrations into the jar (separate
  `flyway_brace_history` table) — apps that hand-created `scheduled_jobs`/`ops_*` tables must
  reconcile. Also adds PaaS `DATABASE_URL` parsing and `brace version`.
- **0.1.3 → 0.1.4** has no API changes but carries a Flyway **checksum-mismatch** risk: the
  bundled migrations were made idempotent, so deployments that already applied them under 0.1.3
  need a `repair` against `flyway_brace_history`.
- **0.1.4 → 0.1.5** fixes a large-request-body hang (real virtual threads) and changes the CLI
  default env to `prod` when `ops.prod.url` is set.
- **0.1.5 → 0.1.6** reworks CLI install (`~/.brace` launcher, `self-update`, per-project
  pinning) and switches generated projects to JitPack coordinates.

The `CLAUDE.md` authoring rule requires keeping each new step's guide current so this gap does not
reopen.
