# Correctness Review: Opus 5 (July 2026)

## Summary

28 findings (4 High, 12 Medium, 12 Low). **All 28 resolved** — 27 fixed on this branch, and H4
(durable jobs stranded by a dead instance) fixed independently on `main` while the review was in
flight. One commit per finding or tight group, full `mvn test` green after each.

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
  round-tripped differently depending on which carrier it rode in (H3). Fixed with a path-segment
  decoder that decodes *after* the route match, plus its inverse in `Url.to` (M6), which was
  appending values raw.
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
  browsers silently discarded the session cookie (M12).
- Plus WebSocket broadcast isolation (M8), SMTP credential decoding (M10), trailing-slash routing
  (L1), multipart header injection (L6), CIDR prefix validation (L9), and redaction fixes that stop
  destroying message structure (L12) and stop leaking raw exception messages from one `Log`
  overload (L10).

**Verification discipline.** Every High and most Mediums were reproduced against a running app with
a throwaway probe before being written up, not just read. That paid off twice in the other
direction as well — see "Corrections to the review's own claims" below.

Fourth review under the [periodic model review process](README.md), and the first in this category.

- **Findings doc (canonical tracker):** [`docs/2026-07-24-correctness-review-todos.md`](../2026-07-24-correctness-review-todos.md)
- **Fix branch:** `claude/correctness-review-ey31yz` (off `main` at `ce085c0`)
- **Review baseline:** `b3409ee`; rechecked against `ce085c0` after the job-system work landed
- **Result:** 28 findings, all resolved. 27 fixed here, H4 fixed upstream.

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
registered would *not* match `/about/` (L1), and `DurableJobTest.jobLeaseRejectsMalformedIntervals`
asserted `"15d"` was invalid, which pinned the absence of the unit rather than a decision (L8). Both
now state why they changed.

**Jetty's URI compliance is a load-bearing part of Brace's threat model.** It rejects ambiguous
encodings before any framework code runs, which is why H3 was not exploitable and why a value
containing `/` or `%` cannot travel in a path segment even though `Url.to` encodes it correctly.
Worth knowing before anyone relaxes it.

## Deferred / not covered

- **Scope cut, deliberate:** the CLI (`Cli*`, `BuildCommands`, `ProjectGenerator`, `Toolchains`),
  `OpsHandler`/`OpsDashboard` rendering, `JfrProfiler`, and the Flyway migration SQL. A follow-up
  correctness pass should start there — the CLI in particular is ~2k lines that this review never
  opened.
- **M5 residual:** each `RateLimiter`'s cleanup virtual thread still runs for the life of the JVM.
  It parks 60s between sweeps over now-unreferenced maps, so it is a parked thread rather than
  growing state; retiring it needs `RateLimiter` to gain a `close()` and a lifecycle owner.
- **H4 residual (upstream's deliberate choice):** `stop()` still does not join per-job virtual
  threads, so an ordinary deploy strands up to `poolSize/2` jobs that recovery reruns up to
  `lease + sweep` later. That is a latency question, not a correctness one.
- **Known flake, pre-existing:** `DurableJobTest.claimsSizedToCapacityAndSlowJobsDontStallNewBatches`
  failed once under full-suite load and passed on every isolated run, including against a stashed
  baseline. Timing-sensitive concurrency assertion, unrelated to this branch — but it should be
  hardened before it erodes trust in the suite.

## Validation

- Full `mvn test` green after every commit (1123 tests at branch tip).
- New tests: `RouteStatsKeyTest`, `ShortCircuitStatsTest`, `PathDecodingTest`,
  `CheckboxAndVaryTest`, `StopReleasesResourcesTest`, `UrlEncodingAndRowShapeTest`,
  `CustomCacheBackendTest`, `SessionOptionsSameSiteTest`, `SmallCorrectnessFixesTest`,
  `SmallFixesUnitTest`, `RedactMessageStructureTest`, plus multipart cases added to
  `MultiValueParamsTest` and trailing-slash cases to `RouterTest`.
- **Not run:** the Testcontainers Postgres tier (`mvn verify`). The merge gate requires it, and
  several fixes touch Postgres-specific paths (`Counters`, `ErrorStore` upsert, job claim). Run it
  before merging.
