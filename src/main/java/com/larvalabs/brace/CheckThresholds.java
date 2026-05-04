package com.larvalabs.brace;

import java.util.Map;

public record CheckThresholds(
    int slowRouteMs,
    int heapWarnPercent,
    int heapFailPercent,
    int gcPauseMs,
    double cacheHitRate,
    int logWindowMinutes
) {
    public static final CheckThresholds DEFAULTS = new CheckThresholds(500, 70, 80, 50, 0.5, 30);

    public static CheckThresholds fromConfig(Map<String, String> values) {
        return new CheckThresholds(
            intOr(values, "check.slow_route_ms", DEFAULTS.slowRouteMs),
            intOr(values, "check.heap_warn_percent", DEFAULTS.heapWarnPercent),
            intOr(values, "check.heap_fail_percent", DEFAULTS.heapFailPercent),
            intOr(values, "check.gc_pause_ms", DEFAULTS.gcPauseMs),
            doubleOr(values, "check.cache_hit_rate", DEFAULTS.cacheHitRate),
            intOr(values, "check.log_window_minutes", DEFAULTS.logWindowMinutes)
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
