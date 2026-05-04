# Phase 3: Templates — JTE Integration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the View stub with real JTE template rendering. At the end, controllers return `View.of("posts/show", "post", post)` and JTE renders a real HTML page with typed parameters and layout support.

**Architecture:** `Brace.templates(path)` configures the JTE template engine. `View.of()` renders through JTE instead of returning placeholder text. `View.render()` returns HTML as a string (for emails). JTE hot-reloads templates in dev mode.

**Tech Stack:** JTE (gg.jte:jte, gg.jte:jte-runtime), JUnit 5

---

## File Structure

```
src/main/java/com/larvalabs/brace/
├── View.java                   # Updated — renders through JTE
├── TemplateEngine.java         # JTE wrapper — init, render, hot-reload config
├── Brace.java                  # Updated — accepts templates path
src/test/java/com/larvalabs/brace/
├── TemplateTest.java           # Tests for template rendering
src/test/resources/
├── views/
│   ├── hello.jte               # Simple test template
│   ├── params.jte              # Template with typed params
│   └── layout/
│       └── main.jte            # Layout template
│   └── withLayout.jte          # Template using layout
```

---

### Task 1: Add JTE Dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add JTE dependencies**

Add:
- `gg.jte:jte` (latest 3.x)
- `gg.jte:jte-runtime` (same version)

Add a `jte.version` property following the existing pattern.

- [ ] **Step 2: Verify build compiles and all 68 tests still pass**

- [ ] **Step 3: Commit**

```
git commit -m "Phase 3 Task 1: add JTE template engine dependency"
```

---

### Task 2: TemplateEngine Wrapper

**Files:**
- Create: `src/main/java/com/larvalabs/brace/TemplateEngine.java`

- [ ] **Step 1: Implement TemplateEngine**

TemplateEngine wraps JTE's `gg.jte.TemplateEngine`. It:
1. Accepts a template root directory path
2. Creates a JTE engine in the appropriate mode:
   - Dev mode: `CodeResolver` from filesystem, hot-reload enabled
   - Prod mode: precompiled templates from classpath
3. Provides `render(template, params)` returning HTML string
4. For now, always use filesystem-based (dev mode) — precompiled comes later

```java
package com.larvalabs.brace;

import gg.jte.ContentType;
import gg.jte.TemplateOutput;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;

import java.nio.file.Path;
import java.util.Map;

public class TemplateEngine {

    private final gg.jte.TemplateEngine engine;

    public TemplateEngine(String templatePath) {
        var codeResolver = new DirectoryCodeResolver(Path.of(templatePath));
        this.engine = gg.jte.TemplateEngine.create(codeResolver, ContentType.Html);
    }

    public String render(String template, Map<String, Object> params) {
        var output = new StringOutput();
        engine.render(template + ".jte", params, output);
        return output.toString();
    }
}
```

- [ ] **Step 2: Commit**

```
git commit -m "Phase 3 Task 2: TemplateEngine wrapper for JTE"
```

---

### Task 3: Update View to Use JTE + Tests

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/View.java`
- Modify: `src/main/java/com/larvalabs/brace/Brace.java`
- Modify: `src/main/java/com/larvalabs/brace/BraceHandler.java`
- Create: `src/test/java/com/larvalabs/brace/TemplateTest.java`
- Create: `src/test/resources/views/hello.jte`
- Create: `src/test/resources/views/params.jte`
- Create: `src/test/resources/views/layout/main.jte`
- Create: `src/test/resources/views/withLayout.jte`

- [ ] **Step 1: Create test templates**

```
<!-- src/test/resources/views/hello.jte -->
Hello from JTE!
```

```
<!-- src/test/resources/views/params.jte -->
@param String name
@param int count

Hello ${name}, you have ${count} items.
```

```
<!-- src/test/resources/views/layout/main.jte -->
@param String title
@param gg.jte.Content content

<!DOCTYPE html>
<html>
<head><title>${title}</title></head>
<body>
${content}
</body>
</html>
```

```
<!-- src/test/resources/views/withLayout.jte -->
@param String message

@template.layout.main(title = "Test Page", content = @`
<h1>${message}</h1>
`)
```

- [ ] **Step 2: Update View.java**

View needs access to the TemplateEngine. Since View is created via static `View.of()`, the engine needs to be set globally (set once by Brace at startup):

```java
package com.larvalabs.brace;

import java.util.LinkedHashMap;
import java.util.Map;

public class View extends Result {

    private static TemplateEngine engine;

    private final String template;
    private final Map<String, Object> params;

    private View(String template, Map<String, Object> params, String renderedHtml) {
        super(200, "text/html", renderedHtml);
        this.template = template;
        this.params = params;
    }

    static void setEngine(TemplateEngine engine) {
        View.engine = engine;
    }

    public static View of(String template, Object... keyValues) {
        var params = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        String html;
        if (engine != null) {
            html = engine.render(template, params);
        } else {
            html = "[Template: " + template + " | Params: " + params.keySet() + "]";
        }
        return new View(template, params, html);
    }

    // Render to string (for emails, etc.)
    public static String render(String template, Object... keyValues) {
        var params = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        if (engine != null) {
            return engine.render(template, params);
        }
        return "[Template: " + template + " | Params: " + params.keySet() + "]";
    }

    public String template() { return template; }
    public Map<String, Object> params() { return params; }
}
```

- [ ] **Step 3: Update Brace.java**

Add a `templates(String path)` method that creates a TemplateEngine and sets it on View:

```java
private TemplateEngine templateEngine;

public Brace templates(String path) {
    this.templateEngine = new TemplateEngine(path);
    View.setEngine(this.templateEngine);
    return this;
}
```

- [ ] **Step 4: Write tests**

```java
package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app()
            .port(0)
            .templates("src/test/resources/views");

        app.get("/hello", req -> View.of("hello"));
        app.get("/greet/{name}", req ->
            View.of("params", "name", req.param("name"), "count", 42));
        app.get("/layout", req ->
            View.of("withLayout", "message", "It works!"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void simpleTemplate() throws Exception {
        var response = get("/hello");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello from JTE!"));
    }

    @Test
    void templateWithParams() throws Exception {
        var response = get("/greet/Alice");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello Alice"));
        assertTrue(response.body().contains("42 items"));
    }

    @Test
    void templateWithLayout() throws Exception {
        var response = get("/layout");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<!DOCTYPE html>"));
        assertTrue(response.body().contains("<title>Test Page</title>"));
        assertTrue(response.body().contains("<h1>It works!</h1>"));
    }

    @Test
    void viewRenderReturnsString() {
        var html = View.render("hello");
        assertTrue(html.contains("Hello from JTE!"));
    }

    @Test
    void contentTypeIsHtml() throws Exception {
        var response = get("/hello");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
    }
}
```

- [ ] **Step 5: Run all tests**

Run: `mvn test`
Expected: 68 existing + ~5 new = ~73 passing

Note: The existing ResultTest has a `viewStubResult` test that checks for the placeholder format. Since View now renders through JTE when an engine is set, but ResultTest doesn't set an engine, the stub fallback should still work. Verify this.

- [ ] **Step 6: Commit**

```
git commit -m "Phase 3 Task 3: JTE template rendering with layout support"
```

---

## Phase 3 Complete

At this point:
- `View.of("template", "key", value)` renders through JTE
- `View.render("template", "key", value)` returns HTML string (for emails)
- Layout templates work via JTE's `@template.layout.main(content = @`...`)`
- Templates hot-reload in dev mode (JTE's DirectoryCodeResolver)
- Fallback to stub when no engine configured (existing tests still pass)

**Next:** Phase 4 adds sessions, CSRF, form binding, and validation.
