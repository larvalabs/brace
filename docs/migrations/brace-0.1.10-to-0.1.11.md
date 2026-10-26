# Migrating from Brace 0.1.10 → 0.1.11

This release carries the fixes from the 2026-10 security review, several of which are
**breaking**. Rows marked breaking in the index need a code change. It also adds opt-in
server-side analytics, gzips responses by default, masks the CSRF token rendered into pages, and
`HEAD` requests now reach `GET` routes instead of returning 404.

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|
| Server-side page-view analytics | new, opt-in | none; add `.analytics()` to use it | [§](#new-server-side-page-view-analytics) |
| Gzip response compression | new, on by default | none; `.compression(false)` to opt out | [§](#new-gzip-response-compression-on-by-default) |
| CSRF token masked in pages | security | tests that compare `${csrfField}` to `Csrf.getToken` | [§](#security-csrf-token-masked-per-render) |
| Framework migration V18 | schema | none (applied automatically) | [§](#schema-framework-migration-v18) |
| `HEAD` requests reach `GET` routes | fix | ops tooling: expect `HEAD <pattern>` rows | [§](#fix-head-requests-reach-get-routes) |
| Request rates per second on busy apps | display | none | [§](#display-request-rates-per-second-on-busy-apps) |
| Brace version on the ops dashboard and in `/ops/status` | display | none | [§](#display-brace-version-on-the-ops-dashboard) |
| `csrfField` is `gg.jte.Content`; new `csrfToken` | security, **breaking** for templates declaring `String csrfField` | change the `@param` type to `gg.jte.Content` | [§](#breaking-csrffield-is-content-new-csrftoken) |
| `req.json`/`bodyAs`/`requireJson` refuse `@Entity` classes | security, **breaking** | bind a record with `jsonForm` and `apply()` it | [§](#breaking-reqjson-refuses-entity-classes) |
| `@Min`/`@Max` compare exactly; NaN and Infinity rejected | security | none, unless a form relied on fractions slipping past a bound | [§](#security-min-and-max-compare-exactly) |
| HTML and text responses declare `charset=utf-8` | security | tests that assert the exact `Content-Type` string | [§](#security-html-and-text-responses-declare-charsetutf-8) |
| `Html.escape`; `Result.html` documented as a raw sink | new | none | [§](#new-htmlescape) |
| `UploadedFile.safeFilename()`, `saveTo(dir, name)`, `Result.fileUnder` | new, security | switch uploads saved via `dir.resolve(file.filename())` | [§](#new-confined-upload-saving-and-file-serving) |
| No `Server` header; plain-text errors for requests Jetty rejects | security | update monitors that match on `Server` or Jetty's HTML error text | [§](#security-no-server-header-plain-text-errors-for-rejected-requests) |
| Stack traces in `/ops` are redacted like messages | security | none | [§](#security-stack-traces-are-redacted-like-messages) |
| Static dotfiles return 404; dev warning for GETs that write | security | serve a deliberate dotfile from a route instead | [§](#security-static-dotfiles-return-404-dev-warning-for-gets-that-write) |
| The `brace` launcher refuses an invalid pinned version | security | fix a `<brace.version>` that isn't `MAJOR.MINOR.PATCH[-SUFFIX]` | [§](#security-the-brace-launcher-refuses-an-invalid-pinned-version) |
| Removing an ops key revokes its outstanding tokens | security | none | [§](#security-removing-an-ops-key-revokes-its-outstanding-tokens) |
| Shared-cache `getOrSet` only reads classes this process has written | security | none | [§](#security-shared-cache-getorset-only-reads-classes-this-process-has-written) |
| The page cache no longer stores pages that carry a CSRF token or flash | security | none | [§](#security-the-page-cache-no-longer-stores-per-visitor-pages) |
| Every route needs an access requirement or `.publicRoute()`; `requireSession` removed | security, **breaking** | add `defaultAccess(...)`, mark public routes | [§](#breaking-every-route-needs-an-access-requirement) |
| WebSocket upgrades run `before()` and access requirements; `app.ws(...)` returns a `RouteConfig` | security, **breaking** | split builder chains that continue after `.ws(...)` | [§](#breaking-websocket-upgrades-are-guarded) |
| `.csrf(false)` on a cookie-session route fails `start()`; cross-site mutating requests refused; `${csrfMeta}` + `csrf-htmx.js` | security, **breaking** | remove the opt-out or give `.csrf(false, "reason")` | [§](#breaking-csrf-opt-outs-are-checked-and-cross-site-requests-refused) |
| Session cookie is `__Host-brace_session` in production; cookies without `_exp` rejected; absolute lifetime; `regenerate()`, `sessionValidator`, `previousSecrets` | security, **breaking** | users are logged out once on deploy; call `session.regenerate()` at login/logout | [§](#breaking-session-cookie-hardening) |
| Jetty, Jackson and PostgreSQL driver security updates | security | none, unless your own pom pins these artifacts | [§](#security-dependency-updates) |

## New: server-side page-view analytics

Visitors, pageviews, top pages, sources, devices and browsers, counted from the requests the app
already serves. There is no tracking script and no cookie, and no IP address or user agent is
stored. Viewed at `/ops/analytics`, with `brace analytics`, and as a "Visitors today" card on the
ops dashboard. Off unless you turn it on.

Before:

```java
var app = Brace.app()
    .database(db)
    .ops("ops-authorized-keys");
```

After:

```java
var app = Brace.app()
    .database(db)
    .ops("ops-authorized-keys")
    .analytics(Analytics.options().timezone("America/New_York"));

// Optional, per route:
app.get("/invite/{code}", ctrl::invite).analytics(false);    // don't record URLs that carry a secret
app.get("/u/{username}", ctrl::profile).analyticsByRoute();  // count as /u/{username}
```

Things to check when you turn it on:

- **It needs a database and ops.** `start()` throws with a message naming the missing piece.
- **Behind a reverse proxy, configure `trustedProxies(...)`.** Visitors are told apart by IP and
  user agent; without trusted proxies every visitor has the proxy's IP. The app logs a warning
  when it sees `X-Forwarded-For` from an untrusted peer.
- **Routes with secrets in the URL.** Parameters named like a secret (`{token}`, `{apiKey}`, ...)
  are stored as their placeholder, and long random-looking segments are redacted, using the same
  rules as error records. A short code under an ordinary name (`/invite/{code}`) is not caught:
  mark those routes `.analytics(false)`.
- **Numbers will be higher than a JavaScript tracker's** for the same site: ad blockers no longer
  hide anyone, and filtering bots by headers is less exact than requiring JavaScript. The page
  shows how many requests each filter rejected.
- **Serve the site over HTTPS.** A request claiming a modern Chrome, Edge or Firefox user agent
  without the `Sec-Fetch-Mode` header those browsers always send is counted as a bot, which
  catches scrapers wearing a browser's user agent. Browsers only send that header over HTTPS (and
  to localhost), so on a site served over plain HTTP those visitors wouldn't be counted.
- **Comparisons appear after a while.** "vs the previous 30 days" needs 60 days of data, so for
  the first weeks the page says "no comparison yet".
- **History is kept as daily summaries.** A nightly job (`analytics-rollup`) summarizes each
  completed day into `brace_analytics_daily`, kept indefinitely, and then deletes raw page views
  older than `rawRetention` (35 days by default). The page and CLI have a 12-month range.

The full guide, with what each number means, is `docs/analytics.md` in the brace repo.

New CLI and endpoints (all need a `read` ops token): `brace analytics [--range today|7d|30d|12mo]`,
`brace ops dashboard --analytics`, `GET /ops/analytics`, `GET /ops/analytics/data`,
`POST /ops/analytics/ignore`, and an `analytics` block in `/ops/status` when enabled.

## New: gzip response compression, on by default

Brace now gzips responses for clients that send `Accept-Encoding: gzip`. Before, it left that to
the reverse proxy, but common setups don't compress by default (Dokploy's Traefik has no
compress middleware unless you add one), so the hop between the app and the CDN carried full-size
HTML. A 91 KB list page is about 5 KB gzipped, and that hop is what the server's egress bill
counts.

Nothing to change. To opt out:

```java
var app = Brace.app()
    .compression(false);   // e.g. a proxy between you and the app already compresses
```

What gets compressed: text responses (HTML, JSON, CSS, JS, SVG, ...) of 1 KB or more, on GET,
POST, PUT, PATCH and DELETE. What doesn't:

- **Event streams** (`Result.sse`). They keep arriving an event at a time.
- **Range requests.** A 206 is served from the file's uncompressed bytes, as before.
- **Already-compressed types:** images other than SVG, audio, video, zip/gzip/brotli/zstd
  archives, and web fonts.
- **Bodies under 1 KB**, and every response to a client that didn't ask for gzip.
- **Request bodies.** A request with `Content-Encoding: gzip` is not inflated, so
  `maxUploadSize` still bounds what a handler reads.

Things you may notice:

- **`Vary: Accept-Encoding`** on compressible responses, merged with `Vary: HX-Request` on htmx
  responses. Tests that assert an exact `Vary` value, or no `Vary` at all, need updating (or
  `.compression(false)` on that test app). `TestApp`'s client sends no `Accept-Encoding`, so
  response bodies in tests are unchanged.
- **ETags on compressed responses end in `--gzip`** (`"1a2b-c3d4--gzip"`). A browser sending
  that back in `If-None-Match` still gets a 304.
- **No `Content-Length` on compressed responses.** They are sent chunked, so a large compressible
  download (a CSV `Result.file` or `Result.download`) no longer shows the browser a total size.
  There is no per-route opt-out yet; `.compression(false)` is app-wide.
- **`Result.stream` writers still stream**: each write is flushed through the compressor, so
  NDJSON and progress output arrive as written.

Behind Cloudflare or another CDN, the CDN still decides what the browser receives. This changes
the CDN-to-origin hop, so expect origin egress to drop and nothing visible to change for users.

## Security: CSRF token masked per render

`${csrfField}` used to render the session's CSRF token as is, identical on every page. With
responses now compressed, a secret repeated on every page next to attacker-controlled text is
the setup for BREACH, which reads secrets out of compressed response sizes. The field now
carries a masked token, `base64url(mask || token XOR mask)` with a fresh random mask per render,
so the value differs every time. Any masked value validates, and so does the raw token, so forms,
`X-CSRF-Token` headers built from `Csrf.getToken(session)`, and `TestApp`'s `*WithCsrf` helpers
all work unchanged.

What to check:

- **A test that asserts the hidden field contains `Csrf.getToken(session)`** now fails: the
  field's value is masked. Assert that the rendered value is accepted by a POST instead.
- **A `<meta>` tag (or anything else) that writes the token into a page** should use the new
  `Csrf.maskedToken(session)`:

  ```html
  <!-- before -->
  <meta name="csrf-token" content="${Csrf.getToken(session)}">
  <!-- after -->
  <meta name="csrf-token" content="${Csrf.maskedToken(session)}">
  ```

Other secrets you render into pages that also echo request input (API keys on a settings page,
for example) are not covered. See "Response Compression" in `docs/SECURITY.md`.

## Schema: framework migration V18

`V18__brace_analytics.sql` creates `brace_analytics_pageviews`, `brace_analytics_rejects`,
`brace_analytics_daily` and `brace_analytics_salts` on both H2 and Postgres. The tables are created whether or not the app
enables analytics, and stay empty unless it does. Nothing to do.

## Fix: `HEAD` requests reach `GET` routes

Before, a `HEAD` request returned 404 for every route registered with `app.get(...)`, because
routes were matched on the exact method. Only `staticFiles` mappings answered it. Uptime
monitors, link checkers and some link-preview fetchers send `HEAD`, so they saw a site that was
up as down.

```
$ curl -sI -o /dev/null -w '%{http_code}\n' https://example.com/
404   # 0.1.10
200   # 0.1.11
```

Now a `HEAD` request with no `HEAD` route of its own runs the matching `GET` route and gets the
`GET`'s status and headers, `Content-Length` and `ETag` included, with no body (RFC 9110
§9.3.2). Details:

- The handler runs as it does for `GET`. `req.method()` returns `"HEAD"`, so a handler that
  wants to skip expensive work for `HEAD` can check it.
- Streamed bodies (`Result.file`, `Result.stream`, `Result.sse`) are not produced for `HEAD`. An
  SSE producer never starts, and a file is not read.
- A route wrapped in `cache.wrap(...)` serves `HEAD` from the same cache entry as `GET`.
- `/ops/routes` and the request log record these requests as `HEAD`, under their own
  `HEAD <pattern>` row. Expect new rows if a monitor polls your site.
- Analytics does not count them: only `GET` requests are page-view candidates, so a monitor's
  `HEAD` checks never show up as visitors.

No code changes are needed.

## Display: request rates per second on busy apps

The ops dashboard and `brace status` show request rates per second once the app averages at
least one request a second over the last hour, and per minute below that. The request card,
the request sparkline and Top Routes switch together. Hover the card for the last minute's
raw count. The `/ops/status` JSON is unchanged (`requestsPerMinute`, `perMinute`), so scripts
and agents reading it need no change.

## Display: Brace version on the ops dashboard

The ops dashboard header shows the running Brace version next to the Java version, `/ops/status`
has it as `app.braceVersion`, and `brace status` prints it. A build from a JitPack tag reports
that tag's version, release candidates included (before, an rc build reported the previous
release's version).

## Security: dependency updates

Brace now ships with patched versions of three dependencies:

| Dependency | Before | After | Advisories fixed |
|---|---|---|---|
| Jetty | 12.0.33 | 12.0.40 | CVE-2026-8384 (path-parameter traversal), CVE-2026-10051 (trailer leakage across keep-alive requests), CVE-2026-6790 (HTTP/2 and HTTP/3 authority mismatch) |
| Jackson | 2.18.6 | 2.18.12 | CVE-2026-89407 (quadratic parsing of long numeric strings, reachable through `req.json()`), CVE-2026-89425, CVE-2026-68494, and the jackson-databind advisories for 2.18.6 |
| PostgreSQL JDBC | 42.7.10 | 42.7.14 | CVE-2026-42198, CVE-2026-54291 |

Nothing to change in app code. If your own `pom.xml` pins any of these artifacts to an older
version, drop the pin or raise it; an explicit pin overrides the version Brace brings in.

Visible difference: when Jetty rejects an ambiguous URI itself (for example `%2e%2e` in a path),
its built-in 400 page now echoes the request URI. Tests that assert a rejected path's name never
appears in the response body need to check the file's content instead.

## Breaking: `csrfField` is `Content`; new `csrfToken`

`${csrfField}` used to reach templates as a `String`. JTE escapes strings, so the documented
`${csrfField}` printed the hidden input as visible text and the form's POST was rejected with
403. The only workaround was `$unsafe{csrfField}`. The field is now a `gg.jte.Content` and
renders as markup.

Before:

```
@param String csrfField = null
<form method="post">$unsafe{csrfField} ...</form>
```

After:

```
@param gg.jte.Content csrfField = null
<form method="post">${csrfField} ...</form>
```

A template that still declares `@param String csrfField` fails at render time with a
`ClassCastException` naming the parameter. Change the type, and replace any `$unsafe{csrfField}`
with `${csrfField}`.

New: `csrfToken` is the bare masked token for a `<meta>` tag that fetch or htmx copy into the
`X-CSRF-Token` header:

```
@param String csrfToken = null
<meta name="csrf-token" content="${csrfToken}">
```

Like the hidden field, it differs on every render and every rendered value validates. Use it
instead of printing `Csrf.maskedToken(session)` yourself.

## Breaking: `req.json` refuses `@Entity` classes

Binding request JSON straight into an entity let a client set any public field. A body like
`{"email":"e@x","role":"admin","id":1,"passwordHash":"p"}` set all four, and a following
`db.update(user)` wrote them, overwriting row 1. `req.json`, `req.bodyAs` and `req.requireJson`
now throw `IllegalArgumentException` when the target class is annotated
`@jakarta.persistence.Entity`, or when an entity appears anywhere inside it (an array element
such as `User[]`, a record or DTO component, a list field), in every mode. Records, DTOs and
`Map` without entities inside bind as before.

Before:

```java
var user = req.json(User.class);   // the client controls id, role, passwordHash
db.update(user);
```

After:

```java
public record ProfileForm(@Required @Email String email, @Required String name) {}

// on the entity
public void apply(ProfileForm form) { this.email = form.email(); this.name = form.name(); }

// in the handler
var form = req.jsonForm(ProfileForm.class);
if (form.hasErrors()) return Result.json(Map.of("errors", form.allErrors()), 422);
var user = db.findOr404(User.class, req.longPathParam("id"));
user.apply(form.value());          // only the fields the form declares
db.update(user);
```

The exception message names the entity and points to this pattern.

## Security: `@Min` and `@Max` compare exactly

Bounds used to be checked after truncating the value to a `long`, so `@Min(0)` accepted
`-0.99` (as `BigDecimal` or `double`), `@Max(100)` accepted `100.99`, and `NaN` and
`BigDecimal 1e999999999` passed both. A negative amount on a refund or transfer form passed
`@Min(0)`.

Comparison is now exact: `BigDecimal` and `BigInteger` via `compareTo`, `double` and `float` as
doubles (`-0.0` still equals `0`), integral types unchanged. `float`, `double`, `Float` and
`Double` components also reject `NaN`, `±Infinity` and overflow (`1e999`) with the field error
"must be a finite number", whether or not a bound is declared. This applies to `req.form` and
`req.jsonForm` alike.

No code change is needed unless a form relied on a fractional value passing a bound it
actually violates; that input now gets a field error.

## Security: HTML and text responses declare `charset=utf-8`

`View`, `Result.html` and the plain-text results (`Result.text`, `notFound`, `error`,
`unauthorized`, `forbidden`, `badRequest`, `created`, `noContent`, `notModified`) now send
`text/html; charset=utf-8` and `text/plain; charset=utf-8` instead of a bare `text/html` /
`text/plain`. Without a declared charset, a page with no `<meta charset>` was open to
encoding sniffing, which can undo the template's escaping inside `<script>`.

Browsers render the same. Only code that compares the exact header string changes:

```java
// before
assertEquals("text/html", res.header("Content-Type"));
// after
assertEquals("text/html; charset=utf-8", res.header("Content-Type"));
```

Results that set their own content type (`Result.bytes`, `Result.stream`, `Result.file`, ...)
are unchanged.

## New: `Html.escape`

`Result.html(String)` sends its body as is, with no escaping, and is now documented that way.
For the rare hand-built snippet that has to include a value, `Html.escape(value)` escapes
`& < > " '` (null becomes the empty string). Rendering a `View` is still the right tool for
anything with request or database data in it.

```java
// before: reflected XSS
return Result.html("<p>Results for " + req.queryParam("q") + "</p>");
// after
return Result.html("<p>Results for " + Html.escape(req.queryParam("q")) + "</p>");
```

## New: confined upload saving and file serving

`UploadedFile.filename()` is the name the client sent, unmodified: it can be `../../etc/x`, an
absolute path, contain backslashes or control characters, or be empty. `dir.resolve(filename())`
writes wherever it points, and an absolute name replaces `dir` entirely. Three additions:

- `file.safeFilename()`: a single safe path component (basename after `/` or `\`, control
  characters stripped, leading dots trimmed, at most 255 UTF-8 bytes, never empty).
- `file.saveTo(Path dir, String name)`: writes under `dir` (created if missing) and throws
  `IllegalArgumentException` if `name` is empty or absolute or would leave `dir`, including
  through a symlink. Returns the path written.
- `Result.fileUnder(Path base, String name)`: streams `name` under `base`, or 404 if it escapes
  `base` or doesn't exist.

```java
// before
file.saveTo(uploadDir.resolve(file.filename()));
return Result.file(publicDir.resolve(req.pathParam("name")));
// after
file.saveTo(uploadDir, file.safeFilename());
return Result.fileUnder(publicDir, req.pathParam("name"));
```

`fileUnder` picks the content type from the extension, so an uploaded `.html` or `.svg` would
render in the browser. Serve user uploads with `Result.download(...)` instead.

## Security: no `Server` header; plain-text errors for rejected requests

Responses no longer carry `Server: Jetty(x.y.z)` or `X-Powered-By`. Requests that Jetty rejects
before Brace's handler runs (for example `//path`, `%2e%2e` path segments, malformed request
lines) used to get Jetty's HTML error page, which showed the Jetty version and echoed the request
URI. They now get a short `text/plain; charset=utf-8` body with only the reason phrase
(`Bad Request`) and `X-Content-Type-Options: nosniff`.

No code change. Update any monitor or test that matched on the `Server` header or on Jetty's
HTML error text.

## Security: stack traces are redacted like messages

Secret-shaped tokens (long base64url or hex strings, JWTs) in an exception's message were
redacted in the error's `message` field but appeared verbatim in its stack trace, which repeats
the message and every `Caused by:` message. The stack traces served by `/ops/status` and
`/ops/errors/{id}` are now redacted the same way, including `Caused by:` and `Suppressed:`
lines. Frame (`at …`) lines are unchanged.

No code change.

## Security: static dotfiles return 404; dev warning for GETs that write

A `staticFiles` mapping no longer serves any path with a segment starting with `.`: `.env`,
`.git/config`, `.htaccess` and `assets/.hidden/x` are 404. The one exception is a leading
`.well-known/`, so `/.well-known/security.txt` and ACME challenges still work. If you
deliberately served a dotfile, rename it or serve it from a route.

In dev mode (`brace.mode=dev`), Brace logs a warning once per route pattern when a GET or HEAD
request's transaction writes (`db.insert`, `update`, `delete`, `deleteBy`, `sql(...)`). Session
cookies are `SameSite=Lax` and CSRF checks cover mutating methods only, so a state-changing GET
can be triggered from another site. Move such handlers to POST, PUT or DELETE. Writes through
raw `db.jdbc(...)` are not detected. Nothing changes in prod.

## Security: the `brace` launcher refuses an invalid pinned version

The launcher used `<brace.version>` from the project's `pom.xml` as a directory name under its
toolchain cache and ran the classpath it found there. A cloned project could pin a value like
`../../../../proc/self/cwd` and make `brace status` run a jar from the repo itself. The launcher
now accepts only `MAJOR.MINOR.PATCH` with an optional `-SUFFIX` (`0.1.10`, `v0.1.10`,
`0.1.11-SNAPSHOT`) and otherwise exits with `Invalid <brace.version> in pom.xml: …` for every
command, `brace version` included. `install.sh` (`BRACE_VERSION` and the release `tag_name`)
and `brace self-update` apply the same rule.

No change for projects that pin an ordinary version. Upgrade the installed `brace` CLI
(`brace self-update`) to get the check: it lives in the launcher, not in the framework jar.

## Security: removing an ops key revokes its outstanding tokens

Ops tokens (bearer, up to 24 h; browser cookie, 8 h) stayed valid after their key was removed
from `ops-authorized-keys`, because the token secret is derived from the session secret and
survives a restart. A token is now honored only while the fingerprint of the key that minted it
is still in the file, which is read at startup: removing a key and redeploying revokes its
tokens. A token's scope is also capped at its key's current ceiling, so lowering a key to
`scope:read` turns its outstanding CONTROL tokens into READ ones. Tokens without a key id are
rejected.

No code change. Clients of a removed key get 401 and must authenticate with a remaining key.

## Security: shared-cache `getOrSet` only reads classes this process has written

`getOrSet` has no type argument, so with a serializing backend (`CacheBackend.postgres(...)`) it
deserialized into whatever class name the stored row carried; anyone able to write the
`brace_cache` table could pick the class. It now reads only classes this process has itself
written, and treats anything else as a miss. After a restart, or when another server wrote the
entry first, the first `getOrSet` for a given class recomputes and rewrites the entry; later
calls hit as before. `cache.get(key, Type.class)` is unchanged.

No code change.

## Security: the page cache no longer stores per-visitor pages

`cache.wrap(...)` stored the first visitor's rendered page and replayed it to everyone. A cached
page that rendered a form (`${csrfField}` or `${csrfToken}`) or a flash message therefore handed
the first visitor's CSRF token and flash text (which can include an email address) to every
later visitor, and every later visitor's form was rejected with 403.

A render whose output contains the visitor's CSRF token, or that carries a non-empty flash, is
now served to that visitor but not stored. Pages without a form or flash are cached exactly as
before, with or without sessions. In dev mode (`brace.mode=dev`) the first skipped render per
path logs a warning.

No code change. Cached routes that render a form now render on every request; cache the data
behind them instead, or drop the wrapper.

## Breaking: every route needs an access requirement

Routes were allow-by-default, and authentication was a separate list of path patterns
(`requireSession("/admin/*", …)`, `before("/api/*", …)`). Nothing tied a route to the guard it
needed, so a route registered outside the guarded prefix was silently public.

Access requirements now attach to routes, and `start()` (and `Brace.test()`) refuses to start
while any route has no requirement and isn't marked `.publicRoute()`. The error lists the
unmarked routes.

Before:

```java
app.requireSession("/app/*", "userId", "/login");
app.get("/", home::index);
app.post("/login", auth::login);
app.getRead("/app/posts", posts::index);
app.before("/admin/*", (req, session) ->
    "admin".equals(session.get("role")) ? null : Result.forbidden());
app.getRead("/admin/users", admin::users);
```

After:

```java
app.defaultAccess(Access.session("userId", "/login"));
app.get("/", home::index).publicRoute();
app.post("/login", auth::login).publicRoute();
app.getRead("/app/posts", posts::index);          // covered by the default
app.group("/admin", Access.check((req, session) ->
    "admin".equals(session.get("role")) ? null : Result.forbidden()),
    admin -> admin.getRead("/users", admin::users));
```

Steps:

1. Pick a default. An app with a login uses `defaultAccess(Access.session(key, loginUrl))`. An
   app with no login at all says so with `defaultAccess(Access.none())`.
2. Mark every page that must stay reachable without the default `.publicRoute()`: home,
   login, signup, password reset. Routes that authenticate some other way (a bearer-token API,
   a webhook that checks a signature) get that check instead of the default:
   `.access(Access.insteadOfDefault(Access.checkRequest(...)))`, or the same on their group.
3. Replace each `requireSession(pattern, key, url)` with the default or a group requirement.
   Replace each `before(pattern, (req, session) -> …)` that makes an access decision with
   `group(prefix, Access.check(...), …)` or `.access(Access.check(...))`. Keep `before(...)` for
   rate limiting and other non-access work; it no longer counts as auth.
4. Run the tests. `start()` lists any route you missed.

Behavior details:

- Requirements run after plain `before(...)` middleware and before the body is read and CSRF is
  checked, and before session-aware `before(...)` middleware.
- `Access.session` answers a page request with 302 to the login URL (as `requireSession` did),
  an htmx request with 401 and `HX-Redirect`, and a JSON request with 401.
- A path with no route now always 404s. A `requireSession("/admin/*", …)` used to redirect
  `/admin` even when no `/admin` route existed.
- The startup banner and `/ops/routes` show each route's access.

## Breaking: WebSocket upgrades are guarded

The WebSocket upgrade ran no `before(...)` middleware and no auth guard, so a socket under a
guarded path accepted anonymous clients. A WebSocket route now takes part in the access model:
it gets the default access unless marked `.publicRoute()`, takes `.access(...)`, and an
unmarked socket fails `start()`. The upgrade runs plain `before(...)` middleware, then the
`Origin` check, then the route's requirements; a rejection declines the handshake (a login
redirect becomes 401). With sessions on, `ctx.session()` is never null (an empty session when
there is no cookie). A declined cross-origin upgrade now gets its 403 immediately instead of
leaving the client waiting.

`app.ws(...)` returns a `RouteConfig` so it can take `.publicRoute()` and `.access(...)`.
Builder chains that continued after it need a separate statement:

```java
// before
var app = Brace.app().port(8080).ws("/live", Live::new).ws("/status", Status::new);
// after
var app = Brace.app().port(8080);
app.ws("/live", Live::new);
app.ws("/status", Status::new).publicRoute();
```

## Breaking: CSRF opt-outs are checked and cross-site requests refused

Three changes.

**`.csrf(false)` on a cookie-session route fails `start()`.** `.csrf(false)` is meant for
bearer-token routes, but nothing checked that, so it could silently switch CSRF off on a route
authenticated by the session cookie. Now a POST/PUT/DELETE/PATCH route with `.csrf(false)` whose
handler takes the `Session`, or that a session access requirement covers, fails `start()` with
the routes listed. Remove the opt-out and send the token, or, if the route is genuinely safe to
force, say why:

```java
// before
app.postSession("/logout", auth::logout).csrf(false);
// after
app.postSession("/logout", auth::logout).csrf(false, "sign-out is idempotent");
```

**Cross-site mutating requests are refused.** A POST/PUT/DELETE/PATCH to a CSRF-protected route
that the browser marks `Sec-Fetch-Site: cross-site`, or whose `Origin` names another host (or is
`null`), gets 403 before the body is read. This applies with or without Brace sessions, so an
app that sets its own auth cookie is now covered. Requests without either header (curl,
server-to-server) are unaffected, and so are `.csrf(false)` routes. A route that legitimately
receives cross-site browser POSTs (a payment provider's return URL, a SAML endpoint) needs
`.csrf(false)`, with a reason if it uses the session.

**htmx CSRF wiring.** Add `${csrfMeta}` to the layout's `<head>` (declare
`@param gg.jte.Content csrfMeta = null`) and `<script src="/__brace/csrf-htmx.js"></script>`
after htmx, and every non-GET htmx request carries the token. Replace any per-element
`hx-headers` CSRF wiring with it.

## Breaking: session cookie hardening

**Everyone is logged out once.** In production the session cookie is now named
`__Host-brace_session` (it was `brace_session`). A `__Host-` cookie can only be set by the exact
host over HTTPS, so a sibling subdomain can no longer plant a session or CSRF token. Requests
read only the name they would be given, so existing `brace_session` cookies are ignored after
the deploy and users sign in again. Plain-HTTP localhost keeps `brace_session`, so local
development and `Brace.test()` are unaffected. A custom `SessionOptions.path(...)` or
`.domain(...)` also keeps the old name, because the prefix requires `Path=/` and no `Domain`.
Code that reads the cookie by name (`req.cookie("brace_session")`, a proxy rule) needs the new
name.

**Cookies without `_exp` are rejected.** Cookies minted by 0.1.6 or earlier carry no `_exp`;
they were accepted through 0.1.10 and are now an empty session.

**Absolute lifetime.** `_exp` slides forward on every write, so a continuously used session
never expired. Sessions now also end 30 days after sign-in (or after `maxAge`, if longer),
however active. Set it with `SessionOptions.absoluteMaxAge(Duration)`.

New, and recommended:

```java
// login: drop the pre-login CSRF token and restart the absolute lifetime
session.regenerate();
session.set("userId", user.id);
session.set("sessionVersion", user.sessionVersion);

// logout
session.clear();
session.regenerate();

// revoke server-side: false makes the request anonymous and clears the cookie
app.sessionValidator(s -> !s.has("userId")
    || currentSessionVersion(s.get("userId")).equals(s.get("sessionVersion")));

// rotate the secret without logging anyone out
app.sessions(SessionOptions.of(newSecret).previousSecrets(oldSecret));
```

