# Brace Framework Reference

Brace is a full-stack Java 21+ web framework. No DI container, no classpath scanning, no magic. Everything is wired explicitly in `main()`. Read `main()` first — it's the map to every route, service, and dependency in the app.

## Installation

Download the latest release zip, unzip it, and add `bin/` to your PATH:

```bash
curl -LO https://github.com/larvalabs/brace/releases/latest/download/brace-0.1.1.zip
unzip brace-0.1.1.zip
export PATH="$PWD/brace-0.1.1/bin:$PATH"
brace help
```

No Maven or per-project scripts needed for the dev loop. Maven is only invoked by `brace deps` to populate a project-local `lib/` folder from `pom.xml`.

## Upgrading

When the brace version in `pom.xml` changes, check the migration guide for that version
step before recompiling. Each guide lists every breaking change with before/after examples.

- **Online:** https://github.com/larvalabs/brace/tree/main/docs/migrations
- **Offline:** the dist zip ships them at `brace-X.Y.Z/migrations/brace-FROM-to-TO.md`
- **Filename convention:** `brace-FROM-to-TO.md` (e.g. `brace-0.1.0-to-0.1.1.md`)

If you skip versions, read every guide between the old and new version in order.

## Build & Run

```bash
brace new myapp                                 # create a new project
cd myapp
brace deps                                      # populate lib/ from pom.xml (first time)
brace compile                                   # compile
brace test                                      # run all tests
brace test app.HomeControllerTest               # run one test class
brace dev                                       # run with auto-restart on file changes
brace run                                       # run without watching
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

Four handler types with typed route methods (no casts needed):

```java
app.get("/hello", req -> Result.text("Hi"));                 // Handler: Request only
app.getDb("/posts", (req, db) -> Result.json(db.findAll(Post.class)));  // DbHandler: Request + Database
app.getSession("/me", (req, session) -> ...);                // SessionHandler: Request + Session
app.postFull("/posts", (req, db, session) -> ...);           // FullHandler: Request + Database + Session

// Typed route methods available for all HTTP methods:
// getDb, postDb, putDb, deleteDb
// getSession, postSession, putSession, deleteSession
// getFull, postFull, putFull, deleteFull

// CSRF is required by default on POST/PUT/DELETE - explicitly opt out for bearer-token APIs
app.post("/api/public", req -> Result.json(data)).csrf(false);
```

Read-only variants skip the transaction commit: `ReadDbHandler`, `ReadFullHandler`.

Path parameters use `{name}` syntax: `app.get("/posts/{id}", ...)` then `req.pathParam("id")` or `req.intPathParam("id")`.

Route configuration methods (called after route registration):
- `.csrf(false)` — disable CSRF protection (only use for bearer-token APIs, NOT cookie-authenticated endpoints)

Grouping:

```java
app.group("/admin", g -> {
    g.get("/users", ctrl::list);       // /admin/users
    g.post("/users", ctrl::create);    // /admin/users
});
```

## Middleware

Before middleware runs before the handler. Return `null` to continue, or a `Result` to short-circuit:

```java
app.before(req -> req.path().startsWith("/admin") && !isAdmin(req) ? Result.unauthorized("no") : null);
app.before("/admin/*", req -> isAdmin(req) ? null : Redirect.to("/login"));
```

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
req.queryInt("page")          // as int
req.queryInt("page", 1)       // with default
req.queryLong("offset")       // as long
req.queryLong("offset", 0)    // with default
req.hasQueryParam("filter")   // boolean
req.queryParams()             // Map<String, String>

// Form parameters (from POST body application/x-www-form-urlencoded)
req.formParam("title")        // form param as String
req.formInt("count")          // as int
req.hasFormParam("optional")  // boolean

// Headers, body, and JSON
req.header("Accept")          // header value or null
req.hasHeader("Accept")       // boolean
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

// Redirects
Result.redirect("/posts")                   // 302 redirect
Result.redirectPermanent("/new-url")        // 301 redirect

// Headers
result.header("X-Custom", "value")          // add response header
```

Note: `Json.of()`, `View.of()`, and `Redirect.to()` still work (called by the `Result.*` methods).

## Database

Thin wrapper over Hibernate StatelessSession. No dirty checking, no lazy loading — all operations are explicit. Transactions are managed per-request automatically.

```java
// Basic CRUD
db.find(Post.class, id)                          // by ID, or null
db.insert(post)                                   // INSERT
db.update(post)                                   // UPDATE
db.delete(post)                                   // DELETE

// Queries
db.findAll(Post.class)                            // all rows
db.query(Post.class, "author.id = ?", userId)     // HQL where clause, returns List
db.queryOne(Post.class, "slug = ?", slug)         // single result or null
db.queryIn(Post.class, "id", List.of(1, 2, 3))   // IN clause batch lookup
db.count(Post.class)                              // count all
db.count(Post.class, "published = ?", true)       // count with condition

// Constrained helpers (single-field queries)
db.findBy(Post.class, "slug", "hello")            // find one by field
db.findAllBy(Post.class, "authorId", 42)          // find all by field
db.countBy(Post.class, "published", true)         // count by field
db.existsBy(Post.class, "email", "user@ex.com")   // check existence (boolean)
db.deleteBy(Post.class, "authorId", userId)       // delete by field (returns count)

// Raw queries
db.hql("SELECT p FROM Post p WHERE ...", args)    // raw HQL, returns List<Object[]>
db.sql("UPDATE posts SET views = views + 1 WHERE id = ?", id) // native SQL execute
db.sqlQuery("SELECT * FROM posts WHERE ...", args) // native SQL query, returns List<Object[]>
db.sqlQueryLong("SELECT count(*) FROM posts")      // native SQL returning Long
db.jdbc(conn -> { /* raw JDBC */ })                // raw Connection access
```

HQL uses `?` positional params — the framework converts to `?1`, `?2` for Hibernate 7.

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

Usage in a handler:

```java
var form = req.form(PostForm.class);
if (form.hasErrors()) return Result.view("posts/new", "form", form);
var post = new Post();
post.apply(form.value());
db.insert(post);
```

`Form` methods: `hasErrors()`, `value()`, `errors()`, `allErrors()`, `errors(field)`, `raw(field)`.

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

CSRF protection is **required by default** on POST/PUT/DELETE requests when sessions are enabled. Explicitly opt out with `.csrf(false)` for bearer-token APIs.

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

In-memory with TTL and tag-based invalidation:

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
app.before("/login", RateLimiter.perKey(req -> req.param("email"), 5, "15m"));
```

**Important:** Configure trusted proxies for accurate IP detection behind load balancers (see Security section below).

## Security

### Trusted Proxies

Configure which proxies to trust for IP forwarding headers. Without this, `X-Forwarded-For` is ignored to prevent IP spoofing:

```java
app.trustedProxies("10.0.0.0/8", "172.16.0.0/12");  // trust RFC1918 private networks
app.trustedProxies("192.168.1.0/24");               // trust specific CIDR
```

Once configured, `req.ip()` will extract the real client IP from `X-Forwarded-For` when the immediate peer is trusted. Without trusted proxies, `req.ip()` always returns the socket's remote address.

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

- **`ops-authorized-keys`** — committed. Public keys allowed to authenticate, one per line as `<base64-pubkey> <label>`. Loaded by `app.ops("ops-authorized-keys")`.
- **`ops-private.key`** — gitignored, per-developer. Three lines: a comment, the base64 private key, and the matching public key. Path is recorded in `.brace.local` as `ops.key=...`.

`brace new` writes both files at scaffold time (the initial `dev` entry corresponds to the local `ops-private.key`). After that, `brace ops keypair` generates a *new* keypair, prints the private key **once to stdout** (it does **not** write `ops-private.key`), and appends the public half to `ops-authorized-keys`.

Common workflows:

- **New developer joining an existing project:** run `brace ops keypair`, copy the printed private key into a new `ops-private.key` (format: comment line, private key, public key), commit the appended `ops-authorized-keys` line so peers accept the new operator.
- **Check whether your local key is already authorized:** compare the public line in `ops-private.key` to entries in `ops-authorized-keys`. There is currently no `brace ops whoami` — `grep -F "$(sed -n '3p' ops-private.key)" ops-authorized-keys` is the manual check.
- **Registering a coworker's existing public key:** there is no CLI for this today; append the line to `ops-authorized-keys` by hand.

Do not run `brace ops keypair` to "verify" or "re-add" an existing key — it always generates a fresh pair and silently appends another authorized entry.

### CLI commands

| Command | Purpose | Exit code |
|---|---|---|
| `brace status [--env prod]` | Full system snapshot | 0 healthy / 1 errors exist / 2 unreachable |
| `brace check [--env prod]` | Run all health checks, return structured verdict | 0 all pass / 1 issues / 2 unreachable |
| `brace errors [--since 1h] [--env prod]` | List unresolved errors | 0 none / 1 some / 2 unreachable |
| `brace logs [-f] [--since 10m] [--level warn] [--env prod]` | Tail structured log entries | 0 |
| `brace cache [--env prod]` | Cache size, hit rate, evictions | 0 / 2 unreachable |
| `brace cache clear [--env prod]` | Empty the cache | 0 / 2 unreachable |
| `brace resolve <id> [--env prod]` | Mark an error as resolved | 0 / 1 not found / 2 unreachable |

All commands auto-detect output: human-readable table when stdout is a TTY, JSON when piped. Force with `--json` or `--pretty`.

### HTTP endpoints

The CLI commands call these under the hood. Use them directly when you need raw JSON or aren't in a project directory.

| Endpoint | Returns |
|---|---|
| `GET /ops/status` | Full system snapshot (app, http, jvm, errors, jobs, cache, metrics, timeseries) |
| `GET /ops/errors[?status=open&since=<iso8601>]` | Tracked errors, filterable by status and time window |
| `GET /ops/logs[?since=<id>&since_ts=<iso8601>&level=<info\|warn\|error>&limit=200]` | Recent log entries from in-memory ring buffer |
| `GET /ops/cache` | Cache stats: size, hits, misses, hitRate, evictions |
| `GET /ops/routes` | All registered routes |
| `GET /ops/dashboard` | HTML dashboard (browser) |
| `POST /ops/errors/{id}/resolve` | Mark error resolved (returns the resolved record with `Accept: application/json`) |
| `POST /ops/cache/clear` | Clear cache (returns `{"cleared": true}` with `Accept: application/json`) |

Authenticate with `POST /ops/auth` (signed timestamp → Bearer token), then pass `Authorization: Bearer <token>`.

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

Each error includes: `id`, `errorType`, `message`, `route`, `stackTrace`, `occurrenceCount`, `firstSeen`, `lastSeen`.

**Triage by route and count.** High-count errors on critical routes are the priority. Then:

1. **Read the stack trace** — identify the exception type and the line in your code where it originates.
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
4. **If `durationMs` is high but `queryMs` is low** — the handler is CPU-bound or waiting on an external service. Check `jvm.profiling.hotMethods` in status output.
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
    "gc": { "totalCount": 15, "avgPauseMs": 2.1, "recentPauses": [...] },
    "profiling": { "hotMethods": [...], "topAllocations": [...] }
  },
  "errors": {
    "recent": [{
      "type": "NullPointerException",
      "message": "Cannot invoke method on null",
      "route": "GET /posts/{id}",
      "count": 3,
      "stackTrace": "...",
      "requestDetail": "...",
      "queriesBefore": "..."
    }]
  },
  "jobs": { "scheduled": [{ "name": "cleanup", "lastStatus": "ok", "lastError": null }] },
  "cache": { "entries": 42, "hits": 1200, "misses": 80 },
  "metrics": { "counters": {...}, "gauges": {...}, "timers": {...} },
  "timeseries": { "minutes": [{ "ts": "...", "requests": 45, "errors": 0, "avgMs": 12.3 }] }
}
```

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

`TestApp` methods: `get(path)`, `post(path, formParams)`, `post(path, formParams, session)`, `postJson(path, body)`, `put(path, formParams)`, `delete(path)`, `withDb(consumer)`, `db()`, `resetDatabase()`, `mailer()`.

`TestResponse` methods: `status()`, `body()`, `header(name)`, `redirectedTo()`, `bodyAs(Class)`.

Create a session for authenticated test requests: `Session.of("userId", 1)`.

## Config

Properties file with mode prefixes and env var substitution:

```properties
port=8080
db.url=jdbc:postgresql://localhost:5432/myapp
db.pass=${DB_PASS}

%dev.port=9000
%dev.db.url=jdbc:h2:mem:dev
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
Log.event("user.signup", Map.of("userId", user.id, "email", user.email));
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
