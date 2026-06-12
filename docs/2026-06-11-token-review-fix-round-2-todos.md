# Token-efficiency review — fix round 2 TODOs (2026-06-11)

Findings from the `/code-review` pass over fix round 1 (commits `9c3da2c..ab72d85` on
`token-efficiency-review-2026-06`). Verified multi-agent review: 7 finder angles,
~30 candidates, 1 refuted (View flash ThreadLocal cross-user leak — impossible under
per-request virtual threads, verified empirically), the rest confirmed. Same process
as round 1: one commit per finding, `mvn test` green per commit, check off here.

R1/R2 are the deep ones — they say F6 and F5 need a second pass at a lower altitude,
not spot patches.

## Findings (ranked)

- [x] **R1 — Flash mechanism is at the wrong altitude (F6 follow-up).** Three confirmed
  failure modes, one root cause: consumption is tied to the handler signature
  (`invoker.needsSession()`) instead of to rendering.
  1. PRG to a DbHandler/plain-Handler view under `requireSession`: flash never displays
     (needsSession false → no setFlash), `_flash:` rides in the cookie and pops up stale
     on a later Session-taking page. Worked between M1 and F6.
  2. `consumeFlash()` has no provenance check: a pass-through guard's `session.flash(k,v)`
     set THIS request is consumed at dispatch; if the Session-taking handler redirects,
     the message dies in the request that created it. Should only consume cookie-borne
     entries (snapshot flash keys at session build, or mark entries added in-flight).
  3. Guards can't read pending flash: `Session.flash(key)` only reads `flashData`,
     populated by `consumeFlash`, which now runs after middleware.
  Direction: consume lazily at View render time (flash supplier on View; consumption
  marks session modified → existing write-back picks it up), make `Session.flash(key)`
  fall back to peeking `data.get("_flash:"+key)`, and only consume entries that arrived
  in the cookie. BraceHandler.java:297, Session.java:160.
- [x] **R2 — Session write-back needs a choke point (F5 follow-up).** Five attach sites
  now, and three paths still drop guard mutations: CSRF-403 (BraceHandler.java:320),
  the NotFoundException catch, and the 500 catch (both structurally can't attach —
  `session` is declared inside the try). Hoist the session local above the try, route
  attachment through `writeResult` (carrying session + csrfOnlySession), and decide
  explicitly whether 500s persist guard mutations (they should — the DB rollback is
  orthogonal to middleware session touches). Extend SessionWriteBackPathsTest to the
  CSRF-403 and thrown-404/500 paths.
- [x] **R3 — Static Set-Cookie + shared caches.** serveStaticFile emits zero caching
  headers, so a static 200 carrying a session Set-Cookie (lastSeen-touch middleware) is
  heuristically cacheable; force-cache-statics proxy recipes replay user A's cookie to
  everyone. Fix: emit `Cache-Control: private` (or no-store) whenever a session cookie
  is attached to a response with no explicit Cache-Control — arguably for all such
  responses, not just static. Add a SECURITY.md note about the lastSeen pattern +
  static prefixes. BraceHandler.java:266.
- [x] **R4 — `?since=` divergence.** In-memory fallback filters `lastSeen`, DB path
  filters `first_seen`. Filter `firstSeen` in `inMemoryErrors` to match.
  OpsHandler.java:613.
- [x] **R5 — ClaudeMdGenerator still emits `?full=true`** — the one emitter missed by
  the `?include=detail` convergence. ClaudeMdGenerator.java:100 (check the
  generated-CLAUDE.md test).
- [x] **R6 — `/ops/status` errors.recent shape differs by mode.** In-memory rows carry
  `firstSeen` + `at`; DB rows (recentUnresolved) don't. Pick one shape — likely give
  recentUnresolved firstSeen + stack_trace→at so both modes serve the richer shape.
  OpsHandler.java:307, ErrorStore.java:178.
- [x] **R7 — LIST_LIMIT=500 silent truncation.** errors.count is uncapped (store prunes
  at 1000); list returns 500 with no signal. Minimum: document in BRACE-OPS + migration
  guide; better: only cap when no `since` filter, or echo a truncation hint.
  ErrorStore.java:206.
- [x] **R8 — parser convergence allocation regression.** parseQuery builds multi-map +
  collapsed map per request (2 maps even with no query string); parseFormBody doubles
  the same way per formParam() call; valuesOf leaks parsePairs' mutable ArrayList
  (was List.copyOf). Single-pass last-wins mode (per-pair callback) in the shared
  parser, shared empty-map early return, List.copyOf at valuesOf. Consider caching the
  parsed form map in a Request field (body is immutable) — fixes the pre-existing
  re-parse-per-call too. Request.java:188, BraceHandler.java:705.
- [x] **R9 — resolveError 500 on malformed id.** Unguarded `longPathParam` (sibling
  errorDetail catches NFE) → POST /ops/errors/abc/resolve 500s and records a framework
  error — re-reddening the count the resolve path exists to clear. Pre-existing;
  two-line fix. OpsHandler.java:575.
- [x] **R10 (decision) — `requireSession` without `.sessions(secret)`: throw, not WARN?**
  Provably an infinite redirect loop; the WARN scrolls past and the symptom
  (ERR_TOO_MANY_REDIRECTS) points nowhere. Proposal: `requireSession` throws at
  `start()`; generic BeforeSession (possibly read-only) keeps the WARN. Matt to decide.

**Status (2026-06-12):** R1–R9 fixed (one commit each), below-the-cut batch folded in
(`c941ccc`), `mvn verify` + `tests/cli/test-distribution.sh` green, review record and
migration guide updated. R10 decided 2026-06-12: requireSession now throws at start();
generic BeforeSession keeps the WARN.

## Below the cut (confirmed, fold in where convenient)

- `parseFormParam` (CSRF `_csrf` extraction) is still a third divergent pair parser:
  raw (undecoded) key compare, first-match-wins vs parseFormBody's last-wins, decode
  failures swallowed. No bypass (passing still needs a valid token) but CSRF and
  FormBinder see different views of one body. Route through `parsePairs(body, false)`.
- `OpsHandler.resolveError`: two near-identical `wantsJson` tails (no-DB/DB) — collapse.
- `Stats.resolveError` = `findError` + remove under the same lock — one scan.
- `ErrorStore.resolve` opens a second session for the re-fetch; `mapRow` on the open
  session restores single-session without re-introducing the drift (cold path, optional).
- `ProjectGenerator` duplicates CliAgentsMd's jar-entry paths as string literals — a
  resource rename breaks `brace new` silently (the `in == null` branch is a quiet
  no-op). Share constants / `loadBundled`.
- `BuildCommands.parseFailures`: container branch is a copy-paste of the test branch's
  flush-and-reset — merge the transition.

## After the round

`mvn verify` + `tests/cli/test-distribution.sh`, update the fix-round table in
`docs/reviews/2026-06-token-efficiency-fable-5.md`, migration-guide entries for
anything user-visible (R3 cache header, R7 cap, R10 if it throws), then the branch is
ready to merge to local main.
