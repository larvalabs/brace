# Correctness Review: Opus 5 (July 2026)

## Summary

28 findings (4 High, 12 Medium, 12 Low). **All 28 resolved.** 22 are fixed on this branch (H3
only in part). H4 (durable jobs stranded by a dead instance) was fixed independently on `main`
while the review was in flight, and M6, M12, L2, L6 and L12 landed on `main` by other routes
(0.1.8's security review and 0.1.9's URL work) before this branch merged; see "Rebase onto
0.1.10" below. One commit per finding or tight group, full `mvn test` green after each.

First review in a new **Correctness** category — a fourth alongside Security, Token Efficiency, and
Runtime Performance. Where those ask "is it safe / cheap / fast", this asks "is it *right*": wrong
results, silently dropped data, unbounded growth, work lost rather than retried, and APIs that
contradict their own documentation.

The substantive changes:

- **Observability was quietly broken in two ways that cancelled each other's evidence.** Per-route
  stats were keyed by the concrete URL rather than the route pattern, so the never-reset `routes`
  map grew one entry per distinct URL ever requested (H1) — and separately, every response that
  short-circuited before the handler (rate-limiter 429s, CSRF 403s, 413s, static files, unmatched
  404s) was never recorded at all (H2). H1 is a regression: the runtime-performance review added
  `recordRequestPattern` for exactly this and it was never wired into `BraceHandler`.
- **Path parameters were never URL-decoded** while query and form params were, so the same value
  round-tripped differently depending on which carrier it rode in (H3), and `Url.to` appended
  values raw (M6). Both halves shipped on `main` in 0.1.9 first; what this branch adds is the
  static-file half of H3 (encoded filenames 404'd).
- **Form binding lost data in two shapes**: repeated multipart fields collapsed to their last value
  (M1), and a checked HTML checkbox — which submits `name=on` — bound to `false` (M2).
- **`Brace.stop()` did not release what `start()` took**: the HikariCP pool and Hibernate
  SessionFactory stayed open (M4), and the rate limiter's process-global statics kept pointing at
  the stopped app (M5).
- **Type and contract lies**: `sqlQuery`/`hql` declared `List<Object[]>` and returned bare scalars
  for single-column selects (M7), `Cache.getOrSet` cast the public SPI to its built-in
  implementation (M9), and `Storage.uriEncodePath` used form encoding where SigV4 requires RFC 3986
  (L5).
- **Scheduling and cookies**: `daily(...)` drifted an hour at every DST transition and could lose a
  day outright to a UTC-day dedupe slot (M11); `sameSite("None")` did not imply `Secure`, so
  browsers silently discarded the session cookie (M12, fixed on `main` in 0.1.8).
- Plus WebSocket broadcast isolation (M8), SMTP credential decoding (M10), trailing-slash routing
  (L1), CIDR prefix validation (L9), and a redaction fix that stops leaking raw exception messages
  from one `Log` overload (L10). Multipart header injection (L6) and redaction destroying message
  structure (L12) were fixed on `main` first.

**Verification discipline.** Every High and most Mediums were reproduced against a running app with
a throwaway probe before being written up, not just read. That paid off twice in the other
direction as well — see "Corrections to the review's own claims" below.

Fourth review under the [periodic model review process](README.md), and the first in this category.

- **Findings doc (canonical tracker):** [`docs/2026-07-24-correctness-review-todos.md`](../2026-07-24-correctness-review-todos.md)
- **Fix branch:** `claude/correctness-review-ey31yz` (off `main` at `ce085c0`), rebased onto the
  0.1.10 development line as `0.1.10/correctness`
- **Review baseline:** `b3409ee`; rechecked against `ce085c0` after the job-system work landed
- **Result:** 28 findings, all resolved. 22 fixed here; H4, M6, M12, L2, L6 and L12 fixed upstream.

Fix commits are `fix(correctness): <ID> …`; documentation-only resolutions are `docs(correctness): …`.

## Corrections to the review's own claims

Two findings were written up with more alarming framing than the code deserved, and the
implementation work is what surfaced it. Both corrections are recorded in the findings doc next to
the original text rather than quietly edited out.

**H3 was a data-correctness bug, not a live traversal hole.** The write-up implied encoded traversal
(`%2e%2e`, `%2F`) could reach the static-file `..` check. It cannot: Jetty's default `UriCompliance`
rejects `%2F`, `%25`, `%2e` and malformed escapes with a 400 before the handler runs. This surfaced
when four traversal tests came back 400 instead of the expected 404. The decode-after-match ordering
is still the right design — compliance is configurable and `Route.match` is public API — but the
severity claim was wrong.

**H1's spec would have made the request log worse.** It said to key the log by route pattern too,
"so `/ops/logs` and `/ops/routes` agree". They should not agree: the routes table is a bounded
latency aggregate, the log is a stream where the concrete URL is the entire diagnostic value.
Knowing that `GET /users/{id}` 404'd is useless without knowing which id. Only stats changed.

A third correction is arithmetic: the findings doc's own summary said 25 findings with 9 Lows. There
are 28, with 12.

## Notes worth carrying forward

**A fix that reverts silently will revert again.** H1 was already fixed once, by the
runtime-performance review's H7, and reverted with nothing in the suite noticing — the method was
added but never called. The regression test is therefore the deliverable, not the fix. Same shape as
`FrameworkMigrationsFrozenTest`: when an invariant has been broken once by ordinary editing, encode
it as a test rather than a comment.

**Making a type honest flushes out code that adapted to the lie.** M7 broke `TestApp.resetDatabase`
immediately: it had cast a single-column result to `List<Object>` and called `toString()` per
element — which only worked *because* the declared type was wrong. Good evidence the finding was
real rather than theoretical, and a reminder to run the full suite (not just targeted tests) after a
signature-semantics change.

**Tests can pin bugs.** Two existing tests asserted the buggy behavior and had to be updated with
reasoning: `RouterTest.trailingSlashPatternNormalized` asserted that a router with `/about/`
registered would *not* match `/about/` (L1), and a job-interval test asserted `"15d"` was invalid,
which pinned the absence of the unit rather than a decision (L8). On this branch that was
`DurableJobTest.jobLeaseRejectsMalformedIntervals`; `jobLease` was replaced on `main` by
`jobTimeout`, and the same assertion moved to `JobOwnershipTest.jobTimeoutAcceptsIntervalStrings`.
Both now state why they changed.

**Jetty's URI compliance is a load-bearing part of Brace's threat model.** It rejects ambiguous
encodings before any framework code runs, which is why H3 was not exploitable and why a value
containing `/` or `%` cannot travel in a path segment (0.1.9's `Url.to` refuses such values and
points at `Url.query`). Worth knowing before anyone relaxes it.

## Deferred / not covered

- **Scope cut, deliberate:** the CLI (`Cli*`, `BuildCommands`, `ProjectGenerator`, `Toolchains`),
  `OpsHandler`/`OpsDashboard` rendering, `JfrProfiler`, and the Flyway migration SQL. A follow-up
  correctness pass should start there — the CLI in particular is ~2k lines that this review never
  opened.
- **M5 residual:** each `RateLimiter`'s cleanup virtual thread still runs for the life of the JVM.
  It parks 60s between sweeps over now-unreferenced maps, so it is a parked thread rather than
  growing state; retiring it needs `RateLimiter` to gain a `close()` and a lifecycle owner.
- **H4 residual, since resolved upstream:** at review time `stop()` did not join per-job virtual
  threads, so a deploy stranded running jobs until recovery. `main`'s heartbeat-owned claims and
  graceful shutdown (`2361e67`, 0.1.8) now give running jobs `jobShutdownTimeout` to finish and
  return the rest to the queue with the attempt refunded.
- **Known flake, pre-existing:** `DurableJobTest.claimsSizedToCapacityAndSlowJobsDontStallNewBatches`
  failed once under full-suite load and passed on every isolated run, including against a stashed
  baseline. Timing-sensitive concurrency assertion, unrelated to this branch — but it should be
  hardened before it erodes trust in the suite.

## Rebase onto 0.1.10

The branch sat unmerged while 0.1.8, 0.1.9 and the 2026-07 security review landed, then was
rebased onto `main` at 0.1.10-SNAPSHOT (2026-10-02). Where `main` already had the behavior,
`main`'s version was kept and the branch's commit dropped or shrunk; the findings doc records
each as "Resolved upstream":

- **M6** (`Url.to` encoding): `main`'s 0.1.9 version is stricter (refuses `/`, `%`, `.`, `..` with
  a pointer to `Url.query`) and replaced this branch's encoder. The commit is now M7 only.
- **H3**: path-parameter decoding shipped in 0.1.9 (`Route.decodeSegment`); the branch's duplicate
  decoder was dropped and only the static-file half kept, built on `main`'s decoder.
- **M12** (`sameSite("None")`), **L2** (dead secret check), **L6** (multipart escaping) and
  **L12** (redaction splicing): fixed on `main` already; the branch's versions and their tests
  were dropped.

Three things changed shape rather than just merging:

- **H1/H2** were rebuilt on `main`'s response choke point (`respond` / `respondToError` /
  `send`, from the security review's after-middleware work). Recording lives in `send()`, after
  the after-middleware chain, so the recorded status is the one sent.
- **L1 needed a security follow-up.** Matching `/admin/` to the `/admin` route meant an exact-path
  guard on `/admin` (middleware `PathPattern` matches `req.path()`) no longer ran for a request
  the router now served: an auth bypass. `BraceHandler` now treats a fallback-matched request as
  its canonical path from routing on, and a regression test pins the guard case.
- **M4** (stop closes the factory) broke two of `main`'s tests that share one factory across app
  instances, `JobOwnershipTest` and `WebSocketFanoutPostgresIT` (the latter only visible under
  `mvn verify`); those apps now pass `.ownsDatabase(false)`, which is exactly the case the opt-out
  exists for. Apps with the same shape need the same change, so it has a breaking entry in the
  0.1.9 -> 0.1.10 migration guide.

## Validation

- Before the rebase: full `mvn test` green after every commit (1123 tests at branch tip).
- After the rebase onto 0.1.10 (2026-10-02): every commit compiles; at the tip, `mvn test` is
  green (1268 tests) and `mvn verify` is green (1268 unit + 37 Postgres ITs). This closes the
  merge-gate gap the original branch left open.
- New tests: `RouteStatsKeyTest`, `ShortCircuitStatsTest`, `PathDecodingTest`,
  `CheckboxAndVaryTest`, `StopReleasesResourcesTest`, `RowShapeTest` (was
  `UrlEncodingAndRowShapeTest`; the M6 half was dropped), `CustomCacheBackendTest`,
  `SmallCorrectnessFixesTest`, `SmallFixesUnitTest`, plus multipart cases added to
  `MultiValueParamsTest` and trailing-slash cases to `RouterTest`. `SessionOptionsSameSiteTest`
  and `RedactMessageStructureTest` were dropped with M12 and L12.
