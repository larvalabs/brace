# Brace Framework — Design Specification

## Vision

A full-stack Java web framework designed for the AI era. Plain Java (no DI container, no bytecode enhancement, no magic), batteries included, with built-in AI observability and deployment integration.

## Differentiators

1. **Plain Java, no magic** — no DI, no annotations that do hidden things, no bytecode enhancement, no classpath scanning. Controllers are plain classes, dependencies wired explicitly in `main()`.
2. **Batteries included** — ORM, templates, sessions, forms, jobs (in-memory + durable), mailer, migrations, CSRF — all integrated out of the box.
3. **AI-optimized API design** — explicit parameter passing, compile-time errors over runtime errors, small API surface (~15 core types), auto-generated CLAUDE.md per project.
4. **AI-observable** — structured JSON logging, `/ops/status` diagnostics endpoint, built-in dashboard, deploy hooks.
5. **AI-controllable** — ops control API + Dokploy MCP integration for autonomous deploy/monitor/fix loops.
6. **Fast** — H2 embedded, Hibernate 7 StatelessSession, JTE compiled templates, Jetty 12, virtual threads. Estimated ~3-4x faster than equivalent Spring Boot stack.

## Technology Stack

| Component | Technology | Why |
|---|---|---|
| HTTP server | Jetty 12 | Virtual thread support, WebSocket, HTTP/2, ~10-15% slower than Netty but vastly simpler integration |
| Persistence | Hibernate 7 StatelessSession | No dirty checking, no persistence context, no proxies. ~2-5x less overhead than standard Session |
| Database | H2 embedded (default), PostgreSQL (optional) | In-process H2 eliminates network latency (~13-17x faster than networked DB). Docker volume for persistence. PostgreSQL supported for zero-downtime deploys |
| Templates | JTE | Compiled to Java classes, typesafe, ~2.7x faster than Thymeleaf. Hot reload in dev mode |
| Migrations | Flyway | Standard SQL migration files, runs on startup |
| JSON | Jackson | Industry standard |
| Passwords | jBCrypt | bcrypt hashing |
| Email | Jakarta Mail | Already a transitive dependency |
| Logging | SLF4J + Logback | Structured JSON output |

**Total dependency footprint: ~18MB** (vs ~40-50MB for Spring Boot equivalent).

**Estimated framework code size: ~3,000-5,000 lines of Java.**

## Architecture

### Entry Point

Every Brace app is a plain `main()` method:

```java
public class App {
    public static void main(String[] args) {
        var db = Brace.database(Config.get("db.url"));
        var mail = Brace.mailer(Config.get("smtp.url"));

        var app = Brace.app()
            .port(Config.getInt("port", 8080))
            .templates("views")
            .sessions(Config.get("session.secret"))
            .database(db)
            .mailer(mail);

        // Controllers — plain classes, instantiated once
        // Service dependencies (mailer, etc.) passed via constructor
        // Request-scoped dependencies (Database, Session) passed via method params
        var posts = new PostController();
        var auth = new AuthController(mail);

        // Middleware
        app.before(Auth::requireLogin);
        app.before("/admin/*", Auth::requireAdmin);

        // Routes — explicit, programmatic
        app.get("/", posts::index);
        app.get("/posts/{id}", posts::show);
        app.post("/posts", posts::create);
        app.get("/login", auth::loginForm);
        app.post("/login", auth::login);

        // Route grouping
        app.group("/admin", admin -> {
            admin.before(Auth::requireAdmin);
            admin.get("/dashboard", adminCtrl::dashboard);
        });

        // Static files
        app.staticFiles("/assets", "public");

        // Jobs — in-memory recurring
        app.every("5m", "cleanup-sessions", new CleanupExpiredSessions(db));
        app.daily("02:00", "send-digest", new SendDigestEmail(db, mail));

        // WebSocket
        app.ws("/chat", ChatSocket::new);

        app.start();
    }
}
```

No classpath scanning. No annotation processing. No DI container. AI reads `main()` and understands the entire application.

### Request Lifecycle

```
HTTP request arrives (Jetty)
  → Route matched
  → Before middlewares run (in order)
  → Hibernate StatelessSession opened (only if handler takes Database param)
  → Controller method called with (Request, Database, Session) as needed
  → Controller returns Result (View, Json, Redirect, etc.)
  → StatelessSession committed (or rolled back on exception)
  → After middlewares run
  → Result serialized to HTTP response
  → Structured log entry written
```

Controller method signatures are inspected once at startup to build invokers. At request time, no reflection — just a pre-built lambda call.

If a method doesn't take a `Database` parameter, no database session is opened. Health check endpoints and static pages have zero database overhead.

## Controllers

Plain classes, instantiated once in `main()`, reused across all requests (effectively singletons). No mutable state — all request-scoped data comes via parameters.

Note: the `Database` object passed to `main()` via `Brace.database()` is a factory/configuration object. The `Database` injected into controller method parameters is a request-scoped StatelessSession created from that factory. Controllers do not store the database factory as a field — the framework provides a fresh per-request session via method parameters. Long-lived service dependencies like `Mailer` are passed via the constructor.

```java
public class PostController {

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
        post.createdAt = Instant.now();
        db.insert(post);
        return Redirect.to("/posts/" + post.id);
    }
}
```

### Supported method parameter types

| Type | What's provided | DB session opened? |
|---|---|---|
| `Request` | HTTP request (params, headers, body, cookies) | No |
| `Database` | Hibernate StatelessSession, scoped to request | Yes |
| `Session` | User session (cookie-based, signed) | No |
| No parameters | Fine — `public Result healthCheck()` | No |

### Request API

```java
req.param("name")              // query or path param as String
req.intParam("id")             // parsed to int, 400 if invalid
req.params("tags")             // multi-value: List<String>
req.header("Accept")           // HTTP header
req.cookie("token")            // cookie value
req.body()                     // raw body as String
req.bodyAs(MyDto.class)        // JSON body → Java object
req.form(PostForm.class)       // form data → validated form object
req.method()                   // GET, POST, etc.
req.path()                     // /posts/123
req.ip()                       // client IP
```

### Result types

```java
View.of("template", "key", value, ...)  // render JTE template
Json.of(object)                          // JSON response (200)
Json.of(object, 201)                     // JSON with status code
Redirect.to("/path")                     // 302 redirect
Redirect.permanent("/path")              // 301 redirect
Result.text("hello")                     // plain text
Result.notFound()                        // 404
Result.notFound("posts/404")             // 404 with custom template
Result.error(500, "message")             // error
Result.noContent()                       // 204
Result.file(path, "download.pdf")        // file download
Result.stream(inputStream, "video/mp4")  // streaming response
```

### Routing

```java
app.get("/path", handler);
app.post("/path", handler);
app.get("/path/{id}", handler);           // path parameters

app.group("/admin", admin -> {            // prefix grouping
    admin.before(Auth::requireAdmin);     // group-scoped middleware
    admin.get("/dashboard", handler);
});

app.staticFiles("/assets", "public");     // static file serving
```

Route table printed at startup and available via `GET /ops/routes`.

### Middleware

```java
// Before — can short-circuit by returning a Result
app.before(req -> {
    if (!req.hasHeader("X-Api-Key")) return Result.unauthorized("Missing API key");
    return null; // continue
});

// After — can modify the response
app.after((req, result) -> {
    result.header("X-Frame-Options", "DENY");
    return result;
});
```

## Database & Persistence

### Models

Plain JPA entities with public fields:

```java
@Entity
public class Post {
    @Id @GeneratedValue
    public Long id;

    public String title;
    public String body;
    public Instant createdAt;

    @ManyToOne
    public User author;

    public void apply(PostForm form) {
        this.title = form.title();
        this.body = form.body();
    }
}
```

The `apply()` method is a convention for mapping form data to entity fields. Centralizes the mapping in one place on the entity.

### Database API

Thin wrapper over Hibernate StatelessSession:

```java
// CRUD
Post post = db.find(Post.class, id);
db.insert(post);
db.update(post);
db.delete(post);

// Queries (HQL, not SQL — references entity fields)
List<Post> posts = db.findAll(Post.class);
List<Post> posts = db.query(Post.class, "author.id = ?", userId);
Post post = db.queryOne(Post.class, "slug = ?", slug);
long count = db.count(Post.class, "author.id = ?", userId);

// Pagination
Page<Post> page = db.paginate(Post.class, "ORDER BY createdAt DESC", pageNum, 20);

// Complex queries — HQL
List<Object[]> results = db.hql("SELECT p.author.name, COUNT(p) FROM Post p GROUP BY p.author.name");

// Batch operations
db.insertAll(List.of(post1, post2, post3));

// Raw SQL escape hatch
db.sql("UPDATE posts SET view_count = view_count + 1 WHERE id = ?", id);

// Scoped session for WebSocket handlers or jobs
db.withSession(session -> {
    session.insert(entity);
});
```

### Key persistence decisions

- **HQL queries, not SQL** — references entity fields, works on H2 and PostgreSQL without changes.
- **Positional `?` parameters only** — simpler, consistent, AI-friendly.
- **No lazy loading** — StatelessSession doesn't support it. Fetch what you need explicitly. Eliminates N+1 surprises.
- **No cascading** — `db.insert(post)` inserts only the post. Insert related entities explicitly.
- **No dirty checking** — modify fields, then call `db.update(post)`. Explicit over implicit.
- **Transactions are per-request** — framework opens transaction before controller, commits after, rolls back on exception.

### Schema migrations

Flyway runs automatically on startup. Plain SQL files:

```
migrations/
  V1__create_posts.sql
  V2__add_author_to_posts.sql
  V3__create_users.sql
```

## Configuration

```properties
# application.conf
port=8080
db.url=jdbc:h2:./data/myapp
db.pool.size=10
smtp.url=smtp://localhost:1025
session.secret=my-secret-key-at-least-32-chars

# Dev overrides
%dev.port=9000
%dev.db.url=jdbc:h2:mem:dev

# Prod overrides
%prod.db.pool.size=50
%prod.smtp.url=smtp://mail.example.com:587
%prod.smtp.user=${SMTP_USER}
%prod.smtp.pass=${SMTP_PASS}

# Any custom prefix
%staging.db.url=jdbc:h2:./data/staging
```

Resolution order: active mode prefix → unprefixed default → environment variable (auto-mapped: `db.url` → `DB_URL`) → error if missing.

Start with `brace dev` (activates `%dev`), `brace start --mode prod` (activates `%prod`), etc.

Config file is hot-reloaded in dev mode (no restart needed).

## Sessions & Authentication

### Signed cookie sessions

Sessions are a `Map<String, String>` serialized to JSON, signed with HMAC-SHA256, stored as a cookie. No server-side session store.

```java
session.get("role")              // String
session.getInt("userId")         // int
session.has("userId")            // boolean
session.set("key", value)        // set
session.remove("key")            // remove
session.clear()                  // destroy session
```

~4KB limit (cookie size). Store IDs and flags, not large objects.

### CSRF protection

Automatic. Framework generates a CSRF token in the session, validates it on POST/PUT/DELETE requests.

In templates:
```html
<form method="POST" action="/posts">
    ${csrfField}
    ...
</form>
```

Skipped for JSON API requests (`Content-Type: application/json`).

### Passwords

```java
String hash = Passwords.hash("user-password");       // bcrypt, cost 12
boolean ok = Passwords.check("user-password", hash);  // verify
```

## Form Binding & Validation

### Form records

```java
public record PostForm(
    @Required String title,
    @Required @MinLength(10) String body,
    @Optional String category
) {
    // Optional custom validation
    public void validate(Errors errors) {
        if (title.contains("<script>")) {
            errors.add("title", "must not contain scripts");
        }
    }
}
```

### Built-in validation annotations

`@Required`, `@MinLength(n)`, `@MaxLength(n)`, `@Min(n)`, `@Max(n)`, `@Pattern("regex")`, `@Email`, `@In("a", "b", "c")`

### Form API

```java
var form = req.form(PostForm.class);
form.valid()                // boolean
form.value()                // the PostForm record (null if invalid)
form.errors()               // Map<String, List<String>>
form.errors("title")        // List<String> for one field
form.raw("title")           // raw submitted string (for re-rendering)
```

### Entity mapping convention

Each entity that accepts form input has an `apply()` method:

```java
@Entity
public class Post {
    // fields...
    public void apply(PostForm form) {
        this.title = form.title();
        this.body = form.body();
    }
}
```

Controller usage:
```java
var post = new Post();
post.apply(form.value());
post.authorId = session.getInt("userId");
db.insert(post);
```

## Templates (JTE)

Compiled to Java classes at build time, hot-reloaded in dev mode.

```html
<!-- views/posts/show.jte -->
@import app.models.Post
@param Post post

@template.layout.main(title = post.title, content = @`
    <h1>${post.title}</h1>
    <p>${post.body}</p>
    <p>By ${post.author.name}</p>
`)
```

Layout template:
```html
<!-- views/layout/main.jte -->
@param String title
@param gg.jte.Content content

<!DOCTYPE html>
<html>
<head><title>${title}</title></head>
<body>${content}</body>
</html>
```

`View.render("template", "key", value)` returns a String (for use in emails). `View.of("template", "key", value)` returns a `Result` (for HTTP responses).

## Jobs

### In-memory recurring jobs

For maintenance tasks that re-register on startup:

```java
app.every("5m", "cleanup-sessions", new CleanupExpiredSessions(db));
app.daily("02:00", "send-digest", new SendDigestEmail(db, mail));
app.cron("0 */15 9-17 * * MON-FRI", "poll-payments", new PollPayments(db));
```

Jobs implement:
```java
public interface Job {
    void run(Database db) throws Exception;
}
```

Each job gets its own Database session. Runs on virtual threads.

### Durable jobs

For one-off future tasks that survive restarts. Stored in `scheduled_jobs` table.

```java
Jobs.schedule(db, new SendReceipt(orderId), Duration.ofMinutes(5));
Jobs.schedule(db, new SendSurvey(orderId), Duration.ofDays(7));

// With options
Jobs.schedule(db, new SendReceipt(orderId), Duration.ofMinutes(5),
    JobOptions.maxAttempts(5).backoff(Duration.ofMinutes(10)));

// Dependencies — simple single-dependency chains
var jobA = Jobs.schedule(db, new ImportProducts(csvUrl), Duration.ZERO);
var jobB = Jobs.schedule(db, new ReindexSearch(), Duration.ZERO, JobOptions.after(jobA));
```

Durable jobs implement:
```java
public interface DurableJob {
    String data();
    void run(String data, Database db) throws Exception;
}
```

### Poller behavior

- Queue empty: poll every 10 seconds
- Queue has items: claim batch of 50, execute in parallel on virtual threads, run continuously until drained

### Parallel helper for bulk operations

```java
Jobs.parallel(items, 20, item -> {  // 20 concurrent virtual threads
    // process item
});
```

## Mailer

```java
var mail = Brace.mailer(Config.get("smtp.url"))
    .from("noreply@myapp.com");

mail.to("user@example.com")
    .subject("Welcome!")
    .html(View.render("emails/welcome", "user", user))
    .send();

mail.to("user@example.com")
    .cc("manager@example.com")
    .subject("Invoice")
    .html(View.render("emails/invoice", "invoice", invoice))
    .attach("invoice.pdf", pdfBytes, "application/pdf")
    .send();
```

- Synchronous by default. For background sending, use a durable job.
- Dev mode: emails captured in ops dashboard, not actually sent.
- Same JTE templates for emails and pages.
- Connection URL format: `smtp://user:pass@host:port` or `smtps://...` for TLS.

## WebSockets

```java
app.ws("/chat", ChatSocket::new);
```

```java
public class ChatSocket {
    private final WsContext ws;

    public ChatSocket(WsContext ws) {
        this.ws = ws;
    }

    public void onConnect() {
        ws.join("general");
        ws.broadcast("general", Json.of("event", "joined"));
    }

    public void onMessage(String message) {
        ws.broadcast("general", Json.of("event", "message", "text", message));
    }

    public void onClose(int code, String reason) {
        ws.broadcast("general", Json.of("event", "left"));
    }
}
```

- Room-based broadcasting built in (`ws.join()`, `ws.broadcast()`).
- Session available (read-only, from upgrade request).
- No automatic database session — use `db.withSession()` for explicit scoped access.

## AI Ops Layer

### Diagnostics endpoint — `GET /ops/status?key=SECRET`

Single JSON endpoint with complete application state:

```json
{
  "app": {"name", "mode", "version", "uptime", "startedAt", "javaVersion", "framework"},
  "http": {"totalRequests", "requestsLast5m", "avgLatencyMs", "p99LatencyMs", "errorRate", "slowestRoutes", "statusCodes"},
  "database": {"type", "status", "totalQueries", "avgQueryMs", "slowQueries", "connectionPool"},
  "memory": {"heapUsed", "heapMax", "gcPausesLast5m", "gcPauseTotalMs"},
  "errors": {"last24h", "unique", "recent": [{"type", "message", "route", "count", "stackTrace", "lastRequest", "queriesBeforeError"}]},
  "jobs": {"scheduled": [...], "durable": {"pending", "running", "completedLast24h", "failedLast24h", "recentFailures"}},
  "websockets": {"activeConnections", "rooms", "messagesLast5m"},
  "mailer": {"sentLast24h", "failedLast24h", "lastFailure"},
  "config": {"key": "value (secrets masked)"},
  "timeseries": {"minutes": [...last 48h...], "hours": [...last 30 days...]}
}
```

### Persistent statistics

Errors, job history, and email logs stored in framework database tables. Throughput/latency stats flushed from in-memory ring buffer to `ops_stats` table once per minute (one INSERT/minute).

Retention: per-minute granularity for 48 hours, compacted to per-hour beyond that.

### Structured logging

Every request produces a JSON log entry:

```json
{"ts": "...", "level": "INFO", "event": "http.request", "method": "GET", "path": "/posts/123", "status": 200, "durationMs": 0.8, "queries": 2, "queryMs": 0.4, "templateMs": 0.3}
```

Application events via `Log.event("user.signup", Map.of("userId", user.id))`.

### Deploy hooks

Framework emits structured events via stdout (Docker log capture) or webhook:

| Event | When |
|---|---|
| `app.started` | Startup complete (config summary, route table, migration status) |
| `app.stopped` | Graceful shutdown |
| `app.error.new` | First occurrence of a new error type |
| `app.error.spike` | Error rate exceeds threshold |
| `app.job.failed` | Durable job exhausted retries |
| `app.health.degraded` | Memory > 90%, slow queries, etc. |

### Ops control endpoints

```
POST /ops/config?key=SECRET     {"db.pool.size": 20}
POST /ops/cache/clear?key=SECRET
POST /ops/maintenance?key=SECRET {"enabled": true}
POST /ops/job/run?key=SECRET     {"name": "cleanup-sessions"}
POST /ops/errors/{id}/resolve?key=SECRET
GET  /ops/routes?key=SECRET
GET  /ops/logs?key=SECRET&lines=100&level=ERROR
```

### Built-in dashboard

Single-page HTML served at `/ops/dashboard?key=SECRET`. No external dependencies — plain HTML, inline CSS, inline JS (~15KB). Fetches `/ops/status` every 5 seconds. Sparkline charts via inline SVG.

Shows: request throughput, latency, error rate, memory, route performance, errors with context, job status, WebSocket connections, mailer status. History survives restarts via `ops_stats` table.

## Dev Experience & CLI

### CLI commands

```bash
brace new myapp              # scaffold a new project
brace dev                    # start in dev mode (hot reload)
brace start                  # start in prod mode
brace start --mode staging   # custom mode prefix
brace routes                 # print route table
brace migrate                # run pending migrations
brace migrate:create name    # create a blank migration file
brace build                  # compile + precompile templates + generate CLAUDE.md
brace deploy                 # build + Docker image + push + Dokploy deploy
```

### Hot reload in dev mode

| Layer | Mechanism | Speed |
|---|---|---|
| JTE templates | Built-in file watcher, recompile | Instant (refresh browser) |
| application.conf | File watcher, re-read | Instant |
| Static assets | Served from filesystem | Instant |
| Java source | Recompile (incremental) + JVM restart | ~1-2 seconds |

No custom classloader. No bytecode enhancement. Fast process restart via incremental compilation + H2 embedded (no DB reconnection).

### Dev error page

Rich error page showing: source code with highlighted line, stack trace, request details (method, path, params, session), database queries that executed before the error, and a diagnostic suggestion.

### Auto-generated CLAUDE.md

`brace build` generates a CLAUDE.md describing: all routes with handlers, all models with fields, all forms with validations, all jobs with schedules, all templates with parameters, all config keys. Regenerated on every build, always current.

### Project scaffold

```
myapp/
├── src/
│   ├── App.java
│   ├── controllers/
│   ├── models/
│   ├── forms/
│   ├── jobs/
│   └── views/
│       └── layout/
├── migrations/
├── public/
├── application.conf
├── Dockerfile
├── CLAUDE.md
└── pom.xml
```

## Performance Estimates

### Per-request latency (full-stack page, 5 DB queries + template)

| Layer | Spring Boot + PG localhost + Thymeleaf | Brace + H2 embedded + JTE |
|---|---|---|
| Framework overhead | ~125μs | ~33μs |
| Database (5 queries) | ~775μs | ~165μs |
| Template rendering | ~479μs | ~180μs |
| Session check | ~50μs | ~10μs |
| **Total** | **~1,430μs (1.4ms)** | **~388μs (0.4ms)** |

Estimated **~3.7x faster** overall. Max throughput on 8 cores: ~18,000 req/s vs ~5,000 req/s.

### AI token efficiency

Estimated **~65% fewer tokens** per development task compared to Spring Boot, due to:
- Smaller context needed (~15 core types vs hundreds)
- No DI graph to trace
- Compile-time errors cost ~50 tokens to fix vs 500-2,000 for runtime/config errors
- Advantage grows with codebase size (linear vs super-linear context scaling)

## Testing

Brace provides a three-level testing approach. With H2 in-memory, there's no reason to separate unit and integration tests — the full app boots in ~50ms with a real database.

### Level 1: Unit tests (JUnit 5, plain Java)

Controllers and models are plain classes with no framework superclass. Test them directly:

```java
@Test
void postApplyForm() {
    var form = new PostForm("Title", "Body content here", "tech");
    var post = new Post();
    post.apply(form);

    assert post.title.equals("Title");
    assert post.body.equals("Body content here");
}
```

### Level 2: In-process integration tests (Brace TestApp)

The framework provides a `TestApp` that boots the real application with H2 in-memory. Real routing, real Hibernate, real JTE templates, real sessions. No mocks, no Docker, no external services.

```java
public class PostControllerTest {

    static TestApp app = Brace.test()
        .database("jdbc:h2:mem:test")
        .templates("views")
        .sessions("test-secret")
        .start();

    @AfterAll
    static void stop() { app.stop(); }

    @BeforeEach
    void reset() { app.resetDatabase(); }

    @Test
    void showPost() {
        app.db().sql("INSERT INTO posts (id, title, body) VALUES (1, 'Hello', 'World')");

        var response = app.get("/posts/1");

        assert response.status() == 200;
        assert response.body().contains("Hello");
    }

    @Test
    void createPostRequiresLogin() {
        var response = app.post("/posts", Map.of("title", "New", "body", "Content"));

        assert response.status() == 302;
        assert response.redirectedTo().equals("/login");
    }

    @Test
    void createPostWithSession() {
        app.db().sql("INSERT INTO users (id, email, name) VALUES (1, 'test@test.com', 'Test')");

        var response = app.post("/posts",
            Map.of("title", "New Post", "body", "This is long enough content"),
            Session.of("userId", 1));

        assert response.status() == 302;
        var post = app.db().queryOne(Post.class, "title = ?", "New Post");
        assert post != null;
        assert post.authorId == 1;
    }

    @Test
    void createPostValidation() {
        var response = app.post("/posts",
            Map.of("title", "", "body", "short"),
            Session.of("userId", 1));

        assert response.status() == 200;
        assert response.body().contains("is required");
        assert response.body().contains("at least 10");
    }
}
```

#### TestApp API

```java
// HTTP methods — return TestResponse
app.get("/path")
app.post("/path", formParams)
app.post("/path", formParams, session)
app.postJson("/path", jsonBody)
app.put("/path", body)
app.delete("/path")

// TestResponse
response.status()                   // int
response.body()                     // String (HTML or JSON)
response.bodyAs(MyDto.class)        // deserialize JSON
response.header("Location")         // response header
response.redirectedTo()             // convenience for 302 Location

// Database access for setup/assertions
app.db()

// Session injection (no need to go through login flow)
Session.of("userId", 1, "role", "admin")

// Reset between tests
app.resetDatabase()                 // truncate all tables, re-run migrations

// Mailer assertions
app.mailer().sent()                 // List<CapturedEmail>
app.mailer().last().to()
app.mailer().last().subject()
app.mailer().last().body()
app.mailer().clear()

// Job assertions
app.jobs().pending()                // List<DurableJob> in queue
app.jobs().runPending()             // execute all pending durable jobs immediately
```

### Level 3: End-to-end browser tests (Playwright)

For testing full user flows with JavaScript execution, rendered pages, and real browser interaction. Playwright has first-class Java/JUnit 5 support and is the fastest/most reliable browser testing tool available.

```java
@UsePlaywright
public class PostFlowTest {

    static TestApp app = Brace.test()
        .database("jdbc:h2:mem:test")
        .templates("views")
        .sessions("test-secret")
        .start();

    @BeforeEach
    void reset() { app.resetDatabase(); }

    @Test
    void userCanCreateAndViewPost(Page page) {
        app.db().sql("INSERT INTO users (id, email, name, password_hash) VALUES (1, 'test@test.com', 'Test', ?)",
            Passwords.hash("password"));

        page.navigate(app.url("/login"));
        page.getByLabel("Email").fill("test@test.com");
        page.getByLabel("Password").fill("password");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log in")).click();

        assertThat(page).hasURL(app.url("/"));

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("New Post")).click();
        page.getByLabel("Title").fill("My First Post");
        page.getByLabel("Body").fill("This is the body of my first post, long enough to pass validation.");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create")).click();

        assertThat(page).hasURL(Pattern.compile("/posts/\\d+"));
        assertThat(page.getByRole(AriaRole.HEADING)).hasText("My First Post");
    }

    @Test
    void validationErrorsShowInBrowser(Page page) {
        // ... login ...
        page.navigate(app.url("/posts/new"));
        page.getByLabel("Title").fill("");
        page.getByLabel("Body").fill("short");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create")).click();

        assertThat(page.locator(".error")).containsText("is required");
        assertThat(page.locator(".error")).containsText("at least 10");
    }
}
```

#### Why Playwright

- First-class Java/JUnit 5 support (`@UsePlaywright` annotation)
- Auto-waiting — no `sleep()` or explicit waits needed (primary cause of flaky tests eliminated)
- Headless by default, 2-15x faster than Selenium
- ~10MB footprint (vs Selenium ~50MB, Cypress ~500MB)
- Supports Chromium, Firefox, WebKit from a single API

### Testing speed summary

| Level | Tool | Speed per test | What it tests |
|---|---|---|---|
| Unit | JUnit 5 | <1ms | Model logic, utility functions |
| In-process integration | Brace TestApp | ~5ms | Routes, controllers, DB, templates, sessions, forms, jobs, emails |
| E2E browser | Playwright + TestApp | ~100-500ms | Full user flows, JavaScript, visual rendering |

All three levels run against the same in-process app with H2 in-memory. No Docker, no external services, no network flakiness.

## Accessibility & Testability

Brace scaffolded templates and error pages use semantic HTML with proper ARIA attributes by default. This is both an accessibility best practice and a direct enabler of reliable E2E testing.

### The overlap

| Accessibility requirement | E2E testing benefit |
|---|---|
| Buttons are `<button>`, not styled `<div>` | `getByRole(BUTTON, "Submit")` works |
| Form inputs have `<label>` elements | `getByLabel("Email")` works |
| Headings use `<h1>`-`<h6>` properly | `getByRole(HEADING, "Post Title")` works |
| Links are `<a>` with descriptive text | `getByRole(LINK, "View all posts")` works |
| Images have `alt` text | `getByAltText("User avatar")` works |
| ARIA labels on interactive elements | `getByLabel("Close modal")` works |
| Semantic HTML structure | Tests survive CSS refactors completely |

### AI benefit

Playwright's role-based selectors (`getByRole`, `getByLabel`) are more self-documenting than CSS selectors. AI generates more reliable tests because the selectors map directly to what's visible in the template — a `<label>` in the template becomes a `getByLabel()` in the test. No brittle CSS class coupling, no XPath guessing.

### Framework conventions

- Scaffolded templates use semantic HTML (`<nav>`, `<main>`, `<article>`, `<button>`, `<label>`)
- Form helpers render proper `<label>` + `<input>` associations
- Error pages use ARIA roles for error regions
- CLAUDE.md convention: "use semantic HTML with labels and ARIA attributes"

This is not a runtime enforcement — just defaults and conventions that make the accessible, testable path the easiest path.

## Database Tables (Framework-Managed)

```sql
-- Durable job queue
CREATE TABLE scheduled_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    job_class VARCHAR(255) NOT NULL,
    job_data TEXT,
    run_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    failed_at TIMESTAMP,
    error TEXT,
    attempts INT DEFAULT 0,
    max_attempts INT DEFAULT 3,
    depends_on_id BIGINT REFERENCES scheduled_jobs(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Error log
CREATE TABLE ops_errors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    error_type VARCHAR(255),
    message TEXT,
    stack_trace TEXT,
    route VARCHAR(255),
    request_detail TEXT,
    queries_before TEXT,
    first_seen TIMESTAMP,
    last_seen TIMESTAMP,
    occurrence_count INT DEFAULT 1,
    resolved_at TIMESTAMP
);

-- Email log
CREATE TABLE ops_emails (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    to_address VARCHAR(255),
    subject VARCHAR(255),
    status VARCHAR(20),
    error TEXT,
    sent_at TIMESTAMP
);

-- Throughput/latency stats
CREATE TABLE ops_stats (
    ts TIMESTAMP NOT NULL,
    granularity VARCHAR(10) NOT NULL,
    requests INT,
    errors INT,
    avg_latency_us INT,
    p99_latency_us INT,
    queries INT,
    avg_query_us INT,
    PRIMARY KEY (ts, granularity)
);
```
