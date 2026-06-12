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

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final Duration windowDuration;
    private final Function<Request, String> keyExtractor;
    private final String label;
    private final LongAdder allowed = new LongAdder();
    private final LongAdder blocked = new LongAdder();

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
     * Cluster-wide fixed-window count backed by a shared atomic counter (B4). Every instance
     * derives the same window slot from wall-clock — slot = floor(now / window) — so the counter
     * key is identical across the fleet and the limit is enforced once, globally. Like B1's
     * scheduler, this needs clocks synced only within one window (NTP-trivial for any real limit).
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
        long count = counters.incrementAndGet(counterKey, 1, Instant.ofEpochMilli(windowEndMillis));

        if (count > maxRequests) {
            blocked.increment();
            long retryAfter = (windowEndMillis - nowMillis + 999) / 1000; // ceil to seconds
            if (retryAfter < 1) retryAfter = 1;
            return tooMany(retryAfter);
        }
        allowed.increment();
        return null;
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
