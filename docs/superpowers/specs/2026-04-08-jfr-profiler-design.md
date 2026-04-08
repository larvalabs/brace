# JFR-Based JVM Profiler for Ops Dashboard

## Summary

Add a JFR-powered JVM profiler to Brace's ops system. Replaces the current basic `Runtime.getRuntime()` memory section with comprehensive JVM health metrics (heap, CPU, GC, threads) plus CPU profiling (method hot spots) and allocation tracking. Uses `jdk.jfr.consumer.RecordingStream` — zero external dependencies, built into Java 21.

## Motivation

The ops dashboard currently shows request-level metrics (latency, error rates, status codes) but nothing about the JVM underneath. When requests slow down, there's no way to tell if the cause is GC thrashing, memory pressure, thread exhaustion, or a hot method — you'd need an external APM tool. This feature brings that visibility into the built-in dashboard.

## Architecture

### New class: `JfrProfiler`

A single class that starts a `RecordingStream` at app boot and aggregates JFR events into lock-free data structures. `OpsHandler` reads from it on demand when `/ops/status` is requested.

```
Boot: Brace.start() → new JfrProfiler() → rs.startAsync()
Request: /ops/status → OpsHandler reads JfrProfiler.snapshot()
Periodic: ops-flush-jvm job → writes top-N + summary to ops_timeseries
Shutdown: Brace.stop() → JfrProfiler.close()
```

### JFR events enabled

| Event | Period/Config | Purpose |
|---|---|---|
| `jdk.CPULoad` | 1s period | JVM + system CPU load |
| `jdk.JavaThreadStatistics` | 1s period | Active, daemon, peak thread counts |
| `jdk.GarbageCollection` | default | Per-GC event with cause, duration |
| `jdk.GCHeapSummary` | default | Heap used/committed after each GC |
| `jdk.ExecutionSample` | 20ms period | CPU profiling — stack sampling |
| `jdk.ObjectAllocationSample` | default throttle | Allocation profiling — statistical sampling |

All events use the JDK "default" configuration as a base — designed for <1% overhead in production.

### In-memory state

`JfrProfiler` maintains:

**Latest values (volatile fields):**
- `jvmCpuUser`, `jvmCpuSystem`, `machineCpu` — from `jdk.CPULoad`
- `activeThreads`, `daemonThreads`, `peakThreads` — from `jdk.JavaThreadStatistics`
- `heapUsed`, `heapCommitted`, `heapMax` — `heapUsed`/`heapCommitted` from `jdk.GCHeapSummary` (after GC), `heapMax` from `MemoryMXBean` on read (since JFR only reports heap at GC boundaries)

**GC tracking:**
- `gcCount` (`LongAdder`) — total GC events
- `totalGcPauseNanos` (`LongAdder`) — cumulative pause time
- `recentGcPauses` — ring buffer of last 100 GC events, each storing: timestamp, duration (ms), collector name, cause

**Profiling (rolling window, reset every 5 minutes):**
- `methodSamples` (`ConcurrentHashMap<String, LongAdder>`) — method name → sample count. Key is `ClassName.methodName`.
- `allocationByClass` (`ConcurrentHashMap<String, LongAdder>`) — class name → bytes allocated (weight from `jdk.ObjectAllocationSample`)
- A periodic reset clears both maps every 5 minutes to keep them bounded and show what's hot right now

### Heap used between GCs

`jdk.GCHeapSummary` only fires at GC events. For a current "heap used right now" reading, `JfrProfiler.snapshot()` supplements the last-known JFR value with `ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()`. This is the only MXBean call — everything else comes from JFR.

## API changes

### OpsHandler `/ops/status` response

Replace the current `memory` section:

```json
{
  "memory": {
    "heapUsedMB": 142,
    "heapMaxMB": 512
  }
}
```

With a new `jvm` section:

```json
{
  "jvm": {
    "heap": {
      "usedMB": 142,
      "committedMB": 256,
      "maxMB": 512
    },
    "cpu": {
      "jvmUser": 0.12,
      "jvmSystem": 0.03,
      "machineTotal": 0.45
    },
    "threads": {
      "active": 24,
      "daemon": 18,
      "peak": 31
    },
    "gc": {
      "totalCount": 847,
      "totalPauseMs": 3421,
      "avgPauseMs": 4.04,
      "recentPauses": [
        { "ts": "2026-04-08T14:23:01Z", "durationMs": 12.3, "collector": "G1 Young Generation", "cause": "G1 Evacuation Pause" }
      ]
    },
    "profiling": {
      "windowSeconds": 300,
      "hotMethods": [
        { "method": "com.example.UserService.findById", "samples": 342 },
        { "method": "org.hibernate.internal.SessionImpl.list", "samples": 218 }
      ],
      "topAllocations": [
        { "class": "byte[]", "bytes": 52428800 },
        { "class": "java.lang.String", "bytes": 18874368 }
      ]
    }
  }
}
```

The `hotMethods` and `topAllocations` arrays return the top 20 entries sorted by samples/bytes descending.

### No new Brace builder methods

`JfrProfiler` is started automatically when `ops(secret)` is configured — no new API surface. JFR is a JDK built-in, so there's nothing to opt into.

## Database persistence

### Schema addition

Add to the framework's ops table creation (test migration and framework DDL):

```sql
CREATE TABLE ops_profiling_snapshots (
    ts TIMESTAMP NOT NULL,
    type VARCHAR(20) NOT NULL,
    name VARCHAR(255) NOT NULL,
    value BIGINT NOT NULL,
    PRIMARY KEY (ts, type, name)
);
```

- `type`: `"method"` or `"allocation"`
- `name`: method name or class name
- `value`: sample count or bytes

### Periodic flush job

Register a new scheduled job `"ops-flush-jvm"` (similar to existing `"ops-flush-http"`):

**Every 60 seconds** — write JVM health summary to `ops_timeseries`:
- `jvm.heap_used_mb`, `jvm.heap_max_mb`
- `jvm.cpu_user`, `jvm.cpu_system`
- `jvm.threads_active`, `jvm.threads_peak`
- `jvm.gc_count`, `jvm.gc_total_pause_ms`, `jvm.gc_max_pause_ms`

**Every 5 minutes** — write top-20 profiling snapshot to `ops_profiling_snapshots`:
- Top 20 methods by sample count
- Top 20 allocating classes by bytes
- Then reset the in-memory profiling maps

This gives the deploy comparison workflow: after deploying a fix, compare the current top methods to the previous snapshot stored before the deploy.

### Retention

No built-in retention/cleanup in v1. The `ops_profiling_snapshots` table grows at ~40 rows per 5 minutes (~11.5k rows/day). The `ops_timeseries` JVM metrics grow at ~9 rows per minute (~13k rows/day). Both are small. Cleanup can be added later if needed (same situation as existing ops tables).

## Dashboard changes

### Replace Heap stat cards

Current cards: `Heap Used`, `Heap Max`

New cards in a "JVM" section:
- **Heap** — `142 / 512 MB` (used / max)
- **CPU** — `12%` (jvmUser, formatted as percentage)
- **Threads** — `24` (active count)
- **GC** — `4.0 ms avg` (average pause)

### New tables (below stat cards, in a two-column layout)

**Hot Methods** table:
| Method | Samples |
|---|---|
| com.example.UserService.findById | 342 |
| ... | ... |

Top 20 rows. Method names truncated with tooltip for full name if long.

**Top Allocations** table:
| Class | Allocated |
|---|---|
| byte[] | 50 MB |
| java.lang.String | 18 MB |

Top 20 rows. Bytes formatted as KB/MB/GB.

**Recent GC Pauses** table:
| Time | Duration | Collector | Cause |
|---|---|---|---|
| 14:23:01 | 12.3 ms | G1 Young Generation | G1 Evacuation Pause |

Last 20 pauses (subset of the 100 kept in memory). Rows with pauses >100ms highlighted in error-text color.

## JfrProfiler class outline

```java
public class JfrProfiler implements AutoCloseable {

    // Latest values (volatile)
    private volatile float jvmCpuUser, jvmCpuSystem, machineCpu;
    private volatile long activeThreads, daemonThreads, peakThreads;
    private volatile long heapUsed, heapCommitted;

    // GC tracking
    private final LongAdder gcCount = new LongAdder();
    private final LongAdder totalGcPauseNanos = new LongAdder();
    private final GcPause[] recentPauses = new GcPause[100]; // ring buffer
    // ring buffer index + lock

    // Profiling (rolling window)
    private final ConcurrentHashMap<String, LongAdder> methodSamples;
    private final ConcurrentHashMap<String, LongAdder> allocationByClass;

    private final RecordingStream rs;

    public JfrProfiler() { /* configure events, register callbacks, startAsync */ }

    // Read current state — called by OpsHandler
    public Map<String, Object> snapshot() { /* assemble jvm section */ }

    // Get top-N for DB persistence — called by flush job
    public List<Map.Entry<String, Long>> topMethods(int n) { ... }
    public List<Map.Entry<String, Long>> topAllocations(int n) { ... }

    // Reset profiling window — called after DB flush
    public void resetProfiling() { ... }

    // GC summary for DB flush
    public long gcCount() { ... }
    public long totalGcPauseMs() { ... }
    public long maxRecentGcPauseMs() { ... }

    @Override
    public void close() { rs.close(); }

    public record GcPause(Instant ts, double durationMs, String collector, String cause) {}
}
```

## Integration with Brace.start()

In `Brace.start()`, after creating `Stats`:

1. Create `JfrProfiler` (unconditionally when ops is enabled — JFR is always available in Java 21)
2. Pass it to `OpsHandler` constructor (new parameter)
3. Register the `"ops-flush-jvm"` scheduled jobs (if database is available)
4. On `Brace.stop()`, call `profiler.close()`

`OpsHandler` replaces the current `Runtime.getRuntime()` memory section with `profiler.snapshot()`.

## Testing

- `JfrProfiler` unit test: start profiler, verify it collects CPU/thread/heap data after a short delay. Verify `topMethods()` returns entries after generating some CPU load.
- Integration test: start a `TestApp` with ops enabled, hit `/ops/status`, verify the `jvm` section is present with expected structure.
- GC ring buffer test: record several GC pauses, verify recent pauses are ordered and bounded.
- Profiling reset test: verify maps are cleared after `resetProfiling()`.

Note: JFR `RecordingStream` works fine in test environments (H2, in-process). No special setup needed.

## Files to create/modify

| File | Action |
|---|---|
| `src/main/java/io/brace/JfrProfiler.java` | **Create** — new class |
| `src/main/java/io/brace/OpsHandler.java` | **Modify** — accept JfrProfiler, replace memory section with jvm section |
| `src/main/java/io/brace/OpsDashboard.java` | **Modify** — add JVM stat cards, hot methods table, allocations table, GC pauses table |
| `src/main/java/io/brace/Brace.java` | **Modify** — create JfrProfiler in start(), pass to OpsHandler, register flush jobs, close on stop() |
| `src/test/resources/db/migration/V4__create_profiling_tables.sql` | **Create** — ops_profiling_snapshots table |
| `src/test/java/io/brace/JfrProfilerTest.java` | **Create** — unit tests |
| `src/test/java/io/brace/IntegrationTest.java` | **Modify** — add /ops/status JVM section test |
