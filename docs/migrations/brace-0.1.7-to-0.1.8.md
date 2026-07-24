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
