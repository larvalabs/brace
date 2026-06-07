package com.larvalabs.brace;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared {@link RegressionStore} backed by {@code brace_regressions} (B6). Every instance reads and
 * writes one fleet-wide set, so {@code /ops/regressions}, acknowledge, and notify-once are consistent
 * across boxes. Rows are scoped to the current {@code deploy} marker, so the regression baseline is
 * anchored to the deploy rather than to each JVM's start time.
 */
final class PostgresRegressionStore implements RegressionStore {

    private final DatabaseFactory dbFactory;
    private final String deploy;

    PostgresRegressionStore(DatabaseFactory dbFactory, String deploy) {
        this.dbFactory = dbFactory;
        this.deploy = deploy;
    }

    @Override
    public boolean create(String id, String type, String route, String message, Instant firstSeen) {
        Timestamp seen = Timestamp.from(firstSeen);
        return inTx(conn -> {
            // The PK insert is the exactly-once claim: one instance inserts (1 row) and notifies;
            // any other observing the same new kind conflicts (0 rows) and stays silent.
            try (var ps = conn.prepareStatement(
                    "INSERT INTO brace_regressions " +
                    "(id, error_type, route, message, deploy, first_seen, count) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 1) ON CONFLICT (id) DO NOTHING")) {
                ps.setString(1, id);
                ps.setString(2, type);
                ps.setString(3, route);
                ps.setString(4, message);
                ps.setString(5, deploy);
                ps.setTimestamp(6, seen);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public void bump(String id) {
        inTx(conn -> {
            try (var ps = conn.prepareStatement(
                    "UPDATE brace_regressions SET count = count + 1 WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public List<RegressionTracker.Regression> list() {
        return inTx(conn -> {
            var out = new ArrayList<RegressionTracker.Regression>();
            try (var ps = conn.prepareStatement(
                    "SELECT id, error_type, route, message, first_seen, count, acknowledged_at " +
                    "FROM brace_regressions WHERE deploy = ? ORDER BY first_seen DESC")) {
                ps.setString(1, deploy);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp ack = rs.getTimestamp("acknowledged_at");
                        out.add(new RegressionTracker.Regression(
                            rs.getString("id"),
                            rs.getString("error_type"),
                            rs.getString("route"),
                            rs.getString("message"),
                            rs.getTimestamp("first_seen").toInstant(),
                            (int) rs.getLong("count"),
                            ack == null ? null : ack.toInstant()));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public boolean acknowledge(String id) {
        return inTx(conn -> {
            // Idempotent: sets acknowledged_at if unset; returns true whenever the row exists.
            try (var ps = conn.prepareStatement(
                    "UPDATE brace_regressions SET acknowledged_at = COALESCE(acknowledged_at, now()) " +
                    "WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private <T> T inTx(Database.JdbcFunction<T> work) {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            T result = db.jdbc(work);
            db.commitTransaction();
            return result;
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } finally {
            db.close();
        }
    }
}
