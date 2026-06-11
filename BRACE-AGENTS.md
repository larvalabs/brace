# Brace Framework Reference

Brace is a full-stack Java 21+ web framework. No DI container, no classpath scanning, no magic. Everything is wired explicitly in `main()`. Read `main()` first — it's the map to every route, service, and dependency in the app.

## Installation

Install the launcher with the bootstrap script, then add `~/.brace/bin` to your PATH:

```bash
curl -fsSL https://github.com/larvalabs/brace/raw/main/install.sh | sh
export PATH="$HOME/.brace/bin:$PATH"
brace help
```

No Maven or per-project scripts needed for the dev loop. Maven is only invoked by `brace deps` to populate a project-local `lib/` folder from `pom.xml`.

The `brace` launcher is independent of the framework version. Each project pins its
framework version with `<brace.version>` in `pom.xml`; inside a project, `brace`
resolves that version (downloading it to `~/.brace/toolchains/<version>` on first
use) and compiles, runs, and tests against it — so `brace run` matches Maven, the
IDE, and CI. The launcher version itself rarely matters.

## Upgrading

Update the launcher with `brace self-update` (latest) or `brace self-update <version>`
(a specific release). This only changes the launcher; a project's framework version is
governed by `<brace.version>` in its `pom.xml`.

When the brace version in `pom.xml` changes, check the migration guide for that version
step before recompiling. Each guide lists every breaking change with before/after examples.

- **Online:** https://github.com/larvalabs/brace/tree/main/docs/migrations
- **Offline:** the dist zip ships them at `brace-X.Y.Z/migrations/brace-FROM-to-TO.md`
- **Filename convention:** `brace-FROM-to-TO.md` (e.g. `brace-0.1.0-to-0.1.1.md`)

If you skip versions, read every guide between the old and new version in order.

After bumping `<brace.version>`, run `brace agents-md` to refresh this file. It is
written once at `brace new` time and does not update itself, so without the refresh it
silently documents the old version's API. (`brace agents-md --stdout` prints instead of
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
brace agents-md                                 # refresh this file from the pinned framework version (--stdout to print instead)
```

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

Builder methods: `port()`, `database()`, `templates()`, `sessions()`, `mailer()`, `cache()`, `storage()`, `ops()`, `opsStatsInterval()`, `staticFiles()`, `maxUploadSize()`, `trustedProxies()`, `ws()`, `before()`, `after()`, `every()`, `daily()`, `group()`.

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

// Typed route methods available for all HTTP methods:
// getRead, postRead, putRead, deleteRead             (ReadDbHandler — DB queries, no transaction)
// getDb, postDb, putDb, deleteDb                     (DbHandler — DB writes, per-request transaction)
// getSession, postSession, putSession, deleteSession (SessionHandler)
// getFull, postFull, putFull, deleteFull             (FullHandler — DB writes + session)
// getReadFull, postReadFull, putReadFull, deleteReadFull (ReadFullHandler — read-only DB + session)

// CSRF is required by default on POST/PUT/DELETE/PATCH - explicitly opt out for bearer-token APIs
app.post("/api/public", req -> Result.json(data)).csrf(false);
```

Use the `Read` variants for handlers that only query: GET routes are almost always
`getRead` (or `getReadFull` if they need the session). They skip the per-request
transaction entirely, which is both faster and signals intent.

Path parameters use `{name}` syntax: `app.get("/posts/{id}", ...)` then `req.pathParam("id")` or `req.intPathParam("id")`.

Route configuration methods (called after route registration):
- `.csrf(false)` — disable CSRF protection (only use for bearer-token APIs, NOT cookie-authenticated endpoints)

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
req.queryParams()             // Map<String, String>

// Form parameters (from POST body application/x-www-form-urlencoded)
req.formParam("title")        // form param as String
req.formInt("count")          // as int
req.hasFormParam("optional")  // boolean

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
// Text and HTML
Result.text("hello")                        // 200 text/plain
Result.html("<h1>hi</h1>")                  // 200 text/html

// Status codes
Result.noContent()                          // 204
Result.notFound()                           // 404
Result.notFoundIfNull(thing)                // throws 404 if null, returns thing otherwise
Result.error(500, "oops")                   // error with status
Result.unauthorized()                       // 401 "Unauthorized"
Result.unauthorized("no")                   // 401 with custom message
Result.forbidden()                          // 403 "Forbidden"
Result.forbidden("access denied")           // 403 with message
Result.badRequest("invalid input")          // 400
Result.created("/posts/42")                 // 201 with Location header

// Binary
Result.bytes(data, "image/png")             // binary response
Result.download(data, "text/csv", "f.csv")  // Content-Disposition attachment

// Templates
Result.view("posts/index", "posts", posts)      // render JTE template
View.render("emails/welcome", "user", user)     // render to String (for emails)

// JSON
Result.json(object)                         // 200 JSON
Result.json(object, 201)                    // JSON with status
Result.json(Json.obj("count", n, "avg", avg)) // one-off shape: ordered, null-tolerant pairs
```

For one-off response shapes use `Json.obj(k1, v1, k2, v2, …)` — never a
LinkedHashMap-and-put block (`Map.of` rejects nulls and scrambles key order). For named
or reused shapes, prefer a 1-line local record: it self-documents the schema and
serializes in declaration order.

**⚠️ JSON and JPA entities:** Never return a JPA entity from `Json.of()` — all public fields are serialized, leaking
`passwordHash`, API keys, or any other sensitive column. Return a record or DTO instead:

```java
// ❌ Dangerous: serializes passwordHash
var user = db.find(User.class, id);
return Result.json(user);

// ✅ Safe: only public fields from the record
public record UserResponse(long id, String email) {}
var user = db.find(User.class, id);
return Result.json(new UserResponse(user.id, user.email));
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

Usage in a handler:

```java
var form = req.form(PostForm.class);
if (form.hasErrors()) return Result.view("posts/new", "form", form);
var post = new Post();
post.apply(form.value());
db.insert(post);
```

`Form` methods: `hasErrors()`, `value()`, `errors()`, `allErrors()`, `errors(field)`, `raw(field)`.

**JSON APIs use the same pipeline** via `req.jsonForm(Class)` — never hand-roll per-field
null checks or wrap `bodyAs` in try/catch. It binds the JSON body to the record, runs the
annotations plus `validate(Errors)`, and turns a malformed or non-object body into a
`"_body"` error instead of an exception (a raw `req.bodyAs` parse failure surfaces as a 500):

```java
app.postDb("/api/talks", (req, db) -> {
    var form = req.jsonForm(TalkForm.class);
    if (form.hasErrors()) return Result.json(Map.of("errors", form.allErrors()), 422);
    var talk = new Talk();
    talk.apply(form.value());
    db.insert(talk);
    return Result.json(talk.id, 201);
});
```

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

Configure session cookie security:

```java
app.sessions(SessionOptions.secure("secret")  // HttpOnly + Secure + SameSite=Lax
    .maxAgeDays(14)
    .sameSiteStrict());
```

`SessionOptions` methods: `of(secret)`, `secure(secret)`, `httpOnly(bool)`, `secure(bool)`, `sameSiteStrict()`, `sameSiteLax()`, `sameSiteNone()`, `maxAge(Duration)`, `maxAgeDays(int)`, `path(String)`, `domain(String)`.

Flash messages (available for one subsequent request):

```java
session.flash("notice", "Post created");  // set flash
session.flash("notice");                  // read flash (returns null after first read)
session.flashData();                      // all flash data as Map
```

## CSRF

CSRF protection is **required by default** on POST/PUT/DELETE/PATCH requests when sessions are enabled. Explicitly opt out with `.csrf(false)` for bearer-token APIs. Content-Type does **not** affect CSRF enforcement — `application/json` requests are validated the same as form submissions.

**Form submission:**

```html
<form method="POST" action="/submit">
    ${csrfField}  <!-- auto-provided hidden input -->
    <input name="data" value="...">
    <button>Submit</button>
</form>
```

**AJAX/fetch with CSRF:**

```javascript
fetch('/api/private', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'X-CSRF-Token': tokenFromServer  // get from session or meta tag
    },
    credentials: 'include',  // sends cookies
    body: JSON.stringify({data: 'value'})
});
```

**Opt out for bearer-token APIs:**

```java
// Public API with Authorization header (no cookies)
app.post("/api/data", req -> {
    String token = req.header("Authorization");
    // validate bearer token
    return Json.of(data);
}).csrf(false);
```

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
app.get("/posts", cache.wrap("30m", ctrl::list).tags("posts"));
cache.clearTag("posts");  // invalidate all cached pages with this tag
```

Cache **values must be non-null** on both backends — `null` is reserved for "missing", so `set(key, null)` throws `IllegalArgumentException`. Page caching varies on `HX-Request`, so an htmx partial and a full-page render of the same path/query are cached separately (they don't clobber each other).

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

### What `clear` does (data vs. stats)

`clear()` and `POST /ops/cache/clear` empty the cached **data**:
- **Shared backend:** a single fleet-wide `TRUNCATE` — one call clears every server's view. There is no separate in-memory data tier, so nothing stale is left behind (the near-cache that *would* introduce per-server L1 copies is deferred — see the design doc).
- **In-process default:** clears only the instance that received the call; other servers keep their copies until TTL.

Two things are **not** cleared fleet-wide, because they live in each instance's memory:
- **Hit/miss/eviction stats are per-instance.** A clear resets the counters only on the box that handled it; every server reports its own hit rate on the dashboard by design. So after a fleet-wide data clear, other boxes' stat numbers stay until they next drain — that's expected, not a bug.
- **Only the `Cache` registered via `app.cache(...)`** is touched by the ops endpoint. If you run the two-instance pattern (a separate `new Cache(...)` you hold yourself), clear that one in your own code.

The clear response reports which happened: `{"cleared": true, "scope": "fleet"}` on a shared backend, `"instance"` otherwise. The dashboard shows a `shared`/`in-process` label and a `[clear fleet]` vs `[clear]` button.

## Jobs

Recurring (in-memory, lost on restart):

```java
app.every("5m", "cleanup", db -> db.sql("DELETE FROM expired WHERE ts < NOW()"));
app.daily("02:00", "digest", db -> sendDigest(db));
```

Durable (database-backed, survives restarts):

```java
Jobs.schedule(db, new SendReceipt(orderId), Duration.ofMinutes(5));
Jobs.schedule(db, new SendSurvey(orderId), Duration.ofDays(7),
    JobOptions.maxAttempts(5).backoff(Duration.ofMinutes(10)));
```

`DurableJob` interface: implement `data()` (serialize state) and `run(String data, Database db)`.

`JobOptions`: `maxAttempts(n)`, `backoff(Duration)`, `after(jobId)` (run after another job completes).

Parallel utility: `Jobs.parallel(items, concurrency, item -> process(item))`.

Job lambdas receive `(Database, JobContext)`. Use `ctx.message(...)` to attach a short
status string shown on the ops dashboard alongside the job's last run:

```java
app.every("30s", "fetch-listings", (db, ctx) -> {
    int n = fetchAndStore(db);
    ctx.message("Retrieved " + n + " new listings");
});
```

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

mail.to("user@example.com")
    .subject("Welcome!")
    .html(View.render("emails/welcome", "user", user))
    .send();

mail.to("user@example.com")
    .cc("admin@example.com")
    .subject("Report")
    .text("Plain text body")
    .send();
```

Dev mode captures emails without sending. Access in tests: `mailer.sent()`, `mailer.last()`, `mailer.sentCount()`, `mailer.clearCaptured()`.

## Storage

S3-compatible object storage (works with S3, R2, MinIO):

```java
var storage = Storage.s3(config);  // reads s3.* keys from Config

// Basic operations
String url = storage.put("uploads/photo.jpg", bytes, "image/jpeg");  // returns public URL
storage.delete("uploads/photo.jpg");
storage.url("uploads/photo.jpg");                  // public URL (no network call)
storage.keyFromUrl("https://cdn.example.com/...");  // extract key from URL

// Safe file upload helpers
var file = req.file("avatar");
var stored = storage.putGenerated("avatars", file);  // auto-generates UUID-based key
// returns StoredFile(key, url)
String key = stored.key();   // "avatars/a1b2c3d4-e5f6-7890-abcd-ef1234567890.jpg"
String url = stored.url();   // "https://cdn.example.com/avatars/..."

// Manual safe key generation
String key = Storage.safeKey("uploads", "user photo.jpg");  // sanitizes and adds UUID
String ext = Storage.extension("photo.jpg");  // "jpg" (sanitized, alphanumeric only)

// Upload with UploadedFile
var stored = storage.put("custom/path.jpg", file);  // returns StoredFile(key, url)
```

Config keys: `s3.accessKeyId`, `s3.secretKey`, `s3.bucket`, `s3.region`, `s3.endpoint`, `s3.publicUrl`.

**StoredFile** record: `key()`, `url()`.

## Asset Fingerprinting

Cache-bust static assets by appending a content-hash query parameter:

```java
app.staticFiles("/assets", "public");

// In a JTE template (or any code that produces URLs):
${Assets.url("/assets/app.css")}   // → "/assets/app.css?v=a1b2c3d4"
```

The hash is the first 8 hex chars of MD5 of the file contents, cached per `(path, mtime)`.
Unknown URLs (no matching `staticFiles` mapping, missing file, or path traversal) return
unchanged. Pair with long `Cache-Control: max-age` on static assets so that redeploys with
unchanged files don't invalidate browser/CDN caches.

## HTTP Client

Outbound HTTP over `java.net.http.HttpClient`:

```java
// JSON
var user = Http.get("https://api.example.com/users/42").fetchJson(User.class);

// String / bytes
String html = Http.get("https://example.com").fetchString();
byte[] image = Http.get("https://example.com/logo.png").fetchBytes();

// Auth + headers + timeout
var resp = Http.get(url)
    .bearer(token)
    .header("X-Trace-Id", traceId)
    .timeout(Duration.ofSeconds(5))
    .fetch();
if (resp.ok()) { ... }

// JSON request body
Http.post(url).bodyJson(Map.of("name", "Alice")).fetch();

// Form-encoded
Http.post(url).bodyForm(Map.of("name", "Bob", "age", "30")).fetch();

// Raw binary upload (S3, R2, image APIs)
Http.put(uploadUrl).bodyBytes(pngBytes, "image/png").fetch();

// multipart/form-data with text fields and file parts
Http.post(uploadUrl)
    .bearer(token)
    .multipart()
    .field("name", "avatar")
    .field("file", bytes, "image.png")           // content-type guessed from extension
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

## Rate Limiting

```java
app.before("/api/*", RateLimiter.perIp(100, "1m"));
app.before("/login", RateLimiter.perKey(req -> req.formParam("email"), 5, "15m"));
```

**Important:** Configure trusted proxies for accurate IP detection behind load balancers (see Security section below). On Postgres a limit is enforced **cluster-wide** (one shared atomic counter), not per instance — see Scaling below.

## Scaling horizontally

Brace runs correctly as **N instances behind a load balancer sharing one Postgres** — no sticky sessions. Full contract: **[`docs/scaling.md`](docs/scaling.md)**. Essentials:

- **Requirements:** run on **Postgres**; set the **session secret from config/env identical on every instance** (per-process secrets break cross-box cookies — Brace warns at startup); optionally set a **deploy marker** (`BRACE_DEPLOY` / `app.deploy("<sha>")`) for per-deploy regression baselines.
- **Automatic on Postgres** (no code change): sessions, CSRF, durable jobs, recurring scheduler (once-per-interval cluster-wide), WebSocket broadcast (`LISTEN`/`NOTIFY`), rate limiter (shared counter), ops console login (shared secret), regression detection (shared table), instance-tagged metrics feed.
- **Opt-in:** the shared cache backend (`CacheBackend.postgres(dbFactory)`) — per-process by default even on Postgres, since it trades latency for consistency.
- **Per-instance by design:** `/ops/dashboard`, `/ops/status`, `/ops/logs`, JFR/heap reflect the serving box (`/ops/status` carries `app.instanceId`). Use an external aggregator over the instance-tagged `ops_timeseries` feed + stdout JSON logs for the fleet view.
- **Watch:** rate-limiter DB load on busy servers (Redis recommended for very high volume / hot keys — see [`docs/2026-06-07-rate-limiter-load.md`](docs/2026-06-07-rate-limiter-load.md)); ephemeral counters reset on crash/failover (by design).

## Security

### Trusted Proxies

Configure which proxies to trust for IP forwarding headers. Without this, `X-Forwarded-For` is ignored to prevent IP spoofing:

```java
app.trustedProxies("10.0.0.0/8", "172.16.0.0/12");  // trust RFC1918 private networks
app.trustedProxies("192.168.1.0/24");               // trust specific CIDR
```

Once configured, `req.ip()` will extract the real client IP from `X-Forwarded-For` when the immediate peer is trusted. Without trusted proxies, `req.ip()` always returns the socket's remote address.

With multiple `X-Forwarded-For` entries, Brace picks the **rightmost untrusted** address (leftmost entries are client-supplied and forgeable) — see "Trusted Proxies" in `docs/SECURITY.md` for the algorithm and examples.

### Security Headers

Add security headers to all responses with one line:

```java
app.after(SecurityHeaders.defaults());
```

Default headers:
- `X-Content-Type-Options: nosniff` (prevents MIME sniffing)
- `Referrer-Policy: strict-origin-when-cross-origin`
- `X-Frame-Options: DENY` (prevents clickjacking)
- `Permissions-Policy: interest-cohort=()` (disables FLoC)

Custom configuration:

```java
app.after(SecurityHeaders.builder()
    .contentTypeOptions("nosniff")
    .referrerPolicy("no-referrer")
    .frameOptions("SAMEORIGIN")
    .strictTransportSecurity("max-age=31536000; includeSubDomains")
    .contentSecurityPolicy("default-src 'self'; script-src 'self' 'unsafe-inline'")
    .build());
```

### Session Secret Validation

Session secrets must be at least 32 characters. The framework validates on startup and warns about weak patterns:

```java
app.sessions("short");  // throws IllegalArgumentException
app.sessions("this-is-a-test-secret-changeme-ok");  // warns but allows (weak pattern detected)
app.sessions("a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6");  // good (32+ random chars)
```

## Ops — Production Health Runbook

Brace ships with CLI commands and HTTP endpoints for inspecting a running app. Use these to diagnose production issues without SSH access or log aggregators.

**Setup:** `app.ops("ops-authorized-keys")` in `main()`. Generate keys with `brace ops keypair`. Run `brace init` to scaffold `.brace` config. All commands below use `--env prod` to target production; omit it for local.

### Ops auth — key files and registration

Ops auth uses Ed25519 keypairs. Two files matter:

- **`ops-authorized-keys`** — committed. Public keys allowed to authenticate, one per line as `<base64-pubkey> [scope:read|scope:control] <label>`. Loaded by `app.ops("ops-authorized-keys")`.
- **`ops-private.key`** — gitignored, per-developer. Three lines: a comment, the base64 private key, and the matching public key. Path is recorded in `.brace.local` as `ops.key=...`.

### Token scopes (read-only keys)

Each authorized key has a **scope ceiling** that caps every token it can mint. Two scopes:

- **`read`** — read endpoints only: `status`, `errors`, `logs`, `routes`, `cache` stats.
- **`control`** — everything `read` can do, plus mutating endpoints: `cache/clear`, `errors/{id}/resolve`. `control` implies `read`.

A line with no `scope:` marker defaults to `control` (backward compatible). Mark a key read-only by adding `scope:read`:

```
<base64-pubkey>  scope:read  oncall-agent
```

`POST /ops/auth` caps the minted token at the key's ceiling, so a `scope:read` key **cannot** obtain a control token even if it requests one — escalation is impossible by construction. This is what lets you hand an autonomous agent (e.g. `brace-oncall`) a key that can pull `logs`/`errors`/`status` but never clear the cache or resolve errors. Generate one with `brace ops keypair --read-only --label oncall-agent`. Tokens also carry a `kid` (key fingerprint) so ops access can be attributed to a key.

**Audit log.** Every authenticated ops request is recorded as a structured `ops.access` log event (`kid`, scope, method, path, `granted`) — including authenticated-but-scope-denied attempts (`granted=false`). It rides the normal log stream, so a stolen or misused key is visible after the fact via `brace logs` (filter on `event=ops.access`); no separate store and works with or without a database.

### Ops session secret (multi-instance)

Ops tokens and the browser login cookie are HMAC-signed. The signing secret is resolved at startup in
this order: an explicit **`app.opsSecret("…")`** → derived from the **session secret** (`app.sessions(…)`)
→ a per-process random value (with a startup warning). The first two are shared config, identical on
every instance, so the **browser login works behind a load balancer**: the `login-token` → `exchange`
handshake is stateless (a short-lived HMAC token, no server-side store) and a session cookie minted on
one instance validates on any other. The per-process fallback is single-instance only — set
`opsSecret(…)` (or `sessions(…)`) on any multi-instance deployment, or ops login will fail ~(N−1)/N of
the time behind an LB. Use `opsSecret(…)` for bearer-token APIs that enable ops without sessions.

`brace new` writes both files at scaffold time (the initial `dev` entry corresponds to the local `ops-private.key`). After that, `brace ops keypair` generates a new keypair and writes **both halves itself**: it creates `ops-private.key` (owner-only permissions, gitignored) and adds the public entry to `ops-authorized-keys`. It is safe to re-run: it **refuses to overwrite** an existing `ops-private.key` (delete it first to rotate), and the authorized entry is keyed by label — re-running with the same label *replaces* that line rather than appending an orphan. Different developers get different labels (`identity@host`), so they never clobber each other's entries.

Common workflows:

- **New developer joining an existing project:** run `brace ops keypair` — it writes their `ops-private.key` and adds their labeled entry. Commit the updated `ops-authorized-keys` so servers accept the new operator.
- **Rotating your key:** delete `ops-private.key`, re-run `brace ops keypair` (same label → the old authorized entry is replaced in place), commit and deploy `ops-authorized-keys`.
- **Check whether your local key is already authorized:** there is currently no `brace ops whoami` — `grep -F "$(sed -n '3p' ops-private.key)" ops-authorized-keys` is the manual check.
- **Registering a coworker's existing public key:** there is no CLI for this today; append the line to `ops-authorized-keys` by hand (raw base64 public key, optional `scope:read` marker, then the label).

### CLI commands

| Command | Purpose | Exit code |
|---|---|---|
| `brace status [--env prod]` | Full system snapshot | 0 healthy / 1 errors exist / 2 unreachable |
| `brace check [--env prod]` | Run all health checks, return structured verdict | 0 all pass / 1 issues / 2 unreachable |
| `brace errors [--since 1h] [--full] [--env prod]` | List unresolved error summaries (`--full` for the per-row detail shape) | 0 none / 1 some / 2 unreachable |
| `brace errors <id> [--env prod]` | Full detail for one error (stack trace, request context, headers, queries) | 0 / 1 not found / 2 unreachable |
| `brace logs [-f] [--since 10m] [--level warn] [--limit 50] [--env prod]` | Tail structured log entries (`--limit` caps entries; server default 200) | 0 |
| `brace cache [--env prod]` | Cache size, hit rate, evictions | 0 / 2 unreachable |
| `brace cache clear [--env prod]` | Empty the cache | 0 / 2 unreachable |
| `brace resolve <id> [--env prod]` | Mark an error as resolved | 0 / 1 not found / 2 unreachable |

All commands auto-detect output: human-readable table when stdout is a TTY, JSON when piped. Force with `--json` or `--pretty`.

### HTTP endpoints

The CLI commands call these under the hood. Use them directly when you need raw JSON or aren't in a project directory.

| Endpoint | Returns |
|---|---|
| `GET /ops/status` | System snapshot (app, http, jvm, error summary, jobs, cache, metrics); `?include=timeseries,profiling` for the bulky blocks |
| `GET /ops/errors[?status=open&since=<iso8601>&full=true]` | Tracked error **summaries** (`id, errorType, message, route, occurrenceCount, firstSeen, lastSeen, at`), filterable by status and time window; `?full=true` returns the pre-0.1.7 full-detail rows |
| `GET /ops/errors/{id}` | Full detail for one error: `stackTrace`, `requestDetail`, `queriesBefore`, `requestHeaders` plus the summary fields; 404 for unknown ids |
| `GET /ops/logs[?since=<id>&since_ts=<iso8601>&level=<info\|warn\|error>&limit=200]` | Recent log entries from in-memory ring buffer |
| `GET /ops/cache` | Cache stats: shared, size, hits, misses, hitRate, evictions |
| `GET /ops/routes` | All registered routes |
| `GET /ops/regressions` | New error kinds since startup (the `/ops/errors` shape + an `acknowledged` flag). The on-call wake signal — empty means no new error types this process lifetime. |
| `GET /ops/dashboard` | HTML dashboard (browser) |
| `POST /ops/errors/{id}/resolve` | Mark error resolved (returns the resolved record with `Accept: application/json`) — **control scope** |
| `POST /ops/cache/clear` | Clear cache (returns `{"cleared": true, "scope": "instance"|"fleet"}` with `Accept: application/json`; `fleet` when a shared backend is configured) — **control scope** |
| `POST /ops/regressions/{id}/acknowledge` | Stop flagging a regression (returns `{"acknowledged": true}`) — **control scope** |

Read endpoints (the `GET`s above) require a `read`-scope token; the mutating `POST`s require `control`. See "Token scopes" above.

Authenticate with `POST /ops/auth` (protocol v2: Ed25519 signature over `publicKey + "\n" + timestamp + "\n" + nonce`, fresh random nonce per attempt → Bearer token), then pass `Authorization: Bearer <token>`. The full handshake, including the per-instance replay caveat, is in `docs/agent-ops-guide.md` → "Auth protocol (v2)". The pre-0.1.7 v1 format (signed timestamp only) is deprecated and will be rejected in a future release.

**Regression notifications.** When a new error kind first appears since startup, Brace notifies the registered notifiers once (recurrences don't re-notify). A `LogNotifier` is always attached (emits a `regression` log event); add more with `app.notifyRegressions(new WebhookNotifier(slackUrl), new MailerNotifier(mailer, "ops@example.com"))`. `WebhookNotifier` posts a Slack/Mattermost-shape `{"text": "..."}` payload. `app.regressionsWarmup(seconds)` (default 30) suppresses cold-boot noise. Requires a database (regressions ride the error store).

**Multi-instance.** On Postgres the regression set is shared fleet-wide (table `brace_regressions`): a new error kind notifies **exactly once** across all instances, the `/ops/regressions` list and acknowledge are consistent on every box, and the regression `id` is a stable string (a hash of `type`+`route`+`deploy`) — so an id listed on one instance acknowledges correctly on another. The baseline is anchored to a **deploy marker** set with `app.deploy("<git-sha>")` (or the `BRACE_DEPLOY` env var; defaults to `"default"`): every instance of one deploy shares it, and a new deploy re-evaluates regressions from a clean baseline. Without Postgres the set is per-process (single-server).

### Multi-instance observability

Behind a load balancer, `/ops/status`, `/ops/logs`, and the JFR/heap figures are **per-instance** — each reflects the box that served the request, so consecutive dashboard refreshes (which self-poll every ~5s) may land on different instances and show different numbers. `/ops/status` includes the serving box's `app.instanceId` (`<host>:<port>-<rand>`) so you can tell which one you hit. The durable metric feed `ops_timeseries` is **instance-tagged**: every instance flushes its own rows (column `instance_id`, primary key `(ts, metric, instance_id)`), so an external dashboard (Grafana, etc.) can sum across instances for a fleet total or filter to one box — they are no longer silently summed or limited to a single instance. The authoritative fleet picture is an external metrics/log aggregator over that feed and the stdout JSON logs (which go to every instance's stdout).

### Storage and retention

| Data | Where it lives | Capacity | Eviction | Survives restart? |
|---|---|---|---|---|
| Errors (`/ops/errors`) | `ops_errors` table (Postgres/H2) | 1000 rows (hardcoded in `Brace.start()`) | When count > 1000: deletes resolved rows first (oldest), then oldest unresolved | Yes |
| Logs (`/ops/logs`) | `LogTap` in-memory ring (`ConcurrentLinkedDeque`) | 1000 entries (configurable via `LogTap.setCapacity`) | Oldest entry dropped when full | No |
| Stats (`/ops/status`) | `Stats` in-memory counters / ring buffers | Per-route + timeseries window | Rolling | No |

Errors are **deduplicated on `error_type + route`** for unresolved rows — repeated occurrences increment `occurrence_count` on the existing row rather than inserting a new one. This means a noisy app produces few rows, not thousands.

Retention is **count-based, not time-based** for both errors and logs — nothing is dropped purely because it got old.

### Agent health check (start here)

When asked to check on production, act as on-call, or verify app health, start with this single command:

```bash
brace check --env prod --json
```

If `healthy` is `true`, report healthy and stop. If `false`, read `summary` for an overview, then look at each check with status `"fail"` or `"warn"`. Use the `followUp` command on any failed check to investigate further.

**Do not run `brace status` first.** `brace check` already fetches status data and applies threshold analysis. Only use the individual commands (`brace errors`, `brace logs`, `brace status`) for follow-up investigation.

**Output structure:**

```json
{
  "healthy": false,
  "summary": "2 issues: 3 unresolved errors, 1 failing job",
  "checks": [
    {
      "name": "errors",
      "status": "fail",
      "message": "3 unresolved errors",
      "details": [{"type": "NullPointerException", "route": "GET /posts/{id}", "count": 3, "id": "42"}],
      "followUp": "brace errors --env prod --json"
    }
  ]
}
```

**Checks performed:** reachability, errors, http_5xx, slow_routes, heap, gc_pressure, jobs, cache, recent_logs.

**Thresholds** are configurable in `.brace`:

```
check.slow_route_ms=500
check.heap_warn_percent=70
check.heap_fail_percent=80
check.gc_pause_ms=50
check.cache_hit_rate=0.5
check.log_window_minutes=30
```

### Runbook: detailed status inspection

> **Note:** For most health checks, use `brace check` above. Use `brace status` directly when you need the full raw data for deeper investigation.

Start here when asked to "check on production" or "is the app healthy":

```bash
brace status --env prod --json
```

Read the output in this order:

1. **`app.uptime`** — if very short, the app recently restarted. Check logs for crash/OOM.
2. **`http.statusCodes`** — look at 5xx count. Any 500s mean unhandled exceptions.
3. **`errors.count`** — if > 0, switch to the error investigation runbook below.
4. **`http.slowestRoutes`** — anything over 500ms avg deserves investigation.
5. **`jvm.heap.usedMB` vs `maxMB`** — if usage is above 80% of max, memory pressure is likely. Check `jvm.gc.avgPauseMs` for GC impact.
6. **`jobs.scheduled`** — any job with `lastStatus` != `"ok"` needs attention.
7. **`cache`** — compute hit rate (hits / (hits + misses)). Below 50% means the cache isn't helping; review TTLs and key strategies.

If everything looks normal, report healthy and stop.

### Runbook: error investigation

When users report errors or `brace status` shows a non-zero error count:

```bash
brace errors --env prod --json
```

Each summary includes: `id`, `errorType`, `message`, `route`, `occurrenceCount`, `firstSeen`, `lastSeen`, and `at` — the first stack frame in app code. That is usually enough to locate the bug without pulling the full trace.

**Triage by route and count.** High-count errors on critical routes are the priority. Then:

1. **Start from `at`** — it names the app class/method/line that threw. If you need the full stack trace and request context, fetch one error's detail:
   ```bash
   brace errors <id> --env prod --json
   ```
2. **Check recent logs around the error time:**
   ```bash
   brace logs --env prod --since 30m --level warn --json
   ```
   Look for log entries on the same route or with related context (e.g., the same user ID, request path).
3. **Check if it's new or recurring** — compare `firstSeen` vs `lastSeen`. A new error after a deploy is likely a regression; a long-running error is a latent bug.
4. **Find the code** — use the route from the error (e.g., `GET /posts/{id}`) to find the handler registration in `main()`, then trace into the handler method.
5. **Fix, deploy, then resolve:**
   ```bash
   brace resolve <id> --env prod
   ```

### Runbook: slow endpoint diagnosis

When `brace status` shows a route with high average latency:

1. **Get the route name** from `http.slowestRoutes` (e.g., `GET /search`).
2. **Check logs for that route:**
   ```bash
   brace logs --env prod --since 1h --json | jq '.[] | select(.path == "/search")'
   ```
   Look at `durationMs` and `queries` / `queryMs` fields in the structured log entries.
3. **If `queryMs` dominates `durationMs`** — the database is the bottleneck. Look at the handler code for N+1 queries, missing indexes, or full table scans.
4. **If `durationMs` is high but `queryMs` is low** — the handler is CPU-bound or waiting on an external service. Check `jvm.profiling.hotMethods` in status output (opt-in: `GET /ops/status?include=profiling`).
5. **Check for GC pauses** — `jvm.gc.avgPauseMs` above 50ms can cause latency spikes across all routes.
6. **Check heap pressure** — if heap usage is near max, GC runs more frequently and takes longer.

### Runbook: post-deploy verification

Run immediately after deploying a new version:

```bash
brace status --env prod --json    # confirm app restarted (short uptime)
brace errors --env prod --since 5m --json   # any new errors since deploy?
brace logs --env prod --since 5m --level error --json   # any error-level log entries?
```

If errors appeared that weren't present before the deploy, they are likely regressions. Investigate with the error runbook above.

### Runbook: cache diagnosis

When performance is worse than expected or cache hit rate is low:

```bash
brace cache --env prod --json
```

- **`hitRate` below 0.5** — cache is missing more than it hits. Check that frequently-accessed data is being cached, TTLs aren't too short, and cache keys match the access pattern.
- **`evictions` growing fast** — cache is full and dropping entries. Consider whether the working set is too large for the configured size.
- **Multi-server note.** Check the `"shared"` field. On a shared backend, `size` is fleet-wide (one store) but `hitRate`/`hits`/`misses`/`evictions` are **per-instance** — each box you query reports its own numbers, so hit rate can differ between servers even though they share the same data. On the in-process default (`"shared": false`), everything — data included — is per-instance, so a low hit rate on one box says nothing about the others.
- **Stale data suspected** — clear and let it repopulate:
  ```bash
  brace cache clear --env prod
  ```

### What `/ops/status` returns

```json
{
  "app": { "uptime": "2h 15m", "startedAt": "...", "javaVersion": "21" },
  "http": {
    "statusCodes": { "200": 1523, "404": 12, "500": 3 },
    "slowestRoutes": [{ "route": "GET /search", "count": 45, "avgMs": 234.5 }]
  },
  "jvm": {
    "heap": { "usedMB": 128, "maxMB": 512 },
    "cpu": { "jvmUser": 0.12 },
    "threads": { "active": 42 },
    "gc": { "totalCount": 15, "avgPauseMs": 2.1, "recentPauses": [...] }
  },
  "errors": {
    "count": 3,
    "recent": [{
      "id": 7,
      "errorType": "NullPointerException",
      "message": "Cannot invoke method on null",
      "route": "GET /posts/{id}",
      "occurrenceCount": 3,
      "lastSeen": "..."
    }]
  },
  "jobs": { "scheduled": [{ "name": "cleanup", "lastStatus": "ok", "lastError": null }] },
  "cache": { "shared": false, "entries": 42, "hits": 1200, "misses": 80 },
  "metrics": { "counters": {...}, "gauges": {...}, "timers": {...} }
}
```

Notes on the shape:

- `errors.count` is the unresolved error count (database-backed when one is configured)
  and `errors.recent` the 5 most recent summaries — no stack traces. Drill into one error
  with `GET /ops/errors/{id}` / `brace errors <id>`. `id` is present when a database backs
  the error store.
- Two bulky blocks are **opt-in** via `?include=timeseries,profiling`:
  `timeseries.minutes` (60 per-minute snapshots: `ts`, `requests`, `errors`, `avgMs`) and
  `jvm.profiling` (JFR `hotMethods` + `topAllocations`). `jvm.cpu` and `jvm.gc` appear
  only when the JFR profiler is attached (it always is when ops is enabled).

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
    .start(app -> {
        var ctrl = new PostController();
        app.getDb("/posts", ctrl::index);
        app.postFull("/posts", ctrl::create);
    });

@Test void listPosts() {
    app.withDb(db -> db.insert(newPost("Hello")));
    var res = app.get("/posts");
    assertEquals(200, res.status());
    assertTrue(res.body().contains("Hello"));
}
```

Don't re-register routes by hand in tests. Keep route registration in a
`public static void routes(Brace app)` method on your `App` class (called from
`main()`, which keeps config and server startup to itself — the `brace new`
scaffold is laid out this way), and reuse it:

```java
static TestApp app = Brace.test()
    .entities(Post.class, User.class)
    .templates("views")
    .start(App::routes);   // exact same wiring as production main()
```

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
var res = app.request("GET", "/api/items")
    .header("Authorization", "Bearer " + token)     // repeatable
    .send();

var created = app.request("POST", "/api/items")
    .header("Authorization", "Bearer " + token)
    .body("{\"title\":\"Hi\"}", "application/json")
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

Load: `Config.load(Path.of("application.conf"), "dev")`. Mode-prefixed keys override base keys.

Methods: `get(key)`, `get(key, default)`, `getInt(key, default)`, `getBool(key, default)`.

## Passwords

```java
String hash = Passwords.hash("secret");
boolean ok = Passwords.check("secret", hash);
```

## Logging

Structured JSON to stdout:

```java
Log.event("user.signup", Map.of("userId", user.id, "email", user.email));  // named event

// Leveled logging — each takes (message) or (message, Map<String,Object> data):
Log.debug("cache warm start");
Log.info("import finished", Map.of("rows", n));
Log.warn("retrying smtp connection");
Log.error("payment failed", exception);    // error also takes (message, Throwable)
```

## htmx

Bundled htmx 2.0.4 served from `/__brace/htmx.min.js`. Add to layout:

```html
<script src="/__brace/htmx.min.js"></script>
```

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

- **Lookups:** `db.findOr404` is the canonical handler lookup — never write a
  find / null-check / `Result.notFound()` preamble.
  ```java
  var post = db.findOr404(Post.class, req.longPathParam("id"));
  ```
- **Shared validation:** rules shared by create and update live in the form record
  (annotations + `validate`), bound with `req.jsonForm(MyForm.class)` for JSON bodies;
  cross-entity checks go in one static helper both handlers call (see §Forms & Validation).
- **Response shapes:** a 1-line local record for named/reused shapes, `Json.obj(k, v, …)`
  for one-offs — never a LinkedHashMap-and-put block (see §Responses).
  ```java
  record TalkStats(long talkId, double averageRating) {}      // named/reused shape
  return Result.json(Json.obj("count", n, "avg", avg));       // one-off shape
  ```
- **Existence checks:** `db.existsBy` (single field) or `db.exists` (multi-field
  where-fragment) — never `db.query(...).isEmpty()`.
  ```java
  if (db.exists(Rating.class, "talkId = ? AND attendeeId = ?", talkId, attendeeId)) ...
  ```
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
