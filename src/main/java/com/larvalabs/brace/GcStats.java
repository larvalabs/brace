package com.larvalabs.brace;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * GC accounting behind {@link JfrProfiler}'s {@code gc} block.
 *
 * <p>A {@code jdk.GarbageCollection} event's duration is the collection's wall-clock span.
 * For a young or full collection that is all stop-the-world, but for G1Old (the concurrent
 * marking cycle), ZGC and Shenandoah most of it runs alongside the application. Only the
 * event's {@code sumOfPauses} and {@code longestPause} fields measure time the application
 * was actually stopped, so every pause figure comes from those; the duration is kept
 * separately as {@code cycleMs}.
 */
final class GcStats {

    /** JFR collector names of whole-heap stop-the-world collections. */
    static final Set<String> FULL_COLLECTORS = Set.of("G1Full", "SerialOld", "ParallelOld");

    private final LongAdder count = new LongAdder();
    private final LongAdder fullCount = new LongAdder();
    private final LongAdder totalPauseNanos = new LongAdder();
    private final GcEvent[] recent = new GcEvent[100];
    private final AtomicInteger index = new AtomicInteger(0);

    /** Records one {@code jdk.GarbageCollection} event from its extracted fields. */
    void record(Instant ts, String collector, String cause,
                long cycleNanos, long sumOfPausesNanos, long longestPauseNanos) {
        boolean full = FULL_COLLECTORS.contains(collector);
        count.increment();
        if (full) fullCount.increment();
        totalPauseNanos.add(sumOfPausesNanos);
        var event = new GcEvent(ts, collector, cause,
            sumOfPausesNanos / 1_000_000.0, longestPauseNanos / 1_000_000.0, cycleNanos / 1_000_000.0, full);
        // Safe without synchronization: RecordingStream callbacks are single-threaded
        int idx = index.getAndUpdate(i -> (i + 1) % recent.length);
        recent[idx] = event;
    }

    long count() { return count.sum(); }
    long totalPauseMs() { return totalPauseNanos.sum() / 1_000_000; }

    /** Longest single stop-the-world pause among the last 100 collections. */
    double maxRecentPauseMs() {
        double max = 0;
        for (var e : recent) {
            if (e != null) max = Math.max(max, e.longestPauseMs());
        }
        return max;
    }

    Map<String, Object> snapshot() {
        var gc = new LinkedHashMap<String, Object>();
        long n = count.sum();
        gc.put("totalCount", n);
        gc.put("fullCount", fullCount.sum());
        gc.put("totalPauseMs", totalPauseMs());
        gc.put("avgPauseMs", n > 0 ? round(totalPauseNanos.sum() / 1_000_000.0 / n) : 0.0);
        gc.put("maxPauseMs", round(maxRecentPauseMs()));
        var all = new ArrayList<GcEvent>();
        for (var e : recent) {
            if (e != null) all.add(e);
        }
        all.sort((a, b) -> b.ts().compareTo(a.ts()));
        var pauses = new ArrayList<Map<String, Object>>();
        for (var e : all.stream().limit(20).toList()) {
            var pm = new LinkedHashMap<String, Object>();
            pm.put("ts", e.ts().toString());
            pm.put("durationMs", round(e.pauseMs()));
            pm.put("longestPauseMs", round(e.longestPauseMs()));
            pm.put("cycleMs", round(e.cycleMs()));
            pm.put("collector", e.collector());
            pm.put("cause", e.cause());
            if (e.full()) pm.put("full", true);
            pauses.add(pm);
        }
        gc.put("recentPauses", pauses);
        return gc;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** One collection: {@code pauseMs} is stop-the-world time, {@code cycleMs} wall-clock span. */
    record GcEvent(Instant ts, String collector, String cause,
                   double pauseMs, double longestPauseMs, double cycleMs, boolean full) {}
}
