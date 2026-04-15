package io.brace;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory ring buffer of structured log entries. Captures everything that
 * flows through {@link Log#println(Map)} so that /ops/logs can serve a
 * tail-able view of recent activity. Lock-free; lost on process restart.
 *
 * <p>IDs are monotonic for the lifetime of the JVM. They are not reset by
 * {@link #clear()} so polling clients holding a {@code since(id)} cursor
 * never see negative gaps.
 *
 * <p>Under heavy concurrent appends the deque may transiently hold up to
 * {@code capacity + (concurrent appender count)} entries before the next
 * round of single-step eviction settles it back to {@code capacity}. This
 * bounded overshoot is the trade for a fully lock-free hot path.
 */
public class LogTap {

    public record LogEntry(long id, Map<String, Object> fields) {}

    private static final ConcurrentLinkedDeque<LogEntry> entries = new ConcurrentLinkedDeque<>();
    private static final AtomicLong nextId = new AtomicLong(1);
    private static final AtomicInteger currentSize = new AtomicInteger(0);
    private static volatile int capacity = 1000;

    private LogTap() {}

    public static void setCapacity(int n) {
        if (n < 1) throw new IllegalArgumentException("capacity must be >= 1");
        capacity = n;
        while (currentSize.get() > n) {
            if (entries.pollFirst() != null) {
                currentSize.decrementAndGet();
            } else {
                break;
            }
        }
    }

    public static int capacity() { return capacity; }

    public static void append(Map<String, Object> fields) {
        long id = nextId.getAndIncrement();
        // Defensive copy: callers must not be coupled to internal state, and
        // snapshot()/since() readers see immutable maps.
        // LinkedHashMap used instead of Map.copyOf because Log.error() paths can
        // put null values (e.g. throwable.getMessage()) which Map.copyOf rejects.
        var copy = new LinkedHashMap<>(fields);
        entries.add(new LogEntry(id, copy));
        if (currentSize.incrementAndGet() > capacity) {
            if (entries.pollFirst() != null) {
                currentSize.decrementAndGet();
            }
        }
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

    /**
     * Clears all retained entries. IDs are NOT reset — the next appended entry
     * will continue from the current monotonic counter so that polling clients
     * holding a since(id) cursor are unaffected.
     */
    public static void clear() {
        entries.clear();
        currentSize.set(0);
    }
}
