# Brace Security Model

This document describes Brace's security features and best practices for building secure applications.

## Table of Contents

- [Sessions](#sessions)
- [CSRF Protection](#csrf-protection)
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

### Best Practices

1. **Configure trusted proxies** first (otherwise IP-based limiting is ineffective)
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
