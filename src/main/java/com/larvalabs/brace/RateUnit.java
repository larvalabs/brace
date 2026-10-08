package com.larvalabs.brace;

/**
 * The unit the ops dashboard and {@code brace status} show request rates in: per second once
 * traffic averages at least one request a second, per minute below that, where per-second
 * figures would be small decimals. Chosen from the window's average, not the last minute, so a
 * site near the line doesn't flip units from one refresh to the next. Rates are still counted
 * per minute; a per-second figure is a minute's average. The JSON keeps per-minute fields.
 */
enum RateUnit {
    SECOND("Sec", "/s", 60), MINUTE("Min", "/min", 1);

    final String label;
    final String suffix;
    private final int minutesDivisor;

    RateUnit(String label, String suffix, int minutesDivisor) {
        this.label = label;
        this.suffix = suffix;
        this.minutesDivisor = minutesDivisor;
    }

    static RateUnit forAvgPerMinute(double avgPerMinute) {
        return avgPerMinute >= 60 ? SECOND : MINUTE;
    }

    double fromPerMinute(double perMinute) {
        return perMinute / minutesDivisor;
    }

    /** A rate for display: whole numbers from 10 up, otherwise enough decimals to not read as 0. */
    static String format(double v) {
        if (v == 0) return "0";
        if (v < 0.01) return "<0.01";
        if (v < 1) return String.format("%.2f", v);
        if (v < 10) return String.format("%.1f", v);
        return String.format("%,.0f", v);
    }
}
