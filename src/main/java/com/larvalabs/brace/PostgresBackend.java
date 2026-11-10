package com.larvalabs.brace;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared {@link CacheBackend} over the {@code brace_cache} / {@code brace_cache_counters} tables
 * (migration {@code migration_pg/V8__brace_cache.sql}). Cross-server-consistent and durable: every
 * instance reads and writes one row set, so {@code delete}/{@code clearTag} invalidate the whole
 * fleet and {@code incr} is a single atomic SQL statement (no per-instance drift).
 *
 * <p>Values arrive as bytes ({@link #requiresSerialization()} is true) — the facade serializes.
 * Values and counters live in separate tables (mirroring the in-memory backend's two maps), so a
 * key used as both never collides. Value expiry is enforced on read (the {@code expires_at}
 * predicate), so a missed sweep never serves stale data; {@link #evictExpired()} is space
 * reclamation only. Each op runs in its own short transaction via {@code DatabaseFactory.withSession}.
 */
class PostgresBackend implements CacheBackend {

    private static final long SIZE_CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);

    /**
     * Default cap on {@code brace_cache} value rows (L4). The in-memory backend has always capped
     * its entries; this table had no bound, so anything that mints keys (a page-cached route with
     * a free-form path parameter) grew it until the disk filled.
     */
    static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final DatabaseFactory factory;
    private final int maxEntries;
    /** Inserts by this instance between two cap checks: a tenth of the cap, at most 1,000. */
    private final int trimEvery;
    private final AtomicInteger insertsSinceTrim = new AtomicInteger();
    private final AtomicBoolean trimming = new AtomicBoolean();

    // size() is a count(*) the ops dashboard polls every few seconds; cache it briefly so repeated
    // dashboard renders don't each scan the table.
    private volatile int cachedSize = -1;
    private volatile long cachedSizeAt;

    PostgresBackend(DatabaseFactory factory) {
        this(factory, DEFAULT_MAX_ENTRIES);
    }

    PostgresBackend(DatabaseFactory factory, int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1, got " + maxEntries);
        }
        this.factory = factory;
        this.maxEntries = maxEntries;
        this.trimEvery = Math.max(1, Math.min(1_000, maxEntries / 10));
    }

    @Override
    public boolean requiresSerialization() {
        return true;
    }

    @Override
    public boolean shared() {
        return true;
    }

    @Override
    public byte[] getBytes(String key) {
        return run(conn -> {
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
        run(conn -> {
            var tagArray = conn.createArrayOf("text", tags == null ? new String[0] : tags);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO brace_cache (cache_key, value, tags, expires_at) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (cache_key) DO UPDATE SET " +
                    "value = EXCLUDED.value, tags = EXCLUDED.tags, expires_at = EXCLUDED.expires_at")) {
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
        if (insertsSinceTrim.incrementAndGet() >= trimEvery && trimming.compareAndSet(false, true)) {
            try {
                insertsSinceTrim.set(0);
                trimToCap();
            } catch (RuntimeException e) {
                // The value is stored; a failed trim is retried on the next check or sweep.
                Log.debug("cache: trimming brace_cache to its cap failed: " + e.getMessage());
            } finally {
                trimming.set(false);
            }
        }
    }

    /**
     * Hold {@code brace_cache} to {@link #maxEntries} value rows (L4): drop expired rows, then, if
     * still over, the rows that expire soonest (rows with no expiry last). Checked every
     * {@link #trimEvery} inserts by this instance and on every {@link #evictExpired()} sweep, so
     * the table can overshoot by about one check interval per instance between checks. Uses the
     * existing {@code expires_at} index; no schema change.
     *
     * @return rows removed
     */
    long trimToCap() {
        return run(conn -> {
            long removed;
            try (var ps = conn.prepareStatement(
                    "DELETE FROM brace_cache WHERE expires_at IS NOT NULL AND expires_at < now()")) {
                removed = ps.executeUpdate();
            }
            long count;
            try (var ps = conn.prepareStatement("SELECT count(*) FROM brace_cache");
                 var rs = ps.executeQuery()) {
                rs.next();
                count = rs.getLong(1);
            }
            if (count > maxEntries) {
                try (var ps = conn.prepareStatement(
                        "DELETE FROM brace_cache WHERE cache_key IN (" +
                        "SELECT cache_key FROM brace_cache ORDER BY expires_at ASC NULLS LAST LIMIT ?)")) {
                    ps.setLong(1, count - maxEntries);
                    removed += ps.executeUpdate();
                }
            }
            return removed;
        });
    }

    @Override
    public long incr(String key, long delta) {
        return run(conn -> {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO brace_cache_counters (cache_key, counter) VALUES (?, ?) " +
                    "ON CONFLICT (cache_key) DO UPDATE SET " +
                    "counter = brace_cache_counters.counter + ? " +
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
        run(conn -> {
            try (var ps = conn.prepareStatement("DELETE FROM brace_cache WHERE cache_key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void deletePrefix(String prefix) {
        run(conn -> {
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
        return run(conn -> {
            try (var ps = conn.prepareStatement(
                    "DELETE FROM brace_cache WHERE tags @> ARRAY[?]::text[]")) {
                ps.setString(1, tag);
                return (long) ps.executeUpdate();
            }
        });
    }

    @Override
    public void clear() {
        run(conn -> {
            try (var ps = conn.prepareStatement("TRUNCATE brace_cache, brace_cache_counters")) {
                ps.executeUpdate();
            }
            return null;
        });
        cachedSize = -1;
    }

    @Override
    public int size() {
        long now = System.nanoTime();
        int snapshot = cachedSize;
        if (snapshot >= 0 && now - cachedSizeAt < SIZE_CACHE_NANOS) {
            return snapshot;
        }
        int n = run(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT count(*) FROM brace_cache " +
                    "WHERE expires_at IS NULL OR expires_at > now()");
                 var rs = ps.executeQuery()) {
                rs.next();
                return (int) rs.getLong(1);
            }
        });
        cachedSize = n;
        cachedSizeAt = now;
        return n;
    }

    @Override
    public int counterCount() {
        return run(conn -> {
            try (var ps = conn.prepareStatement("SELECT count(*) FROM brace_cache_counters");
                 var rs = ps.executeQuery()) {
                rs.next();
                return (int) rs.getLong(1);
            }
        });
    }

    @Override
    public long evictExpired() {
        // Expired rows, then the cap (L4): the sweep is the backstop for instances that insert
        // fewer than trimEvery rows between sweeps.
        return trimToCap();
    }

    /** Run one statement in its own transaction, reusing the framework's session/tx plumbing. */
    private <T> T run(Database.JdbcFunction<T> fn) {
        // Block lambda (not expression) to bind the withSession(Function) overload, not Consumer.
        return factory.withSession(db -> { return db.jdbc(fn); });
    }

    /** Escape LIKE wildcards in a literal prefix so {@code deletePrefix} matches only the prefix. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
