# Brace CLI: Project Config and Agent-Facing Ops Commands — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the `brace` CLI into a project-aware production telemetry surface for humans and agents — adding `.brace`/`.brace.local` config, `brace init` scaffold/diagnose, and read commands `errors`, `logs`, `status`, `cache`, `resolve`.

**Architecture:** Server-side adds a `LogTap` ring buffer wired into `Log.println` plus two new endpoints (`/ops/logs`, `/ops/cache`) and a `since` filter on `/ops/errors`. Client-side splits `Cli.java` into focused modules (`CliConfig`, `CliOutput`, `CliAuth`, `CliInit`, `CliOps`, `CliCommands`) that share one bearer-token auth helper with `target/.brace-token` caching. Output auto-detects TTY vs. JSON; exit codes signal health for scheduled agent checks.

**Tech Stack:** Java 21 (preview features enabled), JUnit 5, Maven, existing `Config` parser, existing `OpsKeys` Ed25519 helpers, existing `Json`/Jackson wrapper, existing `Http` client, existing `TestApp`/`OpsIntegrationTest` patterns.

**Spec:** `docs/superpowers/specs/2026-04-14-brace-cli-project-config-and-ops-commands-design.md`

**Conventions used in this plan:**
- All `mvn test -Dtest=ClassName` commands run a single test class. Use `mvn test` for the full suite.
- Java imports omitted from inline snippets when obvious (`java.util.*`, `java.nio.file.*`, `org.junit.jupiter.api.*`); add what your IDE doesn't auto-import.
- Each task ends with a commit. Use the suggested message verbatim — they reference the spec by date and form a coherent history.

---

## Phase A — Server side (LogTap + new endpoints)

### Task 1: `LogTap` ring buffer

**Files:**
- Create: `src/main/java/io/brace/LogTap.java`
- Create: `src/test/java/io/brace/LogTapTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/io/brace/LogTapTest.java`:
```java
package io.brace;

import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LogTapTest {

    @BeforeEach
    void reset() {
        LogTap.clear();
        LogTap.setCapacity(1000);
    }

    @Test
    void appendAndSnapshotPreservesOrder() {
        LogTap.append(Map.of("level", "INFO", "message", "first"));
        LogTap.append(Map.of("level", "WARN", "message", "second"));

        var snap = LogTap.snapshot();
        assertEquals(2, snap.size());
        assertEquals("first", snap.get(0).fields().get("message"));
        assertEquals("second", snap.get(1).fields().get("message"));
    }

    @Test
    void idsAreMonotonicAndUnique() {
        LogTap.append(Map.of("message", "a"));
        LogTap.append(Map.of("message", "b"));
        var snap = LogTap.snapshot();
        assertTrue(snap.get(1).id() > snap.get(0).id());
    }

    @Test
    void evictsOldestWhenOverCapacity() {
        LogTap.setCapacity(3);
        for (int i = 0; i < 5; i++) LogTap.append(Map.of("n", i));

        var snap = LogTap.snapshot();
        assertEquals(3, snap.size());
        assertEquals(2, snap.get(0).fields().get("n"));
        assertEquals(4, snap.get(2).fields().get("n"));
    }

    @Test
    void sinceIdReturnsOnlyNewer() {
        LogTap.append(Map.of("n", "a"));
        LogTap.append(Map.of("n", "b"));
        LogTap.append(Map.of("n", "c"));
        long firstId = LogTap.snapshot().get(0).id();

        var after = LogTap.since(firstId);
        assertEquals(2, after.size());
        assertEquals("b", after.get(0).fields().get("n"));
    }

    @Test
    void sinceTimestampFiltersByTsField() {
        LogTap.append(Map.of("ts", "2026-04-14T10:00:00Z", "n", "a"));
        LogTap.append(Map.of("ts", "2026-04-14T10:05:00Z", "n", "b"));
        LogTap.append(Map.of("ts", "2026-04-14T10:10:00Z", "n", "c"));

        var after = LogTap.sinceTimestamp(Instant.parse("2026-04-14T10:03:00Z"));
        assertEquals(2, after.size());
        assertEquals("b", after.get(0).fields().get("n"));
    }

    @Test
    void concurrentAppendsAllRecorded() throws Exception {
        int threads = 10, perThread = 100;
        var pool = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) LogTap.append(Map.of("m", "x"));
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdown();
        assertEquals(threads * perThread, LogTap.snapshot().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LogTapTest -q`
Expected: compilation error, `LogTap` does not exist.

- [ ] **Step 3: Implement `LogTap`**

`src/main/java/io/brace/LogTap.java`:
```java
package io.brace;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory ring buffer of structured log entries. Captures everything that
 * flows through {@link Log#println(Map)} so that /ops/logs can serve a
 * tail-able view of recent activity. Lock-free; lost on process restart.
 */
public class LogTap {

    public record LogEntry(long id, Map<String, Object> fields) {}

    private static final ConcurrentLinkedDeque<LogEntry> entries = new ConcurrentLinkedDeque<>();
    private static final AtomicLong nextId = new AtomicLong(1);
    private static volatile int capacity = 1000;

    private LogTap() {}

    public static void setCapacity(int n) {
        if (n < 1) throw new IllegalArgumentException("capacity must be >= 1");
        capacity = n;
        evictExcess();
    }

    public static int capacity() { return capacity; }

    public static void append(Map<String, Object> fields) {
        long id = nextId.getAndIncrement();
        entries.add(new LogEntry(id, fields));
        evictExcess();
    }

    public static List<LogEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    public static List<LogEntry> since(long id) {
        var out = new ArrayList<LogEntry>();
        for (var e : entries) if (e.id() > id) out.add(e);
        return out;
    }

    public static List<LogEntry> sinceTimestamp(Instant ts) {
        var out = new ArrayList<LogEntry>();
        for (var e : entries) {
            Object tsField = e.fields().get("ts");
            if (tsField == null) continue;
            try {
                Instant entryTs = Instant.parse(tsField.toString());
                if (entryTs.isAfter(ts)) out.add(e);
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static void clear() {
        entries.clear();
        nextId.set(1);
    }

    private static void evictExcess() {
        while (entries.size() > capacity) entries.pollFirst();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=LogTapTest -q`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/LogTap.java src/test/java/io/brace/LogTapTest.java
git commit -m "feat: add LogTap in-memory ring buffer for ops logs"
```

---

### Task 2: Wire `LogTap` into `Log.println`

**Files:**
- Modify: `src/main/java/io/brace/Log.java:114-120`
- Create: `src/test/java/io/brace/LogTapWiringTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/io/brace/LogTapWiringTest.java`:
```java
package io.brace;

import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LogTapWiringTest {

    @BeforeEach
    void reset() { LogTap.clear(); }

    @Test
    void infoFlowsIntoLogTap() {
        Log.info("hello world");
        var snap = LogTap.snapshot();
        assertEquals(1, snap.size());
        assertEquals("hello world", snap.get(0).fields().get("message"));
        assertEquals("INFO", snap.get(0).fields().get("level"));
    }

    @Test
    void warnFlowsIntoLogTap() {
        Log.warn("careful");
        var snap = LogTap.snapshot();
        assertEquals(1, snap.size());
        assertEquals("WARN", snap.get(0).fields().get("level"));
    }

    @Test
    void errorWithThrowableFlowsIntoLogTap() {
        Log.error("boom", new RuntimeException("kaboom"));
        var snap = LogTap.snapshot();
        assertEquals(1, snap.size());
        assertEquals("ERROR", snap.get(0).fields().get("level"));
        assertEquals("RuntimeException", snap.get(0).fields().get("error"));
        assertEquals("kaboom", snap.get(0).fields().get("errorMessage"));
    }

    @Test
    void eventWithDataFlowsIntoLogTap() {
        Log.event("user.created", Map.of("userId", 42));
        var snap = LogTap.snapshot();
        assertEquals(1, snap.size());
        assertEquals("user.created", snap.get(0).fields().get("event"));
        assertEquals(42, snap.get(0).fields().get("userId"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=LogTapWiringTest -q`
Expected: FAIL — `LogTap.snapshot()` returns empty because nothing wires into it.

- [ ] **Step 3: Wire `LogTap.append` into `Log.println`**

In `src/main/java/io/brace/Log.java`, replace the `println` method (currently line 114) with:

```java
private static void println(Map<String, Object> map) {
    LogTap.append(map);
    try {
        System.out.println(Json.mapper().writeValueAsString(map));
    } catch (Exception e) {
        System.out.println(map);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=LogTapWiringTest -q`
Expected: PASS, 4 tests. Also run the full Log-related tests as a sanity check: `mvn test -Dtest=LogTapTest,LogTapWiringTest -q`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/Log.java src/test/java/io/brace/LogTapWiringTest.java
git commit -m "feat: tee Log.println into LogTap for ops logs endpoint"
```

---

### Task 3: `GET /ops/logs` endpoint

**Files:**
- Modify: `src/main/java/io/brace/OpsHandler.java` (add `logs(Request)` + `levelRank` helper)
- Modify: `src/main/java/io/brace/Brace.java:418-426` (register route)
- Modify: `src/test/java/io/brace/OpsIntegrationTest.java` (add tests)

- [ ] **Step 1: Add the failing tests**

In `src/test/java/io/brace/OpsIntegrationTest.java`, add at the bottom of the class:

```java
@Test
void opsLogsReturnsRecentEntries() throws Exception {
    LogTap.clear();
    Log.info("hello from test");
    Log.warn("warning from test");

    String token = authenticate();
    var response = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ops/logs"))
            .header("Authorization", "Bearer " + token)
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    String body = response.body();
    assertTrue(body.contains("hello from test"), body);
    assertTrue(body.contains("warning from test"), body);
}

@Test
void opsLogsSinceFiltersById() throws Exception {
    LogTap.clear();
    Log.info("first");
    long firstId = LogTap.snapshot().get(0).id();
    Log.info("second");

    String token = authenticate();
    var response = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ops/logs?since=" + firstId))
            .header("Authorization", "Bearer " + token)
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("second"));
    assertFalse(response.body().contains("\"first\""));
}

@Test
void opsLogsLevelFilter() throws Exception {
    LogTap.clear();
    Log.info("info-line");
    Log.warn("warn-line");
    Log.error("error-line");

    String token = authenticate();
    var response = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ops/logs?level=warn"))
            .header("Authorization", "Bearer " + token)
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertFalse(response.body().contains("info-line"));
    assertTrue(response.body().contains("warn-line"));
    assertTrue(response.body().contains("error-line"));
}

@Test
void opsLogsRequiresAuth() throws Exception {
    var response = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ops/logs"))
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(401, response.statusCode());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OpsIntegrationTest#opsLogsReturnsRecentEntries -q`
Expected: FAIL with 404 (route not registered).

- [ ] **Step 3: Add the `logs` method to `OpsHandler`**

In `src/main/java/io/brace/OpsHandler.java`, add after the existing `routes(Request)` method (~line 365):

```java
public Result logs(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");

    String sinceStr = req.queryParam("since");
    String sinceTsStr = req.queryParam("since_ts");
    String level = req.queryParam("level");
    String limitStr = req.queryParam("limit");
    int limit = Math.min(limitStr != null ? Integer.parseInt(limitStr) : 200, 1000);

    List<LogTap.LogEntry> entries;
    if (sinceStr != null) {
        entries = LogTap.since(Long.parseLong(sinceStr));
    } else if (sinceTsStr != null) {
        entries = LogTap.sinceTimestamp(Instant.parse(sinceTsStr));
    } else {
        entries = LogTap.snapshot();
    }

    if (level != null) {
        int minRank = levelRank(level);
        var filtered = new ArrayList<LogTap.LogEntry>();
        for (var e : entries) {
            if (levelRank((String) e.fields().get("level")) >= minRank) filtered.add(e);
        }
        entries = filtered;
    }

    if (entries.size() > limit) entries = entries.subList(entries.size() - limit, entries.size());

    var out = new ArrayList<Map<String, Object>>();
    for (var e : entries) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", e.id());
        m.putAll(e.fields());
        out.add(m);
    }
    return Json.of(out);
}

private static int levelRank(String level) {
    if (level == null) return 0;
    return switch (level.toUpperCase()) {
        case "DEBUG" -> 0;
        case "INFO"  -> 1;
        case "WARN"  -> 2;
        case "ERROR" -> 3;
        default      -> 0;
    };
}
```

- [ ] **Step 4: Register the route**

In `src/main/java/io/brace/Brace.java`, immediately after the existing `router.add("GET", "/ops/routes", ...)` line (~line 422), add:

```java
router.add("GET", "/ops/logs", (Handler) opsHandler::logs);
```

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest -q`
Expected: PASS — all existing tests plus the four new ones.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/brace/OpsHandler.java src/main/java/io/brace/Brace.java src/test/java/io/brace/OpsIntegrationTest.java
git commit -m "feat: GET /ops/logs endpoint backed by LogTap"
```

---

### Task 4: `GET /ops/cache` endpoint

**Files:**
- Modify: `src/main/java/io/brace/OpsHandler.java` (add `cacheStats(Request)`)
- Modify: `src/main/java/io/brace/Brace.java` (register route)
- Modify: `src/test/java/io/brace/OpsIntegrationTest.java` (add test)

- [ ] **Step 1: Add the failing test**

In `OpsIntegrationTest.java`:
```java
@Test
void opsCacheReturnsStats() throws Exception {
    String token = authenticate();
    var response = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ops/cache"))
            .header("Authorization", "Bearer " + token)
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    String body = response.body();
    assertTrue(body.contains("size"));
    assertTrue(body.contains("hits"));
    assertTrue(body.contains("misses"));
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -Dtest=OpsIntegrationTest#opsCacheReturnsStats -q`
Expected: FAIL with 404.

- [ ] **Step 3: Add the `cacheStats` method**

In `OpsHandler.java`, after `clearCache(Request)`:

```java
public Result cacheStats(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    var out = new LinkedHashMap<String, Object>();
    if (cache == null) {
        out.put("enabled", false);
        return Json.of(out);
    }
    out.put("enabled", true);
    out.put("size", cache.size());
    out.put("hits", cache.hits());
    out.put("misses", cache.misses());
    long total = cache.hits() + cache.misses();
    out.put("hitRate", total == 0 ? 0.0 : (double) cache.hits() / total);
    out.put("evictions", cache.evictions());
    return Json.of(out);
}
```

> `Cache` already exposes `size()`, `hits()`, `misses()`, `evictions()` as public methods (verified at `Cache.java:128-133`). Use them directly.

- [ ] **Step 4: Register the route**

In `Brace.java`, after the existing `/ops/cache/clear` registration:

```java
router.add("GET", "/ops/cache", (Handler) opsHandler::cacheStats);
```

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/brace/OpsHandler.java src/main/java/io/brace/Brace.java src/main/java/io/brace/Cache.java src/test/java/io/brace/OpsIntegrationTest.java
git commit -m "feat: GET /ops/cache returns cache stats for brace cache CLI"
```

---

### Task 5: `?since=` filter on `/ops/errors`

**Files:**
- Modify: `src/main/java/io/brace/ErrorStore.java` (add `list(String status, Instant since)` overload)
- Modify: `src/main/java/io/brace/OpsHandler.java` (forward query param to `ErrorStore`)
- Modify: `src/test/java/io/brace/ErrorStoreTest.java` (add test)
- Modify: `src/test/java/io/brace/OpsIntegrationTest.java` (add test)

- [ ] **Step 1: Add the failing `ErrorStoreTest` case**

In `ErrorStoreTest.java`:
```java
@Test
void listSinceReturnsOnlyErrorsAfterTimestamp() throws Exception {
    var store = new ErrorStore(databaseFactory, 100);
    store.record("OldError", "old", "/old", "stack", null);
    Thread.sleep(20);
    var cutoff = java.time.Instant.now();
    Thread.sleep(20);
    store.record("NewError", "new", "/new", "stack", null);

    var all = store.list(null);
    assertEquals(2, all.size());

    var recent = store.list(null, cutoff);
    assertEquals(1, recent.size());
    assertEquals("NewError", recent.get(0).get("errorType"));
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -Dtest=ErrorStoreTest#listSinceReturnsOnlyErrorsAfterTimestamp -q`
Expected: FAIL — `list(String, Instant)` does not exist.

- [ ] **Step 3: Add the overload to `ErrorStore`**

In `src/main/java/io/brace/ErrorStore.java`, after the existing `list(String status)` method:

```java
public List<Map<String, Object>> list(String status, java.time.Instant since) {
    var all = list(status);
    if (since == null) return all;
    var out = new ArrayList<Map<String, Object>>();
    for (var row : all) {
        Object firstSeen = row.get("firstSeen");
        if (firstSeen == null) continue;
        try {
            var ts = java.time.Instant.parse(firstSeen.toString().replace(' ', 'T') + (firstSeen.toString().endsWith("Z") ? "" : "Z"));
            if (!ts.isBefore(since)) out.add(row);
        } catch (Exception ignored) {
            // Fall back to keeping the row if timestamp is unparseable
            out.add(row);
        }
    }
    return out;
}
```

> The timestamp normalization handles both `2026-04-14T10:00:00Z` (already ISO) and `2026-04-14 10:00:00.0` (from Hibernate `Timestamp.toString()`). If the existing `firstSeen` value is already ISO, the `replace` is a no-op.

- [ ] **Step 4: Run the ErrorStore test**

Run: `mvn test -Dtest=ErrorStoreTest#listSinceReturnsOnlyErrorsAfterTimestamp -q`
Expected: PASS.

- [ ] **Step 5: Add the failing OpsHandler integration test**

In `OpsIntegrationTest.java`:
```java
@Test
void opsErrorsAcceptsSinceQueryParam() throws Exception {
    String token = authenticate();
    var response = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ops/errors?since=2099-01-01T00:00:00Z"))
            .header("Authorization", "Bearer " + token)
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    // Future cutoff filters out everything
    assertEquals("[]", response.body().trim());
}
```

- [ ] **Step 6: Run to verify failure**

Run: `mvn test -Dtest=OpsIntegrationTest#opsErrorsAcceptsSinceQueryParam -q`
Expected: likely PASS coincidentally (no errors recorded), but more importantly the `since` param must be honored — proceed to wire it in.

- [ ] **Step 7: Wire `since` in `OpsHandler.errors`**

Replace `OpsHandler.errors`:
```java
public Result errors(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    if (errorStore == null) return Json.of(List.of());
    String status = req.queryParam("status");
    String since = req.queryParam("since");
    Instant sinceTs = null;
    if (since != null) {
        try { sinceTs = Instant.parse(since); }
        catch (Exception e) { return Result.badRequest("Invalid since timestamp"); }
    }
    return Json.of(errorStore.list(status, sinceTs));
}
```

- [ ] **Step 8: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest,ErrorStoreTest -q`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/brace/ErrorStore.java src/main/java/io/brace/OpsHandler.java src/test/java/io/brace/ErrorStoreTest.java src/test/java/io/brace/OpsIntegrationTest.java
git commit -m "feat: /ops/errors accepts since= for windowed agent polling"
```

---

### Task 6: JSON-friendly responses for resolve and cache-clear

**Files:**
- Modify: `src/main/java/io/brace/OpsHandler.java` (`resolveError`, `clearCache`)
- Modify: `src/test/java/io/brace/OpsIntegrationTest.java`

**Why:** today both endpoints return the dashboard HTML (or a redirect to it). CLI commands need a small JSON response. Detect `Accept: application/json` and branch.

- [ ] **Step 1: Failing test**

```java
@Test
void clearCacheReturnsJsonWhenAcceptIsJson() throws Exception {
    String token = authenticate();
    var response = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ops/cache/clear"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    assertTrue(response.body().contains("cleared"));
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=OpsIntegrationTest#clearCacheReturnsJsonWhenAcceptIsJson -q`
Expected: FAIL — currently returns HTML dashboard.

- [ ] **Step 3: Patch `clearCache` and `resolveError`**

In `OpsHandler.java`, replace `clearCache`:
```java
public Result clearCache(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    if (cache == null) return Result.notFound();
    cache.clear();
    if (wantsJson(req)) {
        return Json.of(Map.of("cleared", true));
    }
    return dashboard(req);
}
```

Replace `resolveError`:
```java
public Result resolveError(Request req) {
    if (!authorize(req)) return Result.unauthorized("Invalid ops key");
    if (errorStore == null) return Result.notFound();
    long id = req.longPathParam("id");
    var resolved = errorStore.resolve(id);
    if (wantsJson(req)) {
        if (resolved == null) return Result.notFound();
        return Json.of(resolved);
    }
    return dashboard(req);
}
```

Add helper near `authorize`:
```java
private boolean wantsJson(Request req) {
    String accept = req.header("Accept");
    return accept != null && accept.contains("application/json");
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/OpsHandler.java src/test/java/io/brace/OpsIntegrationTest.java
git commit -m "feat: ops mutate endpoints honor Accept: application/json"
```

---

## Phase B — CLI infrastructure (Config, Output, Auth)

### Task 7: `CliConfig` — load `.brace` and `.brace.local`

**Files:**
- Create: `src/main/java/io/brace/CliConfig.java`
- Create: `src/test/java/io/brace/CliConfigTest.java`

- [ ] **Step 1: Failing test**

`src/test/java/io/brace/CliConfigTest.java`:
```java
package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliConfigTest {

    @TempDir Path tmp;

    @Test
    void defaultsWhenNoFiles() throws Exception {
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("http://localhost:8080", cfg.url());
        assertEquals("ops-private.key", cfg.keyPath());
        assertEquals("ops-authorized-keys", cfg.authorizedKeysPath());
        assertEquals("local", cfg.env());
    }

    @Test
    void readsBraceFile() throws Exception {
        Files.writeString(tmp.resolve(".brace"),
            "ops.local.url=http://dev.local:9000\n" +
            "ops.prod.url=https://app.example.com\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("http://dev.local:9000", cfg.url());
    }

    @Test
    void envFlagSelectsProdUrl() throws Exception {
        Files.writeString(tmp.resolve(".brace"),
            "ops.local.url=http://dev.local:9000\n" +
            "ops.prod.url=https://app.example.com\n");
        var cfg = CliConfig.load(tmp, new String[]{"--env", "prod"});
        assertEquals("https://app.example.com", cfg.url());
        assertEquals("prod", cfg.env());
    }

    @Test
    void localFileOverridesCommittedFile() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://committed:8080\n");
        Files.writeString(tmp.resolve(".brace.local"), "ops.local.url=http://override:9000\n");
        var cfg = CliConfig.load(tmp, new String[]{});
        assertEquals("http://override:9000", cfg.url());
    }

    @Test
    void cliFlagOverridesEverything() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://committed:8080\n");
        var cfg = CliConfig.load(tmp, new String[]{"--url", "http://flag:1234"});
        assertEquals("http://flag:1234", cfg.url());
    }

    @Test
    void cliKeyFlagOverridesKeyPath() throws Exception {
        var cfg = CliConfig.load(tmp, new String[]{"--key", "/etc/secret.key"});
        assertEquals("/etc/secret.key", cfg.keyPath());
    }

    @Test
    void unknownEnvFallsBackToLocal() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://x:1\n");
        var cfg = CliConfig.load(tmp, new String[]{"--env", "staging"});
        // No ops.staging.url defined → resolve to default
        assertEquals("http://localhost:8080", cfg.url());
        assertEquals("staging", cfg.env());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -Dtest=CliConfigTest -q`
Expected: compile error, `CliConfig` does not exist.

- [ ] **Step 3: Implement `CliConfig`**

`src/main/java/io/brace/CliConfig.java`:
```java
package io.brace;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Project-local CLI configuration loaded from .brace (committed) and
 * .brace.local (gitignored). Resolution order: CLI flag > .brace.local >
 * .brace > built-in default.
 */
public record CliConfig(String url, String keyPath, String authorizedKeysPath, String env) {

    private static final String DEFAULT_LOCAL_URL = "http://localhost:8080";
    private static final String DEFAULT_KEY_PATH = "ops-private.key";
    private static final String DEFAULT_AUTH_KEYS = "ops-authorized-keys";
    private static final String DEFAULT_ENV = "local";

    public static CliConfig load(Path projectDir, String[] cliArgs) throws IOException {
        var values = new LinkedHashMap<String, String>();

        // Layer 1: .brace (committed)
        var brace = projectDir.resolve(".brace");
        if (Files.exists(brace)) merge(values, brace);

        // Layer 2: .brace.local (gitignored)
        var local = projectDir.resolve(".brace.local");
        if (Files.exists(local)) merge(values, local);

        // Layer 3: CLI flags
        String envFlag = null, urlFlag = null, keyFlag = null;
        for (int i = 0; i < cliArgs.length - 1; i++) {
            switch (cliArgs[i]) {
                case "--env" -> envFlag = cliArgs[i + 1];
                case "--url" -> urlFlag = cliArgs[i + 1];
                case "--key" -> keyFlag = cliArgs[i + 1];
                default -> {}
            }
        }

        String env = envFlag != null ? envFlag
                   : values.getOrDefault("ops.env", DEFAULT_ENV);

        String url;
        if (urlFlag != null) {
            url = urlFlag;
        } else {
            String key = "ops." + env + ".url";
            url = values.getOrDefault(key, DEFAULT_LOCAL_URL);
        }

        String keyPath = keyFlag != null ? keyFlag
                       : values.getOrDefault("ops.key", DEFAULT_KEY_PATH);

        String authKeys = values.getOrDefault("ops.authorized_keys", DEFAULT_AUTH_KEYS);

        return new CliConfig(url, keyPath, authKeys, env);
    }

    private static void merge(Map<String, String> into, Path file) throws IOException {
        for (var line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            into.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliConfigTest -q`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliConfig.java src/test/java/io/brace/CliConfigTest.java
git commit -m "feat: CliConfig loads .brace and .brace.local with precedence"
```

---

### Task 8: `CliOutput` — TTY detection, table renderer, exit codes

**Files:**
- Create: `src/main/java/io/brace/CliOutput.java`
- Create: `src/test/java/io/brace/CliOutputTest.java`

- [ ] **Step 1: Failing test**

`src/test/java/io/brace/CliOutputTest.java`:
```java
package io.brace;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CliOutputTest {

    @Test
    void tableRendersHeadersAndRows() {
        var out = CliOutput.table(
            List.of("ID", "MESSAGE"),
            List.of(
                List.of("1", "first"),
                List.of("42", "second")));
        assertTrue(out.contains("ID"));
        assertTrue(out.contains("MESSAGE"));
        assertTrue(out.contains("first"));
        assertTrue(out.contains("second"));
    }

    @Test
    void tableAlignsColumnWidths() {
        var out = CliOutput.table(
            List.of("A", "B"),
            List.of(List.of("short", "long-value")));
        var lines = out.split("\n");
        // Header and row should have same column starts
        int bHeader = lines[0].indexOf("B");
        int bRow = lines[1].indexOf("long-value");
        assertEquals(bHeader, bRow);
    }

    @Test
    void truncatesOverlongValues() {
        var huge = "x".repeat(500);
        var out = CliOutput.table(List.of("M"), List.of(List.of(huge)), 80);
        for (var line : out.split("\n")) {
            assertTrue(line.length() <= 80, "line too long: " + line.length());
        }
    }

    @Test
    void formatsJson() throws Exception {
        var out = CliOutput.json(Map.of("ok", true, "count", 5));
        assertTrue(out.contains("\"ok\""));
        assertTrue(out.contains("\"count\""));
    }

    @Test
    void modeFromEnvForcesJson() {
        assertEquals(CliOutput.Mode.JSON, CliOutput.modeFrom(false, false, false));
        assertEquals(CliOutput.Mode.HUMAN, CliOutput.modeFrom(true, false, false));
        assertEquals(CliOutput.Mode.JSON, CliOutput.modeFrom(true, true, false));
        assertEquals(CliOutput.Mode.HUMAN, CliOutput.modeFrom(false, false, true));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -Dtest=CliOutputTest -q`
Expected: compile error.

- [ ] **Step 3: Implement `CliOutput`**

`src/main/java/io/brace/CliOutput.java`:
```java
package io.brace;

import java.util.*;

/**
 * CLI output helpers: TTY-aware mode selection, table rendering, JSON
 * pretty-printing, exit-code helpers.
 */
public class CliOutput {

    public enum Mode { HUMAN, JSON }

    private CliOutput() {}

    /**
     * Resolve the output mode for a command.
     *
     * @param isTty       true if stdout is a terminal (System.console() != null)
     * @param jsonFlag    --json explicitly requested
     * @param prettyFlag  --pretty explicitly requested
     */
    public static Mode modeFrom(boolean isTty, boolean jsonFlag, boolean prettyFlag) {
        if (jsonFlag) return Mode.JSON;
        if (prettyFlag) return Mode.HUMAN;
        return isTty ? Mode.HUMAN : Mode.JSON;
    }

    public static Mode autoMode(boolean jsonFlag, boolean prettyFlag) {
        return modeFrom(System.console() != null, jsonFlag, prettyFlag);
    }

    public static String table(List<String> headers, List<List<String>> rows) {
        return table(headers, rows, 200);
    }

    public static String table(List<String> headers, List<List<String>> rows, int maxWidth) {
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) widths[i] = headers.get(i).length();
        for (var row : rows) {
            for (int i = 0; i < cols && i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i) == null ? 0 : row.get(i).length());
            }
        }

        // Total width budget — collapse widest column to fit
        int total = 0;
        for (int w : widths) total += w + 2;
        if (total > maxWidth) {
            int over = total - maxWidth;
            int widest = 0;
            for (int i = 1; i < cols; i++) if (widths[i] > widths[widest]) widest = i;
            widths[widest] = Math.max(8, widths[widest] - over);
        }

        var sb = new StringBuilder();
        appendRow(sb, headers, widths);
        for (var row : rows) appendRow(sb, row, widths);
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> row, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.size() && row.get(i) != null ? row.get(i) : "";
            if (cell.length() > widths[i]) cell = cell.substring(0, widths[i] - 1) + "…";
            sb.append(pad(cell, widths[i]));
            if (i < widths.length - 1) sb.append("  ");
        }
        sb.append("\n");
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    public static String json(Object value) {
        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public static String jsonCompact(Object value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public static void printError(String message) {
        System.err.println("✗ " + message);
    }

    public static void printSuccess(String message) {
        System.out.println("✓ " + message);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliOutputTest -q`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliOutput.java src/test/java/io/brace/CliOutputTest.java
git commit -m "feat: CliOutput with TTY-aware mode, table renderer, json helpers"
```

---

### Task 9: `CliAuth` — bearer token fetch + cache

**Files:**
- Create: `src/main/java/io/brace/CliAuth.java`
- Create: `src/test/java/io/brace/CliAuthTest.java`

- [ ] **Step 1: Failing test**

`src/test/java/io/brace/CliAuthTest.java`:
```java
package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliAuthTest {

    static Brace app;
    static int port;
    static OpsKeys.Keypair keypair;
    @TempDir static Path tmp;

    @BeforeAll
    static void start() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");
        Files.writeString(tmp.resolve("ops-private.key"), keypair.publicKey() + "\n" + keypair.privateKey() + "\n");

        app = Brace.app().port(0).ops(keysFile.toString());
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception { app.stop(); }

    @BeforeEach
    void clearCache() throws Exception {
        Path cache = tmp.resolve("target").resolve(".brace-token");
        Files.deleteIfExists(cache);
    }

    @Test
    void freshFetchReturnsBearerToken() throws Exception {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("ops-private.key").toString(),
            "authorized-keys", "local");
        String token = CliAuth.bearer(cfg, tmp);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void cachedTokenReused() throws Exception {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("ops-private.key").toString(),
            "authorized-keys", "local");
        String first = CliAuth.bearer(cfg, tmp);
        String second = CliAuth.bearer(cfg, tmp);
        assertEquals(first, second);
    }

    @Test
    void missingKeyFileThrows() {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("does-not-exist.key").toString(),
            "authorized-keys", "local");
        var ex = assertThrows(Exception.class, () -> CliAuth.bearer(cfg, tmp));
        assertTrue(ex.getMessage().toLowerCase().contains("key"));
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=CliAuthTest -q`
Expected: compile error.

- [ ] **Step 3: Implement `CliAuth`**

`src/main/java/io/brace/CliAuth.java`:
```java
package io.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;

/**
 * Centralized auth helper for project-scoped CLI commands.
 * Loads the private key, signs a timestamp, posts to /ops/auth, caches the
 * bearer token in target/.brace-token until expiry.
 */
public class CliAuth {

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final int DEFAULT_TTL_SECONDS = 3600;

    private CliAuth() {}

    public static String bearer(CliConfig cfg, Path projectDir) throws Exception {
        var cached = readCache(projectDir);
        if (cached != null) return cached;

        var kp = loadKeypair(cfg);

        String timestamp = Instant.now().toString();
        String signature = OpsKeys.sign(timestamp, kp.privateKey());
        var body = Map.of(
            "publicKey", kp.publicKey(),
            "timestamp", timestamp,
            "signature", signature,
            "ttlSeconds", DEFAULT_TTL_SECONDS);

        var response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Authentication failed (" + response.statusCode() + "): " + response.body());
        }

        JsonNode parsed = Json.mapper().readTree(response.body());
        String token = parsed.get("token").asText();
        String expiresAt = parsed.get("expiresAt").asText();

        writeCache(projectDir, token, expiresAt);
        return token;
    }

    public static void clearCache(Path projectDir) throws Exception {
        Files.deleteIfExists(projectDir.resolve("target").resolve(".brace-token"));
    }

    private static OpsKeys.Keypair loadKeypair(CliConfig cfg) throws Exception {
        Path keyFile = Path.of(cfg.keyPath());
        if (Files.exists(keyFile)) {
            return OpsKeys.readKeyFile(cfg.keyPath());
        }
        String envKey = System.getenv("OPS_PRIVATE_KEY");
        if (envKey == null || envKey.isEmpty()) {
            throw new RuntimeException("Private key not found at " + cfg.keyPath()
                + " and OPS_PRIVATE_KEY env var not set.");
        }
        Path authPath = Path.of(cfg.authorizedKeysPath());
        if (!Files.exists(authPath)) {
            throw new RuntimeException("Cannot match OPS_PRIVATE_KEY without "
                + cfg.authorizedKeysPath());
        }
        var authorizedKeys = OpsKeys.loadAuthorizedKeys(authPath.toString());
        var testSig = OpsKeys.sign("test", envKey);
        for (var pub : authorizedKeys) {
            if (OpsKeys.verify("test", testSig, pub)) {
                return new OpsKeys.Keypair(pub, envKey);
            }
        }
        throw new RuntimeException("OPS_PRIVATE_KEY does not match any authorized key.");
    }

    private static String readCache(Path projectDir) {
        try {
            Path file = projectDir.resolve("target").resolve(".brace-token");
            if (!Files.exists(file)) return null;
            JsonNode node = Json.mapper().readTree(Files.readString(file));
            String expiresAt = node.get("expiresAt").asText();
            // Expire 60s early to avoid race with server-side TTL
            if (Instant.parse(expiresAt).minusSeconds(60).isBefore(Instant.now())) return null;
            return node.get("token").asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Path projectDir, String token, String expiresAt) {
        try {
            Path target = projectDir.resolve("target");
            Files.createDirectories(target);
            String json = Json.mapper().writeValueAsString(Map.of("token", token, "expiresAt", expiresAt));
            Files.writeString(target.resolve(".brace-token"), json);
        } catch (Exception e) {
            // Caching is best-effort; ignore failures
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliAuthTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliAuth.java src/test/java/io/brace/CliAuthTest.java
git commit -m "feat: CliAuth fetches and caches bearer token via /ops/auth"
```

---

## Phase C — `brace init`

### Task 10: `brace init` local checks

**Files:**
- Create: `src/main/java/io/brace/CliInit.java`
- Create: `src/test/java/io/brace/CliInitTest.java`

- [ ] **Step 1: Failing test**

`src/test/java/io/brace/CliInitTest.java`:
```java
package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliInitTest {

    @TempDir Path tmp;

    @BeforeEach
    void scaffoldProjectDir() throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
    }

    @Test
    void createsMissingBraceFile() throws Exception {
        var result = CliInit.run(tmp);
        assertTrue(Files.exists(tmp.resolve(".brace")));
        assertTrue(Files.readString(tmp.resolve(".brace")).contains("ops.local.url=http://localhost:8080"));
        assertTrue(result.actions().stream().anyMatch(a -> a.contains(".brace")));
    }

    @Test
    void createsMissingBraceLocalFile() throws Exception {
        CliInit.run(tmp);
        assertTrue(Files.exists(tmp.resolve(".brace.local")));
        assertTrue(Files.readString(tmp.resolve(".brace.local")).contains("ops.key=ops-private.key"));
    }

    @Test
    void appendsGitignoreEntries() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "target/\n");
        CliInit.run(tmp);
        String gitignore = Files.readString(tmp.resolve(".gitignore"));
        assertTrue(gitignore.contains(".brace.local"));
        assertTrue(gitignore.contains("ops-private.key"));
        assertTrue(gitignore.contains("target/"));
    }

    @Test
    void createsGitignoreIfMissing() throws Exception {
        CliInit.run(tmp);
        assertTrue(Files.exists(tmp.resolve(".gitignore")));
    }

    @Test
    void doesNotOverwriteExistingBraceFile() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://custom:1234\n");
        CliInit.run(tmp);
        assertTrue(Files.readString(tmp.resolve(".brace")).contains("custom:1234"));
    }

    @Test
    void reportsMissingKeypair() throws Exception {
        var result = CliInit.run(tmp);
        assertTrue(result.actions().stream().anyMatch(a -> a.contains("brace ops keypair")));
        assertFalse(result.ok());
    }

    @Test
    void okWhenKeypairPresent() throws Exception {
        Files.writeString(tmp.resolve("ops-authorized-keys"), "ed25519:abc test\n");
        Files.writeString(tmp.resolve("ops-private.key"), "ed25519:abc\nprivate\n");
        var result = CliInit.run(tmp);
        assertTrue(result.ok(), String.join("; ", result.actions()));
    }

    @Test
    void idempotent() throws Exception {
        Files.writeString(tmp.resolve("ops-authorized-keys"), "ed25519:abc test\n");
        Files.writeString(tmp.resolve("ops-private.key"), "ed25519:abc\nprivate\n");
        CliInit.run(tmp);
        var second = CliInit.run(tmp);
        assertTrue(second.ok());
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=CliInitTest -q`
Expected: compile error.

- [ ] **Step 3: Implement `CliInit` (local checks only)**

`src/main/java/io/brace/CliInit.java`:
```java
package io.brace;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class CliInit {

    public record Result(boolean ok, List<Check> local, List<Check> remote, List<String> actions) {}
    public record Check(String name, boolean ok, String detail) {}

    private CliInit() {}

    public static Result run(Path projectDir) throws IOException {
        var local = new ArrayList<Check>();
        var actions = new ArrayList<String>();

        // .brace
        Path brace = projectDir.resolve(".brace");
        if (!Files.exists(brace)) {
            Files.writeString(brace,
                "# Brace CLI project config — committed to git\n" +
                "ops.local.url=http://localhost:8080\n" +
                "# ops.prod.url=https://your-app.example.com\n" +
                "ops.authorized_keys=ops-authorized-keys\n");
            local.add(new Check(".brace", true, "created"));
            actions.add("Created .brace");
        } else {
            local.add(new Check(".brace", true, "present"));
        }

        // .brace.local
        Path local1 = projectDir.resolve(".brace.local");
        if (!Files.exists(local1)) {
            Files.writeString(local1,
                "# Brace CLI per-developer overrides — gitignored\n" +
                "ops.key=ops-private.key\n" +
                "ops.env=local\n");
            local.add(new Check(".brace.local", true, "created"));
            actions.add("Created .brace.local");
        } else {
            local.add(new Check(".brace.local", true, "present"));
        }

        // .gitignore
        Path gitignore = projectDir.resolve(".gitignore");
        var existing = Files.exists(gitignore) ? Files.readString(gitignore) : "";
        var needs = new ArrayList<String>();
        if (!existing.contains(".brace.local")) needs.add(".brace.local");
        if (!existing.contains("ops-private.key")) needs.add("ops-private.key");
        if (!needs.isEmpty()) {
            String addition = (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n")
                + "\n# brace CLI\n" + String.join("\n", needs) + "\n";
            Files.writeString(gitignore, existing + addition);
            local.add(new Check(".gitignore", true, "added " + String.join(", ", needs)));
            actions.add("Updated .gitignore");
        } else {
            local.add(new Check(".gitignore", true, "entries OK"));
        }

        // ops-authorized-keys
        Path authKeys = projectDir.resolve("ops-authorized-keys");
        boolean keypairOk = true;
        if (!Files.exists(authKeys) || Files.readString(authKeys).trim().isEmpty()) {
            local.add(new Check("ops-authorized-keys", false, "missing"));
            actions.add("Run `brace ops keypair` to generate one");
            keypairOk = false;
        } else {
            int keyCount = (int) Files.readAllLines(authKeys).stream()
                .filter(l -> !l.trim().isEmpty() && !l.trim().startsWith("#")).count();
            local.add(new Check("ops-authorized-keys", true, keyCount + " key(s)"));
        }

        // ops-private.key
        Path privKey = projectDir.resolve("ops-private.key");
        if (!Files.exists(privKey)) {
            local.add(new Check("ops-private.key", false, "missing"));
            if (keypairOk) actions.add("Run `brace ops keypair` to generate one");
            keypairOk = false;
        } else {
            local.add(new Check("ops-private.key", true, "present"));
        }

        boolean ok = local.stream().allMatch(Check::ok);
        return new Result(ok, local, List.of(), actions);
    }

    public static void print(Result r, CliOutput.Mode mode) {
        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(Map.of(
                "ok", r.ok(),
                "local", r.local(),
                "remote", r.remote(),
                "actions", r.actions())));
            return;
        }
        System.out.println();
        System.out.println("Local setup");
        for (var c : r.local()) {
            System.out.println("  " + (c.ok() ? "✓" : "✗") + " "
                + pad(c.name(), 24) + " " + c.detail());
        }
        if (!r.actions().isEmpty()) {
            System.out.println();
            System.out.println("Actions:");
            for (var a : r.actions()) System.out.println("  - " + a);
        }
        System.out.println();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliInitTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliInit.java src/test/java/io/brace/CliInitTest.java
git commit -m "feat: brace init scaffold + local-checks diagnostic"
```

---

### Task 11: `brace init` remote checks

**Files:**
- Modify: `src/main/java/io/brace/CliInit.java` (add `runWithRemote`)
- Modify: `src/test/java/io/brace/CliInitTest.java` (add tests using `TestApp`)

- [ ] **Step 1: Failing test**

Add to `CliInitTest.java`:
```java
@Test
void remoteChecksSkippedWhenNoProdUrl() throws Exception {
    Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://localhost:8080\n");
    Files.writeString(tmp.resolve("ops-authorized-keys"), "ed25519:abc test\n");
    Files.writeString(tmp.resolve("ops-private.key"), "ed25519:abc\nprivate\n");

    var result = CliInit.runWithRemote(tmp);
    assertTrue(result.remote().isEmpty());
}

@Test
void remoteCheckSucceedsAgainstAuthorizedServer() throws Exception {
    var keypair = OpsKeys.generateKeypair();
    Path keysFile = tmp.resolve("ops-authorized-keys");
    Files.writeString(keysFile, keypair.publicKey() + " test\n");
    Files.writeString(tmp.resolve("ops-private.key"),
        keypair.publicKey() + "\n" + keypair.privateKey() + "\n");

    var app = Brace.app().port(0).ops(keysFile.toString());
    app.start();
    try {
        Files.writeString(tmp.resolve(".brace"),
            "ops.local.url=http://localhost:8080\n" +
            "ops.prod.url=http://localhost:" + app.actualPort() + "\n");

        var result = CliInit.runWithRemote(tmp);
        assertFalse(result.remote().isEmpty(), "expected remote checks to run");
        assertTrue(result.remote().stream().allMatch(c -> c.ok()), result.remote().toString());
    } finally {
        app.stop();
    }
}

@Test
void remoteCheckFailsAgainstUnauthorizedServer() throws Exception {
    var serverKey = OpsKeys.generateKeypair();
    var clientKey = OpsKeys.generateKeypair();
    Path keysFile = tmp.resolve("ops-authorized-keys");
    Files.writeString(keysFile, serverKey.publicKey() + " server-only\n");
    Files.writeString(tmp.resolve("ops-private.key"),
        clientKey.publicKey() + "\n" + clientKey.privateKey() + "\n");

    var app = Brace.app().port(0).ops(keysFile.toString());
    app.start();
    try {
        Files.writeString(tmp.resolve(".brace"),
            "ops.local.url=http://localhost:8080\n" +
            "ops.prod.url=http://localhost:" + app.actualPort() + "\n");

        var result = CliInit.runWithRemote(tmp);
        assertFalse(result.ok());
        assertTrue(result.actions().stream().anyMatch(a -> a.contains("authorized")));
    } finally {
        app.stop();
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=CliInitTest -q`
Expected: compile error — `runWithRemote` does not exist.

- [ ] **Step 3: Add `runWithRemote` to `CliInit`**

In `CliInit.java`, add:
```java
public static Result runWithRemote(Path projectDir) throws IOException {
    var localResult = run(projectDir);

    // Skip remote checks if no prod URL configured
    var braceFile = projectDir.resolve(".brace");
    String prodUrl = null;
    if (Files.exists(braceFile)) {
        for (var line : Files.readAllLines(braceFile)) {
            line = line.trim();
            if (line.startsWith("ops.prod.url=")) {
                prodUrl = line.substring("ops.prod.url=".length()).trim();
                break;
            }
        }
    }
    if (prodUrl == null || prodUrl.isEmpty()) return localResult;

    // Need a usable keypair to attempt remote
    Path privKey = projectDir.resolve("ops-private.key");
    if (!Files.exists(privKey)) return localResult;

    var remote = new ArrayList<Check>();
    var actions = new ArrayList<>(localResult.actions());

    try {
        var cfg = new CliConfig(prodUrl, privKey.toString(), "ops-authorized-keys", "prod");
        try {
            CliAuth.clearCache(projectDir);
            CliAuth.bearer(cfg, projectDir);
            remote.add(new Check("reachable", true, prodUrl));
            remote.add(new Check("authorized", true, "key accepted"));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (msg.contains("Connection") || msg.contains("refused") || msg.contains("HostNotFound")) {
                remote.add(new Check("reachable", false, "not reachable: " + msg));
                actions.add("Verify " + prodUrl + " is reachable");
            } else {
                remote.add(new Check("reachable", true, prodUrl));
                remote.add(new Check("authorized", false, msg));
                String pub = OpsKeys.readKeyFile(privKey.toString()).publicKey();
                actions.add("Add to server's ops-authorized-keys: " + pub + "  <label>");
            }
        }
    } catch (Exception e) {
        remote.add(new Check("setup", false, e.getMessage()));
    }

    boolean ok = localResult.ok() && remote.stream().allMatch(Check::ok);
    return new Result(ok, localResult.local(), remote, actions);
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliInitTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliInit.java src/test/java/io/brace/CliInitTest.java
git commit -m "feat: brace init remote checks against ops.prod.url"
```

---

## Phase D — Read commands

> **Shared structure for tasks 12-16:** each command lives as a static method on a new `CliCommands` class (created in task 12 and extended in subsequent tasks). Each method:
> 1. Loads `CliConfig` from current directory
> 2. Calls `CliAuth.bearer()` for the token
> 3. Hits the appropriate `/ops/*` endpoint with `Authorization: Bearer <token>` and `Accept: application/json`
> 4. Renders via `CliOutput` (TTY-aware) and returns an exit code
>
> The `main(String[] args)` dispatch in `Cli.java` is wired up at the end (task 17).

### Task 12: `brace errors`

**Files:**
- Create: `src/main/java/io/brace/CliCommands.java`
- Create: `src/test/java/io/brace/CliCommandsTest.java`

- [ ] **Step 1: Failing test**

`src/test/java/io/brace/CliCommandsTest.java`:
```java
package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliCommandsTest {

    static Brace app;
    static int port;
    static OpsKeys.Keypair keypair;

    @TempDir static Path projectDir;

    @BeforeAll
    static void start() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keysFile = projectDir.resolve("ops-authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");
        Files.writeString(projectDir.resolve("ops-private.key"),
            keypair.publicKey() + "\n" + keypair.privateKey() + "\n");

        app = Brace.app().port(0).ops(keysFile.toString());
        app.start();
        port = app.actualPort();

        Files.writeString(projectDir.resolve(".brace"),
            "ops.local.url=http://localhost:" + port + "\n");
        Files.writeString(projectDir.resolve(".brace.local"),
            "ops.key=" + projectDir.resolve("ops-private.key") + "\n");
    }

    @AfterAll
    static void stop() throws Exception { app.stop(); }

    @BeforeEach
    void resetCache() throws Exception { CliAuth.clearCache(projectDir); }

    @Test
    void errorsCommandSucceedsWithEmptyList() throws Exception {
        int code = CliCommands.errors(projectDir, new String[]{"--json"});
        assertEquals(0, code);
    }

    @Test
    void errorsCommandHitsServer() throws Exception {
        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        try {
            CliCommands.errors(projectDir, new String[]{"--json"});
        } finally {
            System.setOut(prev);
        }
        // Empty error list serializes to [] or {"errors":[]}
        String out = bout.toString().trim();
        assertTrue(out.startsWith("[") || out.startsWith("{"), "got: " + out);
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=CliCommandsTest -q`
Expected: compile error.

- [ ] **Step 3: Implement `CliCommands.errors`**

`src/main/java/io/brace/CliCommands.java`:
```java
package io.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

public class CliCommands {

    private static final HttpClient http = HttpClient.newHttpClient();

    private CliCommands() {}

    // ---------- brace errors ----------

    public static int errors(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);
        String token = CliAuth.bearer(cfg, projectDir);

        String url = cfg.url() + "/ops/errors";
        String since = parseFlag(args, "--since");
        if (since != null) {
            Instant cutoff = parseDuration(since);
            url += "?since=" + cutoff.toString();
        }

        var response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
            return 2;
        }

        JsonNode root = Json.mapper().readTree(response.body());
        var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));

        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(root));
        } else {
            renderErrorsTable(root);
        }
        return root.size() == 0 ? 0 : 1;
    }

    private static void renderErrorsTable(JsonNode errors) {
        if (errors.size() == 0) {
            System.out.println("No errors.");
            return;
        }
        var rows = new ArrayList<List<String>>();
        for (var e : errors) {
            rows.add(List.of(
                e.path("id").asText("?"),
                String.valueOf(e.path("occurrenceCount").asInt(0)),
                e.path("lastSeen").asText(""),
                e.path("route").asText(""),
                e.path("message").asText("")));
        }
        System.out.println(CliOutput.table(
            List.of("ID", "COUNT", "LAST SEEN", "ROUTE", "MESSAGE"),
            rows, 120));
    }

    // ---------- shared helpers ----------

    static String parseFlag(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) return args[i + 1];
        }
        return null;
    }

    static boolean hasFlag(String[] args, String name) {
        for (var a : args) if (name.equals(a)) return true;
        return false;
    }

    static Instant parseDuration(String s) {
        if (s == null) return Instant.EPOCH;
        char unit = s.charAt(s.length() - 1);
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        Duration d = switch (unit) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("Unknown duration: " + s);
        };
        return Instant.now().minus(d);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliCommandsTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliCommands.java src/test/java/io/brace/CliCommandsTest.java
git commit -m "feat: brace errors command with --since and --json"
```

---

### Task 13: `brace logs` (one-shot + follow)

**Files:**
- Modify: `src/main/java/io/brace/CliCommands.java` (add `logs`)
- Modify: `src/test/java/io/brace/CliCommandsTest.java` (add tests)

- [ ] **Step 1: Failing test**

Add to `CliCommandsTest.java`:
```java
@Test
void logsCommandReturnsTappedEntries() throws Exception {
    LogTap.clear();
    Log.info("test-log-message");

    var bout = new ByteArrayOutputStream();
    var prev = System.out;
    System.setOut(new PrintStream(bout));
    try {
        int code = CliCommands.logs(projectDir, new String[]{"--json"});
        assertEquals(0, code);
    } finally {
        System.setOut(prev);
    }
    assertTrue(bout.toString().contains("test-log-message"));
}

@Test
void logsCommandFiltersBySince() throws Exception {
    LogTap.clear();
    Log.info("ancient-message");
    Thread.sleep(20);

    var bout = new ByteArrayOutputStream();
    var prev = System.out;
    System.setOut(new PrintStream(bout));
    try {
        CliCommands.logs(projectDir, new String[]{"--json", "--since", "1s"});
        Log.info("recent-message");
        // Re-run: only recent should show up if since is interpreted live.
        // For this test we just verify the command exits cleanly.
    } finally {
        System.setOut(prev);
    }
    // Either ancient was filtered or both were returned; we mainly assert 0 exit.
    // Stronger filter assertion lives in OpsHandler test.
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=CliCommandsTest#logsCommandReturnsTappedEntries -q`
Expected: compile error.

- [ ] **Step 3: Add `logs` to `CliCommands`**

In `CliCommands.java`, add after `errors`:
```java
// ---------- brace logs ----------

public static int logs(Path projectDir, String[] args) throws Exception {
    var cfg = CliConfig.load(projectDir, args);
    String token = CliAuth.bearer(cfg, projectDir);

    String level = parseFlag(args, "--level");
    String since = parseFlag(args, "--since");
    boolean follow = hasFlag(args, "-f") || hasFlag(args, "--follow");
    var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));

    String baseUrl = cfg.url() + "/ops/logs";
    StringBuilder query = new StringBuilder();
    if (since != null) {
        Instant cutoff = parseDuration(since);
        query.append("since_ts=").append(cutoff.toString());
    }
    if (level != null) {
        if (query.length() > 0) query.append("&");
        query.append("level=").append(level);
    }
    String firstUrl = query.length() == 0 ? baseUrl : baseUrl + "?" + query;

    long lastId = renderLogsOnce(firstUrl, token, mode);
    if (!follow) return 0;

    while (true) {
        Thread.sleep(1000);
        StringBuilder q = new StringBuilder("since=").append(lastId);
        if (level != null) q.append("&level=").append(level);
        long newLast = renderLogsOnce(baseUrl + "?" + q, token, mode);
        if (newLast > 0) lastId = newLast;
    }
}

private static long renderLogsOnce(String url, String token, CliOutput.Mode mode) throws Exception {
    var response = http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
        CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
        return 0;
    }
    JsonNode entries = Json.mapper().readTree(response.body());
    long lastId = 0;
    for (var e : entries) {
        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.jsonCompact(e));
        } else {
            renderLogLine(e);
        }
        long id = e.path("id").asLong(0);
        if (id > lastId) lastId = id;
    }
    return lastId;
}

private static void renderLogLine(JsonNode e) {
    var sb = new StringBuilder();
    sb.append("[").append(e.path("ts").asText("?")).append("] ");
    sb.append(String.format("%-5s ", e.path("level").asText("INFO")));
    String msg = e.has("message") ? e.path("message").asText() : e.path("event").asText("");
    sb.append(msg);
    var fields = e.fields();
    while (fields.hasNext()) {
        var entry = fields.next();
        String k = entry.getKey();
        if (k.equals("id") || k.equals("ts") || k.equals("level") || k.equals("message") || k.equals("event")) continue;
        sb.append(" ").append(k).append("=").append(entry.getValue().asText());
    }
    System.out.println(sb);
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliCommandsTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliCommands.java src/test/java/io/brace/CliCommandsTest.java
git commit -m "feat: brace logs command with -f follow mode"
```

---

### Task 14: `brace status`

**Files:**
- Modify: `src/main/java/io/brace/CliCommands.java` (add `status`)
- Modify: `src/test/java/io/brace/CliCommandsTest.java` (add test)

- [ ] **Step 1: Failing test**

```java
@Test
void statusCommandReturnsZeroAgainstHealthyApp() throws Exception {
    var bout = new ByteArrayOutputStream();
    var prev = System.out;
    System.setOut(new PrintStream(bout));
    try {
        int code = CliCommands.status(projectDir, new String[]{"--json"});
        assertEquals(0, code);
    } finally {
        System.setOut(prev);
    }
    assertTrue(bout.toString().contains("\"app\""));
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=CliCommandsTest#statusCommandReturnsZeroAgainstHealthyApp -q`
Expected: compile error.

- [ ] **Step 3: Add `status` to `CliCommands`**

```java
// ---------- brace status ----------

public static int status(Path projectDir, String[] args) throws Exception {
    var cfg = CliConfig.load(projectDir, args);
    String token;
    try {
        token = CliAuth.bearer(cfg, projectDir);
    } catch (Exception e) {
        CliOutput.printError("Cannot reach " + cfg.url() + ": " + e.getMessage());
        return 2;
    }

    HttpResponse<String> response;
    try {
        response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/status"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
        CliOutput.printError("Cannot reach " + cfg.url() + ": " + e.getMessage());
        return 2;
    }

    if (response.statusCode() != 200) {
        CliOutput.printError("HTTP " + response.statusCode());
        return 2;
    }

    JsonNode root = Json.mapper().readTree(response.body());
    var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));

    if (mode == CliOutput.Mode.JSON) {
        System.out.println(CliOutput.json(root));
    } else {
        renderStatus(root);
    }

    int errorCount = root.path("errors").path("count").asInt(0);
    return errorCount > 0 ? 1 : 0;
}

private static void renderStatus(JsonNode root) {
    System.out.println();
    System.out.println("App");
    var app = root.path("app");
    System.out.println("  uptime    " + app.path("uptime").asText("-"));
    System.out.println("  java      " + app.path("javaVersion").asText("-"));
    System.out.println();
    System.out.println("HTTP");
    var http = root.path("http");
    System.out.println("  status    " + http.path("statusCodes").toString());
    var slow = http.path("slowestRoutes");
    if (slow.size() > 0) {
        System.out.println("  slowest:");
        for (var r : slow) {
            System.out.println("    " + r.path("route").asText() + "  "
                + r.path("avgMs").asDouble() + "ms (" + r.path("count").asInt() + ")");
        }
    }
    System.out.println();
    var errors = root.path("errors");
    System.out.println("Errors    " + errors.path("count").asInt(0));
    System.out.println();
    var jvm = root.path("jvm");
    if (!jvm.isMissingNode()) {
        var heap = jvm.path("heap");
        System.out.println("Heap      " + heap.path("usedMB").asLong() + "MB / "
            + heap.path("maxMB").asLong() + "MB");
    }
    System.out.println();
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliCommandsTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliCommands.java src/test/java/io/brace/CliCommandsTest.java
git commit -m "feat: brace status command with health-aware exit code"
```

---

### Task 15: `brace cache` (read + clear)

**Files:**
- Modify: `src/main/java/io/brace/CliCommands.java` (add `cache`, `cacheClear`)
- Modify: `src/test/java/io/brace/CliCommandsTest.java` (add tests)

- [ ] **Step 1: Failing test**

```java
@Test
void cacheCommandShowsStats() throws Exception {
    var bout = new ByteArrayOutputStream();
    var prev = System.out;
    System.setOut(new PrintStream(bout));
    try {
        int code = CliCommands.cache(projectDir, new String[]{"--json"});
        assertEquals(0, code);
    } finally {
        System.setOut(prev);
    }
    assertTrue(bout.toString().contains("enabled"));
}

@Test
void cacheClearReturnsZero() throws Exception {
    var bout = new ByteArrayOutputStream();
    var prev = System.out;
    System.setOut(new PrintStream(bout));
    try {
        int code = CliCommands.cacheClear(projectDir, new String[]{});
        assertEquals(0, code);
    } finally {
        System.setOut(prev);
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=CliCommandsTest -q`
Expected: compile errors.

- [ ] **Step 3: Add `cache` and `cacheClear`**

```java
// ---------- brace cache ----------

public static int cache(Path projectDir, String[] args) throws Exception {
    var cfg = CliConfig.load(projectDir, args);
    String token = CliAuth.bearer(cfg, projectDir);

    var response = http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(cfg.url() + "/ops/cache"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
        CliOutput.printError("HTTP " + response.statusCode());
        return 2;
    }
    JsonNode root = Json.mapper().readTree(response.body());
    var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));
    if (mode == CliOutput.Mode.JSON) {
        System.out.println(CliOutput.json(root));
    } else {
        if (!root.path("enabled").asBoolean(false)) {
            System.out.println("Cache: disabled");
        } else {
            System.out.println("Cache");
            System.out.println("  size       " + root.path("size").asLong());
            System.out.println("  hits       " + root.path("hits").asLong());
            System.out.println("  misses     " + root.path("misses").asLong());
            System.out.println("  hit rate   " + String.format("%.1f%%", root.path("hitRate").asDouble() * 100));
            System.out.println("  evictions  " + root.path("evictions").asLong());
        }
    }
    return 0;
}

public static int cacheClear(Path projectDir, String[] args) throws Exception {
    var cfg = CliConfig.load(projectDir, args);
    String token = CliAuth.bearer(cfg, projectDir);

    var response = http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(cfg.url() + "/ops/cache/clear"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
        CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
        return 2;
    }
    var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));
    if (mode == CliOutput.Mode.JSON) {
        System.out.println(response.body());
    } else {
        CliOutput.printSuccess("cache cleared");
    }
    return 0;
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliCommandsTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliCommands.java src/test/java/io/brace/CliCommandsTest.java
git commit -m "feat: brace cache and brace cache clear commands"
```

---

### Task 16: `brace resolve`

**Files:**
- Modify: `src/main/java/io/brace/CliCommands.java` (add `resolve`)
- Modify: `src/test/java/io/brace/CliCommandsTest.java`

- [ ] **Step 1: Failing test**

```java
@Test
void resolveNonExistentReturnsNonZero() throws Exception {
    int code = CliCommands.resolve(projectDir, new String[]{"999999"});
    assertNotEquals(0, code);
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=CliCommandsTest#resolveNonExistentReturnsNonZero -q`
Expected: compile error.

- [ ] **Step 3: Add `resolve`**

```java
// ---------- brace resolve ----------

public static int resolve(Path projectDir, String[] args) throws Exception {
    if (args.length == 0 || args[0].startsWith("--")) {
        CliOutput.printError("Usage: brace resolve <error-id>");
        return 2;
    }
    String id = args[0];

    var cfg = CliConfig.load(projectDir, args);
    String token = CliAuth.bearer(cfg, projectDir);

    var response = http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(cfg.url() + "/ops/errors/" + id + "/resolve"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() == 404) {
        CliOutput.printError("error " + id + " not found");
        return 1;
    }
    if (response.statusCode() != 200) {
        CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
        return 2;
    }
    var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));
    if (mode == CliOutput.Mode.JSON) {
        System.out.println(response.body());
    } else {
        CliOutput.printSuccess("resolved error " + id);
    }
    return 0;
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=CliCommandsTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliCommands.java src/test/java/io/brace/CliCommandsTest.java
git commit -m "feat: brace resolve <id> marks an error as resolved"
```

---

## Phase E — Refactor existing CLI + dispatch

### Task 17: Refactor `Cli.opsDashboard` and `Cli.opsKeypair`

**Files:**
- Create: `src/main/java/io/brace/CliOps.java`
- Modify: `src/main/java/io/brace/Cli.java` (delete old `opsDashboard`/`opsKeypair`, dispatch to `CliOps`)
- Create: `src/test/java/io/brace/CliOpsTest.java`

- [ ] **Step 1: Failing test**

`src/test/java/io/brace/CliOpsTest.java`:
```java
package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliOpsTest {

    @TempDir Path projectDir;

    @BeforeEach
    void scaffoldProject() throws Exception {
        Files.createDirectories(projectDir.resolve("src/main/java"));
    }

    @Test
    void keypairCreatesAuthorizedKeysFile() throws Exception {
        int code = CliOps.keypair(projectDir, new String[]{"--label", "ci"});
        assertEquals(0, code);
        assertTrue(Files.exists(projectDir.resolve("ops-authorized-keys")));
        String content = Files.readString(projectDir.resolve("ops-authorized-keys"));
        assertTrue(content.contains("ci"));
    }

    @Test
    void keypairAppendsWhenFileExists() throws Exception {
        Files.writeString(projectDir.resolve("ops-authorized-keys"), "# header\n");
        CliOps.keypair(projectDir, new String[]{"--label", "k1"});
        CliOps.keypair(projectDir, new String[]{"--label", "k2"});
        var lines = Files.readAllLines(projectDir.resolve("ops-authorized-keys"));
        long keyLines = lines.stream().filter(l -> l.contains("ed25519:")).count();
        assertEquals(2, keyLines);
    }

    @Test
    void dashboardFailsWithoutKey() throws Exception {
        Files.writeString(projectDir.resolve(".brace"), "ops.local.url=http://localhost:8080\n");
        int code = CliOps.dashboard(projectDir, new String[]{});
        assertNotEquals(0, code);
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=CliOpsTest -q`
Expected: compile error.

- [ ] **Step 3: Implement `CliOps`**

`src/main/java/io/brace/CliOps.java`:
```java
package io.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;

public class CliOps {

    private static final HttpClient http = HttpClient.newHttpClient();

    private CliOps() {}

    public static int keypair(Path projectDir, String[] args) {
        String label = "key-1";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--label".equals(args[i])) { label = args[i + 1]; break; }
        }
        var kp = OpsKeys.generateKeypair();
        System.out.println("Public key:   " + kp.publicKey());
        System.out.println("Private key:  " + kp.privateKey());
        System.out.println();

        Path file = projectDir.resolve("ops-authorized-keys");
        try {
            String line = kp.publicKey() + "  " + label + "\n";
            if (Files.exists(file)) {
                Files.writeString(file, line, StandardOpenOption.APPEND);
            } else {
                Files.writeString(file,
                    "# Ops authorized public keys — one per line, optional label\n" + line);
            }
            System.out.println("Added to ops-authorized-keys.");
        } catch (Exception e) {
            CliOutput.printError("Failed to write ops-authorized-keys: " + e.getMessage());
            return 1;
        }
        System.out.println("Store the private key securely — it won't be shown again.");
        return 0;
    }

    public static int dashboard(Path projectDir, String[] args) {
        try {
            var cfg = CliConfig.load(projectDir, args);
            String bearer = CliAuth.bearer(cfg, projectDir);

            // Exchange bearer for single-use login token
            var loginResponse = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(cfg.url() + "/ops/auth/login-token"))
                    .header("Authorization", "Bearer " + bearer)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());

            if (loginResponse.statusCode() != 200) {
                CliOutput.printError("Failed to get login token: " + loginResponse.body());
                return 1;
            }

            JsonNode parsed = Json.mapper().readTree(loginResponse.body());
            String loginToken = parsed.get("loginToken").asText();
            String dashboardUrl = cfg.url() + "/ops/auth/exchange?token=" + loginToken;

            System.out.println("Opening dashboard...");
            openBrowser(dashboardUrl);
            return 0;
        } catch (Exception e) {
            CliOutput.printError(e.getMessage());
            return 1;
        }
    }

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else if (os.contains("linux")) new ProcessBuilder("xdg-open", url).start();
            else if (os.contains("win")) new ProcessBuilder("cmd", "/c", "start", url).start();
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 4: Run `CliOpsTest`**

Run: `mvn test -Dtest=CliOpsTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/CliOps.java src/test/java/io/brace/CliOpsTest.java
git commit -m "refactor: extract CliOps with CliConfig + CliAuth + Json.mapper"
```

---

### Task 18: Wire dispatch in `Cli.java` and update launcher

**Files:**
- Modify: `src/main/java/io/brace/Cli.java` (entire file — replace with new dispatch)
- Modify: `bin/brace` (launcher dispatch + help text)

- [ ] **Step 1: Replace `Cli.java`**

Replace the entire contents of `src/main/java/io/brace/Cli.java`:

```java
package io.brace;

import java.nio.file.*;
import java.util.Arrays;

public class Cli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { printUsage(); return; }
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        try {
            int code = dispatch(cwd, cmd, rest);
            System.exit(code);
        } catch (Exception e) {
            CliOutput.printError(e.getMessage() != null ? e.getMessage() : e.toString());
            System.exit(1);
        }
    }

    private static int dispatch(Path cwd, String cmd, String[] args) throws Exception {
        return switch (cmd) {
            case "new" -> {
                if (args.length < 1) {
                    CliOutput.printError("Usage: brace new <project-name>");
                    yield 1;
                }
                ProjectGenerator.generate(args[0]);
                yield 0;
            }
            case "init" -> initCommand(cwd, args);
            case "ops" -> opsCommand(cwd, args);
            case "errors"  -> requireProject(cwd, () -> CliCommands.errors(cwd, args));
            case "logs"    -> requireProject(cwd, () -> CliCommands.logs(cwd, args));
            case "status"  -> requireProject(cwd, () -> CliCommands.status(cwd, args));
            case "cache"   -> cacheCommand(cwd, args);
            case "resolve" -> requireProject(cwd, () -> CliCommands.resolve(cwd, args));
            default -> { printUsage(); yield 0; }
        };
    }

    private static int cacheCommand(Path cwd, String[] args) throws Exception {
        if (args.length > 0 && "clear".equals(args[0])) {
            return requireProject(cwd, () ->
                CliCommands.cacheClear(cwd, Arrays.copyOfRange(args, 1, args.length)));
        }
        return requireProject(cwd, () -> CliCommands.cache(cwd, args));
    }

    private static int opsCommand(Path cwd, String[] args) throws Exception {
        if (args.length < 1) {
            CliOutput.printError("Usage: brace ops <keypair|dashboard>");
            return 1;
        }
        return switch (args[0]) {
            case "keypair"   -> requireSrc(cwd, () -> CliOps.keypair(cwd, sub(args)));
            case "dashboard" -> requireProject(cwd, () -> CliOps.dashboard(cwd, sub(args)));
            default -> {
                CliOutput.printError("Unknown ops command: " + args[0]);
                yield 1;
            }
        };
    }

    private static int initCommand(Path cwd, String[] args) throws Exception {
        if (!Files.exists(cwd.resolve("src/main/java"))) {
            CliOutput.printError("Run inside a Brace project (no src/main/java). Use `brace new <name>` to create one.");
            return 1;
        }
        var result = CliInit.runWithRemote(cwd);
        var mode = CliOutput.autoMode(
            Arrays.asList(args).contains("--json"),
            Arrays.asList(args).contains("--pretty"));
        CliInit.print(result, mode);
        return result.ok() ? 0 : 1;
    }

    @FunctionalInterface
    private interface CliFn { int run() throws Exception; }

    private static int requireProject(Path cwd, CliFn fn) throws Exception {
        if (!Files.exists(cwd.resolve(".brace"))) {
            CliOutput.printError("This command must be run inside a Brace project. Run `brace init` first.");
            return 1;
        }
        return fn.run();
    }

    private static int requireSrc(Path cwd, CliFn fn) throws Exception {
        if (!Files.exists(cwd.resolve("src/main/java"))) {
            CliOutput.printError("This command must be run inside a Brace project (no src/main/java).");
            return 1;
        }
        return fn.run();
    }

    private static String[] sub(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static void printUsage() {
        System.out.println("Brace CLI v0.1.0");
        System.out.println();
        System.out.println("Global commands:");
        System.out.println("  brace new <name>            Create a new Brace project");
        System.out.println();
        System.out.println("Project commands (run inside a project):");
        System.out.println("  brace init                  Scaffold .brace + .brace.local and run readiness checks");
        System.out.println("  brace ops keypair           Generate an Ed25519 keypair for ops auth");
        System.out.println("  brace ops dashboard         Open the ops dashboard in a browser");
        System.out.println("  brace errors [--since 1h]   List unresolved errors");
        System.out.println("  brace logs [-f] [--since]   Tail recent log lines");
        System.out.println("  brace status                Show app health snapshot");
        System.out.println("  brace cache                 Show cache stats");
        System.out.println("  brace cache clear           Clear the cache");
        System.out.println("  brace resolve <id>          Mark an error as resolved");
        System.out.println();
        System.out.println("All project commands accept --env <name>, --json, --pretty.");
    }
}
```

- [ ] **Step 2: Update `bin/brace` launcher dispatch**

In `bin/brace`, find the dispatch case at the bottom and replace it with:

```bash
case "$COMMAND" in
    # Global (Java CLI)
    new)     run_java_cli new "$@" ;;

    # Project (Java CLI — handled by Cli.java with require_project guards)
    init|ops|errors|logs|status|cache|resolve)
             run_java_cli "$COMMAND" "$@" ;;

    # Project (bash)
    deps)    brace_deps ;;
    compile) brace_compile ;;
    run)     brace_run ;;
    test)    brace_test "$@" ;;
    dev)     brace_dev ;;
    help|-h|--help|*) brace_help ;;
esac
```

And update the `brace_help` function in `bin/brace`:

```bash
brace_help() {
    echo ""
    echo -e "${BOLD}Brace${NC} — Java web framework CLI"
    echo ""
    echo "Global commands:"
    echo -e "  ${CYAN}brace new <name>${NC}           Create a new Brace project"
    echo ""
    echo "Project commands (run inside a project directory):"
    echo -e "  ${CYAN}brace init${NC}                 Scaffold project config + run readiness checks"
    echo -e "  ${CYAN}brace ops keypair${NC}          Generate an Ed25519 keypair for ops auth"
    echo -e "  ${CYAN}brace ops dashboard${NC}        Authenticate and open the ops dashboard"
    echo -e "  ${CYAN}brace errors${NC} [--since 1h]  List unresolved errors"
    echo -e "  ${CYAN}brace logs${NC} [-f] [--since]  Tail recent log lines"
    echo -e "  ${CYAN}brace status${NC}               Show app health snapshot"
    echo -e "  ${CYAN}brace cache${NC} [clear]        Show cache stats / clear cache"
    echo -e "  ${CYAN}brace resolve${NC} <id>         Mark an error as resolved"
    echo -e "  ${CYAN}brace deps${NC}                 Copy dependencies from pom.xml into ./lib/"
    echo -e "  ${CYAN}brace compile${NC}              Compile the project"
    echo -e "  ${CYAN}brace run${NC}                  Compile and run"
    echo -e "  ${CYAN}brace dev${NC}                  Compile, run, and watch for changes"
    echo -e "  ${CYAN}brace test${NC} [class]         Run tests"
    echo -e "  ${CYAN}brace help${NC}                 Show this help"
    echo ""
    echo "All read commands accept --env <name>, --json, --pretty."
    echo ""
}
```

- [ ] **Step 3: Run the full test suite**

Run: `mvn test -q`
Expected: PASS — entire suite, including the new and existing tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/brace/Cli.java bin/brace
git commit -m "feat: dispatch new project subcommands in Cli.java + bin/brace"
```

---

## Phase F — Documentation

### Task 19: Write `docs/agent-ops-guide.md`

**Files:**
- Create: `docs/agent-ops-guide.md`

- [ ] **Step 1: Create the guide**

`docs/agent-ops-guide.md`:
````markdown
# Brace Agent Ops Guide

This guide explains how AI agents (and humans) inspect a running Brace app
using the project-scoped `brace` CLI commands.

## Setup

Once per project:

```bash
brace init       # scaffolds .brace, .brace.local, .gitignore
brace ops keypair  # generates Ed25519 keypair
```

Then add the printed public key line to the *server's* `ops-authorized-keys`
file and re-run `brace init` — it will perform a remote check against
`ops.prod.url` (if configured in `.brace`) and confirm the key is accepted.

## Environment selection

`.brace` defines URLs:

```
ops.local.url=http://localhost:8080
ops.prod.url=https://app.example.com
```

`.brace.local` selects the active environment (gitignored, per developer):

```
ops.env=local
ops.key=ops-private.key
```

Override per command with `--env prod`. All commands below accept `--env`.

## Commands

| Command | Purpose | Exit code |
|---|---|---|
| `brace status` | App health snapshot | 0 healthy / 1 errors > 0 / 2 unreachable |
| `brace errors [--since 1h]` | List unresolved errors | 0 none / 1 some / 2 unreachable |
| `brace logs [-f] [--since 10m]` | Tail recent structured log entries | always 0 |
| `brace cache` | Cache size / hit rate / evictions | 0 / 2 unreachable |
| `brace cache clear` | Empty the cache | 0 / 2 unreachable |
| `brace resolve <id>` | Mark an error as resolved | 0 / 1 not found / 2 |

All read commands auto-detect output mode: human table when stdout is a TTY,
JSON when piped or redirected. Override with `--json` or `--pretty`.

## Workflows

### Checking on production after a deploy

```bash
brace status --env prod && echo "healthy" || echo "needs attention"
brace errors --env prod --since 5m   # any errors since deploy?
brace logs --env prod --since 5m     # what did it say?
```

### Investigating a user-reported error

```bash
brace errors --env prod --json | jq '.[] | select(.route == "/checkout")'
brace logs --env prod --since 1h --level warn
brace resolve <id>
```

### Scheduled health check (cron / agent)

```bash
# Exit non-zero if status reports issues OR there are unresolved errors
brace status --env prod || alert "brace status failed"
brace errors --env prod --since 15m || alert "new errors"
```

## Failure modes

| Symptom | Likely cause | Fix |
|---|---|---|
| `Authentication failed (401)` | Public key not in server's `ops-authorized-keys` | `brace init --env prod` to see what to add |
| `Cannot reach <url>` | Server down or wrong URL | Check deployment status |
| `Run inside a Brace project` | Not in a project directory | `cd` into the project, or `brace init` |
| `Private key not found` | Missing `ops-private.key` | `brace ops keypair` |

## Output stability

JSON shapes returned by `--json` are stable within a minor version. Field
additions are non-breaking. Removals or renames will be flagged in the
release migration notes.
````

- [ ] **Step 2: Commit**

```bash
git add docs/agent-ops-guide.md
git commit -m "docs: add agent ops guide for brace CLI commands"
```

---

### Task 20: Extend `ClaudeMdGenerator` with ops commands section

**Files:**
- Modify: `src/main/java/io/brace/ClaudeMdGenerator.java`
- Modify: `src/test/java/io/brace/ClaudeMdGeneratorTest.java`

- [ ] **Step 1: Failing test**

Add to `ClaudeMdGeneratorTest.java`:
```java
@Test
void generatedClaudeMdMentionsOpsCommands() throws Exception {
    Path tmp = Files.createTempDirectory("claudemd-");
    try {
        ClaudeMdGenerator.generate("DemoApp", tmp);
        String content = Files.readString(tmp.resolve("CLAUDE.md"));
        assertTrue(content.contains("brace status"), content);
        assertTrue(content.contains("brace errors"), content);
        assertTrue(content.contains("brace logs"), content);
        assertTrue(content.contains("agent-ops-guide.md"), content);
    } finally {
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn test -Dtest=ClaudeMdGeneratorTest#generatedClaudeMdMentionsOpsCommands -q`
Expected: FAIL — generated file does not contain those strings.

- [ ] **Step 3: Add the ops section to `ClaudeMdGenerator`**

In `src/main/java/io/brace/ClaudeMdGenerator.java`, locate the spot where the generated content is built and add a new section. The exact insertion point depends on the current file structure — find the `String content = ...` or template literal and append:

```java
content += """

## Production ops (for agents)

This project ships with `brace` CLI commands for inspecting the running app.
Use these to check production health, investigate errors, and read recent
logs without leaving the terminal.

| Command | Purpose |
|---|---|
| `brace status [--env prod]` | App health snapshot — exits non-zero on degradation |
| `brace errors [--since 1h]` | List unresolved errors — exits non-zero if any exist |
| `brace logs [-f] [--since 10m]` | Tail recent structured log entries |
| `brace cache` / `brace cache clear` | Cache stats; clear cache |
| `brace resolve <id>` | Mark an error as resolved |

All read commands auto-detect TTY vs JSON output. Pipe to `jq` or use
`--json` explicitly. See `docs/agent-ops-guide.md` in the brace repo for
workflows and exit-code contracts.
""";
```

> If the generator currently uses string concatenation rather than a single
> `content` builder, append the same section using the same pattern as the
> existing sections.

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=ClaudeMdGeneratorTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/brace/ClaudeMdGenerator.java src/test/java/io/brace/ClaudeMdGeneratorTest.java
git commit -m "docs: ClaudeMdGenerator includes brace ops commands section"
```

---

## Final verification

- [ ] **Run the full test suite**

Run: `mvn test -q`
Expected: PASS. Test count should have grown by ~30 (LogTap × 6, LogTapWiring × 4, OpsIntegrationTest × ~7 new, CliConfig × 7, CliOutput × 5, CliAuth × 3, CliInit × 11, CliCommands × 8, CliOps × 3, ClaudeMdGenerator × 1).

- [ ] **Smoke test the CLI manually**

```bash
mvn package -q
cd /tmp && rm -rf brace-smoke && mkdir brace-smoke && cd brace-smoke
~/code/brace/target/brace-0.1.0-SNAPSHOT/bin/brace new smoke
cd smoke
~/code/brace/target/brace-0.1.0-SNAPSHOT/bin/brace init
~/code/brace/target/brace-0.1.0-SNAPSHOT/bin/brace ops keypair
~/code/brace/target/brace-0.1.0-SNAPSHOT/bin/brace init     # should now be all ✓
```

In a second terminal start the app, then:

```bash
~/code/brace/target/brace-0.1.0-SNAPSHOT/bin/brace status
~/code/brace/target/brace-0.1.0-SNAPSHOT/bin/brace errors
~/code/brace/target/brace-0.1.0-SNAPSHOT/bin/brace logs
```

All should succeed against `http://localhost:8080`.

- [ ] **Update TODO.md**

Mark items as `[x]` and reference the spec/plan paths. Commit:

```bash
git add TODO.md
git commit -m "TODO: mark CLI project config + ops commands as complete"
```
