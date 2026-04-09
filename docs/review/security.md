## Security review of Brace

Here’s a focused security review based on the framework design and the core runtime/security-sensitive code paths I examined.

## Executive summary

**Brace has a solid security foundation for a compact framework**, especially in that it already includes:

- signed session cookies
- CSRF protection
- rate limiting
- password hashing
- authenticated ops endpoints
- upload size limits
- explicit routing instead of reflection-heavy magic

That said, there are several areas where the current design is **not yet safe-by-default enough** for broad “production-ready” claims.

### Top priorities
If I were triaging this, I’d fix these first:

1. **Trusted proxy / client IP handling**
2. **Session cookie security defaults**
3. **Clarify that sessions are signed but not encrypted**
4. **Tighten CSRF policy for JSON/cookie-auth cases**
5. **Replace fragile auth-path JSON parsing with Jackson**
6. **Safer upload/storage defaults**
7. **Security headers middleware / default profile**
8. **Secret rotation story**

---

# Threat model observations

Brace is unusual in that it tries to be both:

- a full-stack app framework
- an ops/automation substrate for agents

That means your threat model is broader than a typical small web framework. You need to think about:

- **browser attackers**: CSRF, XSS, session theft, malicious uploads
- **API attackers**: spoofed IPs, brute force, malformed payloads
- **infrastructure attackers**: exposed ops endpoints, proxy/header confusion
- **automation abuse**: agent-authenticated control surfaces, overpowered diagnostics

Because the framework includes observability and control endpoints, a security flaw there has a particularly high blast radius.

---

# Findings and recommendations

## 1. Session cookies are integrity-protected, not confidential
From what I reviewed, session cookies are serialized to JSON, base64url-encoded, and HMAC-signed.

### What this means
This protects against **tampering**, but not **reading**.

Anyone with the cookie can inspect its contents client-side.

### Risk
Developers may assume session data is private and store:

- email addresses
- roles/permissions
- internal flags
- workflow state
- flash messages with sensitive content

That would leak to the client.

### Recommendation
At minimum, document this explicitly:

> Brace session cookies are tamper-proof but not encrypted. Do not store sensitive data in them.

### Better long-term option
Offer two modes:

- **signed session cookies**
- **encrypted + authenticated session cookies**

If you keep signed-only as the default, the docs should be very blunt about the tradeoff.

### Severity
**High** as a design/documentation risk; **medium** as an implementation issue.

---

## 2. Session cookie flags need stronger defaults
I saw session cookies being set with `HttpOnly` and `SameSite=Lax`, but I did not see a stronger policy around transport and lifetime.

### Risks
- Missing or inconsistent `Secure` handling can expose session cookies over HTTP in bad deployments.
- No expiration/max-age policy means lifetime behavior is unclear.
- No config for domain/path/samesite policy makes it harder to deploy safely in varied environments.

### Recommendation
Add a configurable session policy with secure defaults:

```java
app.sessions(secret, SessionOptions.defaults()
    .secure(true)
    .httpOnly(true)
    .sameSiteLax()
    .maxAgeDays(14));
```


### Suggested defaults
- `HttpOnly = true`
- `SameSite = Lax`
- `Secure = true` when HTTPS is enabled or proxy trust says original scheme was HTTPS
- explicit max age or session duration setting

### Also add
- secret rotation support
- session invalidation/versioning support if you later add remember-me/auth flows

### Severity
**High**

---

## 3. Client IP handling is vulnerable to proxy-header spoofing
The current request IP behavior trusts `X-Forwarded-For` too eagerly.

### Why this is dangerous
If the app is directly reachable, or proxy trust is misconfigured, an attacker can spoof:

- IP-based rate limiting
- audit logs
- abuse detection
- geofencing
- login throttling

### Recommendation
Make proxy trust explicit.

## Safe default
- Ignore forwarding headers by default.
- Use only the socket remote address unless trusted proxies are configured.

## Better API
```java
app.trustedProxies("10.0.0.0/8", "192.168.0.0/16");
```


Then only parse forwarded headers if the immediate peer is trusted.

You may also want support for:
- `Forwarded`
- `X-Forwarded-For`
- `X-Forwarded-Proto`

with clearly documented precedence.

### Severity
**High**

---

## 4. CSRF policy is directionally good, but the JSON exemption is too broad
You have CSRF protection on mutating requests and skip it for JSON requests.

### Problem
Skipping CSRF based on `Content-Type: application/json` is only safe if those endpoints are **not authenticated by browser cookies**.

If a browser automatically sends session cookies to a JSON endpoint, that endpoint is still potentially CSRF-relevant.

### Recommendation
Don’t frame the rule as “skip for JSON APIs.”

Frame it as:

- **Require CSRF for cookie-authenticated browser requests**
- **Allow CSRF exemption for bearer-token / non-browser API auth**

### Better approaches
1. Per-route explicit exemption:
```java
app.postJson("/api/posts", api::create).csrf(false);
```


2. Or exemption based on auth mechanism:
    - cookie auth => CSRF required
    - bearer auth => CSRF not required

3. Or at minimum:
    - default to CSRF on all mutating requests when sessions are enabled
    - let users opt out explicitly

### Severity
**High** in real apps using browser-based JSON endpoints

---

## 5. CSRF token comparison should be timing-safe
The CSRF validation appears to use plain string equality.

### Risk
This is not the worst issue here, but for security tokens it’s better to use constant-time comparison.

### Recommendation
Use the same timing-safe comparison style already used for session signature validation.

### Severity
**Low to medium**

---

## 6. Ops auth request parsing is too fragile for a security-sensitive path
The ops auth endpoint appears to extract JSON fields with manual string searching.

### Risks
Custom parsers on auth endpoints are brittle and can fail in unexpected ways with:
- escapes
- whitespace
- reordered fields
- malformed JSON
- edge-case payloads

Even when not directly exploitable, they create ambiguity and maintenance risk.

### Recommendation
Use Jackson for ops auth payload parsing.

You already have Jackson in the project. This is the exact place to use it.

### Preferred model
```java
record OpsAuthRequest(String publicKey, String timestamp, String signature, Integer ttlSeconds) {}
```


Then parse normally and validate fields.

### Severity
**Medium to high**

---

## 7. Ops endpoints are powerful and need tighter hardening guidance
Your ops design is one of the strongest product features, but also one of the highest-risk surfaces.

These endpoints expose things like:
- status
- routes
- errors
- dashboard data
- cache controls

Potentially also rich diagnostics, request context, and stack traces.

### Risks
If access control fails, the blast radius is large:
- introspection of internal app structure
- leakage of stack traces/request details
- operational control
- possible data exposure through diagnostics

### Recommendations

## A. Add explicit ops hardening guidance
Document that ops endpoints should be:
- behind HTTPS only
- optionally IP-restricted at the reverse proxy
- disabled unless explicitly configured
- never exposed casually on public internet

## B. Consider path randomization or namespace config
Not as primary security, but defense-in-depth:
```java
app.ops("/internal/ops", "authorized-keys");
```


## C. Add audience/scope concepts later
Future improvement:
- read-only tokens
- dashboard-only tokens
- control tokens

Today a token seems broadly valid; finer scopes would help.

## D. Scrub sensitive values from diagnostics
Be very careful with:
- Authorization headers
- cookies
- db credentials in exceptions
- secrets in stack traces or config errors

### Severity
**High** if misconfigured; otherwise **medium**

---

## 8. Upload handling is memory-heavy and may enable heap abuse
Uploads appear to be fully read into memory as `byte[]`.

### Risks
Even with max upload size limits:
- many concurrent uploads can create heap pressure
- memory amplification is easier
- GC churn increases
- DoS becomes simpler

### Recommendation
Move toward:
- in-memory only for small files
- temp-file spooling for larger files
- streaming upload support to storage

### Also add
- explicit per-file and total multipart part count limits
- filename sanitization guidance
- MIME sniffing guidance for security-sensitive uploads

### Severity
**Medium to high**

---

## 9. Storage examples encourage unsafe key generation patterns
The easy example pattern is to use client file names directly in storage keys.

### Risks
Not necessarily path traversal in S3 itself, but still bad practice:
- collisions
- ugly/untrusted names
- confusing extensions
- weird Unicode/control chars
- content-type mismatch risks
- user-controlled URL paths

### Recommendation
Make the safe path the default API.

Instead of encouraging:
```java
"photos/" + file.name()
```


encourage:
```java
storage.putGenerated("photos", file)
```


or:
```java
String key = storage.safeKey("photos", file.name());
```


### Also recommend
- extension allowlists
- server-generated IDs
- content validation/sniffing
- public/private bucket distinction

### Severity
**Medium**

---

## 10. Static file serving is functional but thin on security/caching controls
The static file implementation blocks obvious `..` traversal and normalizes paths, which is good.

### Still missing / worth tightening
- stronger cache-control support
- `X-Content-Type-Options: nosniff`
- validator support (`ETag`, `Last-Modified`)
- optional denylist / allowlist by extension
- better handling for dotfiles if served from public dir

### Recommendation
At minimum, add:
- default `Cache-Control` support
- optional `nosniff`
- documented rule that only explicitly-public assets should live in the served directory

### Severity
**Medium**

---

## 11. Security headers should be first-class
I didn’t see a built-in security headers story.

### Recommendation
Provide a standard middleware:

```java
app.after(SecurityHeaders.defaults());
```


### Default headers to consider
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `X-Frame-Options: DENY` or CSP `frame-ancestors 'none'`
- `Permissions-Policy`
- `Content-Security-Policy` helper for HTML apps
- `Strict-Transport-Security` when HTTPS is configured

This is one of the easiest ways to make apps materially safer by default.

### Severity
**Medium**, but high leverage

---

## 12. Session secret quality should be enforced
If the framework allows weak or placeholder secrets, users will absolutely use them.

### Recommendation
On startup:
- reject secrets below a minimum entropy/length threshold in non-dev mode
- warn loudly on obviously weak values like placeholders

Example:
- minimum 32 bytes random, base64 or hex acceptable

### Severity
**Medium**

---

## 13. Secret rotation support is missing
Currently, session/ops secret validation appears to assume one active secret.

### Risk
Rotating secrets can force global logout or operational disruption unless you support:
- sign with current secret
- verify with current + previous

### Recommendation
Support keyrings:
- current signing key
- N previous verification keys

This is especially important for:
- session cookies
- ops tokens

### Severity
**Medium**

---

## 14. Error/ops data may leak sensitive request details
The ops/error model intentionally captures rich context. That’s valuable, but dangerous if not redacted.

### Risky fields
- cookies
- Authorization headers
- CSRF tokens
- session payloads
- API keys in query strings
- secrets embedded in exception messages

### Recommendation
Add a redaction layer before storing/reporting diagnostics.

At minimum, redact keys/headers matching patterns like:
- `authorization`
- `cookie`
- `set-cookie`
- `token`
- `secret`
- `password`
- `key`

And be careful with full request dumps.

### Severity
**High leverage**

---

## 15. Rate limiting is useful but currently easy to misapply
The limiter itself is fine conceptually, but because IP extraction is spoofable unless proxy trust is fixed, security benefits are weaker than they look.

### Recommendation
After fixing trusted proxies, add first-class recipes for:
- login attempts by IP + username
- password reset request limits
- registration limits
- anonymous API limits
- ops auth rate limits

### Severity
**Dependent on proxy fix**

---

## 16. Password hashing is okay, but the framework should define the standard more strongly
Using bcrypt is acceptable. Cost 12 is reasonable.

### Recommendation
Document the official auth guidance:
- bcrypt cost expectations
- password length handling
- rate limiting around login
- constant-time comparison expectations
- password reset token design recommendations

Longer-term, Argon2id support would be nice, but bcrypt is not a problem by itself.

### Severity
**Low**

---

## 17. Native/raw SQL APIs need documentation on safe usage
The DB wrapper supports parameterized queries, which is good.

### Risk
Users may still build SQL fragments dynamically and create injection issues.

### Recommendation
Be very explicit in docs:
- values go through placeholders only
- never concatenate user input into SQL/HQL fragments
- field names/order clauses should come from allowlists, not raw input

This is mostly a docs/safe-usage issue rather than a framework flaw.

### Severity
**Low to medium**

---

# Recommended security roadmap

## Phase 1: immediate fixes
These are the highest-value changes.

### 1. Trusted proxy configuration
- ignore forwarding headers by default
- only trust them for configured proxies

### 2. Stronger cookie policy
- `Secure`
- configurable lifetime
- clear same-site behavior

### 3. Clarify session confidentiality limits
- docs + startup note if useful

### 4. Use Jackson for ops auth payloads
- remove manual JSON parsing

### 5. Rework CSRF exemption logic
- not “JSON = exempt”
- make cookie-auth the deciding factor, or require explicit opt-out

### 6. Add default security headers middleware
- one-liner secure baseline

---

## Phase 2: hardening improvements

### 7. Redaction for ops/error data
- headers
- query params
- sensitive values

### 8. Upload spooling/streaming
- reduce heap pressure and DoS exposure

### 9. Session/ops key rotation
- current + previous verification keys

### 10. Safer storage APIs
- generated object keys
- public/private patterns

---

## Phase 3: maturity features

### 11. Scoped ops tokens
- dashboard read
- API read
- control actions

### 12. CSP helper / HTML security policy support
- especially valuable for full-stack apps

### 13. Security configuration profile
Something like:

```java
app.security(Security.defaults());
```


with sane, explicit defaults.

---

# Recommended documentation changes

You don’t just need fixes; you need sharper security docs.

## Add a “Security model” page covering

### Sessions
- signed vs encrypted
- what can/cannot be stored
- recommended secret generation
- cookie flags

### CSRF
- when it applies
- JSON/cookie caveat
- API token exemptions

### Proxies
- trusted proxy config required for real IP handling

### Uploads
- size limits
- safe filenames
- MIME validation
- public/private storage guidance

### Ops
- exposure guidance
- HTTPS requirement
- reverse proxy restrictions
- token/key rotation

### Error reporting
- redaction and sensitive-data caveats

This kind of clarity would significantly increase confidence in the framework.

---

# Safe-by-default API recommendations

These are design moves that would directly improve security posture.

## 1. Security headers middleware
```java
app.after(SecurityHeaders.defaults());
```


## 2. Explicit proxy trust
```java
app.trustedProxies("10.0.0.0/8");
```


## 3. Better session config
```java
app.sessions(secret, SessionOptions.defaults()
    .secure(true)
    .sameSiteLax()
    .maxAgeDays(14));
```


## 4. Safe storage path
```java
var saved = storage.putGenerated("photos", file);
```


## 5. Explicit CSRF exemptions
```java
app.post("/api/posts", api::create).csrf(false);
```


This is better than hidden heuristics.

---

# Bottom line

## What’s good
Brace already has a lot of the right security ingredients:
- explicit routing
- signed sessions
- CSRF support
- rate limiting
- strong ops auth concept
- password hashing
- path normalization for static files

## What needs work
The main issues are not “everything is broken.” They’re mostly:

- **unsafe assumptions in defaults**
- **a few brittle implementation choices**
- **insufficiently explicit deployment/security boundaries**

## My overall assessment
I’d describe Brace security today as:

> **thoughtful foundation, but not yet hardened enough to market as fully production-safe without caveats**

The highest-risk issue is **proxy/IP trust**, followed by **cookie/session policy**, **CSRF exemption semantics**, and **ops hardening**.

If you want, I can next turn this into either:

1. **a prioritized fix checklist with concrete implementation steps**, or
2. **a proposed `SecurityHeaders` / `SessionOptions` / `trustedProxies` API design**