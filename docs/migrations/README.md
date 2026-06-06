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
| 0.1.1 → 0.1.3 | ❌ **missing** (no 0.1.2 release; 0.1.2 was SNAPSHOT-only) |
| 0.1.3 → 0.1.4 | ❌ **missing** |
| 0.1.4 → 0.1.5 | ❌ **missing** |
| 0.1.5 → 0.1.6 | ❌ **missing** |
| 0.1.6 → 0.1.7 | ✅ `brace-0.1.6-to-0.1.7.md` (in progress — 0.1.7 is the current `-SNAPSHOT`) |

## Known gap (tracked, not yet backfilled)

The four `0.1.1 → 0.1.6` guides above were never written. Backfilling them means diffing the public
API between each tag pair to recover the breaking changes (if any) and writing a guide per step
(stating "no breaking changes" where that's what the diff shows). This is archival work deliberately
left separate from feature changes — do it as its own focused pass, not bundled into unrelated work.
The `CLAUDE.md` authoring rule now requires keeping new steps current so this gap does not grow.
