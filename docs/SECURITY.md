# Brace Security Model

This document describes Brace's security features and best practices for building secure applications.

## Table of Contents

- [Authorization](#authorization)
- [SQL Injection](#sql-injection)
- [Sessions](#sessions)
- [CSRF Protection](#csrf-protection)
- [Cross-Site Scripting (XSS)](#cross-site-scripting-xss)
- [Content Security Policy](#content-security-policy)
- [Serialization](#serialization)
- [Open Redirects](#open-redirects)
- [Server-Side Request Forgery (SSRF)](#server-side-request-forgery-ssrf)
- [Trusted Proxies](#trusted-proxies)
- [Cookie Security](#cookie-security)
- [Static Files](#static-files)
- [Response Compression](#response-compression)
- [WebSockets](#websockets)
- [File Uploads](#file-uploads)
- [Rate Limiting](#rate-limiting)
- [Deploying in a Container](#deploying-in-a-container)
- [Installing and Updating Brace](#installing-and-updating-brace)
- [Ops Endpoints](#ops-endpoints)
- [Analytics](#analytics)
- [Outbound Mail](#outbound-mail)
- [Secrets Management](#secrets-management)

---

## Authorization

### Route access is required, not opt-in

Every route — HTTP and WebSocket — carries a list of access requirements, and `start()`
refuses to run while any route has none and isn't explicitly marked `.publicRoute()`. A route
therefore cannot become public by being registered outside a guarded path, or by its author
forgetting a guard: the app fails to start (and its tests fail) instead.

```java
app.defaultAccess(Access.session("userId", "/login"));  // applies to every route
app.get("/", home::index).publicRoute();                 // explicit, greppable opt-out
app.group("/admin", Access.check((req, s) ->
    "admin".equals(s.get("role")) ? null : Result.forbidden()), admin -> { ... });
```

Requirements are bound to the `Route` object at `start()`, not matched against the request
path, so a guard and a route can't disagree about a path: `/admin/x/`, `/admin/%78` and
`HEAD /admin/x` reach the same route and run the same requirements. They run after plain
`before(...)` middleware (so a rate limiter sheds load first) and before the request body is
read, so an unauthenticated client can't make the app buffer a body. Rejections go through the
normal response path, so security headers and session write-back apply.

A request whose raw path has a segment that is exactly `.` or `..` (`/items/../y`, `/one/.`)
is a 404 before any route, mapping, middleware or guard sees it: no handler receives `..` as a
path parameter, and a proxy that resolves dot segments for its own rules can't be made to
disagree with Brace about which route is being called. The 404 passes through
after-middleware, so it carries the app's security headers like any other 404.

Plain `before(...)` middleware never counts as access. The previous model —
`requireSession("/admin/*", …)` path-pattern guards — is removed.

The framework's own `/ops/*` routes follow the same rule: each is registered with the ops
credential requirement (`ops:read` or `ops:control`), the handlers carry no auth code, and an
ops route registered without one fails `start()`. The app's default access never applies under
`/ops`.

`/ops/routes` and the startup banner list each route's resolved access.

### Static mappings are covered too

A `staticFiles` mapping is not a route, but it is inside the same model. Every mapping says who
may fetch its files, and `start()` (so also `Brace.test()`) refuses to run while one says
neither:

```java
app.staticFiles("/assets", "public").publicFiles();                    // everyone
app.staticFiles("/uploads", "/var/app/uploads", StaticFiles.UNTRUSTED)
   .access(Access.session("userId", "/login"));                        // logged-in users
```

- **`defaultAccess(...)` does not apply to a mapping**, and neither does
  `defaultAccess(Access.none())`. A mapping's access is exactly what its `.access(...)` lists
  (all must pass). Inheriting a login default would put the login in front of the login page's
  own stylesheet; defaulting to public would publish an upload directory by omission. So there
  is no default, and an undeclared mapping is a startup error.
- The requirements are bound to the mapping, not to a path pattern. They run when no route
  matched the request's method and path and the mapping's prefix covers the path, at the same
  point as a route's: after plain `before(...)`, before session-aware `before(...)`, with the
  one `Session` the request uses. They run before the path is decoded or the disk is touched,
  for every method, so every spelling that reaches the mapping's files is guarded, and a
  rejected request gets the same response whether or not the file exists.
- **A mapping serves `GET` and `HEAD` only.** Any other method on a path under a mapping
  (`POST`, `OPTIONS`, an unknown method) gets `405` with `Allow: GET, HEAD`, after the mapping's
  access has run, so an anonymous request to a guarded mapping still gets the rejection. The
  bundled `/__brace/*` assets answer other methods with `405` too.
- **A route under a mapping's prefix owns its path.** With `staticFiles("/docs", dir)` and
  `app.get("/docs/payroll.pdf", ...)`, the route answers `GET` (and `HEAD`) with its own access,
  and the mapping never serves `/docs/payroll.pdf` under any method: a request the route does
  not handle gets `405` with the route's methods in `Allow`, after the mapping's access. (Before
  this, `POST /docs/payroll.pdf` skipped the route and was served the file by the mapping.)
  `start()` logs a warning naming each mapping that has routes under its prefix, because every
  other file in that directory is still served under the mapping's access alone; a mapping at
  `/` is not warned about. Outside a mapping, a path that only matches a route under another
  method is a `404`, as before.
- Nothing under a guarded mapping can be stored by a shared cache. A served file (200, 206, 304)
  is sent `Cache-Control: private, no-cache`, and a `?v=` fingerprint earns no `immutable`
  there. Every other response under the mapping (the access rejection, a 404, 405 or 416, an
  early response from `before(...)`, an error) is sent `Cache-Control: no-store`. This is
  enforced after after-middleware: `Cache-Control`, `Expires`, `Pragma`, `CDN-Cache-Control`,
  `Surrogate-Control`, `Cloudflare-CDN-Cache-Control` and `Vercel-CDN-Cache-Control` are removed
  in any letter case, whatever set them, and the one `Cache-Control` above is set.
- `start()` refuses two mappings whose directories overlap (the same directory, or one inside
  the other, compared as written and with symlinks resolved) unless their access is the same:
  a guarded directory that a public mapping also serves, or one that a less strictly guarded
  mapping also serves, would be reachable through the weaker prefix. Built-in requirements are
  compared by value (two `Access.session("userId", "/login")` calls match); a custom
  `Access.check(...)` or `Access.checkRequest(...)` matches only itself, so pass the same `Access`
  object to both mappings if they are meant to match.
- `start()` requires every mapping's directory to exist. A directory created later could be a
  symlink into another mapping's tree, or a case-variant sibling of one on a case-insensitive
  volume, which the overlap check would never have seen. As a second line, a mapping answers
  404 for a file whose real path is inside the directory of a mapping with different access,
  which covers a directory replaced by a symlink after startup.
- The same URL prefix cannot be registered twice (`staticFiles` throws; `/files` and `/files/`
  are the same prefix): only one of the two could ever serve.
- The bundled `/__brace/*` assets and `/__brace/health` are not mappings and stay public.
- `/ops/routes` (method `STATIC`) and the startup banner list each mapping with its access.

A path-pattern `before("/uploads/*", ...)` is still not access, for the reasons above. For a
per-file decision (the owner of this file, not any logged-in user), serve the file from a route
instead: look the record up with `db.findOwnedOr404(...)` and return
`Result.fileUnder(dir, name)` or `Result.download(...)`, with `.untrusted()` for uploads. Keep
those files in a directory no mapping serves. If the route does sit under a mapping's prefix,
the route wins for its methods and the mapping never serves that path, as described above.

### Access rejections are never cached

When an access requirement rejects a request, on a route or a mapping, the response is sent
`Cache-Control: no-store` with the same header stripping as above, and
`Vary: Accept, HX-Request` is added (merged with any existing `Vary`), because
`Access.session` answers a page request with a 302, a JSON client with a 401 and an htmx
request with a 401 plus `HX-Redirect`. Without this, an app-wide `after(...)` that marks
responses `public` let a shared cache store the anonymous login redirect for a guarded URL and
serve it to logged-in users.

How a *successful* response from a guarded route is cached stays the app's decision. In dev
mode Brace warns once per route when such a response carries `Cache-Control: public` or
`s-maxage`.

### Object-level authorization

Route access answers "may this user call this endpoint". It does not answer "may this user
touch row 42": `GET /posts/{id}/edit` is reachable by every signed-in user, and
`db.findOr404(Post.class, id)` returns anyone's post, so changing the id in the URL reads or
edits someone else's (an IDOR). Put the owner in the lookup:

```java
var post = db.findOwnedOr404(Post.class, req.longPathParam("id"), "ownerId", session.get("userId"));
var doc  = db.findOr404(Doc.class, id, d -> d.ownerId.equals(userId) || d.sharedWith(teamId));
var mine = db.query(Post.class, "ownerId = ?", userId);   // lists: filter in the query
```

Both lookups answer 404, not 403, for a row that exists but isn't the caller's, so the response
doesn't confirm it exists and ids can't be probed. A null owner (nobody signed in) matches
nothing. The owner field is an entity attribute path validated like `findBy`'s and must be
hard-coded. Use the plain `findOr404(type, id)` only for rows every visitor may see.

Brace has no row-level filter that applies itself: every query that returns user-owned rows
must carry its owner condition.

---

## SQL Injection

`Database` binds values only where the query says `?`. The where-fragment and raw-query methods
(`query`, `queryOne`, `queryOneOr404`, `count`, `exists`, `paginate`, `queryPage`, `hql`, `sql`,
`sqlQuery`, `sqlQueryLong`) run the string they're given, so request data concatenated into it is
HQL or SQL injection: a quoted payload widens a `WHERE` to every row, and an `ORDER BY` built from
`?sort=` takes a `CASE` subquery that leaks any column one bit per request, including tables no
entity maps.

- **Bind values with `?`.** `db.query(Post.class, "title = ?", q)`, never `"title = '" + q + "'"`.
- **Sort with `Sort`.** `Sort.from(req, "sort", Map.of("title", "title", "date", "createdAt"),
  "createdAt DESC")` lets the request pick a key from the map (`-key` for descending); an
  unknown key gets the default. Pass the `Sort` to `query`, `queryPage` or `paginate`.
- **Hard-code field names.** `findBy`, `queryIn`, `countBy`, `existsBy`, `deleteBy` and
  `findOwnedOr404` validate their field argument as an identifier, but it must still never come
  from the request.

### Detection

The `Database` a request handler receives checks each where-fragment and raw query string
against the request's path, query and form values before running it. When the string contains
one, the call throws `IllegalArgumentException` in dev mode (`-Dbrace.mode=dev`) and in
`Brace.test()` apps, so the bug fails a test, and logs one warning per call site in production
(where a false positive must not break a page). The message names the parameter, not its value.

To keep coincidences out, values shorter than 3 characters and plain numbers are ignored, and a
single word (`title`, `-createdAt`) counts only as a token after `ORDER BY` (including an aliased
`p.title`), where it picks a column, not inside a literal, where it can't break out. Anything
with quotes, spaces, parentheses or operators counts anywhere. Form values include every value
of a repeated field; a body that isn't valid form encoding is skipped. A `Sort.of(...)` order is
checked like a fragment; a `Sort.from(...)` order is not, since the request only picked a key.
Production warnings repeat at most once a minute per call site and parameter.

What it does **not** catch:

- A single word or a number concatenated outside `ORDER BY`: `"id = " + req.pathParam("id")`
  with `id` as the value is a tautology that returns every row, and isn't flagged, because
  flagging bare words everywhere would trip on ordinary code.
- A value the handler transformed (trimmed, split into terms, re-cased) before concatenating it.
- JSON bodies, headers and cookies, and queries run outside a request (jobs, `withSession`).
  `Sort.of(...)` is checked against the string you passed as well as the normalized order, so
  `?sort=title%20desc` is caught even though it renders as `title DESC`.

Every path parameter and query value is recorded (the request line is bounded at 8 KB, so there
are never many). The form body is recorded up to 1,000 values or 1 MB of text; a request past
that is *over cap* and cannot be fully checked. When such a request reaches a where-fragment, a
raw query or a `Sort.of(...)`, the call throws in dev mode and `Brace.test()` (so padding a form
cannot slip a payload past a test), and production runs the query and logs an "over cap" warning
at most once a minute per call site.

It can also fire on safe code: a hand-written allowlist (`switch (sort) { case "title" ->
"title"; ... }` concatenated after `ORDER BY`) looks the same as the injection it replaces. Use
`Sort.from`. The check is a backstop for the rules above, not a substitute.

---

## Sessions

### Encrypted and Authenticated

Brace session cookies are **encrypted using AES-256-GCM**.

- ✅ **Confidentiality:** Data cannot be read by the client
- ✅ **Integrity:** Data cannot be tampered with
- ✅ **Authenticity:** Only the server can create valid sessions

### What You Can Store

✅ **Safe to store (encrypted):**
- User ID
- Email addresses
- Permissions, roles, or scopes
- User preferences (theme, language, timezone)
- CSRF tokens
- Flash messages (transient UI notifications)
- Shopping cart contents (within size limits)

### Size Considerations

Cookies have a **4KB size limit**. For large session data:
- Use server-side storage (database, Redis, etc.)
- Store a session ID in the cookie and look up the rest server-side

### Encryption Details

- **Algorithm:** AES-256-GCM (Galois/Counter Mode)
- **Key Derivation:** PBKDF2-HMAC-SHA256 (100,000 iterations)
- **Authentication:** GCM mode provides built-in authentication (no separate HMAC needed)
- **Nonce:** Random 12-byte nonce per cookie. This prevents **keystream reuse** (the GCM
  security requirement of a unique nonce per key) — it does **not** prevent replay. A
  captured cookie can be replayed until it expires; replay is bounded by `_exp` (below).

### Session expiry

Every session cookie carries a server-enforced absolute expiry, `_exp` (epoch seconds),
**inside the encrypted payload**. On each read Brace rejects a cookie whose `_exp` is in
the past, returning an empty session. This is what bounds the lifetime of a *stolen*
cookie: the cookie's `Max-Age` attribute is only a client-side hint (a client can ignore
it and keep replaying the cookie), whereas `_exp` is checked by the server and cannot be
altered without the secret.

- **Horizon:** `SessionOptions.maxAge()` when set to a positive duration; otherwise a
  **14-day** default (so even "session-lifetime" cookies, which set no `Max-Age`, still
  expire server-side).
- **Fixed expiry from last write — no sliding window.** A session is only re-minted when a
  handler modifies it (`session.isModified()`), so the expiry is measured from the last
  write, not the last request. An active user whose session is never modified will be
  logged out when the horizon elapses even with continuous activity. This is intentional;
  there is no per-response sliding refresh. To extend an active session, write to it
  (e.g. bump a `lastSeen` value) on the requests that should renew it.
- **Reserved key.** `_exp` is server-managed: `session.set("_exp", …)` is silently
  ignored, and `_exp` is stripped from the decrypted data so it never appears via
  `get`/`has`.
- **Absolute lifetime (`_abs`).** `_exp` moves forward with every write, so on its own a
  session that is used (or replayed) continuously never expires. `_abs` is stamped once, when
  the session is first issued, and carried unchanged: 30 days by default, or `maxAge` if that
  is longer (`SessionOptions.absoluteMaxAge(Duration)`). `session.regenerate()` restarts it.
- **No `_exp`, no session.** Cookies minted by 0.1.6 or earlier carry no `_exp`; accepted
  through 0.1.10, rejected since 0.1.11.

### Rotation and revocation

- **`session.regenerate()`** at login and logout drops the CSRF token minted before the
  privilege change (one seen on a shared computer, or planted by a tossed cookie, is useless
  afterwards) and restarts the absolute lifetime.
- **Server-side revocation.** A sealed cookie is valid until it expires, wherever it is copied
  to. `app.sessionValidator(session -> boolean)` runs once per request that reads a non-empty
  session, and on WebSocket upgrades; returning false makes the request anonymous and clears
  the client's cookie. Compare a version stored in the session at login with the user's
  current one to implement "log out everywhere", password-change logout and immediate
  demotion. Cache the lookup.
- **Secret rotation.** `SessionOptions.previousSecrets(...)` opens cookies sealed with earlier
  secrets and re-seals them with the current one, so rotating logs nobody out. The ops token
  secret is derived from the session secret, so ops clients re-authenticate after a rotation, and
  so is the key for WebSocket broadcasts between instances on Postgres: during a rolling rotation,
  instances on the old and new secret don't receive each other's broadcasts until all match.
  A secret that is public or compromised (the `brace new` placeholder, or one that leaked) must
  never be listed as previous: a listed secret still mints cookies. Startup refuses the shipped
  placeholder as a previous secret, and a cookie opened with a previous secret is honoured only
  if it carries `_abs` and that deadline is no further out than the configured absolute
  lifetime. An empty previous-secret cookie opens as a brand-new session, inheriting no
  deadline. `app.sessions(options)` copies the options, so changing them afterwards has no
  effect, and `start()` re-runs the secret checks under the mode the app starts in. Drop a
  retired secret once cookies sealed with it have expired.
- **Cookie name.** Wherever the browser allows it (`Secure`, `Path=/`, no `Domain` — every
  default production deployment) the cookie is `__Host-brace_session`. Only the exact host can
  set a `__Host-` cookie, over HTTPS, so a compromised or hostile sibling subdomain can't plant
  a session or CSRF token of its choosing, and a request reads only the name it would be given,
  so a tossed `brace_session` is ignored. Plain-HTTP localhost keeps `brace_session`, and so
  does an app behind a proxy that rewrites `Host` to a loopback address: there `Secure` is
  inferred per request from the browser's `Origin`/`Referer`, which come and go, and the name
  must not change with them. Pass the real host through (see "Pass the real `Host` through") to
  get the prefix.

### Example: Storing User Session Data

```java
// Store user info directly in the encrypted session
session.set("userId", user.id.toString());
session.set("email", user.email);
session.set("role", user.role);

// Retrieve on subsequent requests
var userId = session.getLong("userId");
var email = session.get("email");
var role = session.get("role");
```

### Server-Side Storage (Optional)

For very large session data, you can still use server-side storage:

```java
// Store only an opaque session ID in the cookie
session.set("sessionId", UUID.randomUUID().toString());

// Store large data in the database
var userSession = new UserSession();
userSession.sessionId = session.get("sessionId");
userSession.userId = user.id;
// ... store large data here ...
db.insert(userSession);
```

---

## CSRF Protection

Brace automatically validates CSRF tokens on mutating requests (POST, PUT, DELETE, PATCH).

### How It Works

1. CSRF tokens are automatically generated and stored in the session
2. The token must be included in requests as:
   - Form parameter: `_csrf`
   - Header: `X-CSRF-Token`
3. Validation happens automatically before your handler runs
4. The token written into pages is **masked per render**: `${csrfField}` (a `gg.jte.Content`
   hidden input; declare it `@param gg.jte.Content csrfField = null`) and `${csrfToken}` (the
   bare token, for a `<meta>` tag read by fetch or htmx) both carry
   `base64url(mask || token XOR mask)` with a fresh random mask, so the value differs on every
   page. Validation accepts the masked form and the raw token alike. This keeps a compressed
   page from leaking the token through its size (see [Response Compression](#response-compression)).
   Outside a template, use `Csrf.maskedToken(session)` rather than `Csrf.getToken(session)`.

### Cross-site requests

Every POST/PUT/DELETE/PATCH to a CSRF-protected route is also checked against the browser's own
account of where it came from, before the body is read:

- `Sec-Fetch-Site: cross-site` → 403. `same-origin`, `same-site` (a sibling subdomain) and
  `none` (typed, bookmarked) pass.
- Without `Sec-Fetch-Site`, an `Origin` header must name the host the client addressed
  (`X-Forwarded-Host` from a trusted proxy, else `Host`); another host, or `Origin: null`, → 403.
- A request with neither header (curl, a server-to-server client) passes: it carries no ambient
  cookie to abuse.

This runs whether or not the app uses Brace sessions, so an app that authenticates with its own
cookie is covered too; with sessions it backs up the token check (a leaked token alone is not
enough cross-site). Routes with `.csrf(false)` are exempt.

### Opt-outs are checked

`.csrf(false)` is for bearer-token routes. On a mutating route that uses the cookie session —
its handler takes the `Session`, or a session access requirement or a session-aware
`before(pattern, BeforeSession)` covers it — `start()` fails.
A route that is safe to force anyway states why with `.csrf(false, "reason")`, which is logged
at startup.

### htmx

`${csrfMeta}` renders `<meta name="csrf-token" content="…">` with a masked token, and the bundled
`/__brace/csrf-htmx.js` copies it into `X-CSRF-Token` on every non-GET htmx request. The token is only attached to same-origin requests, so an `hx-post` to a cross-origin URL (for example, with htmx's `selfRequestsOnly` turned off) does not leak it.

### Exemptions

CSRF validation is **skipped** for:
- GET, HEAD, OPTIONS requests (safe methods)
- Routes explicitly opted out with `.csrf(false)`

CSRF validation is **not** bypassed by `Content-Type: application/json`. All mutating
methods on CSRF-required routes are validated regardless of content type. If your API
uses bearer-token authentication (no cookies), call `.csrf(false)` on those routes to
opt out explicitly.

**⚠️ JSON + cookies is still CSRF-vulnerable.** A cross-origin page can trigger a JSON
`POST` with credentials. If your JSON endpoint authenticates via session cookies, either
validate the CSRF token (e.g. via the `X-CSRF-Token` request header) or use bearer
tokens with `.csrf(false)`.

### Best Practices

1. **For HTML forms:** Include the CSRF token field (automatically available in templates)
2. **For JSON APIs with cookies:** Either:
   - Send the CSRF token in the `X-CSRF-Token` request header
   - Use bearer token authentication and call `.csrf(false)` on those routes
3. **For public APIs:** Use API keys or OAuth, not cookie-based sessions

---

## Cross-Site Scripting (XSS)

Templates escape `${...}` for the context it lands in (JTE), and Brace's template output adds the
contexts where escaping alone doesn't stop script:

- **URL attributes.** `href`, `src`, `action`, `formaction`, `xlink:href`, `poster`, `cite`,
  `background`, `data`, `hx-get`, `hx-post`, `hx-put`, `hx-patch`, `hx-delete` (and `data-hx-`
  forms), on any tag. HTML escaping leaves `javascript:alert(1)` intact, and browsers strip
  tabs, newlines and leading control characters before reading the scheme, so
  `java\tscript:` is a script URL too. Also `to`/`from`/`by`/`values` on SVG `<set>` and
  `<animate>`, which can set an `href`. The whole value, template text and every `${...}`, is
  held until its closing quote and normalized the same way, with character references decoded
  (`href="&#32;${url}"` starts with a space, not with a fragment). If the template's own text fixed the
  scheme or made the URL relative (`href="mailto:${email}"`, `href="/users/${id}"`), it's kept;
  if user data decides the scheme (`href="${url}"`, `href="${a}${b}"`, `href="${scheme}://${host}"`)
  only `http`, `https`, `mailto` and `tel` pass, plus raster `data:image/` URLs (png, jpeg, gif,
  webp, avif, bmp; never SVG) on `src` and `poster`. Anything else renders as
  `about:invalid#blocked`. One scheme the template can't choose for data: a `javascript:` or
  `vbscript:` URL with any `${...}` in it (`href="javascript:go('${id}')"`) is blocked as well,
  because the browser undoes both HTML escaping and percent-encoding before it runs the code.
  Put the value in a `data-*` attribute and read it from a script file.
- **Script attributes.** `on*`, `hx-on*` (`hx-on:click`, `hx-on-click`, `data-hx-on:*`),
  `hx-vals`, `hx-vars`, `hx-headers`, `hx-request` and `hx-trigger`. User data is escaped with
  `\uXXXX` sequences for every character that can end a string or start markup or a template
  substitution, which is valid in JavaScript strings, template literals and JSON, so JSON
  `hx-vals`/`hx-headers` keep working with quotes in the data. This protects data written
  **inside a quoted string** in the attribute's code (`onclick="go('${id}')"`,
  `hx-trigger="click[check('${id}')]"`). It does nothing for data that is the code itself:
  `onclick="${handler}"`, `onclick="go(${id})"` or `hx-trigger="${trigger}"` run whatever the
  value says (`click[alert(1)]` is a valid trigger with a filter expression, and has no character
  that needs escaping). Never put user data there outside a quoted string. HTML escaping alone is undone by
  the browser before the code runs (`&#39;` is a quote again), and JTE's JavaScript escaping for
  `on*` left `&` alone, so an entity in user data still closed the string. When data is the whole
  `hx-vals`/`hx-headers`/`hx-request` value (`hx-vals='${json}'`) it is attribute-escaped
  instead, so JSON the handler built stays intact. htmx evaluates such a value when it starts
  with `js:` or `javascript:` after trimming (JavaScript's `trim()`, which strips U+00A0, U+FEFF
  and the other Unicode spaces): if user data supplies any part of that prefix, in one `${...}`
  or several, the value renders as `{}`. A `js:` the template wrote itself is kept. (`hx-vars`
  has no such prefix: htmx evaluates all of it, always. It is deprecated in htmx; use `hx-vals`.)
- **`srcdoc`.** User data is escaped twice, so it is text inside the frame, not markup. Template
  markup inside `srcdoc` isn't URL-checked; an HTML preview should be sanitized HTML written with
  `$unsafe{Html.escape(...)}` in an iframe with `sandbox`.
- **`<script>` bodies.** JavaScript-escaped (JTE), for a `<script>` tag written in any letter
  case, with `<`, `>` and `&` written as `\u003c`, `\u003e` and `\u0026` on top, so data can
  never read as markup whatever the browser makes of the script. As in a script attribute, that
  protects data **inside a quoted string** (`var name = "${name}";`, a template literal, a regex
  literal). Data outside a string is not supported: `var n = ${count};` runs whatever the value
  says, and a value with `<`, `>` or `&` in it is now a syntax error there. Pass numbers and
  objects as JSON in a string or a `data-*` attribute and parse them. Things to know:
  - *Data blocks.* `<script type="application/json">{"name": "${name}"}</script>` gets the same
    escaping. `\u003c` is valid JSON and reads back as `<`. JTE's escapes for `'`, `-`, `` ` ``
    and `$` (`\'`, `\-`) are not valid JSON, so a value containing one of those fails to parse:
    for a JSON block, serialize the whole object in the handler and write it into a `data-*`
    attribute, or fetch it. Markup in a `<script type="text/template">` block is not escaped as
    markup: data there is JavaScript-string-escaped.
  - *Scripts inside `<svg>` or `<math>`.* A browser reads these as markup, not as raw text:
    `<img onerror=...>` in a JavaScript string there is a tag, and after `a<b` in the code a
    quote ends an attribute. Data in such a script is written with `\uXXXX` for quotes too (as
    in script attributes), which a JavaScript string reads back unchanged.
    A self-closing `<script href="a.js"/>` or `<style/>` is a complete element there. In HTML
    it is not: `<script src="a.js"/>` opens a script that runs to the next `</script>`, so
    always write `<script src="a.js"></script>`.
  - *Scripts wrapped in `<!-- ... -->`.* After `<!--` and a `<script` in the text (as in
    `document.write("<script src=x></script>")`), a browser does not end the element at the
    next `</script>`. Brace follows that, so data after it is still escaped as script.
- **CSS.** In a `<style>` body and in a `style=""` attribute, every character of user data other
  than letters, digits, spaces, `# % . _ -` and non-ASCII characters is written as a CSS hex
  escape (`;` becomes `\3b `). An escaped character is never CSS syntax, so data can't close its
  declaration or rule and add a selector (the usual way to leak a hidden field's value under a
  policy that allows inline styles), and can't open `url(...)`. Plain values (`red`, `#ff0000`,
  `50%`, `12px solid`) are unchanged, and data inside a quoted CSS string
  (`content: "${label}"`, `url("${image}")`) reads back as written. A value that needs CSS
  syntax of its own, `rgb(1, 2, 3)` or a whole declaration list in `style="${styles}"`, no longer
  works as data: build it from plain values in the template. Not covered: user data used as a
  selector or property *name* can still be any identifier.

Brace decides the context from the HTML it has written so far, not only from where the `${...}`
sits in the template source, so the rules above also apply to data a `@template` call or a
content block writes inside an attribute value (JTE alone escapes that as body text). It follows
the HTML parser's rules for tags, comments, `<script>`/`<style>` bodies and SVG/MathML content.
Where its reading and JTE's differ, the data gets the rules of both: the URL check if either says
URL attribute, script escaping if either says script. That shows as over-escaped text (`'`
for a quote), never as unescaped data. Two things it reads differently from a browser, on
purpose: the text of `<title>` and `<textarea>` is read as markup, and SVG/MathML content that
isn't well formed (an end tag with no matching start tag inside the `<svg>`) is read as HTML from
there on.

Both the hot-reload and precompiled render paths use this output. `$unsafe{...}` bypasses it, as
does `Result.html(...)`, which is a raw sink.

At input, `@SafeUrl` on a form field accepts only an absolute `http`/`https` URL; outside
templates, `Url.isSafe(url)` / `Url.safe(url)` apply the template rule (for a URL sent in JSON
for a client to use as a link).

**htmx evaluates code.** Bundled htmx runs with `allowEval` and `allowScriptTags` on unless a
page sets `<meta name="htmx-config" content='{"allowEval":false,"allowScriptTags":false}'>`.
With them off, `hx-on*`, `js:` values and trigger filters don't run, and neither do `<script>`
tags in swapped-in HTML, so a template mistake can't become code that way. Recommended unless a
page relies on them.

---

## Content Security Policy

Escaping is the first defence against XSS; a Content-Security-Policy is the second. It decides
what an injected tag can do once one gets past escaping. Brace ships it as
`SecurityHeaders.strict()`:

```java
app.after(SecurityHeaders.strict());
```

That sets the `SecurityHeaders.defaults()` headers (`nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy`, `Permissions-Policy`) plus:

```
Content-Security-Policy: default-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
```

- **`script-src 'self'`**: scripts load only from the app's origin. Inline `<script>` bodies,
  `on*=` attributes, `javascript:` URLs and `eval` (including htmx's `hx-on:*`, which compiles
  its handler with `Function`) don't run. Brace's `/__brace/htmx.min.js` and
  `/__brace/csrf-htmx.js` are same-origin and load normally.
- **`default-src 'self'`** covers styles, images, fonts and fetches, so inline `<style>` blocks
  and `style=""` attributes are blocked as well. htmx inserts a `<style>` for its request
  indicators at startup; under this policy that only logs a console violation. Add
  `<meta name="htmx-config" content='{"includeIndicatorStyles":false}'>` to the layout to
  stop it.
- **`object-src 'none'`, `base-uri 'none'`**: no plugins, and an injected `<base>` can't
  re-point the page's relative script URLs.
- **`frame-ancestors 'none'`**: the CSP form of `X-Frame-Options: DENY`.
- **`form-action 'self'`**: an injected `<form>` can't post the page's data to another site.

After-middleware runs on every response (handlers, static files, 404s, 500s, CSRF rejections),
so the policy covers them all. `defaults()` deliberately has no CSP: adding one would silently
break apps that still have inline scripts. Avoid `'unsafe-inline'` in `script-src` in a custom
policy (`SecurityHeaders.builder().contentSecurityPolicy(...)`): it allows every inline script,
injected ones included, which leaves the policy doing nothing against XSS. `brace check` warns
when `GET /` has no CSP, or one whose scripts allow `'unsafe-inline'` without a nonce or hash.

The `/ops` pages carry the framework's own policy, which replaces the app's on those responses
(see [Ops pages in the browser](#ops-pages-in-the-browser)).

---

## Serialization

### JPA Entities and JSON Responses

Brace entities follow the convention of **public fields** for simplicity. When serializing objects to JSON with `Json.of()` or `Result.json()`, all public fields are included in the response — including sensitive columns like `passwordHash`, API keys, or PII.

**⚠️ Never return a JPA entity directly from a JSON response handler.** Always use a DTO (data transfer object) or record that exposes only the fields you intend to share:

```java
// ❌ Dangerous: serializes all public fields, including passwordHash
@Entity
public class User {
    public Long id;
    public String email;
    public String passwordHash;
}

var user = db.find(User.class, id);
return Result.json(user);  // Leaks passwordHash to HTTP response
```

**✅ Safe: DTO exposes only intended fields**

```java
public record UserResponse(long id, String email) {}

var user = db.find(User.class, id);
return Result.json(new UserResponse(user.id, user.email));  // Only public fields from record
```

### Detection

Brace logs a **WARN** message (once per entity class per process) when `Json.of()` detects an `@Entity`-annotated object being serialized, to help catch this pattern in development. Enable production log monitoring to detect any misuse — the message includes the entity class name and hints at the likely leaked field name.

---

## Open Redirects

### The Vulnerability

A handler that redirects to a user-supplied destination is an **open redirect** — an attacker crafts a link on your domain that lands users on a phishing site, with your domain's credibility behind the link they clicked:

```java
// ❌ Dangerous: ?next=https://evil.example sends the user to the attacker's site
return Redirect.to(req.queryParam("next"));
```

`Redirect.to()` (and `Result.redirect()`) deliberately accept any location — absolute URLs, protocol-relative URLs (`//evil.example`), anything — because server-controlled redirects legitimately need all of those. The vulnerability is feeding them **untrusted input**.

### The Fix: Redirect.toLocal

```java
// ✅ Safe: only local paths accepted; throws IllegalArgumentException otherwise
return Redirect.toLocal(req.queryParam("next"));
```

`Redirect.toLocal(path)` — and `Redirect.permanentLocal(path)` for 301s — validate with an allowlist: the path must start with exactly one `/`. Rejected: null/empty, absolute URLs (`https://evil.example`), protocol-relative URLs (`//evil.example`), backslash bypasses (`/\evil.example` — browsers normalize `\` to `/`), and literal ASCII control characters.

### Best Practices

1. **Any redirect target derived from request data** (query param, form field, header) goes through `toLocal` / `permanentLocal`
2. **Server-controlled destinations** (string literals, paths built with `Url.to()`) can use `Redirect.to()` / `Result.redirect()` freely
3. **Catch the `IllegalArgumentException`** if you want a fallback (e.g. redirect to `/`) instead of a 500 on a tampered `next` parameter

---

## Server-Side Request Forgery (SSRF)

### The Vulnerability

Any feature that fetches a URL a user supplied (import from URL, link previews, webhook
"send a test", avatar from URL) makes requests **from your server**, with your server's network
position. Pointed inward, it reads things no outside client can reach:

```java
// ❌ Before 0.1.11: ?url=http://169.254.169.254/hetzner/v1/userdata returned the server's
// cloud-init user-data, which often holds secrets. http://172.17.0.1:5432/ reached the Docker
// bridge, http://localhost:8080/ops/... the app itself.
var preview = Http.get(req.queryParam("url")).fetchString();
```

### What `Http` does

Since 0.1.11, `Http` refuses destinations that aren't on the public internet, for every call:
`fetch`, `fetchString`, `fetchBytes`, `fetchJson`, `stream`, `fetchEvents` and `multipart()`.
Before sending, it resolves the host and throws `Http.BlockedDestinationException` if **any**
address the name resolves to is:

- loopback (`127.0.0.0/8`, `::1`), unspecified (`0.0.0.0`, which reaches the local host on Linux,
  `::`) or `0.0.0.0/8`
- private: RFC 1918 (`10/8`, `172.16/12`, `192.168/16`), CGNAT (`100.64/10`), IPv6 ULA
  (`fc00::/7`)
- link-local (`169.254/16`, which is where cloud metadata services live, `fe80::/10`) and IPv6
  site-local (`fec0::/10`)
- multicast and broadcast (`224/4`, `ff00::/8`, `255.255.255.255`), reserved (`240/4`),
  benchmarking (`198.18/15`, `2001:2::/48`), documentation and other special-purpose ranges
  (`192.0.0/24`, `192.0.2/24`, `198.51.100/24`, `203.0.113/24`, `192.88.99/24`,
  `2001:db8::/32`, `3fff::/20`), Teredo (`2001::/32`), and any IPv6 outside global unicast
  `2000::/3`
- an IPv6 form that carries one of the IPv4 addresses above: IPv4-mapped (`::ffff:127.0.0.1`),
  IPv4-compatible (`::127.0.0.1`), NAT64 (`64:ff9b::a9fe:a9fe`) and 6to4 (`2002:7f00:1::`)
- Azure's WireServer, `168.63.129.16`, a platform endpoint in a public range

The check runs on the **resolved address**, not the URL text, so `http://0.0.0.0/`,
`http://[::ffff:127.0.0.1]/` and a hostname whose DNS record points at `10.0.0.5` are all refused.
A numeric host must also be a plain dotted quad: `2130706433`, `0177.0.0.1`, `0x7f.1` and `127.1`
are refused outright, because readers disagree about them (Java reads `0177.0.0.1` as decimal
177.0.0.1, an HTTP proxy using `inet_aton` as octal 127.0.0.1). Nothing is sent to a refused
destination. The exception's message names only the host; the resolved address is on
`e.address()` for your logs, so a message shown to a user doesn't map internal names to private
IPs.

`Http.isPublicAddress(InetAddress)` is the same classifier, for rejecting a URL when a user
enters it rather than when you fetch it.

### Internal services: `.trusted()`

A request you make on purpose to your own infrastructure (a sidecar, an internal API, a URL from
your own config) opts out per call:

```java
var health = Http.get(config.get("search.url") + "/health").trusted().fetch();
```

`.trusted()` turns the check off for that request only. **Never** call it on a URL that came
from a user, a database row a user wrote, or a redirect `Location` from a server you don't
control; that is the request the default exists to stop. There is deliberately no global off
switch.

### Redirects

`Http` never follows redirects. To follow one, read `header("Location")` and make a new `Http`
call with it, which checks the new destination too. Don't hand the `Location` to another
client, and don't add `.trusted()` to the follow-up unless you trust wherever it points.

### Response size

Buffered fetches (`fetch`, `fetchString`, `fetchBytes`, `fetchJson`, and the error body of
`fetchEvents`) stop at **10 MB** by default and throw `Http.ResponseTooLargeException`: at once
if `Content-Length` says the body is larger, otherwise as soon as the bytes read pass the limit,
without downloading the rest. Raise or lower it per call with `.maxResponseBytes(n)`. `stream()`
is not capped, because it hands you the body to read: bound what you read yourself.

The same calls are bounded in **time**. `.timeout(...)` (default 30s) is the deadline for the
whole exchange, response headers and body together, and `.idleTimeout(...)` (default 30s) bounds
each wait for more body bytes. Either closes the connection and throws
`Http.StreamTimeoutException`. Without them, a hostile server behind a user-supplied URL could
send headers at once and then stall, or drip one byte every 29 seconds under the size cap, and
hold the request thread (and its pooled database connection, in a `DbHandler`) indefinitely; a
few dozen such requests would exhaust the pool. That was the case for buffered fetches until the
2026-11 security review (M8): `timeout` used to cover only the wait for headers. The error body
`fetchEvents` reads on a non-2xx status gets the same 30s default, though the event stream
itself has no deadline unless you set one.

A buffered call cannot be configured out of its deadline: `.timeout(null)` removes the deadline
only for `stream()` and `fetchEvents`, and means the 30s default for every `fetch*` call. Zero
or a negative duration throws `IllegalArgumentException`, and a duration over 365 days (for
example `Duration.ofSeconds(Long.MAX_VALUE)`) is clamped to 365 days rather than overflowing.

### DNS rebinding: what remains

An attacker who controls a domain's DNS can answer with a public address when it's checked and a
private one when it's connected to. The robust defense is to connect to the exact address that
was checked. `java.net.http` doesn't support that cleanly (the request URI would have to carry
the IP, which needs the restricted `Host` header, a JVM-wide setting read once at startup, plus
per-hostname TLS settings), so `Http` narrows the window instead:

- The JDK resolves the name for the connection through the same JVM address cache that the check
  just read, so it normally connects to an address that was checked. The JVM caches lookups for
  30 seconds by default regardless of the DNS record's TTL.
- The name is checked again when the response headers arrive. If its cache entry expired in the
  instant between the check and the connect and the new answer is private, the JVM has cached
  that answer, so the second check refuses it and the response is discarded before your code
  reads any of it.

So under the default JVM settings a rebinding attack can at most make a blind request (the
request is sent, the response never reaches the caller), and only if it lands a cache expiry in
a window of a few milliseconds. For https not even that: only a TLS handshake reaches the
internal address, and it fails, because the certificate is still verified against the original
hostname, which an internal server can't present.
To keep it that way:

1. **Don't set `networkaddress.cache.ttl=0`** (or `sun.net.inetaddr.ttl=0`), or anything short.
   With the cache off, the check, the connect and the re-check each do a fresh lookup, so a
   rebinding name can answer public to both checks and private to the connect: the request
   (method, headers, body, any bearer token) goes to the internal address **and the response can
   be read**. The same holds when the response takes longer than the remaining cache lifetime.
   `Http` logs a warning once when the TTL is under 10 seconds.
2. **Prefer https for user-supplied URLs.** If the feature allows it, refuse `http://` URLs before
   calling `Http`.
3. **Block metadata and internal ranges at the network layer** too (firewall the container's
   egress to `169.254.169.254` and your private subnets), so `.trusted()` misuse or another HTTP
   client isn't the only line.

If the JVM is configured with an HTTP proxy (`http.proxyHost`/`https.proxyHost`), the proxy makes
the connection and does its own DNS lookup, so the check (which runs on the local lookup) and the
re-check describe what the app resolved, not where the proxy connects. Configure the proxy to
refuse internal destinations itself. The check also needs local DNS: behind an egress proxy that
is the only resolver, every call without `.trusted()` fails with `UnknownHostException`.

### Other clients

The check is in `Http`. `Storage`, `WebhookNotifier` and anything built directly on
`java.net.http` or another library don't apply it; they take URLs from your configuration, not
from users. If you must fetch a user's URL with something else, resolve it and check every
address with `Http.isPublicAddress` first, and accept that the DNS-rebinding window above is
then wider.

---

## Trusted Proxies

When running behind a reverse proxy (nginx, Caddy, load balancer), you must explicitly configure trusted proxies.

### Why This Matters

Without trusted proxy configuration, attackers can **spoof their IP address** by sending fake `X-Forwarded-For` headers, bypassing:
- Rate limiting
- IP-based access control
- Audit logs
- Geofencing

### Configuration

```java
// Trust specific proxy IPs
app.trustedProxies("10.0.0.5", "10.0.0.6");

// Trust the reverse proxy's network (a Docker network, a VPC subnet for the load balancers)
app.trustedProxies("172.18.0.0/16");

// From config or the environment: comma-separated, blank or unset means none
app.trustedProxies(config.get("trusted.proxies"));   // the scaffold sets TRUSTED_PROXIES
```

**Trust the proxy, not the private address space.** Every address in the list can set
`req.ip()` to anything it likes. `10.0.0.0/8`, `172.16.0.0/12` or `192.168.0.0/16` trusts every
container on a Docker host (bridge networks, Swarm overlays and their default address pools all
come from those ranges) and every machine in a typical VPC, so any of them can forge
`X-Forwarded-For` and pose as any client: rate limits, IP allowlists and audit logs all believe
it. List the proxy's own address, or the subnet of a network that only the proxy shares with
the app.

Behind Traefik on Dokploy, the proxy network is `dokploy-network`:

```bash
docker network inspect dokploy-network -f '{{range .IPAM.Config}}{{.Subnet}} {{end}}'
```

Trusting that subnet is far narrower than a whole private range, but Dokploy attaches every app
it routes to `dokploy-network`, so any other app on the same host can still forge the header.
That is acceptable when you run every app on the host. If you don't trust the other apps, trust
only Traefik's own address, and give the Traefik container a fixed address on that network so
it survives a restart.

**Unconfigured proxy warning.** Behind a proxy that isn't trusted, `req.ip()` is the proxy's
address for every client: `RateLimiter.perIp` becomes one shared bucket (one client can lock
everyone out of login), and analytics sees a single visitor. When a peer on a private or loopback
address sends `X-Forwarded-For` or `Forwarded` and isn't a trusted proxy, Brace logs a warning
naming the address, once per process.

**Behind Cloudflare**, use the built-in preset instead of pasting CIDRs. It ships with
Cloudflare's published egress ranges (cloudflare.com/ips); `.autoRefresh()` keeps them synced
by re-fetching the published lists on a background virtual thread (daily, hourly retry after a
failure — the bundled list serves until the first fetch succeeds, and a failed or partial fetch
is discarded wholesale so the trust set never shrinks on a network blip). `.plus(...)` trusts
additional hops of your own — those survive refreshes:

```java
app.trustedProxies(TrustedProxies.cloudflare().autoRefresh());

// with nginx between Cloudflare and the app, also trust the local hop:
app.trustedProxies(TrustedProxies.cloudflare().plus("127.0.0.1", "::1").autoRefresh());
```

Trusting Cloudflare's ranges is only sound when the origin is reachable exclusively through
Cloudflare (firewall the origin, or use Authenticated Origin Pulls at your TLS terminator).
A client that connects to the origin directly is an untrusted peer whose forwarding headers
are ignored — the safe failure mode — but hiding the origin is what keeps attackers from
bypassing Cloudflare entirely. Note that Cloudflare's egress IPs are shared across all
Cloudflare customers; the rightmost-untrusted algorithm below stays spoof-safe regardless,
because Cloudflare itself appends the true connecting address to `X-Forwarded-For`.

### Behavior

- **Without configuration:** `req.ip()` uses socket remote address only (ignores headers)
- **With configuration:** `req.ip()` parses the forwarding header **only if** the immediate peer is trusted

**One forwarding header, chosen explicitly.** Brace reads the client address chain from exactly
one header: `X-Forwarded-For` by default, or RFC 7239 `Forwarded` (its `for=` parameters) when
you opt in. The other header is ignored. Reading both was a spoofing hole: a proxy that sets one
of them usually passes a client-sent copy of the other through untouched, so a client behind a
`Forwarded`-only proxy could send `X-Forwarded-For: 6.6.6.6` and become `6.6.6.6`.

```java
app.trustedProxies("10.0.0.5");                                           // X-Forwarded-For
app.trustedProxies(new TrustedProxies("10.0.0.5").useForwardedHeader());  // Forwarded
```

Choose the header your outermost trusted proxy writes. nginx (`$proxy_add_x_forwarded_for`),
Caddy, Traefik, Cloudflare and the common cloud load balancers write `X-Forwarded-For`. If
your proxy writes only `Forwarded`, opt in, and if it might pass a client's `X-Forwarded-For`
through, that header is now harmless.

If the header arrives on several lines (two proxies that each add a line, or a client line plus
the proxy's), every line is read, in wire order, as one list.

When the forwarding header is consulted, Brace uses **rightmost-untrusted** semantics: it walks
the list from right to left, skips any entry whose address falls inside a trusted CIDR, and
returns the first untrusted address. This is the correct algorithm because real proxies
**append** the connecting client's address — the leftmost entries were written by the client and
can be forged.

Examples:

| `X-Forwarded-For` | Trusted CIDRs | `req.ip()` |
|---|---|---|
| `1.2.3.4` | `10.0.0.0/8` | `1.2.3.4` |
| `spoofed, 9.9.9.9` | `10.0.0.0/8` | `9.9.9.9` (rightmost untrusted, not attacker-supplied leftmost) |
| `client, 10.0.0.2, 10.0.0.1` | `10.0.0.0/8` | `client` (both proxies trusted) |
| `10.0.0.1, 10.0.0.2` | `10.0.0.0/8` | `10.0.0.1` (all trusted → leftmost) |

**Dual-stack.** `TrustedProxies` matches addresses by binary value and never resolves names. An
IPv4 CIDR also matches the IPv4-mapped IPv6 form of its addresses (`10.0.0.0/8` matches
`::ffff:10.1.2.3`), so no extra entry is needed for a proxy seen through a dual-stack socket. IPv4
and IPv6 loopback are different addresses, though: `127.0.0.1` does not match `::1`. If your proxy
can connect over either, list both:

```java
app.trustedProxies("127.0.0.1", "::1");
```

An IPv6 peer is matched and reported without brackets: Jetty gives the socket address as
`[0:0:0:0:0:0:0:1]`, and Brace strips the brackets where the address enters the framework, so a
trusted IPv6 proxy is recognised and a direct IPv6 client's `req.ip()` is `0:0:0:0:0:0:0:1`.

### Supported Headers

- `X-Forwarded-For` (the default client-address header)
- `Forwarded` (RFC 7239), instead of `X-Forwarded-For`, with `useForwardedHeader()`: elements
  split on `,`, parameters on `;`, neither inside a quoted-string (`for="[2001:db8::1]:4711"`)
- `X-Forwarded-Proto` — the original scheme, for the session cookie's `Secure` attribute
- `X-Forwarded-Host` — the host the client addressed, for the `Secure` decision, the session
  cookie's `__Host-` name, the cross-site `Origin` checks (CSRF, WebSocket, ops) and analytics

All of them are ignored unless the immediate peer is a trusted proxy. Each is read from every
line it arrives on, in order, as one list. For `X-Forwarded-Proto` and `X-Forwarded-Host` Brace
takes the **rightmost** entry, the one the nearest proxy added: behind a proxy that appends to
these headers, or passes a client's copy through, the leftmost entry is whatever the client sent.
**Your proxy must set (overwrite) both headers**, as the nginx example below does with
`proxy_set_header`; a proxy that only passes them through lets a client choose them.

`req.ip()` is always an IP address literal (or `unknown` when there is no socket address). If
the entry the walk selects is not an address (`unknown`, `_hidden`, an obfuscated RFC 7239
identifier, or anything a client managed to put there), the socket address is used instead.

If a trusted proxy sends the forwarding header Brace was not told to read, and not the one it
was (say only `Forwarded` while the app reads `X-Forwarded-For`), every client would silently get
the proxy's address; Brace logs one warning naming `TrustedProxies.useForwardedHeader()`.

Repeated `Cookie` lines are joined (with `; `) into one list on every request and WebSocket
upgrade, and a cookie name that appears twice resolves to its first occurrence on both.

### Pass the real `Host` through

Brace uses the request's host to tell production from local development (the `Secure`
attribute) and to recognise same-origin WebSocket upgrades. **nginx's default `proxy_pass`
replaces `Host` with the upstream address**, so every request arrives as `Host: 127.0.0.1:8080`
— which looks exactly like a developer's machine. Symptoms: session cookies without `Secure`,
and every browser WebSocket rejected with 403. Brace logs a one-time warning when it sees the
pattern (a loopback `Host` from a browser on an `https://` page), and uses that browser signal
to set `Secure` where it can, but requests without an `Origin`/`Referer` can't be recognised.
Fix the proxy:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host              $host;
    proxy_set_header X-Forwarded-Host  $host;   # don't pass a client's X-Forwarded-Host through
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /live {                       # each WebSocket path
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade           $http_upgrade;
    proxy_set_header Connection        "upgrade";
    proxy_set_header Host              $host;
    proxy_set_header X-Forwarded-Host  $host;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

```java
app.trustedProxies("127.0.0.1", "::1");   // nginx on the same host
```

Caddy's `reverse_proxy` already passes `Host` through and sets `X-Forwarded-*`; configure
`trustedProxies` so they are honoured. Load balancers that must rewrite `Host` should send
`X-Forwarded-Host` instead.

---

## Cookie Security

### SessionOptions

Configure session cookie security with `SessionOptions`:

```java
// Secure defaults for production
app.sessions(SessionOptions.secure("your-secret")
    .maxAgeDays(14)
    .sameSiteLax());

// Custom configuration
app.sessions(SessionOptions.of("your-secret")
    .secure(true)           // HTTPS only
    .httpOnly(true)         // No JavaScript access
    .sameSiteStrict()       // Strict CSRF protection
    .maxAgeDays(30)         // 30-day expiration
    .domain(".example.com") // Share across subdomains
    .path("/app"));         // Restrict to path
```

### Cookie Attributes

| Attribute | Default | Purpose |
|-----------|---------|---------|
| `HttpOnly` | `true` | Prevents JavaScript access (XSS mitigation) |
| `Secure` | resolved per request | HTTPS only — on for every non-loopback request (see below) |
| `SameSite` | `Lax` | CSRF protection (`Strict`, `Lax`, or `None`) |
| `Max-Age` | session | Cookie lifetime (use `maxAgeDays()` to set) |
| `Path` | `/` | Scope to specific path |
| `Domain` | none | Share across subdomains |

### The `Secure` attribute is resolved per request

With no explicit `.secure(...)`, Brace decides the `Secure` attribute per response:

- `X-Forwarded-Proto: https` from a **trusted** proxy → `Secure` on. This wins outright, so
  a proxy that rewrites `Host` to the upstream still gets the attribute.
- Otherwise, on unless the request's host is a loopback address (`localhost`,
  `127.0.0.0/8`, `::1`). The host is `X-Forwarded-Host` from a trusted proxy, else `Host`.
- A loopback host from a browser whose `Origin` (or `Referer`) is an `https://` page on a
  real host is treated as production: that request was relayed by a TLS proxy rewriting
  `Host`. This signal can only add `Secure`, never remove it. See "Pass the real `Host`
  through" above for the proper fix.

Brace serves cleartext HTTP/1.1 and expects TLS to be terminated by a reverse proxy, so it
cannot read the scheme off its own connector. Before 0.1.8 the attribute simply defaulted to
`false`, which meant every app built the documented way — `.sessions(secret)` — shipped a
session cookie a network attacker could read off any cleartext request to the domain. A
fixed `true` would have been no better: it breaks every app and test suite on
`http://localhost`. Hence the per-request rule, which needs no configuration in either
environment.

An explicit `.secure(true)` or `.secure(false)` always wins. An app genuinely served over
plain HTTP on a real hostname must opt out, and startup logs a warning when it has:

```java
app.sessions(SessionOptions.of("secret").secure(false));
```

An attacker cannot use the `Host` header to strip `Secure` from someone else's cookie: the
header they control is on their own request, and the `Set-Cookie` it shapes comes back to
them.

### Production Recommendations

```java
app.sessions(SessionOptions.secure("secret")
    .maxAgeDays(14)
    .sameSiteLax());
```

This sets: `HttpOnly=true`, `Secure=true` (explicitly, regardless of request),
`SameSite=Lax`, `Max-Age=1209600` (14 days)

### Session Cookies and Shared Caches

Any response Brace attaches a session `Set-Cookie` to is sent `private`. With no
`Cache-Control` it gets `Cache-Control: private`. With one set by the handler or an
after-middleware, `public` is rewritten to `private`, and `private` is added in front when the
value has neither `private` nor `no-store` (`max-age=600` becomes `private, max-age=600`; so
does `s-maxage=600`, which a shared cache then ignores); the other directives are kept. A
response carrying a session cookie is per-user by definition — without this it is
*heuristically cacheable*, or explicitly storable, and a shared cache (e.g. an nginx recipe that
force-caches everything under `/static/`) would replay user A's cookie to every user.

Response header names are case-insensitive in `Result`: `res.header("cache-control", ...)` and
`res.header("Cache-Control", ...)` are the same header, and the second write replaces the
first. (Before 0.1.11 they were two entries; Brace checked one spelling and the other reached
the wire, so a lowercase `cache-control: public` rode out with the session cookie.) Brace does
not strip `CDN-Cache-Control`, `Surrogate-Control` or `Expires` from a cookie-carrying response
outside a guarded static mapping or an access rejection, so don't set those app-wide on
responses that can carry a session.

The riskiest shape is a session-mutating middleware whose path pattern covers static
prefixes — for example a `lastSeen`-touch guard on `/*`. Every static file response then
carries a fresh session cookie. The `private` default makes that safe for spec-compliant
caches, but prefer scoping such middleware to application routes (`/app/*`) rather than
`/*`, and never configure a proxy to ignore response headers for paths a session
middleware can touch.

A static mapping with access requirements never sends anything a shared cache may store: a
served file is `private, no-cache` and every other response under it is `no-store`, with or
without a session cookie (see
[Static mappings are covered too](#static-mappings-are-covered-too)). Access rejections are
`no-store` everywhere (see [Access rejections are never cached](#access-rejections-are-never-cached)).

### The page cache (`cache.wrap`)

A page-cached route stores one rendered response per key and serves it to every visitor. The
key is the route pattern, its decoded path parameters, the method, the `HX-Request` variant, and
the query params and headers the route declares with `.vary(...)` and `.varyHeader(...)` (a param
given twice keys on all its values). **A cached handler must depend only on those.** Anything else
it reads (another query param, `req.url()`, a header such as `Accept-Language`, a cookie,
`req.ip()`, the body) can differ between visitors, and whatever the first visitor sent is replayed
to the rest for the TTL: `?msg=<b>PWNED</b>` on an undeclared param becomes everyone's page. In dev
mode Brace logs such a read once per route, and under `Brace.test()` it throws, so a test of the
route fails; production does no checking. `req.path()` (the raw, undecoded path) and
`req.method()` are not recorded, so they are covered differently: a `HEAD` is never stored (it may
be served an existing `GET` entry), and a request whose raw path is not in RFC 3986 normal form
(an escaped unreserved character such as `/p/%62` for `/p/b`, lower-case hex, raw non-ASCII) is
rendered but never stored, so a crafted spelling cannot choose the page served at the normal URL.
Read path parameters with `req.pathParam(...)` in a cached handler.

Some inputs are refused outright rather than trusted to the handler: a request with
`Authorization`, `HX-Target`, `HX-Trigger`, `HX-Trigger-Name`, `HX-Boosted` or `HX-Prompt` is
served by the handler without reading or writing the cache, unless the route declares that
header with `.varyHeader(...)`. `HX-Request` is keyed as absent or exactly `true`; any other value
(`1`, `True`) bypasses the cache, so a handler testing `req.hasHeader("HX-Request")` cannot store
its partial under the full-page key. A declared header's value enters the key as its SHA-256
digest, so `varyHeader("Authorization")` never puts the credential in `brace_cache.cache_key`. A
response that sets a cookie is never stored (it is returned with its `Set-Cookie` intact). Only 2xx
responses are stored, and a response carrying the visitor's CSRF token or flash is not.

Page entries are kept apart from the app's own cache entries (keys starting `brace:page:`, which
`set`, `getOrSet` and `delete` refuse for app keys): on Postgres they have their own row cap
(10,000 by default), are trimmed least recently used first (written, or hit, recorded at most once
a minute per row) and are trimmed before app rows when the table is over its overall cap; in
memory a full cache evicts the page least recently hit (an approximate LRU), and a new page never
displaces an app entry. A client minting pages (any declared `vary` value or path parameter)
therefore churns only the page cache, and pages nobody reads again go before the hot ones, so a
flood cannot lock the real pages out.

### Shared cache values (`CacheBackend.postgres`)

On the shared backend each value is stored as its class name plus Jackson JSON, so whoever can
write the `brace_cache` table chooses the class name a row carries. Brace never lets that choice
reach Jackson for an arbitrary class. A row is deserialized only when its class is:

- the exact class the caller asked for (`get(key, Rule.class)` for a `Rule`), always allowed;
- a class this process has itself stored;
- a built-in JDK container or value (`ArrayList`, `LinkedList`, `HashMap`, `LinkedHashMap`,
  `TreeMap`, `HashSet`, `LinkedHashSet`, `TreeSet`, `ArrayDeque`, the `List.of`/`Map.of`/
  `Set.of` and `Collections.unmodifiable*`/`empty*`/`singleton*` classes, boxed primitives,
  `String`, `BigDecimal`, `BigInteger`, `UUID`, the `java.time` value types, and arrays of
  these), which Jackson rebuilds without running app code;
- or a class registered with `cache.allowType(...)`.

Anything else (an app class read through `Object`, an interface or a base class, or by
`getOrSet`, on an instance that has not stored it) is treated as a miss and logged at WARN once
per stored class and requested type. A miss on security state fails open: after a deploy, or on
a node that only reads, `cache.get("denylist", Set.class)` for an app-defined set type would see
nothing. **Read security state with its concrete type**, or register the class at startup on
every instance with `cache.allowType(MyType.class)`. Register only plain data classes (records,
POJOs whose constructors and setters only assign): a registered class can be named by any row.

---

## File Uploads

### Upload Size Limits

`maxUploadSize` bounds **every** request body, not just multipart uploads: a non-multipart
body (JSON, form post, raw bytes) larger than the limit is rejected with `413 Payload Too
Large` before the handler runs, and multipart parsing enforces the same cap. Configure it
to prevent DoS:

```java
app.maxUploadSize("10M");  // 10 megabytes (default)
app.maxUploadSize("50M");  // 50 megabytes
```

Since 0.1.8 the body is read **after** before-middleware runs, so a rate limiter or auth
guard can shed a request before its bytes are buffered. A guard that returns a result wins
over the `413`; middleware that reads `req.body()` itself (a webhook signature check) still
triggers the read at that point.

An oversized upload is a **client** error: it returns `413` and deliberately records no
framework error. Before 0.1.10 the `Content-Length` fast-reject ran only for non-multipart
bodies, so an oversized *multipart* upload reached Jetty's own cap and surfaced as a `500`
— which meant any unauthenticated client could flood the error store and the regression
notifier just by POSTing large files.

`maxUploadSize` counts the bytes as received: a gzip-encoded request body is not inflated
(see [Response Compression](#response-compression)), so it can't expand past the cap.

### Upload Spill Files

Multipart parts over `uploadMemoryThreshold` (default 1MB) are written to a temp file
rather than held in the heap, which bounds the memory an upload can consume but introduces
a file on disk holding untrusted content:

- The spill directory (`app.uploadTempDir(Path)`, default `${java.io.tmpdir}/brace-uploads`)
  is created **owner-only (700)** rather than under the ambient umask, and the files inside
  it are created owner-only by the JDK.
- Spill files are deleted when the request ends — on **every** exit path, including 413s,
  CSRF 403s, thrown 404s and 500s. A missed path would not be a leaked file but an
  unbounded disk fill drivable by any client.
- A hard kill (SIGKILL, OOM, container eviction) skips cleanup, so startup sweeps files
  older than six hours. The sweep does not follow symlinks, so a symlink planted in the
  directory cannot turn it into an arbitrary-delete primitive.
- An `UploadedFile` is only valid for the duration of its request. Save it, or push it to
  `Storage`, before the handler returns.

**Budget disk accordingly:** the worst case is concurrent uploads × `maxUploadSize`. This
trades a heap-exhaustion failure mode for a disk one, which degrades far more gracefully
(writes fail, the app keeps serving) but is still a resource an attacker can consume.

### Security Considerations

1. **Validate file types:** check the extension against what you accept. `file.contentType()`
   is whatever the uploader's browser (or script) claimed, so never trust it as the type
2. **Scan for malware:** Use external virus scanning for untrusted uploads
3. **Store safely:** Don't use user-provided filenames directly
4. **Serve as untrusted:** an uploaded `.html` or `.svg` served inline from the app's origin
   runs its script there (stored XSS). See [Serving uploaded files](#serving-uploaded-files)
5. **Limit concurrency:** upload concurrency now consumes disk rather than heap — cap it
   at the proxy if untrusted clients can reach an upload endpoint

### Safe Storage Pattern

```java
// DON'T: Use user filename directly
String key = "uploads/" + file.filename();                 // ❌ Unsafe
file.saveTo(uploadDir.resolve(file.filename()));           // ❌ Unsafe

// DO: Generate safe keys and names
String key = Storage.safeKey("uploads", file.filename());  // ✅ Safe
file.saveTo(uploadDir, file.safeFilename());               // ✅ Safe
```

`Storage.put`, `delete`, and `url` reject keys containing `.`/`..` segments or a leading `/`
outright: the SigV4 canonical URI and the request URL are built from the same unnormalized
string, so a traversing key would have been *validly signed* for the path it escaped to.

`Storage.safeKey` and `UploadedFile.safeFilename()` also neutralize an extension a browser or
server could execute: markup (`html`, `htm`, `xhtml`, `shtml`, `mht`…), `svg`/`svgz`,
`xml`/`xsl`/`xslt`/`xsd`, feeds (`rss`, `atom`, which browsers render as XHTML), `js`/`mjs`/`es`/
`css`/`wasm`/`swf`/`htc`/`hta`, and server-side scripts (`php`, `phtml`, `asp`, `jsp`, `cgi`,
`pl`, `py`…), in any case, and the same before a compression extension (`x.svg.gz`, which nginx's
`gzip_static` would serve for `x.svg`). `safeKey` replaces it with `bin` (`u/<uuid>.bin`);
`safeFilename()` turns each such extension's dot into `_` wherever it is in the name and appends
`.bin` when it was last (`page.html` → `page_html.bin`, `a.php.png` → `a_php.png`, since some
servers run a handler for an extension anywhere in the name). Trailing dots and spaces are
dropped first. This is defense in depth for whatever serves the files by extension; the serving
rules below are the fix.

`Storage.put(key, bytes, contentType)` and `put(key, path, contentType)` store the type you pass.
Never pass `file.contentType()`: the uploader chose it. Use `put(key, uploadedFile)`, which types
the object from the key.

### Serving uploaded files

**Static directory.** Register an upload directory as untrusted:

```java
app.staticFiles("/assets", "public").publicFiles();                          // your files
app.staticFiles("/uploads", "/var/app/uploads", StaticFiles.UNTRUSTED)       // users' files
   .access(Access.session("userId", "/login"));
```

`UNTRUSTED` decides how a file is served; the access declaration decides who gets it, and every
mapping needs one (see [Static mappings are covered too](#static-mappings-are-covered-too)).
`.publicFiles()` on an upload directory means anyone who can guess or enumerate a name can fetch
the file: choose it only for uploads that are meant to be public, such as avatars. A static
mapping can only ask "may this user fetch from this directory"; when the answer depends on the
file, serve it from a handler (below).

In `UNTRUSTED` mode:

- The `Content-Type` comes from an allowlist by extension: raster images (png, jpeg, gif, webp,
  avif, bmp, ico), PDF, plain text, CSV, JSON, office documents, zip/gzip/tar, common audio and
  video. Anything else, `.html`, `.svg`, `.xml` and `.js` included, is `application/octet-stream`.
- Everything except a raster image is sent with `Content-Disposition: attachment`, so following
  a link to it downloads it instead of rendering it. **SVG is not a raster image.** PDF is an
  attachment too: viewers that run PDF JavaScript differ, and the safe default is a download.
- Every response carries `Content-Security-Policy: sandbox` (no script, opaque origin, even if
  a browser renders it anyway) and `X-Content-Type-Options: nosniff`. The sandbox is sent as its
  own header, so an app CSP set by `SecurityHeaders` doesn't replace it.

Images still render in `<img>`, and audio/video still play in `<audio>`/`<video>`; those ignore
`Content-Disposition`. `staticFiles` throws if an untrusted directory is inside a trusted one (or
the reverse), because its files would also be reachable through the trusted mapping; it compares
the paths as written and with symlinks resolved. A file a trusted mapping reaches that resolves
into an untrusted directory anyway (a link made later) is served as untrusted.

These headers are enforced as the response is written, after the handler and after-middleware, so
a `Content-Type` or `Content-Disposition` set later can't undo them, and a page-cache hit keeps
them. `.untrusted()` applies to 2xx responses; an error page is the app's own.

**From a handler** (an access check, a lookup by id), apply the same rules with
`Result.untrusted()`:

```java
app.get("/files/{name}", req -> Result.fileUnder(uploadDir, req.pathParam("name")).untrusted());
// doc looked up with db.findOwnedOr404(...); originalName is the uploader's filename
return Result.download(Path.of(doc.path), doc.originalName).untrusted();   // keeps the filename
```

**S3.** `storage.put(key, uploadedFile)` and `putGenerated(folder, uploadedFile)` ignore the
uploader's `Content-Type`. The object's type comes from the key's extension through the same
allowlist (`application/octet-stream` otherwise), and every object but a raster image is stored
with `Content-Disposition: attachment`. That matters even off the app's origin: a bucket or CDN
on a sibling subdomain shares the site for cookies. To store a type you checked yourself, pass it:
`storage.put(key, file, "image/svg+xml")`.

### Serving user-supplied filenames

`Result.download(bytes, contentType, filename)` is safe to call with a user-supplied name.
It drops any directory component and emits the RFC 6266 pair — an ASCII-safe
`filename="…"` plus `filename*=UTF-8''…` — so a name containing quotes cannot inject
`Content-Disposition` parameters, and non-ASCII names survive intact.

`Http.multipart().field(name, bytes, filename)` on the **outbound** side throws
`IllegalArgumentException` for a name or filename containing control characters, rather
than letting CR/LF terminate the part headers and forge extra parts in the request.

---

## Static Files

Every mapping declares its access: `app.staticFiles(urlPrefix, directory).publicFiles()` or
`.access(...)`. `start()` refuses a mapping with neither, and `defaultAccess(...)` does not cover
mappings; see [Static mappings are covered too](#static-mappings-are-covered-too). Prefer
`.access(...)` to a path guard for anything private: it is bound to the mapping, so it cannot
disagree with the path that is served.

A mapping serves `GET` and `HEAD`; every other method gets `405` with `Allow: GET, HEAD`. Its
directory must exist when the app starts, a URL prefix can be registered once, and two mappings
may share or nest directories only when their access is the same.

`app.staticFiles(urlPrefix, directory)` confines serving to `directory`. The check resolves
symlinks (`toRealPath`) before comparing, so a symlink under the served tree cannot serve a
file outside it — a lexical `normalize()` + `startsWith` check does not catch that, because
the traversal happens in the filesystem rather than in the path string.

Dotfiles and dot-directories (`.env`, `.git/config`, `.htaccess`, `assets/.hidden/x`) are never
served (404), whatever the mapping; the only exception is a leading `.well-known/`, so ACME
challenges and `security.txt` keep working. The check runs on the decoded path, so `%2eenv`
is refused too.

**A static file is served only at its canonical spelling.** Path guards (`before("/files/private/*", ...)`,
a path-scoped `after(...)`, a reverse proxy's `location` block) match the raw, still-encoded
request path, while the file lookup decodes it. Any second spelling of the same file would
skip a guard written for the first, so these get a 404:

- A percent-escape of an RFC 3986 unreserved character (`A-Z a-z 0-9 - . _ ~`), in either hex
  case: `/files/%70rivate/secret.txt`, `/files/a%7Eb.txt`. Browsers, `encodeURIComponent` and
  `Url.to` never encode these.
- An encoded or literal separator: `%2F`, `%5C`, `\`. Jetty already answers these with a 400;
  the static handler refuses them as well in case URI compliance is relaxed.
- A trailing slash or an empty segment: `/files/exact.txt/`, `/files/v2//app.js`. The path that
  would be opened, written back out, has to equal the requested path character for character.
  (A trailing slash on a file was served before 0.1.11, past a guard on the exact path.) A
  directory is a 404 with or without the slash; there are no index files or listings.
- A name that differs from the name stored on disk: `/files/PRIVATE/secret.txt` on a
  case-insensitive volume (macOS, Windows), or a decomposed (NFD) spelling of an accented name
  on APFS. Each requested segment is compared, case-sensitively, with the stored name.

Escapes that a canonical URL needs keep working: `%20`, UTF-8 bytes for non-ASCII names
(`caf%C3%A9.txt`, either hex case), and reserved characters (`%26`, `%40`, `%3D`, which may also
be written literally). Two consequences for guards: write the guarded path in unreserved
characters, because a guard pattern containing `&`, `@`, `=`, a space or non-ASCII can still
be reached by the other legitimate spelling of that character; and the case check relies on `Path.toRealPath` reporting
stored names, which it does on macOS and Windows; a Linux directory with the `casefold`
attribute is untested and may not be covered.

Symlinks that stay inside the served directory keep working. To serve a directory that
lives elsewhere, register it as its own mapping rather than linking it into the web root. A
directory users write to is registered `UNTRUSTED` (see
[Serving uploaded files](#serving-uploaded-files)):

```java
app.staticFiles("/assets", "public").publicFiles();
app.staticFiles("/assets/uploads", "/var/app/uploads", StaticFiles.UNTRUSTED)
   .access(Access.session("userId", "/login"));
```

When prefixes overlap, the longest one serves the request regardless of registration order, so
`/assets/uploads/x` is looked up in `/var/app/uploads` even though `/assets` was registered
first. (Before 0.1.11 the first registered prefix won, and the second mapping here was
unreachable.)

Brace sends no `Server` or `X-Powered-By` header. Requests that Jetty rejects before Brace's
handler runs (ambiguous URIs such as `//x` or `%2e%2e` segments, malformed requests) get a
plain-text reason phrase (`Bad Request`) with `nosniff`: no server version, and the request URI
is not echoed.

Static responses carry `X-Content-Type-Options: nosniff`, and — since 0.1.8 — the app's
after-middleware, so `app.after(SecurityHeaders.defaults())` reaches them too. That matters
most when a static directory holds user-supplied content: those are exactly the responses
that need `X-Frame-Options` and a CSP.

---

## Response Compression

Since 0.1.11 Brace gzips responses by default (`app.compression(false)` turns it off). The rules
are in `CompressionHandler`; the security-relevant ones:

- **Request bodies are never inflated.** A request with `Content-Encoding: gzip` reaches the
  handler as the compressed bytes. Inflating would let a few KB on the wire expand past
  `maxUploadSize` (a gzip bomb), since the cap is checked against what the handler reads.
- **Only clients that send `Accept-Encoding: gzip` get gzip**, and every compressible response
  carries `Vary: Accept-Encoding` (merged with `Vary: HX-Request` on htmx responses), so a
  shared cache never hands compressed bytes to a client that didn't ask for them.

### BREACH

Compression makes a response's length depend on how much of it repeats. If a page reflects
text the attacker chooses (a search term, a URL parameter) *and* contains a secret that is the
same on every load, an attacker who can make the victim's browser send many requests and can see
the encrypted response sizes on the network can recover the secret a character at a time. That
is BREACH. It needs all of: HTTPS traffic the attacker can observe, a page that reflects their
input, and a secret that is byte-identical across responses.

What Brace does about it:

- **The CSRF token is masked per render**, so it is never the same twice and compression has
  nothing to match it against. This covers `${csrfField}`, `${csrfToken}` and `Csrf.maskedToken(session)`. A
  page that prints `Csrf.getToken(session)` directly is not covered.
- **Session cookies are not in the body**, so compression doesn't reach them.

What it doesn't cover, and what to do:

- **Other secrets you render into HTML** (an API key shown on a settings page, a personal
  access token, an invite link). Don't put long-lived secrets in pages that also echo request
  input. Show them once, or mask them the same way.
- **Apps where BREACH matters more than bandwidth** can turn compression off with
  `app.compression(false)`. A CDN in front of the app (Cloudflare, for example) re-compresses
  toward the browser regardless, so what an attacker on the browser's network sees is decided
  there; Brace's compression only changes the CDN-to-origin hop.

---

## WebSockets

A WebSocket upgrade runs plain `before(...)` middleware, then the `Origin` check below, then
the route's access requirements (see [Authorization](#authorization)); a rejection declines the
handshake. Before 0.1.11 the upgrade ran no guard at all, so a socket under a guarded path
accepted anonymous clients.

A socket is upgraded only at its registered path, spelled exactly. `app.ws("/ws", ...)` answers
`/ws` (with any query string); `/x/../ws`, `/%77s` and `/ws;x` get a 404 before any middleware
or guard runs. Jetty matches upgrades against the decoded path with dot segments resolved, so
without this check those spellings reached the socket while a reverse proxy matching the raw
request target saw some other path. The path given to `app.ws(...)` is therefore a literal, not
a pattern.

WebSocket upgrades are validated against the request's `Origin`. A handshake is **not**
subject to the same-origin policy, so without this an attacker's page could open a socket
to your app, have the browser attach the victim's session cookie, and hold an authenticated
socket for its lifetime (cross-site WebSocket hijacking).

- A cross-host `Origin` is rejected with 403.
- A **missing** `Origin` is allowed: only browsers are required to send one, and only
  browsers are what the policy protects. Rejecting would break every non-browser client for
  no gain.
- Hosts are compared without scheme or port, so TLS terminated at a proxy is fine. The app's
  host is `X-Forwarded-Host` from a trusted proxy, else `Host` — a proxy that rewrites `Host`
  to the upstream makes every same-origin socket look cross-origin (403, with a one-time
  warning). See "Pass the real `Host` through".

Declare deliberate cross-origin clients:

```java
app.wsAllowedOrigins("https://studio.example.com");  // exact origin: scheme, host and port
app.wsAllowedOrigins("studio.example.com");          // bare host: any scheme or port
app.ws("/live", ctx -> new LiveHandler(ctx));
```

Prefer the full origin: listing `https://studio.example.com` does not admit
`http://studio.example.com`, whose page a network attacker could inject into.

`"*"` disables the check — sound only if the socket carries no ambient authority, i.e. it
does not rely on the session cookie for authorization.

Do not rely on `SameSite` alone for this. The default `SameSite=Lax` session cookie does
make modern browsers withhold the cookie on a cross-site handshake, but that protection
disappears the moment an app calls `sameSiteNone()`.

### Broadcasts between instances (Postgres)

On Postgres, `broadcast(room, message)` reaches members on other instances through `LISTEN`/
`NOTIFY`. Postgres puts no permission on either: any role that can connect to the database can
`NOTIFY` any channel and `LISTEN` on any channel. Before 0.1.11 the channel was a fixed
`brace_ws` and payloads were plain JSON, so anyone with a database login (a reporting user, a
compromised neighbour app) could push frames to room members, which is script injection when an
app broadcasts HTML fragments for htmx. Two apps sharing a database also received each other's
broadcasts in same-named rooms.

Since 0.1.11:

- **Every payload is signed.** It is HMAC-SHA256 over the exact JSON body, with a key derived
  (under its own label) from the session secret, or from `.opsSecret(...)` when the app has no
  sessions. A receiver checks it in constant time and drops anything unsigned or wrongly signed.
  Payloads too large for a `NOTIFY` (over 7900 bytes, signature included) go through the
  `brace_ws_messages` table as before; the row is signed too, and the signed reference to it
  carries the row's content hash, so writing that table can't inject or swap messages (deleting
  rows can still suppress them, as it always could).
- **The channel is per app**: `brace_ws_` plus 16 hex digits of a hash of the key. Instances of
  one app agree on it; apps with different secrets never share one. The name is not a secret
  (it is visible to a superuser in `pg_stat_activity`), and nothing depends on it being one.
- **Replays are refused.** Every payload carries an issued-at time and a random nonce. A
  receiver drops a payload more than 60 seconds from its own clock, either way, and any nonce it
  has already accepted. It also refuses anything issued before it started (its nonce memory
  starts empty) or before the newest nonce it had to forget early under load, and the window's old
  edge never moves back with the clock. `LISTEN` needs no privilege, so a forger can also capture
  genuine payloads; without this, one captured message could be re-sent thousands of times to
  flood a room. Instances' clocks must agree to well within 60 seconds (NTP does), and a payload
  that waits over 60 seconds in a backed-up listener is dropped rather than delivered late.
- **Rejections are counted, not shouted.** Dropped payloads are logged as a
  `ws_broadcast_rejected` event at most once a minute, with the reason and a count since the last
  line.
- **No secret, no start.** A Postgres app with WebSocket routes and neither `.sessions(secret)`
  nor `.opsSecret(secret)` fails `start()` with a message naming both. There is no per-process
  fallback: a random key would put each instance on its own channel and silently end
  cross-instance delivery.

The secret must be identical on every instance. Instances with different secrets (a rolling
deploy that changes it, or one that mixes 0.1.10 and 0.1.11) don't see each other's broadcasts
until they all match; nothing is delivered wrongly, but members on the other side miss messages.

The signature authenticates the sender as *an instance of this app*. It does not decide who may
read a room: joining one is still an authorization decision the handler makes before
`ctx.join(room)`.

---

## Rate Limiting

Protect endpoints from abuse with rate limiting:

```java
// Per-IP rate limiting (`/api/*`: the bare `/api` pattern matches only the literal path /api)
app.before("/api/*", RateLimiter.perIp(100, "1m"));

// Per-client rate limiting: requests without the header are limited per IP (see below)
app.before("/api/*", RateLimiter.perKey(
    req -> req.header("Authorization"),
    1000,
    "1h"
));

// Login attempts per account: count submissions only (not views of the login page), and
// normalize the username so "Alice", "alice" and "alice " are one account
var loginLimit = RateLimiter.perKey(req -> {
    var username = req.formParam("username");
    return username == null ? null : username.strip().toLowerCase(Locale.ROOT);
}, 5, "15m");
app.before("/login", req -> "POST".equals(req.method()) ? loginLimit.handle(req) : null);
```

**Key the limiter on the value the handler uses.** `req.formParam(...)` reads the request body
only, and so does `req.form(...)` on `POST`, `PUT`, `PATCH` and `DELETE`: a field in the query
string never fills a form field on those methods. (Only `GET` and `HEAD` forms bind from the
query string.) A limiter keyed on `formParam("username")` therefore counts exactly the username
the login handler checks; `POST /login?username=victim` with no username in the body is counted
in the client's missing-key bucket and binds no username.

### Key normalization (DoS protection)

Rate-limit keys extracted from requests (usernames, tokens, IP addresses, custom headers) are
normalized before use:

- **Null or blank extractor result → a missing-key bucket per client address** (`(none):` plus
  the address, an IPv6 client's /64, with the limiter's own limit and window). A request with a
  missing header or form field is limited exactly as `perIp` would limit it, rather than silently
  bypassing the limiter, and keyless requests from one address never use up another's budget (an
  earlier design counted them all in one global bucket, so five visitors viewing a page the
  limiter covered locked out the sixth). The bucket keys cannot be produced from a request, so a
  client that sends the literal value `(none):203.0.113.9` gets its own bucket and cannot drain
  that address's. Where a missing key genuinely means "nothing to limit", opt out explicitly, and
  bound those requests some other way (here, per IP):

  ```java
  app.before("/api/*", RateLimiter.perIp(300, "1m"));
  app.before("/api/*", RateLimiter.perKey(req -> req.header("X-Api-Key"), 1000, "1h")
      .exemptMissingKey());
  ```

  Don't register a key limiter on a path whose page views carry no key (a `GET /login` with no
  username) without restricting it to the submissions, as the login example above does:
  otherwise each view uses up one of that visitor's keyless requests, and a visitor who reloads
  the page `limit` times is locked out of it. Dev mode logs a warning, once per limiter, the first
  time a key limiter counts a keyless `GET` or `HEAD`.
- **Surrounding whitespace is stripped**, so `"victim "` and `"victim"` are one bucket. **Case is
  not folded**: tokens and API keys are case-sensitive. When the key is an identity that is
  case-insensitive (a username, an email address), lower-case it in the extractor, as above.
- **Keys longer than 64 characters → SHA-256 hex digest** (always exactly 64 chars). Long
  user-controlled values (e.g. bearer tokens, forged headers) would otherwise create unbounded
  storage — millions of bytes of map entries locally, or arbitrarily large rows in
  `brace_counters`. Hashing caps the key size while preserving the collision-resistance needed
  for correct bucketing.
- **Keys containing a control character → SHA-256 hex digest** too. Postgres refuses a NUL in a
  text parameter, so a raw `username=a%00` key would otherwise fail every shared-counter write.

The cap is applied before any prefix or window-slot decoration, so it holds equally in
per-process and shared (Postgres) modes.

**`perIp` keys an IPv6 client on its /64.** A site is normally given at least a /64, so one client
can rotate through billions of source addresses; keying each address separately would give it a
fresh budget per request. IPv4 clients (and IPv4-mapped IPv6 addresses such as `::ffff:203.0.113.7`)
are keyed on the full address. Clients that share a /64 share a budget.

**Distinct keys are capped per limiter** (default 100,000). Each key holds memory, and on Postgres
a `brace_counters` row, for its whole window. Once a limiter tracks that many keys (a warning is
logged, at most once a minute):

1. A new key takes the place of a tracked key that is **under** its limit, the least recently
   used of a small sample. The forgotten key loses its count so far (fewer than the limit), an
   accepted bypass: a client that floods fresh keys to reset one key's count pays a whole table
   of requests for fewer than `limit` extra attempts. On Postgres the forgotten key's row is
   deleted at the next flush, so the table stays bounded too.
2. A key **at or over** its limit is never forgotten before its window ends, so a flood cannot
   unblock a client that is being limited.
3. Only when the sample finds nothing but such blocked keys does a new key fall back to an overflow
   bucket for its client address (`ipBucket(req.ip())`; at most `maxKeys / 10` of them), and past
   those, to one shared overflow bucket.

So a flood of fresh keys no longer locks out new legitimate keys (the earlier design sent every
new key to one shared overflow bucket once the table was full). Eviction looks at a fixed number
of keys under a per-limiter lock taken only on that full-table path, so no request scans the
table. Raise or lower the cap per limiter with `.maxKeys(n)`, e.g.
`RateLimiter.perIp(100, "1m").maxKeys(500_000)`.

**Two limiters with the same type, limit and window share buckets on Postgres.** The counter key
is built from the limiter's label (`perKey(5/15m)`), the only identity a limiter has across a
fleet, so on Postgres two such limiters count every key together, their missing-key and overflow
buckets included. In per-process mode each limiter object counts on its own. Give limiters on
different paths different limits or windows if they must be independent.

Cache keys get the same treatment in the `Cache` facade, on both backends: a key with a control
character, or longer than 512 UTF-8 bytes (a page path or `vary` value near the 8 KB URI limit
exceeds Postgres's index row limit), is stored as its first characters (up to 128, stopping at a
control character) followed by a SHA-256 digest of the whole key. This is invisible to callers;
tags still invalidate the entry, and `deletePrefix` still reaches it through the kept prefix.

A key (rate-limit or cache) with an **unpaired surrogate** is hashed too, from an encoding that
keeps it distinct: pgjdbc sends a lone surrogate as `?`, so `"a\uD800"` and `"a\uD801"` were one
row on Postgres.

`deletePrefix` with a prefix that reaches past what a hashed key keeps readable (one containing a
control character, an unpaired surrogate or `#sha256:`, or longer than 128 characters) deletes the
keys stored as-is that start with the whole prefix, plus the hashed keys whose readable part is
exactly the prefix's readable part (every hashed key that starts with the prefix has that). So
`deletePrefix("search:\u0001")` and `deletePrefix("search:#sha256:")` no longer clear
`search:alpha` (they used to clear all of `search:`), but they do clear other hashed keys that
share the readable part, such as `search:\u0002`; a 200-character prefix clears hashed keys that
share its first 128 characters. Over-deletion is a cache miss, never a stale entry.

### Trusted proxies and IP spoofing

`RateLimiter.perIp` uses `req.ip()`, which respects trusted-proxy configuration. Without
`app.trustedProxies(...)`, `req.ip()` returns the direct socket peer — headers are ignored. With
trusted proxies configured, Brace uses **rightmost-untrusted** semantics on `X-Forwarded-For`
(walking right to left, skipping trusted CIDR entries) so spoofed leftmost entries are ignored.

See the [Trusted Proxies](#trusted-proxies) section for configuration. Configuring trusted proxies
correctly is a prerequisite for effective IP-based rate limiting — without it an attacker can
bypass per-IP limits by forging `X-Forwarded-For`.

Because this misconfiguration is silent (behind a proxy or CDN every request shares the proxy's
address, so a per-IP limit quietly becomes site-wide), `Brace.start()` logs a warning when a
`RateLimiter.perIp` middleware is registered without `app.trustedProxies(...)`. Ignore the
warning only if clients genuinely connect to the app directly.

### Database failure posture

When Postgres is in use (multi-server mode), every rate-limited request increments a shared
counter. If the shared counter fails (connection pool exhausted, DB outage), Brace **falls back
to per-instance counting** for that request rather than returning a 500 or admitting the request
without any check. During an outage the effective limit across a fleet of N instances becomes
approximately `limit × N`, so brief over-admission is possible — this is intentional: the
alternative (turning every rate-limited endpoint into a 500) is worse. A warning is logged at
`WARN` level for each request that falls back.

Buffered counts are written in statements of at most 5,000 keys (Postgres refuses a statement
with more than 65,535 parameters). A statement the database refuses with a data error (SQLState
class 22 or 54: a key it cannot store) is split in half, and the halves again, until the refused
key is isolated; that key's buffered count is dropped with a warning instead of failing every
later flush, at a cost of a few dozen statements rather than one per key. Any other failure
(the database unreachable, SQLState class 08, 53 or 57, or an error that is not about the data)
restores every count not yet written and retries after a one-second back-off. A flush runs on the
request that filled the buffer, so it stops after 2 seconds and keeps the rest for the next one.

### Best Practices

1. **Configure trusted proxies** first (otherwise IP-based limiting is ineffective — see above)
2. **Use different limits** for different endpoints:
   - Login: 5 attempts per 15 minutes
   - API: 100-1000 requests per hour
   - Anonymous: 10 requests per minute
3. **Combine with authentication** for logged-in users

---

## Deploying in a Container

`brace new` scaffolds a container setup meant to be deployed as-is:

- **`Dockerfile`.** A Maven build stage, then a distroless Java 25 runtime: a JRE and nothing
  else, so no shell or package manager for an attacker to use. Both base images are pinned by
  digest, so a rebuild can't pick up a different image, and `.github/dependabot.yml` proposes
  digest updates weekly. The process runs as uid 65532 (`USER 65532:65532`), and the app's files
  are owned by root, so the process can't modify its own code or config. The `ENTRYPOINT` is
  exec form: `java` is PID 1 and gets `docker stop`'s SIGTERM. JVM flags come from
  `JAVA_TOOL_OPTIONS`, which the health-check JVM also reads: flags that bind a port, write a
  file or load an agent don't belong there unless the `HEALTHCHECK` is replaced by the
  platform's HTTP check.
- **`.dockerignore`.** Keeps `application.conf` (the dev session secret), `*.key` (the ops
  private key), `.env` files and `target/` out of the build context. The image's config is
  `application.conf.example`, whose values all come from environment variables.
- **`compose.yaml`.** The reference deployment. The app container has a read-only root
  filesystem (`/tmp`, where uploads spill, is an in-memory tmpfs), drops every Linux
  capability, sets `no-new-privileges`, and publishes its port on loopback only, for a
  TLS-terminating reverse proxy in front. Postgres sits on an `internal` network with no
  published port, reachable only from the app. Secrets are required environment variables
  (`${SESSION_SECRET:?…}`), never values in the file.
- **Health.** `GET /__brace/health` answers `200 ok` before routing and middleware, so an
  app-wide login guard or rate limiter can't fail it. It reveals nothing (no version, no
  database status) and isn't logged, counted or recorded by analytics. The image's
  `HEALTHCHECK` polls it with `com.larvalabs.brace.HealthCheck`, since the image has no curl.
- **Listen address.** Brace listens on every interface by default, which a container needs.
  Outside one, with a reverse proxy on the same host, bind to loopback with
  `app.host("127.0.0.1")`.

Behind Traefik (Dokploy) or another proxy container, set `TRUSTED_PROXIES` to the proxy's own
address or its network's CIDR so `req.ip()` sees real clients; see
[Trusted Proxies](#trusted-proxies).

## Installing and Updating Brace

`install.sh`, `brace self-update` and the launcher's fetch of a project's pinned version download
code you then run, so they use **https only**, redirects included (`file://` for local test
trees). Plain `http://` in `BRACE_RELEASE_BASE` or `BRACE_LATEST_URL` is refused unless
`BRACE_ALLOW_INSECURE_DOWNLOAD=1`, which warns. Release zips are not signed yet; TLS to GitHub
authenticates them (see `RELEASE.md`, "Download integrity").

---

## Ops Endpoints

Ops endpoints (`/ops/*`) provide powerful observability and control. **Secure them carefully.**

### Authentication

Ops endpoints use public key authentication:

```java
app.ops("authorized-keys");
```

The `authorized-keys` file contains public keys of clients allowed to access ops endpoints.

Clients mint a bearer token via `POST /ops/auth` (protocol v3): an Ed25519 signature over
`"brace-ops-auth-v3\n" + origin + "\n" + publicKey + "\n" + timestamp + "\n" + nonce`, where
the origin is the server the client means (`https://app.example.com`), the timestamp must be
within ±30 seconds of server time and the nonce is fresh random (16+ bytes, base64url) per
attempt. Binding the public key means a captured signature is only valid for the key that
produced it; the nonce makes each signed request single-use; the origin makes it valid only at
the server it names. The exact wire format is in `docs/agent-ops-guide.md`.

The nonce memory is per instance and empty at startup, so the server also refuses a request whose
timestamp is earlier than its own start time (a request captured during a deploy cannot be
replayed once after the restart). Each key has its own quota of remembered nonces (5,000 of the
100,000 overall), so one key, even a read-scoped one, cannot fill the memory and block other
operators' logins.

**The signature is bound to a server.** The CLI reads the server's URL from the committed
`.brace` file, so a pull request that points `ops.prod.url` somewhere else could collect a fresh
signature and relay it to the real server within the time window. The server accepts a v3
signature only for an origin listed with `app.opsOrigins("https://app.example.com")` (or the
`BRACE_OPS_ORIGINS` environment variable, comma-separated), compared exactly: scheme, host and
port. The request's `Host` and `X-Forwarded-Host` play no part, because the client relaying a
signature chooses those: an app reachable directly, or a proxy that passes a client's
`X-Forwarded-Host` through to an app that trusts the proxy, would let it claim any host. Every
mode except `dev` (including an unset `brace.mode`, as in older scaffold Dockerfiles) refuses to
start with ops on and no origin configured. In dev mode and the test harness, with none set, the
origin's host must match the request's host instead, which is no defense against a client that
can reach the app directly.

The CLI keys its token cache by origin (and checks the origin stored in the cache file), so a
token minted for one server is never sent to another; it refuses an ops URL that is more than an
origin (user info, path, query); and it warns when a project's ops URL changes from the last
server it logged in to (remembered in `~/.brace/ops-origins.json`; a best-effort hint, not a
control: a new clone or a new `ops.env` won't trigger it).

Protocol **v2 is removed** as of 0.1.11: it signed no origin, so a signature was good at any
server. A v2 body gets a 401 naming the cause; upgrade the CLI with the server.

Protocol **v1 is removed** as of 0.1.8. It signed the timestamp alone — not bound to the key,
no nonce — so a captured request was replayable verbatim inside the ±30 s window to mint a
fresh token at the key's full scope ceiling. A v1 body now gets a 401 naming the cause; clients
older than 0.1.7 must be upgraded.

**Caching.** Every `/ops/*` response carries `Cache-Control: no-store`. This matters for the
cookie channel specifically: RFC 9111 already forbids shared caches from storing a response to
a request carrying `Authorization`, but no such rule covers a cookie-authenticated GET, and
`/ops/dashboard` shows one operator's view of the app.

The `__brace_ops_session` cookie is scoped to `Path=/ops`, so an operator credential is not
attached to every application request where any handler could read it via `req.cookie(...)`.

**Credential channels (general endpoints).** All ops endpoints except the browser exchange
handoff accept credentials through exactly two channels:

1. `Authorization: Bearer <token>` header — the standard channel for the CLI and scripts.
2. The `__brace_ops_session` httpOnly cookie — set by the browser exchange flow below, and the
   only credential the dashboard and analytics pages use.

`?token=` query-parameter credentials are **not accepted** on general ops endpoints. Tokens in
URLs leak into proxy access logs, browser history, and the `Referer` header of outbound links.
Pass credentials via the `Authorization` header or the session cookie.

**Browser exchange flow.** The `brace dashboard` CLI command calls `POST /ops/auth/login-token`
(bearer only: the session cookie is refused there), receives a short-lived (60s, never past the
bearer's own expiry), scope-capped login token, and opens the
browser at `/ops/auth/exchange?token=<loginToken>`. The exchange endpoint is the only place
`?token=` is accepted — it is the browser-redirect handoff, and there is no other channel that
can carry a credential into a plain GET redirect. On success, the server sets an 8h httpOnly
session cookie and redirects to the dashboard. The exchange response carries
`Referrer-Policy: no-referrer` and `Cache-Control: no-store` so the token-bearing URL is
not forwarded to any outbound link and is not stored in proxy or browser caches.

**Replay trade-off (exchange).** The login token is stateless and HMAC-verified — no
server-side store is needed, so the exchange works behind a load balancer even if the redirect
hits a different instance than the one that issued the token (B5). Within the 60s TTL the same
login token can be exchanged more than once; single-use would require fleet-wide shared state
that ops can't assume. The TTL is kept very short to limit the replay window.

**Replay suppression (auth, per-instance, best-effort).** Used nonces are tracked in memory on
each server instance, never fleet-wide — ops deliberately works without shared state (no
database required), so behind a load balancer a captured auth request remains replayable against
a *different* instance until the ±30s timestamp window closes. This residual window is accepted;
the mitigations are HTTPS (an attacker should never observe the request body) plus the
recommendations below. A captured request also cannot be replayed against an instance that
started after it was signed: auth requests timestamped before the process started are refused.
The old v1 and v2 protocols are removed (see above).

**Key revocation.** Tokens are bound to the key that minted them. At startup the server takes
the fingerprints of the keys in `ops-authorized-keys` and rejects any bearer token, ops session
cookie or login token whose `kid` is not among them, or that has no `kid`, and caps every
token's scope at its key's current ceiling. Removing a key, or lowering it to `scope:read`, and
restarting or redeploying takes effect on its outstanding tokens immediately, although the token secret is derived from
the session secret and unexpired tokens (up to 24 h bearer, 8 h cookie) would otherwise survive
the restart.

**Scope tokens in `ops-authorized-keys`.** The file is UTF-8 (a leading byte-order mark is
ignored), one key per line:

```
KEY [scope:read | scope:control] [label words ...] [# comment]
```

- `#` at the start of a line, or after whitespace, starts a comment that runs to the end of the
  line. Blank and comment-only lines are ignored. (`ci#1` is label text: no whitespace before
  the `#`.)
- The first token is the public key. The rest may come in any order.
- Any token containing `:` or `=` must be exactly `scope:read` or `scope:control` (`scope:` in
  lowercase, the value case-insensitive), and at most one appears per line. Anything else with
  a `:` or `=` fails startup: `scope: read`, `Scope:read`, `scope=read`, `--scope=read`,
  `role:read`, `scpoe:read`, `scope:read-only`, or a second scope token.
- Every other token is label text, including `control`, `ro`, `read` and `scope-ci`, with one
  exception: `read`, `ro`, `readonly`, `read-only` or `control` as the **only** token after the
  key (`KEY read`, the classic mistake) fails startup with a message to write `scope:read`.
- A line with no scope token is `control` (backward compatible) and is logged as such at boot.

Failures name the file and line number, never the key: a silent fallback would hand an agent a
full-privilege key. A key listed twice keeps the lower scope. Each key's fingerprint and scope
is logged at boot (`ops.key`). A token whose payload has no scope claim, or one the server does
not recognise, is treated as `read`, as is an unrecognised `scope` requested at `/ops/auth`.

### Ops pages in the browser

`/ops/dashboard` and `/ops/analytics` are cookie-only. Their htmx polling and the CONTROL
buttons (resolve an error, clear the cache) send the `__brace_ops_session` cookie and nothing
else; no token is written into the page. Until 0.1.11 the dashboard embedded a 2-hour bearer
token (up to CONTROL scope) in `hx-headers`, so any script running on the app's origin in an
operator's browser could `fetch('/ops/dashboard')` (same-origin, so the cookie went along) and
read it out of the HTML. The httpOnly cookie can't be read by page script.

**Each token works on one channel.** Every ops token carries a type: `bearer` (issued to the CLI
by `POST /ops/auth`, accepted only in `Authorization: Bearer`), `login` (the 60-second handoff,
accepted only by `/ops/auth/exchange`), and `session` (the cookie the exchange sets, accepted
only as the cookie). `POST /ops/auth/login-token` takes only a bearer token. Before this, script
on the app's origin could post to it with the operator's cookie, read a CONTROL login token from
the response, use it as a bearer from anywhere, and mint the next one with it. A token without a
type (minted before 0.1.11) is refused; the CLI re-authenticates on the 401.

**Cross-origin protection for cookie-authenticated changes.** The ops routes are
`.csrf(false)`, since the CLI posts with a bearer token. A mutating ops request
(`POST`/`PUT`/`PATCH`/`DELETE`) authenticated by the cookie must come from a page on the same
origin: `Sec-Fetch-Site: same-origin` when the browser sends it, otherwise an `Origin` whose
host and port are the ones requested. Anything else is refused with `403 {"error":"cross_origin_request"}`
and audited. `SameSite=Strict` already keeps the cookie off cross-site requests, but not off a
sibling subdomain's: `evil.example.com` is the same *site* as `app.example.com`, so its form
post would carry the cookie. A bearer-authenticated request needs no such check, because a
cross-origin page can't set `Authorization`.

**The ops Content-Security-Policy.** Every response from a framework `/ops` route carries:

```
Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'
```

plus `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and `Referrer-Policy:
no-referrer`, and any `Access-Control-*` headers the app's middleware added are removed (an app
CORS policy that reflects origins with credentials would otherwise let a same-site page read ops
JSON). The pages load their
JavaScript and CSS from `/__brace/ops.js`, `/__brace/ops.css` and `/__brace/ops-analytics.css`
and contain no inline script, event handler or `style=""` attribute. The headers are set at the
response choke point *after* the app's after-middleware, so they replace whatever policy the app
sets: a lax app policy can't weaken the ops pages, and an unusual one can't break them. The ops
policy is stricter than `SecurityHeaders.strict()` in every directive, so an app using `strict()`
loses nothing there.

**What remains.** An XSS on the app's origin still runs with the operator's ops session in the
same browser: it can't read the cookie or obtain a token, but it can drive same-origin requests,
including `POST /ops/cache/clear` with a valid `Sec-Fetch-Site: same-origin`. The cookie is
scoped to `Path=/ops`, which limits where it's sent, not who on the origin can trigger a
request. For high-value apps, serve ops from its own hostname (below), so that the app's origin
and the ops origin are different and the browser keeps them apart.

### Security Recommendations

1. **HTTPS only:** Never expose ops endpoints over HTTP
2. **Restrict at reverse proxy:** Use IP allowlisting at nginx/Caddy
3. **Don't expose publicly:** Ops endpoints should not be internet-accessible
4. **Rotate keys regularly:** Implement key rotation for ops access
5. **Monitor access:** Log all ops endpoint access
6. **Separate hostname for high-value apps:** route `/ops/*` only on a dedicated hostname
   (e.g. `ops.app.example.com`, reachable from the operator network) and refuse it on the
   public one. The ops cookie and pages then live on an origin where no application page, and
   so no application XSS, ever runs. Point `ops.prod.url` in `.brace` at the ops hostname for the CLI.

### Deployment Pattern

```nginx
# nginx config
location /ops/ {
    allow 10.0.0.0/8;      # Internal network only
    deny all;
    proxy_pass http://app;
}
```

With a separate ops hostname (recommendation 6), the public server refuses `/ops/` outright and
only the internal one proxies it:

```nginx
server {
    server_name app.example.com;
    location /ops/ { return 404; }
    location / { proxy_pass http://app; }
}
server {
    server_name ops.app.example.com;
    allow 10.0.0.0/8;
    deny all;
    location /ops/ { proxy_pass http://app; }
    location /__brace/ { proxy_pass http://app; }   # the ops pages' JS and CSS
    location / { return 404; }
}
```

---

## Analytics

`app.analytics()` counts page views on the server. Its security properties, and what to check
when you turn it on (full guide: [analytics.md](analytics.md)):

- **No new public endpoint.** Data comes only from requests the app already served; there is no
  ingest URL for anyone to post fake events to. The viewing endpoints (`/ops/analytics`,
  `/ops/analytics/data`, `POST /ops/analytics/ignore`) are ordinary ops endpoints: `read` scope,
  `Cache-Control: no-store`, the same two credential channels as above.
- **What's stored.** Per view: time, day/hour, a 64-bit visitor hash, the path, the referring
  host (or `utm_source`/`ref`), device/browser/OS family and an optional country code. No IP, no
  user agent, no query string, no full referrer URL. The IP and user agent stay in memory for up
  to ~10 seconds until the next flush hashes them.
- **Visitor hash.** SHA-256 over a random per-day salt, the host, the IP and the user agent,
  truncated to 64 bits. The salt lives in `brace_analytics_salts` and is deleted 5 minutes after
  its day ends, after which a stored hash can't be matched to an IP, even by brute force over the
  IPv4 space. During the day, someone who can read the database and already has a candidate IP
  and user agent can test them against stored hashes. Treat database read access accordingly.
- **Daily summaries hold no visitor IDs.** `brace_analytics_daily` stores only per-day counts per
  page, source, device, browser, OS and country, and is kept indefinitely. The paths in it are
  the same redacted paths as the raw rows, so the advice below applies to it too.
- **Paths can carry secrets.** Concrete paths are stored and shown on the dashboard, after two
  redaction passes that reuse the error-store rules (see below): a route parameter with a
  sensitive *name* (`Redactor.isSensitive`, e.g. `{token}`, `{apiKey}`) is stored as its
  placeholder, and a segment with a secret-shaped *value* becomes `[redacted]`. A short code under
  an ordinary name (`/invite/{code}`) passes both; mark such routes `.analytics(false)` or rename
  the parameter.
- **Bounded growth.** Only matched routes returning HTML are counted, so a scanner requesting
  random URLs produces 404s, not rows. The in-memory buffer is capped at 20,000 views; beyond that
  views are dropped and tallied. Two more caps bound table growth against a client that sends
  browser-looking requests in a loop: one visitor (host, IP, user agent) adds at most 30 rows per
  10-second flush window (`maxViewsPerVisitorPerFlush`), and an instance inserts at most 500,000
  rows a day (`dailyRowBudget`, one warning when it is reached). Views over either cap are not
  stored but are tallied as `dropped`, which the dashboard and `notCounted` show, so a capped day
  reads as capped rather than silently low. Every stored string is length-capped, and every value the page
  renders is HTML-escaped.
- **The "don't count" cookie.** `POST /ops/analytics/ignore` sets `__brace_analytics_ignore=1`
  (HttpOnly, `SameSite=Lax`, `Path=/`, `Secure` except on localhost). It carries no credential;
  it only marks the browser's visits as excluded. The endpoint is CSRF-exempt like the other ops
  POSTs: it requires the `SameSite=Strict` ops cookie or a bearer token, and its only effect is a
  cookie on the caller's own browser.
- **`next=analytics` on the login exchange** is a fixed allowlist entry, not a URL, so it can't be
  used as an open redirect.

## Error Store Redaction

Brace scrubs error records at capture time (before they reach the database or
`/ops/errors`). Two redaction passes run inside `BraceHandler` at the point the
exception is caught.

The same value-shaped pass also runs **in the log and stats sinks themselves**:
`Log.request`/`Log.error` redact the request path (and the exception message)
before the entry reaches stdout or the `/ops/logs` ring buffer, and `Stats`
redacts route keys and error messages before they are served on `/ops/status`.
A reset token in a URL path is therefore scrubbed on every request — including
successful ones — not only when an exception is thrown.

### Name-based redaction (query params, request headers, log fields)

Query-string parameters, request headers and log fields whose names look
sensitive have their values replaced with `[REDACTED]`. Matching is on the
normalized name (lowercased, hyphens and underscores stripped):

- **Substring match** for `authorization`, `cookie`, `password`, `passwd`,
  `pwd`, `secret`, `token`, `apikey`, `bearer`, `credential`, `privatekey`,
  `accesskey`, `sessionid`, `csrf`, `jwt`, `assertion`, `signature`, `authkey`
  and `nonce` (`Redactor.SENSITIVE`). This deliberately over-redacts: a field
  named `token_count` is also redacted. `Cf-Access-Jwt-Assertion`,
  `client_assertion`, `X-Amz-Signature` and `X-Auth-Key` are caught here.
- **Whole-name match** for the short names `code`, `sig` and `otp`
  (`Redactor.SENSITIVE_EXACT`): `?code=` (an OAuth authorization code),
  `?sig=` (an Azure SAS signature) and `?otp=` are redacted, while `zip_code`,
  `status_code`, `encode`, `postcode`, `barcode`, `signal`, `design` and
  `footprint` are not. This applies to log fields too: a log field named
  exactly `code` is redacted.

Query parameter names are percent-decoded before matching (`%74oken=` is
`token=`); a malformed escape is kept literally, never an error.

### Value-shaped redaction

High-entropy tokens are detected by shape and replaced with `[redacted]`
(or the whole query value with `[REDACTED]`), regardless of field name. The
heuristic (`Redactor.isSecretShaped`) treats a segment or
whitespace/punctuation-delimited token as a secret when **all** of these hold:

1. Length ≥ 16 characters.
2. Every character is in the base64url-or-hex alphabet (`[A-Za-z0-9_\-+/=]`).
3. Contains at least one ASCII digit **and** at least one ASCII letter (rules
   out purely numeric IDs and purely alphabetic slugs).

Two dotted shapes are caught as special cases: JWTs (two dots dividing three
base64url parts, the outer two at least 10 characters) and two-part
`payload.signature` tokens (one dot, at least 20 base64url characters on each
side, a digit and a letter: the ops bearer token's own shape). Any other
dotted value (a file name, a hostname, a version) is never secret-shaped.

Where it runs:

| Where | How |
|---|---|
| Request path (error record, logs, stats) | `redactPath`: each `/`-segment |
| Exception message and stack trace | `redactMessage` / `redactStackTrace`: each token |
| Query string (error record `requestDetail`, stats) | `redactQuery`: a value whose raw or percent-decoded form contains a secret-shaped token, or is a URL/path with a secret-shaped `/ ? & #` piece (`?next=/reset/<token>`), becomes `[REDACTED]`; a bare `?<token>` part too |
| URL-valued request headers (`Referer`, `Origin`, `Location`, `Content-Location`, `X-Original-URL`, `X-Forwarded-Uri`, `X-Rewrite-URL`, `HX-Current-URL`) | `redactUrl`: path through `redactPath`, query and fragment through `redactQuery`, any `userinfo@` replaced; scheme, host and port kept. A value that is not a URL gets the free-form pass |
| Other request headers | `redactValue`: like `redactMessage`, and a URL or path token with a secret-shaped piece is replaced. A JWT in `X-Amzn-Oidc-Data` or `Sec-WebSocket-Protocol` is caught here |
| Protocol-metadata headers | **kept verbatim**: `Accept*`, `Content-Type`, `Content-Length`, `Host`, `User-Agent`, `If-None-Match` and the other conditional headers, `X-Forwarded-*`, `Forwarded`, `X-Real-IP`, `Via`, request ids and trace context (`X-Request-Id`, `X-Correlation-Id`, `traceparent`, `X-Amzn-Trace-Id`, `Cf-Ray`, B3), `Sec-Fetch-*`, `Sec-Ch-*`, the WebSocket key/version, and htmx's request headers (`Redactor.VERBATIM_HEADERS`). Their values are never a user's credential, and the shape rule would misfire on an ETag, a multipart boundary or a request id |

The request headers are redacted by `Redactor.redactHeaders`, in that order:
a sensitive name first, then the URL, verbatim or free-form rule.

**What is intentionally kept visible:**

| What | Why |
|---|---|
| UUIDs (`8-4-4-4-12` hex), anywhere | Usually record identifiers, not bearer secrets; needed for debugging. A request id that is not a UUID is kept only in the request-id headers above |
| Purely numeric segments and values | Page IDs, order numbers, user IDs |
| Short values (< 16 chars) | Route labels, action names, short hex such as a fingerprint `app.3f9a1c2b.js` (any dotted file name survives, whatever its hash length) |
| Email addresses | PII, not a credential; `@` and `.` are outside the token alphabet |
| Comma- or `#`-separated lists (`#ff0000,#00ff00`) | Each item is short |
| Exception message text without high-entropy tokens | Needed to diagnose the error |

What the shape rule cannot tell from a secret, and so redacts: a 16+ character
run of letters, digits, `-` and `_` with both a digit and a letter, for example a
dated slug `2024-black-friday-sale`, a dash-joined hex list
`ff0000-00ff00-0000ff`, a 16+ character hex hash, or an absolute path with a
digit in a free-form header (`/var/app/releases/20241105`). These look exactly
like a grouped recovery code or a standard-base64 secret.

The same pass runs over stored **stack traces** (`Redactor.redactStackTrace`, at
`Stats.recordError` and `ErrorStore.record`): every message line, including `Caused by:` and
`Suppressed:` lines and multi-line messages, is redacted, and frame (`at …`) lines are kept
as is. A message redacted on its own used to reappear verbatim in the trace.

The heuristic is conservative by design. An over-eager redactor makes error
records useless. If your application routes embed raw bearer tokens or reset
secrets in path positions, consider moving them to opaque database-lookup keys
so the route retains meaning even after redaction.

### One bad record cannot stall the store

Error records carry client-controlled text, so `ErrorStore` makes sure no single record can
block the others:

- `ErrorStore.record` replaces NUL characters with U+FFFD. A request such as `?page=1%00` puts a
  NUL in a `NumberFormatException` message, and Postgres text cannot hold one. Replacing it
  instead of deleting it keeps already-redacted tokens from being joined into one secret-shaped
  token. The error type and route are clipped to their 255-character columns; the route starts
  with the client-chosen HTTP method.
- Each upsert in a flush runs under its own savepoint. A row the database rejects is rolled
  back alone, and the rest of the batch commits.
- A rejected row is retried once, in the same flush, with its payload stripped: the message is
  replaced by a fixed placeholder (`[details not stored: the database rejected this error
  record]`, no request-derived text) and the stack trace, request detail, queries and headers
  are left empty. The error kind's count and the regression notification survive. Only if that
  retry is rejected too is the entry retried on later flushes, and dropped after 5 rejected
  flushes.
- A connection-level failure (database down, pool exhausted, failure to open the session or
  begin the transaction) is not blamed on any entry. The whole batch is kept and retried,
  bounded by the 1,000-kind pending buffer as before. A row error whose SQLState is in a
  connection-level class (`08`, `53`, `57`, `40` or `25`) counts as a connection failure only
  when the connection also fails `isValid`; on a live connection (for example `57014`, a
  statement timeout on one huge row) it is a row rejection like any other.
- Failures are logged at most once a minute, naming the exception class, SQLState and the error
  type of any dropped entry. Request-derived content (messages, paths, headers) is never logged.

Before the 2026-11 security review (M5), one such request failed every later flush on
Postgres, so nothing reached `ops_errors` and regression alerts stopped until restart.

### Regression notifications carry request-derived text

A new error kind's message can contain text a web user chose. `WebhookNotifier` (Slack and
Mattermost incoming webhooks) therefore puts the type, route and message each in an inline code
span, which both render literally: Mattermost markdown links, images (a tracking pixel) and
emphasis are inert. Inside the span, a backtick (which would close it) becomes `ˋ`, `<` `>` `&`
(Slack control sequences such as `<!channel>`) become `‹` `›` `＆`, `@` becomes the full-width
`＠` so no `@channel` mention fires, and line breaks and control characters become spaces. The
message is cut to 500 characters first. Backslash escaping was not used because Slack does not
honour it. `MailerNotifier` sends plain text.

---

## Outbound Mail

`Mailer` over `smtp://user:pass@host:587` **requires STARTTLS**
(`mail.smtp.starttls.required=true`). If the relay's EHLO does not offer it, the send fails
instead of continuing in cleartext, so a network attacker cannot strip the offer and read the
SMTP password and mail bodies (password-reset links). `smtps://` is implicit TLS. A trusted
local relay that cannot do TLS (a sidecar on localhost) opts out explicitly with
`smtp+plain://localhost:25`, which uses STARTTLS when offered but never requires it. Credentials
on an `smtp+plain://` URL are allowed but logged as a warning once, when the `Mailer` is
constructed (the host is named; the user and password never are), because without TLS the AUTH
exchange is cleartext. The scheme is compared case-insensitively (`SMTPS://` is implicit TLS).
Dev mode (no SMTP URL) only captures mail and is unaffected.

`Mailer.to(...)` takes exactly one address and parses it strictly, so a recipient taken from a form
cannot add recipients (`"a@b.c, victim@x.y"` throws). The same holds for `cc(...)`, the
per-message `from(...)`, and `Mailer.from(...)`/`Mailer.replyTo(...)` (checked when configured).
Strict parsing yields exactly one address or throws; on top of that, group syntax
(`g: a@b.c, victim@x.y;`, several mailboxes as one address), route addresses
(`<@relay:a@b.c>`) and control characters are refused. A comma inside a quoted display name
(`"Doe, J" <a@b.c>`) is one mailbox and is accepted. `@Email` likewise rejects `,` `;` `<` `>`,
`(` `)` `"`, whitespace and control characters, and accepts only a value that parses as one bare
address equal to itself: an RFC 822 comment (`evil@attacker.tld(ceo@corp.example)`) would
otherwise be sent as the display name. `MailerNotifier` accepts several recipients, validates each at
construction and sends each its own message.

## Secrets Management

### Secret Quality

- **Minimum:** 32 bytes of random data
- **Generate with:** `openssl rand -base64 32` or `uuid4().toString()`
- **Never use:** "secret", "changeme", "test123", predictable values

### Environment Variables

Store secrets in environment variables, not in code:

```java
var secret = System.getenv("SESSION_SECRET");
if (secret == null) {
    throw new IllegalStateException("SESSION_SECRET not set");
}
app.sessions(secret);
```

### Configuration Pattern

Use `Config` for environment-aware configuration:

```java
var config = Config.load();
app.sessions(config.require("session.secret"));
app.ops(config.get("ops.keys.path", "authorized-keys"));
```

### Secret Rotation

When rotating secrets:
1. **Sessions:** Users will be logged out on rotation
2. **Ops keys:** Add new keys before removing old ones
3. **Database credentials:** Use connection pooling with graceful reload

---

## Additional Resources

- [OWASP Top 10](https://owasp.org/www-project-top-ten/)
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
