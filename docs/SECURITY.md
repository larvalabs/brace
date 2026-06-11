# Brace Security Model

This document describes Brace's security features and best practices for building secure applications.

## Table of Contents

- [Sessions](#sessions)
- [CSRF Protection](#csrf-protection)
- [Serialization](#serialization)
- [Trusted Proxies](#trusted-proxies)
- [Cookie Security](#cookie-security)
- [File Uploads](#file-uploads)
- [Rate Limiting](#rate-limiting)
- [Ops Endpoints](#ops-endpoints)
- [Secrets Management](#secrets-management)

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
- **Back-compat (0.1.7):** cookies minted by ≤0.1.6 have no `_exp` and are **accepted**
  this release (re-minted with `_exp` on the next write). A future release will reject
  expiry-less cookies — see the 0.1.6→0.1.7 migration guide.

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
| `Secure` | `false` | HTTPS only (set to `true` in production) |
| `SameSite` | `Lax` | CSRF protection (`Strict`, `Lax`, or `None`) |
| `Max-Age` | session | Cookie lifetime (use `maxAgeDays()` to set) |
| `Path` | `/` | Scope to specific path |
| `Domain` | none | Share across subdomains |

### Production Recommendations

```java
app.sessions(SessionOptions.secure("secret")
    .maxAgeDays(14)
    .sameSiteLax());
```

This sets: `HttpOnly=true`, `Secure=true`, `SameSite=Lax`, `Max-Age=1209600` (14 days)

---

## File Uploads

### Upload Size Limits

Configure maximum upload size to prevent DoS:

```java
app.maxUploadSize("10M");  // 10 megabytes (default)
app.maxUploadSize("50M");  // 50 megabytes
```

### Security Considerations

1. **Validate file types:** Check `file.contentType()` and extension
2. **Scan for malware:** Use external virus scanning for untrusted uploads
3. **Store safely:** Don't use user-provided filenames directly
4. **Limit concurrency:** High upload concurrency can exhaust memory

### Safe Storage Pattern

```java
// DON'T: Use user filename directly
String key = "uploads/" + file.name(); // ❌ Unsafe

// DO: Generate safe keys
String key = storage.safeKey("uploads", file.name()); // ✅ Safe
```

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
    req -> req.param("username"),
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

## Ops Endpoints

Ops endpoints (`/ops/*`) provide powerful observability and control. **Secure them carefully.**

### Authentication

Ops endpoints use public key authentication:

```java
app.ops("authorized-keys");
```

The `authorized-keys` file contains public keys of clients allowed to access ops endpoints.

Clients mint a bearer token via `POST /ops/auth` (protocol v2): an Ed25519 signature over
`publicKey + "\n" + timestamp + "\n" + nonce`, where the timestamp must be within ±30 seconds
of server time and the nonce is fresh random (16+ bytes, base64url) per attempt. Binding the
public key into the signed message means a captured signature is only valid for the key that
produced it; the nonce makes each signed request single-use. The exact wire format is in
`docs/agent-ops-guide.md`.

**Credential channels (general endpoints).** All ops endpoints except the browser exchange
handoff accept credentials through exactly two channels:

1. `Authorization: Bearer <token>` header — the standard channel for the CLI and the
   dashboard's htmx polling.
2. The `__brace_ops_session` httpOnly cookie — set by the browser exchange flow below.

`?token=` query-parameter credentials are **not accepted** on general ops endpoints. Tokens in
URLs leak into proxy access logs, browser history, and the `Referer` header of outbound links.
Pass credentials via the `Authorization` header or the session cookie.

**Browser exchange flow.** The `brace dashboard` CLI command calls `POST /ops/auth/login-token`
(requires Bearer auth), receives a short-lived (60s), scope-capped login token, and opens the
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

### Security Recommendations

1. **HTTPS only:** Never expose ops endpoints over HTTP
2. **Restrict at reverse proxy:** Use IP allowlisting at nginx/Caddy
3. **Don't expose publicly:** Ops endpoints should not be internet-accessible
4. **Rotate keys regularly:** Implement key rotation for ops access
5. **Monitor access:** Log all ops endpoint access

### Deployment Pattern

```nginx
# nginx config
location /ops/ {
    allow 10.0.0.0/8;      # Internal network only
    deny all;
    proxy_pass http://app;
}
```

---

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
