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

## New (optional): form binding for enums, `LocalDate`, `Instant`, `BigDecimal`

**Nothing to do** — purely additive. Form (and `jsonForm`) records can now declare
enum, `LocalDate`, `Instant`, and `BigDecimal` components directly; previously these
types fell through the binder and crashed record construction with a 500. Unparseable
input becomes a field error (`"must be a date (yyyy-MM-dd)"`, `"must be one of: DRAFT,
PUBLISHED"`, …) — same `hasErrors()` handling as every other validation failure.

**Before (all versions, still works):**

```java
public record EventForm(@Required String name, String startDate) {}
// ...then hand-parse: LocalDate.parse(form.value().startDate()) wrapped in try/catch
```

**After (0.1.7+):**

```java
public record EventForm(@Required String name, LocalDate startDate) {}
```

## New (optional): `Json.obj` — one-line ad-hoc JSON shapes

**Nothing to do** — purely additive. For one-off response shapes, `Json.obj` replaces
the LinkedHashMap-and-put block. It preserves key order and allows `null` values
(the two reasons `Map.of` doesn't work for JSON responses).

**Before (all versions, still works):**

```java
var response = new LinkedHashMap<String, Object>();
response.put("talkId", id);
response.put("averageRating", avg);   // may be null — Map.of would throw
return Json.of(response);
```

**After (0.1.7+):**

```java
return Json.of(Json.obj("talkId", id, "averageRating", avg));
```

## New (optional): `req.jsonForm` — declarative validation for JSON bodies

**Nothing to do** — purely additive. The `@Required`/`@Min`/`@Email` annotation
vocabulary (previously reachable only through form-encoded `req.form()`) now works
for JSON request bodies, including the record's custom `validate(Errors)` method.
Malformed or non-object JSON becomes a `"_body"` validation error instead of an
exception, so the `hasErrors()` idiom covers it — unlike `req.bodyAs()`, which
throws on a parse failure and surfaces as a 500.

**Before (all versions, still works):**

```java
Talk input;
try { input = req.bodyAs(Talk.class); }
catch (Exception e) { return Result.badRequest("invalid json"); }
if (input.title == null || input.title.isBlank()) return Result.error(400, "title required");
if (input.durationMinutes <= 0) return Result.error(400, "duration must be positive");
// ... repeated per field, duplicated between POST and PUT
```

**After (0.1.7+):**

```java
var form = req.jsonForm(TalkForm.class);   // same record + annotations as req.form()
if (form.hasErrors()) return Result.json(Map.of("errors", form.allErrors()), 422);
```

## New (optional): typed read-only route methods (`getRead`, `getReadFull`, …)

**Nothing to do** — purely additive; existing cast-style registrations keep working.
0.1.6 added `getDb`/`postSession`/`putFull` etc., but read-only handlers (the most
common kind — almost every GET) still required a cast, because the raw
`get/post/put/delete` overloads are ambiguous for multi-arg lambdas. 0.1.7 completes
the set: `getRead/postRead/putRead/deleteRead` (ReadDbHandler — DB queries, no
transaction) and `getReadFull/...` (ReadFullHandler — read-only DB + session), on
both `Brace` and route groups.

**Before (all versions, still works):**

```java
app.get("/posts", (ReadDbHandler) (req, db) -> Json.of(db.findAll(Post.class)));
```

**After (0.1.7+, the canonical form):**

```java
app.getRead("/posts", (req, db) -> Json.of(db.findAll(Post.class)));
```

## New (optional): `db.findOr404` / `db.queryOneOr404` lookup helpers

**Nothing to do** — purely additive. The find/null-check/404 preamble that every
show/update/delete handler starts with collapses to one line; the helpers throw
`NotFoundException`, which Brace already renders as a 404 response.

**Before (all versions, still works):**

```java
var post = db.find(Post.class, req.longPathParam("id"));
if (post == null) return Result.notFound();
```

**After (0.1.7+, the canonical lookup):**

```java
var post = db.findOr404(Post.class, req.longPathParam("id"));
// and for non-ID lookups:
var bySlug = db.queryOneOr404(Post.class, "slug = ?", slug);
```

## New (optional): `db.queryPage` pagination + ORDER BY in where-fragments is now documented behavior

**Nothing to do** — purely additive; existing `db.query(...)` calls keep working
unchanged. Two related improvements to the query API's ordering/pagination story:

1. **`ORDER BY` inside the where-fragment is now documented, pinned behavior.** It has
   always worked (the fragment is concatenated into the generated HQL), but nothing
   documented it, so apps sorted result lists in Java. As of 0.1.7 it is covered by
   tests and listed in `BRACE-AGENTS.md` §Database — rely on it.
2. **`db.queryPage(Class, hqlWhere, limit, offset, params...)`** — same where-fragment
   pipeline as `db.query`, plus a result slice applied via Hibernate's
   `setMaxResults`/`setFirstResult` (dialect-correct LIMIT/OFFSET, no string surgery).
   Validates `limit > 0` and `offset >= 0` (`IllegalArgumentException` otherwise).
   Put the `ORDER BY` in the where-fragment string — always order when paginating, or
   page boundaries are unstable. Note this is a **new method**, not an overload of
   `db.query`: an overload would have silently reinterpreted existing calls like
   `db.query(Post.class, "a = ? AND b = ?", 1, 2)` as limit/offset.

**Before (all versions, still works — but fetches every row):**

```java
// full-table fetch, then slice in Java
var all = db.query(Post.class, "published = true");
all.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
var page = all.subList(Math.min(20, all.size()), Math.min(40, all.size()));
long total = all.size();

// …or hand-built LIMIT/OFFSET via native SQL
var rows = db.sqlQuery("SELECT * FROM posts WHERE published = true ORDER BY created_at DESC LIMIT 20 OFFSET 20");
```

**After (0.1.7+):**

```java
// page 2, 20 per page — database does the ordering and slicing
var page  = db.queryPage(Post.class, "published = true ORDER BY createdAt DESC", 20, 20);
long total = db.count(Post.class, "published = true");  // same condition, sans ORDER BY

// and for non-paged ordered lists, ORDER BY in db.query is supported semantics:
var newest = db.query(Post.class, "published = true ORDER BY id DESC");
```

Relatedly, the documented idiom for aggregates is a single projection query — not
fetching rows and loop-summing in Java:

```java
var row = db.hql("SELECT AVG(r.score), COUNT(r) FROM Rating r WHERE r.talkId = ?", id).get(0);
```

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

## New (optional): refreshed `CLAUDE.md` capability index

**Nothing breaks if you skip this** — but your project's `CLAUDE.md` is a snapshot
written at `brace new` time and does not update on upgrade, so it goes stale silently.
0.1.7's generator fixes errors and fills the largest gaps in the emitted file:

- **New capability entries:** the `Http` client (`Http.get(url).fetchJson(Class)`,
  `.bearer(token)`, `.bodyJson(obj)` — previously absent entirely, which sent agents
  to raw `java.net.http`), `Assets.url("/path")` content-hash fingerprinting,
  `Url.to("/users/{id}", 42)`, `Log.debug/info/error` levels (previously only
  `Log.event` was shown), `Redirect.toLocal(path)` for user-derived redirect targets.
- **This release's API additions reflected:** `db.findOr404`/`db.queryOneOr404`,
  `req.jsonForm(Class)`, `Json.obj(...)`, and the typed read-only route methods
  (`app.getRead(...)`/`getReadFull`) as the canonical routing style.
- **Stale facts fixed:** the repo link now points at `github.com/larvalabs/brace`
  (was a dead `matth` URL); the CSRF line now lists PATCH alongside POST/PUT/DELETE.
- **Ops sections merged:** the two overlapping ops sections are now one, with a
  `brace check` row (the documented first move for production health) and the
  previously missing `/ops/logs`, `/ops/cache`, and `/ops/regressions` endpoint rows.

To refresh, re-run the generator and review the diff (it overwrites the file, so
re-apply any project-specific edits — e.g. your filled-in **Deploy** section):

```java
// anywhere you have the app builder, e.g. a one-off main or jshell:
app.generateClaudeMd("myapp", java.nio.file.Path.of("CLAUDE.md"));
```

Tip: `git diff CLAUDE.md` afterwards makes it easy to restore hand-written sections
while keeping the refreshed capability index.

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
