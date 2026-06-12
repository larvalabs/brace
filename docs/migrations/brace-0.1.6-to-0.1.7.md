# Migrating from Brace 0.1.6 → 0.1.7

For most applications this release requires **no code changes**. It fixes a
packaging gap for Postgres (lets most projects **delete a manual dependency**), adds an
**optional** shared cache backend for multi-server deploys, and ships several
request/response **hardening fixes** (case-insensitive headers, multiple `Set-Cookie`,
body-read ordering, smarter `?` parameter conversion) plus several **security fixes**
(bounded request bodies, rightmost-untrusted `req.ip()`, CSRF token persistence, a
new **server-enforced session expiry**, and a **replay-resistant ops auth protocol, v2**)
covered at the end of this guide.

Two narrow cases **are breaking** and need action:

- Middleware patterns with an **interior wildcard** (e.g. `/api/*/admin`) are now
  rejected at startup with an `IllegalArgumentException` — see "middleware trailing
  `/*` now matches the bare prefix" below.
- Scripts that authenticated to `/ops/*` endpoints with a **`?token=` query parameter**
  must switch to the `Authorization: Bearer` header — see "`?token=` query-param auth
  removed" below.

Also note: the session-expiry change alters how long a stolen cookie stays valid — see
"sessions now carry a server-enforced expiry" below — and the old ops auth protocol (v1)
is now deprecated — see "ops auth protocol v2" below.

## Recommended cleanup: drop the manual `flyway-database-postgresql` dependency

**Background.** Flyway 10 (which Brace uses) split per-database support out of
`flyway-core` into separate modules. `flyway-core` bundles only a few handlers — H2 among
them — but **not** PostgreSQL. Through 0.1.6, Brace declared only `flyway-core`, so an app
running on Postgres failed at startup the moment `DatabaseFactory` ran migrations:

```
org.flywaydb.core.api.FlywayException: No database found to handle jdbc:postgresql://…
```

The standard workaround was to add the missing module — and the Postgres JDBC driver — to
your **own** `pom.xml`. Many Brace+Postgres projects carry exactly that.

**What changed in 0.1.7.** Brace now bundles `flyway-database-postgresql` itself (at
`runtime` scope, so it reaches your app transitively). The PostgreSQL JDBC driver was
already bundled this way. So a Brace project on Postgres needs **no Postgres dependencies of
its own** — Brace brings everything.

**The cleanup.** If your project's `pom.xml` declares either of these only to make Postgres
work, you can remove them:

**Before:**

```xml
<dependencies>
    <dependency>
        <groupId>com.larvalabs</groupId>           <!-- or com.github.larvalabs (JitPack) -->
        <artifactId>brace</artifactId>
        <version>0.1.7</version>
    </dependency>

    <!-- Added by hand to work around the packaging gap — no longer needed: -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.4</version>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
        <version>10.22.0</version>
    </dependency>
</dependencies>
```

**After:**

```xml
<dependencies>
    <dependency>
        <groupId>com.larvalabs</groupId>           <!-- or com.github.larvalabs (JitPack) -->
        <artifactId>brace</artifactId>
        <version>0.1.7</version>
    </dependency>
</dependencies>
```

**This cleanup is optional and safe to skip.** Leaving the explicit declarations in place is
harmless — Maven dedupes them against Brace's transitive versions. One reason to remove them:
a hand-pinned `org.postgresql` version (e.g. `42.7.4`) *overrides* the newer driver Brace
ships, so deleting it lets you track Brace's tested version instead.

**How to apply (mechanical):** in each project's `pom.xml`, delete any `<dependency>` on
`org.flywaydb:flyway-database-postgresql` and any `org.postgresql:postgresql` that you only
added for Brace. Recompile and start the app; migrations should run on Postgres unchanged.

## New (optional): shared cache backend for multi-server consistency

**Nothing to do unless you run more than one server.** The cache is unchanged by default —
in-process, same API, no new dependency or table. This release just adds the *option* to back
it with a shared store.

**Why you'd want it.** The default cache is per-process: on a horizontally-scaled deploy each
server keeps its own copy, so `cache.delete`/`clearTag` only invalidate the box that handled
the write, `cache.incr` counts per-instance (a rate limiter is off by a factor of N), and a
cached page can differ between servers. Opt into a shared, durable, cross-server-consistent
backend with one line — it reuses the Postgres database Brace already requires (no new infra):

**Before (per-process, all versions):**

```java
app.cache(Brace.cache());        // or omit entirely — in-process is the default
```

**After (opt into shared, 0.1.7+):**

```java
app.cache(CacheBackend.postgres(dbFactory));   // shared, durable, cross-server-consistent
```

That's the whole change. The shared backend creates its own tables (`brace_cache`, `brace_cache_counters`) via Brace's
framework migrations — no migration to write. `clear()` becomes a fleet-wide `TRUNCATE`,
`incr` is a single atomic statement, and a page rendered on one server is served by any other.

**Constraints (only on the shared backend; the in-process default has none):** values must be
Jackson-round-trippable (POJOs, records, collections, primitives, String) — a non-serializable
value throws at `set` time; `getOrSet` single-flight is per-server, not global. You can also run
both — keep the in-process default for hot read-through pages and a separate
`new Cache(CacheBackend.postgres(dbFactory))` for counters/invalidation that must be consistent.

**Choose per use case, not per deployment.** Full guidance, including the deferred near-cache
(L1/L2) tier, is in `docs/2026-06-04-brace-shared-cache.md` and the Cache section of
`BRACE-AGENTS.md`.

## New (optional): scoped read-only ops keys

**Nothing to do** — existing keys and tokens keep working unchanged. 0.1.7 adds a
**scope ceiling** per authorized ops key: `read` (status, errors, logs, routes — all
`GET`s) or `control` (everything, including `POST /ops/cache/clear` and
`POST /ops/errors/{id}/resolve`). A line in `ops-authorized-keys` with no marker
defaults to `control`, so existing files are backward compatible.

Mark a key read-only with a `scope:read` marker (or generate one directly):

```
# ops-authorized-keys — before (full control, and still valid in 0.1.7)
<base64-pubkey>  deploy-agent

# after — this key can read status/errors/logs but never mutate
<base64-pubkey>  scope:read  oncall-agent
```

```bash
brace ops keypair --read-only --label oncall-agent
```

`POST /ops/auth` caps every minted token at its key's ceiling, so a `scope:read` key
cannot obtain a control token even if it asks for one. Tokens carry a `kid` (key
fingerprint), and every authenticated ops request is logged as a structured
`ops.access` event (`kid`, scope, path, `granted`) for after-the-fact audit. The
intended use is handing an autonomous agent a key that can observe production but
cannot act on it. Details in `BRACE-AGENTS.md` → "Token scopes (read-only keys)".

## Request/response hardening fixes

These are bug fixes and small capability additions. None require code changes; all are
strictly more lenient or additive. One subtle behavior change (body-read ordering) is called
out explicitly below.

### Request headers are now case-insensitive

HTTP header names are case-insensitive, and arrive **lowercased** over HTTP/2. Through 0.1.6,
Brace matched them case-sensitively, so a client (or an HTTP/2-terminating proxy) sending
`content-type` or `cookie` in non-canonical case could silently bypass `req.isJson()`,
session-cookie loading, CSRF header checks, htmx detection, and ops bearer auth.

`req.header(name)` and `req.hasHeader(name)` are now case-insensitive.

```java
// Before 0.1.7: returned null if the client sent "content-type: application/json"
req.header("Content-Type");
// 0.1.7+: any casing works
req.header("content-type");   // == req.header("Content-Type")
req.isJson();                 // true regardless of header casing
```

**Action:** none. If you previously worked around this by checking multiple casings, you can
simplify.

### Responses can carry multiple `Set-Cookie` headers

`Result` stored headers in a single-value map, so a second cookie overwrote the first — most
visibly, the framework's session cookie could clobber an application cookie a handler set (or
vice versa). `Set-Cookie` is now kept in its own list and each value is emitted separately.

```java
// Now all of these survive to the wire together:
return Result.text("ok")
    .cookie("theme", "dark", 3600, false, true, "Lax")   // app cookie
    .cookie("locale", "en", 3600, false, true, "Lax");   // second app cookie
// ...plus the framework's brace_session cookie if the session was modified.
```

`result.header("Set-Cookie", value)` now **appends** rather than replaces; the new
`result.setCookies()` returns all values. Other headers are unchanged (still single-value).

**Action:** none, unless you relied on the old overwrite behavior (you almost certainly did not).

### Request bodies are read only for matched routes

Through 0.1.6 the request body (including multipart parsing) was read **before** route
matching, so static files, 404s, and before-middleware short-circuits paid the cost — and an
unmatched POST with a large multipart body was fully parsed into memory before the 404. The
body is now read only once a route matches.

**Subtle behavior change:** before-middleware running on a request that matches **no** route
now sees an empty `req.body()` (and no uploaded files). Middleware that inspects the body on
otherwise-unmatched paths is the only thing affected — rare, but if you have it, match a route
(even a catch-all) so the body is read.

### `?` parameter conversion skips literals/comments and supports `??`

The positional-parameter converter (`db.query`, `db.sql`, `db.hql`, …) no longer rewrites a
`?` that appears inside a single-quoted string literal or a SQL comment. A literal `?` elsewhere
— notably a Postgres JSONB `?`/`?|`/`?&` operator — can be escaped as `??`:

```java
db.query(Post.class, "title = 'huh?' AND status = ?", status);   // the ? in 'huh?' is left alone
db.sql("UPDATE t SET data = data WHERE meta ??| ARRAY['k']");     // ??| -> ?| JSONB operator
```

For SQL that needs full control, `db.jdbc(...)` remains the raw escape hatch.

**Action:** none, unless you wrote a literal `??` expecting two placeholders (it now means one
literal `?`) — use two separate `?` instead.

The converter has also been extended (correctness fix only — no API change) to recognise three
additional PostgreSQL string/identifier syntaxes so a `?` inside them is never misidentified as
a placeholder:

- **Dollar-quoted strings** — `$$...$$` and `$tag$....$tag$` (used in PL/pgSQL function bodies
  and multi-line literals). The scanner treats the entire body verbatim.
- **Double-quoted identifiers** — `"column?name"` — a `?` inside a quoted identifier is part of
  the name, not a parameter.
- **E-strings** — `E'...'` / `e'...'` — a backslash-escaped quote `\'` inside an E-string does
  not terminate the literal, so a `?` after the `\'` is still inside the string.

**Action:** none. If you previously had to use `db.jdbc(...)` only because your SQL contained
one of these constructs with a `?` inside, you can switch back to the regular helpers.

## Security fix: non-multipart request bodies are now capped at `maxUploadSize`

**Who is affected:** any application that accepts POST/PUT requests with plain (non-multipart)
bodies and has not called `app.maxUploadSize(...)`. The default cap is 10 MB — the same limit
that already applied to multipart uploads.

**What changed.** Through 0.1.6, `maxUploadSize` only bounded the multipart parser. A plain
POST body (JSON, URL-encoded form, raw text) was read into a single `String` with no size
limit. A client sending a chunked multi-hundred-MB body would buffer the entire payload in
heap before the handler ran, risking OOM under load.

0.1.7 enforces the same `maxUploadSize` cap on all plain bodies. When a request exceeds the
limit the server returns **413 Payload Too Large** immediately — for requests with a
`Content-Length` header that exceeds the limit, the rejection happens before reading any bytes.
For chunked (or absent-Content-Length) bodies the read is bounded incrementally.

**Code change required:** none. If your routes intentionally accept bodies larger than 10 MB,
set the limit explicitly:

```java
app.maxUploadSize("50MB");   // or app.maxUploadSize(50 * 1024 * 1024L)
```

**Visible behavior change:** routes that previously accepted arbitrarily large plain bodies now
return 413 for bodies over the configured limit. If you have clients or integration tests that
POST very large bodies and expect 200, increase `maxUploadSize` to cover the intended maximum.

## Security fix: `req.ip()` now returns the rightmost-untrusted address

**Who is affected:** any application using `RateLimiter.perIp`, IP-based access control, or
`req.ip()` for audit/logging **behind a trusted reverse proxy**. If you do not call
`app.trustedProxies(...)`, `req.ip()` always returns the socket address and this change has
no effect.

**What changed.** Through 0.1.6, `req.ip()` returned the **leftmost** entry in the
`X-Forwarded-For` header — the entry the client itself wrote. Because real proxies *append*
the connecting client's address, the leftmost entry is attacker-controlled and spoofable. A
malicious client could send `X-Forwarded-For: 1.2.3.4` to appear to come from any address,
bypassing rate limiting, IP allowlists, and audit logs.

0.1.7 implements **rightmost-untrusted** semantics: walk the chain from right to left, skip
any entry inside a trusted CIDR, return the first untrusted address.

**Before (leftmost — spoofable):**

```
X-Forwarded-For: spoofed-ip, real-client, 10.0.0.1  (trusted proxy appended last)

// Before 0.1.7: req.ip() → "spoofed-ip"   ← attacker-controlled
```

**After (rightmost-untrusted — correct):**

```
X-Forwarded-For: spoofed-ip, real-client, 10.0.0.1  (trusted proxy appended last)

// 0.1.7+: req.ip() → "real-client"   ← first untrusted entry from the right
```

**Code change required:** none — the API is unchanged. However, if your application code or
tests explicitly asserted that `req.ip()` returns the leftmost header entry in a multi-hop
scenario, those assertions now encode the wrong (spoofable) behavior and should be updated.

**Dual-stack note.** `TrustedProxies` fails closed when the address family does not match
(`127.0.0.1` does not match `::1`). If your proxy can connect via both IPv4 and IPv6, list
both representations:

```java
// Before (may miss the IPv6 loopback in dual-stack):
app.trustedProxies("127.0.0.1");

// After (covers both):
app.trustedProxies("127.0.0.1", "::1");
```

See the "Trusted Proxies" section of `docs/SECURITY.md` for a full behavioral table and
IPv6-mapped address guidance.

---

## Why the Postgres packaging gap went unnoticed until now

Brace's test suite ran entirely on in-memory H2, whose Flyway handler *is* bundled in
`flyway-core` — so the framework's own tests never exercised the real Postgres migration
path and never saw the gap. 0.1.7 adds a Postgres testcontainer test tier (`mvn verify`)
that runs the shipped migrations against real Postgres, which is what surfaced this. See
`docs/2026-06-05-pg-testcontainers.md`.

## Security fix: CSRF token now persists when rendered through a plain Handler

**Who is affected:** any route whose handler does **not** take a `Session` parameter (a
plain `Handler` or `DbHandler`), renders the CSRF hidden field via `${csrfField}`, and
POSTs the resulting form back to a CSRF-protected endpoint.

**What changed.** Through 0.1.6, Brace correctly minted a fresh CSRF token and exposed
it to the template (via `${csrfField}`) even for handlers that did not request a `Session`
parameter. However, the token was held in a transient local session object that was never
written back as a `brace_session` cookie on the response. The client had no cookie, so the
subsequent POST could not verify the token and always returned **403 CSRF required**.

The practical workaround was to add a `Session` parameter to every handler that rendered a
form — or to call `.csrf(false)` on the POST endpoint — even when the handler itself had
no other need for the session.

0.1.7 fixes this: when a fresh CSRF token is minted for a plain `Handler`, the session
cookie is written on that response automatically, so the POST can verify the token without
any change to application code.

**Code change required:** none — this is a pure fix. If your handlers already take a
`Session` parameter only to work around this bug, you may now remove that parameter if the
session is otherwise unused. Handlers that already took `Session` for other reasons are
unaffected.

**Visible behavior change:** plain `Handler` routes that render `${csrfField}` will now
produce a `Set-Cookie: brace_session=...` header on the GET response (establishing the
token). This was previously missing; if any code or test explicitly asserted that no
`Set-Cookie` header was present on such a response, that assertion should be removed.

Also in this release: PATCH requests are now treated as mutating for CSRF purposes
(alongside POST/PUT/DELETE). There is no `patch()` route registration method today, so
this only affects applications that register PATCH routes through lower-level APIs.

## Security fix: sessions now carry a server-enforced expiry (`_exp`)

**Who is affected:** every application that uses sessions. No code change is required, but
the behavior of long-lived/stolen cookies changes, so read the back-compat and
fixed-expiry notes below.

**What changed.** Through 0.1.6 the encrypted session payload carried **no expiry**. The
cookie's `Max-Age` attribute is enforced only by the client, so a copied or stolen session
cookie stayed valid **indefinitely** — until the global `session.secret` was rotated.

0.1.7 stamps a server-enforced absolute expiry, `_exp` (epoch seconds), **inside the
encrypted payload** on every write. On read, Brace rejects a cookie whose `_exp` is in the
past and returns an empty session. The expiry horizon is `SessionOptions.maxAge()` when set
to a positive duration; otherwise a **14-day default** (so even "session-lifetime" cookies
that set no `Max-Age` still expire server-side).

```java
// Default 14-day server-side horizon — nothing to configure:
app.sessions(SessionOptions.secure(secret));

// Explicit horizon flows through to _exp as well as the Max-Age cookie hint:
app.sessions(SessionOptions.secure(secret).maxAgeDays(30));   // _exp = now + 30d
```

`_exp` is a **reserved, server-managed key**: `session.set("_exp", …)` is silently ignored
(a handler can't forge or extend its own expiry), and `_exp` is stripped from the decrypted
data so it never shows up via `session.get("_exp")` / `session.has("_exp")`.

### Back-compat: legacy cookies are accepted this release, rejected in a future one

Cookies minted by **≤0.1.6 have no `_exp`**. To avoid logging out every user on the deploy,
this release **accepts** expiry-less cookies and **re-mints them with `_exp` on the next
write** (e.g. the next request that modifies the session). This closes the
indefinite-validity hole for all *new* cookies immediately while letting existing sessions
roll over naturally.

**A future release will reject cookies without `_exp`.** At that point any session that has
not been written since upgrading to 0.1.7 will be treated as expired (the user re-logs in).
Plan for a one-time logout of still-legacy sessions when you take that upgrade.

**Before (≤0.1.6):**

```
brace_session = encrypt({ "userId": "42", "_csrf": "…" })
// no expiry in the payload → valid forever until secret rotation
```

**After (0.1.7+):**

```
brace_session = encrypt({ "userId": "42", "_csrf": "…", "_exp": "1718064000" })
// server rejects the cookie once now > _exp, regardless of the client-side Max-Age
```

### Fixed expiry from last write — there is no sliding window

A session is only re-minted when a handler **modifies** it (`session.isModified()`), so the
expiry is measured from the **last write**, not the last request. An active user whose
session is never modified can therefore be logged out when the horizon elapses (e.g. 14 days
after login) **even with daily activity**. This is intentional — Brace does **not** refresh
the cookie on every response (that would add a `Set-Cookie` to every request and defeat
response caching).

If you want sessions to renew with activity, write to the session on the requests that
should extend it — for example bump a value so `isModified()` becomes true:

```java
session.set("lastSeen", String.valueOf(System.currentTimeMillis()));  // re-mints _exp
```

**Code change required:** none. **Visible behavior change:** sessions now expire server-side
(default 14 days from last write); a stolen cookie is no longer valid until secret rotation.

## Security fix: `?token=` query-param auth removed from general ops endpoints

**Who is affected:** any script, agent, or client that authenticates to `/ops/*` endpoints by
appending `?token=<bearer-token>` to the URL rather than sending an `Authorization: Bearer`
header. **`brace` CLI users are not affected** — the CLI has always used `Authorization: Bearer`.

**What changed.** Through 0.1.6, general ops endpoints accepted a bearer token via a
`?token=` query parameter as a fallback authentication channel. This fallback was removed
because query-parameter tokens leak in:

- Reverse-proxy and CDN **access logs** (the full URL, including query string, is logged by
  default)
- **Browser history** (any browser that follows a link or redirect containing `?token=`)
- **Referer headers** sent with any outbound requests the dashboard page makes

The `?token=` channel survives on `/ops/auth/exchange` only (the browser-redirect handoff from
the CLI), where it is the only viable channel — you cannot put a credential in the headers of a
plain GET redirect. The exchange token is short-lived (60s) and scope-capped; the exchange
response now carries `Referrer-Policy: no-referrer` and `Cache-Control: no-store` to limit
further leakage.

Additionally, the ops browser session TTL was shortened from **24h to 8h** (one workday).
With scope-preservation (H1), the session is already bounded to the caller's key ceiling; 8h
limits the damage window of a stolen cookie without meaningfully affecting normal usage.

**Before (≤0.1.6 — leaks token into proxy logs and browser history):**

```bash
# Wrong: token in URL is logged by every proxy, CDN, and nginx in the path
curl "https://app.example.com/ops/status?token=$TOKEN"
```

**After (0.1.7+ — token only in the Authorization header):**

```bash
# Correct: token in the Authorization header is not logged by standard proxy configs
curl -H "Authorization: Bearer $TOKEN" https://app.example.com/ops/status
```

**Code change required:** any script or agent that appended `?token=` to `/ops/*` URLs must be
updated to pass the token as `Authorization: Bearer <token>`. This is a **breaking change** for
such callers. The bearer token itself is obtained the same way — via `POST /ops/auth` — only
the delivery channel changes.

---

## Security fix: ops auth protocol v2 (key-bound, nonce'd signature); v1 deprecated

**Who is affected:** every user of the `brace` CLI ops commands (`brace status`, `errors`,
`logs`, …) — **no action needed**, the 0.1.7 CLI speaks v2 automatically, and when it
talks to a server still running ≤0.1.6 (which cannot parse a v2 body) it detects the
rejection and falls back to v1 for that request, printing a warning to upgrade the
server. Mixed-version fleets work in both directions during the upgrade window; the
fallback goes away when v1 support is removed. Action is only required if you
implemented the `/ops/auth` handshake yourself (e.g. a custom agent or script signing
requests directly).

**What changed.** Through 0.1.6, the `/ops/auth` client signed **only the ISO timestamp**,
and the server accepted it within ±30 seconds. The signature was not bound to the public
key and carried no nonce, so anyone who observed one auth request — a proxy log, a packet
capture on a mis-terminated hop — could replay the `(publicKey, timestamp, signature)`
tuple within 30 seconds and mint a fresh bearer token at any scope up to that key's
ceiling.

0.1.7 introduces **protocol v2**: the request body carries `v: "2"` and a fresh random
`nonce` (base64url, 16+ bytes, new on every attempt), and the signature is computed over

```
publicKey + "\n" + timestamp + "\n" + nonce
```

Binding the public key into the signed message means a captured signature is only ever
valid for the key that produced it, and the server rejects a reused nonce — a captured
request can no longer be replayed against the same instance.

**Before (≤0.1.6, v1):**

```json
{ "publicKey": "…", "timestamp": "2026-06-09T12:00:00Z",
  "signature": Ed25519(timestamp) }
```

**After (0.1.7+, v2):**

```json
{ "v": "2", "publicKey": "…", "timestamp": "2026-06-09T12:00:00Z",
  "nonce": "<base64url, 16+ random bytes per attempt>",
  "signature": Ed25519(publicKey + "\n" + timestamp + "\n" + nonce) }
```

The full wire format is documented in `docs/agent-ops-guide.md` → "Auth protocol (v2)".

### v1 is accepted this release, rejected in a future one

Because v1 shipped in 0.1.6, a 0.1.7 server still **accepts v1 requests** (a body with no
`v` field) so existing CLIs keep working across the upgrade — each v1 auth logs a
deprecation warning naming the key fingerprint. **A future release will reject v1** with
`ops auth protocol v2 required; upgrade the brace CLI`. Upgrade your CLI (and any
hand-rolled clients) before taking that release.

**Known limitation (unchanged risk posture for v1, by design for v2):** the server's
seen-nonce set is per-instance and in-memory — ops works without shared fleet state — so
behind a load balancer a captured v2 request remains replayable against a *different*
instance within the ±30s timestamp window. HTTPS on every hop to `/ops/*` remains the
primary control; see `docs/SECURITY.md` → "Ops Endpoints".

## Security fix: static files now carry `X-Content-Type-Options: nosniff`

**Impact:** none. This is a pure security header addition.

**What changed.** Static file responses (served via `app.staticFiles(...)` and the bundled
`/__brace/htmx.min.js`) now include the `X-Content-Type-Options: nosniff` header. This header
prevents browsers from MIME-sniffing the response — treating a misnamed `.svg` as executable
JavaScript, for example — and narrows the attack surface for SVG-based XSS (which can execute
scripts if the browser sniffs it as HTML instead of image/svg+xml).

**Code change required:** none. This is a pure fix.

## Security fix: OpsDashboard HTML escaping now handles single quotes

**Impact:** none unless tokens contain single quotes (extremely rare).

**What changed.** The OpsDashboard dashboard embeds the auth bearer token in htmx
`hx-headers` attributes inside single-quoted JSON:

```html
<div hx-headers='{"Authorization": "Bearer TOKEN"}' ...>
```

Through 0.1.7, the `esc()` helper escaped HTML entities (`&`, `<`, `>`, `"`) but not single
quotes (`'`). If a token (generated by the framework) happened to contain a single quote,
it would break out of the attribute and corrupt the HTML. This was a latent issue — the
framework's token generators don't include single quotes — but the escaping was incomplete.

0.1.7 now escapes single quotes as `&#39;` alongside the other entities, closing the gap.

**Code change required:** none. This is a pure safety fix.

## Security fix: open-redirect helper (safe redirect for user input)

**New optional API:** `Redirect.toLocal(path)` and `Redirect.permanentLocal(path)`.

**What changed.** Through 0.1.7, `Redirect.to(location)` accepted any string — absolute URLs,
protocol-relative URLs, and local paths. When used with untrusted input (e.g.,
`Redirect.to(req.queryParam("next"))`), this enables open-redirect vulnerabilities: an
attacker can pass `next=https://attacker.com` to craft a phishing link.

0.1.7 introduces two new helpers that validate the path is local using an **allowlist** rule:

```java
// 0.1.7+ — safe for user input
return Redirect.toLocal(req.queryParam("next"));      // throws if not local
return Redirect.permanentLocal(req.queryParam("next"));
```

The validation rule (all must hold):

1. Path is non-null and non-empty.
2. First character is `'/'`.
3. Second character (if present) is neither `'/'` nor `'\\'` — rejects `//evil.com` (protocol-relative)
   and `/\evil.com` (browsers normalize the backslash to `/`, yielding `//evil.com`).
4. No backslash anywhere in the string.
5. No literal ASCII control characters (code points `< 0x20`) anywhere. Percent-encoded sequences
   like `/%09/x` are **not** decoded and are accepted as-is.

This allowlist approach is secure against the common bypass patterns:

| Input | Old denylist | New allowlist |
|---|---|---|
| `https://attacker.com` | rejected (`://`) | rejected (no leading `/`) |
| `//attacker.com` | rejected (`//`) | rejected (second char `/`) |
| `/\evil.com` | **accepted** (bypass) | rejected (backslash) |
| `https:/evil.com` | **accepted** (no `://`) | rejected (no leading `/`) |
| `https:evil.com` | **accepted** | rejected (no leading `/`) |
| `dashboard` | accepted | **rejected** (no leading `/`) |
| `/dashboard` | accepted | accepted |
| `/path?next=//x` | accepted | accepted (`//` only in query) |

**Behavior change:** bare relative paths without a leading `/` (e.g., `"dashboard"`) were accepted
by the old denylist but are now rejected. `toLocal` / `permanentLocal` are new in 0.1.7 (not
present in 0.1.6), so no 0.1.6 code calls them — there is no compatibility concern.

**Code change required:** none. The original `Redirect.to()` and `Redirect.permanent()`
remain unchanged and work as before. Use the new `toLocal()` variants **only when the path
is derived from user input** (query params, form fields, etc.). For paths you control,
continue using `Redirect.to()`.

**Documentation note:** the class Javadoc documents the exact allowlist rule.

## Security fix: middleware trailing `/*` now matches the bare prefix

**Who is affected:** applications using `before("/path/*", ...)` or `after("/path/*", ...)`
patterns to guard a section or inject headers.

**What changed.** Through 0.1.6, a middleware pattern `/admin/*` matched `/admin/users` and
`/admin/secret` but **not** `/admin` or `/admin/` itself. A developer guarding a section
with `before("/admin/*", auth)` left the index route at `/admin` unauthenticated. Interior
wildcards (e.g., `/api/*/edit`) silently never matched anything.

0.1.7 fixes both:

1. **Trailing `/*` now matches the bare prefix:** `/admin/*` now matches `/admin`, `/admin/`,
   and `/admin/anything`. This is a **behavior change** — if you relied on the `/admin` gap,
   you must now explicitly add a matching route or `before("/admin", ...)`.
2. **Interior wildcards are rejected at registration:** patterns like `/api/*/edit` now throw
   `IllegalArgumentException` at startup instead of failing silently. Only a trailing `/*`
   is supported.

**Before (0.1.6):**

```java
app.before("/admin/*", auth);
// /admin/dashboard  → blocked by middleware ✓
// /admin             → skipped middleware ✗ (bug)
```

**After (0.1.7+):**

```java
app.before("/admin/*", auth);
// /admin/dashboard  → blocked by middleware ✓
// /admin             → blocked by middleware ✓ (fixed)
// /admin/           → blocked by middleware ✓ (fixed)
```

**Action:** review any middleware patterns. If you have a handler on the bare prefix
(`app.get("/admin", ...)`) and you want it guarded, the middleware will now apply —
usually correct, but double-check that your logic is right. If you have an interior
wildcard like `/api/*/edit`, change it to guard at the `/api/*` level (match the prefix)
and check the matched path in your handler if you need per-segment control.

**No action needed** if your middleware patterns already used only trailing `/*` or exact
paths (no `*` at all).

## Security fix: `Class.forName` initializer control on durable jobs and cache entries

**Who is affected:** any application using durable jobs (`Jobs.schedule(...)`) or the cache
with serialized values on a shared backend.

**What changed.** Brace now loads stored class names with `Class.forName(name, false, loader)`,
preventing static initializers from running before type validation. Combined with an explicit
`isAssignableFrom` check, this closes a potential vector for code execution if an attacker
gains write access to the `scheduled_jobs` or `brace_cache` tables.

**Trust boundary:** the `scheduled_jobs` table (durable job queue) and `brace_cache` table
(shared cache) are **application-controlled** — they should be trusted. This fix hardens the
code path in case an attacker compromises the database but not the application code. If you
store untrusted class names in either table, that is itself a security issue; see
**SECURITY.md** under "Database Security" for trust boundary guidance.

**Action:** none. The fix is transparent — existing durable jobs and cached values work as
before. If you have custom code that directly calls `Class.forName` on stored class names,
apply the same pattern: disable initializers with the `false` parameter and validate the
loaded class before instantiation.

## Security fix: page-cache keys now percent-encode query parameters

**Who is affected:** applications using `cache.wrap(...)` on routes that accept query parameters.

**What changed.** Page cache keys are now percent-encoded to prevent collisions when query
parameter values contain special characters like `&`, `=`, or `%`. This fixes a cache-collision
vulnerability where two semantically different requests (e.g., `/search?q=a&b` vs. `/search?q=a%26b`)
could share the same cache entry, causing one user to see another user's cached response.

**Cache format change:** the percent-encoding changes cache key format. Existing cached pages
keyed under the old format will simply **cache-miss** on the first request after upgrade — a
benign degradation for a cache. The miss rate normalizes as the cache repopulates. No manual
cache invalidation is needed.

**Before (0.1.6):** Param values are concatenated unescaped into the key.

```java
// Request: /search?q=a&filter=b
// Key: page:GET:/search?q=a&filter=b   ← ambiguous if 'a' contains literal &

// Request: /search?q=a%26filter=b       ← looks different in URL, same in cache!
// Key: page:GET:/search?q=a%26filter=b  ← same as above → collision
```

**After (0.1.7+):** Param names and values are percent-encoded before insertion.

```java
// Request: /search?q=a&filter=b
// Key: page:GET:/search?q=a&filter=b   ← (& and = in keys are literal, safe)

// Request: /search?q=a%26filter=b
// Key: page:GET:/search?q=a%2526filter%3Db  ← different (the & and = are encoded)
```

**Action:** none. The fix is transparent — existing code works unchanged. Cached entries from
0.1.6 will expire naturally or via `cache.clear()` when you upgrade; cache hits resume after
repopulation.

## Security fix: `brace new` validates project names

**Who is affected:** developers using the `brace new <name>` CLI command.

**What changed.** The `brace new` command now validates the project name to prevent path traversal
and pom.xml injection attacks. Project names must contain only letters, numbers, underscores, and hyphens:
`[A-Za-z0-9_-]+`.

**Before (0.1.6):**

```bash
brace new ../evil          # Created ../evil/ directory (path traversal)
brace new "my';DROP--"     # Injected into pom.xml
```

**After (0.1.7+):**

```bash
brace new ../evil
# Failed to create project: name must contain only letters, numbers, underscores, and hyphens.

brace new my_project       # ✓ valid
brace new my-project-2024  # ✓ valid
```

**Action:** none. If you use `brace new`, the command only accepts safe names now. Any scripts or
automation that pass project names should already be using safe characters; if you receive an error,
change the name to use only `[A-Za-z0-9_-]`.

## Security fix: bcrypt helper for constant-time enumeration-timing mitigation

**Who is affected:** applications that implement login/authentication with password checks.

**What changed.** `Passwords` now includes a new `dummyCheck(String password)` helper for constant-time
user-enumeration mitigation. When a user is not found in the database (or for any other reason you
don't have a password hash), call `dummyCheck(password)` before returning the error. This makes the
response time indistinguishable from a failed password check, preventing attackers from distinguishing
valid usernames by observing timing differences.

**Before (0.1.6 — vulnerable to enumeration timing):**

```java
var user = db.findByEmail(email);
if (user == null) {
    // No delay — returns immediately, faster than a real password check
    return unauthorized("Invalid credentials");
}

if (!Passwords.check(password, user.passwordHash)) {
    // bcrypt takes ~500ms → timing reveals the user exists
    return unauthorized("Invalid credentials");
}

return ok("logged in");
```

**After (0.1.7+ — constant-time response):**

```java
var user = db.findByEmail(email);
if (user == null) {
    // Perform a dummy bcrypt check to consume time, same as a real check
    Passwords.dummyCheck(password);
    return unauthorized("Invalid credentials");
}

if (!Passwords.check(password, user.passwordHash)) {
    return unauthorized("Invalid credentials");
}

return ok("logged in");
```

**Additional improvements:** `Passwords.hash(password)` now rejects null passwords with a clear error,
and `Passwords.check(password, hash)` throws if the hash is null (preventing silent failures).

**Action:** if you implement custom authentication:

1. Add `Passwords.dummyCheck(password)` to your "user not found" path
2. Ensure null password / hash errors are handled (they now throw `IllegalArgumentException`)

The API is backward-compatible — existing `hash()` and `check()` calls work unchanged (except they
now enforce non-null inputs).

## Security fix: TrustedProxies dual-stack IPv6 representation mismatch (documentation)

**No code change required.** This fix updates `docs/SECURITY.md` with guidance on dual-stack proxy
configurations. The underlying behavior is unchanged; this documents a known limitation.

**What changed.** `TrustedProxies` matches addresses by binary value after DNS resolution, but fails
closed (returns `false`) when the address family differs — an IPv4 CIDR does not match the IPv6-mapped
form, and `127.0.0.1` does not match `::1` on a dual-stack bind.

If your reverse proxy can connect to your application over both IPv4 and IPv6, you must list both
representations:

```java
// Before: may fail to recognize IPv6 proxy on dual-stack
app.trustedProxies("127.0.0.1");

// After: covers both
app.trustedProxies("127.0.0.1", "::1");
```

**Impact:** none, unless you have a dual-stack proxy setup. For IPv6-mapped addresses (`::ffff:a.b.c.d`),
see the "Trusted Proxies" section of `docs/SECURITY.md` for examples.

---

## Security fix: high-entropy path segments redacted in access logs and ops stats

**Who is affected:** anyone consuming Brace's structured logs or `/ops/status` route
stats — for example, parsing the `path` field of `http.request` entries.

**What changed.** Through 0.1.6 (and earlier 0.1.7 snapshots), the per-request access
log, the `/ops/logs` ring buffer, and the per-route stats on `/ops/status` recorded the
raw request path. A secret carried in a path position — a password-reset token, an
invite link — was persisted on every **successful** request, even though error records
already redacted it. Exception messages flowing to `Log.error` and the `/ops/status`
error list had the same gap.

0.1.7 runs the value-shaped redaction pass (same heuristic as the error store — see
"Error Store Redaction" in `docs/SECURITY.md`) in the sinks themselves:

```json
// Before
{"event":"http.request","method":"GET","path":"/password-reset/a3f9Bc2d8eF1g4h5","status":200}

// After
{"event":"http.request","method":"GET","path":"/password-reset/[redacted]","status":200}
```

Route keys on `/ops/status` collapse the same way (`GET /password-reset/[redacted]`),
which also stops token-bearing routes from growing the per-route stats map per request.

**Impact:** purely numeric IDs, short slugs, and UUIDs are untouched, so typical REST
paths log exactly as before. Only segments that look like secrets (≥16 chars,
base64url/hex alphabet, mixed letters and digits) are replaced with `[redacted]`. If
your log tooling matched on such paths, match on the `[redacted]` placeholder instead.

## New: `opsProfiler(boolean)` — opt out of the always-on JFR profiler

**Who is affected:** no one is required to change anything — this is a new optional
builder method. Default behavior is unchanged.

When ops is enabled (`app.ops(...)`), Brace starts a continuous JFR recording stream
that backs the JVM panels of `/ops/dashboard` (CPU load, GC pauses, hot methods,
allocation sampling). That sampling costs roughly 0.5–2% CPU plus one thread, around
the clock — usually a worthwhile trade for production diagnostics, and it stays on by
default. 0.1.7 adds an explicit opt-out for CPU-constrained instances:

```java
// Before (and still the default): JFR profiler on whenever ops is enabled
Brace.app().ops("ops-authorized-keys")

// After: ops without the profiler
Brace.app().ops("ops-authorized-keys").opsProfiler(false)
```

With the profiler disabled, the dashboard falls back to basic runtime heap numbers and
the `jvm.*` rows in `ops_timeseries` (and the 5-minute profiling snapshots) are not
collected.

Related fix in the same change: on ops-enabled apps **without a database**, the JFR
method/allocation sample maps were never reset and grew for the life of the JVM; they
now reset every 5 minutes, matching the cadence of the DB-backed metrics flush.

## Changed: stdout logging is now asynchronous (single writer thread)

**Who is affected:** apps that consume Brace's structured stdout logs, and tests that
assert on captured stdout immediately after a request.

Through earlier 0.1.7 snapshots, every `Log.*` call serialized JSON and wrote it to
`System.out` inline on the calling thread — one global `PrintStream` lock and one write
syscall per line, on every request. Under load this was the framework's main contention
point (and a virtual-thread carrier-pinning hazard on JDK 21–24).

Log entries are now placed on a bounded in-memory queue (8,192 entries) and written in
batches by a dedicated `brace-log-writer` daemon thread.

What this means in practice:

- **Ordering and content are unchanged** — same JSON shape, same redaction, still stdout.
- **Lines can trail the event by a few milliseconds.** Log processors are unaffected;
  tests that assert on captured stdout right after a request should call `Brace.stop()`
  first (which flushes) or assert via `/ops/logs` — the in-memory ring buffer is still
  written synchronously and is the more precise tool.
- **Overflow drops oldest lines, counted.** If more than ~8k lines back up (sustained
  faster than stdout can drain), the oldest are dropped and a
  `{"event":"log.dropped","count":N}` WARN is emitted with the next batch.
- **JVM exit and `Brace.stop()` flush the queue**, so shutdown logs are not lost.

## New: minimum log level

`Log` now supports a minimum level — `DEBUG` (default, logs everything: the previous
behavior), `INFO`, `WARN`, `ERROR`. Entries below the level are skipped before any
formatting work and reach neither stdout nor `/ops/logs`.

```java
Log.level("INFO");               // in code
// or: BRACE_LOG_LEVEL=INFO      (env var)
// or: -Dbrace.log.level=INFO    (system property)
```

## Changed: CSRF token minting is lazy; `.csrf(false)` routes skip session crypto entirely

**Who is affected:** log/traffic tooling that expected a `brace_session` Set-Cookie on
every response, and (rare) templates that render `${csrfField}` from a route marked
`.csrf(false)`.

Two behavior refinements on apps with sessions enabled:

1. **`.csrf(false)` routes do no CSRF work at all.** Previously even opted-out routes
   (bearer-token APIs) decrypted the session cookie and minted a token on every request,
   and cookieless clients received a fresh `Set-Cookie` on every response. Such routes
   now skip the decrypt, the mint, and the cookie — `${csrfField}` is no longer populated
   for them (they never validated it anyway). If an opted-out route really needs the
   hidden field, take a `Session` parameter and call `Csrf.ensureToken(session)` +
   `Csrf.hiddenField(session)` directly.

2. **On CSRF-required routes the token is minted when first consumed, not per request.**
   Rendering a view (or calling `View.getCsrfField()`) mints the token and writes the
   session cookie exactly as before — form flows are unchanged. But JSON/redirect/text
   responses that never render the field no longer mint tokens, so cookieless clients
   (health checks, bots, API consumers hitting HTML routes) no longer trigger an
   encrypt + `Set-Cookie` per request. Handlers that need the token programmatically
   without rendering can call `Csrf.ensureToken(session)` themselves.

CSRF **validation** of mutating requests is completely unchanged. Performance: the
session cookie is now decrypted at most once per request (mutating requests on
no-session routes previously decrypted it twice).

## Changed: `/ops/status` route stats are keyed by route pattern

**Who is affected:** tooling that parses the per-route stats keys from `/ops/status`
(the "slowest routes" data) or the ops dashboard.

Per-route stats were keyed by the concrete (redacted) request path, so `GET /users/1`
and `GET /users/2` were separate entries — fragmenting averages across entity IDs and
growing the stats map by one permanent entry per distinct URL ever requested (a slow
memory leak on ID-rich or scanner-probed apps).

Matched requests are now keyed by the **route pattern**:

```
// Before
"GET /users/1": {...}, "GET /users/2": {...}, "GET /users/3": {...}

// After
"GET /users/{id}": {count: 3, ...}
```

This bounds the map by the number of registered routes and makes "slowest routes"
aggregate per route, which is almost certainly what you wanted. Requests that never
match a route keep the old concrete-path keying (with secret redaction).

## Changed: finished durable jobs are pruned after 7 days (configurable)

**Who is affected:** apps using durable jobs (`Jobs.schedule(...)`) that treat old
`scheduled_jobs` rows as a permanent audit log, or that query the table directly.

Completed and failed job rows previously stayed in `scheduled_jobs` forever, so the
poller's claim query walked an ever-growing prefix of dead rows on every poll (every
10 seconds), and `/ops/status` job counts scanned the full history. Now a daily
framework job (`brace-jobs-prune`, runs once cluster-wide) deletes finished rows older
than 7 days. Rows another job still references via `JobOptions.after(...)` are kept
regardless of age.

```java
// Keep finished jobs for 30 days instead
app.jobRetention(30);

// Restore the pre-0.1.7 keep-forever behavior
app.jobRetention(0);
```

If you need job history beyond the retention window, copy what you need into your own
table from the job itself — `scheduled_jobs` is a queue, not an archive.

On Postgres this release also adds a partial index over claimable jobs
(`idx_scheduled_jobs_claimable`, framework migration V15, applied automatically), so
claim cost tracks live work rather than table size.

## Changed: durable-job concurrency is bounded by the connection pool

**Who is affected:** apps that rely on many durable jobs running simultaneously.

The poller previously ran up to 50 jobs at once against the same connection pool web
handlers use (default pool size 10) — a job burst could take every connection, queue
requests behind `connectionTimeout`, and 500 the request path. It also waited for an
entire batch to finish before polling again, so one slow job delayed every other queued
job for its duration.

Now at most `poolSize / 2` jobs execute concurrently (5 with the default pool), claim
batches are sized to the free capacity, and the poller claims more work as soon as a
slot frees instead of waiting for the whole batch. Each job also uses one database
session for both execution and its completed/failed mark (previously two, three on the
failure path).

Net effect: requests stay healthy during job bursts, and total queue throughput for
mixed fast/slow workloads goes up — but peak job parallelism drops from 50 to
`poolSize / 2`. If you need more, raise the pool size via the `DatabaseFactory`
constructor's `poolSize` argument.

## Breaking: cached routes ignore query params unless declared with `.vary(...)`

**Who is affected:** any app using route-level page caching (`cache.wrap(...)`) on routes
whose content depends on query params — pagination, sorting, filtering.

Page-cache keys previously included the **entire query string**, so every distinct
param combination stored a full copy of the rendered page. The request side controls
that keyspace: `GET /cached-page?x=<random>` minted one page-sized cache entry per
request (memory exhaustion on the in-memory backend, a row insert per request on the
Postgres backend), and even benign crawler/tracking params (`?utm_source=`,
`?fbclid=`) fragmented the cache.

Now **query params are ignored by default** — a cached route serves one entry per
path. Declare the params that legitimately change the page:

```java
// Before (0.1.6): every distinct query string = its own cache entry
app.get("/posts", cache.wrap("10m", ctrl::list));

// After (0.1.7): declare what varies; everything else is ignored
app.get("/posts", cache.wrap("10m", ctrl::list).vary("page", "sort"));
//   /posts?page=2                 → its own entry
//   /posts?page=2&utm_source=tw   → same entry as ?page=2
//   /posts?x=<random-flood>       → same entry as /posts
```

**Action required:** audit every `cache.wrap(...)` route. If the handler reads a query
param, add it to `.vary(...)` — otherwise the cache will serve the same entry for all
values of that param (e.g. `?page=2` returning page 1). Routes that ignore the query
string entirely need no change and get a better hit rate.

`HX-Request` varying is unchanged (htmx partials and full pages stay separate).

Relatedly, the in-memory cache backend is now **capped at 10,000 entries** (previously
unbounded): past the cap, inserting a new key drops expired entries first, then
arbitrary ones. Size it with `app.cache(CacheBackend.inMemory(maxEntries))`. Counters
and tags are not capped.

## Changed: Mailer no longer retains sent emails in production

**Who is affected:** apps reading `mailer.sent()`/`mailer.last()` outside dev mode, or
tooling that treated `sentCount` as "send attempts".

The mailer previously appended every email — full HTML and text bodies — to an
unbounded in-memory list even when really sending via SMTP: a slow leak of roughly
20KB per email, ~2GB per 100k sends. Now:

- **Capture is dev-only** (`new Mailer(null)`, no SMTP URL) and bounded to the last
  500 emails, dropping the oldest. The `mailer.sent()` / `mailer.last()` /
  `mailer.clearCaptured()` test API is unchanged within that window.
- **With SMTP configured, nothing is captured.** `mailer.sent()` returns an empty
  list in production — it was never a safe thing to rely on, and now it's explicit.
- **`sentCount()` counts successful sends only** (it previously counted attempts,
  including failures, and was reset by `clearCaptured()` in all modes). Failed sends
  count in `failCount()` as before. The /ops dashboard "Sent" stat therefore now
  means emails actually handed to SMTP. `sentCount()` also widens `int` → `long`;
  recompile if you call it (source-compatible).
