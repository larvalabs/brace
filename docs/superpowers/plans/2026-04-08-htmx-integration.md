# htmx Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add built-in htmx support to Brace with `req.isHtmx()`, bundled htmx.min.js, automatic `Vary` header, and convert the ops dashboard from a JS SPA to server-rendered HTML with htmx polling.

**Architecture:** Minimal framework additions (one method on Request, one classpath resource, one header in BraceHandler). The ops dashboard is converted from client-side JS rendering to server-side Java string building with htmx `hx-get`/`hx-select` for 5-second polling. Actions (resolve error, clear cache) return the full dashboard HTML so htmx can swap the content.

**Tech Stack:** htmx 2.0.4 (bundled as classpath resource), JTE (existing), Jetty (existing)

**Spec:** `docs/superpowers/specs/2026-04-08-htmx-integration-design.md`

---

### File Map

| File | Action | Purpose |
|---|---|---|
| `src/main/java/com/larvalabs/brace/Request.java` | Modify | Add `isHtmx()` method |
| `src/main/java/com/larvalabs/brace/BraceHandler.java` | Modify | Add `Vary: HX-Request` header, serve `/__brace/htmx.min.js` |
| `src/main/java/com/larvalabs/brace/OpsDashboard.java` | Rewrite | Server-side HTML rendering from data objects, htmx polling |
| `src/main/java/com/larvalabs/brace/OpsHandler.java` | Modify | Pass data to OpsDashboard, action endpoints return dashboard HTML |
| `src/main/resources/brace/htmx.min.js` | Create | Bundled htmx 2.0.4 |
| `src/test/java/com/larvalabs/brace/HtmxTest.java` | Create | Tests for isHtmx, Vary header, htmx.min.js serving |
| `src/test/java/com/larvalabs/brace/OpsIntegrationTest.java` | Modify | Update assertions for server-rendered dashboard |

---

### Task 1: Add `req.isHtmx()` to Request

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/Request.java`
- Create: `src/test/java/com/larvalabs/brace/HtmxTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/larvalabs/brace/HtmxTest.java`:

```java
package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmxTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0);

        app.get("/htmx-check", req -> Result.text(req.isHtmx() ? "htmx" : "normal"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET();
        for (int i = 0; i < headers.length - 1; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void isHtmxReturnsFalseForNormalRequest() throws Exception {
        var response = get("/htmx-check");
        assertEquals("normal", response.body());
    }

    @Test
    void isHtmxReturnsTrueWhenHeaderPresent() throws Exception {
        var response = get("/htmx-check", "HX-Request", "true");
        assertEquals("htmx", response.body());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=HtmxTest -pl .`

Expected: Compilation error — `isHtmx()` method does not exist on Request.

- [ ] **Step 3: Implement `isHtmx()` on Request**

Add to `src/main/java/com/larvalabs/brace/Request.java` after the `hasHeader` method (line 62):

```java
public boolean isHtmx() {
    return "true".equals(header("HX-Request"));
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=HtmxTest -pl .`

Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/larvalabs/brace/Request.java src/test/java/com/larvalabs/brace/HtmxTest.java
git commit -m "Add req.isHtmx() for detecting htmx requests"
```

---

### Task 2: Bundle htmx.min.js and serve from `/__brace/`

**Files:**
- Create: `src/main/resources/brace/htmx.min.js`
- Modify: `src/main/java/com/larvalabs/brace/BraceHandler.java`
- Modify: `src/test/java/com/larvalabs/brace/HtmxTest.java`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/larvalabs/brace/HtmxTest.java`:

```java
@Test
void htmxJsServedFromClasspath() throws Exception {
    var response = get("/__brace/htmx.min.js");
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/javascript"));
    assertTrue(response.body().contains("htmx"));
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=HtmxTest#htmxJsServedFromClasspath -pl .`

Expected: FAIL — 404 response, no route registered for `/__brace/htmx.min.js`.

- [ ] **Step 3: Download htmx.min.js**

```bash
mkdir -p src/main/resources/brace
curl -o src/main/resources/brace/htmx.min.js https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js
```

Verify the file was downloaded and contains htmx code:

```bash
head -1 src/main/resources/brace/htmx.min.js
```

- [ ] **Step 4: Serve htmx.min.js from BraceHandler**

Add a classpath resource serving method to `src/main/java/com/larvalabs/brace/BraceHandler.java`. Add a field and initialize it in the constructor:

Add field at the top of the class (after `maxUploadSize`):

```java
private final byte[] htmxJs;
```

In the **canonical constructor** (the one all others delegate to, currently lines 77-95), add after `this.maxUploadSize = maxUploadSize;`:

```java
byte[] htmxBytes = null;
try {
    var stream = BraceHandler.class.getResourceAsStream("/brace/htmx.min.js");
    if (stream != null) {
        htmxBytes = stream.readAllBytes();
        stream.close();
    }
} catch (Exception ignored) {}
this.htmxJs = htmxBytes;
```

In the `serveStaticFile` method, add at the very beginning (before the for loop over staticFileMappings):

```java
if ("/__brace/htmx.min.js".equals(requestPath) && htmxJs != null) {
    return Result.bytes(htmxJs, "text/javascript; charset=utf-8");
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=HtmxTest#htmxJsServedFromClasspath -pl .`

Expected: PASS.

- [ ] **Step 6: Run all tests to check for regressions**

Run: `mvn test -pl .`

Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/brace/htmx.min.js src/main/java/com/larvalabs/brace/BraceHandler.java src/test/java/com/larvalabs/brace/HtmxTest.java
git commit -m "Bundle htmx 2.0.4 and serve from /__brace/htmx.min.js"
```

---

### Task 3: Add `Vary: HX-Request` header for htmx responses

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/BraceHandler.java`
- Modify: `src/test/java/com/larvalabs/brace/HtmxTest.java`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/larvalabs/brace/HtmxTest.java`:

```java
@Test
void varyHeaderSetForHtmxRequests() throws Exception {
    var response = get("/htmx-check", "HX-Request", "true");
    assertEquals("HX-Request", response.headers().firstValue("Vary").orElse(""));
}

@Test
void varyHeaderNotSetForNormalRequests() throws Exception {
    var response = get("/htmx-check");
    assertTrue(response.headers().firstValue("Vary").isEmpty());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=HtmxTest#varyHeaderSetForHtmxRequests -pl .`

Expected: FAIL — no Vary header in response.

- [ ] **Step 3: Add Vary header logic to BraceHandler**

In `src/main/java/com/larvalabs/brace/BraceHandler.java`, in the `handle` method, add after the after-middleware loop (after line 264 `result = after.apply(braceRequest, result);` and its closing brace) and before `writeResult(result, response, callback);` (line 266):

```java
// Add Vary header for htmx requests (caching correctness)
if ("true".equals(braceRequest.header("HX-Request"))) {
    result.header("Vary", "HX-Request");
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=HtmxTest -pl .`

Expected: All HtmxTest tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/larvalabs/brace/BraceHandler.java src/test/java/com/larvalabs/brace/HtmxTest.java
git commit -m "Add Vary: HX-Request header for htmx responses"
```

---

### Task 4: Rewrite OpsDashboard for server-side rendering with htmx

**Files:**
- Rewrite: `src/main/java/com/larvalabs/brace/OpsDashboard.java`

This is the largest task. The current `OpsDashboard.html()` returns an HTML shell with ~220 lines of JavaScript that fetches JSON and renders the DOM client-side. The new version renders HTML server-side from data objects and uses htmx for 5-second polling.

- [ ] **Step 1: Rewrite OpsDashboard.java**

Replace the entire contents of `src/main/java/com/larvalabs/brace/OpsDashboard.java` with a server-side rendering implementation. The method signature changes to accept data objects:

```java
package com.larvalabs.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class OpsDashboard {

    public static String html(String opsSecret, Stats stats, JobScheduler jobScheduler,
                              Mailer mailer, ErrorStore errorStore, Cache cache) {
        var sb = new StringBuilder();
        var now = Instant.now();

        // Gather data
        var statusCodes = stats.statusCodeCounts();
        long totalReqs = statusCodes.values().stream().mapToLong(Long::longValue).sum();
        long errCount = statusCodes.getOrDefault(500, 0L) + statusCodes.getOrDefault(502, 0L) + statusCodes.getOrDefault(503, 0L);
        String errRate = totalReqs > 0 ? String.format("%.1f", (errCount * 100.0) / totalReqs) : "0.0";
        var runtime = Runtime.getRuntime();
        long heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long heapMax = runtime.maxMemory() / (1024 * 1024);
        var uptime = formatDuration(Duration.between(stats.startedAt(), now));
        var routeStats = stats.routeStats().entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue().avgLatencyMs(), a.getValue().avgLatencyMs()))
            .limit(5).toList();
        var minutes = stats.minuteSnapshots();
        var recentErrors = stats.recentErrors();
        var jobStatuses = jobScheduler != null ? jobScheduler.getStatuses() : List.<JobScheduler.JobStatus>of();
        var rateLimiterStats = RateLimiter.allStats();
        List<Map<String, Object>> unresolvedErrors = List.of();
        List<Map<String, Object>> resolvedErrors = List.of();
        if (errorStore != null) {
            unresolvedErrors = errorStore.list(null);
            resolvedErrors = errorStore.list("resolved");
        }

        // Page head
        sb.append("""
            <!DOCTYPE html>
            <html>
            <head>
            <title>Brace Dashboard</title>
            <meta charset="UTF-8">
            <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { background: #1a1a2e; color: #e0e0e0; font-family: 'Menlo', 'Consolas', monospace; font-size: 13px; padding: 20px; }
            h1 { color: #e94560; font-size: 20px; margin-bottom: 4px; }
            h2 { color: #0f3460; background: #e94560; display: inline-block; padding: 2px 10px; margin: 16px 0 8px 0; font-size: 13px; }
            .header { margin-bottom: 16px; border-bottom: 1px solid #333; padding-bottom: 12px; }
            .header span { color: #888; margin-right: 20px; }
            .stats-row { display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
            .stat-card { background: #16213e; border: 1px solid #0f3460; padding: 12px 20px; min-width: 140px; }
            .stat-card .label { color: #888; font-size: 11px; text-transform: uppercase; }
            .stat-card .value { color: #e94560; font-size: 22px; font-weight: bold; margin-top: 4px; }
            table { border-collapse: collapse; width: 100%; margin-bottom: 16px; }
            th { text-align: left; color: #e94560; border-bottom: 1px solid #333; padding: 6px 12px; font-size: 11px; text-transform: uppercase; }
            td { padding: 5px 12px; border-bottom: 1px solid #222; }
            tr:hover td { background: #16213e; }
            .sparkline { display: flex; align-items: flex-end; gap: 2px; height: 60px; margin: 8px 0; }
            .sparkline .bar { background: #e94560; min-width: 6px; flex: 1; }
            .sparkline .bar:hover { background: #ff6b81; }
            .error-text { color: #ff6b6b; }
            .ok-text { color: #51cf66; }
            .muted { color: #666; }
            .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
            @media (max-width: 800px) { .two-col { grid-template-columns: 1fr; } }
            .btn { background: #0f3460; color: #e0e0e0; border: 1px solid #e94560; padding: 4px 12px; cursor: pointer; font-family: inherit; font-size: 12px; }
            .btn:hover { background: #e94560; color: #0f3460; }
            .btn-sm { padding: 2px 8px; font-size: 11px; }
            .section-header { display: flex; align-items: center; gap: 12px; }
            .stack-trace { max-height: 200px; overflow-y: auto; background: #111; padding: 8px; margin-top: 4px; font-size: 11px; white-space: pre-wrap; word-break: break-all; border: 1px solid #222; }
            .expandable { cursor: pointer; }
            .expandable:hover { color: #e94560; }
            .tab-bar { display: flex; gap: 0; margin-bottom: 0; }
            .tab { background: #16213e; border: 1px solid #333; border-bottom: none; padding: 4px 16px; cursor: pointer; color: #888; }
            .tab.active { background: #1a1a2e; color: #e94560; border-color: #e94560; border-bottom: 1px solid #1a1a2e; margin-bottom: -1px; z-index: 1; position: relative; }
            .tab-content { border-top: 1px solid #e94560; padding-top: 8px; }
            </style>
            <script src="/__brace/htmx.min.js"></script>
            </head>
            <body>
            """);

        // Dashboard content — this is the div that htmx polls and replaces
        sb.append("<div id=\"dashboard-content\" hx-get=\"/ops/dashboard?key=").append(esc(opsSecret))
          .append("\" hx-select=\"#dashboard-content\" hx-target=\"this\" hx-swap=\"outerHTML\" hx-trigger=\"every 5s\">\n");

        // Header
        sb.append("<div class=\"header\">");
        sb.append("<h1>Brace Dashboard</h1>");
        sb.append("<span>Uptime: ").append(esc(uptime)).append("</span>");
        sb.append("<span>Java: ").append(esc(System.getProperty("java.version"))).append("</span>");
        sb.append("<span>Started: ").append(esc(stats.startedAt().toString())).append("</span>");
        sb.append("</div>\n");

        // Stat cards
        sb.append("<div class=\"stats-row\">");
        statCard(sb, "Requests", String.valueOf(totalReqs));
        statCard(sb, "Error Rate", errRate + "%");
        statCard(sb, "Heap Used", heapUsed + " MB");
        statCard(sb, "Heap Max", heapMax + " MB");
        if (cache != null) {
            statCard(sb, "Cache", cache.size() + " entries");
            long hits = cache.hits(), misses = cache.misses();
            String hitRate = (hits + misses) > 0 ? ((hits * 100) / (hits + misses)) + "%" : "-";
            statCard(sb, "Hit Rate", hitRate);
        }
        if (mailer != null) {
            statCard(sb, "Emails", String.valueOf(mailer.sentCount()));
            if (mailer.failCount() > 0) {
                statCard(sb, "Mail Failures", String.valueOf(mailer.failCount()));
            }
        }
        sb.append("</div>\n");

        // Sparkline
        if (!minutes.isEmpty()) {
            sb.append("<h2>Requests / Minute</h2>\n");
            long maxReq = Math.max(1, minutes.stream().mapToLong(Stats.MinuteSnapshot::requests).max().orElse(1));
            sb.append("<div class=\"sparkline\">");
            for (var m : minutes) {
                double pct = (m.requests() * 100.0) / maxReq;
                sb.append("<div class=\"bar\" style=\"height:").append(String.format("%.0f", Math.max(2, pct)))
                  .append("%\" title=\"").append(m.requests()).append(" reqs, ")
                  .append(String.format("%.1f", m.avgLatencyMs())).append(" ms avg @ ")
                  .append(m.ts()).append("\"></div>");
            }
            sb.append("</div>\n");
        }

        sb.append("<div class=\"two-col\">\n");

        // Slowest routes
        sb.append("<div><h2>Slowest Routes</h2>");
        sb.append("<table><tr><th>Route</th><th>Count</th><th>Avg ms</th></tr>");
        for (var e : routeStats) {
            sb.append("<tr><td>").append(esc(e.getKey())).append("</td><td>")
              .append(e.getValue().count()).append("</td><td>")
              .append(String.format("%.2f", e.getValue().avgLatencyMs())).append("</td></tr>");
        }
        sb.append("</table></div>\n");

        // Recent in-memory errors
        sb.append("<div><h2>Recent Errors (In-Memory)</h2>");
        if (recentErrors.isEmpty()) {
            sb.append("<p class=\"ok-text\">No errors</p>");
        } else {
            sb.append("<table><tr><th>Type</th><th>Route</th><th>Count</th><th>Last Seen</th></tr>");
            for (var e : recentErrors) {
                sb.append("<tr><td class=\"error-text\">").append(esc(e.type)).append("</td><td>")
                  .append(esc(e.route != null ? e.route : "-")).append("</td><td>")
                  .append(e.count).append("</td><td>")
                  .append(esc(e.lastSeen != null ? e.lastSeen.toString() : "-")).append("</td></tr>");
            }
            sb.append("</table>");
        }
        sb.append("</div>\n");

        sb.append("</div>\n"); // two-col

        // Persisted error tracking
        if (errorStore != null) {
            sb.append("<div class=\"section-header\"><h2>Error Tracking</h2></div>\n");
            sb.append("<div class=\"tab-bar\">");
            sb.append("<div class=\"tab active\" onclick=\"showErrorTab('unresolved')\">Unresolved (")
              .append(unresolvedErrors.size()).append(")</div>");
            sb.append("<div class=\"tab\" onclick=\"showErrorTab('resolved')\">Resolved (")
              .append(resolvedErrors.size()).append(")</div>");
            sb.append("</div>\n");

            // Unresolved tab
            sb.append("<div id=\"tab-unresolved\" class=\"tab-content\" style=\"display:block\">");
            renderPersistedErrors(sb, unresolvedErrors, opsSecret, false);
            sb.append("</div>\n");

            // Resolved tab
            sb.append("<div id=\"tab-resolved\" class=\"tab-content\" style=\"display:none\">");
            renderPersistedErrors(sb, resolvedErrors, opsSecret, true);
            sb.append("</div>\n");
        }

        // Scheduled jobs
        if (!jobStatuses.isEmpty()) {
            sb.append("<h2>Scheduled Jobs</h2>");
            sb.append("<table><tr><th>Name</th><th>Schedule</th><th>Status</th><th>Last Run</th><th>Duration</th><th>Failures</th></tr>");
            for (var j : jobStatuses) {
                String statusCls = "ok".equals(j.lastStatus()) ? "ok-text" : "error".equals(j.lastStatus()) ? "error-text" : "muted";
                sb.append("<tr><td>").append(esc(j.name())).append("</td><td>").append(esc(j.schedule())).append("</td>");
                sb.append("<td class=\"").append(statusCls).append("\">").append(esc(j.lastStatus() != null ? j.lastStatus() : "pending")).append("</td>");
                sb.append("<td>").append(j.lastRun() != null ? esc(j.lastRun().toString()) : "-").append("</td>");
                sb.append("<td>").append(j.lastDurationMs()).append(" ms</td>");
                sb.append("<td>").append(j.failCount()).append("</td></tr>");
            }
            sb.append("</table>\n");
        }

        // Rate limiters
        if (!rateLimiterStats.isEmpty()) {
            sb.append("<h2>Rate Limiters</h2>");
            sb.append("<table><tr><th>Limiter</th><th>Allowed</th><th>Blocked</th><th>Active Windows</th><th>Limit</th></tr>");
            for (var rl : rateLimiterStats) {
                long allowed = ((Number) rl.get("allowed")).longValue();
                long blocked = ((Number) rl.get("blocked")).longValue();
                String blockPct = (allowed + blocked) > 0 ? String.format("%.1f", (blocked * 100.0) / (allowed + blocked)) : "0.0";
                sb.append("<tr><td>").append(esc((String) rl.get("label"))).append("</td>");
                sb.append("<td>").append(allowed).append("</td>");
                sb.append("<td class=\"").append(blocked > 0 ? "error-text" : "").append("\">")
                  .append(blocked).append(" (").append(blockPct).append("%)</td>");
                sb.append("<td>").append(rl.get("activeWindows")).append("</td>");
                sb.append("<td>").append(rl.get("maxRequests")).append("/").append(rl.get("windowSeconds")).append("s</td></tr>");
            }
            sb.append("</table>\n");
        }

        // Cache details
        if (cache != null) {
            sb.append("<div class=\"section-header\"><h2>Cache</h2>");
            sb.append("<button class=\"btn btn-sm\" hx-post=\"/ops/cache/clear?key=").append(esc(opsSecret))
              .append("\" hx-target=\"#dashboard-content\" hx-select=\"#dashboard-content\" hx-swap=\"outerHTML\">Clear All</button></div>");
            sb.append("<div class=\"stats-row\">");
            statCard(sb, "Entries", String.valueOf(cache.size()));
            statCard(sb, "Counters", String.valueOf(cache.counterCount()));
            statCard(sb, "Tags", String.valueOf(cache.tagCount()));
            statCard(sb, "Hits", String.valueOf(cache.hits()));
            statCard(sb, "Misses", String.valueOf(cache.misses()));
            statCard(sb, "Evictions", String.valueOf(cache.evictions()));
            sb.append("</div>\n");
        }

        // Status codes
        sb.append("<h2>Status Codes</h2>");
        sb.append("<table><tr><th>Code</th><th>Count</th></tr>");
        statusCodes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
            sb.append("<tr><td>").append(e.getKey()).append("</td><td>").append(e.getValue()).append("</td></tr>")
        );
        sb.append("</table>\n");

        sb.append("</div>\n"); // dashboard-content

        // Minimal JS for tab switching and stack trace expand/collapse (no rendering)
        sb.append("""
            <script>
            function showErrorTab(tab) {
                document.getElementById('tab-unresolved').style.display = tab === 'unresolved' ? 'block' : 'none';
                document.getElementById('tab-resolved').style.display = tab === 'resolved' ? 'block' : 'none';
                document.querySelectorAll('.tab').forEach((el, i) => {
                    el.classList.toggle('active', (i === 0 && tab === 'unresolved') || (i === 1 && tab === 'resolved'));
                });
            }
            function toggleTrace(el) {
                var row = el.closest('tr').nextElementSibling;
                row.style.display = row.style.display === 'none' ? 'table-row' : 'none';
            }
            </script>
            """);

        sb.append("</body></html>");
        return sb.toString();
    }

    private static void renderPersistedErrors(StringBuilder sb, List<Map<String, Object>> errors,
                                               String opsSecret, boolean resolved) {
        if (errors.isEmpty()) {
            sb.append("<p class=\"").append(resolved ? "muted" : "ok-text").append("\">None</p>");
            return;
        }
        sb.append("<table><tr><th>Type</th><th>Route</th><th>Count</th><th>First Seen</th><th>Last Seen</th><th></th></tr>");
        for (var e : errors) {
            long id = ((Number) e.get("id")).longValue();
            sb.append("<tr>");
            sb.append("<td class=\"error-text expandable\" onclick=\"toggleTrace(this)\">")
              .append(esc(str(e.get("errorType")))).append("</td>");
            sb.append("<td>").append(esc(str(e.get("route"), "-"))).append("</td>");
            sb.append("<td>").append(e.get("occurrenceCount")).append("</td>");
            sb.append("<td>").append(esc(str(e.get("firstSeen"), "-"))).append("</td>");
            sb.append("<td>").append(esc(str(e.get("lastSeen"), "-"))).append("</td>");
            if (!resolved) {
                sb.append("<td><button class=\"btn btn-sm\" hx-post=\"/ops/errors/").append(id)
                  .append("/resolve?key=").append(esc(opsSecret))
                  .append("\" hx-target=\"#dashboard-content\" hx-select=\"#dashboard-content\" hx-swap=\"outerHTML\">Resolve</button></td>");
            } else {
                sb.append("<td class=\"muted\">").append(esc(str(e.get("resolvedAt"), ""))).append("</td>");
            }
            sb.append("</tr>");
            sb.append("<tr style=\"display:none\"><td colspan=\"6\"><div class=\"stack-trace\">")
              .append(esc(str(e.get("stackTrace"), "No stack trace")))
              .append("</div><div style=\"margin-top:4px;color:#888\">")
              .append(esc(str(e.get("message"), ""))).append("</div></td></tr>");
        }
        sb.append("</table>");
    }

    private static void statCard(StringBuilder sb, String label, String value) {
        sb.append("<div class=\"stat-card\"><div class=\"label\">").append(esc(label))
          .append("</div><div class=\"value\">").append(esc(value)).append("</div></div>");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static String str(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }

    private static String formatDuration(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long mins = d.toMinutesPart();
        if (days > 0) return days + "d " + hours + "h " + mins + "m";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `mvn compile -pl .`

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/larvalabs/brace/OpsDashboard.java
git commit -m "Rewrite OpsDashboard for server-side rendering with htmx polling"
```

---

### Task 5: Update OpsHandler to pass data and return dashboard from actions

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/OpsHandler.java`

- [ ] **Step 1: Update `dashboard()` to pass data to OpsDashboard**

In `src/main/java/com/larvalabs/brace/OpsHandler.java`, change the `dashboard` method from:

```java
public Result dashboard(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    return Result.html(OpsDashboard.html(opsSecret));
}
```

To:

```java
public Result dashboard(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    return Result.html(OpsDashboard.html(opsSecret, stats, jobScheduler, mailer, errorStore, cache));
}
```

- [ ] **Step 2: Update `resolveError()` to return dashboard HTML**

Change from:

```java
public Result resolveError(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    if (errorStore == null) return Result.notFound();
    long id = req.longParam("id");
    var resolved = errorStore.resolve(id);
    if (resolved == null) return Result.notFound();
    return Json.of(resolved);
}
```

To:

```java
public Result resolveError(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    if (errorStore == null) return Result.notFound();
    long id = req.longParam("id");
    errorStore.resolve(id);
    return dashboard(req);
}
```

- [ ] **Step 3: Update `clearCache()` to return dashboard HTML**

Change from:

```java
public Result clearCache(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    if (cache == null) return Json.of(Map.of("status", "no cache configured"));
    cache.clear();
    return Json.of(Map.of("status", "cleared"));
}
```

To:

```java
public Result clearCache(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    if (cache == null) return Result.notFound();
    cache.clear();
    return dashboard(req);
}
```

- [ ] **Step 4: Remove `formatDuration` from OpsHandler**

Delete the `formatDuration` method from OpsHandler — it's now in OpsDashboard. (The `status()` endpoint still uses it, so check first. Looking at the code: yes, `status()` on line 46 calls `formatDuration`. Keep it in OpsHandler as well — both classes need it independently.)

Actually, **keep `formatDuration` in OpsHandler** — the `status()` JSON endpoint still uses it. OpsDashboard has its own copy. No change needed here.

- [ ] **Step 5: Verify compilation**

Run: `mvn compile -pl .`

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/larvalabs/brace/OpsHandler.java
git commit -m "Update OpsHandler to pass data to dashboard and return HTML from actions"
```

---

### Task 6: Update tests and verify

**Files:**
- Modify: `src/test/java/com/larvalabs/brace/OpsIntegrationTest.java`

- [ ] **Step 1: Update dashboard test assertions**

The existing tests in `OpsIntegrationTest.java` check for JS function names (`clearCache`, `resolveError`) that no longer exist. Update the assertions to match the new server-rendered output.

In `src/test/java/com/larvalabs/brace/OpsIntegrationTest.java`, change the `dashboardIncludesCacheSection` test (line 171-175):

```java
@Test
void dashboardIncludesCacheSection() throws Exception {
    var response = cacheGet("/ops/dashboard");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("Clear All"));
}
```

Change the `dashboardIncludesErrorTracking` test (line 177-181):

```java
@Test
void dashboardIncludesErrorTracking() throws Exception {
    var response = cacheGet("/ops/dashboard");
    assertTrue(response.body().contains("Error Tracking"));
    assertTrue(response.body().contains("Resolve"));
}
```

The test `clearCacheWithValidKey` (line 159-168) checks for JSON response `"cleared"` — update it since `clearCache` now returns dashboard HTML:

```java
@Test
void clearCacheWithValidKey() throws Exception {
    var response = cachePost("/ops/cache/clear");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("Brace Dashboard"));

    // Verify cache is empty in status
    var status = cacheGet("/ops/status");
    assertTrue(status.body().contains("\"entries\":0"));
}
```

- [ ] **Step 2: Add test for htmx script tag in dashboard**

Add a new test to verify htmx is included:

```java
@Test
void dashboardIncludesHtmxScript() throws Exception {
    var response = get("/ops/dashboard?key=test-ops-key");
    assertTrue(response.body().contains("/__brace/htmx.min.js"));
}
```

- [ ] **Step 3: Add test for htmx polling attributes**

```java
@Test
void dashboardHasHtmxPolling() throws Exception {
    var response = get("/ops/dashboard?key=test-ops-key");
    assertTrue(response.body().contains("hx-get="));
    assertTrue(response.body().contains("hx-trigger=\"every 5s\""));
}
```

- [ ] **Step 4: Run all tests**

Run: `mvn test -pl .`

Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/larvalabs/brace/OpsIntegrationTest.java
git commit -m "Update ops dashboard tests for server-rendered htmx output"
```

---

### Task 7: Final verification

- [ ] **Step 1: Run the full test suite**

Run: `mvn test -pl .`

Expected: All 138+ tests pass (count may increase with new HtmxTest).

- [ ] **Step 2: Verify the sample app still compiles (if applicable)**

Run: `mvn compile -pl . -pl sample` (if sample module exists, otherwise skip)

- [ ] **Step 3: Manual smoke test (optional)**

If you want to verify the dashboard visually, start the sample app and visit `/ops/dashboard?key=<configured-key>`. The dashboard should render server-side and poll every 5 seconds without any visible page flicker.

- [ ] **Step 4: Final commit with all changes if any stragglers**

Run `git status` to check for uncommitted files. If all clean, done.
