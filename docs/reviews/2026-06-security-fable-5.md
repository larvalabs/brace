# Security Review — Fable 5 (June 2026)

First review under the [periodic model review process](README.md). Full-codebase
security review of Brace at 0.1.7-SNAPSHOT, run with Fable 5 across five dimensions:
crypto/sessions, HTTP lifecycle, ops surface, database/injection, files/CLI.

- **Findings doc (canonical tracker):** [`docs/2026-06-09-security-review-todos.md`](../2026-06-09-security-review-todos.md)
- **Fix branch:** `security-review-2026-06`
- **Result:** 25 findings (4 High, 11 Medium, 10 Low) — **all fixed**, one commit per
  finding, full `mvn test` green after each commit (724 → 765 tests over the branch).

## Findings and fix commits

### High

| ID | Finding | Commit |
|---|---|---|
| H1 | Ops dashboard mints CONTROL tokens for READ callers | `9da6482` |
| H2 | X-Forwarded-For trusts the leftmost (spoofable) entry | `d3a70fe` |
| H3 | Unbounded request-body read (OOM DoS) — 413 cap | `5fd2c56` |
| H4 | Scaffold ships a placeholder session secret | `b5ab8a8` |

### Medium

| ID | Finding | Commit |
|---|---|---|
| M1 | PBKDF2 re-derived per request — key cache | `81d2f3b` |
| M2 | No server-side session expiry — `_exp` in encrypted payload | `94ab761` |
| M3 | Ops auth v2: bind signature to key + nonce, suppress replay | `c720d7a` |
| M4 | `?token=` URL auth fallback dropped | `ec27e2a` |
| M5 | CSRF gaps: PATCH method, doc mismatch, token persistence | `86ba072` |
| M6 | Session cookie attached after after-middleware runs | `9251b9f` |
| M7 | Rate-limit key cap + local fallback on DB failure | `f0aca28` |
| M8 | `/prefix/*` middleware covers bare prefix; reject interior wildcards | `b79450c` |
| M9 | Warn when `Json.of` serializes a JPA entity | `1813014` |
| M10 | Value-shaped redaction for error paths and exception messages | `72b6f1e` |
| M11 | Ops keys / CLI tokens written with owner-only permissions | `ecb7b64` |

### Low

| ID | Finding | Commit |
|---|---|---|
| L1 | `Redirect.toLocal()` safe-redirect helper | `4e79f28` |
| L2 | `OpsDashboard.esc()` escapes single quotes | `f65ebc6` |
| L3 | `?`-converter: dollar-quoted strings, quoted identifiers, E-strings | `e697cfa` |
| L4 | Validate field identifiers in Database query helpers | `dbd215a` |
| L5 | Validate job/cache class names before loading | `43d334a` |
| L6 | Percent-encode page-cache key query parameters | `a2cf042` |
| L7 | `X-Content-Type-Options: nosniff` on static responses | `05875e5` |
| L8 | Validate project names in `brace new` | `0c4b753` |
| L9 | `Passwords.dummyCheck()` for enumeration-timing mitigation | `375729a` |
| L10 | TrustedProxies dual-stack representation mismatch (doc-only) | folded into `0c4b753` (SECURITY.md coverage came with H2) |

Commit-history cosmetics (harmless; fix only if rewriting history before merge):
`dbd215a`'s body contains a leftover "(tick L4 checkbox…)" instruction line, and
`0c4b753` (L8) bundles the checkbox ticks and migration-guide entries for L9/L10.

## Validation

- `mvn test` (H2 suite) run before every commit; 765 tests green at branch tip.
- `mvn verify` run at branch tip (2026-06-11): **BUILD SUCCESS** — 765 H2 tests plus
  30 real-Postgres Testcontainers ITs, including `CountersPostgresIT` and
  `RateLimiterPostgresIT`, which cover the real-Postgres paths M2/M3/M7 touched.
- A `/code-review` pass over the whole branch diff is recommended before merge.

## User-visible changes

Everything user-visible is documented with before/after examples in
[`docs/migrations/brace-0.1.6-to-0.1.7.md`](../migrations/brace-0.1.6-to-0.1.7.md),
and `docs/SECURITY.md` was updated throughout (XFF/trusted-proxy guidance, dual-stack
note, entity-serialization guidance, redaction trade-offs, nonce wording).

## Follow-ups surfaced during the work (not in the original findings)

1. **0.1.7 migration guide gap (pre-existing):** the ops token *scoping feature itself*
   (shipped this cycle in `eb93e70`) was never documented in
   `docs/migrations/brace-0.1.6-to-0.1.7.md`. Add before tagging 0.1.7.
2. **v1 ops auth removal (next release):** M3 accepts v1 with a deprecation warning
   (v1 shipped in 0.1.6). `OpsScopeIntegrationTest`, `ErrorStoreTest`, `OpsCsrfTest`,
   `RegressionIntegrationTest`, `OpsSharedSecretTest` still authenticate v1-style and
   double as v1 coverage; migrate them to v2 when v1 is dropped.
   `OpsIntegrationTest.authV1StillAcceptedThisRelease` has a comment to flip it to 401.
3. **Multiple X-Forwarded-For header instances:** the request header map is
   last-one-wins (BraceHandler builds a single-value map), not comma-joined. Last-wins
   keeps the proxy-appended header, but the comment in `Request.ip()` overstates the
   guarantee — worth a look.
4. **Multipart overflow still surfaces as 500, not 413** — no clean single exception
   type to catch from Jetty's MultiPart parser. Cosmetic/correctness follow-up.
5. **Stack traces in error records are deliberately unredacted** (M10 trade-off:
   primary diagnostic signal; only exposed via READ-gated `/ops/errors` JSON). The
   rendered message/route/path fields are scrubbed. Documented in SECURITY.md.
6. **M8 doc sweep may be incomplete:** README.md / BRACE-AGENTS.md may still describe
   the old `/*` middleware semantics — grep for `/*"` pattern docs.
7. **Cosmetic:** `ClaudeMdGenerator.java:17` links `github.com/matth/brace`; everything
   else uses `larvalabs/brace`.
8. **`Counters` is no longer `final`** (M7 made it subclassable for a test stub) —
   fine, but flag if an API-surface review cares.

## Design-intent note

H1's "was this intentional?" question was settled against the design docs (see the
**Design intent confirmed** note in the findings doc): read keys must never mutate;
the sanctioned pattern for agents needing control actions is a separate narrow
CONTROL key, never an upgraded read key.
