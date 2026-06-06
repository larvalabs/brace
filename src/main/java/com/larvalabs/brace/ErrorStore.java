package com.larvalabs.brace;

import java.sql.Timestamp;
import java.time.Instant;
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

    public List<Map<String, Object>> list(String status) {
        var db = new Database(databaseFactory.openSession());
        try {
            String sql;
            List<Object[]> rows;
            if ("resolved".equals(status)) {
                sql = "SELECT id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count, resolved_at, queries_before, request_headers FROM ops_errors WHERE resolved_at IS NOT NULL ORDER BY last_seen DESC";
                rows = db.sqlQuery(sql);
            } else {
                sql = "SELECT id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count, resolved_at, queries_before, request_headers FROM ops_errors WHERE resolved_at IS NULL ORDER BY last_seen DESC";
                rows = db.sqlQuery(sql);
            }

            var result = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                var map = new LinkedHashMap<String, Object>();
                map.put("id", ((Number) row[0]).longValue());
                map.put("errorType", row[1]);
                map.put("message", row[2]);
                map.put("stackTrace", row[3]);
                map.put("route", row[4]);
                map.put("requestDetail", row[5]);
                map.put("firstSeen", row[6] != null ? row[6].toString() : null);
                map.put("lastSeen", row[7] != null ? row[7].toString() : null);
                map.put("occurrenceCount", ((Number) row[8]).intValue());
                map.put("resolvedAt", row[9] != null ? row[9].toString() : null);
                map.put("queriesBefore", row[10]);
                map.put("requestHeaders", row[11]);
                result.add(map);
            }
            return result;
        } finally {
            db.close();
        }
    }

    public List<Map<String, Object>> list(String status, java.time.Instant since) {
        var all = list(status);
        if (since == null) return all;
        var out = new ArrayList<Map<String, Object>>();
        for (var row : all) {
            Object firstSeen = row.get("firstSeen");
            if (firstSeen == null) continue;
            java.time.Instant ts = parseFirstSeen(firstSeen.toString());
            if (ts == null || !ts.isBefore(since)) out.add(row);
        }
        return out;
    }

    // Unparseable values fall through to null and are kept, to avoid silently
    // dropping rows if a future storage format appears.
    private static java.time.Instant parseFirstSeen(String s) {
        try { return java.time.Instant.parse(s); } catch (java.time.format.DateTimeParseException ignored) {}
        try { return java.time.OffsetDateTime.parse(s).toInstant(); } catch (java.time.format.DateTimeParseException ignored) {}
        try {
            return java.time.LocalDateTime.parse(s.replace(' ', 'T'))
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant();
        } catch (java.time.format.DateTimeParseException ignored) {}
        return null;
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
            map.put("firstSeen", row[6] != null ? row[6].toString() : null);
            map.put("lastSeen", row[7] != null ? row[7].toString() : null);
            map.put("occurrenceCount", ((Number) row[8]).intValue());
            map.put("resolvedAt", row[9] != null ? row[9].toString() : null);
            return map;
        } catch (Exception e) {
            db.rollbackTransaction();
            return null;
        } finally {
            db.close();
        }
    }
}
