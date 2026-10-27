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
- [Installing and Updating Brace](#installing-and-updating-brace)
- [Ops Endpoints](#ops-endpoints)
- [Analytics](#analytics)
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

Plain `before(...)` middleware never counts as access. The previous model —
`requireSession("/admin/*", …)` path-pattern guards — is removed.

The framework's own `/ops/*` routes follow the same rule: each is registered with the ops
credential requirement (`ops:read` or `ops:control`), the handlers carry no auth code, and an
ops route registered without one fails `start()`. The app's default access never applies under
`/ops`.

`/ops/routes` and the startup banner list each route's resolved access.

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
- JSON bodies, headers and cookies; queries run outside a request (jobs, `withSession`); and,
  past the first 1,000 values or 64 KB of a request, the rest.

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
`/__brace/csrf-htmx.js` copies it into `X-CSRF-Token` on every non-GET htmx request.

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
  held until its closing quote and normalized the same way. If the template's own text fixed the
  scheme or made the URL relative (`href="mailto:${email}"`, `href="/users/${id}"`), it's kept;
  if user data decides the scheme (`href="${url}"`, `href="${a}${b}"`, `href="${scheme}://${host}"`)
  only `http`, `https`, `mailto` and `tel` pass, plus raster `data:image/` URLs (png, jpeg, gif,
  webp, avif, bmp; never SVG) on `src` and `poster`. Anything else renders as
  `about:invalid#blocked`.
- **Script attributes.** `on*`, `hx-on*` (`hx-on:click`, `hx-on-click`, `data-hx-on:*`),
  `hx-vals`, `hx-vars`, `hx-headers`, `hx-request` and `hx-trigger`. User data is escaped with
  `\uXXXX` sequences for every character that can end a string or start markup or a template
  substitution, which is valid in JavaScript strings, template literals and JSON, so JSON
  `hx-vals`/`hx-headers` keep working with quotes in the data. HTML escaping alone is undone by
  the browser before the code runs (`&#39;` is a quote again), and JTE's JavaScript escaping for
  `on*` left `&` alone, so an entity in user data still closed the string.
- **`srcdoc`.** User data is escaped twice, so it is text inside the frame, not markup. Template
  markup inside `srcdoc` isn't URL-checked; an HTML preview should be sanitized HTML written with
  `$unsafe{Html.escape(...)}` in an iframe with `sandbox`.

Not covered: a `@template` call inside an attribute value renders in body context (JTE's
behavior), so its output gets neither attribute escaping nor the URL check.

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
// Trust private network proxies
app.trustedProxies("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

// Trust specific proxy IPs
app.trustedProxies("10.0.0.5", "10.0.0.6");

// Trust cloud provider ranges (example: AWS)
app.trustedProxies("10.0.0.0/8");
```

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
- **With configuration:** `req.ip()` parses `X-Forwarded-For` / `Forwarded` **only if** the immediate peer is trusted

When forwarding headers are consulted, Brace uses **rightmost-untrusted** semantics: it walks
the `X-Forwarded-For` list from right to left, skips any entry whose address falls inside a
trusted CIDR, and returns the first untrusted address. This is the correct algorithm because
real proxies **append** the connecting client's address — the leftmost entries were written by
the client and can be forged.

Examples:

| `X-Forwarded-For` | Trusted CIDRs | `req.ip()` |
|---|---|---|
| `1.2.3.4` | `10.0.0.0/8` | `1.2.3.4` |
| `spoofed, 9.9.9.9` | `10.0.0.0/8` | `9.9.9.9` (rightmost untrusted, not attacker-supplied leftmost) |
| `client, 10.0.0.2, 10.0.0.1` | `10.0.0.0/8` | `client` (both proxies trusted) |
| `10.0.0.1, 10.0.0.2` | `10.0.0.0/8` | `10.0.0.1` (all trusted → leftmost) |

**Dual-stack / representation mismatch.** `TrustedProxies` matches addresses by binary value
after resolution, but it **fails closed** when the address family differs — a CIDR expressed as
an IPv4 address does not match the IPv6-mapped form `::ffff:a.b.c.d`, and `127.0.0.1` does not
match `::1` on a dual-stack bind. If your proxy can connect over both IPv4 and IPv6, list both
representations explicitly:

```java
app.trustedProxies("127.0.0.1", "::1");
app.trustedProxies("10.0.0.0/8", "::ffff:10.0.0.0/104");  // if proxies may use IPv6-mapped addresses
```

### Supported Headers

- `X-Forwarded-For` (most common)
- `Forwarded` (RFC 7239) — elements correctly split on `,`, parameters on `;`
- `X-Forwarded-Proto` — the original scheme, for the session cookie's `Secure` attribute
- `X-Forwarded-Host` — the host the client addressed, for the `Secure` decision and the
  WebSocket `Origin` check (leftmost entry)

All of them are ignored unless the immediate peer is a trusted proxy.

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

Any response Brace attaches a session `Set-Cookie` to also gets `Cache-Control: private`
(unless the handler set an explicit `Cache-Control`, which always wins). A response
carrying a session cookie is per-user by definition — without this header it is
*heuristically cacheable*, and a misconfigured shared cache (e.g. an nginx recipe that
force-caches everything under `/static/`) would replay user A's cookie to every user.

The riskiest shape is a session-mutating middleware whose path pattern covers static
prefixes — for example a `lastSeen`-touch guard on `/*`. Every static file response then
carries a fresh session cookie. The `private` default makes that safe for spec-compliant
caches, but prefer scoping such middleware to application routes (`/app/*`) rather than
`/*`, and never configure a proxy to ignore response headers for paths a session
middleware can touch.

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
app.staticFiles("/assets", "public");                                       // your files
app.staticFiles("/uploads", "/var/app/uploads", StaticFiles.UNTRUSTED);     // users' files
```

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

`app.staticFiles(urlPrefix, directory)` confines serving to `directory`. The check resolves
symlinks (`toRealPath`) before comparing, so a symlink under the served tree cannot serve a
file outside it — a lexical `normalize()` + `startsWith` check does not catch that, because
the traversal happens in the filesystem rather than in the path string.

Dotfiles and dot-directories (`.env`, `.git/config`, `.htaccess`, `assets/.hidden/x`) are never
served (404), whatever the mapping; the only exception is a leading `.well-known/`, so ACME
challenges and `security.txt` keep working. The check runs on the decoded path, so `%2eenv`
is refused too.

Symlinks that stay inside the served directory keep working. To serve a directory that
lives elsewhere, register it as its own mapping rather than linking it into the web root. A
directory users write to is registered `UNTRUSTED` (see
[Serving uploaded files](#serving-uploaded-files)):

```java
app.staticFiles("/assets", "public");
app.staticFiles("/assets/uploads", "/var/app/uploads", StaticFiles.UNTRUSTED);
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
// Per-IP rate limiting
app.before("/api", RateLimiter.perIp(100, "1m"));

// Per-user rate limiting
app.before("/api", RateLimiter.perKey(
    req -> req.header("Authorization"),
    1000,
    "1h"
));

// Custom key function
app.before("/login", RateLimiter.perKey(
    req -> req.formParam("username"),
    5,
    "15m"
));
```

### Key normalization (DoS protection)

Rate-limit keys extracted from requests (usernames, tokens, IP addresses, custom headers) are
normalized before use:

- **Null or blank extractor result → `"(none)"`** bucket. Requests with a missing header or null
  key are counted together rather than silently bypassing the limiter.
- **Keys longer than 64 characters → SHA-256 hex digest** (always exactly 64 chars). Long
  user-controlled values (e.g. bearer tokens, forged headers) would otherwise create unbounded
  storage — millions of bytes of map entries locally, or arbitrarily large rows in
  `brace_counters`. Hashing caps the key size while preserving the collision-resistance needed
  for correct bucketing.

The cap is applied before any prefix or window-slot decoration, so it holds equally in
per-process and shared (Postgres) modes.

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

### Best Practices

1. **Configure trusted proxies** first (otherwise IP-based limiting is ineffective — see above)
2. **Use different limits** for different endpoints:
   - Login: 5 attempts per 15 minutes
   - API: 100-1000 requests per hour
   - Anonymous: 10 requests per minute
3. **Combine with authentication** for logged-in users

---

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

**The signature is bound to a server.** The CLI reads the server's URL from the committed
`.brace` file, so a pull request that points `ops.prod.url` somewhere else could collect a fresh
signature and relay it to the real server within the time window. The server accepts a v3
signature only for an origin listed with `app.opsOrigins("https://app.example.com")` (or the
`BRACE_OPS_ORIGINS` environment variable, comma-separated), compared exactly: scheme, host and
port. The request's `Host` and `X-Forwarded-Host` play no part, because the client relaying a
signature chooses those: an app reachable directly, or a proxy that passes a client's
`X-Forwarded-Host` through to an app that trusts the proxy, would let it claim any host. Prod
mode refuses to start with ops on and no origin configured. In dev and tests, with none set, the
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
recommendations below. The deprecated v1 protocol (signature over the timestamp alone, no
nonce — replayable as-is within the window) is accepted for one release with a logged
deprecation warning and will then be rejected; see the 0.1.6 → 0.1.7 migration guide.

**Key revocation.** Tokens are bound to the key that minted them. At startup the server takes
the fingerprints of the keys in `ops-authorized-keys` and rejects any bearer token, ops session
cookie or login token whose `kid` is not among them, or that has no `kid`, and caps every
token's scope at its key's current ceiling. Removing a key, or lowering it to `scope:read`, and
restarting or redeploying takes effect on its outstanding tokens immediately, although the token secret is derived from
the session secret and unexpired tokens (up to 24 h bearer, 8 h cookie) would otherwise survive
the restart.

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
  views are dropped and tallied. Every stored string is length-capped, and every value the page
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

### Name-based redaction (query params and request headers)

Query-string parameters and request headers whose names look sensitive
(`token`, `password`, `authorization`, `cookie`, `secret`, `api-key`, and
several others — see `Redactor.SENSITIVE`) have their values replaced with
`[REDACTED]`. Matching is on the normalized name (lowercased, hyphens and
underscores stripped), so it deliberately over-redacts: a field named
`token_count` is also redacted.

### Value-shaped redaction (path segments and exception message tokens)

High-entropy tokens are detected by shape and replaced with `[redacted]`,
regardless of field name. The heuristic (applied in `Redactor.redactPath` and
`Redactor.redactMessage`) treats a segment or whitespace/punctuation-delimited
token as a secret when **all** of these hold:

1. Length ≥ 16 characters.
2. Every character is in the base64url-or-hex alphabet (`[A-Za-z0-9_\-+/=]`).
3. Contains at least one ASCII digit **and** at least one ASCII letter (rules
   out purely numeric IDs and purely alphabetic slugs).

JWTs (two dots dividing three base64url parts) are also caught as a special
case.

**What is intentionally kept visible:**

| What | Why |
|---|---|
| UUIDs (`8-4-4-4-12` hex) | Usually record identifiers, not bearer secrets; needed for debugging |
| Purely numeric segments | Page IDs, order numbers, user IDs |
| Short slugs (< 16 chars) | Route labels, action names |
| Exception message text without high-entropy tokens | Needed to diagnose the error |

The same pass runs over stored **stack traces** (`Redactor.redactStackTrace`, at
`Stats.recordError` and `ErrorStore.record`): every message line, including `Caused by:` and
`Suppressed:` lines and multi-line messages, is redacted, and frame (`at …`) lines are kept
as is. A message redacted on its own used to reappear verbatim in the trace.

The heuristic is conservative by design. An over-eager redactor makes error
records useless. If your application routes embed raw bearer tokens or reset
secrets in path positions, consider moving them to opaque database-lookup keys
so the route retains meaning even after redaction.

---

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
