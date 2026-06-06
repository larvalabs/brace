package com.larvalabs.brace;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Shared {@link CacheBackend} over the {@code brace_cache} table (migration
 * {@code migration_pg/V8__brace_cache.sql}). Cross-server-consistent and durable: every instance
 * reads and writes one row set, so {@code delete}/{@code clearTag} invalidate the whole fleet and
 * {@code incr} is a single atomic SQL statement (no per-instance drift).
 *
 * <p>Values arrive as bytes ({@link #requiresSerialization()} is true) — the facade serializes.
 * Expiry is enforced on read (the {@code expires_at} predicate), so a missed sweep never serves
 * stale data; {@link #evictExpired()} is space reclamation only. Each op runs in its own short
 * transaction via raw JDBC, mirroring {@code JobPoller}'s Postgres path.
 */
class PostgresBackend implements CacheBackend {

    private final DatabaseFactory factory;

    PostgresBackend(DatabaseFactory factory) {
        this.factory = factory;
    }

    @Override
    public boolean requiresSerialization() {
        return true;
    }

    @Override
    public byte[] getBytes(String key) {
        return tx(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT value FROM brace_cache " +
                    "WHERE cache_key = ? AND (expires_at IS NULL OR expires_at > now())")) {
                ps.setString(1, key);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBytes(1) : null;
                }
            }
        });
    }

    @Override
    public void setBytes(String key, byte[] value, Duration ttl, String[] tags) {
        OffsetDateTime expires = ttl == null ? null
                : OffsetDateTime.ofInstant(Instant.now().plus(ttl), ZoneOffset.UTC);
        tx(conn -> {
            var tagArray = conn.createArrayOf("text", tags == null ? new String[0] : tags);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO brace_cache (cache_key, value, tags, expires_at, counter) " +
                    "VALUES (?, ?, ?, ?, NULL) " +
                    "ON CONFLICT (cache_key) DO UPDATE SET " +
                    "value = EXCLUDED.value, tags = EXCLUDED.tags, " +
                    "expires_at = EXCLUDED.expires_at, counter = NULL")) {
                ps.setString(1, key);
                ps.setBytes(2, value);
                ps.setArray(3, tagArray);
                if (expires == null) {
                    ps.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                } else {
                    ps.setObject(4, expires);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public long incr(String key, long delta) {
        return tx(conn -> {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO brace_cache (cache_key, counter) VALUES (?, ?) " +
                    "ON CONFLICT (cache_key) DO UPDATE SET " +
                    "counter = COALESCE(brace_cache.counter, 0) + ? " +
                    "RETURNING counter")) {
                ps.setString(1, key);
                ps.setLong(2, delta);
                ps.setLong(3, delta);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public void delete(String key) {
        tx(conn -> {
            try (var ps = conn.prepareStatement("DELETE FROM brace_cache WHERE cache_key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void deletePrefix(String prefix) {
        tx(conn -> {
            try (var ps = conn.prepareStatement(
                    "DELETE FROM brace_cache WHERE cache_key LIKE ? ESCAPE '\\'")) {
                ps.setString(1, escapeLike(prefix) + "%");
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public long clearTag(String tag) {
        return tx(conn -> {
            try (var ps = conn.prepareStatement(
                    "DELETE FROM brace_cache WHERE tags @> ARRAY[?]::text[]")) {
                ps.setString(1, tag);
                return (long) ps.executeUpdate();
            }
        });
    }

    @Override
    public void clear() {
        tx(conn -> {
            try (var ps = conn.prepareStatement("TRUNCATE brace_cache")) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public int size() {
        return tx(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT count(*) FROM brace_cache " +
                    "WHERE expires_at IS NULL OR expires_at > now()");
                 var rs = ps.executeQuery()) {
                rs.next();
                return (int) rs.getLong(1);
            }
        });
    }

    @Override
    public long evictExpired() {
        return tx(conn -> {
            try (var ps = conn.prepareStatement(
                    "DELETE FROM brace_cache WHERE expires_at IS NOT NULL AND expires_at < now()")) {
                return (long) ps.executeUpdate();
            }
        });
    }

    /** Run one statement in its own transaction. */
    private <T> T tx(Database.JdbcFunction<T> fn) {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            T result = db.jdbc(fn);
            db.commitTransaction();
            return result;
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } catch (Exception e) {
            db.rollbackTransaction();
            throw new RuntimeException("shared cache backend error", e);
        } finally {
            db.close();
        }
    }

    /** Escape LIKE wildcards in a literal prefix so {@code deletePrefix} matches only the prefix. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
