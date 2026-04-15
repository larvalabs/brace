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
