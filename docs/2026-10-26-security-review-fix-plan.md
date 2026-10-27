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
| 1 | **H1** | Route access model (see [H1 design](#h1-design-the-route-access-model)): `Access`, `defaultAccess`, group and route requirements, `.publicRoute()`, startup check, `/ops` via `Access.ops` | L | — |
| 2 | **H2** | WebSocket routes join the access model; requirements run on upgrade | M | H1 |
| 3 | **M2** | `.csrf(false)` + cookie-session startup check; cross-site request check without sessions; htmx CSRF wiring | M | H1 (resolved requirements), M3 (`csrfToken`) |
| 4 | **M10** | `session.regenerate()`, session validator hook, previous secrets, `__Host-` cookie, absolute lifetime, reject cookies without `_exp` | L | M3 (token handling); may share helpers with M2 |
| 5 | **M1** | Don't page-cache renders that consumed the CSRF field or flash | S | M3 |

**Design notes:**

- **H1 and H2:** the full design (API, resolution rules, lifecycle position, rejections,
  WebSockets, `/ops`, tests and commit breakdown) is in
  [H1 design: the route access model](#h1-design-the-route-access-model) below.
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

- Every route in the framework's own `/ops` table and in `sample/` resolves to an access
  requirement or `public`; `start()` fails on an unmarked route.
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
  - **Outcome (Phase 2 spike):** fallback chosen. The `Host` override needs
    `jdk.httpclient.allowRestrictedHeaders`, which the JDK reads once when its HTTP client classes
    load, so it silently doesn't apply if anything used `java.net.http` first; without it an
    IP-literal URI sends `Host: <ip>` and breaks virtual-hosted destinations. SNI on the original
    name did work, but needs one client per hostname. A JVM-wide `InetAddressResolverProvider` was
    rejected as implicit global wiring. Shipped: check-then-connect through the JVM address cache
    (30 s default), a second check when the response headers arrive, refusal of non-canonical
    numeric hosts, and a warning when the DNS cache TTL is under 10 s. The residual window is
    documented in `docs/SECURITY.md` ("DNS rebinding: what remains").

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
| 1 | **M14** | Ops auth v3 (origin in the signed message), a per-origin CLI token cache, a warning when a `.brace` URL changes; v2 removed | M | L4 (Phase 0) |
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

## H1 design: the route access model

Agreed 2026-10-26. This replaces path-pattern auth guards. H2, M2 and the `/ops` rework build on
it, and the scaffold (Phase 5) and docs adopt it.

### The rule

Every route carries a list of **access requirements**, attached to the route object itself, not
to a path string. `start()` refuses to run if any route has no requirement and isn't explicitly
public. This applies to HTTP routes, WebSocket routes and the framework's own `/ops` routes.

### API

```java
// 1. App-wide default: applies to every route that doesn't opt out
app.defaultAccess(Access.session("userId", "/login"));

// 2. Group: applies to every route the group registers, on top of the default
app.group("/admin", Access.check((req, s) -> "admin".equals(s.get("role")) ? null : Result.forbidden()),
    admin -> {
        admin.getDb("/users", adminCtl::users);
        admin.postDb("/users/{id}/ban", adminCtl::ban);
    });

// 3. Single route, on top of default and group
app.postDb("/billing/refund", billing::refund).access(Access.check(Billing::canRefund));

// Opting out of the default: explicit and greppable
app.get("/", home::index).publicRoute();
app.post("/login", auth::login).publicRoute();

// WebSockets use the same config
app.ws("/live", Live::new);                 // gets the default
app.ws("/status", Status::new).publicRoute();
```

`Access` is a guard: it returns `null` to allow the request, or a `Result` to reject it. It also
declares whether it reads the session. It is a small sealed type in a new `Access.java`:

| Factory | Behavior | Uses session |
|---|---|---|
| `Access.session(key, loginUrl)` | Allow if the session has `key`, else reject (see "Rejections") | yes |
| `Access.check((req, session) -> Result)` | Custom logic; `null` allows | yes |
| `Access.checkRequest(req -> Result)` | Custom logic without a session (e.g. a bearer-token check). A separate name rather than an overload of `check`, so method references never need a cast, following the `getDb`/`getSession` precedent. | no |
| `Access.insteadOfDefault(requirement)` | Added during implementation: `requirement` replaces the default on the route or group it's on. Without it, a bearer-token API or signed webhook in a session-login app had no way to drop the session default while staying guarded (`.publicRoute()` can't be combined with `.access(...)`). | as `requirement` |
| `Access.none()` | Only valid in `defaultAccess(...)`: an explicit statement that the app has no login. It is logged at startup in the access table. | no |
| `Access.ops(OpsScope)` | Internal; used for the framework's `/ops` routes | no |

### Resolution rules

1. **Requirements stack (AND).** A route's effective list is, in order: the default, then its
   groups from outer to inner, then its own `.access(...)` calls. A group or route can add
   requirements but can never remove the default. The only way to remove it is
   `.publicRoute()`.
2. **`.publicRoute()` removes only the default.** It is a startup error to combine it with
   `.access(...)`, or to use it inside a group that has a requirement. A public page goes
   outside the restricted group.
3. **Resolved once, at `start()`.** The effective list is computed and stored on `Route`, so
   requests do no pattern matching for access. Trailing-slash variants, encoded paths and the
   HEAD→GET fallback all resolve to the same `Route` object, so they get the same requirements.
   This removes the class of bugs where a guard's path pattern and a route's path disagree.
4. **The startup check.** After resolution, `start()` (and so `Brace.test()`) throws
   `IllegalStateException` listing every route whose list is empty and that isn't public:

   ```
   IllegalStateException: 2 routes have no access requirement and are not marked public:
     GET  /reports/export     (controllers.ReportsController::export)
     WS   /live
   Add app.defaultAccess(...), put them in a group with access, or mark them .publicRoute().
   ```

   It also throws when a session-reading requirement exists but `.sessions(...)` isn't
   configured. That replaces today's `requireSession` misconfiguration check.
5. **Plain middleware is not access.** `before(...)` and the session-aware
   `before(pattern, (req, session) -> …)` stay for rate limiting, maintenance mode, flash and
   last-seen touches, but they never count toward the check. That way a global rate limiter
   can't accidentally "cover" every route.
6. **`requireSession(pattern, …)` is removed.** Its job moves to `defaultAccess` and groups.
   The migration guide shows the rewrite.

### Where it runs in a request

```
match route
  → plain before()                       rate limiters can still shed load first
  → access requirements, in list order   one lazily-decrypted Session, shared
  → session-aware before()
  → body read → CSRF check → handler     handler gets the same Session instance
```

Requirements run before the body is read, so the guarantee that unauthenticated bodies aren't
buffered (2026-07 M2) still holds. The session instance a requirement sees is the one the
handler receives, which preserves today's identity invariant. An unmatched path runs no
requirements and stays a 404. Static files and the bundled htmx asset aren't routes and stay
public, which is why M7 adds an untrusted mode for upload directories.

### Rejections

A requirement's `Result` is sent through the normal response choke point, so after-middleware,
security headers and session write-back all apply. `Access.session` picks the response from the
request:

| Request | Response |
|---|---|
| Browser page (non-htmx, non-JSON) | `302` to `loginUrl` |
| htmx (`HX-Request: true`) | `401` with `HX-Redirect: <loginUrl>`, so htmx navigates instead of swapping the login page into a fragment |
| JSON (`Accept` or `Content-Type` is JSON) | `401` |
| WebSocket upgrade | `401` handshake rejection; a browser socket can't follow a redirect |

Confirm the htmx case against the bundled htmx 2.0.10 in a browser test; it depends on htmx
honoring `HX-Redirect` on a 4xx.

### WebSockets (H2)

`app.ws(...)` returns a config with `.access(...)` and `.publicRoute()`. The upgrade mapping in
`Brace.java` builds a `Request` from the upgrade request and runs, in order:

1. plain `before()`;
2. the route's resolved access requirements;
3. the Origin check.

Any rejection declines the handshake with the rejection's status. The session handed to the
socket is the one the requirements used. `ctx.join(room)` is still an authorization decision
inside the handler; the docs say so.

### `/ops`

The framework registers every `/ops/*` route with `Access.ops(READ)` or `Access.ops(CONTROL)`,
and `/ops/auth*` routes as public. The app's `defaultAccess` does not apply under `/ops`, which
has its own credentials.

`Access.ops` authenticates, audits (`OpsAudit`) and attaches the verified claims to the request,
through a package-private field read by `OpsHandler`. The 17 per-method `authorize(...)` calls
and `authorizedClaims(...)` lookups go away. `loginToken` and `dashboard` read the attached
claims instead.

### Interaction with M2 (CSRF)

A route "uses the cookie session" if its handler type takes a `Session`, or if any of its
resolved requirements is session-reading. M2's startup check reads this from the same resolved
data: `.csrf(false)` on such a route is an error unless it gives a reason
(`.csrf(false, "reason")`).

### Visibility

- **Startup banner:** each route line shows its access, e.g. `public`, `session:userId`,
  `group:/admin + session:userId`, `ops:READ`. With `Access.none()` as the default, the banner
  says so in a single warning line.
- **`/ops/routes`:** gets an `access` field per route.
- **`brace check`:** not changed (decided during implementation). It is a health check of a
  running server, and an app that violates the rule can't start. CI catches violations through
  `Brace.test()`, which runs the same `start()` check, so `brace test` fails on an unmarked
  route.

### Commit breakdown (all under H1, except where noted)

1. `Access` type, `Route` access list, `RouteConfig.access(...)`/`.publicRoute()`,
   `defaultAccess`, and the group overload with nested-group inheritance. `BraceHandler` runs
   requirements at the position shown above. Add the startup check, remove `requireSession`,
   and migrate the framework's own tests.
2. `/ops` routes via `Access.ops`, with per-method `authorize` removed.
3. Visibility: banner, `/ops/routes` and `brace check`.
4. **H2:** WebSocket routes in the model, with requirements run on upgrade.
5. Docs: rewrite the `BRACE-AGENTS.md` middleware/auth section around `defaultAccess`; add an
   "Authorization" section to `SECURITY.md` (covering route access here and object access in
   H4); sweep `README.md`; update `sample/`; add a migration-guide entry with the
   `requireSession` → `defaultAccess` rewrite.

**Framework test suite.** Hundreds of existing tests register routes without access. Give
`Brace` a package-private hook, used only by `com.larvalabs.brace` tests, that defaults to
`Access.none()`, so the public API gets no bypass. `RouteAccessTest` runs with the check fully
on.

### Tests

- **Startup check:**
  - a route with no requirement fails `start()` and names the route and its handler;
  - `.publicRoute()` clears it;
  - `defaultAccess` covers every route;
  - `Access.none()` passes and is logged.
- **Stacking:**
  - default + group + route are all enforced, in order;
  - nested groups inherit from the outer group;
  - a group requirement doesn't leak to sibling routes outside the group.
- **Contradictions:** `.publicRoute()` combined with `.access(...)`, or used inside a guarded
  group, fails startup.
- **Equivalent paths:** trailing-slash, percent-encoded and HEAD requests to a guarded GET route
  are all rejected.
- **Rejections:**
  - a page request redirects;
  - htmx gets `401` + `HX-Redirect`;
  - JSON gets `401`;
  - a WebSocket handshake is declined;
  - security headers are present on each.
- **Lifecycle order:**
  - a rate limiter in `before()` runs before access;
  - a rejected request's body is never read;
  - the handler receives the same `Session` instance the requirement saw.
- **Ops:**
  - every `/ops` route resolves to `ops:*` or public;
  - a handler body with no auth code is still protected;
  - audit records still appear.
- **M2:** `.csrf(false)` on a route covered by `Access.session` fails startup.

## Breaking changes and rollout

**Decision (2026-10-26):** Brace's only users are its maintainers' own apps, so every change is
enforced in the next release (0.1.11). Nothing is staged behind a warning, there are no
deprecation windows, and there is no dual-read. Each user-visible change still gets a
migration-guide entry with a before/after, because the guide is how our own apps get upgraded.

| ID | Change in 0.1.11 | What an app has to do |
|---|---|---|
| H1 | `start()` **fails** on any route that has no access requirement and isn't marked public; `requireSession` is removed | Replace `requireSession(pattern, …)` with `defaultAccess(...)` or a group requirement, and mark the public routes `.publicRoute()` |
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
| D1 | H1: hard fail at `start()`, or a warning? Name of the public marker? | **Decided:** hard fail in 0.1.11, no warn-only mode; the marker is `.publicRoute()`. An app with no login at all states it with `defaultAccess(Access.none())`. Full design in [H1 design](#h1-design-the-route-access-model). |
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
