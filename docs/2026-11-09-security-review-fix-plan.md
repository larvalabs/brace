# Security Review Fix Plan: 2026-11-09

This is the development plan for the findings in
[`2026-11-09-security-review-todos.md`](2026-11-09-security-review-todos.md). That file is the
tracker: it holds each finding's checkbox, fix spec, tests and suggested model. This file covers
the open decisions, how the work is split across agents, the order it merges in, and what has to
be true before it does.

**Status: not started.** Decisions D1 to D6 were settled on 2026-11-09: the recommended option
in every case.

## Goal

The 2026-10 review made most protections structural. These fixes close what was left around
that core, in the same spirit: prefer a startup refusal or a safe default over a documented
caveat, and turn every probe that found a bug into a regression test.

Two findings set the priority. H1 is a regression that an earlier security fix introduced, so
the template track gets the strongest model and its own review. H2 is a release step that was
not taken, so the release checklist changes as well as the code.

## Decisions

Each of these changes behaviour that apps or operators see. **All six are decided: the
"Decided" column.** The alternative that was considered is kept for the record. The fix specs in
the todos doc match.

| # | Finding | Decided | Alternative considered | Affects |
|---|---|---|---|---|
| D1 | M3 static mappings | `staticFiles` takes an `Access` or an explicit public marker, and `start()` refuses a mapping with neither (same model as routes; breaking) | Docs only: state that mappings are always public | Track C, M3 |
| D2 | M4 missing rate-limit key | Null and blank keys share one bucket by default; the exemption becomes opt-in (breaking) | Keep the exemption; correct the docs | Track C, M4 |
| D3 | M4 `req.form()` | Body fields are no longer filled from the query string on POST/PUT/PATCH/DELETE (breaking) | Leave it; change the docs' login example to key on the bound value | Track C, M4 |
| D4 | M9 STARTTLS | Required for `smtp://`, with an explicit plain opt-out (breaking for plain relays) | Warn at startup only | Track D, M9 |
| D5 | H2 toolchain floor | Floor becomes `0.1.11` at release and every `0.1.11` prerelease is refused; older pins need `BRACE_ALLOW_OLD_TOOLCHAIN=1` | Floor `0.1.11`, but allow `rc.6` and later | Track B, H2 |
| D6 | L1 and L2 ops | An unknown `scope:` fails startup; `opsOrigins` is required everywhere except dev (breaking) | Warn only | Track D, L1 and L2 |

A consequence of D5: once the floor is `0.1.11`, a project pinned to `0.1.10` or an
early rc cannot use the launcher without the override until it upgrades.

## Working conventions

These follow [`reviews/README.md`](reviews/README.md) and `AGENTS.md`.

- **Branch and commits.**
  - One integration branch: `security-review-2026-11`. Each track works on its own branch in its
    own worktree and merges into it.
  - One commit per finding: `fix(security): <ID> <summary>`. The body references the todos doc
    and ticks that finding's checkbox. L7 and L11 are one commit per bullet, same ID.
  - No Claude footer, session link or co-author trailer (per `AGENTS.md`).
- **Every commit:**
  - starts from a failing test that reproduces the exploit in the todo entry, then fixes it;
  - passes `mvn test`;
  - updates `BRACE-AGENTS.md` and `README.md` when public API changes;
  - adds a migration-guide entry for any user-visible change to the in-progress step's guide
    (the one ending at the `-SNAPSHOT` version in `pom.xml`).
- **Independent review per commit.** A fresh agent gets the diff and the finding, not the
  author's reasoning, and tries to break the fix. Confirmed problems are fixed in a "review
  fixes" commit before the track merges.
- **Agents run with worktree isolation and absolute paths.** Background agents have resolved
  relative paths against the main checkout before; each brief names the worktree root.

## Tracks

Four tracks touch disjoint files and run in parallel. Within a track the commits are sequential,
in the order listed, because they share files. Sizes: **S** is a few hours, **M** about a day,
**L** several days including a design step.

### Track A: template output

Files: `BraceHtmlOutput.java`, `BraceHtmlOutputTest`, `csrf-htmx.js`, the XSS section of
`docs/SECURITY.md`. Reviewer: Fable 5.2.

| Order | Fix | Model | Size | Notes |
|---|---|---|---|---|
| 1 | H1 script-body escaping | Fable 5.2 | M | Add the script-body test matrix first, in String and binary modes. Decide whether a hold may ever open inside a raw-text element; the answer shapes the L7 commits. |
| 2 | L7 mixed-case `<Script>` | Fable 5.2 | S | Same code path as H1. |
| 3 | L7 template-written `javascript:` | Fable 5.2 | S | |
| 4 | L7 `hx-vals` / `hx-headers` `js:` guard | Fable 5.2 | S | |
| 5 | L7 split scheme and entity-encoded prefix | Fable 5.2 | M | Changes how a hold is chosen; rerun every existing attribute test. |
| 6 | L7 `<style>` and `style=""` | Fable 5.2 | S | Restrict or document; if restricting, check the ops pages and scaffold templates don't interpolate into CSS. |
| 7 | L8 htmx token origin check | Haiku 5.5 | S | Browser check as in the 2026-10 validation. |

Why one model for the whole class: H1 came from a correct-looking fix to a neighbouring case.
Splitting this file across agents or models repeats that risk.

### Track B: launcher and CLI

Files: `bin/brace`, `install.sh` if the pin parse is shared, `Cli*.java`, `tests/cli`,
`AGENTS.md` (release checklist). Reviewer: Opus 5.5.

| Order | Fix | Model | Size | Notes |
|---|---|---|---|---|
| 1 | H2 floor, suffix-aware compare, single-pin check | Opus 5.5 | M | Per D5. Bash 3.2 on macOS with `set -u`: no arrays. The floor value itself changes in the 0.1.11 release commit; this commit lands the comparison and tests with the floor parameterized. |
| 2 | L11 env-name validation | Sonnet 5.5 | S | Flag and file. |
| 3 | L11 output sanitizing | Sonnet 5.5 | S | Human mode only; JSON is already safe. |
| 4 | L11 symlink-safe project writes | Sonnet 5.5 | S | Reuse the `SecretFiles` temp-file-and-move path. |

### Track C: request path

Files: `BraceHandler.java`, `Brace.java`, `Route.java`, `Request.java`, `Assets.java`,
`RateLimiter.java`, `Counters.java`, `Cache.java`, `InMemoryBackend.java`,
`PostgresBackend.java`, `TrustedProxies.java`. Reviewer: Opus 5.5, Fable 5.2 for M3.

| Order | Fix | Model | Size | Notes |
|---|---|---|---|---|
| 1 | M1 prefix boundary | Sonnet 5.5 | S | `serveStaticFile` and `Assets.resolve`. |
| 2 | M2 raw versus decoded path | Opus 5.5 | M | Must not break `my%20file.css`. |
| 3 | M3 static mapping access | Fable 5.2 | L | Per D1. Design note first: API shape, startup refusal, how the UNTRUSTED mode composes, banner and `/ops/routes` output. Scaffold and sample change with it. |
| 4 | L9 dot segments | Sonnet 5.5 | S | |
| 5 | L10 unresolved access fails closed | Sonnet 5.5 | S | Check `TestApp` and any test that builds a handler directly. |
| 6 | M4 limiter key and `form()` | Opus 5.5 | M | Per D2 and D3. Corrects two doc passages. |
| 7 | L5 control characters in keys | Opus 5.5 | M | Postgres IT. |
| 8 | L6 key cardinality and IPv6 /64 | Opus 5.5 | M | |
| 9 | L4 page cache status and key | Opus 5.5 | M | Both backends; Postgres row cap. |
| 10 | L3 forwarded headers | Opus 5.5 | M | Also fixes the last-wins header map for the WebSocket upgrade copy. |

### Track D: stores, ops and outbound

Files: `ErrorStore.java`, `Redactor.java`, `OpsKeys.java`, `OpsScope.java`, `OpsHandler.java`,
`SessionOptions.java`, `Session.java`, `Http.java`, `Mailer.java`, `FormBinder.java`,
`WebhookNotifier.java`, `JobScheduler.java`, `Sort.java`, `QueryTaint.java`, `Analytics.java`.
Reviewer: Opus 5.5.

| Order | Fix | Model | Size | Notes |
|---|---|---|---|---|
| 1 | M5 NUL in the error store | Opus 5.5 | M | Start with the Postgres IT that proves the stall; the review did not run it. If it does not reproduce, record that in the todos doc and keep the isolation and logging changes. |
| 2 | M6 value-shaped redaction | Opus 5.5 | M | Lands after Track C merges: it edits `captureRedactedHeaders` in `BraceHandler.java`. |
| 3 | M8 body timeout | Opus 5.5 | M | |
| 4 | M7 previous secrets | Sonnet 5.5 | S | Plus docs and the migration guide's rotation recipe. |
| 5 | M9 STARTTLS | Sonnet 5.5 | S | Per D4. |
| 6 | L1 scope parsing | Sonnet 5.5 | S | Per D6. |
| 7 | L2 origin pinning outside dev | Sonnet 5.5 | S | Per D6. Check the scaffold and `TestApp` still start. |
| 8 | L13 redaction at remaining sinks | Sonnet 5.5 | S | |
| 9 | L12 webhook escaping | Sonnet 5.5 | S | |
| 10 | L14 `@Email` and single recipient | Sonnet 5.5 | S | |
| 11 | L15 QueryTaint and cache class check | Sonnet 5.5 | S | Deferrable to the next release. |
| 12 | L16 analytics budget and nonce cache | Sonnet 5.5 | M | Deferrable to the next release. |

### Closeout

Run in the main session after all four tracks merge.

| Step | Model | Notes |
|---|---|---|
| Docs errors not covered by a finding | Haiku 5.5 | The list in the todos doc. |
| `BRACE-AGENTS.md` and `README.md` sweep | Sonnet 5.5 | Every sample must still start under the new static-access and limiter defaults. |
| Tool reruns | Sonnet 5.5 | SpotBugs + find-sec-bugs, Semgrep and `.github/scripts/osv-scan.sh`, base `1e28c0f` to tip, new hits triaged. Not run during the review. |
| Whole-branch review | Fable 5.2 | Three areas as in 2026-10 (request path and ops; data and output; CLI and scaffold), each claim proven with a probe. |
| Review record | main session | `docs/reviews/2026-11-security-fable-5-2.md`, and the index row in `reviews/README.md`. |

## Merge order and gate

1. **Track B, then A, then C, then D** into `security-review-2026-11`. B and A are small and
   independent; C is the widest; D's M6 depends on C.
2. **Audit each merge that conflicts, commit by commit**, confirming every `fix(security)`
   commit's change is present afterwards. The 2026-06 performance merge silently dropped
   several fixes this way.
3. **Gate before merging to `main`:**
   - `mvn verify` with Docker available. The Postgres tier fails closed and must run: M5 and L5
     exist only on Postgres.
   - `tests/cli` (Track B, and the scaffold changes from M3 and L2).
   - The whole-branch review, with its fixes landed.
   - The migration guide lists every breaking change from D1 to D6 with before/after examples.

## If shipping in stages

In order of urgency:

1. **H1 and H2.** Track A's first commit and Track B's first commit can ship alone.
2. **M1 to M4.** The static-mapping and rate-limiter gaps; all in Track C.
3. **M5 to M9.**
4. **The Lows.** L15 and L16 can wait for the next release without leaving a known bypass open.

## Breaking changes

Collected for the migration guide.

| Change | Decision | What an app does |
|---|---|---|
| A static mapping needs an access or a public marker | D1 | Add one to each `staticFiles(...)` call |
| A missing rate-limit key is limited | D2 | Opt in to the exemption where a null key means "not yet identified" |
| `req.form()` ignores query-string values on mutating methods | D3 | Send form fields in the body |
| `smtp://` requires STARTTLS | D4 | Use the plain opt-out for a local relay |
| Projects pinned below `0.1.11` need an override to use the launcher | D5 | Upgrade the pin |
| Unknown `scope:` fails startup; `opsOrigins` required outside dev | D6 | Fix the key file; set `BRACE_OPS_ORIGINS` |
| A percent-encoded unreserved character in a static path is a 404 | M2 | None expected; browsers don't send these |
| Page cache stores only 2xx | L4 | None expected |
