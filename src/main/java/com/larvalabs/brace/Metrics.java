package com.larvalabs.brace;

import java.util.function.Supplier;

/**
 * Custom metrics from anywhere, without passing the app around (same static-facade shape as
 * {@link Log} and {@link Url}): {@code Metrics.counter("talks.created")}.
 * <p>
 * Calls record into the {@link Stats} of the most recently constructed {@link Brace} app, the
 * same instance {@code app.stats()} returns, so they show up in {@code /ops/status} and the
 * dashboard. Metrics recorded before any app exists (a service built ahead of
 * {@code Brace.app()} in {@code main()}, or a unit test with no app) go to a process-level
 * registry that the first app adopts, so a gauge registered early still reaches the dashboard.
 * <p>
 * With several apps in one JVM (tests), these calls follow the most recently constructed one;
 * use {@code app.stats()} to record against or read from a specific app.
 */
public final class Metrics {

    private static final Object lock = new Object();
    // Collects metrics until the first app adopts it; null afterwards.
    private static Stats bootstrap = new Stats();
    private static volatile Stats current = bootstrap;

    private Metrics() {}

    /**
     * Called by each {@link Brace} at construction: returns the Stats the app should own and
     * makes it the target of the static calls. The first app takes over the bootstrap registry
     * so nothing recorded before it is lost; later apps get a fresh one.
     */
    static Stats register() {
        synchronized (lock) {
            Stats stats = bootstrap != null ? bootstrap : new Stats();
            bootstrap = null;
            current = stats;
            return stats;
        }
    }

    /** Tests only: back to the no-app state, with a fresh bootstrap registry. */
    static void reset() {
        synchronized (lock) {
            bootstrap = new Stats();
            current = bootstrap;
        }
    }

    /** Increment a counter by 1. */
    public static void counter(String name) {
        current.counter(name);
    }

    /** Increment a counter by {@code amount}. */
    public static void counter(String name, long amount) {
        current.counter(name, amount);
    }

    /** Register a gauge, sampled each minute. Re-registering a name replaces its supplier. */
    public static void gauge(String name, Supplier<Long> supplier) {
        current.gauge(name, supplier);
    }

    /** Record one timing; the dashboard shows count, average and max per minute. */
    public static void timer(String name, long durationMs) {
        current.timer(name, durationMs);
    }
}
