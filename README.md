# Brace

A full-stack Java web framework built for AI agents. Designed for token-efficient development, full production observability for autonomous agents (and humans), and first class runtime performance and scalability.

![Brace Ops Dashboard](docs/brace_ops_screenshot.png)

## Why Brace Exists

Current web frameworks were designed for human developers. They avoid boilerplate through magic: auto-configuration, bean scoping, proxy chains, conditional loading. AI coding agents write better code when frameworks are explicit and predictable, and they work best when guided by strict compile-time checking and unit tests.

Microframeworks solve the complexity problem but create a different one: every project becomes a bespoke assembly of packages, each with their own conventions, config, and error handling. The AI has to hold all of that in context.

Brace is both simple and complete. ~20 core types, ~4,000 lines of framework code, 410 tests, and everything you need to build and operate a production application — HTTP, database, templates, sessions, forms, cache, jobs, mailer, storage, WebSocket, and an ops dashboard — all with consistent conventions. One dependency to learn, not ten.

### AI Token Efficiency

Everything flows through parameters. A controller method's signature tells you exactly what it has access to — no guessing about what's injected, what's ThreadLocal, what's magic. Templates fail the build if parameters are wrong. Wrong types are caught at compile time, not when a user hits the page.

In benchmarks measuring AI token cost to build and extend a Conference Manager API (10 entities, 117 tests), Brace costs 33% less than Spring Boot on feature additions ($5.43 vs $8.16) — and the gap widens as the codebase grows:

| Phase | Brace | Spring | Saving |
|---|---|---|---|
| Greenfield build (6 entities, 35 tests) | $2.24 | $2.38 | 6% |
| + Speaker Availability | $1.01 | $1.59 | 36% |
| + Waitlist with Auto-Promotion | $1.02 | $1.14 | 11% |
| + Ratings & Speaker Stats | $0.75 | $1.18 | 36% |
| + Multi-Day Events & Tracks | $1.29 | $1.96 | 34% |
| + Notifications & Activity Feed | $1.36 | $2.29 | 41% |

The greenfield build is roughly tied — both frameworks are cheap when the codebase is empty. The advantage emerges as features accumulate and the AI has to read and modify existing code. Brace's context scales linearly (read the controller and its dependencies) while Spring's scales super-linearly (trace the DI graph, understand conditional beans, check profiles). Hono (TypeScript) performed comparably to Brace on token cost ($5.79 for feature additions) but trades runtime performance for simplicity. Full benchmark data and methodology: [ai-benchmark](https://github.com/mattonfoot/ai-benchmark).

### Agent Observability

No existing framework exposes a structured diagnostics API designed for AI agents. Brace does.

`GET /ops/status` returns everything an agent needs to diagnose any problem: request stats, slow routes, recent errors with full context (stack trace, request details, queries that ran before the error), custom metrics, JFR profiling (heap, CPU, GC pauses, hot methods, allocations), job statuses, cache hit rates, and per-minute timeseries. The built-in dashboard shows the same data visually.

Ops endpoints use Ed25519 keypair authentication with short-lived tokens — agents authenticate securely without shared secrets. An AI agent can deploy, monitor via `/ops/status`, detect problems, fix code, and redeploy — autonomously.

### Runtime Performance

The same design choices that help AI also eliminate runtime overhead. No DI container means no proxy indirection. Hibernate's StatelessSession skips dirty checking and persistence context management. JTE templates compile to Java classes. Jetty 12 runs on virtual threads.

For a full-stack page render (5 DB queries + template), Brace with PostgreSQL is roughly 2x faster than the equivalent Spring Boot stack. Not because of any single optimization, but because every layer has less overhead: framework dispatch (~33μs vs ~125μs), no ORM lifecycle tax, compiled templates (~180μs vs ~480μs for Thymeleaf).

AI agents: read [AGENTS.md](AGENTS.md) for the complete framework reference.

## Install

Requires JDK 21 or later. JDK 25 LTS is recommended — [JEP 491](https://openjdk.org/jeps/491) removes virtual-thread pinning inside `synchronized` blocks, which materially improves tail latency under load when Hibernate and JDBC drivers are on the hot path.

Download the latest release zip, unzip it, and add `bin/` to your PATH:

```bash
curl -LO https://github.com/larvalabs/brace/releases/latest/download/brace-0.1.0.zip
unzip brace-0.1.0.zip
export PATH="$PWD/brace-0.1.0/bin:$PATH"
brace help
```

No Maven or per-project scripts needed for the dev loop. Maven is only invoked by `brace deps` to populate a project-local `lib/` folder from `pom.xml`.

```bash
brace new myapp        # scaffold a new project
cd myapp
brace deps             # populate ./lib/ from pom.xml (one time, requires Maven)
brace dev              # compile + run + watch for changes
brace test             # run all tests
brace ops keypair      # generate ops auth keys
brace ops dashboard    # authenticate and open /ops/dashboard
```

## Quick Start

Brace wires everything explicitly in `main()`, making the app self-documenting. An agent can read one file and knows every route, middleware, entity, and job. No separate architecture docs to maintain or drift out of sync.


```java
public class App {
    public static void main(String[] args) throws Exception {
        var config = Config.load(Path.of("application.conf"), System.getProperty("brace.mode"));
        var db = new DatabaseFactory(config.get("db.url"), config.get("db.user"), config.get("db.pass"),
            List.of(Post.class, User.class));
        var mail = new Mailer(config.get("smtp.url")).from("noreply@myapp.com");
        var cache = Brace.cache();
        var storage = Storage.s3(config);

        var app = Brace.app()
            .port(config.getInt("port", 8080))
            .database(db)
            .templates("views")
            .sessions(SessionOptions.secure(config.get("session.secret")).maxAgeDays(30))
            .trustedProxies("10.0.0.0/8", "172.16.0.0/12")  // secure IP handling behind load balancer
            .after(SecurityHeaders.defaults())               // security headers on all responses
            .mailer(mail)
            .cache(cache)
            .storage(storage)
            .ops("ops-authorized-keys")
            .staticFiles("/assets", "public");

        var posts = new PostController();
        var auth = new AuthController(mail);

        app.before(Auth::requireLogin);
        app.get("/", cache.wrap("5m", posts::index));
        app.getDb("/posts/{id}", posts::show);
        app.postFull("/posts", posts::create);
        app.group("/auth", g -> {
            g.get("/login", auth::loginForm);
            g.postSession("/login", auth::login);
        });

        app.every("5m", "cleanup", new CleanupJob());
        app.daily("02:00", "digest", new DigestJob(mail));

        app.start();
    }
}
```

## What's Included

- **HTTP** — Jetty 12 with virtual threads, programmatic routing, middleware, route grouping, static file serving
- **Database** — Hibernate 7 StatelessSession, per-request transactions, Flyway migrations, `queryIn()` for batch lookups, `withSession()` for scoped access. PostgreSQL JDBC driver bundled — no extra dependency to add
- **Templates** — JTE compiled type safe templates with explicit parameters, hot-reload in dev
- **Sessions** — AES-256-GCM encrypted cookies, secure by default, stateless
- **Forms** — Record-based form binding with validation annotations
- **CSRF** — Required by default on POST/PUT/DELETE, explicit opt-out with `.csrf(false)` for bearer-token APIs
- **Security** — Trusted proxy configuration (CIDR-based), secure cookie defaults, secret validation, security headers middleware
- **Cache** — In-memory with TTL, tag-based invalidation, route-level page caching via `cache.wrap()`
- **Jobs** — In-memory recurring scheduler + durable database-backed queue with retry
- **Mailer** — SMTP sending with dev-mode email capture using JTE templates
- **Storage** — S3-compatible object storage with built-in AWS Sig V4 signing (works with S3, R2, MinIO)
- **WebSocket** — `app.ws()` with rooms, broadcast, and session access
- **Rate Limiting** — Per-IP and per-key rate limiting middleware with trusted proxy support
- **File Uploads** — `req.file()` and `req.files()` with configurable size limits, built in S3 support
- **htmx** — Bundled htmx 2.0.4, `req.isHtmx()` partial detection, automatic `Vary: HX-Request`
- **Custom Metrics** — Counters, gauges, and timers with lock-free internals and dashboard sparklines
- **Ops** — `/ops/status` diagnostics, `/ops/errors` exception tracking, `/ops/dashboard` HTML dashboard, JFR profiling, Ed25519 token auth
- **CLI** — global `brace` command distributed as a downloadable zip: `brace new` scaffolding, `brace dev`/`run`/`test`/`compile` dev loop (no Maven needed), `brace deps` to populate project `lib/` from pom.xml, `brace ops keypair`/`dashboard` for ops auth
- **Testing** — `Brace.test()` harness for fast in-process integration tests with H2

## Controllers

Plain classes. Dependencies via constructor. Request-scoped data via method parameters.

```java
public class PostController {
    public Result index(Request req, Database db) {
        var posts = db.findAll(Post.class);
        return Result.view("posts/index", "posts", posts);
    }

    public Result show(Request req, Database db) {
        var post = db.find(Post.class, req.intPathParam("id"));
        if (post == null) return Result.notFound();
        return Result.view("posts/show", "post", post);
    }

    public Result create(Request req, Database db, Session session) {
        var form = req.form(PostForm.class);
        if (form.hasErrors()) return Result.view("posts/new", "form", form);
        var post = new Post();
        post.apply(form.value());
        post.authorId = session.getInt("userId");
        db.insert(post);
        return Result.redirect("/posts/" + post.id);
    }
}
```

## Handler Types

```java
app.get("/hello", req -> Result.text("Hello!"));                       // Handler: Request only
app.getDb("/posts", (req, db) -> Result.json(db.findAll(Post.class)));  // DbHandler: Request + Database
app.getSession("/profile", (req, session) -> ...);                     // SessionHandler: Request + Session
app.postFull("/posts", (req, db, session) -> ...);                     // FullHandler: Request + Database + Session

// Typed route methods eliminate cast syntax
app.getDb("/posts", (req, db) -> ...);          // getDb, postDb, putDb, deleteDb
app.getSession("/profile", (req, session) -> ...); // getSession, postSession, putSession, deleteSession
app.getFull("/dashboard", (req, db, session) -> ...); // getFull, postFull, putFull, deleteFull

// CSRF is required by default on POST/PUT/DELETE - explicitly opt out for bearer-token APIs
app.post("/api/public", req -> Result.json(data)).csrf(false);  // no CSRF for bearer-token API
```

## Database

```java
db.find(Post.class, id)                          // find by ID
db.insert(post)                                   // insert
db.update(post)                                   // update
db.delete(post)                                   // delete
db.findAll(Post.class)                            // all rows
db.query(Post.class, "author.id = ?", userId)     // HQL where clause
db.queryOne(Post.class, "slug = ?", slug)         // single result or null
db.queryIn(Post.class, "id", List.of(1, 2, 3))   // batch lookup with IN clause
db.count(Post.class, "published = ?", true)       // count with condition
db.sql("UPDATE posts SET views = views + 1 WHERE id = ?", id) // native SQL

// Constrained helpers for common single-field queries
db.findBy(Post.class, "slug", "hello-world")      // find one by field
db.findAllBy(Post.class, "authorId", 42)          // find all by field
db.countBy(Post.class, "published", true)         // count by field
db.existsBy(Post.class, "email", "user@ex.com")   // check existence
db.deleteBy(Post.class, "authorId", userId)       // delete by field (returns count)
```

For scoped DB access outside the request lifecycle (background tasks, WebSocket handlers):

```java
dbFactory.withSession(db -> {
    db.insert(new AuditLog("user signed up"));
});

var count = dbFactory.withSession(db -> db.count(User.class));
```

## Forms & Validation

```java
public record PostForm(
    @Required String title,
    @Required @MinLength(10) String body,
    @Email String contactEmail
) {
    public void validate(Errors errors) {
        if (title.contains("<script>")) errors.add("title", "no scripts allowed");
    }
}

var form = req.form(PostForm.class);
if (form.hasErrors()) return Result.view("posts/new", "form", form);
```

## Sessions

Sessions are encrypted with AES-256-GCM — you can safely store emails, roles, and permissions.

```java
session.set("userId", user.id);
session.set("email", user.email);
session.set("role", user.role);
session.getInt("userId");
session.get("email");
session.has("userId");
session.clear();
```

Configure session cookie security:

```java
app.sessions(SessionOptions.secure("secret")
    .maxAgeDays(14)
    .sameSiteLax());
```

## Jobs

```java
// Recurring (in-memory)
app.every("5m", "cleanup", db -> db.sql("DELETE FROM sessions WHERE expired < NOW()"));
app.daily("02:00", "digest", db -> sendDigestEmails(db));

// Durable (database-backed, survives restarts)
Jobs.schedule(db, new SendReceipt(orderId), Duration.ofMinutes(5));
Jobs.schedule(db, new SendSurvey(orderId), Duration.ofDays(7),
    JobOptions.maxAttempts(5).backoff(Duration.ofMinutes(10)));
```

## Mailer

```java
mail.to("user@example.com")
    .subject("Welcome!")
    .html(View.render("emails/welcome", "user", user))
    .send();
```

Dev mode captures emails without sending. Access via `mailer.sent()` in tests.

## Storage

```java
var storage = Storage.s3(config);  // reads s3.* keys from Config

String url = storage.put("uploads/photo.jpg", bytes, "image/jpeg");  // upload, returns public URL
storage.delete("uploads/photo.jpg");                                  // delete
storage.url("uploads/photo.jpg");                                     // public URL (no network call)

// Safe file upload with auto-generated UUID-based keys
app.post("/upload", req -> {
    var file = req.file("photo");
    var stored = req.storage().putGenerated("avatars", file);  // returns StoredFile(key, url)
    return Result.json(Map.of("key", stored.key(), "url", stored.url()));
});

// Manual key with safety helpers
app.post("/upload-manual", req -> {
    var file = req.file("photo");
    var key = Storage.safeKey("avatars", file.filename());  // sanitizes extension, adds UUID
    var stored = req.storage().put(key, file);
    return Result.json(Map.of("url", stored.url()));
});
```

## Custom Metrics

Built-in counters, gauges, and timers — no external metrics server needed. Metrics auto-render as sparklines in the ops dashboard and are exposed in `/ops/status` JSON.

```java
// Counter — tracks rate (events per minute)
Stats.counter("talks.created");
Stats.counter("bytes.uploaded", file.size());

// Gauge — samples a value each minute
Stats.gauge("queue.depth", () -> queue.size());

// Timer — tracks count, avg, and max duration
Stats.timer("api.external", durationMs);
```

## Cache

```java
var cache = Brace.cache();

cache.set("user:42", user, "30m");                   // set with TTL
cache.get("user:42", User.class);                    // get or null
cache.getOrSet("stats", "5m", () -> computeStats()); // compute on miss
cache.delete("user:42");                             // remove one
cache.deletePrefix("user:");                         // remove by prefix
cache.clearTag("simulation");                        // remove by tag

// Route-level page caching
app.get("/", cache.wrap("30m", ctrl::index).tags("simulation"));
app.get("/team/{id}", cache.wrap("30m", ctrl::team).tags("simulation"));
cache.clearTag("simulation");  // invalidate all cached pages at once
```

## Static Files

```java
app.staticFiles("/assets", "public");   // serve public/ directory at /assets/*
```

## Route Grouping

```java
app.group("/admin", admin -> {
    admin.get("/users", ctrl::list);
    admin.post("/users", ctrl::create);
    admin.group("/api", api -> {        // nesting supported
        api.get("/stats", ctrl::stats); // registers /admin/api/stats
    });
});
```

## htmx

Dynamic page updates without a JavaScript framework. Brace bundles htmx 2.0.4 and serves it from `/__brace/htmx.min.js`. The default pattern: handlers return a full page, htmx uses `hx-select` to extract the element it needs client-side. For optimization, detect htmx requests and return just the partial.

```java
// In your layout: <script src="/__brace/htmx.min.js"></script>

// Full page by default, partial when htmx requests it
app.get("/posts", (DbHandler) (req, db) -> {
    var posts = db.findAll(Post.class);
    if (req.isHtmx()) return Result.view("posts/_list", "posts", posts);
    return Result.view("posts/index", "posts", posts);
});
```

```html
<!-- In your template -->
<div hx-get="/posts" hx-trigger="every 5s" hx-select="#post-list" hx-swap="outerHTML">
    <div id="post-list">...</div>
</div>
```

Brace automatically sets `Vary: HX-Request` so caches don't mix full pages with partials.

## Testing

```java
static TestApp app = Brace.test()
    .entities(Post.class, User.class)
    .templates("views")
    .start(app -> {
        app.get("/posts", (DbHandler) (req, db) -> Json.of(db.findAll(Post.class)));
    });

@Test void listPosts() {
    app.withDb(db -> { db.insert(newPost("Hello")); });
    var response = app.get("/posts");
    assertEquals(200, response.status());
    assertTrue(response.body().contains("Hello"));
}

@Test void showPost() {
    var response = app.get("/posts/42");
    assertEquals(200, response.status());
}
```

## Configuration

```properties
port=8080
db.url=jdbc:postgresql://localhost:5432/myapp
db.user=myapp
db.pass=${DB_PASS}
session.secret=change-me

%dev.port=9000
%dev.db.url=jdbc:h2:mem:dev
%dev.db.user=
%dev.db.pass=
```

## Tech Stack

| Component | Technology |
|---|---|
| HTTP | Jetty 12 (virtual threads) |
| ORM | Hibernate 7 (StatelessSession) |
| JDBC Drivers | PostgreSQL 42.7.10 (bundled), H2 (test) |
| Templates | JTE |
| Migrations | Flyway |
| JSON | Jackson |
| Passwords | jBCrypt |
| Email | Jakarta Mail |
| Storage | AWS Sig V4 (no SDK) |

**~4,000 lines of framework code. 409 tests.**

## Security

See [docs/SECURITY.md](docs/SECURITY.md) for comprehensive security documentation including:
- Encrypted sessions (AES-256-GCM)
- Trusted proxy configuration
- CSRF protection
- Cookie security options
- Rate limiting
- File upload security
- Ops endpoint hardening
