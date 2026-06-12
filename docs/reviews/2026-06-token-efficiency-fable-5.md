# Token Efficiency Review — Fable 5 (June 2026)

Second review under the [periodic model review process](README.md). Full-codebase
token-efficiency review of Brace at 0.1.7-SNAPSHOT, run with Fable 5 across five
dimensions: API shapes that force boilerplate, patterns agents actually repeat in
generated apps (measured from the ai-benchmark corpus), doc weight/staleness, CLI/tool
output verbosity, and testing/scaffold ergonomics. Formalizes and supersedes the
informal 2026-06-10 pass that was filed straight into `TODO.md`.

- **Findings doc (canonical tracker):** [`docs/2026-06-11-token-efficiency-review-todos.md`](../2026-06-11-token-efficiency-review-todos.md)
- **Fix branch:** `token-efficiency-review-2026-06`
- **Result:** 35 findings (8 High, 16 Medium, 11 Low) — **all fixed**, one commit per
  finding, full `mvn test` green after each commit (795 → 933 tests over the branch).
- **Method:** five parallel review agents for the sweep; every High verified directly
  against source before listing (the route-overload ambiguity by compile test). Fixes
  executed by parallel subagents in isolated git worktrees (two batches of 7, then a
  final pair), integrated by cherry-pick; the two invariant-heavy items (M1 session
  middleware; conflict resolution throughout) handled in the main session.

## Findings and fix commits

### High

| ID | Finding | Commit |
|---|---|---|
| H1 | Route overloads ambiguous for bare lambdas; no typed read-only methods (`getRead`, …) | `7a7fadd` |
| H2 | JSON bodies: no declarative validation; malformed JSON → 500 (`req.jsonForm`) | `9f25906` |
| H3 | BRACE-AGENTS.md split: dev core (1,324→853 lines) + BRACE-OPS.md ops reference | `4f99043` |
| H4 | Ops-keypair docs contradicted the shipped CLI | `ec63247` |
| H5 | `/ops/errors` dumped full traces to agents; summary-by-default + `/ops/errors/{id}` | `7f7eb93` |
| H6 | `/ops/status` bloat + `brace status` always exited 0 (`errors.count` never emitted) | `cd75f9c` |
| H7 | `brace test` raw JUnit passthrough → condensed non-TTY output | `e354ff3` |
| H8 | `brace compile` raw javac diagnostics → one-line, deduped, capped | `27f9b8a` |

### Medium

| ID | Finding | Commit |
|---|---|---|
| M1 | Session-aware before-middleware + `requireSession` guard | `9419f50` |
| M2 | `db.findOr404` / `db.queryOneOr404` (the 3-line preamble appeared 32× in the benchmark app) | `19aa918` |
| M3 | `Json.obj` — ordered, null-tolerant one-line JSON shapes | `a1583c4` |
| M4 | `db.queryPage` + ORDER-BY-in-fragment pinned as supported semantics | `07ca63a` |
| M5 | `db.exists(type, where, params)` + token-minimizing patterns doc section | `cdf82fd` |
| M6 | TestApp request builder, CSRF helpers, session variants, JSON assertions | `ae66a5b` |
| M7 | Scaffold extracts reusable `App.routes(Brace)` + test idiom | `c7313b2` |
| M8 | Scaffold pom: surefire pin (false-green `mvn test`) + shade packaging (broken Dockerfile) | `43931df` |
| M9 | Generated CLAUDE.md: capability gaps, stale link/PATCH, merged ops sections | `c763ef6` |
| M10 | `brace agents-md` — version-matched doc refresh from the pinned jar | `d461f9d` |
| M11 | `http.error` log lines carry an `at` first-app-frame field | `36ad777` |
| M12 | Agent-mode CLI JSON compact, not pretty-printed | `b9458f2` |
| M13 | Third-party startup log noise quieted (JUL config + slf4j-jdk14; ~80 lines → 2) | `5c633bc` |
| M14 | AGENTS.md/README stale size+test counts; core-types table completed | `814774c` |
| M15 | README benchmark numbers synced with reconciled ai-benchmark data | `2cf70fa` |
| M16 | FormBinder binds enums, LocalDate, Instant, BigDecimal (field errors, not 500s) | `6a63cdb` |

### Low

| ID | Finding | Commit |
|---|---|---|
| L1 | Phantom `req.param()` references in docs/javadoc (unified accessor deliberately rejected) | `1000c91` |
| L2 | Defaulted numeric accessors return the default on unparseable input | `1464cb3` |
| L3 | Dev-mode 404s list near-miss registered routes | `b580de8` |
| L4 | CLI nits: unknown command exits 1, `logs --limit`, scaffold suggests `brace dev` | `e5920af` |
| L5 | README API drift (PATCH, wrong reference link, non-compiling Job lambdas) | `eeeab06` + `21ad102` |
| L6 | README/BRACE-AGENTS duplication — kept the tour, added release-checklist drift sweep | `b548613` |
| L7 | BRACE-AGENTS currency sweep (413 cap, session expiry, Url.to, log levels) + open-redirect docs | `4e7b8b2` |
| L8 | Migration guide: 44-row index table + narration compression | `c765094` + `16e3843` |
| L9 | Sample relabeled as smoke app; reachable admin route; no fake version strings | `bcfb6ce` |
| L10 | Per-builder unique test H2 DB; `resetDatabase()` documented H2-only | `b4784aa` |
| L11 | Multi-value `req.queryParams(name)` / `req.formParams(name)` | `da81b75` |

## Notable spec deviations (recorded in the findings doc per item)

- **M4:** `queryPage` is a distinct method, not a `db.query` overload — the overload
  would have silently reinterpreted existing positional int params as limit/offset.
- **H7:** `System.console() == null` proved unreliable for TTY detection (JLine-backed
  JDKs); `bin/brace` now passes `[ -t 1 ]` via `-Dbrace.stdout.tty` with fallbacks.
- **M13:** the log-level override is a system property (`-Dlog.level.<logger>=`), not a
  config key — the noisy libraries boot before any app config is loaded; Jetty added to
  the quiet list (shipping a provider would otherwise have *surfaced* its INFO lines).
- **H3:** dev core landed at 853 lines, not the ≤750 target — the file had grown +205
  lines of new API reference during this very review; the remainder is dense signatures.
  Ops reference ships as `/brace/agent-ops-guide.md` in the jar, scaffolds and refreshes
  as `BRACE-OPS.md` (via `brace new` and `brace agents-md`).
- **M6:** explicit-session sends evict the cookie jar's `brace_session` (two cookies
  raced nondeterministically); plain `post(...)` deliberately never auto-injects CSRF.
- **L6/L9 decisions:** README API tour kept (drift is a process problem — release-checklist
  sweep added) rather than trimmed; sample relabeled as a minimal smoke app rather than
  expanded (a golden-path sample is separately tracked in TODO.md Tier 3).

## Validation

- `mvn test` (H2 suite) run before every commit; **933 tests green** at branch tip.
- `mvn verify` (full suite + real-Postgres Testcontainers ITs, 30 IT tests):
  **BUILD SUCCESS** at branch tip (2026-06-11).
- `tests/cli/test-distribution.sh` (shell e2e against the packaged zip) run by the
  H7/H8, M10/L4, and H3 agents in their worktrees — all green, including new
  assertions for condensed test output, `brace agents-md`, and the BRACE-OPS scaffold.
- Code-review pass over the full branch diff (process step 5): **done** — see the
  fix round below; `mvn verify` green again after it.

## Code-review fix round (2026-06-11, post-35/35)

The merge-gate review (multi-agent, adversarially verified: 29 candidates, 1 refuted)
found regressions the per-finding fixes introduced plus pre-existing issues the branch
exposed. All confirmed findings fixed, one commit per finding where independent:

| Finding | Commit |
|---|---|
| F1 `brace dev` never set `-Dbrace.mode=dev` — fresh scaffold's printed next step crashed on first run; dev-404 dead under its own command | `9c3da2c` |
| F2 legacy `post(path, params, session)` ignored the explicit session whenever the jar held a minted cookie | `a4d2811` |
| F3 concise `brace test` dropped container-level failures (`@BeforeAll`, constructor) when a method failure parsed | `dc6590d` |
| F4 condensed javac diagnostics lost the symbol detail (line 2) and deduped distinct missing symbols into one line | `3c90422` |
| F5 BeforeSession mutations silently dropped on static-file and 404 paths | `a28612b` |
| F6 `buildSession` consumed flash for any guarded route — racing polls and the `requireSession` redirect destroyed pending flash | `a745609` |
| F7+F8 no-database apps: `errors.count` never shrank (status red until restart) and stack traces were remotely unreachable — `/ops/errors{,/{id},/{id}/resolve}` now serve the in-memory Stats records with stable ids | `89f3e1f` |
| F9 `autoMode` keyed on `System.console()` instead of `stdoutIsTty()` | `10366a8` |
| F10 `-Dlog.level.<logger>=DEBUG/TRACE` was a silent no-op (ConsoleHandler stayed at INFO) | `62f6614` |
| `requireSession` without `.sessions(secret)` now warns at startup (silent guard-loop) | `da8025c` |
| Live stderr in concise `brace test`; UTF-8 scaffold doc writes; reverse-slf4j-binding + JUL-mutation migration notes | `946e1df` |
| `ErrorStore.resolve()` reuses `find()` (had drifted); `list()` pushes `since` into SQL + LIMIT 500 | `f9d9b51` |
| One URL-pair parser (`Request.parsePairs`) behind query/form/multi-value parsing | `bcfaba8` |
| Unshipped-surface cleanups: `?full=true` → `?include=detail` (one verbosity grammar), read-only typed route names GET-only (`postRead` et al. removed as footguns), profiling block computed only when requested, one in-memory error-summary builder | `50b2079` |

## Code-review fix round 2 (2026-06-12)

The `/code-review` pass over fix round 1 (7 finder angles, ~30 candidates, 1 refuted)
said F5 and F6 needed a second pass at a lower altitude, not spot patches — plus seven
smaller findings. Findings doc: `docs/2026-06-11-token-review-fix-round-2-todos.md`.
All fixed except R10 (a startup-throw decision left for Matt); one commit per finding,
`mvn test` green per commit, `mvn verify` + CLI distribution tests green at the end:

| Finding | Commit |
|---|---|
| R2 session write-back choke point — every response path (CSRF-403, thrown-404, 500 included) persists guard mutations through one `writeResult` overload | `784ad1d` |
| R1 flash consumed at View render time with cookie-borne provenance — PRG to plain-Handler pages displays flash, guard-set flash survives its own request, guards can read pending flash | `aece711` |
| R3 `Cache-Control: private` on session-cookie responses (heuristic-cacheability leak via force-cache proxies) + SECURITY.md note | `c2239fa` |
| R4 in-memory `/ops/errors?since=` filters `firstSeen` like the DB path | `dbd5e84` |
| R5 ClaudeMdGenerator emitted the removed `?full=true` | `77678cc` |
| R6 `/ops/status` errors.recent — one row shape in both deployment modes (`firstSeen` + `at` added to the DB rows) | `79c19e4` |
| R7 `/ops/errors` 500-row cap applies only to unfiltered lists; `since` windows are complete; cap documented | `10c7002` |
| R8 single-pass pair parsing, cached form map on Request, `List.copyOf` at the multi-value accessors | `1b5187a` |
| R9 `resolveError` 404s on malformed ids instead of 500ing (which re-recorded a framework error) | `b863c51` |
| Below-cut batch: CSRF `_csrf` through the shared parser (last divergent copy), resolveError/Stats/ErrorStore dedup, ProjectGenerator shares CliAgentsMd's jar-entry constants, parseFailures shared transition | `c941ccc` |

**R10 (decided 2026-06-12, post-merge on main):** `requireSession` without
`.sessions(secret)` now **throws `IllegalStateException` at `start()`** — it is a
provable infinite redirect loop, and the round-1 WARN (`da8025c`) scrolled past while
the browser symptom (ERR_TOO_MANY_REDIRECTS) pointed nowhere. A generic session-aware
`before(...)` keeps the WARN (it may be read-only or tolerant of an empty session).

## User-visible changes

Everything user-visible has a before/after entry in
[`docs/migrations/brace-0.1.6-to-0.1.7.md`](../migrations/brace-0.1.6-to-0.1.7.md),
which now opens with a 45-row machine-scannable index (change | type | action |
anchor). Four entries are breaking for scripted consumers: interior-wildcard
middleware patterns (pre-existing), `?token=` removal (pre-existing), `/ops/errors`
summaries (H5), and the `/ops/status` compact snapshot (H6).

## Follow-ups surfaced during the work (not new findings)

1. **README Quick Start contradiction:** `app.get("/", cache.wrap("5m", posts::index))`
   vs a `index(Request, Database)` controller — `Cache.wrap` only takes the
   request-only `Handler`. Needs a design decision about cached DB handlers (L5 agent,
   reported-not-fixed).
2. **Migration-guide cosmetics** (L8 agent): the OpsDashboard-escaping section says
   "Through 0.1.7" where it means 0.1.6; the open-redirect section's "Through 0.1.7,
   `Redirect.to` accepted any string" is misleading (`Redirect.to` is unchanged).
3. **FormBinder `List<String>` components** deferred from L11 — multi-value reads go
   through the new accessors for now.
4. **M11's optional dev-mode 500 body** (type + message + app-trimmed trace when
   `brace.mode=dev`) deferred — design-gated on information-disclosure review.
5. **ai-benchmark cross-repo items:** harness bugs (duplicated CLAUDE.md context
   blocks, broken route listing) already filed in that repo's TODO.md; its
   `brace-template/CLAUDE.md` should be rewritten with the new canonical idioms
   (typed routes, `findOr404`, `jsonForm`, `Json.obj`) before the planned F1–F5
   re-run — the benchmark's published numbers predate every fix on this branch.
6. **`Cache.wrap` handler shapes** (related to 1): wrapping DB-backed handlers is a
   natural ask the API can't express; candidate for the next API-ergonomics pass.
7. **v1 ops auth removal** (carried from the security review): when v1 is dropped,
   the `brace errors --full`/version-skew notes in the migration guide simplify.

## Cost/benefit anchor

The review was motivated by the ai-benchmark data: Brace beat Spring by ~31%
cumulative but **lost to Hono on F5** ($1.54 vs $1.18), the round where the
orchestrator re-reads the largest generated corpus. Findings H1/H2/M2/M3/M5 alone
collapse an estimated ~350–400 of the benchmark app's 1,292 controller lines; the
doc-split (H3) removes ~2k tokens from every session that loads the reference; and
the tool-output fixes (H5–H8, M12, M13) cut the per-iteration cost of every fix
loop. The benchmark re-run (after the template rewrite) is the measurement.
