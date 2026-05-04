# Ops Dashboard Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the ops dashboard with a TUI-inspired design (GitHub Dark + Tokyo Night colors), add sparkline graphs, and improve JFR data formatting.

**Architecture:** Pure restyle of `OpsDashboard.java` (string-built HTML/CSS), one small data change in `Stats.java` to capture heap usage per minute, and test updates. No new files, no new dependencies.

**Tech Stack:** Java 21, inline HTML/CSS, htmx (existing)

---

### Task 1: Add heap usage to minute snapshots

Capture `heapUsedMB` in each minute snapshot so the dashboard can render a heap sparkline.

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/Stats.java:97-116` (snapshot method + record)
- Test: `src/test/java/com/larvalabs/brace/StatsTest.java` (new file)

- [ ] **Step 1: Write the failing test**

```java
package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatsTest {

    @Test
    void minuteSnapshotIncludesHeapUsedMB() {
        var stats = new Stats();
        stats.recordRequest("GET", "/test", 200, 1000, 0, 0);
        var snap = stats.snapshot();
        assertTrue(snap.heapUsedMB() > 0, "heapUsedMB should be captured from runtime");
    }

    @Test
    void minuteSnapshotsReturnCapturedHeap() {
        var stats = new Stats();
        stats.recordRequest("GET", "/test", 200, 1000, 0, 0);
        stats.snapshot();
        var snapshots = stats.minuteSnapshots();
        assertFalse(snapshots.isEmpty());
        assertTrue(snapshots.getFirst().heapUsedMB() > 0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=StatsTest -pl .`
Expected: FAIL — `heapUsedMB()` method does not exist on `MinuteSnapshot`

- [ ] **Step 3: Add heapUsedMB to MinuteSnapshot and snapshot()**

In `Stats.java`, update the `MinuteSnapshot` record (line 161) to add the field:

```java
public record MinuteSnapshot(
    Instant ts,
    long requests,
    long errors,
    long totalLatencyUs,
    long maxLatencyUs,
    long queries,
    long queryUs,
    long heapUsedMB
) {
    public double avgLatencyMs() {
        if (requests == 0) return 0.0;
        return (totalLatencyUs / (double) requests) / 1000.0;
    }
}
```

In the `snapshot()` method (line 97), capture heap and pass it to the constructor:

```java
public MinuteSnapshot snapshot() {
    long requests = requestCount.sumThenReset();
    long errs = errorCount.sumThenReset();
    long latencyUs = totalLatencyUs.sumThenReset();
    long queries = totalQueryCount.sumThenReset();
    long queryUs = totalQueryUs.sumThenReset();
    long maxUs = maxLatencyUs.getAndSet(0);
    long heapMB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);

    var snapshot = new MinuteSnapshot(
        Instant.now(), requests, errs, latencyUs, maxUs, queries, queryUs, heapMB
    );

    synchronized (ringLock) {
        ringBuffer[ringHead] = snapshot;
        ringHead = (ringHead + 1) % ringBuffer.length;
        if (ringSize < ringBuffer.length) ringSize++;
    }

    return snapshot;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=StatsTest -pl .`
Expected: PASS

- [ ] **Step 5: Run full test suite**

Run: `mvn test`
Expected: All tests pass (existing code doesn't access `heapUsedMB` yet)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/larvalabs/brace/Stats.java src/test/java/com/larvalabs/brace/StatsTest.java
git commit -m "Add heapUsedMB to minute snapshots for dashboard sparkline"
```

---

### Task 2: Restyle CSS, header, and stat cards

Replace the entire `<style>` block and restyle the header bar and stat cards with the new TUI design.

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/OpsDashboard.java:50-139`

- [ ] **Step 1: Replace the CSS block**

Replace the `<style>` block (lines 57-90) with the new TUI theme:

```java
sb.append("""
    <!DOCTYPE html>
    <html>
    <head>
    <title>Brace Ops</title>
    <meta charset="UTF-8">
    <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { background: #0d1117; color: #c9d1d9; font-family: 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 12px; padding: 16px; }
    .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #30363d; padding-bottom: 10px; margin-bottom: 12px; }
    .header .title { color: #7aa2f7; font-weight: bold; font-size: 14px; }
    .header .meta { color: #565f89; }
    .stats-row { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
    .stat-card { flex: 1; border: 1px solid #30363d; padding: 8px; min-width: 120px; }
    .stat-card .label { color: #565f89; font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px; }
    .stat-card .value { font-size: 20px; font-weight: bold; margin-top: 2px; }
    .stat-card .detail { color: #565f89; font-size: 10px; margin-top: 1px; }
    .section { border: 1px solid #30363d; padding: 10px; margin-bottom: 14px; }
    .section-head { font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 8px; border-bottom: 1px solid #30363d; padding-bottom: 6px; }
    .two-col { display: flex; gap: 10px; margin-bottom: 14px; }
    .two-col > div { flex: 1; }
    @media (max-width: 800px) { .two-col { flex-direction: column; } }
    table { border-collapse: collapse; width: 100%; }
    th { text-align: left; color: #565f89; font-size: 9px; text-transform: uppercase; padding: 3px 0; }
    td { padding: 3px 0; }
    .sparkline { display: flex; align-items: flex-end; gap: 1px; height: 45px; }
    .sparkline .bar { flex: 1; min-width: 4px; }
    .sparkline .bar-lo { background: #238636; }
    .sparkline .bar-md { background: #2ea043; }
    .sparkline .bar-hi { background: #3fb950; }
    .sparkline-sm { height: 25px; }
    .sparkline .bar-err { background: #f7768e; }
    .sparkline .bar-heap { background: #bb9af7; }
    .c-blue { color: #7aa2f7; }
    .c-green { color: #9ece6a; }
    .c-purple { color: #bb9af7; }
    .c-amber { color: #e0af68; }
    .c-red { color: #f7768e; }
    .c-cyan { color: #7dcfff; }
    .c-muted { color: #565f89; }
    .pkg { color: #565f89; display: inline-block; max-width: 30ch; overflow: hidden; text-overflow: ellipsis; direction: rtl; text-align: left; vertical-align: bottom; }
    .method { color: #c9d1d9; font-weight: bold; }
    .ok-dot { color: #9ece6a; }
    .err-dot { color: #f7768e; }
    .btn { background: transparent; color: #7aa2f7; border: 1px solid #30363d; padding: 2px 8px; cursor: pointer; font-family: inherit; font-size: 11px; }
    .btn:hover { border-color: #7aa2f7; }
    .btn-resolve { color: #9ece6a; }
    .btn-resolve:hover { border-color: #9ece6a; }
    .btn-danger { color: #f7768e; }
    .btn-danger:hover { border-color: #f7768e; }
    .tab-bar { display: flex; gap: 0; margin-bottom: 0; }
    .tab { background: transparent; border: 1px solid #30363d; border-bottom: none; padding: 4px 16px; cursor: pointer; color: #565f89; font-family: inherit; font-size: 11px; }
    .tab.active { color: #7aa2f7; border-color: #7aa2f7; border-bottom: 1px solid #0d1117; margin-bottom: -1px; z-index: 1; position: relative; }
    .tab-content { border: 1px solid #30363d; border-top: 1px solid #7aa2f7; padding: 10px; margin-bottom: 14px; }
    .stack-trace { max-height: 200px; overflow-y: auto; background: #161b22; padding: 8px; margin-top: 4px; font-size: 11px; white-space: pre-wrap; word-break: break-all; border: 1px solid #30363d; color: #c9d1d9; }
    </style>
    <script src="/__brace/htmx.min.js"></script>
    </head>
    <body>
    """);
```

- [ ] **Step 2: Restyle the header**

Replace the header section (lines 100-106) with:

```java
// Header
sb.append("<div class=\"header\">");
sb.append("<span class=\"title\">┌ BRACE</span>");
sb.append("<span class=\"meta\">↑ ").append(esc(uptime))
  .append(" │ Java ").append(esc(System.getProperty("java.version")))
  .append(" │ started ").append(esc(stats.startedAt().toString().substring(0, 16).replace("T", " ")))
  .append(" │ 5s refresh</span>");
sb.append("</div>\n");
```

- [ ] **Step 3: Restyle the stat cards with color coding**

Replace the `statCard` helper method and the stat cards section. First, replace the helper (line 376):

```java
private static void statCard(StringBuilder sb, String label, String value, String colorClass) {
    sb.append("<div class=\"stat-card\"><div class=\"label\">").append(esc(label))
      .append("</div><div class=\"value ").append(colorClass).append("\">").append(esc(value))
      .append("</div></div>");
}

private static void statCard(StringBuilder sb, String label, String value, String detail, String colorClass) {
    sb.append("<div class=\"stat-card\"><div class=\"label\">").append(esc(label))
      .append("</div><div class=\"value ").append(colorClass).append("\">").append(esc(value))
      .append("</div><div class=\"detail\">").append(esc(detail)).append("</div></div>");
}
```

Then update the stat cards rendering (lines 109-139):

```java
// Stat cards
sb.append("<div class=\"stats-row\">");
statCard(sb, "Requests", String.valueOf(totalReqs), "", "c-blue");
statCard(sb, "Error Rate", errRate + "%", errCount + " total", Double.parseDouble(errRate) > 5 ? "c-red" : "c-green");
statCard(sb, "Heap", heapUsed + "M", "/ " + heapMax + "M", "c-purple");
if (jvmSnap != null) {
    var cpu = (Map<String, Object>) jvmSnap.get("cpu");
    var threads = (Map<String, Object>) jvmSnap.get("threads");
    var gc = (Map<String, Object>) jvmSnap.get("gc");
    double cpuPct = (double) cpu.get("jvmUser") * 100;
    statCard(sb, "CPU", String.format("%.0f%%", cpuPct), "", cpuPct > 80 ? "c-red" : cpuPct > 50 ? "c-amber" : "c-amber");
    statCard(sb, "Threads", String.valueOf(threads.get("active")), threads.get("daemon") + " daemon", "c-cyan");
    double avgGc = (double) gc.get("avgPauseMs");
    statCard(sb, "GC Avg", String.format("%.0fms", avgGc), gc.get("totalCount") + " pauses", avgGc > 100 ? "c-red" : avgGc > 10 ? "c-amber" : "c-green");
} else {
    var threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
    statCard(sb, "CPU", "-", "", "c-amber");
    statCard(sb, "Threads", String.valueOf(threadBean.getThreadCount()), "", "c-cyan");
    statCard(sb, "GC", "-", "", "c-green");
}
if (cache != null) {
    long hits = cache.hits(), misses = cache.misses();
    String hitRate = (hits + misses) > 0 ? ((hits * 100) / (hits + misses)) + "%" : "-";
    statCard(sb, "Cache", cache.size() + " entries", "hit rate " + hitRate, "c-amber");
}
if (mailer != null) {
    String mailDetail = mailer.failCount() > 0 ? mailer.failCount() + " failed" : "";
    statCard(sb, "Emails", String.valueOf(mailer.sentCount()), mailDetail, "c-cyan");
}
sb.append("</div>\n");
```

- [ ] **Step 4: Run tests to verify nothing is broken**

Run: `mvn test -Dtest=OpsIntegrationTest -pl .`
Expected: Most tests pass. Some tests that check for specific text like `"Brace Dashboard"` will fail — we renamed the title to `"Brace Ops"`. Fix in Task 6.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/larvalabs/brace/OpsDashboard.java
git commit -m "Restyle dashboard CSS, header, and stat cards with TUI theme"
```

---

### Task 3: Restyle sparklines and add error/heap sparklines

Enhance the req/min sparkline with green gradient coloring and add error rate and heap usage sparklines.

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/OpsDashboard.java:142-154` (sparkline section)

- [ ] **Step 1: Replace the req/min sparkline and add new sparklines**

Replace the sparkline section (lines 142-154) with:

```java
// Sparklines
if (!minutes.isEmpty()) {
    // Req/min sparkline
    sb.append("<div class=\"section\">");
    sb.append("<div style=\"color:#565f89;font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;\">")
      .append("Requests / Minute <span style=\"float:right\">last ").append(minutes.size()).append(" min</span></div>");
    long maxReq = Math.max(1, minutes.stream().mapToLong(Stats.MinuteSnapshot::requests).max().orElse(1));
    sb.append("<div class=\"sparkline\">");
    for (var m : minutes) {
        double pct = (m.requests() * 100.0) / maxReq;
        String barClass = pct > 75 ? "bar-hi" : pct > 40 ? "bar-md" : "bar-lo";
        sb.append("<div class=\"bar ").append(barClass).append("\" style=\"height:")
          .append(String.format("%.0f", Math.max(2, pct)))
          .append("%\" title=\"").append(m.requests()).append(" reqs, ")
          .append(String.format("%.1f", m.avgLatencyMs())).append(" ms avg @ ")
          .append(m.ts()).append("\"></div>");
    }
    sb.append("</div>\n");

    // Error rate sparkline
    long maxErr = Math.max(1, minutes.stream().mapToLong(Stats.MinuteSnapshot::errors).max().orElse(1));
    if (maxErr > 0) {
        sb.append("<div style=\"color:#565f89;font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-top:10px;margin-bottom:8px;\">")
          .append("Errors / Minute</div>");
        sb.append("<div class=\"sparkline sparkline-sm\">");
        for (var m : minutes) {
            double pct = (m.errors() * 100.0) / maxErr;
            sb.append("<div class=\"bar bar-err\" style=\"height:")
              .append(String.format("%.0f", Math.max(pct > 0 ? 4 : 0, pct)))
              .append("%\" title=\"").append(m.errors()).append(" errors @ ").append(m.ts())
              .append("\"></div>");
        }
        sb.append("</div>\n");
    }

    // Heap sparkline
    long maxHeap = Math.max(1, minutes.stream().mapToLong(Stats.MinuteSnapshot::heapUsedMB).max().orElse(1));
    sb.append("<div style=\"color:#565f89;font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-top:10px;margin-bottom:8px;\">")
      .append("Heap MB</div>");
    sb.append("<div class=\"sparkline sparkline-sm\">");
    for (var m : minutes) {
        double pct = (m.heapUsedMB() * 100.0) / maxHeap;
        sb.append("<div class=\"bar bar-heap\" style=\"height:")
          .append(String.format("%.0f", Math.max(4, pct)))
          .append("%\" title=\"").append(m.heapUsedMB()).append(" MB @ ").append(m.ts())
          .append("\"></div>");
    }
    sb.append("</div>\n");

    sb.append("</div>\n"); // close section
}
```

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest -pl .`
Expected: PASS (sparkline content is not asserted in tests beyond existence)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/larvalabs/brace/OpsDashboard.java
git commit -m "Add error rate and heap sparklines with green gradient bars"
```

---

### Task 4: Improve JFR data formatting

Dim package paths, bold class.method, left-ellipsis overflow. Color-code GC pause durations.

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/OpsDashboard.java:157-213` (JVM section)

- [ ] **Step 1: Add the method name formatting helper**

Add this helper method alongside the existing helpers at the bottom of `OpsDashboard.java`:

```java
private static String formatMethod(String method) {
    // Split into package.Class.method — dim the package, bold the class.method
    int lastDot = method.lastIndexOf('.');
    if (lastDot <= 0) return "<span class=\"method\">" + esc(method) + "</span>";
    int secondLastDot = method.lastIndexOf('.', lastDot - 1);
    if (secondLastDot <= 0) return "<span class=\"method\">" + esc(method) + "</span>";
    String pkg = method.substring(0, secondLastDot + 1);
    String classMethod = method.substring(secondLastDot + 1);
    return "<span class=\"pkg\" title=\"" + esc(method) + "\">" + esc(pkg)
        + "</span><span class=\"method\">" + esc(classMethod) + "</span>";
}

private static String formatClassName(String className) {
    int lastDot = className.lastIndexOf('.');
    if (lastDot <= 0) return "<span class=\"method\">" + esc(className) + "</span>";
    String pkg = className.substring(0, lastDot + 1);
    String name = className.substring(lastDot + 1);
    return "<span class=\"pkg\" title=\"" + esc(className) + "\">" + esc(pkg)
        + "</span><span class=\"method\">" + esc(name) + "</span>";
}
```

- [ ] **Step 2: Restyle the JVM section**

Replace the JVM profiling section (lines 157-213) with:

```java
// JVM profiling
if (jvmSnap != null) {
    // Hot Methods + Slowest Routes
    sb.append("<div class=\"two-col\">\n");

    var profiling = (Map<String, Object>) jvmSnap.get("profiling");
    var hotMethods = (List<Map<String, Object>>) profiling.get("hotMethods");
    sb.append("<div class=\"section\">");
    sb.append("<div class=\"section-head c-blue\">Hot Methods <span class=\"c-muted\" style=\"font-weight:normal\">— 5 min window</span></div>");
    if (hotMethods.isEmpty()) {
        sb.append("<p class=\"c-muted\">No samples yet</p>");
    } else {
        sb.append("<table><tr><th>Method</th><th style=\"text-align:right\">Samples</th></tr>");
        for (var m : hotMethods) {
            String method = (String) m.get("method");
            sb.append("<tr><td title=\"").append(esc(method)).append("\">").append(formatMethod(method))
              .append("</td><td style=\"text-align:right\" class=\"c-amber\">").append(m.get("samples")).append("</td></tr>");
        }
        sb.append("</table>");
    }
    sb.append("</div>\n");

    // Slowest routes (moved here from the old two-col below)
    sb.append("<div class=\"section\">");
    sb.append("<div class=\"section-head c-blue\">Slowest Routes <span class=\"c-muted\" style=\"font-weight:normal\">— avg latency</span></div>");
    sb.append("<table><tr><th>Route</th><th style=\"text-align:right\">Avg</th><th style=\"text-align:right\">Calls</th></tr>");
    for (var e : routeStats) {
        String[] parts = e.getKey().split(" ", 2);
        String httpMethod = parts[0];
        String path = parts.length > 1 ? parts[1] : e.getKey();
        String methodColor = switch (httpMethod) {
            case "GET" -> "c-green";
            case "POST" -> "c-amber";
            case "PUT" -> "c-blue";
            case "DELETE" -> "c-red";
            default -> "c-muted";
        };
        sb.append("<tr><td><span class=\"").append(methodColor).append("\">").append(esc(httpMethod))
          .append("</span> ").append(esc(path)).append("</td>");
        sb.append("<td style=\"text-align:right\" class=\"c-amber\">").append(String.format("%.0fms", e.getValue().avgLatencyMs())).append("</td>");
        sb.append("<td style=\"text-align:right\" class=\"c-muted\">").append(String.format("%,d", e.getValue().count())).append("</td></tr>");
    }
    sb.append("</table>");
    sb.append("</div>\n");

    sb.append("</div>\n"); // two-col

    // Allocations + GC Pauses
    sb.append("<div class=\"two-col\">\n");

    var topAllocs = (List<Map<String, Object>>) profiling.get("topAllocations");
    sb.append("<div class=\"section\">");
    sb.append("<div class=\"section-head c-purple\">Top Allocations <span class=\"c-muted\" style=\"font-weight:normal\">— 5 min window</span></div>");
    if (topAllocs.isEmpty()) {
        sb.append("<p class=\"c-muted\">No allocation data yet</p>");
    } else {
        sb.append("<table><tr><th>Class</th><th style=\"text-align:right\">Size</th></tr>");
        for (var a : topAllocs) {
            String className = (String) a.get("class");
            sb.append("<tr><td title=\"").append(esc(className)).append("\">").append(formatClassName(className))
              .append("</td><td style=\"text-align:right\" class=\"c-purple\">").append(formatBytes((long) a.get("bytes"))).append("</td></tr>");
        }
        sb.append("</table>");
    }
    sb.append("</div>\n");

    // GC pauses
    var gc = (Map<String, Object>) jvmSnap.get("gc");
    var pauses = (List<Map<String, Object>>) gc.get("recentPauses");
    sb.append("<div class=\"section\">");
    sb.append("<div class=\"section-head c-red\">Recent GC Pauses</div>");
    if (pauses.isEmpty()) {
        sb.append("<p class=\"c-muted\">No GC pauses recorded</p>");
    } else {
        sb.append("<table><tr><th>Time</th><th>Collector</th><th>Cause</th><th style=\"text-align:right\">Duration</th></tr>");
        for (var p : pauses) {
            String ts = (String) p.get("ts");
            String time = ts.length() > 19 ? ts.substring(11, 19) : ts;
            double durationMs = (double) p.get("durationMs");
            String durColor = durationMs > 100 ? "c-red" : durationMs > 10 ? "c-amber" : "c-green";
            String weight = durationMs > 100 ? "font-weight:bold" : "";
            sb.append("<tr><td class=\"c-muted\">").append(esc(time))
              .append("</td><td>").append(esc((String) p.get("collector")))
              .append("</td><td class=\"c-muted\">").append(esc((String) p.get("cause")))
              .append("</td><td style=\"text-align:right;").append(weight).append("\" class=\"").append(durColor).append("\">")
              .append(String.format("%.0fms", durationMs)).append("</td></tr>");
        }
        sb.append("</table>");
    }
    sb.append("</div>\n");

    sb.append("</div>\n"); // two-col
}
```

- [ ] **Step 3: Remove the old slowest routes / recent errors two-col block**

Delete the old two-col section (lines 216-244) that contained "Slowest Routes" and "Recent Errors (In-Memory)". Slowest routes moved into the JVM section above. Recent in-memory errors will be folded into the error tracking section in Task 5.

For the case where JFR is not active, add a standalone slowest routes section before the error tracking:

```java
// Slowest routes (standalone — when JFR is not active, it wasn't rendered in the JVM section)
if (jvmSnap == null && !routeStats.isEmpty()) {
    sb.append("<div class=\"section\">");
    sb.append("<div class=\"section-head c-blue\">Slowest Routes <span class=\"c-muted\" style=\"font-weight:normal\">— avg latency</span></div>");
    sb.append("<table><tr><th>Route</th><th style=\"text-align:right\">Avg</th><th style=\"text-align:right\">Calls</th></tr>");
    for (var e : routeStats) {
        String[] parts = e.getKey().split(" ", 2);
        String httpMethod = parts[0];
        String path = parts.length > 1 ? parts[1] : e.getKey();
        String methodColor = switch (httpMethod) {
            case "GET" -> "c-green";
            case "POST" -> "c-amber";
            case "PUT" -> "c-blue";
            case "DELETE" -> "c-red";
            default -> "c-muted";
        };
        sb.append("<tr><td><span class=\"").append(methodColor).append("\">").append(esc(httpMethod))
          .append("</span> ").append(esc(path)).append("</td>");
        sb.append("<td style=\"text-align:right\" class=\"c-amber\">").append(String.format("%.0fms", e.getValue().avgLatencyMs())).append("</td>");
        sb.append("<td style=\"text-align:right\" class=\"c-muted\">").append(String.format("%,d", e.getValue().count())).append("</td></tr>");
    }
    sb.append("</table>");
    sb.append("</div>\n");
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest -pl .`
Expected: PASS — tests check for "Hot Methods" and "Top Allocations" which are still present

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/larvalabs/brace/OpsDashboard.java
git commit -m "Improve JFR formatting: dim packages, bold methods, color-coded GC"
```

---

### Task 5: Restyle remaining sections

Restyle error tracking, jobs, cache, rate limiters, and status codes with the new TUI theme.

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/OpsDashboard.java:247-320` (remaining sections)

- [ ] **Step 1: Restyle the error tracking section**

Replace the error tracking section (lines 247-263) and the `renderPersistedErrors` method (lines 344-373):

```java
// Recent in-memory errors
if (!recentErrors.isEmpty()) {
    sb.append("<div class=\"section\">");
    sb.append("<div class=\"section-head c-red\">Recent Errors <span class=\"c-muted\" style=\"font-weight:normal\">— in-memory, ").append(recentErrors.size()).append(" tracked</span></div>");
    sb.append("<table><tr><th>Type</th><th>Route</th><th style=\"text-align:right\">Count</th><th style=\"text-align:right\">Last Seen</th></tr>");
    for (var e : recentErrors) {
        sb.append("<tr><td class=\"c-red\">").append(esc(e.type)).append("</td><td>")
          .append(esc(e.route != null ? e.route : "-")).append("</td><td style=\"text-align:right\">")
          .append(e.count).append("</td><td style=\"text-align:right\" class=\"c-muted\">")
          .append(esc(e.lastSeen != null ? e.lastSeen.toString().substring(11, 19) : "-")).append("</td></tr>");
    }
    sb.append("</table>");
    sb.append("</div>\n");
}

// Persisted error tracking
if (errorStore != null) {
    sb.append("<div class=\"tab-bar\">");
    sb.append("<div class=\"tab active\" onclick=\"showErrorTab('unresolved')\">Unresolved (")
      .append(unresolvedErrors.size()).append(")</div>");
    sb.append("<div class=\"tab\" onclick=\"showErrorTab('resolved')\">Resolved (")
      .append(resolvedErrors.size()).append(")</div>");
    sb.append("</div>\n");

    sb.append("<div id=\"tab-unresolved\" class=\"tab-content\" style=\"display:block\">");
    renderPersistedErrors(sb, unresolvedErrors, opsSecret, false);
    sb.append("</div>\n");

    sb.append("<div id=\"tab-resolved\" class=\"tab-content\" style=\"display:none\">");
    renderPersistedErrors(sb, resolvedErrors, opsSecret, true);
    sb.append("</div>\n");
}
```

Update `renderPersistedErrors` to use the new styles:

```java
private static void renderPersistedErrors(StringBuilder sb, List<Map<String, Object>> errors,
                                           String opsSecret, boolean resolved) {
    if (errors.isEmpty()) {
        sb.append("<p class=\"").append(resolved ? "c-muted" : "c-green").append("\">None</p>");
        return;
    }
    sb.append("<table><tr><th>Type</th><th>Route</th><th style=\"text-align:right\">Count</th><th>First Seen</th><th>Last Seen</th><th></th></tr>");
    for (var e : errors) {
        long id = ((Number) e.get("id")).longValue();
        sb.append("<tr>");
        sb.append("<td class=\"c-red\" style=\"cursor:pointer\" onclick=\"toggleTrace(this)\">")
          .append(esc(str(e.get("errorType")))).append("</td>");
        sb.append("<td>").append(esc(str(e.get("route"), "-"))).append("</td>");
        sb.append("<td style=\"text-align:right\">").append(e.get("occurrenceCount")).append("</td>");
        sb.append("<td class=\"c-muted\">").append(esc(str(e.get("firstSeen"), "-"))).append("</td>");
        sb.append("<td class=\"c-muted\">").append(esc(str(e.get("lastSeen"), "-"))).append("</td>");
        if (!resolved) {
            sb.append("<td><button class=\"btn btn-resolve\" hx-post=\"/ops/errors/").append(id)
              .append("/resolve?key=").append(esc(opsSecret))
              .append("\" hx-target=\"#dashboard-content\" hx-select=\"#dashboard-content\" hx-swap=\"outerHTML\">✓ resolve</button></td>");
        } else {
            sb.append("<td class=\"c-muted\">").append(esc(str(e.get("resolvedAt"), ""))).append("</td>");
        }
        sb.append("</tr>");
        sb.append("<tr style=\"display:none\"><td colspan=\"6\"><div class=\"stack-trace\">")
          .append(esc(str(e.get("stackTrace"), "No stack trace")))
          .append("</div><div style=\"margin-top:4px\" class=\"c-muted\">")
          .append(esc(str(e.get("message"), ""))).append("</div></td></tr>");
    }
    sb.append("</table>");
}
```

- [ ] **Step 2: Restyle jobs + cache as a two-column section**

Replace the jobs section (lines 266-278) and cache section (lines 299-311):

```java
// Jobs + Cache (two-column)
boolean hasJobs = !jobStatuses.isEmpty();
boolean hasCache = cache != null;
if (hasJobs || hasCache) {
    sb.append("<div class=\"two-col\">\n");

    if (hasJobs) {
        sb.append("<div class=\"section\">");
        sb.append("<div class=\"section-head c-cyan\">Scheduled Jobs</div>");
        sb.append("<table><tr><th>Name</th><th>Schedule</th><th>Status</th><th style=\"text-align:right\">Last Run</th></tr>");
        for (var j : jobStatuses) {
            String statusDot = "ok".equals(j.lastStatus()) ? "ok-dot" : "error".equals(j.lastStatus()) ? "err-dot" : "c-muted";
            String statusLabel = j.lastStatus() != null ? j.lastStatus() : "pending";
            String lastRun = "-";
            if (j.lastRun() != null) {
                String ago = formatDuration(Duration.between(j.lastRun(), now));
                lastRun = ago + " ago (" + j.lastDurationMs() + "ms)";
            }
            sb.append("<tr><td>").append(esc(j.name())).append("</td><td class=\"c-muted\">").append(esc(j.schedule())).append("</td>");
            sb.append("<td><span class=\"").append(statusDot).append("\">● </span>").append(esc(statusLabel)).append("</td>");
            sb.append("<td style=\"text-align:right\" class=\"c-muted\">").append(esc(lastRun)).append("</td></tr>");
        }
        sb.append("</table>");
        sb.append("</div>\n");
    }

    if (hasCache) {
        sb.append("<div class=\"section\">");
        sb.append("<div class=\"section-head c-amber\">Cache <span style=\"float:right;font-weight:normal;text-transform:none;letter-spacing:0\">");
        sb.append("<button class=\"btn btn-danger\" hx-post=\"/ops/cache/clear?key=").append(esc(opsSecret))
          .append("\" hx-target=\"#dashboard-content\" hx-select=\"#dashboard-content\" hx-swap=\"outerHTML\">[clear all]</button>");
        sb.append("</span></div>");
        long hits = cache.hits(), misses = cache.misses();
        String hitRate = (hits + misses) > 0 ? ((hits * 100) / (hits + misses)) + "%" : "-";
        sb.append("<div style=\"display:flex;gap:16px;margin-bottom:4px;\">");
        sb.append("<div><span class=\"c-muted\" style=\"font-size:9px\">ENTRIES</span><br/><span class=\"c-amber\" style=\"font-weight:bold\">").append(cache.size()).append("</span></div>");
        sb.append("<div><span class=\"c-muted\" style=\"font-size:9px\">HIT RATE</span><br/><span class=\"c-green\" style=\"font-weight:bold\">").append(hitRate).append("</span></div>");
        sb.append("<div><span class=\"c-muted\" style=\"font-size:9px\">MISSES</span><br/><span class=\"c-muted\" style=\"font-weight:bold\">").append(misses).append("</span></div>");
        sb.append("<div><span class=\"c-muted\" style=\"font-size:9px\">EVICTIONS</span><br/><span class=\"c-muted\" style=\"font-weight:bold\">").append(cache.evictions()).append("</span></div>");
        sb.append("</div>");
        sb.append("</div>\n");
    }

    sb.append("</div>\n"); // two-col
}
```

- [ ] **Step 3: Restyle rate limiters and status codes**

Replace the rate limiters section (lines 281-296) and status codes section (lines 313-319):

```java
// Rate limiters
if (!rateLimiterStats.isEmpty()) {
    sb.append("<div class=\"section\">");
    sb.append("<div class=\"section-head c-blue\">Rate Limiters</div>");
    sb.append("<table><tr><th>Limiter</th><th style=\"text-align:right\">Allowed</th><th style=\"text-align:right\">Blocked</th><th style=\"text-align:right\">Active</th><th style=\"text-align:right\">Limit</th></tr>");
    for (var rl : rateLimiterStats) {
        long allowed = ((Number) rl.get("allowed")).longValue();
        long blocked = ((Number) rl.get("blocked")).longValue();
        String blockPct = (allowed + blocked) > 0 ? String.format("%.1f%%", (blocked * 100.0) / (allowed + blocked)) : "0.0%";
        sb.append("<tr><td>").append(esc((String) rl.get("label"))).append("</td>");
        sb.append("<td style=\"text-align:right\" class=\"c-green\">").append(allowed).append("</td>");
        sb.append("<td style=\"text-align:right\" class=\"").append(blocked > 0 ? "c-red" : "c-muted").append("\">")
          .append(blocked).append(" (").append(blockPct).append(")</td>");
        sb.append("<td style=\"text-align:right\">").append(rl.get("activeWindows")).append("</td>");
        sb.append("<td style=\"text-align:right\" class=\"c-muted\">").append(rl.get("maxRequests")).append("/").append(rl.get("windowSeconds")).append("s</td></tr>");
    }
    sb.append("</table>");
    sb.append("</div>\n");
}

// Status codes
sb.append("<div class=\"section\">");
sb.append("<div class=\"section-head c-muted\">Status Codes</div>");
sb.append("<table><tr><th>Code</th><th style=\"text-align:right\">Count</th></tr>");
statusCodes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
    String codeColor = e.getKey() < 300 ? "c-green" : e.getKey() < 400 ? "c-blue" : e.getKey() < 500 ? "c-amber" : "c-red";
    sb.append("<tr><td class=\"").append(codeColor).append("\">").append(e.getKey())
      .append("</td><td style=\"text-align:right\">").append(e.getValue()).append("</td></tr>");
});
sb.append("</table>");
sb.append("</div>\n");
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest -pl .`
Expected: PASS — tests check for "Error Tracking", "Resolve"/"resolve", "Clear All"/"clear all", and htmx attributes which are all still present

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/larvalabs/brace/OpsDashboard.java
git commit -m "Restyle errors, jobs, cache, rate limiters, status codes"
```

---

### Task 6: Update tests for new markup

Fix any tests that assert on old markup text that changed in the restyle.

**Files:**
- Modify: `src/test/java/com/larvalabs/brace/OpsIntegrationTest.java`

- [ ] **Step 1: Run full test suite to identify failures**

Run: `mvn test`
Note which assertions fail (expected: title changed from "Brace Dashboard" to "Brace Ops", button text changes, etc.)

- [ ] **Step 2: Fix failing assertions**

Update `OpsIntegrationTest.java` — the likely changes:

In `opsDashboardWithValidKey` (line 91): `"Brace Dashboard"` → `"Brace Ops"`

In `clearCacheWithValidKey` (line 187): `"Brace Dashboard"` → `"Brace Ops"`

In `dashboardIncludesCacheSection` (line 197): `"Clear All"` → `"clear all"`

In `dashboardIncludesErrorTracking` (line 203): `"Resolve"` — check if the new text `"✓ resolve"` still matches the `contains("Resolve")` check. If not, update to `contains("resolve")`.

These are the expected changes. The exact fixes depend on what fails in Step 1 — update only what actually breaks.

- [ ] **Step 3: Run full test suite**

Run: `mvn test`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/larvalabs/brace/OpsIntegrationTest.java
git commit -m "Update dashboard tests for new TUI-styled markup"
```

---

### Task 7: Final verification

- [ ] **Step 1: Run full test suite one final time**

Run: `mvn test`
Expected: All tests pass

- [ ] **Step 2: Visual check**

Run: `./sample/brace sample` (if a sample app exists) or write a quick manual test to load the dashboard in a browser and verify:
- Dark background with Tokyo Night accent colors
- Stat cards are color-coded
- Sparklines show green gradient for req/min, red for errors, purple for heap
- Method names show dimmed package + bold class.method
- GC pauses are color-coded by duration
- All interactive features work (tab switching, stack trace toggle, resolve, clear cache)

- [ ] **Step 3: Final commit if any tweaks needed**

Only commit if visual check revealed issues that needed fixing.
