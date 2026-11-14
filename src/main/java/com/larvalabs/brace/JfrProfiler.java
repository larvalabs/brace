package com.larvalabs.brace;

import jdk.jfr.consumer.RecordingStream;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public class JfrProfiler implements AutoCloseable {

    // Latest values (volatile)
    private volatile double jvmCpuUser, jvmCpuSystem, machineCpu;
    private volatile long activeThreads, daemonThreads, peakThreads;
    private volatile long heapCommitted;

    // GC tracking
    private final GcStats gcStats = new GcStats();

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

        // GC events — the duration spans the whole collection, which for concurrent
        // cycles (G1Old, ZGC, Shenandoah) is mostly not a pause; see GcStats.
        rs.enable("jdk.GarbageCollection");
        rs.onEvent("jdk.GarbageCollection", event -> gcStats.record(
            event.getStartTime(),
            event.getString("name"),
            event.getString("cause"),
            event.getDuration().toNanos(),
            event.getDuration("sumOfPauses").toNanos(),
            event.getDuration("longestPause").toNanos()
        ));

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

        // Not rs.startAsync(): its "JFR Event Stream" thread is non-daemon and kept the JVM alive
        // when Brace.start() failed after this point. A daemon thread running the blocking
        // start() dispatches the same events and never holds the process open; close() ends it.
        Thread.ofPlatform().daemon().name("brace-jfr-stream").start(rs::start);
    }

    public Map<String, Object> snapshot() {
        return snapshot(true);
    }

    /**
     * JVM metrics snapshot. The profiling block (hot methods + top allocations) sorts and
     * maps two sample tables, so it is built only when the caller will actually emit it —
     * {@code /ops/status} includes it solely under {@code ?include=profiling}.
     */
    public Map<String, Object> snapshot(boolean includeProfiling) {
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
        data.put("gc", gcStats.snapshot());

        // Profiling
        if (includeProfiling) {
            var profiling = new LinkedHashMap<String, Object>();
            profiling.put("windowSeconds", 300);
            profiling.put("hotMethods", topEntries(methodSamples.get(), 20));
            profiling.put("topAllocations", topAllocEntries(allocationByClass.get(), 20));
            data.put("profiling", profiling);
        }

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

    /** Width of {@code ops_profiling_snapshots.name} (V3), which is part of the primary key. */
    static final int MAX_STORED_NAME = 255;
    private static final int HASH_HEX_CHARS = 16;

    /**
     * {@code name} as stored in {@code ops_profiling_snapshots}: unchanged when it fits in
     * {@value #MAX_STORED_NAME} characters, otherwise a recognizable prefix plus {@code ~} and
     * 16 hex characters of the full name's SHA-256, exactly {@value #MAX_STORED_NAME} long.
     *
     * <p>Generated classes can exceed the column: Hibernate's
     * {@code Entity$HibernateAccessOptimizer...} names spell out every mapped field. The hash keeps
     * two long names that share a prefix distinct within the {@code (ts, type, name)} key, and the
     * mapping is stable, so one name lands on the same stored value in every snapshot. (The column
     * stays VARCHAR rather than widening to a CLOB: H2 can't put a CLOB in a primary key.)
     */
    static String storedName(String name) {
        if (name == null || name.length() <= MAX_STORED_NAME) return name;
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
        String hash = HexFormat.of().formatHex(digest, 0, HASH_HEX_CHARS / 2);
        int end = MAX_STORED_NAME - 1 - HASH_HEX_CHARS;
        // Don't leave half a surrogate pair at the cut (UTF-16 units >= characters, so the
        // result is never longer than the column in either measure).
        if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
        return name.substring(0, end) + "~" + hash;
    }

    public void resetProfiling() {
        methodSamples.set(new ConcurrentHashMap<>());
        allocationByClass.set(new ConcurrentHashMap<>());
    }

    public long gcCount() { return gcStats.count(); }

    /** Total stop-the-world GC time (sum of pauses, not collection wall-clock time). */
    public long totalGcPauseMs() { return gcStats.totalPauseMs(); }

    /** Longest single stop-the-world GC pause among the last 100 collections. */
    public long maxRecentGcPauseMs() { return (long) Math.ceil(gcStats.maxRecentPauseMs()); }

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
}
