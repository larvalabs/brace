# `brace check` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `brace check` CLI command that runs all production health checks in one shot and returns a structured verdict for AI agents acting as on-call.

**Architecture:** Pure CLI-side command — no new server endpoints. `CliCheck.java` fetches data from the three existing ops endpoints (`/ops/status`, `/ops/errors`, `/ops/logs`), applies configurable thresholds, and outputs a structured pass/warn/fail verdict. A `CheckThresholds` record reads `check.*` keys from `.brace` config with sensible defaults.

**Tech Stack:** Java 21, Jackson (JsonNode), JUnit 5, existing Brace test infrastructure (`Brace.app().port(0).ops(...)`)

**Spec:** `docs/superpowers/specs/2026-04-15-brace-check-design.md`

---

### Task 1: CheckThresholds record and config parsing

**Files:**
- Create: `src/main/java/com/larvalabs/brace/CheckThresholds.java`
- Modify: `src/main/java/com/larvalabs/brace/CliConfig.java`
- Test: `src/test/java/com/larvalabs/brace/CheckThresholdsTest.java`

- [ ] **Step 1: Write the failing test for default thresholds**

```java
package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CheckThresholdsTest {

    @Test
    void defaultsWhenNoConfigKeys() {
        var t = CheckThresholds.fromConfig(Map.of());
        assertEquals(500, t.slowRouteMs());
        assertEquals(70, t.heapWarnPercent());
        assertEquals(80, t.heapFailPercent());
        assertEquals(50, t.gcPauseMs());
        assertEquals(0.5, t.cacheHitRate(), 0.001);
        assertEquals(30, t.logWindowMinutes());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CheckThresholdsTest -pl . -q`
Expected: FAIL — `CheckThresholds` class does not exist

- [ ] **Step 3: Create CheckThresholds record**

Create `src/main/java/com/larvalabs/brace/CheckThresholds.java`:

```java
package com.larvalabs.brace;

import java.util.Map;

public record CheckThresholds(
    int slowRouteMs,
    int heapWarnPercent,
    int heapFailPercent,
    int gcPauseMs,
    double cacheHitRate,
    int logWindowMinutes
) {
    public static final CheckThresholds DEFAULTS = new CheckThresholds(500, 70, 80, 50, 0.5, 30);

    public static CheckThresholds fromConfig(Map<String, String> values) {
        return new CheckThresholds(
            intOr(values, "check.slow_route_ms", DEFAULTS.slowRouteMs),
            intOr(values, "check.heap_warn_percent", DEFAULTS.heapWarnPercent),
            intOr(values, "check.heap_fail_percent", DEFAULTS.heapFailPercent),
            intOr(values, "check.gc_pause_ms", DEFAULTS.gcPauseMs),
            doubleOr(values, "check.cache_hit_rate", DEFAULTS.cacheHitRate),
            intOr(values, "check.log_window_minutes", DEFAULTS.logWindowMinutes)
        );
    }

    private static int intOr(Map<String, String> m, String key, int def) {
        String v = m.get(key);
        if (v == null) return def;
        return Integer.parseInt(v);
    }

    private static double doubleOr(Map<String, String> m, String key, double def) {
        String v = m.get(key);
        if (v == null) return def;
        return Double.parseDouble(v);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CheckThresholdsTest -pl . -q`
Expected: PASS

- [ ] **Step 5: Write test for custom overrides**

Add to `CheckThresholdsTest.java`:

```java
@Test
void overridesFromConfig() {
    var t = CheckThresholds.fromConfig(Map.of(
        "check.slow_route_ms", "1000",
        "check.heap_fail_percent", "90",
        "check.cache_hit_rate", "0.8"
    ));
    assertEquals(1000, t.slowRouteMs());
    assertEquals(70, t.heapWarnPercent());  // not overridden
    assertEquals(90, t.heapFailPercent());
    assertEquals(0.8, t.cacheHitRate(), 0.001);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CheckThresholdsTest -pl . -q`
Expected: PASS (implementation already handles this)

- [ ] **Step 7: Add checkThresholds() to CliConfig**

In `src/main/java/com/larvalabs/brace/CliConfig.java`, the `merge()` method already reads all key=value pairs from `.brace` files. We need to expose those raw values so `CheckThresholds.fromConfig()` can read the `check.*` keys.

Change the `CliConfig` record to include the raw config values:

```java
public record CliConfig(String url, String keyPath, String authorizedKeysPath, String env,
                        Map<String, String> rawValues) {
```

Update the `load()` method's return to pass `values`:

```java
return new CliConfig(url, keyPath, authKeys, env, Map.copyOf(values));
```

Add a convenience method at the end of the record:

```java
public CheckThresholds checkThresholds() {
    return CheckThresholds.fromConfig(rawValues);
}
```

- [ ] **Step 8: Run existing CliConfig tests to verify no regressions**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CliConfigTest -pl . -q`
Expected: PASS — existing tests may need a minor update if they construct `CliConfig` directly (check and fix if needed)

- [ ] **Step 9: Commit**

```bash
cd /Users/matt/code/brace && git add src/main/java/com/larvalabs/brace/CheckThresholds.java src/test/java/com/larvalabs/brace/CheckThresholdsTest.java src/main/java/com/larvalabs/brace/CliConfig.java src/test/java/com/larvalabs/brace/CliConfigTest.java
git commit -m "feat: add CheckThresholds record with configurable defaults"
```

---

### Task 2: CliCheck core — check evaluation logic

**Files:**
- Create: `src/main/java/com/larvalabs/brace/CliCheck.java`
- Test: `src/test/java/com/larvalabs/brace/CliCheckTest.java`

This task implements the pure check evaluation function that takes parsed JSON data and thresholds and returns a structured result. No HTTP calls — just logic. All nine checks.

- [ ] **Step 1: Write failing test for a fully healthy app**

```java
package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CliCheckTest {

    static JsonNode json(Object obj) {
        return Json.mapper().valueToTree(obj);
    }

    static JsonNode healthyStatus() {
        var status = new LinkedHashMap<String, Object>();
        status.put("app", Map.of(
            "uptime", "2h 15m",
            "startedAt", "2026-04-15T10:00:00Z",
            "javaVersion", "21.0.1"
        ));
        status.put("http", Map.of(
            "statusCodes", Map.of("200", 100, "302", 5),
            "slowestRoutes", List.of(
                Map.of("route", "GET /api/posts", "count", 50, "avgMs", 45.2)
            )
        ));
        status.put("jvm", Map.of(
            "heap", Map.of("usedMB", 100, "maxMB", 512),
            "gc", Map.of("avgPauseMs", 5.0)
        ));
        status.put("errors", Map.of("recent", List.of()));
        status.put("jobs", Map.of("scheduled", List.of(
            Map.of("name", "cleanup", "lastStatus", "ok", "failCount", 0)
        )));
        status.put("cache", Map.of(
            "entries", 50, "hits", 800, "misses", 200
        ));
        return json(status);
    }

    @Test
    void healthyAppPassesAllChecks() {
        var result = CliCheck.evaluate(
            healthyStatus(), json(List.of()), json(List.of()),
            CheckThresholds.DEFAULTS);
        assertTrue(result.healthy());
        assertEquals("All checks passed", result.summary());
        for (var check : result.checks()) {
            assertEquals("pass", check.status(), check.name() + " should pass");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CliCheckTest#healthyAppPassesAllChecks -pl . -q`
Expected: FAIL — `CliCheck` class does not exist

- [ ] **Step 3: Create CliCheck with the evaluate method and inner types**

Create `src/main/java/com/larvalabs/brace/CliCheck.java`:

```java
package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public class CliCheck {

    private CliCheck() {}

    public record CheckResult(boolean healthy, String summary, List<Check> checks) {}

    public record Check(String name, String status, String message,
                        List<Map<String, Object>> details, String followUp) {
        public Check(String name, String status, String message) {
            this(name, status, message, null, null);
        }
    }

    public static CheckResult evaluate(JsonNode status, JsonNode errors, JsonNode logs,
                                       CheckThresholds thresholds) {
        String env = "prod"; // followUp commands use this
        var checks = new ArrayList<Check>();

        checks.add(checkReachability(status));
        checks.add(checkErrors(status, errors, env));
        checks.add(checkHttp5xx(status));
        checks.add(checkSlowRoutes(status, thresholds));
        checks.add(checkHeap(status, thresholds));
        checks.add(checkGcPressure(status, thresholds));
        checks.add(checkJobs(status));
        checks.add(checkCache(status, thresholds));
        checks.add(checkRecentLogs(logs, thresholds, env));

        boolean healthy = checks.stream().noneMatch(c -> "fail".equals(c.status()));
        String summary = buildSummary(checks);
        return new CheckResult(healthy, summary, checks);
    }

    static Check checkReachability(JsonNode status) {
        var app = status.path("app");
        String uptime = app.path("uptime").asText("unknown");
        String java = app.path("javaVersion").asText("unknown");

        // Warn if uptime contains only seconds or minutes < 5
        String startedAt = app.path("startedAt").asText("");
        if (!startedAt.isEmpty()) {
            try {
                var started = java.time.Instant.parse(startedAt);
                long seconds = java.time.Duration.between(started, java.time.Instant.now()).getSeconds();
                if (seconds < 300) {
                    return new Check("reachability", "warn",
                        "App up for " + uptime + " (recent restart), Java " + java,
                        null, null);
                }
            } catch (Exception ignored) {}
        }
        return new Check("reachability", "pass", "App up for " + uptime + ", Java " + java);
    }

    static Check checkErrors(JsonNode status, JsonNode errors, String env) {
        int count = errors.size();
        if (count == 0) {
            return new Check("errors", "pass", "No unresolved errors");
        }
        var details = new ArrayList<Map<String, Object>>();
        for (var e : errors) {
            var d = new LinkedHashMap<String, Object>();
            d.put("type", e.path("errorType").asText(e.path("type").asText("?")));
            d.put("route", e.path("route").asText("?"));
            d.put("count", e.path("occurrenceCount").asInt(e.path("count").asInt(1)));
            if (e.has("id")) d.put("id", e.path("id").asText());
            details.add(d);
        }
        return new Check("errors", "fail",
            count + " unresolved error" + (count == 1 ? "" : "s"),
            details, "brace errors --env " + env + " --json");
    }

    static Check checkHttp5xx(JsonNode status) {
        var codes = status.path("http").path("statusCodes");
        int total5xx = 0;
        int totalRequests = 0;
        var it = codes.fields();
        while (it.hasNext()) {
            var entry = it.next();
            int code = Integer.parseInt(entry.getKey());
            int count = entry.getValue().asInt(0);
            totalRequests += count;
            if (code >= 500) total5xx += count;
        }
        if (total5xx > 0) {
            return new Check("http_5xx", "fail",
                total5xx + " server error" + (total5xx == 1 ? "" : "s") + " in " + totalRequests + " requests");
        }
        return new Check("http_5xx", "pass",
            "0 server errors in " + totalRequests + " request" + (totalRequests == 1 ? "" : "s"));
    }

    static Check checkSlowRoutes(JsonNode status, CheckThresholds thresholds) {
        var routes = status.path("http").path("slowestRoutes");
        var slow = new ArrayList<Map<String, Object>>();
        for (var r : routes) {
            double avgMs = r.path("avgMs").asDouble(0);
            if (avgMs > thresholds.slowRouteMs()) {
                slow.add(Map.of(
                    "route", r.path("route").asText(),
                    "avgMs", avgMs,
                    "count", r.path("count").asInt()
                ));
            }
        }
        if (!slow.isEmpty()) {
            return new Check("slow_routes", "warn",
                slow.size() + " route" + (slow.size() == 1 ? "" : "s") + " over " + thresholds.slowRouteMs() + "ms",
                slow, null);
        }
        return new Check("slow_routes", "pass",
            "All routes under " + thresholds.slowRouteMs() + "ms");
    }

    static Check checkHeap(JsonNode status, CheckThresholds thresholds) {
        var heap = status.path("jvm").path("heap");
        long usedMB = heap.path("usedMB").asLong(0);
        long maxMB = heap.path("maxMB").asLong(1);
        int percent = maxMB > 0 ? (int) (usedMB * 100 / maxMB) : 0;
        String msg = usedMB + "MB / " + maxMB + "MB (" + percent + "%)";
        if (percent >= thresholds.heapFailPercent()) {
            return new Check("heap", "fail", msg);
        }
        if (percent >= thresholds.heapWarnPercent()) {
            return new Check("heap", "warn", msg);
        }
        return new Check("heap", "pass", msg);
    }

    static Check checkGcPressure(JsonNode status, CheckThresholds thresholds) {
        double avgPause = status.path("jvm").path("gc").path("avgPauseMs").asDouble(0);
        String msg = "Avg pause " + (int) avgPause + "ms";
        if (avgPause > thresholds.gcPauseMs()) {
            return new Check("gc_pressure", "fail", msg);
        }
        return new Check("gc_pressure", "pass", msg);
    }

    static Check checkJobs(JsonNode status) {
        var scheduled = status.path("jobs").path("scheduled");
        if (scheduled.isMissingNode() || scheduled.size() == 0) {
            return new Check("jobs", "pass", "No scheduled jobs");
        }
        int total = scheduled.size();
        var failing = new ArrayList<Map<String, Object>>();
        var warned = new ArrayList<Map<String, Object>>();
        for (var j : scheduled) {
            String lastStatus = j.path("lastStatus").asText("ok");
            int failCount = j.path("failCount").asInt(0);
            if (!"ok".equals(lastStatus)) {
                var d = new LinkedHashMap<String, Object>();
                d.put("name", j.path("name").asText());
                d.put("lastStatus", lastStatus);
                d.put("failCount", failCount);
                String lastError = j.path("lastError").asText(null);
                if (lastError != null) d.put("lastError", lastError);
                failing.add(d);
            } else if (failCount > 0) {
                var d = new LinkedHashMap<String, Object>();
                d.put("name", j.path("name").asText());
                d.put("failCount", failCount);
                warned.add(d);
            }
        }
        if (!failing.isEmpty()) {
            return new Check("jobs", "fail",
                failing.size() + " of " + total + " job" + (total == 1 ? "" : "s") + " failing",
                failing, null);
        }
        if (!warned.isEmpty()) {
            return new Check("jobs", "warn",
                warned.size() + " job" + (warned.size() == 1 ? "" : "s") + " with prior failures (currently ok)",
                warned, null);
        }
        return new Check("jobs", "pass",
            total + " job" + (total == 1 ? "" : "s") + ", all ok");
    }

    static Check checkCache(JsonNode status, CheckThresholds thresholds) {
        var cache = status.path("cache");
        if (cache.isMissingNode()) {
            return new Check("cache", "pass", "Cache not configured");
        }
        long hits = cache.path("hits").asLong(0);
        long misses = cache.path("misses").asLong(0);
        long total = hits + misses;
        if (total == 0) {
            return new Check("cache", "pass", "Cache active, no requests yet");
        }
        double hitRate = (double) hits / total;
        String msg = "Hit rate " + Math.round(hitRate * 100) + "% (" + hits + " hits / " + misses + " misses)";
        if (hitRate < thresholds.cacheHitRate()) {
            return new Check("cache", "warn", msg);
        }
        return new Check("cache", "pass", msg);
    }

    static Check checkRecentLogs(JsonNode logs, CheckThresholds thresholds, String env) {
        int errorCount = 0;
        int warnCount = 0;
        for (var entry : logs) {
            String level = entry.path("level").asText("INFO").toUpperCase();
            if ("ERROR".equals(level)) errorCount++;
            else if ("WARN".equals(level) || "WARNING".equals(level)) warnCount++;
        }
        String window = thresholds.logWindowMinutes() + "m";
        if (errorCount > 0) {
            return new Check("recent_logs", "fail",
                errorCount + " error-level entr" + (errorCount == 1 ? "y" : "ies") + " in last " + window,
                null, "brace logs --env " + env + " --since " + window + " --level error --json");
        }
        if (warnCount > 0) {
            return new Check("recent_logs", "warn",
                warnCount + " warn-level entr" + (warnCount == 1 ? "y" : "ies") + " in last " + window,
                null, "brace logs --env " + env + " --since " + window + " --level warn --json");
        }
        return new Check("recent_logs", "pass",
            "0 error-level entries in last " + window,
            null, null);
    }

    static String buildSummary(List<Check> checks) {
        var issues = new ArrayList<String>();
        for (var c : checks) {
            if ("fail".equals(c.status()) || "warn".equals(c.status())) {
                issues.add(c.message());
            }
        }
        if (issues.isEmpty()) return "All checks passed";
        return issues.size() + " issue" + (issues.size() == 1 ? "" : "s") + ": " + String.join(", ", issues);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CliCheckTest#healthyAppPassesAllChecks -pl . -q`
Expected: PASS

- [ ] **Step 5: Write failing tests for each check in fail/warn state**

Add to `CliCheckTest.java`:

```java
@Test
void errorsCheckFailsWithUnresolvedErrors() {
    var errors = json(List.of(
        Map.of("errorType", "NullPointerException", "route", "GET /posts/{id}",
               "occurrenceCount", 3, "id", 42)
    ));
    var check = CliCheck.checkErrors(json(Map.of()), errors, "prod");
    assertEquals("fail", check.status());
    assertEquals("1 unresolved error", check.message());
    assertEquals(1, check.details().size());
    assertEquals("NullPointerException", check.details().get(0).get("type"));
    assertEquals("brace errors --env prod --json", check.followUp());
}

@Test
void http5xxCheckFailsWith500s() {
    var status = json(Map.of("http", Map.of(
        "statusCodes", Map.of("200", 100, "500", 3)
    )));
    var check = CliCheck.checkHttp5xx(status);
    assertEquals("fail", check.status());
    assertTrue(check.message().contains("3 server errors"));
}

@Test
void slowRoutesWarnOverThreshold() {
    var status = json(Map.of("http", Map.of(
        "slowestRoutes", List.of(
            Map.of("route", "GET /search", "avgMs", 750.0, "count", 20),
            Map.of("route", "GET /api/posts", "avgMs", 45.0, "count", 100)
        )
    )));
    var check = CliCheck.checkSlowRoutes(status, CheckThresholds.DEFAULTS);
    assertEquals("warn", check.status());
    assertTrue(check.message().contains("1 route over 500ms"));
}

@Test
void heapWarnAndFail() {
    var warnStatus = json(Map.of("jvm", Map.of("heap", Map.of("usedMB", 380, "maxMB", 512))));
    assertEquals("warn", CliCheck.checkHeap(warnStatus, CheckThresholds.DEFAULTS).status());

    var failStatus = json(Map.of("jvm", Map.of("heap", Map.of("usedMB", 430, "maxMB", 512))));
    assertEquals("fail", CliCheck.checkHeap(failStatus, CheckThresholds.DEFAULTS).status());
}

@Test
void gcPressureFailOverThreshold() {
    var status = json(Map.of("jvm", Map.of("gc", Map.of("avgPauseMs", 75.0))));
    var check = CliCheck.checkGcPressure(status, CheckThresholds.DEFAULTS);
    assertEquals("fail", check.status());
}

@Test
void jobsFailWhenLastStatusNotOk() {
    var status = json(Map.of("jobs", Map.of("scheduled", List.of(
        Map.of("name", "cleanup", "lastStatus", "ok", "failCount", 0),
        Map.of("name", "email-digest", "lastStatus", "error", "failCount", 3,
               "lastError", "Connection refused")
    ))));
    var check = CliCheck.checkJobs(status);
    assertEquals("fail", check.status());
    assertTrue(check.message().contains("1 of 2 jobs failing"));
    assertEquals("Connection refused", check.details().get(0).get("lastError"));
}

@Test
void jobsWarnWhenFailCountPositiveButCurrentlyOk() {
    var status = json(Map.of("jobs", Map.of("scheduled", List.of(
        Map.of("name", "cleanup", "lastStatus", "ok", "failCount", 2)
    ))));
    var check = CliCheck.checkJobs(status);
    assertEquals("warn", check.status());
}

@Test
void cacheWarnWhenHitRateLow() {
    var status = json(Map.of("cache", Map.of("hits", 20, "misses", 80)));
    var check = CliCheck.checkCache(status, CheckThresholds.DEFAULTS);
    assertEquals("warn", check.status());
    assertTrue(check.message().contains("20%"));
}

@Test
void cachePassWhenNotConfigured() {
    var check = CliCheck.checkCache(json(Map.of()), CheckThresholds.DEFAULTS);
    assertEquals("pass", check.status());
}

@Test
void recentLogsFailWithErrorEntries() {
    var logs = json(List.of(
        Map.of("level", "ERROR", "message", "NPE in handler"),
        Map.of("level", "INFO", "message", "normal log")
    ));
    var check = CliCheck.checkRecentLogs(logs, CheckThresholds.DEFAULTS, "prod");
    assertEquals("fail", check.status());
    assertTrue(check.message().contains("1 error-level entry"));
    assertNotNull(check.followUp());
}

@Test
void recentLogsWarnWithWarnings() {
    var logs = json(List.of(
        Map.of("level", "WARN", "message", "slow query")
    ));
    var check = CliCheck.checkRecentLogs(logs, CheckThresholds.DEFAULTS, "prod");
    assertEquals("warn", check.status());
}

@Test
void summaryListsIssues() {
    var status = healthyStatus();
    var errors = json(List.of(
        Map.of("errorType", "NPE", "route", "GET /x", "occurrenceCount", 1)
    ));
    var result = CliCheck.evaluate(status, errors, json(List.of()), CheckThresholds.DEFAULTS);
    assertFalse(result.healthy());
    assertTrue(result.summary().contains("1 issue"));
    assertTrue(result.summary().contains("1 unresolved error"));
}
```

- [ ] **Step 6: Run all tests**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CliCheckTest -pl . -q`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
cd /Users/matt/code/brace && git add src/main/java/com/larvalabs/brace/CliCheck.java src/test/java/com/larvalabs/brace/CliCheckTest.java
git commit -m "feat: add CliCheck evaluation logic with all nine health checks"
```

---

### Task 3: CliCheck CLI wiring — run method with HTTP fetches and output

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/CliCheck.java`
- Modify: `src/main/java/com/larvalabs/brace/Cli.java`

- [ ] **Step 1: Add the `run` method to CliCheck**

This method handles auth, parallel HTTP fetches, output formatting, and exit codes. Add to `CliCheck.java`:

```java
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;

// Add these as static fields at the top of the class:
private static final HttpClient http = HttpClient.newHttpClient();

public static int run(Path projectDir, String[] args) throws Exception {
    var cfg = CliConfig.load(projectDir, args);
    var thresholds = cfg.checkThresholds();
    var mode = CliOutput.autoMode(
        CliCommands.hasFlag(args, "--json"),
        CliCommands.hasFlag(args, "--pretty"));

    String token;
    try {
        token = CliAuth.bearer(cfg, projectDir);
    } catch (Exception e) {
        if (mode == CliOutput.Mode.JSON) {
            var result = new CheckResult(false, "App unreachable: " + e.getMessage(), List.of(
                new Check("reachability", "fail", "Cannot connect: " + e.getMessage())
            ));
            System.out.println(CliOutput.json(result));
        } else {
            CliOutput.printError("Cannot reach " + cfg.url() + ": " + e.getMessage());
        }
        return 2;
    }

    // Fetch all three endpoints
    JsonNode status, errors, logs;
    try {
        status = fetchJson(cfg.url() + "/ops/status", token);
    } catch (Exception e) {
        if (mode == CliOutput.Mode.JSON) {
            var result = new CheckResult(false, "App unreachable: " + e.getMessage(), List.of(
                new Check("reachability", "fail", "Cannot connect: " + e.getMessage())
            ));
            System.out.println(CliOutput.json(result));
        } else {
            CliOutput.printError("Cannot reach " + cfg.url() + ": " + e.getMessage());
        }
        return 2;
    }

    try {
        errors = fetchJson(cfg.url() + "/ops/errors", token);
    } catch (Exception e) {
        errors = Json.mapper().valueToTree(List.of());
    }

    try {
        Instant since = Instant.now().minusSeconds(thresholds.logWindowMinutes() * 60L);
        logs = fetchJson(cfg.url() + "/ops/logs?level=warn&since_ts=" + since.toString(), token);
    } catch (Exception e) {
        logs = Json.mapper().valueToTree(List.of());
    }

    var result = evaluate(status, errors, logs, thresholds);

    if (mode == CliOutput.Mode.JSON) {
        System.out.println(CliOutput.json(result));
    } else {
        renderHuman(result);
    }

    return result.healthy() ? 0 : 1;
}

private static JsonNode fetchJson(String url, String token) throws Exception {
    var response = http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
        throw new RuntimeException("HTTP " + response.statusCode());
    }
    return Json.mapper().readTree(response.body());
}

private static void renderHuman(CheckResult result) {
    System.out.println();
    for (var check : result.checks()) {
        String icon = switch (check.status()) {
            case "pass" -> "✓";
            case "warn" -> "⚠";
            case "fail" -> "✗";
            default -> "?";
        };
        System.out.printf("%s %-15s %s%n", icon, check.name(), check.message());
        if (check.followUp() != null && !"pass".equals(check.status())) {
            System.out.printf("  %-15s → %s%n", "", check.followUp());
        }
    }
    System.out.println();
    long issues = result.checks().stream()
        .filter(c -> !"pass".equals(c.status())).count();
    if (issues == 0) {
        System.out.println("All checks passed");
    } else {
        System.out.println(issues + " issue" + (issues == 1 ? "" : "s") + " found");
    }
}
```

- [ ] **Step 2: Wire into Cli.java dispatch**

In `Cli.java`, add the `"check"` case to the switch in `dispatch()`:

```java
case "check"   -> requireProject(cwd, () -> CliCheck.run(cwd, rest));
```

Add it after the `"status"` line (line 37).

- [ ] **Step 3: Add help text for check command**

In `Cli.java` `printUsage()`, add after the `brace status` line:

```java
System.out.println("  brace check                 Run all health checks");
```

- [ ] **Step 4: Compile to verify wiring**

Run: `cd /Users/matt/code/brace && mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
cd /Users/matt/code/brace && git add src/main/java/com/larvalabs/brace/CliCheck.java src/main/java/com/larvalabs/brace/Cli.java
git commit -m "feat: wire brace check command into CLI dispatcher"
```

---

### Task 4: Integration test — end-to-end check against a running test app

**Files:**
- Create: `src/test/java/com/larvalabs/brace/CliCheckTest.java` (add integration tests to existing file)

- [ ] **Step 1: Write integration test**

Add to the bottom of `CliCheckTest.java`, following the same pattern as `CliCommandsTest.java`:

```java
// --- Integration tests (with real Brace app) ---

static Brace integrationApp;
static int integrationPort;
static OpsKeys.Keypair integrationKeypair;
@TempDir static Path integrationProjectDir;

@BeforeAll
static void startApp() throws Exception {
    integrationKeypair = OpsKeys.generateKeypair();
    Path keysFile = integrationProjectDir.resolve("ops-authorized-keys");
    Files.writeString(keysFile, integrationKeypair.publicKey() + " test\n");
    Files.writeString(integrationProjectDir.resolve("ops-private.key"),
        integrationKeypair.privateKey() + "\n" + integrationKeypair.publicKey() + "\n");

    integrationApp = Brace.app().port(0).ops(keysFile.toString());
    integrationApp.start();
    integrationPort = integrationApp.actualPort();

    Files.writeString(integrationProjectDir.resolve(".brace"),
        "ops.local.url=http://localhost:" + integrationPort + "\n");
    Files.writeString(integrationProjectDir.resolve(".brace.local"),
        "ops.key=" + integrationProjectDir.resolve("ops-private.key") + "\n");
}

@AfterAll
static void stopApp() throws Exception {
    if (integrationApp != null) integrationApp.stop();
}

@Test
void checkCommandReturnsZeroForHealthyApp() throws Exception {
    CliAuth.clearCache(integrationProjectDir);
    var bout = new ByteArrayOutputStream();
    var prev = System.out;
    System.setOut(new PrintStream(bout));
    try {
        int code = CliCheck.run(integrationProjectDir, new String[]{"--json"});
        assertEquals(0, code);
    } finally {
        System.setOut(prev);
    }
    String output = bout.toString();
    JsonNode result = Json.mapper().readTree(output);
    assertTrue(result.path("healthy").asBoolean());
    assertEquals("All checks passed", result.path("summary").asText());
}
```

Add imports at the top of the file:

```java
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
```

- [ ] **Step 2: Run the integration test**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CliCheckTest#checkCommandReturnsZeroForHealthyApp -pl . -q`
Expected: PASS

- [ ] **Step 3: Run all CliCheckTest tests together**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CliCheckTest -pl . -q`
Expected: ALL PASS

- [ ] **Step 4: Run full test suite to check for regressions**

Run: `cd /Users/matt/code/brace && mvn test -pl . -q`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
cd /Users/matt/code/brace && git add src/test/java/com/larvalabs/brace/CliCheckTest.java
git commit -m "test: add integration test for brace check command"
```

---

### Task 5: Pass the env name through to followUp commands

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/CliCheck.java`
- Modify: `src/test/java/com/larvalabs/brace/CliCheckTest.java`

The `evaluate()` method currently hardcodes `"prod"` for followUp commands. It should use the actual `--env` value so followUp commands work correctly.

- [ ] **Step 1: Write failing test**

Add to `CliCheckTest.java`:

```java
@Test
void followUpCommandsUseEnvName() {
    var errors = json(List.of(
        Map.of("errorType", "NPE", "route", "GET /x", "occurrenceCount", 1)
    ));
    var result = CliCheck.evaluate(healthyStatus(), errors, json(List.of()),
        CheckThresholds.DEFAULTS, "staging");
    var errCheck = result.checks().stream()
        .filter(c -> "errors".equals(c.name())).findFirst().orElseThrow();
    assertTrue(errCheck.followUp().contains("--env staging"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CliCheckTest#followUpCommandsUseEnvName -pl . -q`
Expected: FAIL — `evaluate()` doesn't accept an env parameter

- [ ] **Step 3: Add env parameter to evaluate()**

Change the `evaluate` signature to accept `String env`:

```java
public static CheckResult evaluate(JsonNode status, JsonNode errors, JsonNode logs,
                                   CheckThresholds thresholds, String env) {
```

Remove the hardcoded `String env = "prod";` line.

Add a backward-compatible overload:

```java
public static CheckResult evaluate(JsonNode status, JsonNode errors, JsonNode logs,
                                   CheckThresholds thresholds) {
    return evaluate(status, errors, logs, thresholds, "prod");
}
```

Update `run()` to pass `cfg.env()`:

```java
var result = evaluate(status, errors, logs, thresholds, cfg.env());
```

- [ ] **Step 4: Run all tests**

Run: `cd /Users/matt/code/brace && mvn test -Dtest=CliCheckTest -pl . -q`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
cd /Users/matt/code/brace && git add src/main/java/com/larvalabs/brace/CliCheck.java src/test/java/com/larvalabs/brace/CliCheckTest.java
git commit -m "feat: pass env name through to followUp commands in check output"
```

---

### Task 6: Documentation — update BRACE-AGENTS.md

**Files:**
- Modify: `/Users/matt/code/brace/BRACE-AGENTS.md`

- [ ] **Step 1: Add `brace check` to the CLI commands table**

In the `### CLI commands` section (around line 568-577), add a row to the table:

```
| `brace check [--env prod]` | Run all health checks, return structured verdict | 0 all pass / 1 issues / 2 unreachable |
```

- [ ] **Step 2: Add the quick health check section**

Add a new section before the existing "Runbook: general health check" section (before line 598). This becomes the new recommended starting point:

```markdown
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
```

- [ ] **Step 3: Update the "Runbook: general health check" section header**

Change "### Runbook: general health check" to "### Runbook: detailed status inspection" and add a note at the top:

```markdown
> **Note:** For most health checks, use `brace check` above. Use `brace status` directly when you need the full raw data for deeper investigation.
```

- [ ] **Step 4: Commit**

```bash
cd /Users/matt/code/brace && git add BRACE-AGENTS.md
git commit -m "docs: add brace check to agent ops runbook"
```

---

### Task 7: Update Brace CLI help text

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/Cli.java`

The global `brace --help` output should include `brace check` as a formatting-only change (the dispatch was already wired in Task 3).

- [ ] **Step 1: Verify the help text was added in Task 3**

Read `Cli.java` and confirm the `brace check` line is in `printUsage()`. If it was added in Task 3 Step 3, this is already done.

- [ ] **Step 2: Run full test suite**

Run: `cd /Users/matt/code/brace && mvn test -pl . -q`
Expected: ALL PASS

- [ ] **Step 3: Commit if any changes needed**

If no changes were needed (Task 3 already covered it), skip this commit.

---

Plan complete and saved to `docs/superpowers/plans/2026-04-15-brace-check.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?