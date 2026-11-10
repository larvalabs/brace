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
| A static mapping answers 405 to methods other than GET and HEAD, and never serves a path a route pattern matches | M3 review | Serve anything that takes another method from a route |
| A static mapping's directory must exist at `start()` | M3 review | Create it at deploy time or before `start()` |
| Mappings on overlapping directories need the same access; a prefix is registered once | M3 review | Separate the directories, or pass the same `Access` objects |
| Under a guarded mapping, and on any access rejection, caching headers from the app are replaced (`private, no-cache` for the file, `no-store` otherwise) | M3 review | None expected |
| `Result` header names are case-insensitive; `headers()` iterates in name order | M3 review | None expected; don't rely on two spellings being two headers |
| A response carrying the session cookie gets `private` added to an explicit `Cache-Control` without `private`/`no-store` | M3 review | None expected |

## M3 design: static mapping access

Written before the M3 code, per the Track C table. It extends the route access model
([H1 design](2026-10-26-security-review-fix-plan.md#h1-design-the-route-access-model)) to
`staticFiles` mappings, per decision D1.

### The rule

Every static mapping carries either a list of access requirements or an explicit public marker,
attached to the mapping object. `start()` (and so `Brace.test()`) refuses to run while a mapping
has neither. No mapping is public unless the code says so.

### API

`staticFiles(...)` returns a `StaticFilesConfig` in place of the `Brace` it returned before. It
has two methods, and each returns the `Brace`, so a builder chain continues after the declaration:

```java
app.staticFiles("/assets", "public").publicFiles();                    // served to everyone

app.staticFiles("/uploads", "/var/app/uploads", StaticFiles.UNTRUSTED)
   .access(Access.session("userId", "/login"));                        // logged-in users only

app.staticFiles("/reports", "/var/app/reports")
   .access(Access.session("userId", "/login"), Access.check(Reports::canRead));   // all must pass
```

- `.publicFiles()` reads like `.publicRoute()`; `.access(Access, Access...)` reads like a
  route's `.access(...)`, and takes any `Access` a route takes (`session`, `check`,
  `checkRequest`).
- Returning a config changes the type of an existing chain
  (`Brace.app().staticFiles(...).port(...)`), so most callers get a compile error at the exact
  line that needs the declaration. A call whose return value is dropped still compiles; the
  startup check catches that one.
- `StaticFiles.UNTRUSTED` is unchanged and orthogonal: the mode decides how a file is served
  (type allowlist, attachment, sandbox), the access decides who gets it. Any combination works.

### Resolution, and how `defaultAccess` relates

**`defaultAccess(...)` does not apply to static mappings.** A mapping's effective list is
exactly what its `.access(...)` call declared. Reasons:

1. Inheriting the default would silently put the login in front of the stylesheet, scripts and
   images of the login page itself in every app that has one. The failure is a broken page with
   no error, not a startup refusal.
2. Defaulting to public is the bug being fixed.
3. So neither default is safe, and the route model's answer to "no safe default" is a startup
   refusal that names the two choices. It costs one method call per mapping, and an app has one
   or two mappings.

Consequences of "exactly what was declared":

- `Access.none()` is rejected in `.access(...)` (as on a route): the public marker is
  `.publicFiles()`. `defaultAccess(Access.none())` does not make a mapping public either.
- `Access.insteadOfDefault(x)` is accepted and means `x`: there is no default to replace.
- Calling both `.publicFiles()` and `.access(...)` on one mapping throws at the call.
- A session-reading requirement without `.sessions(...)` fails `start()`, as for routes.
- The framework test suite's fallback default (`frameworkTestDefaultAccess`) does not cover
  mappings either: every test declares its mapping.

The startup error:

```
IllegalStateException: 1 static mapping has no access declaration:
  /uploads  ->  /var/app/uploads
Say who may fetch each one: app.staticFiles(...).publicFiles() serves it to everyone,
app.staticFiles(...).access(Access.session("userId", "/login")) guards it. defaultAccess(...)
does not apply to static mappings.
```

**Overlapping directories need the same access.** `start()` refuses two mappings whose
directories overlap (one contains, or is contained in, the other; compared as written and with
symlinks resolved, like the existing trusted/untrusted check) unless both are public or both
list the same requirements: the files would be reachable around the stricter guard through the
other prefix. The first version of this check covered only guarded-versus-public; the review
found that `/admin-files` (login + admin) and `/files` (login) on one directory started fine.
Requirements are compared by identity (`Access` has no equality), so two
`Access.session("userId", "/login")` calls count as different: the fail-closed reading.

**A mapping's directory must exist at `start()`.** The overlap check reads the filesystem once.
A directory missing at start could be created later as a symlink into another mapping's tree,
or as a case-variant sibling (`data/Private` beside `data/private`) on a case-insensitive
volume. As a second line, at serve time a mapping answers 404 for a file whose real path is
inside the directory of a mapping with different access; that covers a directory replaced by a
symlink after startup. A symlink created later from inside one mapping's directory into
another's was already a 404: the real path is outside the serving mapping's own directory.

**A URL prefix is registered once.** `staticFiles` throws on a duplicate (`/files` and
`/files/` are the same prefix): the second was silently dead.

### Where it runs in a request

```
no route matched this method and path, a mapping's prefix covers the path
  → plain before()                         unchanged; rate limiters shed load first
  → the mapping's access requirements      one Session, built on first session-reading requirement
  → session-aware before()                 gets that same Session instance
  → a route pattern matches the path       405, Allow: that route's methods (review fix)
    under another method
  → method is not GET or HEAD              405, Allow: GET, HEAD (review fix)
  → file lookup and response
```

- The mapping is chosen once, by the M1 prefix rule (longest prefix, segment boundary), and the
  requirements run against that mapping object, before any decoding or filesystem access. Every
  spelling that can reach the mapping's files passes through its guard; M1 and M2 already turn
  the other spellings into 404s.
- A rejection is sent before the file is looked up, so it is the same response for a file that
  exists, one that doesn't, a dotfile and a non-canonical spelling. Only a request that passed
  the guard can learn whether a file exists.
- A route still wins over a mapping for the same path, and carries its own access. Review fix:
  that has to hold for every method, not only the route's. The router matches on method and
  path, so `POST /docs/payroll.pdf` found no route when only `GET` was registered, fell through
  to the mapping, and was served the file under the mapping's weaker access. Now the mapping
  never serves a path that any route pattern matches (`Router.methodsMatching`), and serves
  only `GET` and `HEAD` anywhere. Both 405s come after the mapping's access, so an anonymous
  caller gets the same rejection as for any other path under the mapping and learns nothing
  about the route or the file. A caller who passes the mapping's access learns from `Allow`
  which methods the route has, which it could already learn by trying them. The route's own
  access is not evaluated for the 405: several routes with different access can share the
  path, and nothing of theirs is served.
- The router had no 404-versus-405 distinction to reuse (`noRouteFound` only adds dev-mode
  suggestions to a 404), and a path outside every mapping that matches a route only under
  another method is still a 404. Changing that app-wide is a separate decision.
- `start()` warns, once per mapping, when routes are registered under its prefix (not for a
  mapping at `/`, which every route is under). It does not refuse: a route over a mapped file
  is the documented way to decide per file.
- The bundled `/__brace/*` assets and `/__brace/health` are not mappings: they stay public and
  need no declaration, also when a mapping is registered at `/`.

### Caching

- A public mapping keeps today's headers (`public, max-age=0, must-revalidate`; `public,
  max-age=31536000, immutable` for a current `?v=` fingerprint).
- A guarded mapping sends `Cache-Control: private, no-cache`: the browser may keep the file but
  revalidates on every use, and the revalidation runs the guard, so a file is not shown from
  cache after logout. A `?v=` fingerprint earns no `immutable` here. `ETag`/`Last-Modified`,
  conditional requests and `Range` work as before.
- That is enforced at the response choke point, after after-middleware. The first version
  rewrote `public` to `private` in `Cache-Control`; the review showed that was not enough
  (`s-maxage`, a bare `max-age`, `Expires`, `CDN-Cache-Control` and `Surrogate-Control` passed,
  a lowercase `cache-control` was not found at all, and with no after-middleware the 404, 416
  and rejection carried no `Cache-Control`). Now, for every response under a guarded mapping,
  `Cache-Control`, `Expires`, `Pragma`, `CDN-Cache-Control`, `Surrogate-Control`,
  `Cloudflare-CDN-Cache-Control` and `Vercel-CDN-Cache-Control` are removed in any letter case
  and one `Cache-Control` is set: `private, no-cache` for the file (200, 206, 304), `no-store`
  for everything else (rejection, 404, 405, 416, a `before()` short-circuit, an error).
- An access rejection on a route gets the same treatment (`no-store`, plus
  `Vary: Accept, HX-Request`): a cached login redirect would be served to logged-in users.
  Successful responses on guarded routes are left to the app; dev mode warns once per route
  when one is sent `public` or `s-maxage`.
- `Result` header names are case-insensitive (the class of bug behind the lowercase miss), and
  a response carrying the session cookie gets `private` added unless it already says `private`
  or `no-store`, so the cookie never rides `max-age` or `s-maxage` alone either.

### `Assets`

`Assets.url(...)` keeps fingerprinting files in any mapping. For a guarded mapping the `?v=` is
harmless but buys no long-lived caching. `Assets` only ever returns a URL with a short content
hash; it serves no bytes.

### Visibility

- **Startup banner:** a `static (n)` block after the routes, one line per mapping: prefix,
  access (`public` or the requirement labels joined with ` + `), and `untrusted` for that mode.
- **`/ops/routes`:** one entry per mapping, `{"method":"STATIC","pattern":"/uploads/*",
  "access":"session:userId"}`, plus `"mode":"untrusted"` where it applies.
- **`brace check`:** unchanged, as for routes: it probes a running server, and an app that
  breaks the rule cannot start.

### Tests

`StaticMappingAccessTest`: startup refusal (plain `start()` and `Brace.test()`), both markers
together, `Access.none()`, session requirement without sessions, guarded/public directory
overlap; anonymous rejected and authenticated served; the M1/M2 variants never return 200;
rejection identical for existing and missing files; cache headers for guarded and public
mappings, including under a `public` after-middleware; UNTRUSTED plus access; `/ops/routes` and
the banner; `/__brace/*` served with no declaration.

`StaticMappingReviewFixesTest` (review fixes), over raw sockets: POST, OPTIONS, QUERY, PUT,
DELETE and PATCH on a guarded and a public mapping, with and without a route at the path; the
405 and its `Allow`; the uniform rejection for anonymous requests whatever the method; cache
headers on the 200, 206, 304, 404, 405, 416 and the rejection under an after-middleware that
sets every caching header in mixed case, and with no after-middleware; a session cookie under a
lowercase `cache-control: public`; route rejections `no-store` with `Vary`; the dev-mode
warning; the startup refusals (different access on one directory, missing directory, duplicate
prefix) and the startup warning; a directory relinked after start.
