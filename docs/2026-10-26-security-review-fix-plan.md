# Security Review Fix Plan: 2026-10-26

This is the development plan for the findings in
[`2026-10-26-security-review-todos.md`](2026-10-26-security-review-todos.md). That file is the
tracker: it holds each finding's checkbox, fix spec, tests and effort tier. This file covers
order, dependencies, how the breaking changes reach users, and what has to be true before each
phase merges.

## Goal

The review's core question was whether each protection is **structural** or **per-handler**.
Most of the findings exist because Brace leaves a security decision to each route, and agents
writing apps will skip that decision whenever the docs don't make it unavoidable. The fixes below
favor making the safe path the default:

- an API that makes the unsafe version awkward;
- a startup check that refuses a contradictory configuration;
- a dev/test-mode check that fails loudly, because agents run tests;
- docs that teach the safe form as the canonical one.

A per-call helper on its own counts as half a fix.

## Working conventions

These follow [`reviews/README.md`](reviews/README.md) and `AGENTS.md`.

- **Branch and commits.**
  - One branch: `security-review-2026-10`.
  - One commit per finding: `fix(security): <ID> <summary>`. The body references the todos doc
    and ticks that finding's checkbox.
  - Findings explicitly paired below (M4+M5, M6+L3, M14+L4) may share a commit, with both IDs in
    the subject.
- **Every commit:**
  - passes `mvn test`;
  - adds the tests named in its todo entry;
  - updates `BRACE-AGENTS.md` and `README.md` when public API changes;
  - adds a migration-guide entry for any user-visible change, in
    `docs/migrations/brace-0.1.10-to-0.1.11.md` (the in-progress step). Nothing is staged
    across releases; see "Breaking changes and rollout".
- **Every phase ends with a merge gate:**
  1. `mvn verify` **with a Docker daemon available**. The Postgres IT tier fails closed and must
     actually run: L6 and H2's fan-out path are DB-backed.
  2. `tests/cli` for anything touching the launcher, CLI or scaffold.
  3. A `/code-review` pass over that phase's diff. The 2026-06 review's own fixes introduced
     five regressions, all in the request and response choke points that Phase 1 rewrites.
- **Phase merges.** Merge the branch to `main` at each phase boundary, rather than once at the
  end, so each diff stays reviewable and the early phases ship sooner. This differs from
  previous reviews, which merged once.
- **Re-verify each fix the way it was found.** Every High and Medium was reproduced with a
  probe, so each fix commit turns that probe into a regression test that fails before the fix
  and passes after it.

## Phases

The sizes are relative: **S** is a few hours, **M** is about a day, **L** is several days and
includes a design step.

### Phase 0: Quick wins (independent, low risk)

These close real holes with little design work. None depends on another, so they can be done in
parallel by smaller-tier agents.

| Order | ID | What | Size | Notes |
|---|---|---|---|---|
| 1 | **M12** | Bump Jackson to 2.18.11, Jetty to 12.0.36, pgjdbc to 42.7.12 | S | Goes first so every later commit tests on the patched stack. The OSV CI gate comes in Phase 5. |
| 2 | **H6** | Validate the pinned version in `bin/brace`, `install.sh`, `CliSelfUpdate`, `Toolchains` | S | Highest severity per unit of effort in the review. |
| 3 | **M3** | `csrfField` as `gg.jte.Content`; add `csrfToken` | S | **Breaking** for `@param String csrfField`. Unblocks M1 and M2. |
| 4 | **M11** | `@Min`/`@Max` exact comparison; reject NaN and Infinity | S | |
| 5 | **M9** | `bodyAs` refuses `@Entity` targets | S | **Breaking**; see the rollout table. |
| 6 | **M8** | `safeFilename()`, `saveTo(dir, name)`, `Result.fileUnder` | S | Also fix `FileUploadTest`. |
| 7 | **L1** | Hide the Jetty version; Brace error handler for Jetty-level errors | S | |
| 8 | **L2** | Redact message lines in stack traces at the sinks | S | |
| 9 | **L4** | Ops tokens must name a currently authorized key | S | |
| 10 | **L7** | Typed `getOrSet` | S | |
| 11 | **L9** | `charset=utf-8` on HTML and text results | S | |
| 12 | **L10** | `Result.html` warning, `Html.escape` | S | |
| 13 | **L11** | Return 404 for dotfiles in static mappings; dev warning on writing GETs | S | |

**Exit criteria:**

- The Phase 0 boxes are ticked.
- The jackson number-string timing check is under 50 ms.
- `tests/cli` covers a malicious pin.

### Phase 1: Authorization and the request path

This is the center of the review. H1 settles the access model, and H2, M2 and M1 build on it, so
the order matters.

| Order | ID | What | Size | Depends on |
|---|---|---|---|---|
| 1 | **H1** | Route access model: `.publicRoute()`, `defaultAccess(...)`, group-bound guards, an unguarded-routes report, an ops route wrapper | L | — |
| 2 | **H2** | Run before and session guards on WebSocket upgrades; WS routes join the access model | M | H1 (coverage computation) |
| 3 | **M2** | `.csrf(false)` + cookie-session startup check; cross-site request check without sessions; htmx CSRF wiring | M | H1 (coverage), M3 (`csrfToken`) |
| 4 | **M10** | `session.regenerate()`, session validator hook, previous secrets, `__Host-` cookie, absolute lifetime, reject cookies without `_exp` | L | M3 (token handling); may share helpers with M2 |
| 5 | **M1** | Don't page-cache renders that consumed the CSRF field or flash | S | M3 |

**Design notes:**

- **H1: compute coverage once.** At `start()`, build a `route → [guards]` map by matching every
  route pattern against every `before`/`beforeSession` pattern. A route pattern `/a/{id}` is
  covered by `/a/*`. H1's report, H2's WebSocket check and M2's startup check all read this one
  map, so build it as its own small class (`RouteAccess`) in the first H1 commit.
- **H1: pick the public-route name now.** `.publicRoute()` reads poorly; `.allowAnonymous()` is
  an alternative. Decide before the API ships (decision D1), because agents will copy it.
- **H2: guard results on a socket.** A browser WebSocket client can't follow a redirect, so a
  guard result of `302 /login` becomes a `401` handshake rejection. Any other status passes
  through as is.
- **M2: cross-site check.**
  - Prefer `Sec-Fetch-Site` when it is present: reject `cross-site`, allow `same-origin`,
    `same-site` and `none`.
  - Otherwise compare `Origin` against `ProxyHeaders.effectiveHost`, the function the WebSocket
    origin check already uses.
  - A missing `Origin` on a mutating request passes, for non-browser clients.
- **M10: session size.** The validator hook runs at most once per request, on the same lazy path
  as session decryption, so routes that never touch the session pay nothing. Keep
  `_iat`/`_ver` inside the encrypted payload, next to `_exp`.

**Exit criteria:**

- The repro probes are now tests:
  - a guarded WebSocket rejects unauthenticated upgrades;
  - `.csrf(false)` on a `SessionHandler` fails startup;
  - two-user page-cache isolation holds;
  - the CSRF token changes across login.
- `/ops/routes` shows each route's access mode.

### Phase 2: Data layer and outbound requests

| Order | ID | What | Size | Depends on |
|---|---|---|---|---|
| 1 | **H4** | `findOwnedOr404`, predicate `findOr404`, docs teach owned lookups | M | — |
| 2 | **H3** | `Sort` allowlist API, a dev/test taint check for request data in query strings, docs rewrite | L | A request-scoped holder (shares nothing with H4 beyond docs) |
| 3 | **H5** | `Http` outbound address policy (resolve once, connect to the checked IP, re-check redirects), response size cap | L | — |

**Design notes:**

- **H3: taint-check false positives.** Only match request values of 3 or more characters that
  aren't purely numeric, and only against the fragment string the caller passed (never the
  converted HQL). Run it against the existing test suite, `sample/` and `benchmark/`: zero false
  positives is the bar before it may throw in dev/test.
- **H3 + H4: one docs pass.** Both rewrite the Database section of `BRACE-AGENTS.md`, so write
  it once, after both APIs exist. Add the matching `SECURITY.md` sections: "SQL injection" and
  "Object-level authorization".
- **H5: pinning to the checked address.** The connection must go to the address that passed
  the check. With `java.net.http` that means:
  - rewrite the URI host to the checked IP literal and set the `Host` header (restricted by
    default; use `jdk.httpclient.allowRestrictedHeaders=host` only if unavoidable);
  - for https, keep SNI and hostname verification on the original name with a custom
    `SSLParameters`.
  - If that proves fragile, fall back to a check-then-connect with a resolver cache whose TTL is
    longer than the request timeout, and say in the docs that a residual DNS-rebinding window
    remains. Decide this after a spike, and record the outcome in the review record.

**Exit criteria:**

- The H2-database injection payloads fail under the taint check.
- `Sort` is used by every `?sort=` example in the docs.
- `Http` refuses 169.254.169.254, `2130706433`, `[::ffff:127.0.0.1]` and a hostname resolving to
  loopback.

### Phase 3: Output and XSS

| Order | ID | What | Size | Depends on |
|---|---|---|---|---|
| 1 | **M4** | `BraceHtmlOutput`: normalize and allowlist URL attributes, incl. `hx-get` etc.; `@Url`, `Url.safe` | L | — |
| 2 | **M5** | JS-escape `hx-on*`; htmx-config meta (`allowEval:false`) | M | M4 (same class) |
| 3 | **M6** + **L3** | Move the ops dashboard JS to `/__brace/ops.js`, make polling cookie-only (no token in the page), add `SecurityHeaders.strict()`, fix the docs' CSP | M | — |
| 4 | **M7** | Untrusted static mode (attachment + `CSP: sandbox`), `safeKey` neutralizes active extensions, `Storage` ignores the client `Content-Type` | M | — |

**Design notes:**

- **M4: one output class for both paths.** Hot-reload rendering and precompiled templates go
  through different JTE entry points, and both must receive `BraceHtmlOutput`. Add a test that
  renders the same template both ways and compares the output byte for byte.
- **M6: browser-check the dashboard.** The ops dashboard has no browser test today. Use the
  pre-installed Playwright to load `/ops/dashboard` under `SecurityHeaders.strict()` and assert
  there are no CSP violations in the console and that polling works.

**Exit criteria:**

- The URL-bypass and `hx-on` payloads render neutralized.
- The ops dashboard runs clean under the strict CSP.
- An uploaded `.html`/`.svg` is served as a sandboxed attachment.

### Phase 4: Ops protocol and message integrity

| Order | ID | What | Size | Depends on |
|---|---|---|---|---|
| 1 | **M14** | Ops auth v3 (origin in the signed message), a per-origin CLI token cache, a warning when a `.brace` URL changes; v2 deprecated | M | L4 (Phase 0) |
| 2 | **L6** | HMAC on NOTIFY payloads; per-app channel name | M | — |
| 3 | **L8** | Signed release checksums; verification in `bin/brace`, `install.sh`, `Toolchains`; https only | M | Release-process change (D5) |

**Design note (M14).** v2 is removed in the same release that adds v3; there is no deprecation
window. The server rejects v2 with a 401 that names the cause (as v1 does today), so the CLI and
the server must be upgraded together. Release the CLI first, or in the same step.

**Exit criteria:**

- A cross-origin replay of a v3 signature is rejected.
- An unsigned NOTIFY isn't delivered (Postgres IT).
- A tampered toolchain zip is refused.

### Phase 5: Scaffold, deploy and CI

This phase comes last so that `ProjectGenerator` changes once, collecting every scaffold-facing
change from the earlier phases:

- M2: `${csrfMeta}` and `csrf-htmx.js` in the layout;
- M5: the `htmx-config` meta;
- M6: `.after(SecurityHeaders.strict())`;
- H1: `defaultAccess` with the home route marked public;
- L5: `trustedProxies` from env.

| Order | ID | What | Size |
|---|---|---|---|
| 1 | **M13** | Multi-stage, non-root, digest-pinned Dockerfile with an exec-form entrypoint; `/__brace/health`; `.dockerignore`; reference `compose.yaml` (read-only, `cap_drop: ALL`, no-new-privileges, private network, Postgres unpublished); `app.host(...)` | M |
| 2 | **L5** | Warn on unconfigured proxies; scaffold reads `TRUSTED_PROXIES`; docs show the specific Traefik CIDR | S |
| 3 | **M12 (CI)** | OSV-Scanner (or dependency-check) in the merge gate, failing on HIGH/CRITICAL | S |
| 4 | — | Scaffold integration of the items listed above | M |

**Exit criteria:**

- `brace new` produces an app that passes `brace check` with no security warnings.
- That app's image runs read-only and as non-root.
- Its home page carries the strict headers.
- An htmx POST works out of the box.
- If Docker is available, `tests/cli` builds the image and runs a container health check.

### Phase 6: Closeout

1. Sweep `README.md`'s API tour against `BRACE-AGENTS.md`, per the release checklist in
   `AGENTS.md`.
2. Run the migration gate (`ai-benchmark/run-migrate.sh --from 0.1.10 --to <new>`, which
   lives outside this repo) and require `fix_attempts: 0`. H1,
   M3 and M9 are exactly the kind of change that gate exists for; extend the migrate fixture
   with a guarded route, a form and a JSON body if it lacks them.
3. Write `docs/reviews/2026-10-security-<reviewer>.md` with:
   - scope and findings summary;
   - the commit list;
   - validation status;
   - the D-decisions as made;
   - follow-ups.
4. Add the review's row to the index in `docs/reviews/README.md`.
5. Re-run the review's probes and tools (SpotBugs + find-sec-bugs, Semgrep, OSV) against the
   branch tip, and attach the deltas to the record.

## Breaking changes and rollout

**Decision (2026-10-26):** Brace's only users are its maintainers' own apps, so every change is
enforced in the next release (0.1.11). Nothing is staged behind a warning, there are no
deprecation windows, and there is no dual-read. Each user-visible change still gets a
migration-guide entry with a before/after, because the guide is how our own apps get upgraded.

| ID | Change in 0.1.11 | What an app has to do |
|---|---|---|
| H1 | `start()` **fails** on any route that has no access requirement and isn't marked public | Add `defaultAccess(...)` and mark the public routes |
| H2 | Route access requirements apply to WebSocket upgrades | Nothing, unless a socket was meant to be anonymous: mark it public |
| M2 | Startup **fails** on `.csrf(false)` for a route that uses the cookie session, unless `.csrf(false, "reason")` | Remove the opt-out or give a reason |
| M2 | Cross-site check on mutating requests, even without sessions | Nothing for same-origin apps |
| M3 | `csrfField` becomes `gg.jte.Content` | Templates declaring `@param String csrfField` change the type |
| M9 | `req.json(Entity.class)` throws | Bind to a record (`jsonForm`) and `apply()` it |
| M10 | Session cookie renamed to `__Host-brace_session`; the old name is not read | Users are logged out once on deploy |
| M10 | Cookies without `_exp` rejected | None (only pre-0.1.7 cookies) |
| M7 | `safeKey` neutralizes `.html`/`.svg`/… extensions | Nothing, unless code relied on the original extension |
| H5 | `Http` refuses non-public destinations by default | Mark calls to internal services `.trusted()` |
| M14 | Ops auth v2 rejected | Upgrade the CLI with the server |
| L9 | `text/html; charset=utf-8` | Tests asserting the exact header string change |

## Decisions

| # | Decision | Status |
|---|---|---|
| D1 | H1: hard fail at `start()`, or a warning? Name of the public marker? | **Decided:** hard fail in 0.1.11, no escape hatch. Name still open: `.publicRoute()` (recommended) or `.allowAnonymous()`. |
| D2 | H5: should `Http` default to public-only? | **Decided:** yes, in 0.1.11. Internal calls use `.trusted()`. |
| D3 | M2: fail startup on `.csrf(false)` + cookie session, and turn on the cross-site check by default? | **Decided:** yes to both, in 0.1.11. |
| D4 | M9: throw on `@Entity` binding immediately? | **Decided:** yes, in 0.1.11. |
| D5 | L8: signing scheme and key custody for release artifacts | minisign, with the key held offline by the release owner and the public key embedded in the launcher and `Toolchains`. Needs a `RELEASE.md` update. |
| D6 | M6: does `SecurityHeaders.defaults()` gain a CSP, or only the new `strict()`? | Only `strict()`. Changing `defaults()` would silently break apps with inline scripts. The scaffold uses `strict()`. |
| D7 | M13: runtime base image | Distroless nonroot Java 25, digest-pinned and refreshed by Dependabot or Renovate. Alpine if a shell is needed for debugging; document `docker debug` instead. |

## Sequencing summary

```
Phase 0 (parallel)  M12 → H6, M3, M11, M9, M8, L1, L2, L4, L7, L9, L10, L11
Phase 1             H1 → H2, M2 (needs M3) → M10 → M1 (needs M3)
Phase 2             H4, H3, H5            (independent of each other)
Phase 3             M4 → M5;  M6+L3;  M7
Phase 4             M14 (after L4), L6, L8 (after D5)
Phase 5             M13, L5, OSV gate, scaffold integration of M2/M5/M6/H1/L5
Phase 6             docs sweep, migrate gate, review record, index
```

All six Highs are fixed by the end of Phase 2. H6 lands in Phase 0 because it is the cheapest
High to fix.
