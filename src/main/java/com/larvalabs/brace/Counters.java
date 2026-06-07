package com.larvalabs.brace;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

/**
 * Shared, cluster-wide atomic counters backed by the {@code brace_counters} table — the
 * coordination primitive (F2) behind the multi-server rate limiter (B4). A single logical
 * counter is the same row on every instance, so a windowed count is enforced across the whole
 * fleet rather than N times too loosely.
 *
 * <p><b>Portability.</b> Increment is a {@code SELECT ... FOR UPDATE} followed by
 * {@code UPDATE}/{@code INSERT} inside one transaction — the same row-lock pattern the recurring
 * scheduler uses (see {@code JobScheduler.claimRun}). This runs identically on H2 (the
 * {@code mvn test} path) and Postgres; we deliberately avoid {@code ON CONFLICT ... RETURNING},
 * which the shared <em>cache</em> backend can use only because it is Postgres-only.
 *
 * <p><b>Windows &amp; expiry.</b> Callers encode the window in the key (e.g.
 * {@code ratelimit:<scope>:<slot>}) and pass an {@code expiresAt}; successive windows are distinct
 * rows reaped by {@link #sweepExpired()}. If an existing row is already past its expiry when read,
 * it is treated as a fresh start (reset to {@code delta}), so a reused key never continues a stale
 * count even before the sweep runs.
 */
final class Counters {

    private final DatabaseFactory dbFactory;

    Counters(DatabaseFactory dbFactory) {
        this.dbFactory = dbFactory;
    }

    /**
     * Atomically add {@code delta} to the counter and return the new value. If the row is absent
     * or expired, the count starts from zero (so the result is {@code delta}) and its expiry is
     * (re)set to {@code expiresAt}.
     *
     * @param key       counter identity (caller encodes any window in the key)
     * @param delta     amount to add (typically 1)
     * @param expiresAt when this counter may be reaped; null = never expires
     * @return the counter value after applying {@code delta}
     */
    long incrementAndGet(String key, long delta, Instant expiresAt) {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            Instant now = Instant.now();
            Timestamp exp = expiresAt == null ? null : Timestamp.from(expiresAt);
            Timestamp nowTs = Timestamp.from(now);
            long result = db.jdbc(conn ->
                dbFactory.isPostgres()
                    ? incrementPostgres(conn, key, delta, exp, nowTs)
                    : incrementPortable(conn, key, delta, exp, now));
            db.commitTransaction();
            return result;
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } catch (Exception e) {
            db.rollbackTransaction();
            throw new RuntimeException(e);
        } finally {
            db.close();
        }
    }

    /**
     * Postgres fast path: a single atomic upsert — one round trip, lock held only for the statement,
     * HOT-update-friendly (the PK never changes). The expiry reset folds into the {@code CASE} so an
     * already-expired counter starts over rather than continuing a stale count. This is the hot path
     * for the rate limiter on a busy server; see {@code docs/2026-06-07-rate-limiter-load.md}.
     */
    private static long incrementPostgres(java.sql.Connection conn, String key, long delta,
                                          Timestamp exp, Timestamp now) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO brace_counters AS c (counter_key, n, expires_at) VALUES (?, ?, ?) " +
                "ON CONFLICT (counter_key) DO UPDATE SET " +
                "n = CASE WHEN c.expires_at IS NOT NULL AND c.expires_at <= ? THEN ? ELSE c.n + ? END, " +
                "expires_at = EXCLUDED.expires_at " +
                "RETURNING n")) {
            ps.setString(1, key);
            ps.setLong(2, delta);
            setNullableTimestamp(ps, 3, exp);
            ps.setTimestamp(4, now);   // expiry comparison uses the same Timestamp basis as storage
            ps.setLong(5, delta);      // reset value when the prior window had expired
            ps.setLong(6, delta);      // otherwise increment the live count
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Portable path (H2 and any non-Postgres): SELECT ... FOR UPDATE then UPDATE/INSERT. */
    private static long incrementPortable(java.sql.Connection conn, String key, long delta,
                                          Timestamp exp, Instant now) throws java.sql.SQLException {
        long current = 0;
        boolean exists = false;
        try (var ps = conn.prepareStatement(
                "SELECT n, expires_at FROM brace_counters WHERE counter_key = ? FOR UPDATE")) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                    Timestamp rowExp = rs.getTimestamp(2);
                    // An already-expired row starts over rather than continuing a stale count.
                    boolean expired = rowExp != null && !rowExp.toInstant().isAfter(now);
                    current = expired ? 0 : rs.getLong(1);
                }
            }
        }
        long next = current + delta;
        if (exists) {
            try (var ps = conn.prepareStatement(
                    "UPDATE brace_counters SET n = ?, expires_at = ? WHERE counter_key = ?")) {
                ps.setLong(1, next);
                setNullableTimestamp(ps, 2, exp);
                ps.setString(3, key);
                ps.executeUpdate();
            }
        } else {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO brace_counters (counter_key, n, expires_at) VALUES (?, ?, ?)")) {
                ps.setString(1, key);
                ps.setLong(2, next);
                setNullableTimestamp(ps, 3, exp);
                ps.executeUpdate();
            }
        }
        return next;
    }

    /**
     * Current counter value, or 0 if the key is absent or expired. A read-only convenience; the
     * authoritative path is {@link #incrementAndGet}.
     */
    long get(String key) {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            Timestamp now = Timestamp.from(Instant.now());
            long value = db.jdbc(conn -> {
                try (var ps = conn.prepareStatement(
                        "SELECT n FROM brace_counters WHERE counter_key = ? " +
                        "AND (expires_at IS NULL OR expires_at > ?)")) {
                    ps.setString(1, key);
                    ps.setTimestamp(2, now);
                    try (var rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            });
            db.commitTransaction();
            return value;
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } catch (Exception e) {
            db.rollbackTransaction();
            throw new RuntimeException(e);
        } finally {
            db.close();
        }
    }

    /**
     * Delete expired counter rows (space reclamation; expiry is already enforced on read/increment).
     * @return number of rows removed
     */
    long sweepExpired() {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            Timestamp now = Timestamp.from(Instant.now());
            long removed = db.jdbc(conn -> {
                try (var ps = conn.prepareStatement(
                        "DELETE FROM brace_counters WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
                    ps.setTimestamp(1, now);
                    return (long) ps.executeUpdate();
                }
            });
            db.commitTransaction();
            return removed;
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } catch (Exception e) {
            db.rollbackTransaction();
            throw new RuntimeException(e);
        } finally {
            db.close();
        }
    }

    private static void setNullableTimestamp(java.sql.PreparedStatement ps, int idx, Timestamp ts)
            throws java.sql.SQLException {
        if (ts == null) {
            ps.setNull(idx, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(idx, ts);
        }
    }
}
