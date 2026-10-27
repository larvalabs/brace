# Security Review Todos: 2026-10-26

Findings from a full-codebase security review of Brace at `e11da4f` (0.1.10, with 0.1.11 in
progress). This is the third security review under the
[periodic model review process](reviews/README.md). The earlier ones are
[2026-06](reviews/2026-06-security-fable-5.md) and [2026-07](reviews/2026-07-security-opus-5.md).

**Framing.** The earlier reviews looked for bugs. This one asks a different question for each
category: is the protection **structural** (the framework enforces it by default, so code an app
or an agent writes can't easily get it wrong), **per-handler** (each route or developer has to
remember it), or **missing**? Per-handler protection counts as a finding even when every current
call site is correct, because Brace's users are largely agents that follow the docs literally.
Several findings below are therefore about API shape and docs, not about a line that is
wrong today.

**Scope.**

- Framework source (`src/main/java/com/larvalabs/brace`).
- The `bin/brace` launcher and `install.sh`.
- The project `brace new` generates (`ProjectGenerator.java`).
- The docs agents learn from (`BRACE-AGENTS.md`, `docs/SECURITY.md`).

Deployment context assumed: fat JAR in Docker, managed by Dokploy (Traefik in front) on a single
Hetzner VM, with Postgres in a sibling container on the same host using LISTEN/NOTIFY.

**Verification.**

- Every High and Medium finding was either confirmed by reading the code at the cited lines, or
  reproduced with a scratch program against `target/classes`. The request-path findings were
  reproduced on a live Jetty server, and SQL injection on Hibernate 7.0.10 with H2.
- Nothing from the 2026-06 or 2026-07 reviews has regressed. Items those reviews cleared are not
  repeated here.
- Suspicions that could not be confirmed are listed under "Not confirmed" at the end, and
  re-checked areas that hold up are listed under "Checked and cleared".

**Tooling.** Tool output was treated as leads, and every lead was verified by reading the code.

- **SpotBugs 4.8.6 + find-sec-bugs 1.13.0** (Max effort, Low threshold, SECURITY category):
  98 hits, every one triaged, none a real issue on its own.
  - 57 `POTENTIAL_XML_INJECTION` in `OpsDashboard`/`AnalyticsDashboard`: all interpolations go
    through `esc()`, confirmed by rendering a payload in every analytics row.
  - 23 `IMPROPER_UNICODE`: locale-less `toLowerCase`, no security effect.
  - 8 `COMMAND_INJECTION`: argv-list `ProcessBuilder` calls.
  - `SQL_INJECTION_JDBC` at `JobPoller.java:961`: private helper, only constant SQL is passed in.
  - `REDOS` at `Database.java:494`: linear pattern.
  - MD5 in `Assets`: cache-busting fingerprint, not used for security.
- **Semgrep** (`p/java`, `p/security-audit`, `p/secrets`): 2 hits, the same MD5 and
  file-permission items.
- **Trivy:** not run. There is no Dockerfile or image in this repo; the Dockerfile only exists in
  generated projects. Dependencies were checked against the OSV API instead (M12), and the
  generated Dockerfile was reviewed by hand (M13).

**Fix plan.** Ordering, phases, breaking-change rollout and the merge gate are in
[`2026-10-26-security-review-fix-plan.md`](2026-10-26-security-review-fix-plan.md). This file is
the canonical tracker: tick a box in the commit that fixes it.

**Effort tier.** Prior trackers named a specific model per item. This one gives a tier sized by
how subtle the fix is: **small** (mechanical, well specified), **mid** (multi-file, some judgment)
or **frontier** (changes a default, a protocol or an invariant, or needs a design decision).

## Summary

| # | Category | Verdict | Note |
|---|---|---|---|
| 1 | Authorization | **Per-handler / Missing** | Routes are allow-by-default. Auth is a separate path-pattern list, not tied to routes. WebSocket upgrades skip every guard. There is no object-level scoping, and the canonical `findOr404` is unscoped. Sessions can't be revoked or rotated. |
| 2 | XSS | **Per-handler (close to structural)** | JTE `ContentType.Html` is used in dev, prod and the precompiler, and there is no `$unsafe` anywhere in the repo. Gaps: the documented `${csrfField}` only works via `$unsafe`; the URL check is narrow; `hx-on` values aren't JS-escaped; there's no CSP by default; uploads can be served inline. |
| 3 | SQL injection | **Per-handler** | Framework SQL is fully parameterized. The app API takes raw HQL/SQL fragments, the docs put ORDER BY in them and also promote `?sort=`, and Hibernate's `sql()` turns HQL injection into native SQL. LISTEN/NOTIFY is **structural** (constant channel, bound payload). |
| 4 | CSRF | **Structural for forms with sessions; per-handler elsewhere** | `.csrf(false)` is accepted silently on cookie-session handlers. There's no CSRF at all without `.sessions()`. htmx requests don't carry the token automatically. The page cache leaks tokens across users. |
| 5 | Path traversal | **Structural for static files; per-handler for uploads** | Static files: decode, reject `..`, then check containment lexically and on the real path. Upload filenames are passed through raw, and there is no confined save helper. |
| 6 | SSRF | **Missing** | `Http` has no destination policy. Redirects aren't followed (good). |
| 7 | Command injection | **Structural in Java; missing in the launcher** | Every `ProcessBuilder` call uses an argv list. `bin/brace` builds its exec classpath from an unvalidated pom version. |
| 8 | Deserialization / parsing | **Mostly structural** | No default typing; body caps, lazy body read and multipart spill are in place. Gaps: entity binding via `req.json`, `@Min`/`@Max` truncation, a reachable jackson-core CVE. |
| 9 | Information exposure | **Mostly structural** | Generic 500s and redacted logs. Gaps: unredacted stack traces in `/ops`, Jetty version disclosure, a bearer token in the ops page HTML. |
| 10 | Container / deploy | **Per-project (scaffold weak)** | Secrets come from env vars and the app fails closed without them. The generated image runs as root on a floating Ubuntu tag with a shell, and no hardened runtime config is generated. |

**Counts:** 6 High, 14 Medium, 11 Low.

## High

- [x] **H1: Routes are allow-by-default, and auth is a parallel list not tied to routes**, `Router.java:20-43`, `Middleware.java:57-88`, `Brace.java:910`, `RouteGroup.java`
  - **What's wrong.**
    - A route registers with only method, pattern and handler; no access declaration exists.
    - Protection is a separate list of path patterns (`requireSession("/admin/*", …)`,
      `before("/api/*", …)`), and nothing connects a route to the guard it needs.
    - A route registered outside the guarded prefix is silently public, and nothing reports
      which routes no guard covers.
    - `RouteGroup` can't carry a guard, so a group's prefix and its guard pattern are
      maintained separately and can drift apart.
    - The framework's own `/ops/*` handlers follow the same model: 17 handlers each start with
      `authorize(...)` (`OpsHandler.java:255, 508, 522, …`). All correct today; one missed line
      in a future endpoint exposes ops data.
  - **Attack.** An agent adds `app.getDb("/reports/export", reports::export)` to an app guarded
    by `requireSession("/app/*", …)`. The export is public, no test fails, and `/ops/routes`
    looks the same.
  - **Fix.** The route access model, specified in the fix plan's
    [H1 design](2026-10-26-security-review-fix-plan.md#h1-design-the-route-access-model).
    In short:
    - Access requirements (`Access`) attach to route objects through `defaultAccess(...)`,
      `group(prefix, access, cfg)` and `.access(...)`. They stack; `.publicRoute()` removes only
      the default.
    - They are resolved once at `start()`, which (with `Brace.test()`) fails on any route that
      has neither a requirement nor `.publicRoute()`. `brace check` reports the same.
    - `requireSession(pattern, …)` is removed; plain `before(...)` no longer counts as auth.
    - `/ops/*` routes use `Access.ops(...)`, replacing the 17 per-method `authorize` calls.
    - The startup banner and `/ops/routes` show each route's access.
  - **Tests.** Listed in the design section: startup check, stacking, contradictions,
    equivalent paths, rejections per client type, lifecycle order, ops and the M2 interaction.
  - **Tier: frontier.** A new core API that every app and the framework's own tests change for.

- [x] **H2: WebSocket upgrades bypass all before-middleware, including `requireSession`**, `Brace.java:1140-1162`
  - **What's wrong.** The upgrade mapping runs in `WebSocketUpgradeHandler`, outside
    `BraceHandler`. It checks only Origin, then hands the decrypted session to the handler.
    No before or session guard runs, and neither does `RateLimiter`.
  - **Verified (live server).** With `requireSession("/app/*", "userId", "/login")`:
    - `GET /app/secret` with no cookie → `302 /login`.
    - `ws://…/app/live` with no cookie → connected, and the handler ran with `userId=null`.
  - **Attack.** A socket under a guarded prefix that subscribes to a client-named room, or that
    assumes `ctx.session()` is authenticated, serves other users' pushes to unauthenticated
    clients.
  - **Fix.** WebSocket routes join the H1 access model (see the fix plan's
    [H1 design](2026-10-26-security-review-fix-plan.md#websockets-h2)):
    - `app.ws(...)` takes `.access(...)`/`.publicRoute()` and inherits `defaultAccess`.
    - The upgrade builds a `Request` and runs plain `before()`, then the route's requirements,
      then the Origin check.
    - A rejection declines the handshake (a session requirement answers 401, since a browser
      WebSocket can't follow a redirect).
    - Document that `ctx.join(room)` is an authorization decision.
  - **Tests.**
    - An unauthenticated upgrade to a route under `Access.session` is rejected.
    - An authenticated one connects.
    - A `before` rate limiter applies to upgrades.
    - Origin check behavior is unchanged.
  - **Tier: frontier.** Guard semantics on a non-HTTP-response path.

- [x] **H3: HQL injection through where-fragments and ORDER BY; `sql()` escalates it to native SQL**, `Database.java:71, 102, 130, 139, 192, 221, 236, 258, 355, 365, 374, 385, 400, 413, 545-551`
  - **What's wrong.**
    - Values are bound when the developer writes `?`, but the APIs take a raw string, and
      `whereClause` concatenates it.
    - `BRACE-AGENTS.md:380-384` and `:1248-1249` teach that ORDER BY belongs inside the
      fragment. `:247`, `:450`, `:623-628` and `Request.urlWith` promote user-controlled
      `?sort=` links. Together these lead straight to `"… ORDER BY " + req.queryParam("sort")`.
    - There is no safe sort API, nothing detects request data in a fragment, and there is no
      SQL-injection section in `SECURITY.md`.
  - **Verified (Hibernate 7.0.10 / H2).**
    - `db.query(Post.class, "title = '" + q + "' AND ownerId = ?", 99L)` with
      `q = zzz' or 1=1 or title='` returned all rows.
    - An ORDER BY `case when (select substring(x.passwordHash,1,1) from Account x where x.id=1)='s' then id else -id end`
      gives a one-bit-per-request oracle.
    - `sql('…')` inside an injected fragment reached non-entity tables as native SQL.
  - **Fix.**
    1. Add `Sort`: `Sort.from(req, "sort", Map.of("name","title","date","createdAt"), "createdAt DESC")`.
       It allowlists the request's sort key against field names (validated with
       `requireValidFieldIdentifier`), parses `-` for descending, and renders ` ORDER BY …`.
       Add `query`/`paginate`/`queryPage` overloads that take `Sort`, and rewrite every docs
       `?sort=` example to use it.
    2. Add a dev/test-mode taint check in `Database`: if a fragment contains, verbatim, any
       current request query, form or path value of 3 or more characters, throw
       `IllegalArgumentException` naming the parameter. In prod, log one warning per call site.
       The current request is available via a request-scoped holder set by `BraceHandler`.
    3. Add a rule to `BRACE-AGENTS.md` ("never concatenate request data into a query string;
       bind with `?`, sort with `Sort`") and a SQL section to `SECURITY.md`.
  - **Tests.**
    - `Sort` rejects an unlisted key and falls back to the default.
    - The taint check throws in dev/test for a concatenated query param and a concatenated path
      param, but not for bound params or for short or common values (`id`, `1`).
    - The H2 injection payloads above fail with the check on.
  - **Tier: frontier for the taint check** (false-positive tuning). **Mid for `Sort` and the docs.**

- [x] **H4: No object-level authorization; the canonical lookup is an IDOR**, `Database.java:211-215`, `BRACE-AGENTS.md:373, 1229-1230`
  - **What's wrong.**
    - `findOr404(type, id)` returns any user's row, and it is taught as "the canonical handler
      lookup" with "don't write a preamble around it".
    - There are no Hibernate filters, no tenant support and no ownership helper.
    - Neither doc has an authorization or IDOR section.
  - **Attack.** `GET /posts/{id}/edit` → `db.findOr404(Post.class, req.longPathParam("id"))`.
    Changing the id edits another user's post. Every show/edit/delete route written the
    documented way has this bug.
  - **Fix.**
    - Add `db.findOwnedOr404(type, id, "ownerField", ownerId)`, with the field validated, and
      `findOr404(type, id, Predicate<T> allowed)`. Both return 404 (not 403) on mismatch.
    - Teach the owned form as the canonical lookup for user-owned data.
    - Add an "Object-level authorization" section to `SECURITY.md`.
    - Follow-up design note (not in this fix): a request-scoped `db.scopedTo("ownerId", uid)`
      that the where-fragment helpers apply automatically.
  - **Tests.**
    - The owner gets the row.
    - A non-owner gets `NotFoundException`.
    - An invalid field name throws at the call.
    - The predicate variant behaves the same way.
  - **Tier: mid** (API plus docs). The docs rewrite matters as much as the code.

- [x] **H5: The `Http` client has no SSRF protection**, `Http.java:36-38, 141-150`, `BRACE-AGENTS.md:807-860`
  - **Verified.**
    - These reach a 127.0.0.1 listener: `127.0.0.1`, `localhost`, `0.0.0.0`, `2130706433`
      (decimal), `[::ffff:127.0.0.1]`.
    - Nothing blocks 169.254.169.254, 10/8, 172.16/12 (the Docker bridge and the Postgres
      container), 192.168/16, 100.64/10 or fc00::/7, or names that resolve to them.
    - Non-http(s) schemes are rejected by the JDK, and redirects are not followed.
    - `fetch()`/`fetchString()` have no response size cap.
  - **Attack.** An "import from URL", webhook-test or link-preview feature reads Hetzner
    metadata at `http://169.254.169.254/hetzner/v1/userdata`, where cloud-init user-data often
    holds secrets. It can also reach internal services on the Docker network.
  - **Fix.**
    - Add an outbound address policy that is **on by default** for every `Http` call, with
      `Http.get(url).trusted()` as the explicit per-call escape for internal services.
    - Resolve the host once, reject loopback, link-local, RFC 1918, CGNAT, ULA, unspecified,
      multicast and IPv4-mapped/compatible addresses, then connect to the checked address
      (rewrite the URI to the IP, keep the `Host` header, keep SNI) so DNS rebinding can't swap
      it.
    - Any redirect the caller re-issues goes through the same check.
    - Add a `maxResponseBytes` default (e.g. 10 MB) and override.
    - Add an SSRF section to `SECURITY.md` and a warning at the `Http` section of
      `BRACE-AGENTS.md`.
  - **Tests.**
    - Each blocked range is rejected, including decimal and mapped forms and a hostname
      resolving to 127.0.0.1.
    - A public address passes.
    - The size cap is enforced.
    - `trusted()` bypasses the policy.
  - **Tier: frontier.** Connecting to the pinned address under TLS/SNI with `java.net.http` is
    the subtle part.

- [x] **H6: The `brace` launcher runs a classpath chosen by a cloned repo**, `bin/brace:65-74, 78-97, 100-111, 150-162`
  - **What's wrong.** `pinned_version` takes `<brace.version>` from `./pom.xml`, strips only a
    leading `v`, and splices it into `$BRACE_CACHE/$v`. That path becomes
    `exec java -cp "$HOME_DIR/lib/*"`. The same string also reaches `rm -rf "$dest"`/`mv` in
    `fetch_toolchain`.
  - **Verified.** A pom pinning `../../../../../../../../../../../proc/self/cwd`, plus a
    `lib/x.jar` holding a fake `com.larvalabs.brace.Cli`, ran attacker code on `brace status`.
  - **Attack.** A developer, CI job or coding agent clones a third-party Brace project and runs
    `brace status`. The attacker gets code execution with access to `~/.brace` ops keys and
    tokens.
  - **Fix.**
    - Validate in `pinned_version` against `^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$` and
      exit with an error otherwise.
    - Apply the same check to `install.sh` (`VERSION`, `tag_name`) and to
      `CliSelfUpdate`/`Toolchains` (`target`, `tag_name`) in Java.
  - **Tests.**
    - A new `tests/cli` case: a traversal pin errors out without exec'ing.
    - Valid `0.1.10`, `v0.1.10` and `0.1.11-SNAPSHOT` pins still resolve.
    - Java unit test for the shared validator.
  - **Tier: small.**

## Medium

- [x] **M1: The page cache serves one user's CSRF token and flash message to every visitor**, `Cache.java:353-375`, `View.java:55-63`, `BraceHandler.java:412-430`
  - **What's wrong.** `View.of` resolves the CSRF field and flash into the template params, and
    `BraceHandler` wires both for plain `Handler` routes too. `CachedHandler` then stores the
    rendered body.
  - **Verified.** With `app.get("/cached", Brace.cache().wrap("5m", req -> View.of("form")))`:
    - User A (pending flash "reset link sent to alice@…", token `TOKEN_OF_USER_A…`) renders
      the page first.
    - User B, with a different session, received A's flash text and A's masked token, which
      unmasks to `TOKEN_OF_USER_A…`.
  - **Impact.**
    - Flash PII leaks to arbitrary visitors.
    - Any visitor holds a valid CSRF token for whoever rendered first.
    - Every other user's form 403s, which pushes developers toward `.csrf(false)`.
  - **Fix.**
    - `View` records whether this render consumed `csrfField`/`csrfToken` or `flash`, via a
      flag on the `View` instance set by `of()`.
    - `CachedHandler` doesn't store such a render (the response is still returned), and logs
      once per route in dev.
    - Optional follow-up: per-request placeholder substitution, so cached pages with forms work.
  - **Tests.**
    - The two-user repro above: B gets its own token and no flash from A.
    - A page with no CSRF field or flash is still cached.
  - **Tier: mid.**

- [x] **M2: CSRF can be silently disabled on cookie-authenticated routes**, `BraceHandler.java:374`, `RouteConfig.java:37`
  - **Verified.** `app.post("/transfer", (SessionHandler) …).csrf(false)` starts with no
    warning, and a token-less POST returns 200, running with the cookie session.
  - **Gaps.**
    1. `.csrf(false)` is documented as "for bearer-token APIs", but nothing checks that the
       route doesn't use the cookie session (a Session/Full handler, or coverage by a session
       guard).
    2. Apps that authenticate with their own cookie and never call `.sessions()` get no CSRF
       enforcement at all.
    3. htmx requests get no token automatically (doc note only, `BRACE-AGENTS.md:592-594`),
       so every non-form `hx-post` 403s until someone wires it up.
  - **Fix.**
    - `start()` rejects a mutating route with `.csrf(false)` that uses the cookie session: its
      handler takes a `Session`, or any of its resolved H1 access requirements reads the
      session. Opt out with `.csrf(false, "reason")`, which also documents why.
    - Add a cross-site request check as a second layer that runs even without sessions:
      reject mutating requests whose `Sec-Fetch-Site` is `cross-site`, or whose `Origin` host
      doesn't match the effective host. Skip it for `.csrf(false)` routes.
    - Add `${csrfMeta}` (a masked token in `<meta name="csrf-token">`) plus a bundled
      `/__brace/csrf-htmx.js` (`htmx:configRequest` adds `X-CSRF-Token`), and put both in the
      scaffold layout.
  - **Tests.**
    - Startup fails for `.csrf(false)` + `SessionHandler`, and also for a plain `Handler`
      covered by `Access.session`.
    - A cross-site `Sec-Fetch-Site`/`Origin` POST gets 403 with sessions off.
    - A same-origin POST passes.
    - An htmx POST from the scaffold layout succeeds end to end.
  - **Tier: frontier.** Changes a default and needs a startup-check design.

- [x] **M3: The documented `${csrfField}` renders escaped; only `$unsafe` makes it work**, `View.java:55-57`, `Csrf.java:88`, `BRACE-AGENTS.md:590`, `SECURITY.md:128`
  - **Verified.** The output is `<form method="post">&lt;input type="hidden" name="_csrf" …&gt;</form>`;
    the field shows as text and the POST 403s. No test renders `csrfField` through a `.jte`.
  - **Why it matters for security.** The only fix available to a developer or agent is
    `$unsafe{csrfField}`, which normalizes `$unsafe`.
  - **Fix.**
    - Pass `csrfField` as `gg.jte.Content` (a small `CsrfField implements Content` that writes
      the escaped hidden input).
    - Also expose `csrfToken`: the masked string, for `<meta>` tags and `value="${csrfToken}"`.
    - Update the docs: templates declare `@param gg.jte.Content csrfField = null`.
  - **Tests.** A real `.jte` renders `${csrfField}` as a working hidden input, and a POST with
    it passes CSRF.
  - **Breaking:** templates that declared `@param String csrfField`. Needs a migration-guide
    entry.
  - **Tier: small.**

- [x] **M4: JTE's `javascript:` guard is narrow and bypassable; Brace adds no URL sanitizing**, `TemplateEngine.java`
  - **What's wrong.** JTE's `OwaspHtmlTemplateOutput` checks only `<a href>`, and only for the
    literal (trimmed, case-insensitive) prefix `javascript:`.
  - **Verified.**
    - `href="${u}"` with `u = "java\tscript:alert(1)"` or `"\u0001javascript:alert(1)"` is
      emitted unchanged, and the WHATWG URL parser browsers use reads both as `javascript:`.
    - `<iframe src>`, `<form action>` and `hx-get` pass `javascript:` and `data:text/html`
      through.
    - There is no `@Url` validator and no safe-href helper.
  - **Attack.** A stored profile "website" rendered as `<a href="${user.website}">` gives
    stored XSS.
  - **Fix.**
    - Add `BraceHtmlOutput extends OwaspHtmlTemplateOutput` (public and non-final; JTE doesn't
      re-wrap an existing `HtmlTemplateOutput`) and pass it from `TemplateEngine.render`/`renderToBytes`.
    - In `setContext`, for URL attributes (`href`, `src`, `action`, `formaction`, `xlink:href`,
      `poster`, `hx-get|post|put|patch|delete`), normalize as WHATWG does (strip leading C0 and
      space, remove tab/CR/LF) and allow only `http`, `https`, `mailto`, `tel` and relative
      URLs. Anything else renders as `#` (or `about:invalid`).
    - Add a `@Url` form annotation and `Url.safe(String)`.
  - **Tests.**
    - Each bypass string renders neutralized in each URL attribute.
    - Relative, http(s), mailto and tel URLs are unchanged.
    - Precompiled templates get the same output class.
  - **Tier: frontier.** It hooks JTE internals, and both the dev and precompiled paths need it.

- [x] **M5: User data inside `hx-on*` attributes becomes JavaScript**, `TemplateEngine.java`, `src/main/resources/brace/htmx.min.js`
  - **What's wrong.** JTE JS-escapes `on*` attributes but treats `hx-on:click`, `hx-on-click`,
    `hx-vals='js:…'` and `hx-trigger` filters as plain attributes.
  - **Verified.** `hx-on:click="go('${s}')"` with `s = "');alert(document.domain)//"` renders
    `&#39;`, which the browser decodes back to `'` before htmx evaluates it.
  - **htmx config.** The bundled htmx 2.0.10 runs with `allowEval:true` and
    `allowScriptTags:true`. Nothing sets `htmx.config`.
  - **Fix.**
    - In `BraceHtmlOutput` (M4), apply JS-attribute escaping to `hx-on*`/`data-hx-on*`, and to
      `hx-vals`/`hx-headers` values beginning with `js:`/`javascript:`.
    - Scaffold layout ships
      `<meta name="htmx-config" content='{"allowEval":false,"allowScriptTags":false,"includeIndicatorStyles":false}'>`.
    - Docs: htmx attributes are code.
  - **Tests.** The payload above renders JS-escaped in `hx-on:click`, and a normal `hx-get` URL
    is unaffected.
  - **Tier: mid** (it rides on M4).

- [x] **M6: No CSP and no security headers by default; the documented CSP is ineffective**, `SecurityHeaders.java:25-32, 105`, `ProjectGenerator.java:191-196`, `BRACE-AGENTS.md:969`, `OpsDashboard.java:555-557, 666-680, 695`
  - **What's wrong.**
    - `SecurityHeaders.defaults()` is opt-in and has no CSP.
    - The scaffold doesn't add it.
    - The only documented CSP is `script-src 'self' 'unsafe-inline'`.
    - Verified on the wire: handler responses carry no `nosniff`, `X-Frame-Options` or CSP.
    - A strict app-wide CSP breaks `/ops/dashboard` (inline `<script>` and `onclick`), and
      after-middleware now reaches `/ops/*`.
  - **Fix.**
    - Move the ops dashboard JS into a served `/__brace/ops.js`, remove the inline handlers,
      and give `/ops/*` responses their own strict CSP.
    - Add `SecurityHeaders.strict()`: defaults plus
      `default-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`.
    - The scaffold uses `.after(SecurityHeaders.strict())`.
    - Remove `'unsafe-inline'` from the docs.
    - `brace check` flags an app with no CSP.
    - Decide whether `defaults()` itself gains the CSP (breaking; see the fix plan's open
      decisions).
  - **Tests.**
    - The ops dashboard works under the strict CSP (htmx polling, buttons).
    - The scaffold app returns the strict headers on handler, static, 404 and 500 responses.
  - **Tier: mid.**
  - **Progress.** Done with L3, except the scaffold line, which Phase 5's scaffold integration
    collects. `defaults()` keeps no CSP (D6). The strict-headers test runs against an app built
    with `after(SecurityHeaders.strict())` rather than the scaffold, and the dashboard was
    browser-checked under that app (no CSP violations; polling, tabs and both CONTROL buttons work).

- [x] **M7: Uploaded content can be served inline as HTML or SVG on the app origin**, `BraceHandler.java:1251, 1258`, `Result.java:152, 163`, `Storage.java:161-174, 247-268`, `SECURITY.md:512, 544`
  - **What's wrong.**
    - `staticFiles` and `Result.file` map `.html` to `text/html` and `.svg` to
      `image/svg+xml`, served inline.
    - `Storage.put(key, UploadedFile)` forwards the browser-claimed `Content-Type`.
    - `safeKey` keeps the attacker's extension.
    - `SECURITY.md` shows `staticFiles("/assets/uploads", "/var/app/uploads")` with `safeKey`
      as "✅ Safe".
  - **Verified.** `/uploads/x.html` came back 200 `text/html` with a working `<script>`, and
    `/uploads/x.svg` came back 200 `image/svg+xml`.
  - **Fix.**
    - `safeKey`/`extension` map active types (html, htm, xhtml, svg, xml, js, mjs) to `.bin`
      (or reject them via a `safeKey(..., allowed)` overload).
    - `Storage.put(key, UploadedFile)` derives `Content-Type` from the key's extension through
      an allowlist, defaulting to `application/octet-stream`.
    - Add `staticFiles(prefix, dir, StaticFiles.UNTRUSTED)`: `Content-Disposition: attachment`
      plus `Content-Security-Policy: sandbox` for anything that isn't a raster image.
    - Correct the `SECURITY.md` example to use it.
  - **Tests.**
    - The `.html`/`.svg` upload repro under `UNTRUSTED` comes back as an attachment with
      sandbox.
    - `safeKey("x.svg")` doesn't end in `.svg`.
    - `Storage.put` ignores a client `text/html`.
  - **Tier: mid.**

- [x] **M8: Upload filenames are passed through raw; there is no confined save helper**, `UploadedFile.java:65, 145`, `BraceHandler.java:1537-1555`
  - **Verified.** `../../../etc/cron.d/x`, `/abs/path.txt` and backslash names come through
    `filename()` unchanged. `dir.resolve("/abs/path.txt")` discards `dir`. The framework's own
    `FileUploadTest` uses `tmp.resolve(file.filename())`.
  - **Fix.**
    - Add `UploadedFile.safeFilename()`: basename after both `/` and `\`, controls stripped,
      leading dots trimmed, length-capped, never empty.
    - Add `saveTo(Path dir, String name)`, which resolves, normalizes and real-path-checks
      containment the way `serveStaticFile` does. Same for `Result.fileUnder(Path base, String name)`.
    - Fix `FileUploadTest` to use the safe API, and add a warning on `filename()` in
      `BRACE-AGENTS.md`.
  - **Tests.**
    - Traversal, absolute, backslash and empty names are each confined or rejected.
    - A symlinked target dir escaping the base is rejected.
  - **Tier: small.**

- [x] **M9: `req.json()`/`bodyAs()`/`requireJson()` bind request JSON into `@Entity` classes**, `Request.java:379-388, 431-437`, `BRACE-AGENTS.md:259-261`
  - **Verified.** `{"email":"e@x","role":"admin","id":1,"passwordHash":"p"}` deserialized into
    an `Account` entity with `role=admin` and `id=1`. A following `db.update(u)` overwrites
    row 1. (The record-only `req.form`/`req.jsonForm` are already safe.)
  - **Fix.**
    - `bodyAs` throws when the target class is annotated `@jakarta.persistence.Entity`
      (anywhere, not only in dev), with a message pointing at `jsonForm(Record)` + `apply()`.
      This mirrors the existing output-side check in `Json.of`.
    - Add a short "binding" rule to the docs.
  - **Tests.**
    - `req.json(EntityClass)` throws with the guidance message.
    - Records and DTOs are unaffected.
  - **Breaking** for apps that bind into entities today. Needs a migration-guide entry with a
    before/after.
  - **Tier: small.**

- [x] **M10: Sessions can't be revoked or rotated, and login doesn't rotate the CSRF token**, `Session.java:300-314`, `Csrf.java` (`ensureToken`), `SessionOptions.java:195`, `BraceHandler.parseCookieValue`
  - **What's fine.** AES-256-GCM with random nonces, HttpOnly, SameSite=Lax, `Secure`
    resolved per request, and 256-bit CSRF tokens.
  - **Gaps.**
    - Logout only clears the client copy. A copied cookie stays valid until `_exp` (14 days by
      default, extended on each write).
    - There is no per-user revocation (password change, "log out everywhere").
    - There is no graceful secret rotation (no old-key list).
    - Roles stored in the session (encouraged by the `Session` Javadoc) stay stale until
      expiry.
    - There is no `session.regenerate()`, so the pre-login CSRF token survives login.
    - The cookie name has no `__Host-` prefix and the first matching cookie wins, so cookie
      tossing from a sibling subdomain can plant a session and token.
    - Legacy cookies without `_exp` are still accepted.
  - **Fix.**
    1. Add `session.regenerate()`: clears `_csrf`, re-mints on next use, and marks the session
       modified. Use it in the documented login and logout patterns.
    2. Add a session validator hook, `app.sessionValidator((session, db) -> boolean)`, run once
       per request that resolves a session, for a `session_version`/epoch check. Document the
       pattern.
    3. Add `SessionOptions.previousSecrets(String...)`: decrypt with any of them, always
       re-encrypt with the current one.
    4. Add the `__Host-` prefix: write `__Host-brace_session` when Secure and `Path=/` and no
       `Domain`. The old name is not read, so users are logged out once on deploy.
    5. Add an absolute lifetime (`_iat` plus `SessionOptions.absoluteMaxAge`).
    6. Reject cookies without `_exp`.
  - **Tests.** One per sub-item:
    - The token changes across `regenerate()`.
    - A validator returning false yields an empty session.
    - Previous-secret decrypt works and re-mints with the new secret.
    - `__Host-` is written only when its prerequisites hold.
    - The absolute cap is enforced.
    - An expiry-less cookie is rejected.
  - **Breaking:** the cookie rename (one forced logout) and expiry-less rejection. Needs
    migration-guide entries.
  - **Tier: frontier** (crypto and session invariants).

- [x] **M11: `@Min`/`@Max` truncate to long, so fractional, negative-fraction and NaN values pass**, `FormBinder.java:190-197`
  - **Verified.**
    - `@Min(0) BigDecimal -0.99` passes, and so do `@Min(0) double -0.9` and `NaN`.
    - `@Max(100) double 100.99` passes.
    - `@Min(0) BigDecimal 1e999999999` passes.
  - **Attack.** A negative amount on a refund or transfer form passes `@Min(0)`.
  - **Fix.**
    - Compare via `new BigDecimal(raw)` (or `Double.compare`) against the bound.
    - Reject NaN and ±Infinity for `float`/`double`/`Float`/`Double`.
    - Optionally cap `BigDecimal` scale and precision (e.g. `@MaxDigits`).
  - **Tests.** Each value above produces an error, and integral edge values (`0`, `-1`,
    `Long.MAX_VALUE`) behave as before.
  - **Tier: small.**

- [ ] **M12: Dependency CVEs (verified via OSV)**, `pom.xml`
  - **jackson-core 2.18.6** (fixed in 2.18.11):
    - GHSA-p6pp-m3f8-5c89 / CVE-2026-89407 (HIGH): quadratic number-pattern backtracking,
      reachable through `req.json()`. A 20,000-character string bound to a
      `Double`/`BigDecimal` field took about 1.5 s per parse, within the 10 MB body cap.
    - GHSA-7hhh-6rmp-j9qf / CVE-2026-89425 and GHSA-r7wm-3cxj-wff9 / CVE-2026-68494: parser
      modes Brace doesn't use for bodies.
  - **jackson-databind 2.18.6:** 13 advisories. The polymorphic-typing ones need default
    typing, which Brace doesn't enable. Upgrade together with core.
  - **Jetty 12.0.33** (fixed in 12.0.36):
    - GHSA-w7x5-g22v-xqhr / CVE-2026-8384 (path-parameter traversal): couldn't be triggered
      through Brace's router or static serving.
    - GHSA-f4v5-65jj-pcr2 / CVE-2026-10051 (trailer leakage on HTTP/1.1 keep-alive).
    - GHSA-7p3p-8qv8-m2vh / CVE-2026-6790 (HTTP/2 and HTTP/3 only).
  - **pgjdbc 42.7.10** (fixed in 42.7.12): CVE-2026-42198 and CVE-2026-54291, both server or
    MITM side. Low relevance with Postgres on the same host.
  - **Fix.**
    - Bump Jackson to 2.18.11+, Jetty to 12.0.36+ and pgjdbc to 42.7.12+.
    - Add an OSV-Scanner (or OWASP dependency-check) step that fails on HIGH/CRITICAL to the
      merge gate.
  - **Tests.** Full `mvn verify`. Re-run the jackson number-string timing check (under 50 ms).
  - **Progress.** Dependency bumps landed: Jetty 12.0.40, Jackson 2.18.12, pgjdbc 42.7.14, all
    clean on OSV; the timing check is under 50 ms for every probe. The OSV gate remains (Phase 5),
    so the box stays open.
  - **Tier: small** for the bumps; **mid** for the CI gate.

- [ ] **M13: The generated container is root, unpinned and shell-bearing, and no hardened runtime config is generated**, `ProjectGenerator.java:386-420`, `Brace.java:1105-1106`
  - **What's wrong.**
    - `FROM eclipse-temurin:25-jre` is a floating tag on Ubuntu, so the runtime has a shell
      and apt.
    - No `USER`, so it runs as root.
    - `ENTRYPOINT ["sh","-c", …]` needs the shell.
    - No HEALTHCHECK, and no unauthenticated health route exists.
    - No `.dockerignore`.
    - No compose file, so nothing sets `read_only`, `cap_drop`, `no-new-privileges` or
      per-app networks.
    - The app listens on `0.0.0.0`.
  - **What's good.** Secrets come from env vars, and the app fails closed without
    `SESSION_SECRET`.
  - **Fix.**
    - Multi-stage Dockerfile ending in a distroless nonroot Java 25 runtime (or temurin-alpine
      plus `USER 10001`), pinned by digest.
    - Exec-form `ENTRYPOINT ["java", "-Dbrace.mode=prod", "-jar", "app.jar"]`, with JVM flags
      via `JAVA_TOOL_OPTIONS`.
    - Built-in `GET /__brace/health` (liveness, no data, exempt from analytics), plus a
      HEALTHCHECK or Dokploy health-path note.
    - Generate a `.dockerignore`.
    - Generate a `compose.yaml` reference:
      - `read_only: true`, with a `tmpfs` for `/tmp` (the upload spill dir is under
        `java.io.tmpdir`);
      - `cap_drop: [ALL]` and `security_opt: [no-new-privileges:true]`;
      - a dedicated network;
      - Postgres with no published ports;
      - secrets from Dokploy env or Docker secrets.
    - Add `app.host(String)` for binding.
  - **Tests.**
    - `ProjectGeneratorTest` asserts the `USER`/digest/exec-form lines and the presence of
      `.dockerignore` and `compose.yaml`.
    - A `tests/cli` smoke test builds the image and runs it read-only as non-root, where Docker
      is available.
  - **Tier: mid.**

- [x] **M14: The CLI token cache isn't keyed by server, and ops auth signatures aren't bound to an audience**, `CliAuth.java:52-67, 155-175`, `CliConfig.java:19-63`, `OpsKeys.java:79-81`
  - **What's wrong.**
    - The bearer token is cached at `target/.brace-token` regardless of `cfg.url()`, and
      `ops.<env>.url` comes from the committed `.brace` file.
    - `v2AuthMessage` signs only publicKey, timestamp and nonce.
  - **Attack.** A malicious PR changes `ops.prod.url`. A reviewer runs `brace status`, and the
    cached prod token (up to 1 h) goes to the attacker, or a fresh signature is relayed to
    real prod within ±30 s.
  - **Fix.**
    - Key the token cache by normalized origin.
    - Add ops auth **v3**: the signed message includes the target origin, and the server checks
      it against its effective host (via `ProxyHeaders.effectiveHost`). Remove v2 in the same
      release (401 naming the cause, as v1 is handled today).
    - The CLI prints a warning when a `.brace` ops URL differs from the last one it
      authenticated to.
  - **Tests.**
    - A cached token is not sent to a different origin.
    - A v3 signature for origin A is rejected by origin B.
    - v2 is rejected with a 401 that names the cause.
  - **Breaking** for old CLIs: upgrade the CLI with the server.
  - **Tier: frontier** (auth protocol change).

## Low

- [x] **L1: Version disclosure, and Jetty error pages bypass Brace's headers**, `Brace.java:1104-1107`
  - **Verified.**
    - `Server: Jetty(12.0.33)` appears on every response.
    - Jetty's own HTML error pages ("Powered by Jetty:// 12.0.33") answer requests Jetty
      rejects before `BraceHandler` runs, e.g. `//app/secret` → 400 "Ambiguous URI empty
      segment". No after-middleware or security headers are applied.
  - **Fix.**
    - Set `HttpConfiguration.setSendServerVersion(false)` and `setSendXPoweredBy(false)`.
    - Install a Jetty `ErrorHandler` that writes Brace-style plain-text errors with the static
      security headers.
  - **Tests.** No `Server` header; a 400 from Jetty is plain text with no version.
  - **Tier: small.**

- [x] **L2: Stack traces are stored and served unredacted**, `BraceHandler.java:518, 532`, `Stats.java:118-138`, `ErrorStore`
  - **What's wrong.** `Redactor.redactMessage` is applied to the message, but
    `stackTraceToString(e)` embeds the same message and every `Caused by:` message verbatim
    into `/ops/status` and `/ops/errors/{id}`. Ops READ tokens are routinely handed to on-call
    agents.
  - **Fix.** Redact the stack trace's message lines (the first line and each `Caused by:`)
    through `Redactor.redactMessage` at the `Stats.recordError` and `ErrorStore.record` sinks.
  - **Tests.** A secret-shaped token in a nested cause's message doesn't appear in
    `/ops/errors/{id}`.
  - **Tier: small.**

- [x] **L3: The ops dashboard embeds a live bearer token in page HTML**, `OpsDashboard.java:151, 610, 704`
  - **What's wrong.** The token sits in `hx-headers`. Any XSS on the app origin that runs in an
    operator's browser can `fetch('/ops/dashboard')` (same-origin, so the Strict cookie is
    sent) and extract a token up to CONTROL scope.
  - **Fix.** Make dashboard htmx calls cookie-only, as `/ops/analytics` already does. Drop the
    token from the page. Document hosting ops on a separate hostname for high-value apps.
  - **Tests.** The dashboard HTML contains no `Bearer`, and polling still works with the cookie.
  - **Tier: small** (do it alongside M6's dashboard JS move).

- [x] **L4: Ops tokens survive key revocation**, `OpsHandler.java:858-873`, `Brace.java:256-262`
  - **What's wrong.** `authenticate` checks only the HMAC, not that the token's `kid` is still
    in `ops-authorized-keys`. The token secret derives from the session secret, so 24 h bearer
    and 8 h cookie tokens survive key removal and restarts.
  - **Fix.** Reject claims whose `kid` isn't a fingerprint of a currently authorized key.
  - **Tests.** Remove a key and restart: its outstanding token gets 401.
  - **Tier: small.**

- [ ] **L5: The scaffold sets no `trustedProxies`, and the docs' suggested CIDRs trust every container**, `ProjectGenerator.java:191-196`, `BRACE-AGENTS.md:69, 944`
  - **What's wrong.**
    - Behind Dokploy's Traefik, `req.ip()` is the proxy for every client. `RateLimiter.perIp`
      becomes one global bucket (one attacker throttles everyone's login), and analytics
      collapses visitors.
    - The documented `trustedProxies("10.0.0.0/8","172.16.0.0/12")` lets any container on the
      host forge `X-Forwarded-For`.
  - **Fix.**
    - One-time runtime warning when a private-range peer sends `X-Forwarded-For` and no proxies
      are configured.
    - Scaffold reads `TRUSTED_PROXIES` from env.
    - Docs show the specific Traefik network CIDR.
  - **Tests.** The warning fires once.
  - **Tier: small.**

- [ ] **L6: NOTIFY messages are unauthenticated, and the channel is shared across apps on a database**, `PostgresMessageBus.java:34, 166, 188-207`, `WsRegistry.java:69-81`
  - **What's wrong.** Any role that can connect can `NOTIFY brace_ws, '{"r":"room","m":"…"}'`
    and push frames to room members, which is XSS-like when broadcasts are HTML. Two Brace apps
    on one database leak broadcasts to each other's same-named rooms. Injection itself is
    structural.
  - **Fix.**
    - HMAC the payload (key derived from the session/ops secret) and drop unsigned messages.
    - Namespace the channel per app (`brace_ws_<hash(app id)>`).
  - **Tests.**
    - An unsigned NOTIFY isn't delivered.
    - Two apps with different secrets don't cross-deliver (Postgres IT).
  - **Tier: mid.**

- [x] **L7: The shared-cache deserializer trusts the class name stored in the row**, `Cache.java:119, 269-279`
  - **What's wrong.** `getOrSet` passes `expected=null`, so the type check is skipped. There's
    no default typing, so this is defense in depth against anyone who can write `brace_cache`.
  - **Fix.** Require a `Class<T>` in `getOrSet` (or infer it from the supplier's first value)
    and allowlist the stored class.
  - **Tests.** A row with a foreign class name isn't deserialized.
  - **Tier: small.**

- [ ] **L8: Self-update and install downloads are verified by TLS only**, `Toolchains.java:106-125`, `install.sh`, `bin/brace` (`fetch_toolchain`)
  - **What's wrong.** There's no checksum or signature, and `BRACE_RELEASE_BASE` accepts
    `http://`. (The zip-slip guard is correct.)
  - **Fix.**
    - Publish `SHA256SUMS` plus a signature (minisign or sigstore) per release, and verify it
      before unpacking in all three paths.
    - Require `https://` unless an explicit insecure flag is set.
  - **Tests.**
    - A tampered zip is rejected.
    - An `http://` base is rejected.
  - **Tier: mid** (touches the release process; see `RELEASE.md`).

- [x] **L9: HTML responses carry no charset**, `View.java:39`, `Result.java:264`
  - **What's wrong.** The type is `text/html` alone. Layout-less pages are open to
    encoding-sniffing attacks: ISO-2022-JP `ESC ( J` undoes JTE's `\"` escaping inside
    `<script>`.
  - **Fix.** Use `text/html; charset=utf-8` (and `text/plain; charset=utf-8` for the plain
    results).
  - **Tests.** Update `TemplateTest.contentTypeIsHtml`, and assert the charset on `View`,
    `Result.html` and `Result.text`.
  - **Tier: small.**

- [x] **L10: `Result.html(String)` is a raw-HTML sink documented with no warning**, `Result.java:264`, `BRACE-AGENTS.md:296`
  - **Fix.**
    - Add a doc warning ("never concatenate request data; use `View`").
    - Add `Html.escape(String)` and an `html(gg.jte.Content)` overload.
  - **Tier: small.**

- [x] **L11: Mutating GETs aren't discouraged, and static mappings serve dotfiles**, `BraceHandler.java:436-447, 1105-1200`
  - **What's wrong.**
    - GET (and HEAD, via its fallback to the GET route) `DbHandler` routes run in a read-write
      transaction. With `SameSite=Lax`, a top-level cross-site GET carries the cookie, so a
      state-changing GET is CSRF-able, and nothing nudges developers away from writing one.
    - Separately, `.env`, `.git/…` and other dotfiles under a mapped static directory are
      served.
  - **Fix.**
    - Dev-mode warning when a GET request's transaction performed an insert, update or delete
      (`Database` already counts statements).
    - `serveStaticFile` returns 404 for any `.`-prefixed segment (except `.well-known`).
  - **Tests.**
    - A dotfile returns 404 and `.well-known/x` is served.
    - The dev warning fires on a writing GET.
  - **Tier: small.**

## Not confirmed

- **No cap on form-parameter count.** `Request.parsePairs` is a custom parser, so Jetty's
  `maxFormKeys` doesn't apply. It will build a map of up to about 1M distinct keys from a
  10 MB body. The heap cost per request was not measured. (The hash-collision angle was
  cleared in 2026-07.)
- **Mailer.**
  - `InternetAddress.parse(email.to)` accepts comma lists, so a user-supplied `to` could add
    recipients.
  - `mail.smtp.starttls.required` isn't set, so a STARTTLS strip would downgrade SMTP AUTH.
  - Both were read in code and not exercised.
- **Terminal and agent injection via the CLI.** `brace errors`, `brace check --json` and
  analytics output print attacker-influenced strings (error messages, referrer hosts, routes)
  without stripping C1 or ESC characters. Not tested whether Jetty passes a raw ESC through in
  header values.
- **CLI handling of server-returned fields.** A sub-review of this was cut off before it
  finished. Treat it as **not reviewed**, not as clean.
- **`View.of(<user input>)`.** Template-name traversal was not checked.
- **`Paged` links.** They are built from the raw `req.path()`. A protocol-relative `//host`
  link would need an empty-segment path to match a route, and Jetty rejects those with a 400.
- **htmx `historyCacheSize:10`.** With `hx-boost`/`hx-push-url`, DOM snapshots (CSRF fields,
  PII) persist in localStorage. Brace itself doesn't use them.
- **bcrypt.** `Passwords.hash` silently truncates input at 72 bytes. This is not exploitable;
  it only reduces entropy for very long passphrases.

## Checked and cleared

- **Template escaping.** JTE `ContentType.Html` is used in all three template paths
  (`TemplateEngine.java:26, 29`, `TemplatePrecompiler.java:43-45`). There is no `$unsafe`
  anywhere in the repo, scaffold or docs. `<script>` contexts are JS-escaped including
  `</script>`. `on*` attributes are JS-escaped.
- **Framework HTML.** Every interpolation in `OpsDashboard`/`AnalyticsDashboard` goes through
  `esc()`. The analytics `range` is allowlisted.
- **Path matching.** The router and middleware both match the raw path, so `%61pp`, `;param` and
  `//` variants fail closed (404 or 400). Verified on a live server. Trailing-slash
  canonicalization runs before the guards.
- **Framework SQL.**
  - `Counters`, `ErrorStore`, `JobPoller`, `JobScheduler`, `PostgresBackend` (the LIKE escape is
    correct), `PostgresRegressionStore` and the analytics queries are all parameterized or use
    constant SQL.
  - The field-name helpers go through `requireValidFieldIdentifier`.
- **LISTEN/NOTIFY injection.** The channel name is constant, payloads go through `pg_notify`
  with bound parameters, the 7,900-byte UTF-8 limit spills to a table, and received payloads are
  parsed into a plain `Map`.
- **Deserialization.** No default typing, `@JsonTypeInfo` or `ObjectInputStream`. Durable jobs
  require `DurableJob.isAssignableFrom`.
- **Body handling.**
  - The body is read lazily, after the guards.
  - An over-limit Content-Length is rejected before reading, and the read itself is bounded.
  - Multipart parts spill to a 700-permission directory that is cleaned up on every exit path.
  - Size violations return 413.
- **Production errors.** 500s are a generic "Internal Server Error". The dev 404 hint is
  `text/plain`. Dev mode is on only when `brace.mode` is exactly `dev`.
- **Ops auth.**
  - Ed25519 v2 with key binding, a nonce and a ±30 s window.
  - The READ→CONTROL escalation is closed.
  - Tokens are kept out of URLs, except the 60 s exchange.
  - The cookie is `SameSite=Strict` and scoped to `/ops`.
  - Responses are `no-store`.
- **Other.**
  - `Redirect.toLocal` and `Url.to` encoding hold.
  - SSE event names and ids reject CR, LF and NUL.
  - `Result.download`'s `Content-Disposition` is sanitized.
  - `Storage` keys can't change the host or path.
  - Every `ProcessBuilder` call is argv-only, and the Windows browser launch is guarded.
  - The toolchain zip extraction guards against zip-slip.
