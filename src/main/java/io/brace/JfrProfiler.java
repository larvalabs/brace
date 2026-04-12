package io.brace;

import jdk.jfr.consumer.RecordingStream;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public class JfrProfiler implements AutoCloseable {

    // Latest values (volatile)
    private volatile double jvmCpuUser, jvmCpuSystem, machineCpu;
    private volatile long activeThreads, daemonThreads, peakThreads;
    private volatile long heapCommitted;

    // GC tracking
    private final LongAdder gcCount = new LongAdder();
    private final LongAdder totalGcPauseNanos = new LongAdder();
    private final GcPause[] recentPauses = new GcPause[100];
    private final AtomicInteger pauseIndex = new AtomicInteger(0);

    // Profiling (rolling window)
    private final AtomicReference<ConcurrentHashMap<String, LongAdder>> methodSamples = new AtomicReference<>(new ConcurrentHashMap<>());
    private final AtomicReference<ConcurrentHashMap<String, LongAdder>> allocationByClass = new AtomicReference<>(new ConcurrentHashMap<>());

    private final RecordingStream rs;

    public JfrProfiler() {
        rs = new RecordingStream();

        // CPU load — 1s period
        rs.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));
        rs.onEvent("jdk.CPULoad", event -> {
            jvmCpuUser = event.getDouble("jvmUser");
            jvmCpuSystem = event.getDouble("jvmSystem");
            machineCpu = event.getDouble("machineTotal");
        });

        // Thread stats — 1s period
        rs.enable("jdk.JavaThreadStatistics").withPeriod(Duration.ofSeconds(1));
        rs.onEvent("jdk.JavaThreadStatistics", event -> {
            activeThreads = event.getLong("activeCount");
            daemonThreads = event.getLong("daemonCount");
            peakThreads = event.getLong("peakCount");
        });

        // GC events
        rs.enable("jdk.GarbageCollection");
        rs.onEvent("jdk.GarbageCollection", event -> {
            gcCount.increment();
            long durationNanos = event.getDuration().toNanos();
            totalGcPauseNanos.add(durationNanos);
            var pause = new GcPause(
                event.getStartTime(),
                durationNanos / 1_000_000.0,
                event.getString("name"),
                event.getString("cause")
            );
            // Safe without synchronization: RecordingStream callbacks are single-threaded
            int idx = pauseIndex.getAndUpdate(i -> (i + 1) % recentPauses.length);
            recentPauses[idx] = pause;
        });

        // Heap summary after GC
        rs.enable("jdk.GCHeapSummary");
        rs.onEvent("jdk.GCHeapSummary", event -> {
            heapCommitted = event.getLong("heapSpace.committedSize");
        });

        // CPU profiling — execution sampling
        rs.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20));
        rs.onEvent("jdk.ExecutionSample", event -> {
            var stackTrace = event.getStackTrace();
            if (stackTrace != null && !stackTrace.getFrames().isEmpty()) {
                var frame = stackTrace.getFrames().getFirst();
                String key = frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
                methodSamples.get().computeIfAbsent(key, k -> new LongAdder()).increment();
            }
        });

        // Allocation profiling
        rs.enable("jdk.ObjectAllocationSample");
        rs.onEvent("jdk.ObjectAllocationSample", event -> {
            String className = event.getClass("objectClass").getName();
            long weight = event.getLong("weight");
            allocationByClass.get().computeIfAbsent(className, k -> new LongAdder()).add(weight);
        });

        rs.startAsync();
    }

    public Map<String, Object> snapshot() {
        var data = new LinkedHashMap<String, Object>();

        // Heap — supplement JFR data with MXBean for between-GC accuracy
        var heap = new LinkedHashMap<String, Object>();
        var mxHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        heap.put("usedMB", mxHeap.getUsed() / (1024 * 1024));
        heap.put("committedMB", heapCommitted > 0 ? heapCommitted / (1024 * 1024) : mxHeap.getCommitted() / (1024 * 1024));
        heap.put("maxMB", mxHeap.getMax() / (1024 * 1024));
        data.put("heap", heap);

        // CPU — keep raw fractions; rounding to 2 decimals would bucket anything
        // under 0.5% to 0 and hide the real load on a mostly-idle JVM.
        var cpu = new LinkedHashMap<String, Object>();
        cpu.put("jvmUser", jvmCpuUser);
        cpu.put("jvmSystem", jvmCpuSystem);
        cpu.put("machineTotal", machineCpu);
        data.put("cpu", cpu);

        // Threads
        var threads = new LinkedHashMap<String, Object>();
        threads.put("active", activeThreads > 0 ? activeThreads : ManagementFactory.getThreadMXBean().getThreadCount());
        threads.put("daemon", daemonThreads > 0 ? daemonThreads : ManagementFactory.getThreadMXBean().getDaemonThreadCount());
        threads.put("peak", peakThreads > 0 ? peakThreads : ManagementFactory.getThreadMXBean().getPeakThreadCount());
        data.put("threads", threads);

        // GC
        var gc = new LinkedHashMap<String, Object>();
        long count = gcCount.sum();
        long totalMs = totalGcPauseNanos.sum() / 1_000_000;
        gc.put("totalCount", count);
        gc.put("totalPauseMs", totalMs);
        gc.put("avgPauseMs", count > 0 ? round((double) totalMs / count) : 0.0);
        var pauses = new ArrayList<Map<String, Object>>();
        var allPauses = new ArrayList<GcPause>();
        for (var p : recentPauses) {
            if (p != null) allPauses.add(p);
        }
        allPauses.sort((a, b) -> b.ts().compareTo(a.ts()));
        for (var p : allPauses.stream().limit(20).toList()) {
            var pm = new LinkedHashMap<String, Object>();
            pm.put("ts", p.ts().toString());
            pm.put("durationMs", round(p.durationMs()));
            pm.put("collector", p.collector());
            pm.put("cause", p.cause());
            pauses.add(pm);
        }
        gc.put("recentPauses", pauses);
        data.put("gc", gc);

        // Profiling
        var profiling = new LinkedHashMap<String, Object>();
        profiling.put("windowSeconds", 300);
        profiling.put("hotMethods", topEntries(methodSamples.get(), 20));
        profiling.put("topAllocations", topAllocEntries(allocationByClass.get(), 20));
        data.put("profiling", profiling);

        return data;
    }

    public List<Map.Entry<String, Long>> topMethods(int n) {
        return methodSamples.get().entrySet().stream()
            .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(n).toList();
    }

    public List<Map.Entry<String, Long>> topAllocations(int n) {
        return allocationByClass.get().entrySet().stream()
            .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(n).toList();
    }

    public void resetProfiling() {
        methodSamples.set(new ConcurrentHashMap<>());
        allocationByClass.set(new ConcurrentHashMap<>());
    }

    public long gcCount() { return gcCount.sum(); }
    public long totalGcPauseMs() { return totalGcPauseNanos.sum() / 1_000_000; }

    public long maxRecentGcPauseMs() {
        long max = 0;
        for (var p : recentPauses) {
            if (p != null) max = Math.max(max, (long) Math.ceil(p.durationMs()));
        }
        return max;
    }

    @Override
    public void close() {
        rs.close();
    }

    private static List<Map<String, Object>> topEntries(ConcurrentHashMap<String, LongAdder> map, int n) {
        return map.entrySet().stream()
            .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(n)
            .<Map<String, Object>>map(e -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("method", e.getKey());
                m.put("samples", e.getValue());
                return m;
            }).toList();
    }

    private static List<Map<String, Object>> topAllocEntries(ConcurrentHashMap<String, LongAdder> map, int n) {
        return map.entrySet().stream()
            .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(n)
            .<Map<String, Object>>map(e -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("class", e.getKey());
                m.put("bytes", e.getValue());
                return m;
            }).toList();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public record GcPause(Instant ts, double durationMs, String collector, String cause) {}
}
