package com.larvalabs.brace;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Persists exception data to the ops_errors table.
 * Each operation opens its own StatelessSession and manages its own transaction.
 */
public class ErrorStore {

    /**
     * Notified when an error is recorded, so a {@link RegressionTracker} can detect new
     * error kinds since startup without ErrorStore re-doing the existence check. {@link #onNew}
     * fires when a brand-new {@code (type, route)} is inserted; {@link #onRepeat} when an
     * existing one recurs.
     */
    public interface RegressionListener {
        void onNew(String type, String route, String message, Instant firstSeen);
        void onRepeat(String type, String route);
    }

    private final DatabaseFactory databaseFactory;
    private final int maxErrors;
    private volatile RegressionListener regressionListener;

    public ErrorStore(DatabaseFactory databaseFactory, int maxErrors) {
        this.databaseFactory = databaseFactory;
        this.maxErrors = maxErrors;
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
     */
    public void record(String type, String message, String route, String stackTrace,
                       String requestDetail, String queriesBefore, String requestHeaders) {
        var db = new Database(databaseFactory.openSession());
        db.beginTransaction();
        Instant firstSeen = Instant.now();
        boolean isNew = false;
        boolean committed = false;
        try {
            if (databaseFactory.isPostgres()) {
                isNew = upsertPostgres(db, type, message, route, stackTrace, requestDetail,
                    queriesBefore, requestHeaders, firstSeen);
            } else {
                isNew = upsertH2(db, type, message, route, stackTrace, requestDetail,
                    queriesBefore, requestHeaders, firstSeen);
            }

            // Prune if over limit
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
        } finally {
            db.close();
        }

        // Notify the regression listener only after a successful commit, off the DB path.
        var listener = regressionListener;
        if (committed && listener != null) {
            if (isNew) listener.onNew(type, route, message, firstSeen);
            else listener.onRepeat(type, route);
        }
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
    private boolean upsertPostgres(Database db, String type, String message, String route,
                                   String stackTrace, String requestDetail, String queriesBefore,
                                   String requestHeaders, Instant firstSeen) {
        var now = Timestamp.from(firstSeen);
        return db.jdbc(connection -> {
            try (var ps = connection.prepareStatement(
                "INSERT INTO ops_errors " +
                "(error_type, message, stack_trace, route, request_detail, queries_before, request_headers, first_seen, last_seen, occurrence_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1) " +
                "ON CONFLICT (error_type, route) WHERE resolved_at IS NULL DO UPDATE SET " +
                "occurrence_count = ops_errors.occurrence_count + 1, " +
                "message = EXCLUDED.message, stack_trace = EXCLUDED.stack_trace, " +
                "request_detail = EXCLUDED.request_detail, queries_before = EXCLUDED.queries_before, " +
                "request_headers = EXCLUDED.request_headers, last_seen = EXCLUDED.last_seen " +
                "RETURNING (xmax = 0) AS inserted")) {
                ps.setString(1, type);
                ps.setString(2, message);
                ps.setString(3, stackTrace);
                ps.setString(4, route);
                ps.setString(5, requestDetail);
                ps.setString(6, queriesBefore);
                ps.setString(7, requestHeaders);
                ps.setTimestamp(8, now);
                ps.setTimestamp(9, now);
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
    private boolean upsertH2(Database db, String type, String message, String route,
                             String stackTrace, String requestDetail, String queriesBefore,
                             String requestHeaders, Instant firstSeen) {
        var existing = db.sqlQuery(
            "SELECT id, occurrence_count FROM ops_errors WHERE error_type = ? AND route = ? AND resolved_at IS NULL",
            type, route);

        if (!existing.isEmpty()) {
            Object[] row = existing.get(0);
            long id = ((Number) row[0]).longValue();
            int count = ((Number) row[1]).intValue();
            db.sql("UPDATE ops_errors SET occurrence_count = ?, message = ?, stack_trace = ?, request_detail = ?, queries_before = ?, request_headers = ?, last_seen = ? WHERE id = ?",
                count + 1, message, stackTrace, requestDetail, queriesBefore, requestHeaders, Timestamp.from(firstSeen), id);
            return false;
        }
        var now = Timestamp.from(firstSeen);
        db.sql("INSERT INTO ops_errors (error_type, message, stack_trace, route, request_detail, queries_before, request_headers, first_seen, last_seen, occurrence_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            type, message, stackTrace, route, requestDetail, queriesBefore, requestHeaders, now, now, 1);
        return true;
    }

    /** Count of unresolved errors — the number {@code /ops/status} reports as {@code errors.count}. */
    public long countUnresolved() {
        var db = new Database(databaseFactory.openSession());
        try {
            Long n = db.sqlQueryLong("SELECT COUNT(*) FROM ops_errors WHERE resolved_at IS NULL");
            return n != null ? n : 0;
        } finally {
            db.close();
        }
    }

    /**
     * The most recent unresolved errors, summary fields only ({@code id, errorType, message,
     * route, occurrenceCount, firstSeen, lastSeen, at}) — the same row shape as the no-database
     * in-memory summaries, so {@code errors.recent} in {@code /ops/status} doesn't change shape
     * with the deployment mode. {@code at} is the first app frame of the stored trace; full
     * detail lives at {@code /ops/errors/{id}}.
     */
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

    /**
     * Cap on rows returned by {@link #list}. The store itself is pruned to {@code maxErrors}
     * (default 1000), but a list response carrying every heavy column unbounded is still a
     * needlessly large payload — recent-first plus {@code since} covers the real use.
     */
    static final int LIST_LIMIT = 500;

    public List<Map<String, Object>> list(String status) {
        return list(status, null);
    }

    public List<Map<String, Object>> list(String status, Instant since) {
        var db = new Database(databaseFactory.openSession());
        try {
            String where = "resolved".equals(status) ? "resolved_at IS NOT NULL" : "resolved_at IS NULL";
            String sql = "SELECT id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count, resolved_at, queries_before, request_headers "
                + "FROM ops_errors WHERE " + where
                + (since != null ? " AND first_seen >= ?" : "")
                + " ORDER BY last_seen DESC LIMIT ?";
            List<Object[]> rows = since != null
                ? db.sqlQuery(sql, Timestamp.from(since), LIST_LIMIT)
                : db.sqlQuery(sql, LIST_LIMIT);

            var result = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                result.add(mapRow(row));
            }
            return result;
        } finally {
            db.close();
        }
    }

    /** Fetch one error by id with full detail (any status), or null if the id is unknown. */
    public Map<String, Object> find(long id) {
        var db = new Database(databaseFactory.openSession());
        try {
            var rows = db.sqlQuery(
                "SELECT id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count, resolved_at, queries_before, request_headers FROM ops_errors WHERE id = ?", id);
            if (rows.isEmpty()) return null;
            return mapRow(rows.get(0));
        } finally {
            db.close();
        }
    }

    /** Map a full SELECT row (the column order used by {@link #list} and {@link #find}). */
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
        } catch (Exception e) {
            db.rollbackTransaction();
            return null;
        } finally {
            db.close();
        }
        // Re-fetch through find() — one row-mapping (this method used to hand-build a
        // copy of mapRow's map and had already drifted, omitting two fields).
        return find(id);
    }
}
