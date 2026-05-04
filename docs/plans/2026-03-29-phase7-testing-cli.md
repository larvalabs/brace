# Phase 7: TestApp Harness & CLI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the TestApp harness for easy in-process testing, and a basic CLI for project scaffolding. At the end, a developer can write integration tests with `Brace.test()` and scaffold new projects with `brace new myapp`.

**Architecture:** `Brace.test()` returns a `TestApp` that boots the full framework with H2 in-memory, provides an HTTP client for making requests, and exposes database/mailer/jobs for assertions. The CLI is a simple main class that generates project files.

**Tech Stack:** java.net.http.HttpClient (built into JDK), JUnit 5

---

## File Structure

```
src/main/java/com/larvalabs/brace/
├── TestApp.java                # Test harness — boots app, HTTP client, assertions
├── TestResponse.java           # Response wrapper for test assertions
├── Cli.java                    # CLI entry point — brace new, brace routes
├── ProjectGenerator.java       # Scaffolds a new project
├── Brace.java                  # Updated — test() factory method
```

---

### Task 1: TestApp Harness

**Files:**
- Create: `src/main/java/com/larvalabs/brace/TestApp.java`
- Create: `src/main/java/com/larvalabs/brace/TestResponse.java`
- Modify: `src/main/java/com/larvalabs/brace/Brace.java` — add test() factory
- Create: `src/test/java/com/larvalabs/brace/TestAppTest.java`

- [ ] **Step 1: Create TestResponse**

Simple wrapper for HTTP response data with convenience methods:

```java
package com.larvalabs.brace;

import java.net.http.HttpResponse;

public class TestResponse {
    private final int status;
    private final String body;
    private final java.net.http.HttpHeaders headers;

    TestResponse(HttpResponse<String> response) {
        this.status = response.statusCode();
        this.body = response.body();
        this.headers = response.headers();
    }

    public int status() { return status; }
    public String body() { return body; }
    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }
    public String redirectedTo() { return header("Location"); }

    public <T> T bodyAs(Class<T> type) {
        try {
            return Json.mapper().readValue(body, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body as " + type.getSimpleName(), e);
        }
    }
}
```

- [ ] **Step 2: Create TestApp**

TestApp wraps a running Brace instance and provides test utilities:

```java
package com.larvalabs.brace;

import java.net.URI;
import java.net.http.*;
import java.util.Map;

public class TestApp {

    private final Brace app;
    private final HttpClient client;
    private final DatabaseFactory databaseFactory;
    private final Mailer mailer;

    TestApp(Brace app, DatabaseFactory databaseFactory, Mailer mailer) {
        this.app = app;
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new java.net.CookieManager())
            .build();
        this.databaseFactory = databaseFactory;
        this.mailer = mailer;
    }

    public String url(String path) {
        return "http://localhost:" + app.actualPort() + path;
    }

    // HTTP methods
    public TestResponse get(String path) { ... }
    public TestResponse post(String path, Map<String, String> formParams) { ... }
    public TestResponse post(String path, Map<String, String> formParams, Session session) { ... }
    public TestResponse postJson(String path, Object body) { ... }
    public TestResponse delete(String path) { ... }

    // Database access for seeding and assertions
    public Database db() {
        var session = databaseFactory.openSession();
        var db = new Database(session);
        db.beginTransaction();
        return db;  // caller responsible for commit + close
    }

    // Convenience: run a db operation in a transaction
    public void withDb(java.util.function.Consumer<Database> action) {
        var db = db();
        try {
            action.accept(db);
            db.commitTransaction();
        } catch (Exception e) {
            db.rollbackTransaction();
            throw new RuntimeException(e);
        } finally {
            db.close();
        }
    }

    // Reset database (truncate all tables)
    public void resetDatabase() {
        withDb(db -> {
            // Get all table names and truncate
            var tables = db.sqlQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'TABLE'");
            // Disable referential integrity for truncation
            db.sql("SET REFERENTIAL_INTEGRITY FALSE");
            for (var row : tables) {
                var tableName = row instanceof Object[] arr ? (String) arr[0] : (String) row;
                if (!tableName.startsWith("flyway_")) {
                    db.sql("TRUNCATE TABLE " + tableName);
                }
            }
            db.sql("SET REFERENTIAL_INTEGRITY TRUE");
        });
    }

    // Mailer assertions
    public Mailer mailer() { return mailer; }

    // Lifecycle
    public void stop() throws Exception { app.stop(); }
    public int port() { return app.actualPort(); }
}
```

For POST with form params, URL-encode the map:
```java
public TestResponse post(String path, Map<String, String> formParams) {
    var body = formParams.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
        .collect(Collectors.joining("&"));
    var request = HttpRequest.newBuilder()
        .uri(URI.create(url(path)))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .build();
    return new TestResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
}
```

For POST with session injection: create a signed session cookie and add it to the request:
```java
public TestResponse post(String path, Map<String, String> formParams, Session session) {
    // Same as above but add session cookie header
    var sessionCookie = session.toCookie(sessionSecret);
    // Add Cookie header to request
}
```

Wait — TestApp needs the session secret. Pass it from Brace.

- [ ] **Step 3: Add Brace.test() factory**

```java
public static TestAppBuilder test() {
    return new TestAppBuilder();
}

public static class TestAppBuilder {
    private String dbUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1";
    private String dbUser = null;
    private String dbPass = null;
    private String sessionSecret = "test-secret-at-least-32-characters-long";
    private String templatesPath = null;
    private final List<Class<?>> entityClasses = new ArrayList<>();

    public TestAppBuilder database(String url) { this.dbUrl = url; return this; }
    public TestAppBuilder templates(String path) { this.templatesPath = path; return this; }
    public TestAppBuilder sessions(String secret) { this.sessionSecret = secret; return this; }
    public TestAppBuilder entities(Class<?>... classes) {
        Collections.addAll(entityClasses, classes);
        return this;
    }

    public TestApp start(java.util.function.Consumer<Brace> configure) throws Exception {
        var dbFactory = new DatabaseFactory(dbUrl, dbUser, dbPass, entityClasses);
        var mailer = new Mailer(null); // dev mode
        var app = Brace.app()
            .port(0)
            .database(dbFactory)
            .sessions(sessionSecret)
            .mailer(mailer);
        if (templatesPath != null) {
            app.templates(templatesPath);
        }
        configure.accept(app);  // let user register routes
        app.start();
        return new TestApp(app, dbFactory, mailer, sessionSecret);
    }
}
```

- [ ] **Step 4: Write TestAppTest**

```java
package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TestAppTest {

    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .entities(Post.class)
            .templates("src/test/resources/views")
            .start(app -> {
                app.get("/hello", req -> Result.text("Hello!"));
                app.get("/posts", (DbHandler) (req, db) ->
                    Json.of(db.findAll(Post.class)));
                app.post("/posts", (DbHandler) (req, db) -> {
                    var post = new Post();
                    post.title = req.param("title");
                    post.body = req.param("body");
                    post.createdAt = Instant.now();
                    db.insert(post);
                    return Json.of(post, 201);
                });
            });
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
    }

    @BeforeEach
    void reset() {
        testApp.resetDatabase();
    }

    @Test
    void simpleGet() {
        var response = testApp.get("/hello");
        assertEquals(200, response.status());
        assertEquals("Hello!", response.body());
    }

    @Test
    void postAndQuery() {
        var response = testApp.post("/posts", Map.of("title", "Test", "body", "Content"));
        assertEquals(201, response.status());

        var list = testApp.get("/posts");
        assertTrue(list.body().contains("Test"));
    }

    @Test
    void resetDatabaseClearsData() {
        testApp.withDb(db -> {
            var post = new Post();
            post.title = "Seed";
            post.body = "Data";
            post.createdAt = Instant.now();
            db.insert(post);
        });

        testApp.resetDatabase();

        var response = testApp.get("/posts");
        assertEquals("[]", response.body());
    }

    @Test
    void dbHelper() {
        testApp.withDb(db -> {
            var post = new Post();
            post.title = "Via Helper";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
        });

        var response = testApp.get("/posts");
        assertTrue(response.body().contains("Via Helper"));
    }

    @Test
    void mailerCapture() {
        // Would need a route that sends email, but for now just verify mailer is accessible
        assertNotNull(testApp.mailer());
        assertEquals(0, testApp.mailer().sentCount());
    }
}
```

- [ ] **Step 5: Run all tests, commit**

```
git commit -m "Phase 7 Task 1: TestApp harness for in-process integration testing"
```

---

### Task 2: CLI and Project Generator

**Files:**
- Create: `src/main/java/com/larvalabs/brace/Cli.java`
- Create: `src/main/java/com/larvalabs/brace/ProjectGenerator.java`

- [ ] **Step 1: Create ProjectGenerator**

Generates a new Brace project directory with:
- `pom.xml` (with brace dependency)
- `src/main/java/{package}/App.java` (sample main)
- `src/main/java/{package}/controllers/HomeController.java`
- `src/test/java/{package}/HomeControllerTest.java`
- `migrations/V1__initial.sql`
- `src/main/resources/views/layout/main.jte`
- `src/main/resources/views/home/index.jte`
- `public/css/style.css`
- `application.conf`
- `Dockerfile`
- `CLAUDE.md`
- `.gitignore`

Each file is generated from a template string in Java. No external template files needed.

- [ ] **Step 2: Create Cli**

Simple main class that parses command-line args:

```java
package com.larvalabs.brace;

public class Cli {
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "new" -> {
                if (args.length < 2) {
                    System.err.println("Usage: brace new <project-name>");
                    System.exit(1);
                }
                ProjectGenerator.generate(args[1]);
            }
            case "routes" -> {
                System.out.println("Run your app and check /ops/routes or startup output");
            }
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("Brace CLI");
        System.out.println("  brace new <name>    Create a new project");
        System.out.println("  brace routes        Show route table");
    }
}
```

- [ ] **Step 3: Commit**

```
git commit -m "Phase 7 Task 2: CLI and project scaffolding"
```

---

## Phase 7 Complete

At this point, the Brace framework has:
- TestApp harness for fast in-process testing with H2
- CLI for project scaffolding
- All 7 phases complete: HTTP, database, templates, sessions/forms, jobs/mailer, AI ops, testing/CLI

**The framework is ready for use.**
