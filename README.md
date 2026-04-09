# Brace

A full-stack Java web framework built for AI agents. Designed for token-efficient development, full production observability for autonomous agents (and humans), and first class runtime performance and scalability.

![Brace Ops Dashboard](docs/brace_ops_screenshot.png)

## Why Brace Exists

Current web frameworks were designed for human developers. They avoid boilerplate through magic: auto-configuration, bean scoping, proxy chains, conditional loading. AI coding agents write better code when frameworks are explicit and predictable, and they work best when guided by strict compile-time checking and unit tests.

Microframeworks solve the complexity problem but create a different one: every project becomes a bespoke assembly of packages, each with their own conventions, config, and error handling. The AI has to hold all of that in context.

Brace is both simple and complete. ~15 core types, ~5,500 lines of framework code, and everything you need to build and operate a production application — HTTP, database, templates, sessions, forms, cache, jobs, mailer, storage, WebSocket, and an ops dashboard — all with consistent conventions. One dependency to learn, not ten.

### AI Token Efficiency

Everything flows through parameters. A controller method's signature tells you exactly what it has access to — no guessing about what's injected, what's ThreadLocal, what's magic. Templates fail the build if parameters are wrong. Wrong types are caught at compile time, not when a user hits the page.

Because everything is wired explicitly in `main()`, the app is self-documenting. An agent reads one file and knows every route, middleware, entity, and job. No separate architecture docs to maintain or drift out of sync.

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

## Quick Start

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
            .sessions(config.get("session.secret"))
            .mailer(mail)
            .cache(cache)
            .storage(storage)
            .ops("ops-authorized-keys")
            .staticFiles("/assets", "public");

        var posts = new PostController();
        var auth = new AuthController(mail);

        app.before(Auth::requireLogin);
        app.get("/", cache.wrap("5m", posts::index));
        app.get("/posts/{id}", (DbHandler) posts::show);
        app.post("/posts", (FullHandler) posts::create);
        app.group("/auth", g -> {
            g.get("/login", auth::loginForm);
            g.post("/login", (SessionHandler) auth::login);
        });

        app.every("5m", "cleanup", new CleanupJob());
        app.daily("02:00", "digest", new DigestJob(mail));

        app.start();
    }
}
```

## What's Included

- **HTTP** — Jetty 12 with virtual threads, programmatic routing, middleware, route grouping, static file serving
- **Database** — Hibernate 7 StatelessSession, per-request transactions, Flyway migrations, `queryIn()` for batch lookups, `withSession()` for scoped access
- **Templates** — JTE compiled type safe templates with explicit parameters, hot-reload in dev
- **Sessions** — HMAC-SHA256 signed cookies, no server-side storage, stateless
- **Forms** — Record-based form binding with validation annotations
- **CSRF** — Automatic protection on POST/PUT/DELETE, skip for JSON APIs
- **Cache** — In-memory with TTL, tag-based invalidation, route-level page caching via `cache.wrap()`
- **Jobs** — In-memory recurring scheduler + durable database-backed queue with retry
- **Mailer** — SMTP sending with dev-mode email capture using JTE templates
- **Storage** — S3-compatible object storage with built-in AWS Sig V4 signing (works with S3, R2, MinIO)
- **WebSocket** — `app.ws()` with rooms, broadcast, and session access
- **Rate Limiting** — Per-IP and per-key rate limiting middleware
- **File Uploads** — `req.file()` and `req.files()` with configurable size limits, built in S3 support
- **htmx** — Bundled htmx 2.0.4, `req.isHtmx()` partial detection, automatic `Vary: HX-Request`
- **Custom Metrics** — Counters, gauges, and timers with lock-free internals and dashboard sparklines
- **Ops** — `/ops/status` diagnostics, `/ops/errors` exception tracking, `/ops/dashboard` HTML dashboard, JFR profiling, Ed25519 token auth
- **CLI** — `brace new` project scaffolding, `brace ops keypair` key generation, `brace ops dashboard` authenticated access
- **Testing** — `Brace.test()` harness for fast in-process integration tests with H2

## Controllers

Plain classes. Dependencies via constructor. Request-scoped data via method parameters.

```java
public class PostController {
    public Result index(Request req, Database db) {
        var posts = db.findAll(Post.class);
        return View.of("posts/index", "posts", posts);
    }

    public Result show(Request req, Database db) {
        var post = db.find(Post.class, req.intParam("id"));
        if (post == null) return Result.notFound();
        return View.of("posts/show", "post", post);
    }

    public Result create(Request req, Database db, Session session) {
        var form = req.form(PostForm.class);
        if (!form.valid()) return View.of("posts/new", "form", form);
        var post = new Post();
        post.apply(form.value());
        post.authorId = session.getInt("userId");
        db.insert(post);
        return Redirect.to("/posts/" + post.id);
    }
}
```

## Handler Types

```java
app.get("/hello", req -> Result.text("Hello!"));                              // Handler: Request only
app.get("/posts", (DbHandler) (req, db) -> Json.of(db.findAll(Post.class)));  // DbHandler: Request + Database
app.get("/profile", (SessionHandler) (req, session) -> ...);                  // SessionHandler: Request + Session
app.post("/posts", (FullHandler) (req, db, session) -> ...);                  // FullHandler: Request + Database + Session
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
if (!form.valid()) return View.of("posts/new", "form", form);
```

## Sessions

```java
session.set("userId", user.id);
session.getInt("userId");
session.has("userId");
session.clear();
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

// Available in handlers via req.storage()
app.post("/upload", (req, db) -> {
    var file = req.file("photo");
    String url = req.storage().put("photos/" + file.name(), file.bytes(), file.contentType());
    return Json.of(Map.of("url", url));
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
    if (req.isHtmx()) return View.of("posts/_list", "posts", posts);
    return View.of("posts/index", "posts", posts);
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
| Templates | JTE |
| Migrations | Flyway |
| JSON | Jackson |
| Passwords | jBCrypt |
| Email | Jakarta Mail |
| Storage | AWS Sig V4 (no SDK) |

**~5,500 lines of framework code. 342 tests.**
