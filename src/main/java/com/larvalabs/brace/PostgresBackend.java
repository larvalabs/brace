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
 * (migrations {@code migration_pg/V8__brace_cache.sql}, {@code V19__brace_cache_used_at.sql}).
 * Cross-server-consistent and durable: every
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

    /**
     * Default cap on page-cache rows (keys starting with {@link Cache#PAGE_KEY_PREFIX}), counted
     * and trimmed on their own (L4 review F5): a client can mint page rows cheaply, and under one
     * shared cap they pushed out the app's own {@code cache.set} entries.
     */
    static final int DEFAULT_MAX_PAGE_ENTRIES = 10_000;

    private final DatabaseFactory factory;
    private final int maxEntries;
    private final int maxPageEntries;
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
        this(factory, maxEntries, Math.min(DEFAULT_MAX_PAGE_ENTRIES, maxEntries));
    }

    PostgresBackend(DatabaseFactory factory, int maxEntries, int maxPageEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1, got " + maxEntries);
        }
        if (maxPageEntries < 0) {
            throw new IllegalArgumentException("maxPageEntries must not be negative, got " + maxPageEntries);
        }
        this.factory = factory;
        this.maxEntries = maxEntries;
        this.maxPageEntries = maxPageEntries;
        this.trimEvery = Math.max(1, Math.min(1_000, Math.min(maxEntries, Math.max(1, maxPageEntries)) / 10));
    }

    int trimEvery() { return trimEvery; }

    /**
     * How stale a page row's {@code used_at} must be before a hit refreshes it (L4 final review).
     * A hot page then costs at most one write per interval, not one per hit. Tests set it to zero.
     */
    private volatile long touchIntervalSeconds = 60;

    void touchInterval(Duration interval) { this.touchIntervalSeconds = interval.toSeconds(); }

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
        if (!Cache.isPageKey(key)) {
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
        // A page hit records use (used_at), so the page cap trims cold pages first; at most once
        // per touchInterval per row, so a hot page is not a write per request (L4 final review).
        long interval = touchIntervalSeconds;
        return run(conn -> {
            byte[] value;
            boolean stale;
            try (var ps = conn.prepareStatement(
                    "SELECT value, used_at < now() - make_interval(secs => ?) FROM brace_cache " +
                    "WHERE cache_key = ? AND (expires_at IS NULL OR expires_at > now())")) {
                ps.setLong(1, interval);
                ps.setString(2, key);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    value = rs.getBytes(1);
                    stale = rs.getBoolean(2);
                }
            }
            if (stale) {
                try (var ps = conn.prepareStatement(
                        "UPDATE brace_cache SET used_at = now() " +
                        "WHERE cache_key = ? AND used_at < now() - make_interval(secs => ?)")) {
                    ps.setString(1, key);
                    ps.setLong(2, interval);
                    ps.executeUpdate();
                }
            }
            return value;
        });
    }

    @Override
    public void setBytes(String key, byte[] value, Duration ttl, String[] tags) {
        OffsetDateTime expires = ttl == null ? null
                : OffsetDateTime.ofInstant(Instant.now().plus(ttl), ZoneOffset.UTC);
        run(conn -> {
            var tagArray = conn.createArrayOf("text", tags == null ? new String[0] : tags);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO brace_cache (cache_key, value, tags, expires_at, used_at) " +
                    "VALUES (?, ?, ?, ?, now()) " +
                    "ON CONFLICT (cache_key) DO UPDATE SET " +
                    "value = EXCLUDED.value, tags = EXCLUDED.tags, expires_at = EXCLUDED.expires_at, " +
                    "used_at = EXCLUDED.used_at")) {
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
     * Hold {@code brace_cache} to its caps (L4, L4 review F5): drop expired rows; hold page-cache
     * rows ({@link Cache#PAGE_KEY_PREFIX}, hashed forms included, since a hashed key keeps its
     * prefix) to {@link #maxPageEntries}; then hold the whole table to {@link #maxEntries},
     * deleting page rows before any other. Page rows go least recently used first ({@code used_at}:
     * last written, or last hit, recorded at most once per {@link #touchIntervalSeconds}; L4 final
     * review, migration {@code V19__brace_cache_used_at.sql}); trimmed soonest-expiring first, rows
     * on a long-TTL route pinned the cap and every short-TTL page was trimmed as soon as it was
     * written. App rows go soonest-expiring first (rows with no expiry last). So the app's own
     * entries are only ever trimmed when they alone exceed {@link #maxEntries}, never because pages
     * grew. Checked every {@link #trimEvery} inserts by this instance and on every
     * {@link #evictExpired()} sweep, so the table can overshoot by about one check interval per
     * instance between checks.
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
            String pagePattern = escapeLike(Cache.PAGE_KEY_PREFIX) + "%";
            long pages = count(conn, " WHERE cache_key LIKE ? ESCAPE '\\'", pagePattern);
            if (pages > maxPageEntries) {
                removed += deleteFirst(conn, "cache_key LIKE ? ESCAPE '\\'", pagePattern, PAGE_TRIM_ORDER,
                    pages - maxPageEntries);
                pages = maxPageEntries;
            }
            long total = count(conn, "", null);
            if (total > maxEntries) {
                long fromPages = Math.min(pages, total - maxEntries);
                if (fromPages > 0) {
                    removed += deleteFirst(conn, "cache_key LIKE ? ESCAPE '\\'", pagePattern, PAGE_TRIM_ORDER, fromPages);
                }
                if (total - fromPages > maxEntries) {
                    removed += deleteFirst(conn, "cache_key NOT LIKE ? ESCAPE '\\'", pagePattern, APP_TRIM_ORDER,
                        total - fromPages - maxEntries);
                }
            }
            return removed;
        });
    }

    /** Page rows: least recently used first (L4 final review). */
    private static final String PAGE_TRIM_ORDER = "used_at ASC";
    /** App rows: soonest-expiring first, rows with no expiry last. */
    private static final String APP_TRIM_ORDER = "expires_at ASC NULLS LAST";

    private static long count(java.sql.Connection conn, String where, String param) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement("SELECT count(*) FROM brace_cache" + where)) {
            if (param != null) ps.setString(1, param);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long deleteFirst(java.sql.Connection conn, String where, String param, String order, long n)
            throws java.sql.SQLException {
        try (var ps = conn.prepareStatement(
                "DELETE FROM brace_cache WHERE cache_key IN (SELECT cache_key FROM brace_cache WHERE " + where
                    + " ORDER BY " + order + " LIMIT ?)")) {
            ps.setString(1, param);
            ps.setLong(2, n);
            return ps.executeUpdate();
        }
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
