# Custom Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add app-level custom metrics (counters, gauges, timers) to Brace with dashboard sparklines and `/ops/status` JSON exposure.

**Architecture:** Extend the existing `Stats` class with three new metric types using the same lock-free patterns (LongAdder, AtomicLong). Custom metrics ride along in the existing `MinuteSnapshot` ring buffer. `OpsDashboard` renders a sparkline per metric. `/ops/status` includes a new `metrics` section.

**Tech Stack:** java.util.concurrent.atomic.LongAdder, ConcurrentHashMap, existing Stats/OpsDashboard infrastructure

**Spec:** `docs/superpowers/specs/2026-04-08-custom-metrics-design.md`

---

### Task 1: Counter Support in Stats

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/Stats.java`
- Modify: `src/test/java/com/larvalabs/brace/StatsTest.java`

- [ ] **Step 1: Write failing tests for counters**

Add to `StatsTest.java`:

```java
@Test
void counterIncrementsByOne() {
    var stats = new Stats();
    stats.counter("talks.created");
    stats.counter("talks.created");
    stats.counter("talks.created");

    var snapshot = stats.snapshot();
    assertEquals(3, snapshot.counterDeltas().get("talks.created"));
}

@Test
void counterIncrementsByN() {
    var stats = new Stats();
    stats.counter("bytes.uploaded", 4096);
    stats.counter("bytes.uploaded", 2048);

    var snapshot = stats.snapshot();
    assertEquals(6144, snapshot.counterDeltas().get("bytes.uploaded"));
}

@Test
void counterResetsAfterSnapshot() {
    var stats = new Stats();
    stats.counter("events");
    stats.counter("events");
    stats.snapshot(); // resets

    stats.counter("events");
    var snapshot = stats.snapshot();
    assertEquals(1, snapshot.counterDeltas().get("events"));
}

@Test
void counterTotalIsCumulative() {
    var stats = new Stats();
    stats.counter("events");
    stats.counter("events");
    stats.snapshot();
    stats.counter("events");

    assertEquals(3, stats.counterTotal("events"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=StatsTest -pl .`
Expected: Compilation error — `counter()`, `counterTotal()`, `counterDeltas()` don't exist.

- [ ] **Step 3: Add counter fields and methods to Stats**

In `Stats.java`, add fields after the existing ones (around line 39):

```java
// Custom counters
private final ConcurrentHashMap<String, LongAdder> counterDeltas = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, LongAdder> counterTotals = new ConcurrentHashMap<>();
```

Add counter methods:

```java
public void counter(String name) {
    counter(name, 1);
}

public void counter(String name, long amount) {
    counterDeltas.computeIfAbsent(name, k -> new LongAdder()).add(amount);
    counterTotals.computeIfAbsent(name, k -> new LongAdder()).add(amount);
}

public long counterTotal(String name) {
    var adder = counterTotals.get(name);
    return adder != null ? adder.sum() : 0;
}
```

Update `MinuteSnapshot` to include counter deltas. Change the record:

```java
public record MinuteSnapshot(
    Instant ts,
    long requests,
    long errors,
    long totalLatencyUs,
    long maxLatencyUs,
    long queries,
    long queryUs,
    long heapUsedMB,
    Map<String, Long> counterDeltas,
    Map<String, Long> gaugeValues,
    Map<String, TimerSnapshot> timerValues
) {
    public double avgLatencyMs() {
        return requests > 0 ? (totalLatencyUs / 1000.0) / requests : 0;
    }
}

public record TimerSnapshot(long count, double avgMs, long maxMs) {}
```

Update the `snapshot()` method to capture counter deltas:

```java
public MinuteSnapshot snapshot() {
    // ... existing counter captures ...

    // Capture custom counter deltas
    var cDeltas = new LinkedHashMap<String, Long>();
    for (var entry : counterDeltas.entrySet()) {
        long val = entry.getValue().sumThenReset();
        if (val > 0) cDeltas.put(entry.getKey(), val);
    }

    // Gauge and timer captures will be added in later tasks — empty for now
    var gValues = Map.<String, Long>of();
    var tValues = Map.<String, TimerSnapshot>of();

    var snap = new MinuteSnapshot(Instant.now(), reqs, errs, latUs, maxLat, qCount, qUs, heapMB,
            cDeltas, gValues, tValues);
    // ... store in ring buffer ...
    return snap;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=StatsTest -pl .`
Expected: All counter tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/larvalabs/brace/Stats.java src/test/java/com/larvalabs/brace/StatsTest.java
git commit -m "Add custom counter support to Stats"
```

---

### Task 2: Gauge Support in Stats

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/Stats.java`
- Modify: `src/test/java/com/larvalabs/brace/StatsTest.java`

- [ ] **Step 1: Write failing tests for gauges**

Add to `StatsTest.java`:

```java
@Test
void gaugeSamplesSupplierAtSnapshotTime() {
    var stats = new Stats();
    var value = new java.util.concurrent.atomic.AtomicLong(42);
    stats.gauge("queue.depth", value::get);

    var snapshot = stats.snapshot();
    assertEquals(42, snapshot.gaugeValues().get("queue.depth"));

    value.set(99);
    snapshot = stats.snapshot();
    assertEquals(99, snapshot.gaugeValues().get("queue.depth"));
}

@Test
void gaugeReplacesSupplierOnReregister() {
    var stats = new Stats();
    stats.gauge("metric", () -> 10L);
    stats.gauge("metric", () -> 20L);

    var snapshot = stats.snapshot();
    assertEquals(20, snapshot.gaugeValues().get("metric"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=StatsTest -pl .`
Expected: Compilation error — `gauge()` method doesn't exist.

- [ ] **Step 3: Add gauge fields and methods to Stats**

In `Stats.java`, add field:

```java
// Custom gauges
private final ConcurrentHashMap<String, java.util.function.Supplier<Long>> gauges = new ConcurrentHashMap<>();
```

Add gauge method:

```java
public void gauge(String name, java.util.function.Supplier<Long> supplier) {
    gauges.put(name, supplier);
}
```

Update `snapshot()` to sample gauges:

```java
// Replace the empty gauge capture with:
var gValues = new LinkedHashMap<String, Long>();
for (var entry : gauges.entrySet()) {
    try {
        gValues.put(entry.getKey(), entry.getValue().get());
    } catch (Exception e) {
        // Skip failed gauge suppliers
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=StatsTest -pl .`
Expected: All gauge tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/larvalabs/brace/Stats.java src/test/java/com/larvalabs/brace/StatsTest.java
git commit -m "Add custom gauge support to Stats"
```

---

### Task 3: Timer Support in Stats

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/Stats.java`
- Modify: `src/test/java/com/larvalabs/brace/StatsTest.java`

- [ ] **Step 1: Write failing tests for timers**

Add to `StatsTest.java`:

```java
@Test
void timerRecordsCountAndAverage() {
    var stats = new Stats();
    stats.timer("api.latency", 100);
    stats.timer("api.latency", 200);
    stats.timer("api.latency", 300);

    var snapshot = stats.snapshot();
    var timer = snapshot.timerValues().get("api.latency");
    assertEquals(3, timer.count());
    assertEquals(200.0, timer.avgMs(), 0.01);
    assertEquals(300, timer.maxMs());
}

@Test
void timerResetsAfterSnapshot() {
    var stats = new Stats();
    stats.timer("api.latency", 100);
    stats.snapshot(); // resets

    stats.timer("api.latency", 500);
    var snapshot = stats.snapshot();
    var timer = snapshot.timerValues().get("api.latency");
    assertEquals(1, timer.count());
    assertEquals(500.0, timer.avgMs(), 0.01);
    assertEquals(500, timer.maxMs());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=StatsTest -pl .`
Expected: Compilation error — `timer()` method doesn't exist.

- [ ] **Step 3: Add timer fields and methods to Stats**

In `Stats.java`, add the `TimerAccumulator` inner class and field:

```java
// Custom timers
private final ConcurrentHashMap<String, TimerAccumulator> timers = new ConcurrentHashMap<>();

static class TimerAccumulator {
    final LongAdder count = new LongAdder();
    final LongAdder totalMs = new LongAdder();
    final AtomicLong maxMs = new AtomicLong(0);

    void record(long ms) {
        count.increment();
        totalMs.add(ms);
        maxMs.accumulateAndGet(ms, Math::max);
    }

    TimerSnapshot snapshotAndReset() {
        long c = count.sumThenReset();
        long total = totalMs.sumThenReset();
        long max = maxMs.getAndSet(0);
        double avg = c > 0 ? (double) total / c : 0;
        return new TimerSnapshot(c, avg, max);
    }
}
```

Add timer method:

```java
public void timer(String name, long durationMs) {
    timers.computeIfAbsent(name, k -> new TimerAccumulator()).record(durationMs);
}
```

Update `snapshot()` to capture timers:

```java
// Replace the empty timer capture with:
var tValues = new LinkedHashMap<String, TimerSnapshot>();
for (var entry : timers.entrySet()) {
    var ts = entry.getValue().snapshotAndReset();
    if (ts.count() > 0) tValues.put(entry.getKey(), ts);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=StatsTest -pl .`
Expected: All timer tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/larvalabs/brace/Stats.java src/test/java/com/larvalabs/brace/StatsTest.java
git commit -m "Add custom timer support to Stats"
```

---

### Task 4: Expose Custom Metrics in /ops/status JSON

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/OpsHandler.java`
- Modify: `src/main/java/com/larvalabs/brace/Stats.java`
- Modify: `src/test/java/com/larvalabs/brace/OpsIntegrationTest.java`

- [ ] **Step 1: Write failing integration test**

Add to `OpsIntegrationTest.java`. First, add a counter/gauge/timer in the test app setup (`@BeforeAll`):

```java
// In @BeforeAll, after app.start():
app.get("/count-test", req -> {
    stats.counter("test.counter");
    stats.timer("test.timer", 42);
    return Result.text("ok");
});
stats.gauge("test.gauge", () -> 99L);
```

Note: `stats` needs to be accessible. Either expose it from `Brace` via `app.stats()` or register the metrics before start. The simplest approach: add a `stats()` accessor to `Brace` that returns the Stats instance (needed for app-level metric registration).

Add test:

```java
@Test
void opsStatusIncludesCustomMetrics() throws Exception {
    // Trigger the counter and timer
    get("/count-test");
    // Force a snapshot
    // ... stats snapshot happens on timer, but for test we can call it directly

    var response = getWithToken("/ops/status");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"metrics\""));
    assertTrue(response.body().contains("\"counters\""));
    assertTrue(response.body().contains("\"gauges\""));
    assertTrue(response.body().contains("\"timers\""));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OpsIntegrationTest#opsStatusIncludesCustomMetrics -pl .`
Expected: FAIL — no `metrics` section in status JSON.

- [ ] **Step 3: Add metrics accessor methods to Stats**

Add to `Stats.java`:

```java
public Map<String, Long> counterTotals() {
    var result = new LinkedHashMap<String, Long>();
    for (var entry : counterTotals.entrySet()) {
        result.put(entry.getKey(), entry.getValue().sum());
    }
    return result;
}

public Map<String, Long> currentGaugeValues() {
    var result = new LinkedHashMap<String, Long>();
    for (var entry : gauges.entrySet()) {
        try {
            result.put(entry.getKey(), entry.getValue().get());
        } catch (Exception e) {
            // skip
        }
    }
    return result;
}

public Map<String, TimerSnapshot> lastTimerValues() {
    // Return from the most recent snapshot in the ring buffer
    synchronized (ringLock) {
        if (ringSize == 0) return Map.of();
        int lastIdx = (ringHead - 1 + ringBuffer.length) % ringBuffer.length;
        return ringBuffer[lastIdx].timerValues();
    }
}
```

- [ ] **Step 4: Add metrics section to OpsHandler.status()**

In `OpsHandler.java`, in the `status()` method, add after the existing sections:

```java
// Custom metrics
var metrics = new LinkedHashMap<String, Object>();

var countersMap = new LinkedHashMap<String, Object>();
var counterTotals = stats.counterTotals();
// Get last minute's deltas from latest snapshot
var latestSnapshots = stats.minuteSnapshots();
var lastSnapshot = latestSnapshots.isEmpty() ? null : latestSnapshots.get(latestSnapshots.size() - 1);
for (var entry : counterTotals.entrySet()) {
    var counterData = new LinkedHashMap<String, Object>();
    counterData.put("total", entry.getValue());
    long rate = lastSnapshot != null && lastSnapshot.counterDeltas().containsKey(entry.getKey())
            ? lastSnapshot.counterDeltas().get(entry.getKey()) : 0;
    counterData.put("rate", rate);
    countersMap.put(entry.getKey(), counterData);
}
metrics.put("counters", countersMap);

var gaugesMap = new LinkedHashMap<String, Object>();
for (var entry : stats.currentGaugeValues().entrySet()) {
    gaugesMap.put(entry.getKey(), Map.of("value", entry.getValue()));
}
metrics.put("gauges", gaugesMap);

var timersMap = new LinkedHashMap<String, Object>();
for (var entry : stats.lastTimerValues().entrySet()) {
    var t = entry.getValue();
    var timerData = new LinkedHashMap<String, Object>();
    timerData.put("count", t.count());
    timerData.put("avgMs", Math.round(t.avgMs() * 10) / 10.0);
    timerData.put("maxMs", t.maxMs());
    timersMap.put(entry.getKey(), timerData);
}
metrics.put("timers", timersMap);

data.put("metrics", metrics);
```

- [ ] **Step 5: Add `stats()` accessor to Brace.java**

In `Brace.java`, add after `start()`:

```java
public Stats stats() {
    return stats;
}
```

- [ ] **Step 6: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest -pl .`
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/larvalabs/brace/Stats.java src/main/java/com/larvalabs/brace/OpsHandler.java src/main/java/com/larvalabs/brace/Brace.java src/test/java/com/larvalabs/brace/OpsIntegrationTest.java
git commit -m "Expose custom metrics in /ops/status JSON"
```

---

### Task 5: Dashboard Sparklines for Custom Metrics

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/OpsDashboard.java`
- Modify: `src/test/java/com/larvalabs/brace/OpsIntegrationTest.java`

- [ ] **Step 1: Write failing test**

Add to `OpsIntegrationTest.java`:

```java
@Test
void dashboardRendersCustomMetricSparklines() throws Exception {
    // Register a counter and trigger it
    stats.counter("dashboard.test.counter");
    stats.gauge("dashboard.test.gauge", () -> 42L);
    stats.timer("dashboard.test.timer", 100);
    stats.snapshot(); // Ensure data is in ring buffer

    var response = getWithToken("/ops/dashboard");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("dashboard.test.counter"));
    assertTrue(response.body().contains("dashboard.test.gauge"));
    assertTrue(response.body().contains("dashboard.test.timer"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OpsIntegrationTest#dashboardRendersCustomMetricSparklines -pl .`
Expected: FAIL — dashboard doesn't render custom metrics.

- [ ] **Step 3: Add custom metrics section to OpsDashboard**

In `OpsDashboard.java`, after the existing sparklines section (around line 221) and before the route performance table, add a custom metrics section. Insert this rendering code:

```java
// Custom Metrics section
var allCounterNames = new LinkedHashSet<String>();
var allGaugeNames = new LinkedHashSet<String>();
var allTimerNames = new LinkedHashSet<String>();
for (var m : snapshots) {
    allCounterNames.addAll(m.counterDeltas().keySet());
    allGaugeNames.addAll(m.gaugeValues().keySet());
    allTimerNames.addAll(m.timerValues().keySet());
}

if (!allCounterNames.isEmpty() || !allGaugeNames.isEmpty() || !allTimerNames.isEmpty()) {
    sb.append("<div class=\"section\"><div class=\"section-header\">Custom Metrics</div>");

    // Counter sparklines
    for (var name : allCounterNames) {
        long maxVal = snapshots.stream()
                .mapToLong(m -> m.counterDeltas().getOrDefault(name, 0L)).max().orElse(1);
        if (maxVal == 0) maxVal = 1;
        long latest = snapshots.isEmpty() ? 0
                : snapshots.get(snapshots.size() - 1).counterDeltas().getOrDefault(name, 0L);

        sb.append("<div style=\"margin-bottom:8px\"><span class=\"label\">")
          .append(esc(name)).append("</span> <span class=\"val\" style=\"color:#7dcfff\">")
          .append(latest).append("/min</span></div>");
        sb.append("<div class=\"sparkline\">");
        int empty = SPARKLINE_SLOTS - snapshots.size();
        for (int i = 0; i < empty; i++) sb.append("<div class=\"bar\"></div>");
        for (var m : snapshots) {
            long v = m.counterDeltas().getOrDefault(name, 0L);
            int pct = (int) Math.max(v > 0 ? 2 : 0, (v * 100) / maxVal);
            sb.append("<div class=\"bar\" style=\"height:").append(pct)
              .append("%;background:#7dcfff\" title=\"").append(v).append("/min\"></div>");
        }
        sb.append("</div>");
    }

    // Gauge sparklines
    for (var name : allGaugeNames) {
        long maxVal = snapshots.stream()
                .mapToLong(m -> m.gaugeValues().getOrDefault(name, 0L)).max().orElse(1);
        if (maxVal == 0) maxVal = 1;
        long latest = snapshots.isEmpty() ? 0
                : snapshots.get(snapshots.size() - 1).gaugeValues().getOrDefault(name, 0L);

        sb.append("<div style=\"margin-bottom:8px\"><span class=\"label\">")
          .append(esc(name)).append("</span> <span class=\"val\" style=\"color:#e0af68\">")
          .append(latest).append("</span></div>");
        sb.append("<div class=\"sparkline\">");
        int empty = SPARKLINE_SLOTS - snapshots.size();
        for (int i = 0; i < empty; i++) sb.append("<div class=\"bar\"></div>");
        for (var m : snapshots) {
            long v = m.gaugeValues().getOrDefault(name, 0L);
            int pct = (int) Math.max(v > 0 ? 4 : 0, (v * 100) / maxVal);
            sb.append("<div class=\"bar\" style=\"height:").append(pct)
              .append("%;background:#e0af68\" title=\"").append(v).append("\"></div>");
        }
        sb.append("</div>");
    }

    // Timer sparklines
    for (var name : allTimerNames) {
        double maxAvg = snapshots.stream()
                .filter(m -> m.timerValues().containsKey(name))
                .mapToDouble(m -> m.timerValues().get(name).avgMs()).max().orElse(1);
        if (maxAvg == 0) maxAvg = 1;
        var latestTimer = snapshots.isEmpty() ? null
                : snapshots.get(snapshots.size() - 1).timerValues().get(name);
        String latestStr = latestTimer != null
                ? String.format("%.1fms avg", latestTimer.avgMs()) : "—";

        sb.append("<div style=\"margin-bottom:8px\"><span class=\"label\">")
          .append(esc(name)).append("</span> <span class=\"val\" style=\"color:#7aa2f7\">")
          .append(latestStr).append("</span></div>");
        sb.append("<div class=\"sparkline\">");
        int empty = SPARKLINE_SLOTS - snapshots.size();
        for (int i = 0; i < empty; i++) sb.append("<div class=\"bar\"></div>");
        for (var m : snapshots) {
            var t = m.timerValues().get(name);
            double avg = t != null ? t.avgMs() : 0;
            int pct = (int) Math.max(avg > 0 ? 4 : 0, (avg * 100) / maxAvg);
            String tooltip = t != null
                    ? String.format("%.1fms avg, %d calls, %dms max", t.avgMs(), t.count(), t.maxMs())
                    : "no data";
            sb.append("<div class=\"bar\" style=\"height:").append(pct)
              .append("%;background:#7aa2f7\" title=\"").append(tooltip).append("\"></div>");
        }
        sb.append("</div>");
    }

    sb.append("</div>");
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=OpsIntegrationTest -pl .`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/larvalabs/brace/OpsDashboard.java src/test/java/com/larvalabs/brace/OpsIntegrationTest.java
git commit -m "Add custom metric sparklines to ops dashboard"
```

---

### Task 6: Run full test suite

- [ ] **Step 1: Run all tests**

Run: `mvn test -pl .`
Expected: All tests PASS. No regressions.

- [ ] **Step 2: Commit any fixups**

```bash
git add -A
git commit -m "Fix any test regressions from custom metrics"
```
