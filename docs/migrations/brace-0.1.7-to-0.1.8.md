# Migrating from Brace 0.1.7 → 0.1.8

For most applications this release requires **no code changes**. It is dominated by the
findings of the [2026-07 security review](../2026-07-24-security-review-todos.md), which
tightened several framework defaults.

Two cases **are breaking** and need action:

- **Session cookies now carry `Secure`** on every non-loopback request. An app deliberately
  served over plain HTTP on a real hostname will stop receiving its session cookie back
  until it opts out with `.secure(false)` — see
  "session cookies are `Secure` by default" below. Local development, `http://localhost`,
  and in-process test suites are unaffected.
- **Ops auth protocol v1 is rejected.** A `brace` CLI older than 0.1.7 can no longer
  authenticate against a 0.1.8 server — see "ops auth v1 removed" below.

Everything else is a hardening change with no action required.

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|
| Session cookies `Secure` by default | breaking | plain-HTTP prod deployments: add `.secure(false)` | [§](#breaking-session-cookies-are-secure-by-default) |
| Ops auth v1 removed | breaking | upgrade the `brace` CLI to 0.1.7+ | [§](#breaking-ops-auth-protocol-v1-removed) |
| Static files no longer follow symlinks out of the root | behavior change | re-point symlinked assets inside the served directory | [§](#security-fix-static-files-no-longer-follow-symlinks-out-of-the-served-directory) |
| Security headers now cover errors and static files | behavior change | none | [§](#security-fix-security-headers-now-apply-to-static-files-404s-500s-and-413s) |
| `/ops/*` responses are `no-store` | behavior change | none | [§](#security-fix-ops-responses-are-no-store) |
| WebSocket upgrades are `Origin`-checked | behavior change | cross-origin WS clients: declare `wsAllowedOrigins(...)` | [§](#security-fix-websocket-upgrades-are-origin-checked) |
| Request bodies read after before-middleware | behavior change | before-middleware reading `req.body()` still works | [§](#security-fix-request-bodies-are-read-after-before-middleware) |
| `Result.download` escapes the filename | behavior change | none | [§](#security-fix-resultdownload-escapes-the-filename) |
| `Http.multipart` rejects control chars in part names | behavior change | none unless passing raw user filenames | [§](#security-fix-httpmultipart-rejects-control-characters-in-part-names) |
| `Result.cookie` rejects invalid names/values | behavior change | none unless setting cookies from raw user input | [§](#security-fix-resultcookie-validates-names-and-values) |
| Ops session cookie scoped to `/ops` | behavior change | none | [§](#security-fix-ops-session-cookie-is-scoped-to-ops) |
| `Storage` rejects traversal in keys | behavior change | none unless building keys from user input | [§](#security-fix-storage-rejects-traversal-segments-in-object-keys) |

---

## Breaking: session cookies are `Secure` by default

**What changed.** `SessionOptions` no longer defaults `secure` to `false`. With no explicit
setting the `Secure` attribute is now resolved **per request**: on for every request, off
only when the request's `Host` is a loopback address (`localhost`, `127.0.0.0/8`, `::1`).
An `X-Forwarded-Proto: https` from a **trusted** proxy forces it on regardless of `Host`.

**Why.** Brace serves cleartext HTTP/1.1 and expects TLS to be terminated by a reverse
proxy, so it cannot read the scheme off its own connector. The old fixed `false` meant
every app built the documented way — `.sessions(secret)` — shipped a session cookie with no
`Secure` attribute, and the cookie *is* the session: any cleartext request to the domain
(a typed `http://` URL, a stale bookmark, a first visit before HSTS is pinned) handed it to
a network attacker. A fixed `true` would have been no better — it silently breaks every app
and test suite on `http://localhost` — hence the per-request resolution.

**Who needs to act.** Only an app served over **plain HTTP on a real hostname** in
production. It will now set a `Secure` cookie the browser refuses to send back, so sessions
appear to reset on every request.

**Before (0.1.7) — cookie had no `Secure` attribute:**

```java
var app = Brace.app().sessions(System.getenv("SESSION_SECRET"));
// Set-Cookie: brace_session=...; Path=/; HttpOnly; SameSite=Lax
```

**After (0.1.8) — same code, `Secure` added off-loopback:**

```java
var app = Brace.app().sessions(System.getenv("SESSION_SECRET"));
// Set-Cookie: brace_session=...; Path=/; HttpOnly; Secure; SameSite=Lax
```

**Opting out** (plain-HTTP deployment on a private network, a prod smoke test):

```java
var app = Brace.app()
    .sessions(SessionOptions.of(System.getenv("SESSION_SECRET")).secure(false));
```

Startup logs a warning whenever that opt-out is in effect, so the state is visible rather
than silent. `SessionOptions.secure(secret)` and `.sameSiteNone()` still force `Secure` on,
and an explicit `.secure(true)` / `.secure(false)` always wins over the per-request
resolution.

**Tests are unaffected.** `Brace.test()` and any suite hitting `http://localhost` keep
working with no change: a loopback `Host` resolves to no `Secure` attribute.

**If you set `Secure` explicitly already**, nothing changes — your value still wins.

---

## Security fix: static files no longer follow symlinks out of the served directory

`app.staticFiles(prefix, dir)` checked containment with `Path.normalize()` +
`startsWith(baseDir)`. That is a lexical check — it collapses `..` in the path *string* and
does not resolve symlinks — while the file read follows them. A symlink anywhere under a
served directory therefore served whatever it pointed at, **including files outside the web
root**.

Containment is now re-checked against the link-resolved paths (`toRealPath()` on both the
candidate and the base directory).

**Action required only if** you relied on a symlink pointing *out* of a served directory —
e.g. `public/uploads` → `/var/app/uploads`. That now returns 404. Either move the target
inside the served tree, or register the target directory as its own mapping:

```java
// Before: public/uploads was a symlink to /var/app/uploads
app.staticFiles("/assets", "public");

// After: serve the real directory directly
app.staticFiles("/assets", "public");
app.staticFiles("/assets/uploads", "/var/app/uploads");
```

Symlinks that stay **inside** the served directory keep working unchanged.

---

## Security fix: request bodies are read after before-middleware

The request body (including multipart parsing) used to be buffered *before* the
`before(...)` middleware chain ran, so a rate limiter or auth guard could not reject a
request until up to `maxUploadSize` had already been read into the heap — with handlers on
virtual threads, nothing bounded how many of those were in flight at once.

The body is now read lazily, after the guards have run. **No action required.**

Two consequences worth knowing:

- A request rejected by before-middleware now returns the **guard's** status rather than
  `413`, even when the body was oversized. Previously an oversized body on a guarded route
  returned `413` before the guard was consulted.
- Middleware that genuinely needs the body — a webhook signature check, say — still works
  unchanged; reading `req.body()` inside a guard triggers the read at that point.

```java
// Still works: the body read happens on access, inside the guard.
app.before("/webhooks/*", req ->
    verifySignature(req.header("X-Signature"), req.body()) ? null : Result.unauthorized());
```

The `maxUploadSize` cap itself is unchanged: a request that reaches a handler with an
oversized body still gets `413`.

---

## Security fix: security headers now apply to static files, 404s, 500s and 413s

After-middleware — including `app.after(SecurityHeaders.defaults())` — used to run only on
the normal handler path. Every other response left the server undecorated: static files
(which got `nosniff` and nothing else), unmatched-route 404s, handler-thrown 404s, CSRF
403s, 500s, and `413 Payload Too Large`. An app that followed the documented hardening step
had no `X-Frame-Options`, `Referrer-Policy`, or CSP on exactly the responses that most need
them.

After-middleware now runs for every response leaving the handler. **No action required.**

Path-scoped middleware is unaffected — `app.after("/admin/*", …)` still only fires for
matching paths, including for static files and error responses under that prefix.

One nuance: on the 404-thrown and 500 paths, an after-middleware that itself throws is
logged and skipped rather than replacing the error response. On all other paths a throwing
after-middleware surfaces as a 500, as before.

---

## Security fix: WebSocket upgrades are `Origin`-checked

`app.ws(path, …)` accepted an upgrade from any origin and then decrypted the
`brace_session` cookie straight into the handler's `WsContext`. A WebSocket handshake is
not subject to the same-origin policy, so an attacker's page could open a socket to your
app, have the browser attach the victim's session cookie, and hold an authenticated socket
for its lifetime — cross-site WebSocket hijacking. The only thing standing in the way was
the default `SameSite=Lax` cookie, which an app disables the moment it calls
`sameSiteNone()`.

Upgrades whose `Origin` names a different host are now rejected with 403.

**No action required** for same-origin browser clients or for non-browser clients (a
missing `Origin` is allowed — only browsers send one, and only browsers need the check).
The host is compared without scheme or port, so TLS terminated at a proxy is fine.

**Action required** only for a deliberately cross-origin browser client:

```java
var app = Brace.app()
    .wsAllowedOrigins("https://studio.example.com")   // full origin, or a bare host
    .ws("/live", ctx -> new LiveHandler(ctx));
```

Pass `"*"` to disable the check entirely — sound only if the socket carries no ambient
authority (i.e. it does not rely on the session cookie for authorization).

---

## Security fix: `/ops/*` responses are `no-store`

Only `/ops/auth/exchange` sent `Cache-Control: no-store`. Every other ops endpoint relied
on the caller's credential channel to suppress caching — which RFC 9111 guarantees for the
CLI's `Authorization` header, but *not* for the `__brace_ops_session` cookie a browser
uses. A response with no `Cache-Control` is heuristically cacheable, and `/ops/dashboard`
embeds a live bearer token in its HTML, so an intermediary could store one operator's
dashboard and serve it — token included — to the next requester.

Every `/ops/*` response now carries `Cache-Control: no-store`. **No action required.**

---

## Security fix: `Result.download` escapes the filename

`Result.download(bytes, contentType, filename)` interpolated the filename raw into
`Content-Disposition: attachment; filename="…"`. A name containing a double quote closed the
quoted-string early and everything after it was parsed by the client as further parameters —
`Result.download(b, "text/plain", "x\"; name=\"y")` put
`Content-Disposition: attachment; filename="x"; name="y"` on the wire. Serving a user-uploaded
file under its original name is the method's primary use case, which is exactly where the
value is attacker-supplied.

The header is now built safely and emits the RFC 6266 pair — an ASCII-safe `filename="…"`
plus `filename*=UTF-8''…` carrying the exact name. Directory components are dropped (a
download name is a leaf), and characters that can't appear literally in a quoted-string are
replaced with `_` in the ASCII form.

**No action required.** One visible improvement: non-ASCII filenames now reach the browser
intact instead of being mangled.

```java
Result.download(pdf, "application/pdf", "отчёт.pdf");
// Content-Disposition: attachment; filename="______.pdf"; filename*=UTF-8''%D0%BE%D1%82%D1%87%D1%91%D1%82.pdf
```

---

## Security fix: `Http.multipart` rejects control characters in part names

`Http.post(url).multipart().field(name, bytes, filename)` wrote part names and filenames into
the multipart headers verbatim, so CR/LF in either value terminated the part headers and let
the caller forge **additional parts** in the outbound request — parameter smuggling into
whatever third-party API the app was calling (adding a `visibility=public` part to an upload,
say).

Quotes and backslashes are now escaped per RFC 7578, and a control character throws
`IllegalArgumentException` rather than being silently stripped: for an outbound API call, a
request that quietly differs from what the caller asked for is worse than one that doesn't
happen.

**Action required only if** you pass raw user-supplied filenames straight through. Sanitize
or catch:

```java
// A filename from an upload — now rejected loudly instead of smuggling parts.
var safeName = upload.filename().replaceAll("[\\p{Cntrl}]", "");
Http.post(url).multipart().field("file", upload.bytes(), safeName).fetch();
```
