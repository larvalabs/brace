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

    /**
     * Longest one flush may write to the database (L6 review F4). The flush runs on the request
     * thread that crossed the batch threshold, so a slow or failing batch held that request; past
     * the budget the deltas not yet written are put back for the next flush.
     */
    static final long DEFAULT_FLUSH_TIME_BUDGET_MILLIS = 2_000;
    static volatile long flushTimeBudgetMillis = DEFAULT_FLUSH_TIME_BUDGET_MILLIS;

    /**
     * Rows per counter statement (L6 review F4). Three parameters a row; Postgres refuses a
     * statement with more than 65,535, so one statement for a large buffer failed outright.
     */
    static final int FLUSH_CHUNK_ROWS = Counters.MAX_BATCH_ROWS;

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
    private final ConcurrentHashMap<String, SharedKey> windowEndByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastKnownGlobal = new ConcurrentHashMap<>();
    private final LongAdder totalPending = new LongAdder();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile long flushCooldownUntilMillis = 0L;
    // Negative cache: a counter key known over the limit for the current window short-circuits to a
    // 429 with no DB call and no buffering (the abusive-client traffic we most want to shed). Value
    // = the window-end millis it stays blocked until; cleared once the window rolls.
    private final ConcurrentHashMap<String, Long> blockedUntil = new ConcurrentHashMap<>();

    /**
     * Default cap on the distinct keys one limiter tracks at a time (L6). Each key holds map entries
     * (and, on Postgres, a {@code brace_counters} row) for its whole window, so without a cap a
     * client that can mint keys (a rotating source address, a made-up username) grows the state
     * without bound. What happens at the cap is described on {@link #admit}. Change per limiter with
     * {@code maxKeys(int)}.
     */
    static final int DEFAULT_MAX_KEYS = 100_000;

    /**
     * Last-resort bucket for new keys once the table is full of keys at their limit and the per-IP
     * overflow buckets are used up too. Like {@link #MISSING_KEY} it starts with a space, which no
     * normalized key can, so a client cannot address it directly; the per-IP overflow buckets are
     * this followed by {@code ":"} and the client's {@link #ipBucket}.
     */
    static final String OVERFLOW_KEY = " (overflow)";

    /** Minimum gap between two "key cap reached" warnings from one limiter. */
    private static final long OVERFLOW_WARN_INTERVAL_MILLIS = 60_000;

    /** Keys examined per eviction attempt (L6 review): bounded work, an approximate LRU. */
    static final int EVICTION_SAMPLE = 16;

    private volatile int maxKeys = DEFAULT_MAX_KEYS;
    private final AtomicLong lastOverflowWarnMillis = new AtomicLong();

    /** Tracked keys (local key or shared counter key) of the per-IP overflow buckets, to bound them. */
    private final java.util.Set<String> overflowKeys = ConcurrentHashMap.newKeySet();
    /** Serializes eviction only: taken on the full-table, new-key path, never otherwise. */
    private final java.util.concurrent.locks.ReentrantLock evictLock = new java.util.concurrent.locks.ReentrantLock();
    /** Where the eviction sampler resumes in each table, so successive attempts look at new keys. */
    private java.util.Iterator<String> localCursor;
    private java.util.Iterator<String> sharedCursor;

    /** A key the shared path tracks: its window end, when it was last used, and whether it is an overflow bucket. */
    private static final class SharedKey {
        final long windowEnd;
        final boolean overflow;
        volatile long lastTouch;

        SharedKey(long windowEnd, boolean overflow, long now) {
            this.windowEnd = windowEnd;
            this.overflow = overflow;
            this.lastTouch = now;
        }
    }

    private RateLimiter(int maxRequests, Duration windowDuration, Function<Request, String> keyExtractor, String label) {
        this.maxRequests = maxRequests;
        this.windowDuration = windowDuration;
        this.keyExtractor = keyExtractor;
        this.label = label;
        startCleanup();
        synchronized (ALL) { ALL.add(this); }
    }

    /**
     * Rate-limit requests by client address, {@code req.ip()} (configure
     * {@code app.trustedProxies(...)} behind a proxy). An IPv6 client is keyed on its /64, the
     * smallest block a site is normally assigned, so rotating through the addresses of one /64
     * does not mint fresh budgets (L6); an IPv4 client, including one seen as an IPv4-mapped IPv6
     * address, is keyed on its full address.
     */
    public static PerIpCheck perIp(int maxRequests, String duration) {
        var limiter = new RateLimiter(maxRequests, Cache.parseTtl(duration),
            req -> {
                var ip = req.ip();
                return ip != null ? ipBucket(ip) : "unknown";
            }, "perIp(" + maxRequests + "/" + duration + ")");
        return new PerIpCheck(limiter);
    }

    /**
     * The per-IP bucket for an address from {@code req.ip()}: an IPv6 address's /64 network
     * ({@code 2001:db8:1:2::/64}), an IPv4 or IPv4-mapped address as dotted IPv4. Parsed without
     * DNS; a value that is not an address literal is returned unchanged.
     */
    static String ipBucket(String ip) {
        var addr = TrustedProxies.parseLiteral(ip);
        if (addr == null) {
            return ip;
        }
        if (addr instanceof java.net.Inet4Address) {
            return addr.getHostAddress();
        }
        byte[] bytes = addr.getAddress();
        var sb = new StringBuilder(24);
        for (int i = 0; i < 8; i += 2) {
            sb.append(Integer.toHexString(((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF))).append(':');
        }
        return sb.append(":/64").toString();
    }

    /**
     * The middleware returned by {@link #perIp}. A named type rather than a lambda so
     * {@code Brace.start()} can spot an IP-keyed limiter registered without
     * {@code app.trustedProxies(...)} and warn about proxy-IP bucketing.
     */
    public static final class PerIpCheck implements Middleware.Before {
        private final RateLimiter limiter;

        private PerIpCheck(RateLimiter limiter) {
            this.limiter = limiter;
        }

        /**
         * Track at most {@code maxKeys} distinct clients at a time (default
         * {@value RateLimiter#DEFAULT_MAX_KEYS}). At the cap a new client takes the place of
         * one under its limit; clients at their limit are kept until their windows end (see
         * {@link RateLimiter#perKey} for the details).
         *
         * @return this middleware, for registration
         */
        public PerIpCheck maxKeys(int maxKeys) {
            limiter.setMaxKeys(maxKeys);
            return this;
        }

        @Override
        public Result handle(Request req) {
            return limiter.check(req);
        }
    }

    private void setMaxKeys(int maxKeys) {
        if (maxKeys < 1) {
            throw new IllegalArgumentException("maxKeys must be at least 1, got " + maxKeys);
        }
        this.maxKeys = maxKeys;
    }

    /**
     * The key a request is counted under (L6, review decision B). {@code key} itself while the
     * table has room or already tracks it. At the cap ({@link #maxKeys} keys):
     * <ol>
     *   <li>a tracked key that is <em>under</em> its limit is forgotten to make room, the least
     *       recently used of a bounded sample ({@link #EVICTION_SAMPLE}; an expired key goes
     *       first). Forgetting it forfeits fewer than {@code maxRequests} counted requests for
     *       that key, an accepted bypass; a key at or over its limit is never forgotten before its
     *       window ends, so a flood cannot release a blocked client;</li>
     *   <li>when the sample holds only such pinned keys, the request is counted in a per-client
     *       overflow bucket, {@link #OVERFLOW_KEY}{@code + ":" + ipBucket(req.ip())}, so one
     *       flooder does not lock out everyone else; at most {@code max(1, maxKeys / 10)} of
     *       these are tracked;</li>
     *   <li>past that, in the single {@link #OVERFLOW_KEY} bucket.</li>
     * </ol>
     * The size check is not atomic with the insert that follows, so under concurrency the cap can
     * be overshot by a few entries; it bounds growth, not an exact count. Eviction takes a
     * per-limiter lock and examines a fixed number of keys, so no request scans the table.
     *
     * @param trackedKey maps a bucket key to the key the table stores it under
     */
    private String admit(String key, Request req, java.util.Map<String, ?> tracked, boolean shared,
                         Function<String, String> trackedKey, long nowMillis) {
        if (tracked.size() < maxKeys || tracked.containsKey(trackedKey.apply(key))) {
            return key;
        }
        warnKeyCap();
        if (evictOne(shared, nowMillis)) {
            return key;
        }
        var ip = req == null ? null : req.ip();
        String perIp = OVERFLOW_KEY + ":" + (ip != null ? ipBucket(ip) : "unknown");
        String tracking = trackedKey.apply(perIp);
        if (overflowKeys.contains(tracking) || overflowKeys.size() < Math.max(1, maxKeys / 10)) {
            overflowKeys.add(tracking);
            return perIp;
        }
        return OVERFLOW_KEY;
    }

    private void warnKeyCap() {
        long now = System.currentTimeMillis();
        long last = lastOverflowWarnMillis.get();
        if (now - last >= OVERFLOW_WARN_INTERVAL_MILLIS && lastOverflowWarnMillis.compareAndSet(last, now)) {
            Log.warn("rate-limiter: " + label + " is tracking its maximum of " + maxKeys
                + " keys; new keys replace keys under their limit (keys at their limit are kept until "
                + "their windows end), and fall back to a per-client overflow bucket when none can be replaced");
        }
    }

    private static final long EVICT_PINNED = Long.MAX_VALUE;
    private static final long EVICT_EXPIRED = Long.MIN_VALUE;

    /**
     * Forget one tracked key that is under its limit (or expired), chosen from the next
     * {@link #EVICTION_SAMPLE} keys of a cursor that walks the table across calls: the least
     * recently used of them. Returns false when every sampled key is pinned.
     */
    private boolean evictOne(boolean shared, long nowMillis) {
        evictLock.lock();
        try {
            java.util.Iterator<String> cursor = shared ? sharedCursor : localCursor;
            String best = null;
            long bestTouch = EVICT_PINNED;
            boolean restarted = false;
            for (int examined = 0; examined < EVICTION_SAMPLE; ) {
                if (cursor == null || !cursor.hasNext()) {
                    if (restarted) break;
                    restarted = true;
                    cursor = (shared ? windowEndByKey : windows).keySet().iterator();
                    continue;
                }
                String candidate = cursor.next();
                examined++;
                long touch = shared ? sharedEvictionRank(candidate, nowMillis) : localEvictionRank(candidate, nowMillis);
                if (touch == EVICT_PINNED) continue;
                if (touch < bestTouch) {
                    best = candidate;
                    bestTouch = touch;
                    if (touch == EVICT_EXPIRED) break;
                }
            }
            if (shared) sharedCursor = cursor; else localCursor = cursor;
            return best != null && (shared ? evictShared(best, nowMillis) : evictLocal(best, nowMillis));
        } finally {
            evictLock.unlock();
        }
    }

    /** Last-use time of an evictable local key, {@link #EVICT_EXPIRED}, or {@link #EVICT_PINNED}. */
    private long localEvictionRank(String key, long nowMillis) {
        var w = windows.get(key);
        if (w == null || key.startsWith(OVERFLOW_KEY)) return EVICT_PINNED;
        if (w.expired(nowMillis)) return EVICT_EXPIRED;
        return w.count < maxRequests ? w.lastTouch : EVICT_PINNED;
    }

    private boolean evictLocal(String key, long nowMillis) {
        boolean[] removed = {false};
        windows.computeIfPresent(key, (k, w) -> {
            if (w.expired(nowMillis) || w.count < maxRequests) {
                removed[0] = true;
                return null;
            }
            return w;
        });
        return removed[0];
    }

    /** As {@link #localEvictionRank}, for a shared counter key. */
    private long sharedEvictionRank(String counterKey, long nowMillis) {
        var tracked = windowEndByKey.get(counterKey);
        if (tracked == null || tracked.overflow) return EVICT_PINNED;
        if (tracked.windowEnd <= nowMillis) return EVICT_EXPIRED;
        if (blockedUntil.containsKey(counterKey)) return EVICT_PINNED;
        long estimate = lastKnownGlobal.getOrDefault(counterKey, 0L) + currentPending(counterKey);
        return estimate < maxRequests ? tracked.lastTouch : EVICT_PINNED;
    }

    /**
     * Forget a shared key. Its buffered (unflushed) count is dropped with it: fewer than
     * {@code maxRequests}, since only an under-limit key is evicted.
     */
    private boolean evictShared(String counterKey, long nowMillis) {
        var tracked = windowEndByKey.get(counterKey);
        if (tracked == null || sharedEvictionRank(counterKey, nowMillis) == EVICT_PINNED
                || !windowEndByKey.remove(counterKey, tracked)) {
            return false;
        }
        // A key this instance has flushed has a brace_counters row; delete it at the next flush
        // so a flood of fresh keys leaves no row per key behind for a whole window. Bounded: past
        // maxKeys queued, the row is left to expire with its window.
        if (lastKnownGlobal.containsKey(counterKey) && evictedRowCount.get() < maxKeys) {
            evictedRows.add(counterKey);
            evictedRowCount.incrementAndGet();
        }
        forgetShared(counterKey);
        return true;
    }

    /** Counter keys evicted after a flush, whose rows the next flush deletes (see {@link #evictShared}). */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> evictedRows = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger evictedRowCount = new java.util.concurrent.atomic.AtomicInteger();

    /** Delete the rows of evicted keys that were not tracked again since; best effort. */
    private void deleteEvictedRows(Counters counters) {
        var keys = new ArrayList<String>();
        for (String k; keys.size() < MAX_FLUSH_BATCH && (k = evictedRows.poll()) != null; ) {
            evictedRowCount.decrementAndGet();
            if (!windowEndByKey.containsKey(k)) keys.add(k);
        }
        if (keys.isEmpty()) return;
        try {
            counters.deleteKeys(keys);
        } catch (RuntimeException e) {
            Log.debug("rate-limiter: deleting the rows of evicted keys failed; they expire with their windows: "
                + e.getMessage());
        }
    }

    /** Drop every per-key map entry of a shared counter key except its {@link #windowEndByKey} entry. */
    private void forgetShared(String counterKey) {
        var adder = pending.remove(counterKey);
        if (adder != null) {
            totalPending.add(-adder.get());
        }
        lastKnownGlobal.remove(counterKey);
        blockedUntil.remove(counterKey);
        overflowKeys.remove(counterKey);
    }

    /**
     * Rate-limit requests by an arbitrary key extracted from the request.
     *
     * <p>The key is stripped of surrounding whitespace; it is <em>not</em> case-folded, because
     * tokens are case-sensitive. When the key names an identity that is case-insensitive
     * (a username, an email address), normalize it in the extractor so {@code Alice} and
     * {@code alice} share one bucket.
     *
     * <p><strong>A null or blank key is limited, not skipped</strong> (M4): such a request counts
     * against a missing-key bucket for its client address ({@code ipBucket(req.ip())}, so an IPv6
     * client's /64), with this limiter's limit and window, exactly as {@link #perIp} would count it.
     * A client cannot bypass the limiter by leaving the key out, and keyless requests from one
     * address cannot lock out another. When a missing key genuinely means "nothing to count" and
     * must pass through, opt in with {@link PerKeyLimit#exemptMissingKey()}.
     *
     * <p>Example: rate-limit login submissions per account. The limiter runs only for the POST,
     * so viewing the login page is not counted (registered for every method, each page view
     * would use up one of the visitor's keyless requests; dev mode warns once when a key limiter
     * counts a keyless GET or HEAD). A submission without a username is counted against its
     * address's missing-key bucket.
     *
     * <pre>{@code
     * var loginLimit = RateLimiter.perKey(req -> {
     *     var username = req.formParam("username");
     *     return username == null ? null : username.strip().toLowerCase(Locale.ROOT);
     * }, 5, "15m");
     * app.before("/login", req -> "POST".equals(req.method()) ? loginLimit.handle(req) : null);
     * }</pre>
     */
    public static PerKeyLimit perKey(Function<Request, String> keyExtractor, int maxRequests, String duration) {
        var limiter = new RateLimiter(maxRequests, Cache.parseTtl(duration), keyExtractor,
            "perKey(" + maxRequests + "/" + duration + ")");
        return new PerKeyLimit(limiter);
    }

    /**
     * The middleware returned by {@link #perKey}: register it with {@code app.before(...)}, and
     * call {@link #exemptMissingKey()} on it to change how a missing key is treated.
     */
    public static final class PerKeyLimit implements Middleware.Before {
        private final RateLimiter limiter;

        private PerKeyLimit(RateLimiter limiter) {
            this.limiter = limiter;
        }

        /**
         * Let a request whose key is null or blank through without counting it, instead of
         * counting it in its address's missing-key bucket (the default). Use it only where a client
         * gains nothing by omitting the key, typically because another limiter already bounds
         * the requests that lack one:
         *
         * <pre>{@code
         * app.before("/api/*", RateLimiter.perIp(300, "1m"));                // everyone, by address
         * app.before("/api/*", RateLimiter.perKey(req -> req.header("X-Api-Key"), 1000, "1h")
         *     .exemptMissingKey());                                           // keyed clients, by key
         * }</pre>
         *
         * @return this middleware, for registration
         */
        public PerKeyLimit exemptMissingKey() {
            limiter.exemptMissingKey = true;
            return this;
        }

        /**
         * Track at most {@code maxKeys} distinct keys at a time (default
         * {@value RateLimiter#DEFAULT_MAX_KEYS}). At the cap a new key takes the place of a key
         * under its limit, and keys at their limit are kept until their windows end; see
         * {@link RateLimiter#perKey}.
         *
         * @return this middleware, for registration
         */
        public PerKeyLimit maxKeys(int maxKeys) {
            limiter.setMaxKeys(maxKeys);
            return this;
        }

        // Visible for testing
        int windowCount() {
            return limiter.windowCount();
        }

        // Visible for testing
        boolean warnedKeylessRead() {
            return limiter.warnedKeylessRead.get();
        }

        @Override
        public Result handle(Request req) {
            return limiter.check(req);
        }
    }

    /**
     * Prefix of the bucket keys for a null or blank extractor result ({@link #missingKeyBucket}).
     * It starts with a space, and {@link #normalizeKey} strips every key it returns and hashes to
     * hex, so no client-sent key (not even the literal {@code " (none):<address>"}) can land in,
     * drain, or ride on one of these buckets.
     */
    static final String MISSING_KEY = " (none)";

    /** Set by {@link PerKeyLimit#exemptMissingKey()}: skip, rather than count, a missing key. */
    private volatile boolean exemptMissingKey;

    /** The dev-mode "keyless GET/HEAD counted" warning has been logged (once per limiter). */
    private final AtomicBoolean warnedKeylessRead = new AtomicBoolean();

    /**
     * The bucket for a request with no key (M4 review): {@link #MISSING_KEY}, {@code ":"}, and the
     * client's {@link #ipBucket}. Starts with a space like {@link #MISSING_KEY}, so no client-sent
     * key (which {@link #normalizeKey} strips) can name it, for its own address or another's.
     */
    static String missingKeyBucket(Request req) {
        var ip = req == null ? null : req.ip();
        return MISSING_KEY + ":" + (ip != null ? ipBucket(ip) : "unknown");
    }

    /**
     * Dev mode, once per limiter: a key limiter just counted a keyless GET or HEAD, which is usually
     * a page view of the form the limiter guards (a {@code GET /login} with no email). Each such
     * view uses up the visitor's keyless budget; limiting only the POST is almost always what was
     * meant.
     */
    private void warnIfKeylessRead(Request req) {
        if (warnedKeylessRead.get() || req == null) return;
        var method = req.method();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return;
        if (!"dev".equals(System.getProperty("brace.mode"))) return;
        if (warnedKeylessRead.compareAndSet(false, true)) {
            Log.warn("rate-limiter: " + label + " counted a " + method.toUpperCase(java.util.Locale.ROOT) + " "
                + req.path() + " that has no key, so each view of that page uses up one of the visitor's "
                + "keyless requests. Limit only the submission: app.before(\"/login\", req -> "
                + "\"POST\".equals(req.method()) ? limiter.handle(req) : null). Logged once per limiter, in dev mode.");
        }
    }

    /**
     * Install the shared cluster-wide counter backend. Called by {@code Brace.start()} when the app
     * runs on Postgres; afterwards every rate limiter counts fleet-wide. Idempotent.
     */
    static void useSharedBackend(Counters counters) {
        sharedCounters = counters;
    }

    /** Revert to per-process counting. Called by {@code Brace.stop()} and in test teardown. */
    static void disableSharedBackend() {
        sharedCounters = null;
    }

    /**
     * Drop every registered limiter from the ops registry (M5). {@link #ALL} is a process-global
     * list that limiters only ever joined, so across app restarts in one JVM — tests, or several
     * {@code Brace} instances — {@link #allStats()} kept reporting limiters whose app was long
     * gone, with their final counts frozen. Called by {@code Brace.stop()}.
     */
    static void forgetLimiters() {
        synchronized (ALL) { ALL.clear(); }
    }

    Result check(Request req) {
        // Normalize the key before it reaches either backend: stripped, and keys longer than
        // MAX_KEY_LENGTH → SHA-256 hex digest (fixed 64 chars) to prevent DoS via unbounded
        // brace_counters rows / local map entries with huge key strings.
        var key = normalizeKey(keyExtractor.apply(req));

        // M4: a null or blank key is counted rather than skipping the limiter (leaving the key out
        // was a bypass). The exemption is an explicit opt-in. M4 review (decision A): counted per
        // client address, not in one global bucket: every keyless page view from anyone drained
        // that bucket and locked out the next visitor. A keyless client is limited exactly as
        // perIp would limit it, with this limiter's limit and window.
        if (key == null) {
            if (exemptMissingKey) {
                return null;
            }
            warnIfKeylessRead(req);
            key = missingKeyBucket(req);
        }

        var counters = sharedCounters;
        if (counters != null) {
            try {
                return checkShared(key, req, counters);
            } catch (RuntimeException e) {
                // Postgres (or any shared-counter) failure: fall back to per-instance counting
                // rather than returning a 500 or silently admitting every request.  A brief DB
                // blip causes per-instance approximation (across a fleet the effective limit
                // becomes limit × N for the duration of the outage), which is far better than
                // turning every rate-limited endpoint into an error.
                Log.warn("rate-limiter: shared-counter DB error, falling back to local counting — " + e.getMessage());
                return checkLocal(key, req);
            }
        }
        return checkLocal(key, req);
    }

    /**
     * Normalize a raw key value extracted from the request.
     *
     * <ul>
     *   <li>null or blank → {@code null}: the caller ({@link #check}) maps it to
     *       {@link #MISSING_KEY} or exempts it.
     *   <li>surrounding whitespace is stripped ({@link String#strip()}, the same definition of
     *       whitespace as {@link String#isBlank()}), so {@code "victim "} is {@code "victim"}.
     *       Case is left alone: tokens are case-sensitive, and the extractor is the place to fold
     *       case for identities that are not.
     *   <li>length &gt; {@link #MAX_KEY_LENGTH} → SHA-256 hex digest — caps storage at 64 chars,
     *       preventing DoS via arbitrarily long user-controlled strings (see field javadoc).
     * </ul>
     *
     * Never returns a value that starts with whitespace, which is what keeps
     * {@link #MISSING_KEY} out of a client's reach. Applied before any prefix/window-slot
     * decoration so the cap holds in both the local and shared backends.
     */
    static String normalizeKey(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        rawKey = rawKey.strip();
        if (rawKey.isEmpty()) {
            return null;
        }
        if (rawKey.length() <= MAX_KEY_LENGTH && !hasControlCharacter(rawKey) && !Cache.hasUnpairedSurrogate(rawKey)) {
            return rawKey;
        }
        // Replace long keys, and keys with a control character (L5: a NUL is refused by pgjdbc and
        // failed the whole shared-counter batch) or an unpaired surrogate (L5 review F11: pgjdbc
        // sends it as "?", so two such keys shared a row), with their SHA-256 hex digest (64
        // chars, fixed). Cache.digestInput hashes a lone surrogate injectively.
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(Cache.digestInput(rawKey));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE implementation — unreachable.
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /** True if any char is an ISO control character: below 0x20, 0x7F, or 0x80 to 0x9F. */
    private static boolean hasControlCharacter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Per-process fixed-window count (default; single-process apps, H2, no database). */
    private Result checkLocal(String key, Request req) {
        var now = Instant.now();
        long nowMillis = now.toEpochMilli();
        key = admit(key, req, windows, false, Function.identity(), nowMillis);
        var window = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.expired(nowMillis)) {
                return new Window(now, 1, nowMillis);
            }
            return new Window(existing.start, existing.count + 1, nowMillis);
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
    private Result checkShared(String key, Request req, Counters counters) {
        long windowMillis = Math.max(1, windowDuration.toMillis());
        long nowMillis = Instant.now().toEpochMilli();
        long slot = nowMillis / windowMillis;
        long windowEndMillis = (slot + 1) * windowMillis;
        // Key must be stable across instances for the same logical limiter: label encodes type +
        // limit + window, key is the per-client value, slot rotates each window. (Two limiters with
        // identical type/limit/window on different paths would share a budget — name them apart if
        // that matters; uncommon, and the failure mode is conservative.) The label is the only
        // identity a limiter has across the fleet, so this sharing covers its internal missing-key
        // and overflow buckets too (L6 review F12): two same-label limiters count keyless and
        // overflowed requests from one client together. In local mode each limiter object has its
        // own table, so nothing is shared; making local mode share by label would couple limiters
        // the app built separately, so the difference is documented instead.
        Function<String, String> counterKeyOf = k -> "rl:" + label + ":" + k + ":" + slot;
        // L6: cap distinct keys. windowEndByKey holds every key this instance tracks for the shared
        // path; pending, lastKnownGlobal and blockedUntil hold only keys it holds too (bar a brief
        // race with expiry, which the next flush or sweep clears), so capping it caps them all.
        String bucket = admit(key, req, windowEndByKey, true, counterKeyOf, nowMillis);
        String counterKey = counterKeyOf.apply(bucket);

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
        boolean overflowBucket = bucket.startsWith(OVERFLOW_KEY);
        windowEndByKey.computeIfAbsent(counterKey, k -> new SharedKey(windowEndMillis, overflowBucket, nowMillis))
            .lastTouch = nowMillis;
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
            if (flush(counters, nowMillis)) {
                flushCooldownUntilMillis = 0L;
            } else {
                // Out of time (L6 review F4): what was not written is back in the buffer. Back off
                // like a failure, so the next requests are not each held for another budget.
                flushCooldownUntilMillis = nowMillis + FLUSH_RETRY_COOLDOWN_MILLIS;
            }
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
     * are dropped (their counter row is irrelevant). On a DB outage every captured delta is added
     * back to the buffer so no count is lost and per-instance enforcement continues; the exception
     * propagates so {@link #maybeFlush} arms the retry cooldown.
     *
     * <p>L6 review F4: the batch is written in statements of {@link #FLUSH_CHUNK_ROWS} rows (one
     * statement for a large buffer exceeded Postgres's parameter limit). A statement the database
     * refuses with a data error (SQLState class 22 or 54: a key it cannot store) is bisected until
     * the refused key(s) are isolated and dropped, O(log n) statements per bad key instead of one
     * per key (L5 had retried key by key). Any other failure restores everything not yet written
     * and propagates, like an outage: it is not a key's fault. The flush stops after
     * {@link #flushTimeBudgetMillis}, restoring what it did not get to.
     *
     * @return false if the time budget ran out
     */
    private boolean flush(Counters counters, long nowMillis) {
        var updates = new ArrayList<Counters.CounterUpdate>();
        long pulled = 0;
        for (var entry : pending.entrySet()) {
            String counterKey = entry.getKey();
            SharedKey tracked = windowEndByKey.get(counterKey);
            if (tracked == null || tracked.windowEnd <= nowMillis) {
                // Window rolled (or the key was evicted): drop it entirely (new requests use a new
                // slot's key).
                pulled += entry.getValue().getAndSet(0);
                pending.remove(counterKey);
                if (tracked != null) windowEndByKey.remove(counterKey, tracked);
                lastKnownGlobal.remove(counterKey);
                blockedUntil.remove(counterKey);
                overflowKeys.remove(counterKey);
                continue;
            }
            long delta = entry.getValue().getAndSet(0);
            if (delta <= 0) {
                continue;
            }
            pulled += delta;
            updates.add(new Counters.CounterUpdate(counterKey, delta, Instant.ofEpochMilli(tracked.windowEnd)));
        }
        totalPending.add(-pulled);
        deleteEvictedRows(counters);
        if (updates.isEmpty()) {
            return true;
        }
        return writeBisecting(counters, updates, System.nanoTime() + flushTimeBudgetMillis * 1_000_000L);
    }

    /** Write {@code updates} in chunks, bisecting a chunk refused for its data; see {@link #flush}. */
    private boolean writeBisecting(Counters counters, List<Counters.CounterUpdate> updates, long deadlineNanos) {
        var work = new java.util.ArrayDeque<List<Counters.CounterUpdate>>();
        for (int from = 0; from < updates.size(); from += FLUSH_CHUNK_ROWS) {
            work.addLast(updates.subList(from, Math.min(updates.size(), from + FLUSH_CHUNK_ROWS)));
        }
        int refused = 0;
        boolean inTime = true;
        while (!work.isEmpty()) {
            var part = work.pollFirst();
            if (System.nanoTime() - deadlineNanos > 0) {
                restore(part);
                work.forEach(this::restore);
                inTime = false;
                Log.warn("rate-limiter: writing the shared counters of " + label + " took over "
                    + flushTimeBudgetMillis + " ms; the rest is kept for the next flush");
                break;
            }
            try {
                counters.incrementBatch(part).forEach(lastKnownGlobal::put);
            } catch (RuntimeException e) {
                if (Counters.isOutage(e) || !Counters.isDataError(e)) {
                    restore(part);
                    work.forEach(this::restore);
                    throw e;
                }
                if (part.size() == 1) {
                    refused++;
                    continue;
                }
                int mid = part.size() / 2;
                work.addFirst(part.subList(mid, part.size()));
                work.addFirst(part.subList(0, mid));
            }
        }
        if (refused > 0) {
            Log.warn("rate-limiter: the database refused " + refused + " shared-counter key(s) of "
                + label + "; their buffered counts were dropped");
        }
        return inTime;
    }

    /** Put the deltas of a failed write back into the buffer so the next flush retries them. */
    private void restore(List<Counters.CounterUpdate> updates) {
        long restored = 0;
        for (var u : updates) {
            pending.computeIfAbsent(u.key(), k -> new AtomicLong()).addAndGet(u.delta());
            restored += u.delta();
        }
        totalPending.add(restored);
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
        long sweepMillis = now.toEpochMilli();
        windows.entrySet().removeIf(entry -> entry.getValue().expired(sweepMillis));

        // M17: drop batched-counter state for windows that have rolled. Without traffic a flush
        // never runs, so keys for an expired window would otherwise sit in the maps until the next
        // flush. Negative-cache entries self-clear on access, but a blocked key with no further
        // traffic is reaped here too. Flushing zeroes a key's pending count but keeps its entries
        // until the window rolls, so the number of keys is bounded by maxKeys (L6), not by the
        // flush batch; this sweep is what frees them once their windows end.
        long nowMillis = now.toEpochMilli();
        windowEndByKey.entrySet().removeIf(entry -> {
            if (entry.getValue().windowEnd > nowMillis) {
                return false;
            }
            forgetShared(entry.getKey());
            return true;
        });
        blockedUntil.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
        // Per-IP overflow buckets whose windows are gone from both tables no longer count against
        // their own cap. Bounded by that cap, so this is cheap.
        overflowKeys.removeIf(k -> !windows.containsKey(k) && !windowEndByKey.containsKey(k));

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
        /** When the key was last counted, for the eviction sampler's least-recently-used choice. */
        final long lastTouch;

        Window(Instant start, int count, long lastTouch) {
            this.start = start;
            this.count = count;
            this.lastTouch = lastTouch;
        }

        boolean expired(long nowMillis) {
            return nowMillis > start.toEpochMilli() + windowDuration.toMillis();
        }
    }
}
