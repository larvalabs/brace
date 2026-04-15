package io.brace;

import org.hibernate.StatelessSession;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Persists exception data to the ops_errors table.
 * Each operation opens its own StatelessSession and manages its own transaction.
 */
public class ErrorStore {

    private final DatabaseFactory databaseFactory;
    private final int maxErrors;

    public ErrorStore(DatabaseFactory databaseFactory, int maxErrors) {
        this.databaseFactory = databaseFactory;
        this.maxErrors = maxErrors;
    }

    public void record(String type, String message, String route, String stackTrace, String requestDetail) {
        var db = new Database(databaseFactory.openSession());
        db.beginTransaction();
        try {
            // Look for existing unresolved error with same type+route
            var existing = db.sqlQuery(
                "SELECT id, occurrence_count FROM ops_errors WHERE error_type = ? AND route = ? AND resolved_at IS NULL",
                type, route);

            if (!existing.isEmpty()) {
                Object[] row = existing.get(0);
                long id = ((Number) row[0]).longValue();
                int count = ((Number) row[1]).intValue();
                db.sql("UPDATE ops_errors SET occurrence_count = ?, message = ?, stack_trace = ?, request_detail = ?, last_seen = ? WHERE id = ?",
                    count + 1, message, stackTrace, requestDetail, Timestamp.from(Instant.now()), id);
            } else {
                var now = Timestamp.from(Instant.now());
                db.sql("INSERT INTO ops_errors (error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    type, message, stackTrace, route, requestDetail, now, now, 1);
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
        } catch (Exception e) {
            db.rollbackTransaction();
        } finally {
            db.close();
        }
    }

    public List<Map<String, Object>> list(String status) {
        var db = new Database(databaseFactory.openSession());
        try {
            String sql;
            List<Object[]> rows;
            if ("resolved".equals(status)) {
                sql = "SELECT id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count, resolved_at FROM ops_errors WHERE resolved_at IS NOT NULL ORDER BY last_seen DESC";
                rows = db.sqlQuery(sql);
            } else {
                sql = "SELECT id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count, resolved_at FROM ops_errors WHERE resolved_at IS NULL ORDER BY last_seen DESC";
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
            try {
                java.time.Instant ts;
                String s = firstSeen.toString();
                if (s.endsWith("Z") || s.contains("+")) {
                    // Already ISO-8601 with timezone
                    ts = java.time.Instant.parse(s);
                } else {
                    // Timestamp.toString() format: "yyyy-MM-dd HH:mm:ss.SSS" in local time
                    String normalized = s.replace(' ', 'T');
                    ts = java.time.LocalDateTime.parse(normalized)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant();
                }
                if (!ts.isBefore(since)) out.add(row);
            } catch (Exception ignored) {
                // Fall back to keeping the row if timestamp is unparseable
                out.add(row);
            }
        }
        return out;
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
                "SELECT id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count, resolved_at FROM ops_errors WHERE id = ?", id);

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
