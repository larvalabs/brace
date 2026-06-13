package com.larvalabs.brace;

import java.time.Instant;
import java.util.List;

/**
 * Storage behind {@link RegressionTracker}. The tracker owns policy (warmup, deploy marker, id
 * derivation, notification); a store owns only where the regression set lives.
 *
 * <p>Two implementations ship: {@link InMemoryRegressionStore} (default; per-process, single-server)
 * and {@link PostgresRegressionStore} (shared across the fleet, selected automatically on Postgres
 * so {@code /ops/regressions}, acknowledge, and notify-once are consistent across instances — B6).
 */
interface RegressionStore {

    /**
     * Record a newly-seen regression. Returns {@code true} iff this call created it — so exactly one
     * caller (one instance) gets {@code true} for a given id and notifies. An idempotent claim.
     */
    boolean create(String id, String type, String route, String message, Instant firstSeen);

    /** Add {@code count} occurrences to an existing regression (no-op if absent). */
    void bump(String id, long count);

    /** Current regressions, newest first. */
    List<RegressionTracker.Regression> list();

    /** Mark acknowledged so it stops being flagged. Returns false if no such id. */
    boolean acknowledge(String id);
}
