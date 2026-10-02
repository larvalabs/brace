package com.larvalabs.brace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

public class Stats {

    private final Instant startedAt = Instant.now();

    // Current-window counters (reset on snapshot)
    private final LongAdder requestCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder totalLatencyUs = new LongAdder();
    private final LongAdder totalQueryCount = new LongAdder();
    private final LongAdder totalQueryUs = new LongAdder();
    private final AtomicLong maxLatencyUs = new AtomicLong(0);

    private final ConcurrentHashMap<Integer, LongAdder> statusCodes = new ConcurrentHashMap<>();

    // Custom counters (delta resets on snapshot, total is cumulative)
    private final ConcurrentHashMap<String, LongAdder> counterDeltas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> counterTotals = new ConcurrentHashMap<>();

    // Custom gauges (sampled at snapshot time)
    private final ConcurrentHashMap<String, java.util.function.Supplier<Long>> gauges = new ConcurrentHashMap<>();

    // Custom timers (reset on snapshot)
    private final ConcurrentHashMap<String, TimerAccumulator> timerAccumulators = new ConcurrentHashMap<>();

    // Per-route stats (cumulative, not reset on rotation)
    private final ConcurrentHashMap<String, RouteStats> routes = new ConcurrentHashMap<>();

    // Ring buffer of per-minute snapshots (60 slots)
    private final MinuteSnapshot[] ringBuffer = new MinuteSnapshot[60];
    private int ringHead = 0;
    private int ringSize = 0;
    private final Object ringLock = new Object();

    // Deduplicated recent errors (max 50)
    private final List<ErrorRecord> errors = new ArrayList<>();
    private final Object errorsLock = new Object();
    private static final int MAX_ERRORS = 50;
    // Stable ids so the no-database /ops/errors/{id} and resolve paths can address records.
    private final java.util.concurrent.atomic.AtomicLong errorIdSeq = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Records a request against a raw URL path. The path is redacted (secrets must never
     * reach /ops/status) but remains concrete — {@code /users/1} and {@code /users/2} are
     * distinct keys, so every distinct URL mints a permanent entry in the never-reset
     * {@code routes} map.
     *
     * <p><strong>The framework does not use this.</strong> {@code BraceHandler} routes every
     * request — matched or not — through {@link #recordRequestPattern}, which is bounded by the
     * route table (H7, re-broken and re-fixed as correctness H1). It stays public for apps that
     * want to record their own synthetic entries and can vouch for the key's cardinality; if the
     * path is at all user-influenced, use {@link #recordRequestPattern} with a constant instead.
     */
    public void recordRequest(String method, String path, int status, long latencyUs,
                              int queryCount, long queryUs) {
        record(method + " " + Redactor.redactPath(path), status, latencyUs, queryCount, queryUs);
    }

    /**
     * Records a request against its matched route pattern (e.g. {@code GET /users/{id}}).
     * Patterns are code-site literals: no redaction needed, and the routes map stays
     * bounded by the number of registered routes instead of growing per distinct URL —
     * previously ID-bearing paths leaked one map entry per entity ever requested (H7).
     */
    void recordRequestPattern(String method, String routePattern, int status, long latencyUs,
                              int queryCount, long queryUs) {
        record(method + " " + routePattern, status, latencyUs, queryCount, queryUs);
    }

    private void record(String routeKey, int status, long latencyUs,
                        int queryCount, long queryUs) {
        requestCount.increment();
        totalLatencyUs.add(latencyUs);
        totalQueryCount.add(queryCount);
        totalQueryUs.add(queryUs);

        if (status >= 500) {
            errorCount.increment();
        }

        statusCodes.computeIfAbsent(status, k -> new LongAdder()).increment();

        // Update max latency
        long current = maxLatencyUs.get();
        while (latencyUs > current) {
            if (maxLatencyUs.compareAndSet(current, latencyUs)) break;
            current = maxLatencyUs.get();
        }

        routes.computeIfAbsent(routeKey, k -> new RouteStats()).record(latencyUs);
    }

    public void recordError(String type, String message, String route,
                            String stackTrace, String requestDetail, String queriesBefore) {
        // This record is served on /ops/status; exception messages can carry embedded
        // credentials or SQL literals, so run the value-shaped pass at the sink.
        message = Redactor.redactMessage(message);
        synchronized (errorsLock) {
            String dedupeKey = type + "|" + route;
            for (ErrorRecord rec : errors) {
                if (rec.dedupeKey.equals(dedupeKey)) {
                    rec.count++;
                    rec.lastSeen = Instant.now();
                    rec.stackTrace = stackTrace;
                    rec.requestDetail = requestDetail;
                    return;
                }
            }
            if (errors.size() >= MAX_ERRORS) {
                errors.remove(0);
            }
            var rec = new ErrorRecord();
            rec.id = errorIdSeq.incrementAndGet();
            rec.dedupeKey = dedupeKey;
            rec.type = type;
            rec.message = message;
            rec.route = route;
            rec.stackTrace = stackTrace;
            rec.requestDetail = requestDetail;
            rec.queriesBefore = queriesBefore;
            rec.firstSeen = Instant.now();
            rec.lastSeen = rec.firstSeen;
            rec.count = 1;
            errors.add(rec);
        }
    }

    // --- Custom metric methods ---

    public void counter(String name) { counter(name, 1); }

    public void counter(String name, long amount) {
        counterDeltas.computeIfAbsent(name, k -> new LongAdder()).add(amount);
        counterTotals.computeIfAbsent(name, k -> new LongAdder()).add(amount);
    }

    public long counterTotal(String name) {
        var adder = counterTotals.get(name);
        return adder != null ? adder.sum() : 0;
    }

    public void gauge(String name, java.util.function.Supplier<Long> supplier) {
        gauges.put(name, supplier);
    }

    public void timer(String name, long durationMs) {
        timerAccumulators.computeIfAbsent(name, k -> new TimerAccumulator()).record(durationMs);
    }

    public MinuteSnapshot snapshot() {
        long requests = requestCount.sumThenReset();
        long errs = errorCount.sumThenReset();
        long latencyUs = totalLatencyUs.sumThenReset();
        long queries = totalQueryCount.sumThenReset();
        long queryUs = totalQueryUs.sumThenReset();
        long maxUs = maxLatencyUs.getAndSet(0);
        long heapMB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);

        // Capture counter deltas and reset
        var cDeltas = new java.util.LinkedHashMap<String, Long>();
        for (var entry : counterDeltas.entrySet()) {
            long val = entry.getValue().sumThenReset();
            if (val != 0) cDeltas.put(entry.getKey(), val);
        }

        // Sample gauges
        var gValues = new java.util.LinkedHashMap<String, Long>();
        for (var entry : gauges.entrySet()) {
            gValues.put(entry.getKey(), entry.getValue().get());
        }

        // Capture timers and reset
        var tValues = new java.util.LinkedHashMap<String, TimerSnapshot>();
        for (var entry : timerAccumulators.entrySet()) {
            var acc = entry.getValue();
            var snap = acc.snapshotAndReset();
            if (snap != null) tValues.put(entry.getKey(), snap);
        }

        var snapshot = new MinuteSnapshot(
            Instant.now(), requests, errs, latencyUs, maxUs, queries, queryUs, heapMB,
            Collections.unmodifiableMap(cDeltas),
            Collections.unmodifiableMap(gValues),
            Collections.unmodifiableMap(tValues)
        );

        synchronized (ringLock) {
            ringBuffer[ringHead] = snapshot;
            ringHead = (ringHead + 1) % ringBuffer.length;
            if (ringSize < ringBuffer.length) ringSize++;
        }

        return snapshot;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public long totalRequests() {
        return requestCount.sum();
    }

    public Map<Integer, Long> statusCodeCounts() {
        return statusCodes.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
    }

    public List<MinuteSnapshot> minuteSnapshots() {
        synchronized (ringLock) {
            if (ringSize == 0) return List.of();
            var list = new ArrayList<MinuteSnapshot>(ringSize);
            // Oldest first: if buffer is full, oldest is at ringHead; otherwise starts at 0
            if (ringSize < ringBuffer.length) {
                for (int i = 0; i < ringSize; i++) {
                    list.add(ringBuffer[i]);
                }
            } else {
                for (int i = 0; i < ringSize; i++) {
                    list.add(ringBuffer[(ringHead + i) % ringBuffer.length]);
                }
            }
            return Collections.unmodifiableList(list);
        }
    }

    public List<ErrorRecord> recentErrors() {
        synchronized (errorsLock) {
            return List.copyOf(errors);
        }
    }

    /** Find a tracked error by id, or null. Backs the no-database /ops/errors/{id}. */
    public ErrorRecord findError(long id) {
        synchronized (errorsLock) {
            for (ErrorRecord rec : errors) {
                if (rec.id == id) return rec;
            }
            return null;
        }
    }

    /**
     * Remove a tracked error by id; returns the removed record or null. The no-database
     * resolve path: without it one transient exception keeps {@code errors.count} (and
     * {@code brace status}) red until process restart.
     */
    public ErrorRecord resolveError(long id) {
        // Reuses findError under the same (reentrant) lock so find-and-remove is atomic.
        synchronized (errorsLock) {
            ErrorRecord rec = findError(id);
            if (rec != null) errors.remove(rec);
            return rec;
        }
    }

    public Map<String, Long> counterTotals() {
        var result = new java.util.LinkedHashMap<String, Long>();
        for (var entry : counterTotals.entrySet()) {
            result.put(entry.getKey(), entry.getValue().sum());
        }
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Long> currentGaugeValues() {
        var result = new java.util.LinkedHashMap<String, Long>();
        for (var entry : gauges.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return Collections.unmodifiableMap(result);
    }

    public Map<String, TimerSnapshot> lastTimerValues() {
        var snapshots = minuteSnapshots();
        if (snapshots.isEmpty()) return Map.of();
        return snapshots.getLast().timerValues();
    }

    public Map<String, RouteStats> routeStats() {
        return Collections.unmodifiableMap(routes);
    }

    // --- Inner types ---

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
            if (requests == 0) return 0.0;
            return (totalLatencyUs / (double) requests) / 1000.0;
        }
    }

    public record TimerSnapshot(long count, double avgMs, long maxMs) {}

    public static class TimerAccumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalMs = new LongAdder();
        private final AtomicLong maxMs = new AtomicLong(0);

        void record(long durationMs) {
            count.increment();
            totalMs.add(durationMs);
            long current = maxMs.get();
            while (durationMs > current) {
                if (maxMs.compareAndSet(current, durationMs)) break;
                current = maxMs.get();
            }
        }

        TimerSnapshot snapshotAndReset() {
            long c = count.sumThenReset();
            long t = totalMs.sumThenReset();
            long m = maxMs.getAndSet(0);
            if (c == 0) return null;
            return new TimerSnapshot(c, (double) t / c, m);
        }
    }

    public static class RouteStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalUs = new LongAdder();

        void record(long latencyUs) {
            count.increment();
            totalUs.add(latencyUs);
        }

        public long count() {
            return count.sum();
        }

        public double avgLatencyMs() {
            long c = count.sum();
            if (c == 0) return 0.0;
            return (totalUs.sum() / (double) c) / 1000.0;
        }
    }

    public static class ErrorRecord {
        public long id;
        String dedupeKey;
        public String type;
        public String message;
        public String route;
        public String stackTrace;
        public String requestDetail;
        public String queriesBefore;
        public Instant firstSeen;
        public Instant lastSeen;
        public int count;
    }
}
