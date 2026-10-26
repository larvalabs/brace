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
`@jakarta.persistence.Entity`, in every mode. Records, DTOs and `Map` bind as before.

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
