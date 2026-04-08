# App-Level Custom Metrics Design

**Date:** 2026-04-08
**Status:** Approved

## Overview

Add custom application metrics to Brace: counters, gauges, and timers. In-memory only, using the same lock-free patterns as the existing `Stats` class. Auto-rendered in the ops dashboard with sparklines and exposed in `/ops/status` JSON.

## Goals

- Let app developers track domain-specific metrics (`Stats.counter("talks.created")`, `Stats.gauge("queue.depth", supplier)`, `Stats.timer("api.latency", durationMs)`)
- Standard Grafana metric types: counters (monotonically increasing), gauges (point-in-time values), timers/histograms (duration distributions)
- Lock-free `LongAdder` internals for zero contention on the hot path
- Auto-rendered in ops dashboard with sparklines
- Exposed in `/ops/status` JSON
- In-memory only — resets on restart, no DB persistence

## API

### Counters

Monotonically increasing count. Tracks a rate (events per minute).

```java
Stats.counter("talks.created");           // increment by 1
Stats.counter("bytes.uploaded", 4096);    // increment by N
```

### Gauges

Point-in-time value, sampled each minute during snapshot.

```java
Stats.gauge("queue.depth", () -> queue.size());       // supplier, sampled each snapshot
Stats.gauge("active.sessions", () -> sessions.count());
```

### Timers

Duration tracking. Records count and total duration for computing avg, plus tracks max.

```java
Stats.timer("api.external", 142);    // record a duration in milliseconds
Stats.timer("db.migration", 3400);
```

## Internal Design

### Data Structures

All stored in `Stats` alongside existing fields:

```java
// Custom counters: name → LongAdder (reset each snapshot, delta goes to ring buffer)
private static final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

// Custom gauges: name → Supplier<Long> (sampled at snapshot time)
private static final ConcurrentHashMap<String, Supplier<Long>> gauges = new ConcurrentHashMap<>();

// Custom timers: name → TimerAccumulator (reset each snapshot)
private static final ConcurrentHashMap<String, TimerAccumulator> timers = new ConcurrentHashMap<>();
```

`TimerAccumulator` is a small inner class:

```java
static class TimerAccumulator {
    final LongAdder count = new LongAdder();
    final LongAdder totalMs = new LongAdder();
    final AtomicLong maxMs = new AtomicLong();

    void record(long ms) {
        count.increment();
        totalMs.add(ms);
        maxMs.accumulateAndGet(ms, Math::max);
    }

    // Reset and return snapshot values
    TimerSnapshot snapshotAndReset() { ... }
}
```

### Snapshot Integration

The existing `Stats.snapshot()` method (called once per minute by `StatsScheduler`) is extended:

1. Existing behavior: capture request/error/latency counters, store in ring buffer
2. New: capture custom counter deltas (sum and reset each `LongAdder`)
3. New: sample all gauge suppliers
4. New: capture timer snapshots (count, avgMs, maxMs) and reset

### MinuteSnapshot Extension

`MinuteSnapshot` gains three new fields:

```java
Map<String, Long> counterDeltas;          // counter name → delta this minute
Map<String, Long> gaugeValues;            // gauge name → sampled value
Map<String, TimerSnapshot> timerValues;   // timer name → {count, avgMs, maxMs}
```

`TimerSnapshot` record: `record TimerSnapshot(long count, double avgMs, long maxMs) {}`

### Ring Buffer

Same 60-slot ring buffer. Custom metric data rides along in each `MinuteSnapshot`. No separate ring buffers per metric.

## Dashboard Rendering

### Layout

Custom metrics section appears after the existing sparklines, before the routes table. One sparkline per registered metric, grouped by type:

**Counters** — bar chart showing rate (events/minute), same style as requests/minute sparkline. Color: cyan (`#7dcfff`).

**Gauges** — bar chart showing sampled value each minute. Color: amber (`#e0af68`).

**Timers** — bar chart showing avg duration per minute. Color: blue (`#7aa2f7`). Tooltip includes count and max.

Each sparkline has a label (the metric name) and current value displayed above it.

### Rendering

Uses the same `sparkline` helper pattern from `OpsDashboard`. Iterates over the 60-slot ring buffer, extracts the relevant metric from each `MinuteSnapshot`'s maps.

Metrics that haven't been recorded yet (no entries in the maps) are rendered as empty bars.

## `/ops/status` JSON

New `metrics` section:

```json
{
  "metrics": {
    "counters": {
      "talks.created": { "total": 1523, "rate": 12 }
    },
    "gauges": {
      "queue.depth": { "value": 42 }
    },
    "timers": {
      "api.external": { "count": 89, "avgMs": 142.3, "maxMs": 890 }
    }
  }
}
```

- Counter `total` is the cumulative sum (separate `LongAdder` that doesn't reset). `rate` is the last minute's delta.
- Gauge `value` is the most recent sampled value.
- Timer values are from the last completed minute snapshot.

## Registration Behavior

- `Stats.counter()` auto-registers on first call. No upfront declaration needed.
- `Stats.gauge()` registers the supplier. Calling again with the same name replaces the supplier.
- `Stats.timer()` auto-registers on first call.
- Thread-safe: `ConcurrentHashMap.computeIfAbsent` for all registrations.

## Testing

- Unit test: counter increment and snapshot reset
- Unit test: gauge supplier sampling
- Unit test: timer recording (count, avg, max)
- Unit test: multiple metrics in a single snapshot
- Unit test: ring buffer carries custom metrics across minutes
- Integration test: metrics appear in `/ops/status` JSON
- Integration test: dashboard renders custom metric sparklines
