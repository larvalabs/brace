# Security Review: Opus 5 (July 2026)

## Summary

Full-codebase security review of Brace at **0.1.8-SNAPSHOT**, run with Opus 5 across the
five standing dimensions (crypto/sessions, HTTP lifecycle, ops surface, database/injection,
files/CLI). Second review under the [periodic model review process](README.md); the first was
[Fable 5, June 2026](2026-06-security-fable-5.md).

**17 findings (2 High, 8 Medium, 7 Low). All fixed**, one commit per finding.

The substantive changes:

- **Static-file containment was bypassable by symlink** (H1). The `normalize()` +
  `startsWith` check is lexical and does not resolve links, while the read follows them — a
  symlink under a served directory served files from outside the web root. Containment now
  re-checks against link-resolved paths.
- **Session cookies had no `Secure` attribute** for every app built the documented way (H2).
  Brace serves cleartext and expects TLS at a proxy, so "off unless configured" was the
  permanent state everywhere. The attribute is now resolved per request — on for every
  non-loopback request, forced on by a trusted proxy's `X-Forwarded-Proto: https` — which
  makes production secure by default without breaking `http://localhost`.
- **Security headers reached only the handler path** (M1): static files, 404s, 500s, CSRF
  403s and 413s left undecorated. After-middleware now runs at the response choke point.
- **Request bodies were buffered before before-middleware** (M2), so rate limiters and auth
  guards could not shed a request until up to `maxUploadSize` was already on the heap. The
  body is read lazily, after the guards.
- **WebSocket upgrades were not `Origin`-checked** (M3) while decrypting the session cookie
  into the handler — cross-site WebSocket hijacking, held off only by a `SameSite` default
  an app can turn off.
- **Ops responses were cacheable** (M4): only the exchange endpoint said `no-store`, while
  `/ops/dashboard` embeds a live bearer token and authenticates by cookie, which no RFC 9111
  rule keeps out of a shared cache.
- **Ops auth v1 removed** (M5) — its one-release deprecation window, opened in 0.1.7, had
  elapsed.
- **Filenames were interpolated raw into `Content-Disposition`**, on the response side (M6)
  and in outbound multipart bodies (M7), and **a server-supplied token reached `cmd /c
  start`** on Windows (M8).
- Smaller hardening: cookie name/value validation (L1), the ops cookie scoped to `/ops`
  (L2), a dead weak-secret clause removed (L3), an explicit charset in the CSRF comparison
  (L4), traversal rejection in storage keys (L5), CSRF-field escaping (L6), and a floor on
  the ops token TTL (L7).

- **Findings doc (canonical tracker):** [`docs/2026-07-24-security-review-todos.md`](../2026-07-24-security-review-todos.md)
- **Fix branch:** `claude/framework-security-review-pq5a11`
- **Result:** 17 findings, **all fixed**, full `mvn test` green (1008 → 1073 tests over the branch).

Every High and Medium was verified against a running server — raw-socket probes and
integration probes — rather than inferred from reading. The suspicions that did **not**
survive that check are recorded under "Checked and cleared" in the findings doc, so the next
reviewer doesn't spend the effort again. The most useful of those: **Jetty 12 folds CR/LF in
header values to spaces**, which is why M6 and L1 are parameter/attribute injection rather
than response splitting.

## Findings and fix commits

### High

| ID | Finding | Commit |
|---|---|---|
| H1 | Static-file serving follows symlinks out of the served directory | `7e8b957` |
| H2 | Session cookies are not `Secure` by default | `83b8879` |

### Medium

| ID | Finding | Commit |
|---|---|---|
| M1 | `SecurityHeaders` never reaches static files, 404s, 500s, CSRF 403s, 413s | `7bc2da5` |
| M2 | Request bodies buffered before before-middleware runs | `15c4801` |
| M3 | WebSocket upgrades are not `Origin`-checked | `ea364af` |
| M4 | Ops responses carry no `Cache-Control: no-store` | `10ad780` |
| M5 | Ops auth protocol v1 still accepted | `f806ca7` |
| M6 | `Result.download` does not quote the filename | `2dfb805` |
| M7 | `Http.Multipart` interpolates part name/filename unescaped | `e357d04` |
| M8 | `brace ops dashboard` passes a server-supplied token through `cmd /c start` | `dd619ec` |

### Low

| ID | Finding | Commit |
|---|---|---|
| L1 | `Result.cookie` does not validate name or value | `816a0a9` |
| L2 | Ops session cookie scoped to `Path=/` | `816a0a9` |
| L3 | Dead branch in the weak-secret check | `b5dbe58` |
| L4 | `Csrf.validateToken` uses the platform default charset | `b5dbe58` |
| L5 | `Storage.put`/`delete` accept `..` segments in keys | `b5dbe58` |
| L6 | `Csrf.hiddenField` does not HTML-escape the token | `b5dbe58` |
| L7 | `OpsToken.create` accepts `ttlSeconds <= 0` | `b5dbe58` |

Two non-finding commits on the branch:

| Commit | What |
|---|---|
| `19e6ea1` | De-flaked `WebSocketTest.roomBroadcast` and `DurableJobTest.claimsSizedToCapacity…` — see "Test flakiness" below |
| `16a9d06` | `ResultTest.downloadSetsContentDispositionHeader` still pinned the pre-M6 header, so M6's commit left the suite red for one commit; corrected in a follow-up rather than rewritten into history |

## Design notes worth keeping

**H2 — why per-request rather than a mode flag.** The first attempt gated the `Secure`
default on `brace.mode != dev`. Two things killed it: the scaffold's own Dockerfile runs
`java -jar app.jar` with no `-Dbrace.mode`, so the primary production path would have
defaulted to *insecure*; and it broke 9 tests across 7 classes, i.e. it would have broken
every user's `http://localhost` test suite too. Resolving from the request's `Host` (plus a
trusted `X-Forwarded-Proto`) is correct in both environments with no configuration, and
landed with zero changes to existing integration tests — only the two assertions that pinned
the old default. An attacker cannot use `Host` to strip `Secure` from a victim's cookie: the
header they control is on their own request, and the `Set-Cookie` it shapes returns to them.

**M1 — error-path re-entrancy.** After-middleware now runs inside `writeResult`, which the
500 handler also calls. A throwing middleware on that path would recurse back into the catch
block, so the two catch paths decorate through `applyAfterQuietly`, which logs and continues.
Every other path keeps the existing behavior of surfacing an after-middleware exception as a
500.

**M2 — where the 413 lives.** Making the body lazy could have moved the 413 to an arbitrary
point (whenever a handler first touched `req.body()`). Resolution is instead *forced* right
after the guards and before CSRF extraction, so the 413 keeps its existing position in the
lifecycle and nothing downstream ever sees a half-set-up request. The one visible change: a
request rejected by before-middleware now returns the guard's status rather than 413.

## Validation

- `mvn test` (H2 suite) green at branch tip: **1073 tests, 0 failures**, and run before each
  commit. Baseline at branch point was 1008.
- **The real-Postgres tier of the merge gate did not run here.** `mvn verify` reports BUILD
  SUCCESS, but every `*PostgresIT` reports `Tests run: 0`: this container has the `docker`
  binary but no daemon, so `PostgresTestBase` skips via JUnit assumptions exactly as designed.
  **`mvn verify` still needs to be run somewhere with Docker before merge.** Risk is low but
  not zero — none of the 11 ITs touch ops auth, response headers, cookies, uploads, or static
  files (they are DB-layer tests: counters, durable jobs, cache backend, error store,
  scheduler, skip-locked claims, and WebSocket fanout over the message bus), and the WS
  fanout IT connects with a Java client that sends no `Origin`, which M3 allows.
- A `/code-review` pass over the branch diff has **not** been run. The 2026-06 review found
  five regressions that its own fixes introduced (CR1–CR5), so this is worth doing before
  merge — M1 and M2 both touch the request/response choke points those regressions clustered
  around.

## Test flakiness found along the way

Two tests failed intermittently under the branch's larger suite (they pass 4/4 in isolation,
and the baseline was 3/3 green). Both are test-side races, not product bugs, and neither
touches the changed code:

- `WebSocketTest.roomBroadcast` — `connect()` returns when the *client* handshake completes,
  but the server-side `onConnect` and its `ws.join("lobby")` run afterwards, so the broadcast
  could fan out to a room that wasn't fully populated.
- `DurableJobTest.claimsSizedToCapacityAndSlowJobsDontStallNewBatches` — `runCount` counts
  `fast1` and `fast2`, but `fast1` belongs to the first batch, whose threads are deliberately
  not joined, so its increment could still be in flight.

Both fixed in `19e6ea1`; suite green 3/3 afterwards. Worth noting that the added load
*surfaced* these — a suite that only passes at a particular size is a latent problem.

## User-visible changes

All documented with before/after examples in
[`docs/migrations/brace-0.1.7-to-0.1.8.md`](../migrations/brace-0.1.7-to-0.1.8.md), created by
this review (0.1.8 had no guide yet). Two breaking entries: the `Secure` default and the ops
v1 removal. `docs/SECURITY.md` gained Static Files and WebSockets sections and a rewritten
`Secure`-attribute discussion; `BRACE-AGENTS.md` and `README.md` were updated for
`wsAllowedOrigins`, the `Result.cookie` path overload, and the `Secure` default.

## Follow-ups (not in the original findings)

1. **Run `mvn verify` with Docker available**, and a `/code-review` pass over the branch diff,
   before merging — see Validation.
2. **The migration gate has not been run.** Per AGENTS.md, `ai-benchmark/run-migrate.sh --from
   0.1.7 --to 0.1.8-SNAPSHOT` must pass with `fix_attempts: 0` before tagging. The `Secure`
   default is exactly the kind of change that gate exists to catch: an agent upgrading an app
   whose tests hit a non-loopback host would need the guide's opt-out section to be findable.
3. **The migrate fixture may need extending** for the `Secure` change and the WebSocket
   `Origin` check — neither is plain CRUD, which is the case AGENTS.md flags as needing
   fixture work.
4. **Session expiry is sliding, with no absolute cap.** `_exp` is re-stamped on every write,
   so a continuously-used (or continuously-replayed) session never ages out. Deliberate, but
   an absolute-lifetime option would be a reasonable addition.
5. **Multipart overflow still surfaces as 500, not 413** — carried over from 2026-06; there is
   still no single clean exception type to catch from Jetty's MultiPart parser.
6. **Ops v2 replay suppression remains per-instance** (carried over): a captured v2 request is
   still replayable against a *different* instance inside the ±30 s window. Documented in
   SECURITY.md; fixing it needs fleet-shared state that ops deliberately does not assume.
7. **`Json.of` entity warning is still shallow** (carried over from 2026-06): it misses Map
   wrappers, arrays, and nested/DTO-field entities.
8. **Cosmetic, carried over:** `ClaudeMdGenerator.java:17` still links `github.com/matth/brace`
   rather than `larvalabs/brace`.
