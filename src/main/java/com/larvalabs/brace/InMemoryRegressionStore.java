package com.larvalabs.brace;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-process {@link RegressionStore} — the default for single-server / non-Postgres apps. Holds the
 * regression set in a {@link ConcurrentHashMap} keyed by the tracker's stable id.
 */
final class InMemoryRegressionStore implements RegressionStore {

    private static final class Entry {
        final String id;
        final String type;
        final String route;
        volatile String message;
        final Instant firstSeen;
        final AtomicInteger count = new AtomicInteger(1);
        volatile Instant acknowledgedAt;

        Entry(String id, String type, String route, String message, Instant firstSeen) {
            this.id = id;
            this.type = type;
            this.route = route;
            this.message = message;
            this.firstSeen = firstSeen;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public boolean create(String id, String type, String route, String message, Instant firstSeen) {
        return entries.putIfAbsent(id, new Entry(id, type, route, message, firstSeen)) == null;
    }

    @Override
    public void bump(String id, long count) {
        Entry e = entries.get(id);
        if (e != null) e.count.addAndGet((int) Math.min(count, Integer.MAX_VALUE));
    }

    @Override
    public List<RegressionTracker.Regression> list() {
        return entries.values().stream()
            .sorted(Comparator.comparing((Entry e) -> e.firstSeen).reversed())
            .map(e -> new RegressionTracker.Regression(
                e.id, e.type, e.route, e.message, e.firstSeen, e.count.get(), e.acknowledgedAt))
            .toList();
    }

    @Override
    public boolean acknowledge(String id) {
        Entry e = entries.get(id);
        if (e == null) return false;
        if (e.acknowledgedAt == null) e.acknowledgedAt = Instant.now();
        return true;
    }
}
