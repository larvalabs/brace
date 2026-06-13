package com.larvalabs.brace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

public class RateLimiter {

    // Global registry of all rate limiters for ops visibility
    private static final List<RateLimiter> ALL = new ArrayList<>();

    // Shared cluster-wide counter backend, installed by Brace.start() when the app runs on
    // Postgres (B4). When set, the window count is enforced across the whole fleet via one atomic
    // DB counter instead of each instance counting only its own traffic (a limit of 100/min across
    // N boxes otherwise allows ~100*N/min). Read at request time, so it applies to limiters created
    // before start(). Null = per-process counting (single-process apps, H2, no database).
    //
    // Static like the ALL registry above, matching the framework's one-app-per-JVM assumption for
    // the rate-limiter subsystem. All instances point their Counters at the same database, so even
    // across the two-instances-in-one-JVM integration tests the shared count is consistent.
    private static volatile Counters sharedCounters;

    /**
     * Maximum raw key length before the key is replaced by its SHA-256 hex digest.
     *
     * <p>User-controlled values (usernames, bearer tokens, IP addresses, custom headers) flow into
     * the key extractor and ultimately into the counter key string stored in {@code brace_counters}
     * (shared backend) or the in-process window map (local backend). Without a cap an attacker can
     * craft arbitrarily long values — a 1 MB header field repeated across requests creates millions
     * of bytes of map-key storage or 1 MB rows in Postgres. Replacing the raw key with its SHA-256
     * hex digest (64 chars, fixed length) eliminates this DoS vector at the cost of a negligible
     * hash computation per request. Two distinct long keys are astronomically unlikely to produce
     * the same digest, so the bucketing is functionally identical to using the raw key.
     */
    static final int MAX_KEY_LENGTH = 64;

    /**
     * Upper bound on the per-instance flush batch (M17). Keeps the local buffer bounded for a
     * pathologically large limit by making flushes more frequent — it never lets a breach go
     * undetected (single-instance breaches are caught by the per-request estimate regardless).
     */
    static final int MAX_FLUSH_BATCH = 10_000;

    /**
     * Default divisor relating the flush batch size to the limit: {@code flushThreshold =
     * maxRequests / batchDivisor} (floored at 1). See {@link #batchDivisor}.
     */
    static final int DEFAULT_BATCH_DIVISOR = 10;

    /**
     * M17 accuracy/DB-load knob (set via {@code Brace.rateLimitBatchDivisor}). An instance flushes
     * its buffered counts to the shared counter every {@code maxRequests / batchDivisor} requests.
     * Higher divisor → smaller batches → tighter fleet accuracy but more DB writes; lower → bigger
     * batches → fewer writes but a looser fleet view (worst-case burst ≈ {@code maxRequests/divisor ×
     * instances} over the limit). At the default 10, a small limit (e.g. login {@code 5/15m}) flushes
     * essentially every request — near-exact, and cheap because the traffic is low — while a large
     * limit (e.g. {@code 1000/min}) batches ~10×. Single-instance abuse is always caught immediately
     * via the per-request estimate + negative cache, independent of this knob.
     */
    private static volatile int batchDivisor = DEFAULT_BATCH_DIVISOR;

    /** After a failed flush, back off DB retries this long while counting per-instance. */
    private static final long FLUSH_RETRY_COOLDOWN_MILLIS = 1_000;

    /** Set the global flush-batch divisor (Brace builder). Clamped to ≥ 1. */
    static void setBatchDivisor(int divisor) {
        batchDivisor = Math.max(1, divisor);
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final Duration windowDuration;
    private final Function<Request, String> keyExtractor;
    private final String label;
    private final LongAdder allowed = new LongAdder();
    private final LongAdder blocked = new LongAdder();

    // --- M17: batched, best-effort shared counting state (used only on the shared-backend path) ---
    // Instead of a DB round trip per request, each instance buffers increments locally and flushes
    // up to flushThreshold() of them in one Counters.incrementBatch call, so DB writes are bounded by
    // requests/flushThreshold regardless of key cardinality. A request's allow/block decision uses
    // (lastKnownGlobal at last flush) + (local pending) — best-effort: the fleet view lags by up to
    // one flush, so the effective limit can overshoot by ~flushThreshold × instances. Documented.
    private final ConcurrentHashMap<String, AtomicLong> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> windowEndByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastKnownGlobal = new ConcurrentHashMap<>();
    private final LongAdder totalPending = new LongAdder();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile long flushCooldownUntilMillis = 0L;
    // Negative cache: a counter key known over the limit for the current window short-circuits to a
    // 429 with no DB call and no buffering (the abusive-client traffic we most want to shed). Value
    // = the window-end millis it stays blocked until; cleared once the window rolls.
    private final ConcurrentHashMap<String, Long> blockedUntil = new ConcurrentHashMap<>();

    private RateLimiter(int maxRequests, Duration windowDuration, Function<Request, String> keyExtractor, String label) {
        this.maxRequests = maxRequests;
        this.windowDuration = windowDuration;
        this.keyExtractor = keyExtractor;
        this.label = label;
        startCleanup();
        synchronized (ALL) { ALL.add(this); }
    }

    public static Middleware.Before perIp(int maxRequests, String duration) {
        var limiter = new RateLimiter(maxRequests, Cache.parseTtl(duration),
            req -> {
                var ip = req.ip();
                return ip != null ? ip : "unknown";
            }, "perIp(" + maxRequests + "/" + duration + ")");
        return limiter::check;
    }

    /**
     * Rate-limit requests by an arbitrary key extracted from the request.
     *
     * <p>If the key extractor returns {@code null} or a blank string, the request is
     * <strong>not</strong> rate-limited and passes through immediately. This is an intentional
     * escape hatch: a {@code null} key means "no identity established yet" (e.g., a GET to
     * the login page before the user has typed their email), and bucketing those requests
     * together with a shared limit would cause site-wide lockout of the guarded endpoint.
     *
     * <p>Example: rate-limit login attempts by email address. GET requests to the login page
     * (which have no email parameter) return {@code null} from the extractor and are exempted;
     * only POST submissions with a concrete email value are counted.
     *
     * <pre>{@code
     * app.before("/login", RateLimiter.perKey(req -> req.formParam("email"), 5, "15m"));
     * }</pre>
     */
    public static Middleware.Before perKey(Function<Request, String> keyExtractor, int maxRequests, String duration) {
        var limiter = new RateLimiter(maxRequests, Cache.parseTtl(duration), keyExtractor,
            "perKey(" + maxRequests + "/" + duration + ")");
        return limiter::check;
    }

    /**
     * Install the shared cluster-wide counter backend. Called by {@code Brace.start()} when the app
     * runs on Postgres; afterwards every rate limiter counts fleet-wide. Idempotent.
     */
    static void useSharedBackend(Counters counters) {
        sharedCounters = counters;
    }

    /** Revert to per-process counting (test teardown). */
    static void disableSharedBackend() {
        sharedCounters = null;
    }

    Result check(Request req) {
        var rawKey = keyExtractor.apply(req);

        // null or blank key → request is not rate-limited (intentional exemption).
        // See perKey() Javadoc for the rationale: a null key means "no identity established
        // yet" (e.g., a GET to the login page before the email field is submitted). Bucketing
        // all such requests together would cause site-wide lockout of the endpoint.
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }

        // Normalize the key before it reaches either backend:
        //   keys longer than MAX_KEY_LENGTH → SHA-256 hex digest (fixed 64 chars) to prevent
        //   DoS via unbounded brace_counters rows / local map entries with huge key strings.
        var key = normalizeKey(rawKey);

        var counters = sharedCounters;
        if (counters != null) {
            try {
                return checkShared(key, counters);
            } catch (RuntimeException e) {
                // Postgres (or any shared-counter) failure: fall back to per-instance counting
                // rather than returning a 500 or silently admitting every request.  A brief DB
                // blip causes per-instance approximation (across a fleet the effective limit
                // becomes limit × N for the duration of the outage), which is far better than
                // turning every rate-limited endpoint into an error.
                Log.warn("rate-limiter: shared-counter DB error, falling back to local counting — " + e.getMessage());
                return checkLocal(key);
            }
        }
        return checkLocal(key);
    }

    /**
     * Normalize a non-null, non-blank raw key value extracted from the request.
     *
     * <ul>
     *   <li>length &gt; {@link #MAX_KEY_LENGTH} → SHA-256 hex digest — caps storage at 64 chars,
     *       preventing DoS via arbitrarily long user-controlled strings (see field javadoc).
     *   <li>null or blank keys are handled by the caller ({@link #check}) before this is called.
     * </ul>
     *
     * Applied before any prefix/window-slot decoration so the cap holds in both the local and
     * shared backends.
     */
    static String normalizeKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            // Callers should have exempted null/blank keys already; treat defensively the same way.
            return null;
        }
        if (rawKey.length() <= MAX_KEY_LENGTH) {
            return rawKey;
        }
        // Replace long keys with their SHA-256 hex digest (exactly 64 chars, fixed).
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE implementation — unreachable.
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /** Per-process fixed-window count (default; single-process apps, H2, no database). */
    private Result checkLocal(String key) {
        var now = Instant.now();
        var window = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.expired(now)) {
                return new Window(now, 1);
            }
            return new Window(existing.start, existing.count + 1);
        });

        if (window.count > maxRequests) {
            blocked.increment();
            long retryAfter = Duration.between(now, window.start.plus(windowDuration)).getSeconds();
            if (retryAfter < 1) retryAfter = 1;
            return tooMany(retryAfter);
        }
        allowed.increment();
        return null;
    }

    /**
     * Cluster-wide fixed-window count backed by a shared atomic counter (B4), counted in
     * best-effort batches (M17). Every instance derives the same window slot from wall-clock —
     * slot = floor(now / window) — so the counter key is identical across the fleet. Like B1's
     * scheduler, this needs clocks synced only within one window (NTP-trivial for any real limit).
     *
     * <p>Rather than one DB round trip per request, an instance buffers increments and flushes them
     * in batches of up to {@code flushThreshold} (= {@code maxRequests}); the per-request decision
     * uses {@code lastKnownGlobal + localPending}. This is deliberately approximate — see the field
     * comments and {@code docs/2026-06-07-rate-limiter-load.md}. An already-blocked key is served
     * from the local negative cache with no DB at all.
     */
    private Result checkShared(String key, Counters counters) {
        long windowMillis = Math.max(1, windowDuration.toMillis());
        long nowMillis = Instant.now().toEpochMilli();
        long slot = nowMillis / windowMillis;
        long windowEndMillis = (slot + 1) * windowMillis;
        // Key must be stable across instances for the same logical limiter: label encodes type +
        // limit + window, key is the per-client value, slot rotates each window. (Two limiters with
        // identical type/limit/window on different paths would share a budget — name them apart if
        // that matters; uncommon, and the failure mode is conservative.)
        String counterKey = "rl:" + label + ":" + key + ":" + slot;

        // Negative cache: a key already over the limit for this window is rejected locally — no DB
        // call, no buffering. Fixed-window counts are monotonic within a slot, so once a key is over
        // it stays over until the slot rolls; the cached block is the same decision the counter
        // would return, at zero DB cost — which is exactly the abusive-client load we want to shed.
        Long blockUntil = blockedUntil.get(counterKey);
        if (blockUntil != null) {
            if (nowMillis < blockUntil) {
                blocked.increment();
                return tooMany(retryAfterSeconds(blockUntil, nowMillis));
            }
            blockedUntil.remove(counterKey, blockUntil); // window rolled — resume counting
        }

        // Buffer this request's increment locally; the batch flush below writes it to the shared
        // counter. windowEndByKey records the key's expiry for the flush and for eviction.
        windowEndByKey.putIfAbsent(counterKey, windowEndMillis);
        pending.computeIfAbsent(counterKey, k -> new AtomicLong()).incrementAndGet();
        totalPending.increment();

        maybeFlush(counters, nowMillis);

        // Estimate after any flush: lastKnownGlobal (fleet count as of the last successful flush,
        // which already includes this instance's earlier flushes) + whatever is still buffered
        // locally for this key. A concurrent flush may have just reset the pending half to ~0 and
        // bumped lastKnownGlobal — either ordering yields a sound best-effort estimate.
        long estimate = lastKnownGlobal.getOrDefault(counterKey, 0L) + currentPending(counterKey);
        if (estimate > maxRequests) {
            blocked.increment();
            blockedUntil.putIfAbsent(counterKey, windowEndMillis); // arm the negative cache
            return tooMany(retryAfterSeconds(windowEndMillis, nowMillis));
        }
        allowed.increment();
        return null;
    }

    private long currentPending(String counterKey) {
        var adder = pending.get(counterKey);
        return adder == null ? 0L : adder.get();
    }

    private static long retryAfterSeconds(long untilMillis, long nowMillis) {
        long retryAfter = (untilMillis - nowMillis + 999) / 1000; // ceil to seconds
        return retryAfter < 1 ? 1 : retryAfter;
    }

    /**
     * Flush the local buffer to the shared counter when it has reached {@code flushThreshold},
     * unless another thread is already flushing or we're backing off after a recent failure. A
     * single thread wins the {@link #flushing} CAS and performs the batch; the rest skip and let
     * their increments ride the next flush. No time-based flush is needed: while fewer than
     * {@code flushThreshold} (= maxRequests) requests are buffered, no key can have breached the
     * limit, so there is nothing a timer would catch that the buffer-full trigger does not.
     */
    /**
     * Flush batch size for this limiter: {@code maxRequests / batchDivisor}, floored at 1 and capped
     * at {@link #MAX_FLUSH_BATCH}. Read live so the {@code Brace.rateLimitBatchDivisor} knob applies
     * even to limiters created before {@code start()}.
     */
    private int flushThreshold() {
        int threshold = maxRequests / batchDivisor;
        if (threshold < 1) {
            threshold = 1;
        }
        return Math.min(threshold, MAX_FLUSH_BATCH);
    }

    private void maybeFlush(Counters counters, long nowMillis) {
        if (totalPending.sum() < flushThreshold()) {
            return;
        }
        if (nowMillis < flushCooldownUntilMillis) {
            return; // backing off DB retries after a failed flush; keep counting per-instance
        }
        if (!flushing.compareAndSet(false, true)) {
            return; // another thread is flushing
        }
        try {
            flush(counters, nowMillis);
            flushCooldownUntilMillis = 0L;
        } catch (RuntimeException e) {
            // Shared-counter (DB) error. The buffered counts were restored (see flush), so the
            // limiter keeps enforcing per-instance — mirroring the old per-request fallback ("a
            // brief DB blip causes per-instance approximation") instead of 500-ing the endpoint.
            // Back off retries so a sustained outage isn't hammered once per request.
            Log.warn("rate-limiter: shared-counter flush failed, counting per-instance until retry — " + e.getMessage());
            flushCooldownUntilMillis = nowMillis + FLUSH_RETRY_COOLDOWN_MILLIS;
        } finally {
            flushing.set(false);
        }
    }

    /**
     * Drain the pending buffer and write it as one batch. Entries whose window has already rolled
     * are dropped (their counter row is irrelevant). On a DB failure every captured delta is added
     * back to the buffer so no count is lost and per-instance enforcement continues; the exception
     * propagates so {@link #maybeFlush} arms the retry cooldown.
     */
    private void flush(Counters counters, long nowMillis) {
        var updates = new ArrayList<Counters.CounterUpdate>();
        long pulled = 0;
        for (var entry : pending.entrySet()) {
            String counterKey = entry.getKey();
            Long windowEnd = windowEndByKey.get(counterKey);
            if (windowEnd == null || windowEnd <= nowMillis) {
                // Window rolled: drop the key entirely (new requests use a new slot's key).
                pulled += entry.getValue().getAndSet(0);
                pending.remove(counterKey);
                windowEndByKey.remove(counterKey);
                lastKnownGlobal.remove(counterKey);
                blockedUntil.remove(counterKey);
                continue;
            }
            long delta = entry.getValue().getAndSet(0);
            if (delta <= 0) {
                continue;
            }
            pulled += delta;
            updates.add(new Counters.CounterUpdate(counterKey, delta, Instant.ofEpochMilli(windowEnd)));
        }
        totalPending.add(-pulled);
        if (updates.isEmpty()) {
            return;
        }
        try {
            counters.incrementBatch(updates).forEach(lastKnownGlobal::put);
        } catch (RuntimeException e) {
            long restored = 0;
            for (var u : updates) {
                pending.computeIfAbsent(u.key(), k -> new AtomicLong()).addAndGet(u.delta());
                restored += u.delta();
            }
            totalPending.add(restored);
            throw e;
        }
    }

    private static Result tooMany(long retryAfterSeconds) {
        return Result.error(429, "Too Many Requests")
            .header("Retry-After", String.valueOf(retryAfterSeconds));
    }

    /**
     * Returns stats for all registered rate limiters, for use by OpsHandler.
     */
    public static List<Map<String, Object>> allStats() {
        var result = new ArrayList<Map<String, Object>>();
        synchronized (ALL) {
            for (var limiter : ALL) {
                var map = new LinkedHashMap<String, Object>();
                map.put("label", limiter.label);
                map.put("allowed", limiter.allowed.sum());
                map.put("blocked", limiter.blocked.sum());
                map.put("activeWindows", limiter.windows.size());
                map.put("maxRequests", limiter.maxRequests);
                map.put("windowSeconds", limiter.windowDuration.getSeconds());
                result.add(map);
            }
        }
        return result;
    }

    private void startCleanup() {
        Thread.ofVirtual().name("rate-limiter-cleanup").start(() -> {
            while (true) {
                try {
                    Thread.sleep(Duration.ofSeconds(60));
                } catch (InterruptedException e) {
                    break;
                }
                evictExpired();
            }
        });
    }

    private void evictExpired() {
        var now = Instant.now();
        windows.entrySet().removeIf(entry -> entry.getValue().expired(now));

        // M17: drop batched-counter state for windows that have rolled. Without traffic a flush
        // never runs, so keys for an expired window would otherwise sit in the maps until the next
        // flush. Negative-cache entries self-clear on access, but a blocked key with no further
        // traffic is reaped here too. Bounded anyway (≤ flushThreshold buffered), this just trims.
        long nowMillis = now.toEpochMilli();
        windowEndByKey.entrySet().removeIf(entry -> {
            if (entry.getValue() > nowMillis) {
                return false;
            }
            String counterKey = entry.getKey();
            var adder = pending.remove(counterKey);
            if (adder != null) {
                totalPending.add(-adder.get());
            }
            lastKnownGlobal.remove(counterKey);
            blockedUntil.remove(counterKey);
            return true;
        });
        blockedUntil.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);

        // Reap expired shared-counter rows (space reclamation; expiry is enforced on read too). The
        // delete is global and idempotent, so running it from each limiter's cleanup is redundant
        // but harmless.
        var counters = sharedCounters;
        if (counters != null) {
            try {
                counters.sweepExpired();
            } catch (RuntimeException ignored) {
                // Transient DB error — next cycle retries; stale rows are corrected on read.
            }
        }
    }

    // Visible for testing
    int windowCount() {
        return windows.size();
    }

    private class Window {
        final Instant start;
        final int count;

        Window(Instant start, int count) {
            this.start = start;
            this.count = count;
        }

        boolean expired(Instant now) {
            return now.isAfter(start.plus(windowDuration));
        }
    }
}
