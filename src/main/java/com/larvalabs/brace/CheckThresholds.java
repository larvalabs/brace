package com.larvalabs.brace;

import java.util.Map;

/**
 * Thresholds for {@code brace check}, from {@code check.*} keys in {@code .brace}. The
 * {@code db*} ones judge the {@code db} block of {@code /ops/status} over its last few complete
 * minutes; a value of 0 turns that signal off.
 *
 * @param dbPoolWaitMinutes    warn when requests waited for a pool connection in at least this
 *                             many of the recent minutes
 * @param dbLockSessions       warn when sessions blocked on a lock average at least this many
 * @param dbMaxLoad            warn when average active sessions exceed this; off by default,
 *                             since only you know the database server's core count (set it to that)
 * @param dbConnectionsPercent warn when connections reach this share of {@code max_connections}
 */
public record CheckThresholds(
    int slowRouteMs,
    int heapWarnPercent,
    int heapFailPercent,
    int gcPauseMs,
    double cacheHitRate,
    int logWindowMinutes,
    int dbPoolWaitMinutes,
    double dbLockSessions,
    double dbMaxLoad,
    int dbConnectionsPercent
) {
    public static final CheckThresholds DEFAULTS = new CheckThresholds(500, 70, 80, 50, 0.5, 30, 1, 1.0, 0, 90);

    /** The pre-0.1.11 thresholds, with the database ones at their defaults. Kept for source compatibility. */
    public CheckThresholds(int slowRouteMs, int heapWarnPercent, int heapFailPercent, int gcPauseMs,
                           double cacheHitRate, int logWindowMinutes) {
        this(slowRouteMs, heapWarnPercent, heapFailPercent, gcPauseMs, cacheHitRate, logWindowMinutes, 1, 1.0, 0, 90);
    }

    public static CheckThresholds fromConfig(Map<String, String> values) {
        return new CheckThresholds(
            intOr(values, "check.slow_route_ms", DEFAULTS.slowRouteMs),
            intOr(values, "check.heap_warn_percent", DEFAULTS.heapWarnPercent),
            intOr(values, "check.heap_fail_percent", DEFAULTS.heapFailPercent),
            intOr(values, "check.gc_pause_ms", DEFAULTS.gcPauseMs),
            doubleOr(values, "check.cache_hit_rate", DEFAULTS.cacheHitRate),
            intOr(values, "check.log_window_minutes", DEFAULTS.logWindowMinutes),
            intOr(values, "check.db_pool_wait_minutes", DEFAULTS.dbPoolWaitMinutes),
            doubleOr(values, "check.db_lock_sessions", DEFAULTS.dbLockSessions),
            doubleOr(values, "check.db_max_load", DEFAULTS.dbMaxLoad),
            intOr(values, "check.db_connections_percent", DEFAULTS.dbConnectionsPercent)
        );
    }

    private static int intOr(Map<String, String> m, String key, int def) {
        String v = m.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for " + key + ": " + v, e);
        }
    }

    private static double doubleOr(Map<String, String> m, String key, double def) {
        String v = m.get(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for " + key + ": " + v, e);
        }
    }
}
