package io.brace;

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

    public void recordRequest(String method, String path, int status, long latencyUs,
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

        // Per-route stats
        String routeKey = method + " " + path;
        routes.computeIfAbsent(routeKey, k -> new RouteStats()).record(latencyUs);
    }

    public void recordError(String type, String message, String route,
                            String stackTrace, String requestDetail, String queriesBefore) {
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

    public MinuteSnapshot snapshot() {
        long requests = requestCount.sumThenReset();
        long errs = errorCount.sumThenReset();
        long latencyUs = totalLatencyUs.sumThenReset();
        long queries = totalQueryCount.sumThenReset();
        long queryUs = totalQueryUs.sumThenReset();
        long maxUs = maxLatencyUs.getAndSet(0);

        var snapshot = new MinuteSnapshot(
            Instant.now(), requests, errs, latencyUs, maxUs, queries, queryUs
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
        long queryUs
    ) {
        public double avgLatencyMs() {
            if (requests == 0) return 0.0;
            return (totalLatencyUs / (double) requests) / 1000.0;
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
