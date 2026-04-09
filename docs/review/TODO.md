# Brace Review Action Items

This document tracks the implementation plan for API improvements and security hardening based on the reviews in `api.md` and `security.md`.

## Phase 1: Security Hardening (Immediate)

These are correctness/safety issues that should block "production-ready" claims.

### 1. Trusted proxy configuration ✅
- [x] Add explicit proxy trust configuration (e.g., `app.trustedProxies("10.0.0.0/8")`)
- [x] Ignore forwarding headers by default
- [x] Only parse `X-Forwarded-For`, `X-Forwarded-Proto`, `Forwarded` when immediate peer is trusted
- [x] Update `Request.ip()` to use socket remote address by default

**Priority:** HIGH — fixes spoofable IP-based rate limiting and audit logs
**Status:** COMPLETED — TrustedProxies class added with CIDR support, Request.ip() updated to respect proxy trust, comprehensive tests added

### 2. Session cookie policy ✅ → **UPGRADED TO ENCRYPTED SESSIONS**
- [x] Create `SessionOptions` class with configurable settings
- [x] Add secure defaults: `Secure=true` (when HTTPS), `HttpOnly=true`, `SameSite=Lax`
- [x] Add configurable `maxAge` or session duration
- [x] Support `secure(boolean)`, `httpOnly(boolean)`, `sameSite(String)` configuration
- [x] Update `Brace.sessions()` to accept `SessionOptions`
- [x] **BONUS:** Implement AES-256-GCM encrypted sessions (eliminates all confidentiality concerns)

**Priority:** HIGH — prevents session theft via insecure transport
**Status:** COMPLETED + UPGRADED — SessionOptions class + **AES-256-GCM encryption** with PBKDF2 key derivation, 29 tests pass (17 SessionOptions + 12 encryption)

### 3. Document session confidentiality ✅ → **OBSOLETE (sessions now encrypted)**
- [x] ~~Add clear documentation that sessions are signed but not encrypted~~
- [x] ~~Warn against storing sensitive data in sessions (emails, roles, etc.)~~
- [x] ~~Add startup warning when sessions are configured~~
- [x] Update security model documentation page

**Priority:** HIGH — prevents misuse and data leakage
**Status:** COMPLETED + OBSOLETE — Documentation updated to reflect encrypted sessions, Session class JavaDoc updated, docs/SECURITY.md updated

### 4. Ops auth uses Jackson
- [ ] Replace manual JSON parsing in ops auth with Jackson
- [ ] Create `OpsAuthRequest` record with typed fields
- [ ] Use `bodyAs(OpsAuthRequest.class)` for parsing

**Priority:** MEDIUM-HIGH — hardens security-sensitive code path

### 5. CSRF exemption logic
- [ ] Change CSRF exemption from automatic JSON content-type to explicit opt-out
- [ ] Add `.csrf(boolean)` method to route registration (e.g., `app.post(...).csrf(false)`)
- [ ] Default to CSRF required for all mutating requests when sessions are enabled
- [ ] Document that cookie-authenticated endpoints need CSRF, bearer-token endpoints can opt out

**Priority:** HIGH — fixes CSRF vulnerability in cookie-authenticated JSON endpoints

### 6. Security headers middleware
- [ ] Create `SecurityHeaders` class with `.defaults()` static method
- [ ] Include: `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`
- [ ] Include: `X-Frame-Options: DENY` or CSP `frame-ancestors 'none'`
- [ ] Include: `Strict-Transport-Security` when HTTPS detected
- [ ] Support optional `Permissions-Policy` and `Content-Security-Policy` configuration
- [ ] Add one-liner: `app.after(SecurityHeaders.defaults())`

**Priority:** MEDIUM — high leverage, easy wins

### 7. Secret validation
- [ ] Enforce minimum secret length (32 bytes) in non-dev mode
- [ ] Reject obviously weak secrets (e.g., "secret", "changeme", "test")
- [ ] Add startup validation for session secret and ops secret
- [ ] Provide clear error messages with guidance on generating secure secrets

**Priority:** MEDIUM — prevents weak credential usage

---

## Phase 2: API Improvements (Additive, Non-Breaking)

Make the API more agent-friendly without breaking existing code.

### 1. Typed route methods
- [ ] Add `getDb(String path, DbHandler)`, `postDb(...)`, `putDb(...)`, `deleteDb(...)`
- [ ] Add `getSession(...)`, `postSession(...)`
- [ ] Add `getFull(...)`, `postFull(...)`, `putFull(...)`, `deleteFull(...)`
- [ ] Keep existing cast-based API for backward compatibility
- [ ] Update examples and docs to prefer typed methods

**Benefit:** Eliminates cast syntax, more inferable for agents

### 2. Source-specific request accessors
- [ ] Add `pathParam(String)`, `intPathParam(String)`, `longPathParam(String)`
- [ ] Add `queryParam(String)`, `queryParam(String, String)`, `queryInt(String)`, `queryInt(String, int)`
- [ ] Add `queryLong(String)`, `hasQueryParam(String)`
- [ ] Add `formParam(String)`, `formInt(String)`, `hasFormParam(String)`
- [ ] Keep `param(String)` as convenience fallback, but demote in docs
- [ ] Add typed helper methods: `json(Class<T>)`, `requireJson(Class<T>)`
- [ ] Add predicate methods: `isJson()`, `isFormPost()`, `isMultipart()`

**Benefit:** Eliminates ambiguity about data source, more explicit

### 3. Unified Result helpers
- [ ] Add static factory methods to `Result` class
- [ ] `Result.json(Object)`, `Result.view(String, Object...)`, `Result.redirect(String)`
- [ ] `Result.text(String)`, `Result.html(String)`, `Result.bytes(byte[], String)`
- [ ] `Result.notFound()`, `Result.unauthorized()`, `Result.forbidden()`, `Result.badRequest(String)`
- [ ] `Result.created(String)`, `Result.download(String, byte[], String)`
- [ ] Keep `Json.of()`, `View.of()`, `Redirect.to()` as aliases or deprecate gradually

**Benefit:** One namespace, consistent pattern, easier to generate

### 4. Constrained DB helpers
- [ ] Add `findBy(Class<T>, String field, Object value)` — find one by field
- [ ] Add `findAllBy(Class<T>, String field, Object value)` — find all by field
- [ ] Add `countBy(Class<T>, String field, Object value)` — count by field
- [ ] Add `existsBy(Class<T>, String field, Object value)` — check existence
- [ ] Add `deleteBy(Class<T>, String field, Object value)` — delete by field
- [ ] Keep existing `query()` and `queryOne()` for complex cases

**Benefit:** Covers 80% of queries without string query generation

### 5. Form.hasErrors()
- [ ] Add `hasErrors()` method to `Form<T>` interface
- [ ] Keep `valid()` for backward compatibility
- [ ] Update examples to prefer `hasErrors()` over `!valid()`

**Benefit:** Clearer boolean logic, less double-negative

### 6. Safer storage helpers
- [ ] Add `safeKey(String folder, String originalName)` — sanitizes filename
- [ ] Add `putGenerated(String folder, UploadedFile)` — auto-generates safe key
- [ ] Add `extension(String filename)` helper
- [ ] Return `StoredFile` record with `key()` and `url()` methods
- [ ] Document safe patterns in examples

**Benefit:** Makes safe pattern the easy pattern

---

## Phase 3: Maturity Features (When Needed)

Nice-to-haves that can wait until there's clear demand.

### 1. Session encryption option
- [ ] Implement encrypted + authenticated session cookie mode
- [ ] Support AES-GCM or similar AEAD cipher
- [ ] Allow choice between signed-only and encrypted modes
- [ ] Document tradeoffs (size, performance, confidentiality)

**Benefit:** Enables storing sensitive data in sessions safely

### 2. Key rotation support
- [ ] Support keyring concept: current signing key + N previous verification keys
- [ ] Apply to both session cookies and ops tokens
- [ ] Add rotation guidance to docs
- [ ] Prevent forced logout during key rotation

**Benefit:** Allows secret rotation without disruption

### 3. Scoped ops tokens
- [ ] Add token scopes: read-only, dashboard, control
- [ ] Support scope validation in ops endpoints
- [ ] Document least-privilege token patterns

**Benefit:** Reduces blast radius of token compromise

### 4. Upload streaming
- [ ] Move from in-memory `byte[]` to streaming for large files
- [ ] Use temp file spooling for uploads above threshold
- [ ] Add streaming API to storage abstraction
- [ ] Keep in-memory option for small files

**Benefit:** Reduces heap pressure and DoS risk

### 5. CSP helpers
- [ ] Create `ContentSecurityPolicy` builder API
- [ ] Support nonce generation for inline scripts
- [ ] Add integration with View rendering
- [ ] Provide safe defaults for HTML apps

**Benefit:** Materially improves XSS protection

### 6. Redaction layer
- [ ] Scrub sensitive values from ops/error diagnostics
- [ ] Redact headers: `authorization`, `cookie`, `set-cookie`
- [ ] Redact query params matching patterns: `token`, `secret`, `password`, `key`
- [ ] Add configurable redaction rules

**Benefit:** Prevents credential leakage in logs/diagnostics

---

## Documentation Updates

These documentation changes should accompany the implementation:

### Security model page
- [ ] Document session signing vs encryption tradeoffs
- [ ] Document CSRF policy and exemption rules
- [ ] Document trusted proxy configuration requirements
- [ ] Document upload size/safety best practices
- [ ] Document ops endpoint hardening guidance
- [ ] Document secret generation and rotation

### API style guide
- [ ] Prefer typed route registration over casts
- [ ] Prefer source-specific request accessors over `param()`
- [ ] Prefer unified `Result.*` over fragmented helpers
- [ ] Prefer constrained DB helpers before raw queries
- [ ] Prefer safe storage key generation
- [ ] Document canonical controller pattern

### AGENTS.md updates
- [ ] Update handler examples to use typed route methods
- [ ] Update request examples to use source-specific accessors
- [ ] Update result examples to use unified namespace
- [ ] Update database examples to show constrained helpers

---

## Implementation Strategy

1. **Start with Phase 1** — security hardening has highest priority and minimal API churn
2. **Phase 2 can be additive** — new methods alongside old, update docs/examples to prefer new style
3. **Phase 3 is demand-driven** — implement when users need it, not speculatively

Each phase should include:
- Implementation
- Tests
- Documentation updates
- Example code updates
