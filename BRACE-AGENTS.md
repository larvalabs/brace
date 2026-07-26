# Brace Framework Reference

Brace is a full-stack Java 21+ web framework. No DI container, no classpath scanning, no magic. Everything is wired explicitly in `main()`. Read `main()` first — it's the map to every route, service, and dependency in the app.

## Installation

Install the launcher with the bootstrap script, then add `~/.brace/bin` to your PATH:

```bash
curl -fsSL https://github.com/larvalabs/brace/raw/main/install.sh | sh
export PATH="$HOME/.brace/bin:$PATH"
brace help
```

No Maven or per-project scripts needed for the dev loop (Maven is only invoked by `brace deps`
to populate a project-local `lib/` from `pom.xml`). The launcher is independent of the framework
version: each project pins `<brace.version>` in `pom.xml`, and inside a project `brace` resolves
that version (downloading to `~/.brace/toolchains/<version>` on first use) and compiles, runs,
and tests against it — `brace run` matches Maven, the IDE, and CI.

## Upgrading

`brace self-update [version]` updates the launcher only; a project's framework version is
governed by `<brace.version>` in its `pom.xml`. When that version changes, read the migration
guide for each version step (in order, if you skip versions) before recompiling — every guide
lists breaking changes with before/after examples. Guides live at
https://github.com/larvalabs/brace/tree/main/docs/migrations and in the dist zip at
`brace-X.Y.Z/migrations/brace-FROM-to-TO.md`.

After bumping `<brace.version>`, run `brace agents-md` to refresh this file (and `BRACE-OPS.md`).
Both are written once at `brace new` time and do not update themselves, so without the refresh
they silently document the old version's API. (`brace agents-md --stdout` prints instead of
overwriting; requires a 0.1.7+ toolchain.)

## Build & Run

```bash
brace new myapp                                 # create a new project
cd myapp
brace deps                                      # populate lib/ from pom.xml (first time)
brace compile                                   # compile
brace test                                      # run all tests (concise when piped: one line per failure + "N passed, M failed in X.Xs"; --verbose for full JUnit output)
brace test app.HomeControllerTest               # run one test class
brace dev                                       # run with auto-restart on file changes
brace run                                       # run without watching
brace agents-md                                 # refresh this file + BRACE-OPS.md from the pinned framework version (--stdout to print instead)
```

`brace dev` launches the app JVM with `-Dbrace.mode=dev`, `brace run` with
`-Dbrace.mode=prod` — so `%dev.`/`%prod.` config prefixes select per-mode values and
the framework picks prod behavior (e.g. precompiled templates) under `brace run`.
Extra JVM flags for the app go in `BRACE_JAVA_OPTS` (e.g. `BRACE_JAVA_OPTS="-Xmx512m"
brace run`); flags there override the defaults, including the mode.

## App Setup

Everything configured via `Brace.app()` builder in `main()`:

```java
var config = Config.load(Path.of("application.conf"), System.getProperty("brace.mode"));
var db = new DatabaseFactory(config.get("db.url"), config.get("db.user"), config.get("db.pass"),
    List.of(Post.class, User.class));

var app = Brace.app()
    .port(config.getInt("port", 8080))
    .database(db)
    .templates("views")
    .sessions(SessionOptions.secure(config.get("session.secret")).maxAgeDays(30))
    .trustedProxies("10.0.0.0/8", "172.16.0.0/12")
    .mailer(new Mailer(config.get("smtp.url")).from("noreply@app.com"))
    .cache(Brace.cache())
    .storage(Storage.s3(config))
    .ops("ops-authorized-keys")
    .staticFiles("/assets", "public")
    .maxUploadSize("10MB")
    .after(SecurityHeaders.defaults());
```

Builder methods: `port()`, `database()`, `templates()`, `sessions()`, `mailer()`, `cache()`, `storage()`, `ops()`, `opsProfiler()`, `opsStatsInterval()`, `staticFiles()`, `maxUploadSize()`, `trustedProxies()`, `ws()`, `wsMaxQueuedBytes()`, `wsAllowedOrigins()`, `before()`, `after()`, `every()`, `daily()`, `jobRetention()`, `jobLease()`, `jobPollInterval()`, `group()`.

## Routing

Always register routes through the typed route methods — only `app.get(path, req -> ...)`
(single-arg `Handler`) uses the bare verb. Multi-arg lambdas on the bare verbs do not
compile (the raw overloads are ambiguous); the typed names are the canonical form:

```java
app.get("/hello", req -> Result.text("Hi"));                 // Handler: Request only
app.getRead("/posts", (req, db) -> Result.json(db.findAll(Post.class)));  // ReadDbHandler: query-only, no transaction
app.postDb("/posts", (req, db) -> ...);                      // DbHandler: Request + Database (transaction)
app.getSession("/me", (req, session) -> ...);                // SessionHandler: Request + Session
app.postFull("/posts", (req, db, session) -> ...);           // FullHandler: Request + Database + Session

// Typed route methods:
// getRead, getReadFull                               (read-only DB; GET only — a mutating verb with a
//                                                     transaction-skipping handler is a footgun)
// getDb, postDb, putDb, deleteDb                     (DbHandler — DB writes, per-request transaction)
// getSession, postSession, putSession, deleteSession (SessionHandler)
// getFull, postFull, putFull, deleteFull             (FullHandler — DB writes + session)

// CSRF is required by default on POST/PUT/DELETE/PATCH - explicitly opt out for bearer-token APIs
app.post("/api/public", req -> Result.json(data)).csrf(false);
```

Use the `Read` variants for handlers that only query: GET routes are almost always
`getRead` (or `getReadFull` if they need the session). They skip the per-request
transaction entirely, which is both faster and signals intent.

Path parameters use `{name}` syntax: `app.get("/posts/{id}", ...)` then `req.pathParam("id")` or `req.intPathParam("id")`.

Grouping:

```java
app.group("/admin", g -> {
    g.getRead("/users", ctrl::list);   // /admin/users — typed methods work on groups too
    g.postDb("/users", ctrl::create);  // /admin/users
});
```

## Middleware

Before middleware runs before the handler. Return `null` to continue, or a `Result` to short-circuit:

```java
app.before(req -> req.header("X-Maintenance") != null ? Result.error(503, "down") : null);
app.before("/api/*", req -> req.header("Authorization") == null ? Result.unauthorized() : null);
```

**Auth guards use session-aware before-middleware** — never repeat a login check inside
handlers. The one-liner covers the whole subtree:

```java
app.requireSession("/admin/*", "userId", "/login");  // redirect to /login unless session has userId
```

`requireSession` requires `.sessions(secret)` — `start()` throws without it (an empty
per-request session would make the guard redirect forever).

For custom logic, the 2-arg `before` receives the session — the SAME instance the handler
gets, so mutations made in the guard persist via the normal cookie write-back:

```java
app.before("/admin/*", (req, session) ->
    "admin".equals(session.get("role")) ? null : Result.forbidden());
```

Session-aware before-middleware runs after all plain `before(...)` middleware and before
CSRF validation. Handlers behind the guard can assume the session key is present.

Pattern semantics: a trailing `/*` matches the bare prefix too — `/admin/*` covers
`/admin`, `/admin/`, and `/admin/anything` — so a guard cannot be bypassed by requesting
the prefix itself. Only a trailing wildcard is allowed; an interior wildcard
(`/api/*/admin`) throws `IllegalArgumentException` at startup.

After middleware can transform the response:

```java
app.after((req, result) -> result.header("X-Frame-Options", "DENY"));
app.after("/api/*", (req, result) -> result.header("X-Api-Version", "1"));
```

## Request

```java
req.method()                  // "GET", "POST", etc.
req.path()                    // "/posts/42"

// Path parameters (from route pattern like /posts/{id})
req.pathParam("id")           // path param as String
req.intPathParam("id")        // as int
req.longPathParam("id")       // as long
req.pathParams()              // Map<String, String>

// Query parameters (from ?key=value)
req.queryParam("page")        // query param as String or null
req.queryParam("page", "1")   // with default value
req.queryInt("page")          // as int (throws NumberFormatException on bad input)
req.queryInt("page", 1)       // with default — returns the default on missing OR unparseable input
req.queryLong("offset")       // as long (throws NumberFormatException on bad input)
req.queryLong("offset", 0)    // with default — returns the default on missing OR unparseable input
req.hasQueryParam("filter")   // boolean
req.queryParams()             // Map<String, String> (repeated keys: last value wins)
req.queryParams("tag")        // List<String> of ALL values (?tag=a&tag=b), order preserved — multi-selects/checkbox groups

// Form parameters (from POST body application/x-www-form-urlencoded)
req.formParam("title")        // form param as String
req.formInt("count")          // as int
req.hasFormParam("optional")  // boolean
req.formParams("tag")         // List<String> of ALL values of a repeated field, order preserved

// Headers, body, and JSON
req.header("Accept")          // header value or null (header names are case-insensitive)
req.hasHeader("Accept")       // boolean (case-insensitive)
req.body()                    // raw body string
req.bodyAs(MyClass.class)     // JSON body deserialized
req.json(MyClass.class)       // alias for bodyAs
req.requireJson(MyClass.class) // enforces Content-Type: application/json
req.isJson()                  // check if Content-Type is JSON
req.isFormPost()              // check if form POST
req.isMultipart()             // check if multipart/form-data

// Other
req.cookie("name")            // cookie value
req.ip()                      // client IP (respects trusted proxies)
req.isHtmx()                  // true if HX-Request header present
req.form(MyForm.class)        // bind and validate form (see Forms)
req.file("photo")             // UploadedFile
req.files("photos")           // List<UploadedFile>
req.storage()                 // Storage instance
```

**UploadedFile:** `filename()`, `contentType()`, `bytes()`, `size()`, `saveTo(Path)`.

**Body size cap:** `maxUploadSize` (builder, default 10MB) bounds **every** request body, not
just file uploads — a non-multipart body (JSON, form post, raw bytes) over the limit is
rejected with `413 Payload Too Large` before your handler runs.

## Responses

All response factory methods are on the `Result` class:

```java
Result.text("hello")                        // 200 text/plain
Result.html("<h1>hi</h1>")                  // 200 text/html
Result.noContent()                          // 204
Result.notFound()                           // 404
Result.notFoundIfNull(thing)                // throws 404 if null, returns thing otherwise
Result.error(500, "oops")                   // error with status
Result.unauthorized()                       // 401 — or unauthorized("msg")
Result.forbidden()                          // 403 — or forbidden("msg")
Result.badRequest("invalid input")          // 400
Result.created("/posts/42")                 // 201 with Location header
Result.bytes(data, "image/png")             // binary response
Result.download(data, "text/csv", "f.csv")  // Content-Disposition attachment; filename is
                                            // sanitized + RFC 6266 encoded, so a user-supplied
                                            // name is safe to pass
Result.view("posts/index", "posts", posts)      // render JTE template
View.render("emails/welcome", "user", user)     // render to String (for emails)
Result.json(object)                         // 200 JSON — or json(object, 201) with status
Result.json(Json.obj("count", n, "avg", avg)) // one-off shape: ordered, null-tolerant pairs
```

For one-off response shapes use `Json.obj(k1, v1, k2, v2, …)` — never a
LinkedHashMap-and-put block (`Map.of` rejects nulls and scrambles key order). For named
or reused shapes, prefer a 1-line local record: it self-documents the schema and
serializes in declaration order.

**⚠️ JSON and JPA entities:** Never return a JPA entity from `Json.of()` — all public fields are serialized, leaking
`passwordHash`, API keys, or any other sensitive column. Return a record or DTO instead:

```java
public record UserResponse(long id, String email) {}
return Result.json(new UserResponse(user.id, user.email));  // not Result.json(user)
```

Brace logs a warning (once per entity class) when `Json.of()` detects an `@Entity`-annotated object, to help catch this pattern early.

```java
// Redirects
Result.redirect("/posts")                   // 302 redirect
Result.redirectPermanent("/new-url")        // 301 redirect
Redirect.toLocal(req.queryParam("next"))    // 302, local paths only — use for user-derived
                                            // paths: rejects absolute and protocol-relative
                                            // URLs (open redirect). 301: Redirect.permanentLocal

// URL generation from route patterns
Url.to("/users/{id}", 42)                   // "/users/42"

// Headers and cookies
result.header("X-Custom", "value")          // set a response header (single-value)
result.cookie("theme", "dark", 3600, true, true, "Lax")  // name, value, maxAge, httpOnly, secure, sameSite
result.cookie("t", "v", 3600, true, true, "Lax", "/admin")  // ...plus an explicit Path
```

`Set-Cookie` is the one repeatable header: multiple `result.cookie(...)` calls all reach the
wire (and your app cookies are never clobbered by the framework session cookie). Every other
header is single-value — setting it again replaces the prior value.

Note: `Json.of()`, `View.of()`, and `Redirect.to()` still work (called by the `Result.*` methods).

## Database

Thin wrapper over Hibernate StatelessSession. No dirty checking, no lazy loading — all operations are explicit. Transactions are managed per-request automatically.

```java
// Basic CRUD
db.find(Post.class, id)                          // by ID, or null
db.findOr404(Post.class, id)                      // by ID, or throws 404 — the canonical handler lookup
db.insert(post)                                   // INSERT
db.update(post)                                   // UPDATE
db.delete(post)                                   // DELETE

// Queries
db.findAll(Post.class)                            // all rows
db.query(Post.class, "author.id = ?", userId)     // HQL where clause, returns List
db.query(Post.class, "published = true ORDER BY id DESC") // ORDER BY goes inside the where-fragment
db.queryPage(Post.class, "published = true ORDER BY createdAt DESC", 20, 20) // limit 20, offset 20 → page 2
//   total for the pager: db.count(Post.class, "published = true")
db.queryOne(Post.class, "slug = ?", slug)         // single result or null
db.queryOneOr404(Post.class, "slug = ?", slug)    // single result, or throws 404
db.queryIn(Post.class, "id", List.of(1, 2, 3))   // IN clause batch lookup
db.count(Post.class)                              // count all
db.count(Post.class, "published = ?", true)       // count with condition

// Constrained helpers (single-field queries)
db.findBy(Post.class, "slug", "hello")            // find one by field
db.findAllBy(Post.class, "authorId", 42)          // find all by field
db.countBy(Post.class, "published", true)         // count by field
db.existsBy(Post.class, "email", "user@ex.com")   // check existence (boolean)
db.exists(Post.class, "talkId = ? AND userId = ?", talkId, userId) // multi-field existence via where-fragment
db.deleteBy(Post.class, "authorId", userId)       // delete by field (returns count)

// Raw queries
db.hql("SELECT p FROM Post p WHERE ...", args)    // raw HQL, returns List<Object[]>
db.hql("SELECT AVG(r.score), COUNT(r) FROM Rating r WHERE r.talkId = ?", id) // aggregates in one round-trip — don't fetch rows and loop-sum in Java
db.sql("UPDATE posts SET views = views + 1 WHERE id = ?", id) // native SQL execute
db.sqlQuery("SELECT * FROM posts WHERE ...", args) // native SQL query, returns List<Object[]>
db.sqlQueryLong("SELECT count(*) FROM posts")      // native SQL returning Long
db.jdbc(conn -> { /* raw JDBC */ })                // raw Connection access
```

HQL/SQL uses `?` positional params — the framework converts to `?1`, `?2` for Hibernate 7.
The converter leaves alone any `?` inside single-quoted string literals or SQL comments; to
write a literal `?` operator (e.g. Postgres JSONB `?`/`?|`/`?&`), escape it as `??`. For SQL
that needs full control, `db.jdbc(...)` is the raw escape hatch.

For DB access outside request lifecycle (jobs, WebSocket):

```java
dbFactory.withSession(db -> { db.insert(new AuditLog("event")); });
var count = dbFactory.withSession(db -> db.count(User.class));
```

## Entities

JPA entities with public fields. No getters/setters needed.

```java
@Entity
@Table(name = "posts")
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public long id;
    public String title;
    public String body;
    public long authorId;
    @Column(name = "created_at")
    public Instant createdAt;

    public void apply(PostForm form) {
        this.title = form.title();
        this.body = form.body();
    }
}
```

Add a Flyway migration for each schema change: `migrations/V1__create_posts.sql`. Register entities in `main()`: `new DatabaseFactory(url, user, pass, List.of(Post.class, ...))`.

## Forms & Validation

Forms are Java records with validation annotations:

```java
public record PostForm(
    @Required String title,
    @Required @MinLength(10) String body,
    @Email String contactEmail,
    @Optional String notes
) {
    public void validate(Errors errors) {
        if (title != null && title.contains("<script>")) errors.add("title", "no scripts");
    }
}
```

Annotations: `@Required`, `@MinLength(n)`, `@MaxLength(n)`, `@Min(n)`, `@Max(n)`, `@Email`, `@In({"a","b"})`, `@Optional`.

Component types that bind automatically: `String`, `int`/`long`/`double`/`float`/`boolean`
(+ boxed), enums (bad value → "must be one of: …" field error), `LocalDate` (yyyy-MM-dd),
`Instant` (ISO-8601), `BigDecimal`. Unparseable input becomes a field error, never an
exception — no hand-parsing `<input type="date">` into String components.

Usage in a handler (`req.form` for form posts, `req.jsonForm` for JSON bodies):

```java
var form = req.form(PostForm.class);
if (form.hasErrors()) return Result.view("posts/new", "form", form);
var post = new Post();
post.apply(form.value());
db.insert(post);

// JSON API — same pipeline:
var jf = req.jsonForm(TalkForm.class);
if (jf.hasErrors()) return Result.json(Map.of("errors", jf.allErrors()), 422);
```

`Form` methods: `hasErrors()`, `value()`, `errors()`, `allErrors()`, `errors(field)`, `raw(field)`.

**JSON APIs use the same pipeline** via `req.jsonForm(Class)` — never hand-roll per-field
null checks or wrap `bodyAs` in try/catch. It binds the JSON body to the record, runs the
annotations plus `validate(Errors)`, and turns a malformed or non-object body into a
`"_body"` error instead of an exception (a raw `req.bodyAs` parse failure surfaces as a 500).

When create and update share validation rules, put them in the record (annotations +
`validate`) — not duplicated in the two handlers. Cross-entity checks (referenced rows
exist, uniqueness) belong in one static helper both handlers call.

## Templates

JTE compiled templates. Files are `.jte` in the configured templates directory.

```html
@param String title
@param List<Post> posts

@template.layout.main(title = title)
    @for(var post : posts)
        <h2>${post.title}</h2>
        <p>${post.body}</p>
    @endfor
@endtemplate
```

Render from handler: `Result.view("posts/index", "title", "Posts", "posts", posts)`. Template params are type-checked at compile time.

Partial templates use `_` prefix convention: `_list.jte`, `_stats.jte`.

**Dev vs prod compilation.** In dev mode templates compile on first render and
hot-reload on change. In prod mode (`brace.mode=prod`, set by `brace run`) the
framework loads ahead-of-time compiled template classes from `target/jte-classes`
(written by `brace compile`/`brace run`; override the location with
`-Dbrace.templates.precompiled=<dir>`, e.g. for jte-maven-plugin output) — no
compiler in production, no first-render latency. If no precompiled classes match
the configured template directory, prod falls back to compiling all templates once
at startup; a broken template then fails the boot instead of 500ing on first hit.

## Sessions

AES-256-GCM encrypted cookies. Stateless — no server-side storage. Safe to store emails, roles, and permissions.

Sessions carry a **server-enforced absolute expiry** (`_exp`, stamped inside the encrypted
payload on every write): a cookie past its expiry is rejected server-side regardless of the
client's `Max-Age`. The horizon is `SessionOptions.maxAge()` when set, otherwise 14 days.
`_exp` is a reserved, server-managed key — `session.set("_exp", …)` is silently ignored.
(Details: the 0.1.6→0.1.7 migration guide's session-expiry section.)

```java
session.set("userId", user.id);        // store int
session.set("role", "admin");          // store string
session.getInt("userId");              // retrieve int
session.get("role");                   // retrieve string
session.getLong("someId");             // retrieve long
session.has("userId");                 // check existence
session.remove("userId");             // remove key
session.clear();                       // remove all
```

Configure cookie security with `app.sessions(SessionOptions.secure("secret").maxAgeDays(14).sameSiteStrict())` — `secure(secret)` means HttpOnly + Secure + SameSite=Lax. `SessionOptions` methods: `of(secret)`, `secure(secret)`, `httpOnly(bool)`, `secure(bool)`, `sameSiteStrict()`, `sameSiteLax()`, `sameSiteNone()`, `maxAge(Duration)`, `maxAgeDays(int)`, `path(String)`, `domain(String)`.

**The `Secure` attribute is on by default.** With no explicit `.secure(...)`, Brace resolves it per request: on unless the request's `Host` is a loopback address, and always on when a trusted proxy reports `X-Forwarded-Proto: https`. So production gets `Secure` with no configuration and `http://localhost` (dev, `Brace.test()`) keeps working. An app genuinely served over plain HTTP on a real hostname must opt out with `.secure(false)`, which logs a startup warning. Do not add `.secure(false)` to make a local setup work — check the Host first.

Flash messages (display once, on the next request): `session.flash("notice", "Post created")` sets; the message is consumed when the next page renders — any handler type, e.g. a redirect-after-POST landing on a plain `Handler` view — and is available to templates as the `flash` map (`flash.get("notice")`). `session.flash("notice")` reads it programmatically: reading a pending message from a previous request consumes it (read-once); reading one set during the current request peeks without consuming, so it still displays next request. `session.flashData()` returns the consumed entries as a Map.

## CSRF

CSRF protection is **required by default** on POST/PUT/DELETE/PATCH requests when sessions are enabled. Content-Type does **not** affect enforcement — `application/json` requests are validated the same as form submissions.

- **Forms:** include `${csrfField}` inside the `<form>` (an auto-provided hidden input).
- **AJAX/fetch:** send the token in the `X-CSRF-Token` header (from the session or a meta
  tag) with `credentials: 'include'` so cookies are sent.
- **Bearer-token APIs (no cookies):** opt out per route with
  `app.post("/api/data", handler).csrf(false)`.

**Important:** Only disable CSRF for endpoints that do NOT use cookie-based authentication. Cookie-authenticated JSON endpoints still need CSRF protection.

## Cache

TTL with tag-based invalidation:

```java
var cache = Brace.cache();
cache.set("key", value, "30m");                       // set with TTL
cache.set("key", value, "5m", "tag1", "tag2");        // set with tags
cache.get("key", MyClass.class);                      // get or null
cache.getOrSet("stats", "5m", () -> computeStats());  // compute on miss
cache.delete("key");                                   // remove one
cache.deletePrefix("user:");                           // remove by prefix
cache.clearTag("posts");                               // remove all with tag
cache.incr("counter");                                 // atomic increment
cache.decr("counter");                                 // atomic decrement
cache.clear();                                         // remove all
```

Route-level page caching:

```java
app.get("/", cache.wrap("5m", ctrl::index));
app.get("/posts", cache.wrap("30m", ctrl::list).tags("posts").vary("page", "sort"));
cache.clearTag("posts");  // invalidate all cached pages with this tag
```

**Query params are ignored by default** — a cached route serves one entry per path. If the
page's content depends on a param (`?page=`, `?sort=`), declare it with `.vary("page", "sort")`
or the cache will serve the same entry for every value. Undeclared params never key the cache
(so `?utm_source=...` junk can't mint entries). The in-memory backend is capped at 10,000
entries (drop-expired-then-arbitrary past the cap; `CacheBackend.inMemory(maxEntries)` to size).

Cache **values must be non-null** on both backends — `null` is reserved for "missing", so `set(key, null)` throws `IllegalArgumentException`. Page caching varies on `HX-Request`, so an htmx partial and a full-page render of the same path are cached separately (they don't clobber each other).

### Backends: in-process (default) vs shared

The default cache is **per-process** — fast (a hashmap, no serialization), but each server keeps its own copy. On a multi-server deploy that means `delete`/`clearTag` only invalidate the box that handled the write, `incr` counts per-instance, and a cached page can differ between servers. For cross-server consistency, opt into the **shared, Postgres-backed** backend with one line:

```java
app.cache(CacheBackend.postgres(dbFactory));   // shared, durable, cross-server-consistent
// default stays in-process if you never call this
```

It reuses the database Brace already requires (table `brace_cache`, applied by the framework's own migrations — no setup). `clear()` becomes a fleet-wide `TRUNCATE`, `incr` is a single atomic SQL statement, and a page rendered on one server is served by any other.

Choose **per use case**, not per deployment — you can run both (a default in-process `Cache` for hot read-through pages, plus `new Cache(CacheBackend.postgres(dbFactory))` for counters/rate limits/invalidation that must be consistent):

| Use | Backend |
|---|---|
| Single server | in-process (default) |
| Read-through of expensive compute, per-server copies OK | in-process |
| Counters / rate limits / invalidation correctness across servers | shared |
| Cached pages that must match across the fleet | shared |

Constraints on the shared backend (the in-process default has none of these):
- **Values must be Jackson-round-trippable** (POJOs, records, collections, primitives, String). A non-serializable value (a stream, a live handle) throws at `set` time. A `get` with the wrong `Class` fails loudly (values carry a type header).
- **`getOrSet` dogpile is per-server, not global** — a cold key can have one supplier run per server before the first write lands. Accepted; it's a stampede, not a correctness bug.
- `counterCount()`/`tagCount()` report 0 (use `size()`).

`clear()` / `POST /ops/cache/clear` empty the cached data — fleet-wide (one `TRUNCATE`) on the shared backend, this-instance-only on the in-process default. Hit/miss/eviction stats are per-instance either way, and the ops endpoint only reaches the `Cache` registered via `app.cache(...)`. Full fleet semantics: `BRACE-OPS.md` → "Cache clear semantics across a fleet".

## Jobs

Recurring (in-memory, lost on restart):

```java
app.every("5m", "cleanup", (db, ctx) -> db.sql("DELETE FROM expired WHERE ts < NOW()"));
app.daily("02:00", "digest", (db, ctx) -> sendDigest(db));
```

Durable (database-backed, survives restarts):

```java
Jobs.schedule(db, new SendReceipt(orderId), Duration.ofMinutes(5));
Jobs.schedule(db, new SendSurvey(orderId), Duration.ofDays(7),
    JobOptions.maxAttempts(5).backoff(Duration.ofMinutes(10)));
```

`DurableJob` interface: implement `data()` (serialize state) and `run(String data, Database db)`.

`JobOptions`: `maxAttempts(n)`, `backoff(Duration)`, `after(jobId)` (run after another job completes).

Finished (completed/failed) durable jobs are pruned daily after 7 days — `scheduled_jobs` is a
queue, not an archive. Configure with `app.jobRetention(days)`; `0` keeps rows forever. Rows
another job still depends on are kept regardless of age.

Durable jobs run on virtual threads, at most `poolSize / 2` concurrently (they share the
connection pool with web handlers), and the poller claims more work as slots free — a slow job
doesn't block the rest of the queue. Need more parallelism? Raise the `DatabaseFactory` pool size.

`Jobs.schedule(db, job, Duration.ZERO)` wakes the poller as soon as **your** transaction commits,
so a job with no delay starts almost immediately — no polling delay in the common case. The wake is
registered as an after-commit hook precisely because `schedule` runs inside the caller's
transaction; waking any sooner would have the poller look before the row is visible.

Polling continues underneath as the safety net, at `app.jobPollInterval(...)` (default `"5s"`,
interval string or `Duration`, must be positive). It covers the five things a wake can't reach:
jobs with a future `run_at`, retries whose backoff expired, rows freed by the stalled-job sweeper,
work enqueued on a **different** instance, and anything already queued at startup. A missed wake
costs latency, never correctness.

A job holds its claim for at most `app.jobLease(...)` (default 30 minutes). If the instance
running it dies before the job finishes — an ordinary deploy is enough, since JVM exit kills
in-flight jobs — the claim expires and the job is returned to the queue, or failed outright if its
`maxAttempts` are already spent. Set the lease above the longest job you expect to run: a lease
can't tell a dead instance from a slow job, so a job still running when its lease expires may be
picked up again elsewhere. That's the at-least-once contract `DurableJob` already carries — **jobs
should be idempotent**.

Takes an interval string (`"30s"`, `"15m"`, `"2h"` — same format as `every()`) or a `Duration`, so
it can come straight from config:

```java
app.jobLease("2h");                                // literal
app.jobLease(config.get("jobs.lease", "30m"));     // conf file or JOBS_LEASE env var
```

A null/blank string keeps the default rather than disabling, so a missing config key can't silently
strand jobs. To disable recovery deliberately: `jobLease("0s")` or `jobLease((Duration) null)`.

Parallel utility: `Jobs.parallel(items, concurrency, item -> process(item))`.

Job lambdas receive `(Database, JobContext)`. Use `ctx.message("Retrieved " + n + " new listings")`
to attach a short status string shown on the ops dashboard alongside the job's last run.

Fire-and-forget async tasks (non-scheduled, non-durable, virtual thread per task):

```java
Jobs.run(() -> sendNotification(userId));     // exceptions caught + logged
Future<Receipt> f = Jobs.submit(() -> generateReceipt(orderId));
Receipt r = f.get();                          // exceptions propagate via Future
```

Inspect via `Jobs.asyncSubmitted()` / `Jobs.asyncFailed()` (counters).

## Mailer

```java
var mail = new Mailer(config.get("smtp.url")).from("noreply@app.com");

// From request handlers, prefer sendAsync(): returns immediately, sends on a background
// virtual thread. send() does synchronous SMTP (commonly 100ms-2s) on the calling thread,
// holding the request's transaction open the whole time.
mail.to("user@example.com")
    .cc("admin@example.com")                              // optional
    .subject("Welcome!")
    .html(View.render("emails/welcome", "user", user))
    .sendAsync();

// send() blocks until delivered and throws on failure — for jobs/scripts that need the result.
mail.to("user@example.com")
    .cc("admin@example.com")
    .subject("Report")
    .text("Plain text body")
    .send();
```

SMTP timeouts are bounded by default — 10s connect, 30s per read/write — so a wedged relay fails
the send instead of hanging the caller forever. Override with `.connectTimeout(Duration)` and
`.timeout(Duration)` for a slow relay or large attachments.

`sendAsync()` failures are logged and counted in `failCount()` instead of thrown.
Dev mode (no SMTP URL) captures emails without sending — bounded to the last 500, drop-oldest.
Access in tests: `mailer.sent()`, `mailer.last()`, `mailer.sentCount()`, `mailer.clearCaptured()`.
With SMTP configured nothing is captured; `sentCount()` counts successful sends, `failCount()` failures.

## Storage

S3-compatible object storage (works with S3, R2, MinIO):

```java
var storage = Storage.s3(config);  // reads s3.* keys from Config
String url = storage.put("uploads/photo.jpg", bytes, "image/jpeg");  // returns public URL
storage.delete("uploads/photo.jpg");
storage.url("uploads/photo.jpg");                   // public URL (no network call)
storage.keyFromUrl("https://cdn.example.com/...");  // extract key from URL

// Safe file upload: auto-generates a UUID-based key, returns StoredFile(key, url)
var stored = storage.putGenerated("avatars", req.file("avatar"));
stored.key();   // "avatars/a1b2c3d4-….jpg"     stored.url();  // "https://cdn.example.com/avatars/…"
var stored2 = storage.put("custom/path.jpg", file);          // UploadedFile at an explicit key
String key = Storage.safeKey("uploads", "user photo.jpg");   // sanitizes and adds UUID
String ext = Storage.extension("photo.jpg");                 // "jpg" (alphanumeric only)
```

Config keys: `s3.accessKeyId`, `s3.secretKey`, `s3.bucket`, `s3.region`, `s3.endpoint`, `s3.publicUrl`, `s3.timeoutSeconds` (per-request timeout, default 60).

**StoredFile** record: `key()`, `url()`.

## Asset Fingerprinting

Cache-bust static assets by appending a content-hash query parameter — in a JTE template
(or any code that produces URLs): `${Assets.url("/assets/app.css")}` →
`"/assets/app.css?v=a1b2c3d4"`. The hash is the first 8 hex chars of MD5 of the file
contents, cached per `(path, mtime)`. Unknown URLs (no matching `staticFiles` mapping,
missing file, or path traversal) return unchanged. Pair with long `Cache-Control: max-age`
so redeploys with unchanged files don't invalidate browser/CDN caches.

## HTTP Client

Outbound HTTP over `java.net.http.HttpClient`:

```java
var user = Http.get("https://api.example.com/users/42").fetchJson(User.class);  // JSON
String html = Http.get("https://example.com").fetchString();                    // string
byte[] image = Http.get("https://example.com/logo.png").fetchBytes();           // bytes

// Auth + headers + timeout
var resp = Http.get(url).bearer(token).header("X-Trace-Id", traceId)
    .timeout(Duration.ofSeconds(5)).fetch();
if (resp.ok()) { ... }

Http.post(url).bodyJson(Map.of("name", "Alice")).fetch();              // JSON request body
Http.post(url).bodyForm(Map.of("name", "Bob", "age", "30")).fetch();   // form-encoded
Http.put(uploadUrl).bodyBytes(pngBytes, "image/png").fetch();          // raw binary (S3, R2)

// multipart/form-data with text fields and file parts
Http.post(uploadUrl).bearer(token).multipart()
    .field("name", "avatar")
    .field("file", bytes, "image.png")                       // content-type guessed from extension
    .field("blob", bytes, "x.bin", "application/x-custom")
    .fetch();
```

`Response`: `status()`, `body()`, `header(name)`, `ok()`, `as(Class)`.

## WebSocket

```java
app.ws("/chat", ctx -> new ChatHandler(ctx));
```

`WsContext` methods: `send(message)`, `join(room)`, `leave(room)`, `broadcast(room, message)`, `session()`, `close()`.

Use `dbFactory.withSession()` for database access inside WebSocket handlers.

**Slow-consumer backpressure.** `send`/`broadcast` are non-blocking. A connection that stops reading would otherwise make its outgoing frames pile up in Jetty's queue without bound (a per-connection memory leak). Brace bounds each connection's queued-but-unflushed bytes and force-closes a connection that exceeds the cap (`TRY_AGAIN_LATER`); the bound is per connection, so one slow client never blocks healthy members of the same room. Tune with `app.wsMaxQueuedBytes(bytes)` (default 4 MB).

**Origin checking.** Upgrades from a cross-host `Origin` are rejected with 403 — a WebSocket handshake is not subject to the same-origin policy, so without this an attacker page could open a socket that the browser authenticates with the victim's session cookie. A missing `Origin` is allowed (non-browser clients); hosts are compared without scheme or port, so TLS at a proxy is fine. Declare deliberate cross-origin browser clients with `app.wsAllowedOrigins("https://studio.example.com")` (full origin or bare host; `"*"` disables the check).

## Rate Limiting

```java
app.before("/api/*", RateLimiter.perIp(100, "1m"));
app.before("/login", RateLimiter.perKey(req -> req.formParam("email"), 5, "15m"));
```

**Important:** Configure trusted proxies for accurate IP detection behind load balancers (see Security section below). On Postgres a limit is enforced **cluster-wide** (one shared atomic counter), not per instance — see `BRACE-OPS.md` → "Scaling horizontally" and [`docs/scaling.md`](docs/scaling.md).

**DB load is bounded, not per-request (M17).** Shared counting is *batched and best-effort*: each instance buffers increments and flushes them to the DB every `maxRequests / divisor` requests (default divisor 10), so writes scale with request rate / divisor, not 1:1 — and a client already over the limit is rejected from a local negative cache with **no DB call at all** (the abusive traffic you most want to shed costs nothing). The trade-off is fleet accuracy: across `K` instances a burst can overshoot the limit by up to about `maxRequests / divisor × K` before enforcement engages (assume **no** sticky routing). Abuse from one client is always caught immediately regardless — an instance sees its own count with no lag. Two knobs:

- `app.rateLimitBatchDivisor(int)` (default 10) — higher = tighter fleet accuracy + more DB writes; lower = fewer writes + looser. A small limit like `5/15m` already flushes ~every request at the default (near-exact, and cheap because the traffic is low); a large limit like `1000/min` batches ~10×.
- `app.sharedRateLimiting(false)` — eliminate **all** rate-limiter DB traffic; every limiter then counts purely per-instance, so the effective limit becomes roughly `K × maxRequests` across the fleet. Off Postgres, limiters are already per-instance and both knobs are no-ops.

## Security

### Trusted Proxies

Configure which proxies to trust for IP forwarding headers. Without this, `X-Forwarded-For` is ignored to prevent IP spoofing and `req.ip()` always returns the socket's remote address:

```java
app.trustedProxies("10.0.0.0/8", "172.16.0.0/12");  // CIDRs (here: RFC1918 private ranges)
```

Once configured, `req.ip()` extracts the real client IP from `X-Forwarded-For` when the immediate peer is trusted. With multiple `X-Forwarded-For` entries, Brace picks the **rightmost untrusted** address (leftmost entries are client-supplied and forgeable) — see "Trusted Proxies" in `docs/SECURITY.md` for the algorithm and examples.

### Security Headers

`app.after(SecurityHeaders.defaults())` adds `X-Content-Type-Options: nosniff`,
`Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`, and
`Permissions-Policy: interest-cohort=()` to all responses. Customize with the builder:

```java
app.after(SecurityHeaders.builder()
    .contentTypeOptions("nosniff").referrerPolicy("no-referrer").frameOptions("SAMEORIGIN")
    .strictTransportSecurity("max-age=31536000; includeSubDomains")
    .contentSecurityPolicy("default-src 'self'; script-src 'self' 'unsafe-inline'")
    .build());
```

### Session Secret Validation

Session secrets must be at least 32 characters — shorter throws `IllegalArgumentException`
at startup. Recognizable placeholder patterns (`changeme`, `secret`, `password`, `test`,
all-lowercase-letters) log a warning but are allowed. Use 32+ random characters.

## Ops

Enable ops endpoints with `app.ops("ops-authorized-keys")` in `main()`; scaffold config with
`brace init`, keys with `brace ops keypair`. **Operating in production?** Start with
`brace check --env prod --json` — not `brace status`. The full ops reference — auth keys and
scopes, CLI/HTTP endpoint tables, the `/ops/status` shape, runbooks, scaling, storage/retention —
is **`BRACE-OPS.md`** in the project root (written by `brace new`, refreshed together with this
file by `brace agents-md`; also packaged in the framework jar; source: `docs/agent-ops-guide.md`
in the brace repo).

## Custom Metrics

```java
Stats.counter("talks.created");              // increment by 1
Stats.counter("bytes.uploaded", file.size()); // increment by amount
Stats.gauge("queue.depth", () -> queue.size()); // sampled each minute
Stats.timer("api.external", durationMs);     // tracks count, avg, max
```

Metrics appear in `/ops/status` JSON and as sparklines in the dashboard.

## Testing

In-process integration tests with H2:

```java
static TestApp app = Brace.test()
    .entities(Post.class, User.class)
    .templates("views")
    .start(App::routes);   // exact same wiring as production main()

@Test void listPosts() {
    app.withDb(db -> db.insert(newPost("Hello")));
    var res = app.get("/posts");
    assertEquals(200, res.status());
    assertTrue(res.body().contains("Hello"));
}
```

Don't re-register routes by hand in tests (`.start(app -> { app.getDb("/posts", ctrl::index); … })`
works but drifts). Keep route registration in a `public static void routes(Brace app)` method on
your `App` class — called from `main()`, which keeps config and server startup to itself; the
`brace new` scaffold is laid out this way — and reuse it as above.

Create a session for authenticated test requests: `Session.of("userId", 1)`. Every HTTP verb has a session variant — `get(path, session)`, `post(path, params, session)`, `postJson(path, body, session)`, `put(path, params, session)`, `delete(path, session)` — that sends the session as an encrypted cookie.

### CSRF in tests

With `.sessions(...)` enabled, **every mutating route (POST/PUT/DELETE/PATCH) requires a CSRF token by default** — a plain `post(...)` to such a route returns 403 `{"error":"csrf_required"}`, even for JSON bodies. Use the `*WithCsrf` helpers, which mint a token into the session (`Csrf.ensureToken`) and send it with the request:

```java
var session = Session.of("userId", "1");
var res = app.postWithCsrf("/posts", Map.of("title", "Hi"), session);  // 200
app.putWithCsrf("/posts/1", Map.of("title", "New"), session);
app.deleteWithCsrf("/posts/1", session);

// post(...) never auto-injects a token, so missing-token 403s stay testable:
assertEquals(403, app.post("/posts", Map.of("title", "Hi"), session).status());
```

`postWithCsrf`/`putWithCsrf` send the token as the `_csrf` form param; `deleteWithCsrf` sends it in the `X-CSRF-Token` header (DELETE has no form body — Brace accepts either). Routes registered with `.csrf(false)` (bearer-token APIs) need no token.

### Request builder — custom headers, bearer-token APIs

For anything the fixed methods don't cover (auth headers, raw bodies, unusual verbs):

```java
var created = app.request("POST", "/api/items")
    .header("Authorization", "Bearer " + token)     // repeatable
    .body("{\"title\":\"Hi\"}", "application/json") // optional
    .send();
```

The builder also takes `.session(session)` to send an encrypted session cookie.

### JSON assertions

Prefer structural assertions over `body().contains(...)` substring checks:

```java
var res = app.get("/api/posts");
assertEquals("Hello", res.json().get(0).get("title").asText());     // Jackson JsonNode tree
List<Post> posts = res.bodyAs(new TypeReference<List<Post>>() {});  // typed generic lists
Post post = app.get("/api/posts/1").bodyAs(Post.class);             // typed single values
```

`TestApp` methods: `request(method, path)` (builder: `.header(name, value)`, `.session(session)`, `.body(body, contentType)`, `.send()`), `get(path[, session])`, `post(path, formParams[, session])`, `postWithCsrf(path, formParams, session)`, `postJson(path, body[, session])`, `put(path, formParams[, session])`, `putWithCsrf(path, formParams, session)`, `delete(path[, session])`, `deleteWithCsrf(path, session)`, `withDb(consumer)`, `db()`, `resetDatabase()`, `mailer()`.

Each `Brace.test()` builder gets its own in-memory H2 database by default, so test
classes are isolated from each other without any reset. `resetDatabase()` (truncates all
non-Flyway tables) is for isolation *within* a class — call it from `@BeforeEach` when
tests share one TestApp. It is H2-only and throws `UnsupportedOperationException` on
Postgres; use explicit fixtures there. To deliberately share one database across
TestApps, pass an explicit URL: `.database("jdbc:h2:mem:shared;DB_CLOSE_DELAY=-1")`.

`TestResponse` methods: `status()`, `body()`, `json()`, `bodyAs(Class)`, `bodyAs(TypeReference)`, `header(name)`, `headers(name)`, `redirectedTo()`.

## Config

Properties file with mode prefixes and env var substitution:

```properties
port=8080
db.url=jdbc:postgresql://localhost:5432/myapp
db.pass=${DB_PASS}

%dev.port=9000
%dev.db.url=jdbc:h2:mem:dev;DB_CLOSE_DELAY=-1
```

Load: `Config.load(Path.of("application.conf"), System.getProperty("brace.mode"))`.
Mode-prefixed keys override base keys. `brace dev` sets the mode to `dev` and
`brace run` to `prod`; outside the CLI, pass `-Dbrace.mode=...` yourself.

Methods: `get(key)`, `get(key, default)`, `getInt(key, default)`, `getBool(key, default)`.

## Passwords

bcrypt: `String hash = Passwords.hash("secret");` then `boolean ok = Passwords.check("secret", hash);`

## Logging

Structured JSON to stdout:

```java
Log.event("user.signup", Map.of("userId", user.id, "email", user.email));  // named event
Log.debug("cache warm start");                   // debug/info/warn/error each take
Log.info("import finished", Map.of("rows", n));  // (message) or (message, Map data);
Log.error("payment failed", exception);          // error also takes (message, Throwable)
```

Stdout writes are asynchronous: entries go through a bounded queue drained by a single
writer thread (batched writes, no per-request lock contention). Lines may trail the
request by a few ms under load; `/ops/logs` sees entries immediately. If the queue
overflows (sustained > ~8k lines buffered), oldest lines are dropped and a
`log.dropped` WARN reports the count. `Brace.stop()` and JVM exit flush the queue.

Minimum level (default `DEBUG` = everything): set `BRACE_LOG_LEVEL=INFO`,
`-Dbrace.log.level=INFO`, or `Log.level("INFO")`. Entries below the level are skipped
entirely — they reach neither stdout nor `/ops/logs`.

## htmx

Bundled htmx 2.0.10 — add `<script src="/__brace/htmx.min.js"></script>` to your layout.
Default pattern: handler returns full page, htmx uses `hx-select` to extract elements client-side. Optimize by detecting htmx requests:

```java
if (req.isHtmx()) return View.of("posts/_list", "posts", posts);
return View.of("posts/index", "posts", posts);
```

`Vary: HX-Request` is set automatically so caches don't mix full pages with partials.

## Common Patterns

**Adding an endpoint:**
1. Add handler method to controller
2. Register in `main()`: `app.getDb("/path", ctrl::method)` (or `postDb`, `getFull`, etc.)

**Adding an entity:**
1. Create `@Entity` class with public fields
2. Create Flyway migration: `migrations/V{n}__description.sql`
3. Add to `DatabaseFactory` in `main()`: `List.of(..., NewEntity.class)`

**Adding form validation:**
1. Create record with annotations: `record MyForm(@Required String name) {}`
2. In handler: `var form = req.form(MyForm.class); if (!form.valid()) ...`
3. Add `apply(MyForm form)` to entity for field mapping

**Adding htmx dynamic updates:**
1. Add `hx-get`, `hx-target`, `hx-select`, `hx-trigger` to HTML elements
2. Handler returns full page; htmx extracts what it needs via `hx-select`
3. Optimize: check `req.isHtmx()` and return `_partial.jte` directly

**Token-minimizing patterns** — the framework already has a 1-line form of each of these;
use it instead of re-deriving the verbose version:

- **Lookups:** `db.findOr404(Post.class, req.longPathParam("id"))` is the canonical
  handler lookup — never write a find / null-check / `Result.notFound()` preamble.
- **Shared validation:** rules shared by create and update live in the form record
  (annotations + `validate`), bound with `req.jsonForm(MyForm.class)` for JSON bodies;
  cross-entity checks go in one static helper both handlers call (see §Forms & Validation).
- **Response shapes:** a 1-line local record (`record TalkStats(long talkId, double avg) {}`)
  for named/reused shapes, `Json.obj("count", n, "avg", avg)` for one-offs — never a
  LinkedHashMap-and-put block (see §Responses).
- **Existence checks:** `db.existsBy` (single field) or `db.exists` (multi-field
  where-fragment, e.g. `db.exists(Rating.class, "talkId = ? AND userId = ?", t, u)`) —
  never `db.query(...).isEmpty()`.
- **Batch-fetch related entities:** one `db.queryIn` + `Collectors.toMap`, not a
  find-per-item loop (N+1).
  ```java
  var speakerIds = talks.stream().map(t -> t.speakerId).distinct().toList();
  var speakers = db.queryIn(Speaker.class, "id", speakerIds).stream()
      .collect(Collectors.toMap(s -> s.id, s -> s));
  ```
- **Sorting/paging:** ORDER BY belongs inside the query's where-fragment
  (`db.query`/`db.queryPage`), not in-memory sorts — see §Database.
- **Aggregates:** one `db.hql` projection (`SELECT AVG(...), COUNT(...) ...`), not
  fetch-rows-and-loop-sum — see §Database.
