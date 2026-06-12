package com.larvalabs.brace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Tracks new error kinds (distinct {@code type}+{@code route}) seen under the current deploy — the
 * set behind {@code GET /ops/regressions} and the deploy post-check. Wired as the
 * {@link ErrorStore.RegressionListener}: {@link #onNew} fires when {@code ErrorStore} inserts a
 * brand-new {@code (type, route)}, {@link #onRepeat} bumps the count on recurrence. Each new kind
 * notifies the configured {@link Notifier}s exactly once; a configurable warmup window suppresses
 * cold-boot noise.
 *
 * <p>State lives in a {@link RegressionStore}: per-process by default, or shared across the fleet on
 * Postgres ({@link PostgresRegressionStore}) so the regression set, acknowledgements, and the
 * notify-once claim are consistent across instances behind a load balancer (B6). The regression id
 * is a stable hash of {@code (type, route, deploy)} — identical on every instance — so an id listed
 * on one box acknowledges correctly on another. The baseline is the {@code deploy} marker, not each
 * JVM's start time, so a rolling deploy classifies the same error identically everywhere.
 */
public class RegressionTracker implements ErrorStore.RegressionListener {

    /** A new error kind. {@code id} is a stable hash of (type, route, deploy). */
    public record Regression(String id, String type, String route, String message,
                             Instant firstSeen, int count, Instant acknowledgedAt) {
        public boolean acknowledged() { return acknowledgedAt != null; }
    }

    private final Instant startedAt;
    private final int warmupSeconds;
    private final List<Notifier> notifiers;
    private final String deploy;
    private final RegressionStore store;

    /** In-memory tracker (single-server / no database). */
    public RegressionTracker(Instant startedAt, int warmupSeconds, List<Notifier> notifiers) {
        this(startedAt, warmupSeconds, notifiers, "default", null);
    }

    /**
     * @param deploy    deploy marker anchoring the regression baseline (same on every instance of a
     *                  deploy, different across deploys)
     * @param dbFactory when non-null and Postgres, the regression set is shared fleet-wide; otherwise
     *                  per-process
     */
    public RegressionTracker(Instant startedAt, int warmupSeconds, List<Notifier> notifiers,
                             String deploy, DatabaseFactory dbFactory) {
        this.startedAt = startedAt;
        this.warmupSeconds = Math.max(0, warmupSeconds);
        this.notifiers = notifiers != null ? List.copyOf(notifiers) : List.of();
        this.deploy = deploy != null ? deploy : "default";
        this.store = (dbFactory != null && dbFactory.isPostgres())
            ? new PostgresRegressionStore(dbFactory, this.deploy)
            : new InMemoryRegressionStore();
    }

    /** Stable id for a regression: hash of (type, route, deploy), identical across instances. */
    private String id(String type, String route) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(nullSafe(type).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(nullSafe(route).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(deploy.getBytes(StandardCharsets.UTF_8));
            // 16 bytes (32 hex chars) is ample to avoid collisions for this set.
            byte[] full = md.digest();
            return HexFormat.of().formatHex(full, 0, 16);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    @Override
    public void onNew(String type, String route, String message, Instant firstSeen) {
        // Warmup: ignore brand-new errors in the first warmupSeconds after this process started, so
        // cold-boot noise (e.g. a transient DB-connect failure) isn't flagged as a deploy regression.
        if (firstSeen.isBefore(startedAt.plusSeconds(warmupSeconds))) return;
        add(type, route, message, firstSeen, true);
    }

    @Override
    public void onRepeat(String type, String route, long count) {
        store.bump(id(type, route), count);
    }

    private void add(String type, String route, String message, Instant firstSeen, boolean notify) {
        String id = id(type, route);
        boolean created = store.create(id, type, route, message, firstSeen);
        if (created && notify) {
            Regression r = new Regression(id, type, route, message, firstSeen, 1, null);
            for (Notifier n : notifiers) {
                try {
                    n.notifyRegression(r);
                } catch (Exception ex) {
                    Log.warn("regression notifier failed: " + ex.getMessage());
                }
            }
        }
    }

    /**
     * Seed from the DB on startup so a restart keeps context: unresolved errors first seen at or
     * after this process's start are recorded (without notifying, ignoring warmup). Only meaningful
     * for the in-memory store — the shared Postgres store already persists the set across restarts,
     * so seeding there is unnecessary and skipped.
     */
    public void seed(ErrorStore errorStore) {
        if (errorStore == null || store instanceof PostgresRegressionStore) return;
        for (var err : errorStore.list(null)) {
            Instant firstSeen = (Instant) err.get("firstSeen");
            if (firstSeen != null && !firstSeen.isBefore(startedAt)) {
                add((String) err.get("errorType"), (String) err.get("route"),
                    (String) err.get("message"), firstSeen, false);
            }
        }
    }

    /** Current regressions, newest first. */
    public List<Regression> list() {
        return store.list();
    }

    /** Mark a regression acknowledged so it stops being flagged. Returns false if no such id. */
    public boolean acknowledge(String id) {
        return store.acknowledge(id);
    }
}
