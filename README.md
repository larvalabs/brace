# Brace

A full-stack Java web framework for the AI era.

Plain Java. No DI container. No bytecode enhancement. No magic. Batteries included.

## Quick Start

```java
public class App {
    public static void main(String[] args) throws Exception {
        var config = Config.load(Path.of("application.conf"), System.getProperty("brace.mode"));
        var db = new DatabaseFactory(config.get("db.url"), config.get("db.user"), config.get("db.pass"),
            List.of(Post.class, User.class));
        var mail = new Mailer(config.get("smtp.url")).from("noreply@myapp.com");

        var app = Brace.app()
            .port(config.getInt("port", 8080))
            .database(db)
            .templates("views")
            .sessions(config.get("session.secret"))
            .mailer(mail)
            .ops(config.get("ops.secret"));

        var posts = new PostController();
        var auth = new AuthController(mail);

        app.before(Auth::requireLogin);
        app.get("/", posts::index);
        app.get("/posts/{id}", (DbHandler) posts::show);
        app.post("/posts", (FullHandler) posts::create);
        app.get("/login", auth::loginForm);
        app.post("/login", (SessionHandler) auth::login);

        app.every("5m", "cleanup", new CleanupJob());
        app.daily("02:00", "digest", new DigestJob(mail));

        app.start();
    }
}
```

## What's Included

- **HTTP** -- Jetty 12 with virtual threads, programmatic routing, middleware
- **Database** -- Hibernate 7 StatelessSession, per-request transactions, Flyway migrations
- **Templates** -- JTE compiled templates with layout support, hot-reload in dev
- **Sessions** -- HMAC-SHA256 signed cookies, no server-side storage
- **Forms** -- Record-based form binding with validation annotations
- **CSRF** -- Automatic protection on POST/PUT/DELETE, skip for JSON APIs
- **Jobs** -- In-memory recurring scheduler + durable database-backed queue with retry
- **Mailer** -- SMTP sending with dev-mode email capture
- **Ops** -- `/ops/status` diagnostics, `/ops/dashboard` built-in HTML dashboard, structured JSON logging
- **Testing** -- `Brace.test()` harness for fast in-process integration tests with H2
- **CLI** -- `brace new myapp` project scaffolding

## Philosophy

Brace is designed for AI-assisted development:

- **Explicit over implicit.** Every dependency is visible in `main()`. No classpath scanning, no auto-configuration, no proxy generation.
- **Compile-time over runtime.** Typed templates, typed parameters. Errors caught at build time, not when a user hits the page.
- **Small API surface.** ~15 core types. AI can hold the entire framework in context.
- **One way to do things.** No choice between annotations vs XML vs programmatic config. Just plain Java.

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
app.get("/hello", req -> Result.text("Hello!"));                          // Handler: Request only
app.get("/posts", (DbHandler) (req, db) -> Json.of(db.findAll(Post.class))); // DbHandler: Request + Database
app.get("/profile", (SessionHandler) (req, session) -> ...);              // SessionHandler: Request + Session
app.post("/posts", (FullHandler) (req, db, session) -> ...);              // FullHandler: Request + Database + Session
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
db.count(Post.class, "published = ?", true)       // count with condition
db.sql("UPDATE posts SET views = views + 1 WHERE id = ?", id) // native SQL
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

## AI Ops

`/ops/status?key=SECRET` returns full diagnostics:
- App uptime, Java version
- Request stats, status codes, slowest routes
- Memory usage
- Recent errors with stack traces and request context
- Job statuses (scheduled + durable)
- Mailer stats
- Per-minute timeseries

`/ops/dashboard?key=SECRET` serves a built-in HTML dashboard.

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
ops.secret=change-me

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

**~3,700 lines of framework code. 138 tests. One dependency for your project.**
