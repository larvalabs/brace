package com.larvalabs.brace;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Persists exception data to the ops_errors table.
 *
 * <p>Recording is coalesced (perf review H9): {@link #record} is a cheap in-memory merge per
 * {@code (type, route)}, and a single flusher thread writes one upsert per distinct error kind
 * every {@value #FLUSH_INTERVAL_MS}ms. Error floods happen exactly when the DB is degraded — the
 * old per-occurrence path (one virtual thread + 1–2 transactions each) starved the connection pool
 * that healthy requests needed. Coalescing loses nothing the table keeps ({@code ops_errors} is
 * already one row per kind with a count and the latest payload); the costs are bounded and
 * deliberate: a crash drops at most one flush interval of counts, dashboards and regression
 * notifications lag by at most one interval, and past {@value #MAX_PENDING_KINDS} distinct pending
 * kinds new kinds are dropped (counted, warned on flush). Occurrences of already-pending kinds are
 * never dropped — they're a counter bump. Tests and shutdown call {@link #flush()} directly.
 */
public class ErrorStore {

    /**
     * Notified when a flush commits, so a {@link RegressionTracker} can detect new error kinds
     * since startup without ErrorStore re-doing the existence check. {@link #onNew} fires when a
     * brand-new {@code (type, route)} is inserted; {@link #onRepeat} when an existing one recurs,
     * with the number of occurrences coalesced into that flush.
     */
    public interface RegressionListener {
        void onNew(String type, String route, String message, Instant firstSeen);
        void onRepeat(String type, String route, long count);
    }

    static final long FLUSH_INTERVAL_MS = 2_000;
    static final int MAX_PENDING_KINDS = 1_000;

    /** One coalesced error kind awaiting flush: occurrence count + the latest payload. */
    private static final class Pending {
        final String type;
        final String route;
        final Instant firstSeen;
        long count;
        String message;
        String stackTrace;
        String requestDetail;
        String queriesBefore;
        String requestHeaders;
        Instant lastSeen;

        Pending(String type, String route, Instant firstSeen) {
            this.type = type;
            this.route = route;
            this.firstSeen = firstSeen;
        }
    }

    private final DatabaseFactory databaseFactory;
    private final int maxErrors;
    private volatile RegressionListener regressionListener;
    private final java.util.concurrent.ConcurrentHashMap<String, Pending> pending =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.LongAdder droppedKinds =
        new java.util.concurrent.atomic.LongAdder();
    private final Thread flusher;

    public ErrorStore(DatabaseFactory databaseFactory, int maxErrors) {
        this.databaseFactory = databaseFactory;
        this.maxErrors = maxErrors;
        this.flusher = Thread.ofVirtual().name("brace-error-flush").start(() -> {
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                try {
                    flush();
                } catch (Exception e) {
                    // Never let the flusher die; the next interval retries.
                }
            }
        });
    }

    /** Stop the flusher and write out anything still buffered. Called by {@code Brace.stop()}. */
    public void close() {
        flusher.interrupt();
        try {
            flush();
        } catch (Exception e) {
            // Best effort on shutdown.
        }
    }

    public void setRegressionListener(RegressionListener listener) {
        this.regressionListener = listener;
    }

    public void record(String type, String message, String route, String stackTrace, String requestDetail) {
        record(type, message, route, stackTrace, requestDetail, null, null);
    }

    /**
     * Record an error with the instant-of-failure context captured at the catch point:
     * {@code queriesBefore} (a small JSON summary of DB work done before the throw) and
     * {@code requestHeaders} (the redacted request headers). Both may be null.
     *
     * <p>Cheap and non-blocking: merges into the in-memory buffer; the flusher persists it within
     * {@value #FLUSH_INTERVAL_MS}ms. Safe to call on the request thread.
     */
    public void record(String type, String message, String route, String stackTrace,
                       String requestDetail, String queriesBefore, String requestHeaders) {
        var now = Instant.now();
        var key = type + '\u0000' + route;
        if (pending.size() >= MAX_PENDING_KINDS && !pending.containsKey(key)) {
            droppedKinds.increment();
            return;
        }
        pending.compute(key, (k, p) -> {
            if (p == null) p = new Pending(type, route, now);
            p.count++;
            p.message = message;
            p.stackTrace = stackTrace;
            p.requestDetail = requestDetail;
            p.queriesBefore = queriesBefore;
            p.requestHeaders = requestHeaders;
            p.lastSeen = now;
            return p;
        });
    }

    /**
     * Drain the buffer and persist it: one upsert per distinct {@code (type, route)} carrying the
     * coalesced occurrence count, plus one prune check — all in a single transaction on a single
     * connection. On failure the drained entries are merged back and the next interval retries.
     * Serialized so the periodic, shutdown, and test-triggered flushes can't interleave.
     */
    public synchronized void flush() {
        long dropped = droppedKinds.sumThenReset();
        if (dropped > 0) {
            Log.warn("error storm: dropped " + dropped + " new error kinds beyond the "
                + MAX_PENDING_KINDS + "-kind pending buffer");
        }
        if (pending.isEmpty()) {
            return;
        }
        // Drain a snapshot; records arriving during the flush re-create entries for the next one.
        var batch = new ArrayList<Pending>();
        for (var key : pending.keySet()) {
            var p = pending.remove(key);
            if (p != null) batch.add(p);
        }
        if (batch.isEmpty()) {
            return;
        }

        var isNew = new boolean[batch.size()];
        boolean committed = false;
        var db = new Database(databaseFactory.openSession());
        db.beginTransaction();
        try {
            for (int i = 0; i < batch.size(); i++) {
                var p = batch.get(i);
                isNew[i] = databaseFactory.isPostgres() ? upsertPostgres(db, p) : upsertH2(db, p);
            }

            // Prune if over limit — once per flush, not per occurrence.
            var countResult = db.sqlQueryLong("SELECT COUNT(*) FROM ops_errors");
            if (countResult != null && countResult > maxErrors) {
                long excess = countResult - maxErrors;
                // Delete resolved first (oldest), then unresolved oldest
                db.sql("DELETE FROM ops_errors WHERE id IN (" +
                    "SELECT id FROM ops_errors ORDER BY " +
                    "CASE WHEN resolved_at IS NOT NULL THEN 0 ELSE 1 END, " +
                    "last_seen ASC " +
                    "LIMIT ?)", (int) excess);
            }

            db.commitTransaction();
            committed = true;
        } catch (Exception e) {
            db.rollbackTransaction();
            // DB unavailable (the storm case): put the batch back so counts keep accumulating —
            // bounded by kind cardinality, not error rate — and retry next interval.
            for (var p : batch) {
                mergeBack(p);
            }
        } finally {
            db.close();
        }

        // Notify the regression listener only after a successful commit, off the DB path.
        var listener = regressionListener;
        if (committed && listener != null) {
            for (int i = 0; i < batch.size(); i++) {
                var p = batch.get(i);
                if (isNew[i]) listener.onNew(p.type, p.route, p.message, p.firstSeen);
                else listener.onRepeat(p.type, p.route, p.count);
            }
        }
    }

    /** Re-merge a failed-flush entry without clobbering anything newer that arrived meanwhile. */
    private void mergeBack(Pending old) {
        pending.compute(old.type + '\u0000' + old.route, (k, p) -> {
            if (p == null) return old;
            p.count += old.count; // keep p's payload — it's newer
            return p;
        });
    }

    /**
     * Postgres path: a single atomic upsert. Two instances (or threads) recording the same
     * {@code (error_type, route)} concurrently can't both insert — the partial unique index
     * {@code ops_errors_unresolved_dedupe} (see {@code migration_pg/V6}) forces one to take the
     * {@code DO UPDATE} branch and bump the count under the row lock, so no duplicate row and no
     * lost increment. The H2 check-then-insert ({@link #upsertH2}) has that race; H2 can't model
     * it, which is why this branch is proven by {@code ErrorStorePostgresIT}, not the unit suite.
     *
     * <p>Runs through raw JDBC (like the durable-job claim) so Hibernate doesn't try to classify an
     * {@code INSERT ... RETURNING} as a plain mutation. {@code RETURNING (xmax = 0)} is the standard
     * idiom for "was this a fresh insert?": {@code xmax} is 0 on a row this statement inserted,
     * non-zero on one it updated — that's the new-vs-repeat signal the regression listener needs.
     */
    private boolean upsertPostgres(Database db, Pending p) {
        return db.jdbc(connection -> {
            try (var ps = connection.prepareStatement(
                "INSERT INTO ops_errors " +
                "(error_type, message, stack_trace, route, request_detail, queries_before, request_headers, first_seen, last_seen, occurrence_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (error_type, route) WHERE resolved_at IS NULL DO UPDATE SET " +
                "occurrence_count = ops_errors.occurrence_count + EXCLUDED.occurrence_count, " +
                "message = EXCLUDED.message, stack_trace = EXCLUDED.stack_trace, " +
                "request_detail = EXCLUDED.request_detail, queries_before = EXCLUDED.queries_before, " +
                "request_headers = EXCLUDED.request_headers, last_seen = EXCLUDED.last_seen " +
                "RETURNING (xmax = 0) AS inserted")) {
                ps.setString(1, p.type);
                ps.setString(2, p.message);
                ps.setString(3, p.stackTrace);
                ps.setString(4, p.route);
                ps.setString(5, p.requestDetail);
                ps.setString(6, p.queriesBefore);
                ps.setString(7, p.requestHeaders);
                ps.setTimestamp(8, Timestamp.from(p.firstSeen));
                ps.setTimestamp(9, Timestamp.from(p.lastSeen));
                ps.setLong(10, p.count);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean("inserted");
                }
            }
        });
    }

    /**
     * H2 path: check for an existing unresolved {@code (type, route)}, then UPDATE-or-INSERT. Not
     * atomic — see {@link #upsertPostgres} for the race this carries and why H2 keeps it anyway
     * (no partial unique index, different upsert syntax). Returns true if a new row was inserted.
     */
    private boolean upsertH2(Database db, Pending p) {
        var existing = db.sqlQuery(
            "SELECT id, occurrence_count FROM ops_errors WHERE error_type = ? AND route = ? AND resolved_at IS NULL",
            p.type, p.route);

        if (!existing.isEmpty()) {
            Object[] row = existing.get(0);
            long id = ((Number) row[0]).longValue();
            long count = ((Number) row[1]).longValue();
            db.sql("UPDATE ops_errors SET occurrence_count = ?, message = ?, stack_trace = ?, request_detail = ?, queries_before = ?, request_headers = ?, last_seen = ? WHERE id = ?",
                count + p.count, p.message, p.stackTrace, p.requestDetail, p.queriesBefore, p.requestHeaders, Timestamp.from(p.lastSeen), id);
            return false;
        }
        db.sql("INSERT INTO ops_errors (error_type, message, stack_trace, route, request_detail, queries_before, request_headers, first_seen, last_seen, occurrence_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            p.type, p.message, p.stackTrace, p.route, p.requestDetail, p.queriesBefore, p.requestHeaders,
            Timestamp.from(p.firstSeen), Timestamp.from(p.lastSeen), p.count);
        return true;
    }

    public List<Map<String, Object>> list(String status) {
        return list(status, null);
    }

    public List<Map<String, Object>> list(String status, java.time.Instant since) {
        var db = new Database(databaseFactory.openSession());
        try {
            // L17: filter both the resolved/unresolved status AND the since-cutoff in SQL. The old
            // path fetched every matching row — each carrying the full stack_trace / request_detail /
            // request_headers text — then dropped the too-old ones in a Java loop. `first_seen >= ?`
            // preserves the prior Java semantics exactly: a NULL first_seen is never `>= ?`, so those
            // rows are excluded as before. `status` maps to a fixed clause (never user SQL).
            String where = "resolved".equals(status) ? "resolved_at IS NOT NULL" : "resolved_at IS NULL";
            String sql = "SELECT id, error_type, message, stack_trace, route, request_detail, first_seen, "
                + "last_seen, occurrence_count, resolved_at, queries_before, request_headers FROM ops_errors WHERE "
                + where;
            List<Object[]> rows;
            if (since != null) {
                rows = db.sqlQuery(sql + " AND first_seen >= ? ORDER BY last_seen DESC", Timestamp.from(since));
            } else {
                rows = db.sqlQuery(sql + " ORDER BY last_seen DESC");
            }

            var result = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                result.add(mapRow(row));
            }
            return result;
        } finally {
            db.close();
        }
    }

    private static final String FULL_COLUMNS =
        "id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, "
        + "occurrence_count, resolved_at, queries_before, request_headers";

    /** Maps a full {@link #FULL_COLUMNS} ops_errors row to the JSON shape the ops endpoints return. */
    private static Map<String, Object> mapRow(Object[] row) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", ((Number) row[0]).longValue());
        map.put("errorType", row[1]);
        map.put("message", row[2]);
        map.put("stackTrace", row[3]);
        map.put("route", row[4]);
        map.put("requestDetail", row[5]);
        map.put("firstSeen", toInstant(row[6]));
        map.put("lastSeen", toInstant(row[7]));
        map.put("occurrenceCount", ((Number) row[8]).intValue());
        map.put("resolvedAt", toInstant(row[9]));
        map.put("queriesBefore", row[10]);
        map.put("requestHeaders", row[11]);
        return map;
    }

    /** Count of unresolved errors — for the ops dashboard summary (token-efficiency review). */
    public long countUnresolved() {
        var db = new Database(databaseFactory.openSession());
        try {
            Long n = db.sqlQueryLong("SELECT COUNT(*) FROM ops_errors WHERE resolved_at IS NULL");
            return n != null ? n : 0;
        } finally {
            db.close();
        }
    }

    /** Most-recent unresolved errors (compact shape + first app stack frame) for the dashboard. */
    public List<Map<String, Object>> recentUnresolved(int limit) {
        var db = new Database(databaseFactory.openSession());
        try {
            var rows = db.sqlQuery(
                "SELECT id, error_type, message, route, occurrence_count, first_seen, last_seen, stack_trace " +
                "FROM ops_errors WHERE resolved_at IS NULL ORDER BY last_seen DESC LIMIT ?", limit);
            var result = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", ((Number) row[0]).longValue());
                m.put("errorType", row[1]);
                m.put("message", row[2]);
                m.put("route", row[3]);
                m.put("occurrenceCount", ((Number) row[4]).intValue());
                m.put("firstSeen", toInstant(row[5]));
                m.put("lastSeen", toInstant(row[6]));
                String at = Log.appFrame((String) row[7]);
                if (at != null) m.put("at", at);
                result.add(m);
            }
            return result;
        } finally {
            db.close();
        }
    }

    /** Full detail for one error by id, or null if not found. */
    public Map<String, Object> find(long id) {
        var db = new Database(databaseFactory.openSession());
        try {
            var rows = db.sqlQuery("SELECT " + FULL_COLUMNS + " FROM ops_errors WHERE id = ?", id);
            if (rows.isEmpty()) return null;
            return mapRow(rows.get(0));
        } finally {
            db.close();
        }
    }

    /**
     * Normalize whatever temporal type the JDBC driver surfaces for a {@code TIMESTAMP WITH TIME
     * ZONE} column into an absolute {@link Instant}. Hibernate native queries hand back an
     * {@link OffsetDateTime} on both H2 and Postgres, but {@link Timestamp} and {@link Instant} are
     * accepted too. This replaces the old multi-format string parser ({@code parseFirstSeen}): the
     * column stores a real instant now, so this is type dispatch, not zone guessing — and an
     * unexpected type fails loudly rather than silently returning null.
     */
    private static Instant toInstant(Object o) {
        return switch (o) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime odt -> odt.toInstant();
            case Timestamp ts -> ts.toInstant();
            default -> throw new IllegalStateException(
                "Unexpected timestamp type from DB: " + o.getClass().getName());
        };
    }

    public Map<String, Object> resolve(long id) {
        var db = new Database(databaseFactory.openSession());
        db.beginTransaction();
        try {
            var now = Timestamp.from(Instant.now());
            db.sql("UPDATE ops_errors SET resolved_at = ? WHERE id = ?", now, id);
            db.commitTransaction();

            // Re-fetch the updated record
            var rows = db.sqlQuery(
                "SELECT id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count, resolved_at, queries_before, request_headers FROM ops_errors WHERE id = ?", id);

            if (rows.isEmpty()) return null;
            var row = rows.get(0);
            var map = new LinkedHashMap<String, Object>();
            map.put("id", ((Number) row[0]).longValue());
            map.put("errorType", row[1]);
            map.put("message", row[2]);
            map.put("stackTrace", row[3]);
            map.put("route", row[4]);
            map.put("requestDetail", row[5]);
            map.put("firstSeen", toInstant(row[6]));
            map.put("lastSeen", toInstant(row[7]));
            map.put("occurrenceCount", ((Number) row[8]).intValue());
            map.put("resolvedAt", toInstant(row[9]));
            return map;
        } catch (Exception e) {
            db.rollbackTransaction();
            return null;
        } finally {
            db.close();
        }
    }
}
