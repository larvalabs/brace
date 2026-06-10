# Migrating from Brace 0.1.6 → 0.1.7

This release has **no breaking changes** — no code changes are required. It fixes a
packaging gap for Postgres (lets most projects **delete a manual dependency**), adds an
**optional** shared cache backend for multi-server deploys, and ships several
request/response **hardening fixes** (case-insensitive headers, multiple `Set-Cookie`,
body-read ordering, smarter `?` parameter conversion) covered at the end of this guide.

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
