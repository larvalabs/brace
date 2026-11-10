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

    /**
     * Most single-row fleet-count reads one limiter makes in a second for keys that may have been
     * evicted earlier in their window (L6 verification). Past it a returning key is treated as new
     * and the next batched flush brings its fleet count, so a flood that makes every new key look
     * like a returning one cannot put a database round trip on every request.
     */
    static final int DEFAULT_FLEET_READS_PER_SECOND = 50;
    static volatile int fleetReadsPerSecond = DEFAULT_FLEET_READS_PER_SECOND;

    /** Set the global flush-batch divisor (Brace builder). Clamped to ≥ 1. */
    static void setBatchDivisor(int divisor) {
        batchDivisor = Math.max(1, divisor);
    }

    /**
     * What a limiter does with a request it has no room to count: its key table is full of keys at
     * their limit, and so is its table of per-address overflow buckets. See {@link #perKey} for
     * how a table gets there and what each choice costs.
     */
    public enum Saturation {
        /**
         * The default. Every such request is counted in one last-resort bucket with the limiter's
         * own limit, shared by all of them, so once it is used up they are refused with 429 until
         * the window ends. No request goes uncounted, at the price that an attacker who can
         * saturate the tables locks out every client the limiter is not already tracking.
         */
        FAIL_CLOSED,
        /**
         * Such a request passes uncounted (a warning is logged at most once a minute). Clients
         * are never locked out by a saturated table, at the price that an attacker who can
         * saturate the tables gets unlimited requests for any key the limiter is not already
         * tracking. Keys that are tracked are still counted, and blocked keys stay blocked.
         */
        FAIL_OPEN
    }

    private final int maxRequests;
    private final Duration windowDuration;
    private final Function<Request, String> keyExtractor;
    private final String label;
    private final LongAdder allowed = new LongAdder();
    private final LongAdder blocked = new LongAdder();

    /**
     * Default cap on the distinct keys one limiter tracks at a time (L6). Each key holds an entry
     * for its whole window, so without a cap a client that can mint keys (a rotating source
     * address, a made-up username) grows the state without bound. A limiter holds at most
     * {@code maxKeys} keys plus {@code maxKeys + 1} overflow buckets; what happens at the cap is
     * described on {@link #perKey}. Change per limiter with {@code maxKeys(int)}. Counting
     * everything, a limiter holds at most those tracked entries, a flush queue of
     * {@link #dirtyBound()} ({@code 2 × maxKeys + 10,016}) entries on Postgres, the per-process
     * fallback tables after a shared-counter error (another {@code 2 × maxKeys + 1}), and two
     * evicted-key filter generations of 4 bytes per {@code maxKeys} each.
     */
    static final int DEFAULT_MAX_KEYS = 100_000;

    /**
     * Last-resort bucket for new keys once the key table and the per-address overflow table are
     * both full of entries at their limit ({@link Saturation#FAIL_CLOSED}). Like
     * {@link #MISSING_KEY} it starts with a space, which no normalized key can, so a client cannot
     * address it directly; the per-address overflow buckets are this followed by {@code ":"} and
     * the client's {@link #ipBucket}.
     */
    static final String OVERFLOW_KEY = " (overflow)";

    /** Minimum gap between two "key cap reached" or "saturated" warnings from one limiter. */
    private static final long OVERFLOW_WARN_INTERVAL_MILLIS = 60_000;

    /** Keys examined per eviction attempt (L6 review): bounded work, an approximate LRU. */
    static final int EVICTION_SAMPLE = 16;

    private volatile int maxKeys = DEFAULT_MAX_KEYS;
    private volatile Saturation saturation = Saturation.FAIL_CLOSED;
    private final AtomicLong lastOverflowWarnMillis = new AtomicLong();
    private final AtomicLong lastSaturationWarnMillis = new AtomicLong();

    /**
     * Recency for the eviction sampler: every counted request takes the next value (L6 final
     * review). A wall-clock millisecond is shared by every request in it, so the key being hit
     * "now" tied with the rest of its millisecond and could be the one evicted.
     */
    private final AtomicLong touchSequence = new AtomicLong();

    // --- Per-process counting (single-process apps, H2, no database) ---
    /** Ordinary keys, at most {@link #maxKeys} (plus a few under concurrency; see {@link #trim}). */
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    /** Per-address overflow buckets, at most {@link #maxKeys}, and the one last-resort bucket. */
    private final ConcurrentHashMap<String, Window> overflowWindows = new ConcurrentHashMap<>();

    // --- M17: batched, best-effort shared counting (used only on the shared-backend path) ---
    // Instead of a DB round trip per request, each instance buffers increments locally and flushes
    // them in one Counters.incrementBatch call every flushThreshold() buffered requests, so DB
    // writes are bounded by requests/flushThreshold regardless of key cardinality. A request's
    // allow/block decision uses (fleet count at the key's last flush) + (its buffered count):
    // best-effort, the fleet view lags by up to one flush, so the effective limit can overshoot
    // by ~flushThreshold × instances. Documented.
    //
    // L6 final review: everything the instance knows about a key is ONE object in ONE map (two
    // maps: ordinary keys and overflow buckets), so there is no second map whose entries can
    // outlive the first. A SharedKey that has left its map is garbage once the flush queue lets
    // go of it; nothing re-inserts it.
    private final ConcurrentHashMap<String, SharedKey> sharedKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SharedKey> sharedOverflowKeys = new ConcurrentHashMap<>();
    /** Keys with a buffered count, each at most once ({@link SharedKey#queued}); drained by a flush. */
    private final java.util.concurrent.ConcurrentLinkedQueue<SharedKey> dirty = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger dirtyCount = new java.util.concurrent.atomic.AtomicInteger();
    /** The queue hit its bound (only while flushes fail): the next flush scans the tables instead. */
    private volatile boolean dirtyOverflowed;
    /**
     * Requests buffered since the last flush drained the buffer; the flush trigger. Only ever
     * incremented by a counted request, set to zero by a flush, and added back to when a failed
     * flush restores its deltas, so it cannot go negative and an eviction cannot hold it under the
     * threshold (L6 final review: the old running total was decremented on eviction, drifted
     * negative under races, and stalled the flush).
     */
    private final AtomicLong unflushed = new AtomicLong();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile long flushCooldownUntilMillis = 0L;

    /** Serializes eviction only: taken on the full-table, new-key path, never otherwise. */
    private final java.util.concurrent.locks.ReentrantLock evictLock = new java.util.concurrent.locks.ReentrantLock();
    /** Where the eviction sampler resumes in each table, so successive attempts look at new keys. Guarded by {@link #evictLock}. */
    private final java.util.IdentityHashMap<Map<String, ?>, java.util.Iterator<String>> cursors = new java.util.IdentityHashMap<>();

    /**
     * Shared keys this instance evicted whose windows are still open, as Bloom filters, so a key
     * that comes back is recognised and its fleet count read before it is judged (L6 final
     * review). Null until the first eviction. A false positive costs one extra database round
     * trip, within {@link #fleetReadsPerSecond}.
     *
     * <p>L6 verification: one filter for a whole window filled up over a long flood, and then
     * every new key looked like a returning one. The filters now come in generations: when the
     * current one has taken {@link EvictedFilter#capacity} keys a new one starts and the old one
     * is kept for lookups until the next rotation, so the false-positive rate stays under about
     * 0.4% per generation. A key evicted two rotations ago is no longer recognised: it is then
     * treated as new, and the next batched flush brings its fleet count (see {@link #perKey}).
     */
    private volatile EvictedFilters evictedFilters;

    /** One generation of the evicted-key filter: 32 bits per key it is sized for, two probes. */
    private static final class EvictedFilter {
        final java.util.concurrent.atomic.AtomicLongArray bits;
        /** Keys this generation takes before the next one starts. */
        final int capacity;
        int added; // guarded by evictLock
        /** The latest window end among its keys; past it every key in it has expired. */
        volatile long horizonMillis;

        EvictedFilter(int maxKeys, long horizonMillis) {
            long size = Math.max(1L << 16, Math.min(32L * maxKeys, 1L << 25));
            this.bits = new java.util.concurrent.atomic.AtomicLongArray((int) (size / 64));
            this.capacity = (int) (size / 32);
            this.horizonMillis = horizonMillis;
        }

        boolean live(long nowMillis) {
            return nowMillis < horizonMillis;
        }
    }

    /** The current generation and the one before it, swapped as one so a lookup sees both. */
    private record EvictedFilters(EvictedFilter current, EvictedFilter previous) {}

    /** {@link #fleetReadsPerSecond} accounting: the epoch second shifted left 20 bits, plus reads in it. */
    private final AtomicLong fleetReads = new AtomicLong();

    /** What kind of bucket a tracked entry is. */
    private enum Kind { KEY, ADDRESS_OVERFLOW, LAST_RESORT }

    /**
     * Everything one instance knows about one shared counter key (which names the limiter, the
     * bucket and the window slot, so a new window is a new key and a new object).
     */
    private static final class SharedKey {
        final String counterKey;
        final long windowEnd;
        final Kind kind;
        /** Requests counted here and not yet written to the shared counter. */
        final AtomicLong pending = new AtomicLong();
        /** In {@link #dirty}; set by the request that enqueues it, cleared by the flush that polls it. */
        final AtomicBoolean queued = new AtomicBoolean();
        /** A request is reading this key's fleet count ({@code readFleetCount}); no monitor is held across it. */
        final AtomicBoolean reading = new AtomicBoolean();
        /** The fleet count as of this key's last write, or -1 when this instance has not written or read it yet. */
        final AtomicLong fleetCount = new AtomicLong(-1);
        /**
         * Negative cache: known over the limit for its window, so answered 429 with no DB call and
         * no buffering (the abusive-client traffic we most want to shed). Fixed-window counts only
         * grow within a slot, so once over, a key stays over until the slot rolls.
         */
        volatile boolean blocked;
        volatile long lastTouch;

        SharedKey(String counterKey, long windowEnd, Kind kind) {
            this.counterKey = counterKey;
            this.windowEnd = windowEnd;
            this.kind = kind;
        }

        long estimate() {
            return Math.max(0, fleetCount.get()) + pending.get();
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

        /**
         * What to do with a client there is no room to count; see {@link Saturation} and
         * {@link RateLimiter#perKey}. Default {@link Saturation#FAIL_CLOSED}.
         *
         * @return this middleware, for registration
         */
        public PerIpCheck onSaturation(Saturation saturation) {
            limiter.setSaturation(saturation);
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

    private void setSaturation(Saturation saturation) {
        this.saturation = java.util.Objects.requireNonNull(saturation, "saturation");
    }

    /** Where a request is counted: a table, the key it is stored under there, and what kind of bucket that is. */
    private record Slot<T>(ConcurrentHashMap<String, T> table, String key, Kind kind) {}

    /**
     * Where a request is counted (L6; review decision B, final review M-D), or null when there is
     * nowhere and the limiter fails open. The bucket's own entry in {@code keys} while that table
     * has room or already tracks it. At the cap ({@link #maxKeys} keys):
     * <ol>
     *   <li>a tracked key that is <em>under</em> its limit is forgotten to make room: of a bounded
     *       sample ({@link #EVICTION_SAMPLE}) an expired key first, else the least recently used,
     *       by a per-limiter sequence, so the key being hit now is never the candidate while
     *       another exists. A key at or over its limit is never forgotten before its window ends,
     *       so a flood cannot release a blocked client. What forgetting costs differs by mode;
     *       see {@link #perKey};</li>
     *   <li>when the sample holds only such pinned keys, the request is counted in a per-address
     *       overflow bucket, {@link #OVERFLOW_KEY}{@code + ":" + ipBucket(req.ip())}, so one
     *       flooder does not lock out everyone else. These live in their own table of up to
     *       {@code maxKeys} entries and are replaced by the same rule (under its limit: evictable;
     *       at its limit: pinned);</li>
     *   <li>when that table too holds only pinned buckets the limiter is saturated:
     *       {@link Saturation#FAIL_CLOSED} counts the request in the single last-resort
     *       {@link #OVERFLOW_KEY} bucket, {@link Saturation#FAIL_OPEN} lets it pass uncounted.</li>
     * </ol>
     * The size check is not atomic with the insert that follows, so under concurrency a table can
     * be a few entries (at most one per concurrent request) over its cap; {@link #trim} brings it
     * back. Eviction takes a per-limiter lock and examines a fixed number of keys, so no request
     * scans a table.
     *
     * @param tableKey maps a bucket to the key the tables store it under
     */
    private <T> Slot<T> admit(String bucket, Request req, ConcurrentHashMap<String, T> keys,
                              ConcurrentHashMap<String, T> overflow, Function<String, String> tableKey,
                              long nowMillis) {
        String key = tableKey.apply(bucket);
        if (keys.containsKey(key) || keys.size() < maxKeys) {
            return new Slot<>(keys, key, Kind.KEY);
        }
        warnKeyCap();
        if (evictOne(keys, nowMillis)) {
            return new Slot<>(keys, key, Kind.KEY);
        }
        var ip = req == null ? null : req.ip();
        String perAddress = tableKey.apply(OVERFLOW_KEY + ":" + (ip != null ? ipBucket(ip) : Request.UNKNOWN_IP));
        if (overflow.containsKey(perAddress) || overflow.size() < maxKeys || evictOne(overflow, nowMillis)) {
            return new Slot<>(overflow, perAddress, Kind.ADDRESS_OVERFLOW);
        }
        boolean failOpen = saturation == Saturation.FAIL_OPEN;
        warnSaturated(failOpen);
        return failOpen ? null : new Slot<>(overflow, tableKey.apply(OVERFLOW_KEY), Kind.LAST_RESORT);
    }

    /** After an insert: if concurrent inserts took {@code table} past its cap, give one entry back. */
    private void trim(ConcurrentHashMap<String, ?> table, long nowMillis) {
        // The overflow tables also hold the one last-resort bucket.
        int cap = table == windows || table == sharedKeys ? maxKeys : maxKeys + 1;
        if (table.size() > cap) {
            evictOne(table, nowMillis);
        }
    }

    private void warnKeyCap() {
        long now = System.currentTimeMillis();
        long last = lastOverflowWarnMillis.get();
        if (now - last >= OVERFLOW_WARN_INTERVAL_MILLIS && lastOverflowWarnMillis.compareAndSet(last, now)) {
            Log.warn("rate-limiter: " + label + " is tracking its maximum of " + maxKeys
                + " keys; new keys replace keys under their limit (keys at their limit are kept until "
                + "their windows end), and fall back to a per-address overflow bucket when none can be replaced");
        }
    }

    private void warnSaturated(boolean failOpen) {
        long now = System.currentTimeMillis();
        long last = lastSaturationWarnMillis.get();
        if (now - last >= OVERFLOW_WARN_INTERVAL_MILLIS && lastSaturationWarnMillis.compareAndSet(last, now)) {
            Log.warn("rate-limiter: " + label + " is SATURATED: all " + maxKeys + " tracked keys and all "
                + maxKeys + " per-address overflow buckets are at their limit, which takes a sustained flood. "
                + (failOpen
                    ? "Requests for keys it is not tracking are passing UNCOUNTED (onSaturation(FAIL_OPEN))."
                    : "Requests for keys it is not tracking share one last-resort bucket and are mostly "
                        + "refused with 429 (onSaturation(FAIL_CLOSED), the default).")
                + " Raise maxKeys(...), and limit the flood's sources with a perIp limiter in front."
                + " See docs/SECURITY.md, Rate Limiting. Logged at most once a minute.");
        }
    }

    private static final long EVICT_PINNED = Long.MAX_VALUE;
    private static final long EVICT_EXPIRED = Long.MIN_VALUE;

    /**
     * Added to the rank of a shared key with a buffered count, so a key counted since the last
     * flush is evicted only when the sample holds no key that was not. Sequence values stay far
     * below it.
     */
    private static final long EVICT_BUFFERED = 1L << 62;

    /**
     * Forget one entry of {@code table} that is under its limit (or expired), chosen from the next
     * {@link #EVICTION_SAMPLE} keys of a cursor that walks the table across calls: the lowest
     * {@link #evictionRank} of them. Returns false when every sampled entry is pinned.
     */
    private <T> boolean evictOne(ConcurrentHashMap<String, T> table, long nowMillis) {
        evictLock.lock();
        try {
            java.util.Iterator<String> cursor = cursors.get(table);
            String best = null;
            long bestRank = EVICT_PINNED;
            boolean restarted = false;
            for (int examined = 0; examined < EVICTION_SAMPLE; ) {
                if (cursor == null || !cursor.hasNext()) {
                    if (restarted) break;
                    restarted = true;
                    cursor = table.keySet().iterator();
                    continue;
                }
                String candidate = cursor.next();
                examined++;
                long rank = evictionRank(candidate, table.get(candidate), nowMillis);
                if (rank < bestRank) {
                    best = candidate;
                    bestRank = rank;
                    if (rank == EVICT_EXPIRED) break;
                }
            }
            cursors.put(table, cursor);
            return best != null && evict(table, best, nowMillis);
        } finally {
            evictLock.unlock();
        }
    }

    /**
     * How soon an entry should be evicted: {@link #EVICT_EXPIRED} (first), its last-use sequence
     * number (lower goes sooner; plus {@link #EVICT_BUFFERED} for a shared key counted since the
     * last flush), or {@link #EVICT_PINNED} (never: at or over its limit, the last-resort bucket,
     * or already gone).
     */
    private long evictionRank(String key, Object entry, long nowMillis) {
        if (entry instanceof Window w) {
            if (w.expired(nowMillis)) return EVICT_EXPIRED;
            return OVERFLOW_KEY.equals(key) || w.count >= maxRequests ? EVICT_PINNED : w.lastTouch;
        }
        if (entry instanceof SharedKey k) {
            if (k.windowEnd <= nowMillis) return EVICT_EXPIRED;
            if (k.kind == Kind.LAST_RESORT || k.blocked || k.estimate() >= maxRequests) return EVICT_PINNED;
            return k.pending.get() > 0 ? k.lastTouch + EVICT_BUFFERED : k.lastTouch;
        }
        return EVICT_PINNED;
    }

    /**
     * Remove {@code key} from {@code table} unless it became pinned since it was ranked.
     *
     * <p>A local entry's count goes with it. A shared entry loses nothing: its {@code brace_counters}
     * row is left alone (final review M-B: deleting it let an instance with a stale view release a
     * key the fleet had blocked; rows expire with their windows), a count it has buffered is still
     * written by the next flush (the object stays in the flush queue), and the key is remembered
     * in {@link #evictedFilters} so that, if it comes back, its fleet count can be read before it
     * is judged.
     */
    private <T> boolean evict(ConcurrentHashMap<String, T> table, String key, long nowMillis) {
        Object[] removed = {null};
        table.computeIfPresent(key, (k, entry) -> {
            if (evictionRank(k, entry, nowMillis) == EVICT_PINNED) return entry;
            removed[0] = entry;
            return null;
        });
        if (removed[0] instanceof SharedKey sk && sk.windowEnd > nowMillis) {
            rememberEvicted(sk, nowMillis);
        }
        return removed[0] != null;
    }

    /**
     * Record an evicted shared key in the current filter generation, starting a new generation
     * when the current one is full or stale. Called with {@link #evictLock} held.
     *
     * <p>A generation has 32 bits per key of the cap (400 KB at the default cap; two generations
     * at most, allocated at the first eviction) and takes as many keys as the cap before the next
     * one starts: two probes give about 0.4% false positives when it is full, so a lookup in both
     * generations is wrong for under 1% of new keys however long the flood. A false positive
     * makes a new key cost one single-row write instead of a share of a batch, within the
     * per-second budget.
     */
    private void rememberEvicted(SharedKey sk, long nowMillis) {
        var filters = evictedFilters;
        if (filters == null || !filters.current().live(nowMillis)) {
            filters = new EvictedFilters(new EvictedFilter(maxKeys, sk.windowEnd), null);
        } else if (filters.current().added >= filters.current().capacity) {
            filters = new EvictedFilters(new EvictedFilter(maxKeys, sk.windowEnd), filters.current());
        }
        var filter = filters.current();
        int bits = filter.bits.length() * 64;
        for (int probe = 0; probe < 2; probe++) {
            int bit = filterBit(sk.counterKey, probe, bits);
            filter.bits.accumulateAndGet(bit >>> 6, 1L << (bit & 63), (a, b) -> a | b);
        }
        filter.added++;
        if (sk.windowEnd > filter.horizonMillis) {
            filter.horizonMillis = sk.windowEnd;
        }
        evictedFilters = filters;
    }

    /** True when this instance may have evicted {@code counterKey} in its current window. */
    private boolean mayHaveBeenEvicted(String counterKey, long nowMillis) {
        var filters = evictedFilters;
        if (filters == null) return false;
        return contains(filters.current(), counterKey, nowMillis) || contains(filters.previous(), counterKey, nowMillis);
    }

    private static boolean contains(EvictedFilter filter, String counterKey, long nowMillis) {
        if (filter == null || !filter.live(nowMillis)) return false;
        int bits = filter.bits.length() * 64;
        for (int probe = 0; probe < 2; probe++) {
            int bit = filterBit(counterKey, probe, bits);
            if ((filter.bits.get(bit >>> 6) & 1L << (bit & 63)) == 0) return false;
        }
        return true;
    }

    /**
     * Take one of this second's {@link #fleetReadsPerSecond} single-row reads, or return false
     * when they are used up (the key then waits for the next batched flush).
     */
    private boolean takeFleetRead(long nowMillis) {
        long second = nowMillis / 1000;
        int budget = fleetReadsPerSecond;
        for (;;) {
            long cur = fleetReads.get();
            long next;
            if (cur >>> 20 != second) {
                if (budget < 1) return false;
                next = second << 20 | 1;
            } else if ((cur & 0xFFFFF) >= Math.min(budget, 0xFFFFF)) {
                return false;
            } else {
                next = cur + 1;
            }
            if (fleetReads.compareAndSet(cur, next)) return true;
        }
    }

    private static int filterBit(String key, int probe, int bits) {
        long h = (key.hashCode() + probe * 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        return (int) ((h & Long.MAX_VALUE) % bits);
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
     * <p><strong>Memory is bounded, and a flood has a defined outcome.</strong> A limiter tracks at
     * most {@code maxKeys} keys (default {@value #DEFAULT_MAX_KEYS}), {@code maxKeys} per-address
     * overflow buckets and one last-resort bucket (the flush queue and the fallback tables have
     * bounds of the same order; see {@link #DEFAULT_MAX_KEYS}). With the table full, a new key replaces a tracked
     * key that is under its limit (least recently used of a small sample); keys at their limit are
     * kept until their windows end, so a blocked key stays blocked. With shared (Postgres) counters
     * forgetting a key loses nothing fleet-wide: its row is kept and its buffered count is still
     * written. When it comes back, its fleet count is read before its first request is judged if
     * the instance still recognises it (it is among at least the last
     * {@code max(2,048, min(maxKeys, 1,048,576))} keys this instance evicted, and Bloom-filter
     * false positives stay under 1%) and the limiter has made fewer than 50 such single-row reads
     * in the current second. Otherwise it is counted as a new key and its fleet count arrives with
     * the limiter's next batched flush, the same lag as for any key: a key the fleet has blocked
     * is admitted by this instance at most one flush batch ({@code limit / 10} requests by
     * default) before it is refused, plus requests that arrive while another thread's flush is
     * running, outages aside. The exception is an instance whose key table is full of keys at
     * their limit: there an untracked key, a fleet-blocked one included, is counted in its client
     * address's overflow bucket (below) rather than refused as a key. With per-process counting the
     * forgotten key restarts from zero, so a client that keeps the table turning over (about
     * {@code maxKeys / 16} fresh-key requests per turn) buys fewer than the limit in extra attempts
     * at one key each turn. When only keys at their limit are found, a new key is counted against
     * its client address instead ({@code limit} requests per address and window for all its
     * untracked keys). When the per-address buckets are all at their limit too the limiter is
     * saturated, and {@link PerKeyLimit#onSaturation} decides: {@link Saturation#FAIL_CLOSED}
     * (default) refuses nearly every request it cannot count, so a flood of that size locks out
     * clients it is not tracking; {@link Saturation#FAIL_OPEN} lets them through uncounted, so the
     * same flood buys unlimited attempts at untracked keys. Saturating {@code perKey(…, 5, "15m")}
     * at the default cap takes about 1,300 requests a second sustained, from 100,000 client
     * addresses (or IPv6 /64s). {@code docs/SECURITY.md}, Rate Limiting, has the arithmetic.
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

        /**
         * What to do with a request there is no room to count: the key table and the per-address
         * overflow table are both full of entries at their limit. Default
         * {@link Saturation#FAIL_CLOSED}; see {@link Saturation} and {@link RateLimiter#perKey}
         * for what each choice costs.
         *
         * @return this middleware, for registration
         */
        public PerKeyLimit onSaturation(Saturation saturation) {
            limiter.setSaturation(saturation);
            return this;
        }

        // Visible for testing
        int windowCount() {
            return limiter.windowCount();
        }

        /** The size of the limiter's largest table. Visible for testing. */
        int trackedEntries() {
            return limiter.trackedEntries();
        }

        /** Entries in the flush queue, and the most it may hold. Visible for testing. */
        int queuedForFlush() {
            return limiter.queuedForFlush();
        }

        int flushQueueBound() {
            return limiter.dirtyBound();
        }

        /** Requests buffered since the last shared-counter flush. Visible for testing. */
        long unflushed() {
            return limiter.unflushed.get();
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
            Log.warn(keylessReadWarning(label, method, req.path()));
        }
    }

    /** The dev warning's text. The path is redacted as the request log redacts it (a reset token in a URL). */
    static String keylessReadWarning(String label, String method, String path) {
        return "rate-limiter: " + label + " counted a " + method.toUpperCase(java.util.Locale.ROOT) + " "
            + Redactor.redactPath(path) + " that has no key, so each view of that page uses up one of the "
            + "visitor's keyless requests. Limit only the submission: app.before(\"/login\", req -> "
            + "\"POST\".equals(req.method()) ? limiter.handle(req) : null). Logged once per limiter, in dev mode.";
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
                Log.warn("rate-limiter: shared-counter DB error, falling back to local counting — " + Redactor.redactMessage(e.getMessage()));
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
        var slot = admit(key, req, windows, overflowWindows, Function.identity(), nowMillis);
        if (slot == null) {
            allowed.increment(); // saturated and failing open: passed, uncounted
            return null;
        }
        boolean[] created = {false};
        var window = slot.table().compute(slot.key(), (k, existing) -> {
            long touch = touchSequence.incrementAndGet();
            if (existing == null || existing.expired(nowMillis)) {
                created[0] = existing == null;
                return new Window(now, 1, touch);
            }
            return new Window(existing.start, existing.count == Integer.MAX_VALUE ? existing.count : existing.count + 1, touch);
        });
        if (created[0]) {
            trim(slot.table(), nowMillis);
        }

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
     * uses the key's last known fleet count plus its buffered count. This is deliberately approximate — see the field
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
        // L6: cap distinct keys (see admit). A counter key names its window slot, so an entry is
        // only ever hit during its own window; entries of past windows wait for the sweep.
        var where = admit(key, req, sharedKeys, sharedOverflowKeys, counterKeyOf, nowMillis);
        if (where == null) {
            allowed.increment(); // saturated and failing open: passed, uncounted
            return null;
        }

        // Count the request on the key's entry, creating it if this instance is not tracking the
        // key. Negative cache: a key already over the limit for this window is rejected with no DB
        // call and no buffering; that is the same decision the counter would return, at zero DB
        // cost, for exactly the abusive-client load we want to shed.
        boolean[] flags = new boolean[2]; // [0] created, [1] already blocked
        SharedKey tracked = where.table().compute(where.key(), (k, existing) -> {
            if (existing == null) {
                existing = new SharedKey(k, windowEndMillis, where.kind());
                flags[0] = true;
            }
            existing.lastTouch = touchSequence.incrementAndGet();
            if (existing.blocked) {
                flags[1] = true;
            } else {
                existing.pending.incrementAndGet();
            }
            return existing;
        });
        if (flags[1]) {
            blocked.increment();
            return tooMany(retryAfterSeconds(tracked.windowEnd, nowMillis));
        }
        if (flags[0]) {
            trim(where.table(), nowMillis);
        }

        // A key this instance evicted earlier in the window and is tracking again: read what the
        // fleet has counted for it before judging this request (final review M-B), instead of
        // learning it at the next flush. Otherwise, or past this second's read budget (L6
        // verification), the count waits in the buffer for a flush.
        boolean written = tracked.fleetCount.get() < 0 && mayHaveBeenEvicted(tracked.counterKey, nowMillis)
            && readFleetCount(tracked, counters, nowMillis);
        if (!written) {
            unflushed.incrementAndGet();
            enqueue(tracked);
        }

        maybeFlush(counters, nowMillis);

        // Estimate after any flush: the fleet count as of the key's last write (which includes this
        // instance's earlier flushes) plus whatever is still buffered here. A concurrent flush may
        // have just moved the buffered half into the fleet half; either ordering yields a sound
        // best-effort estimate.
        if (tracked.estimate() > maxRequests) {
            blocked.increment();
            tracked.blocked = true; // arm the negative cache
            return tooMany(retryAfterSeconds(windowEndMillis, nowMillis));
        }
        allowed.increment();
        return null;
    }

    /**
     * Most entries the flush queue holds: every tracked entry, plus the keys one flush batch can
     * count and evict before the flush runs. Reached only while flushes are failing or stalled;
     * past it a request's key is not queued and the next flush scans the tables instead.
     */
    int dirtyBound() {
        return (int) Math.min(Integer.MAX_VALUE, 2L * maxKeys + MAX_FLUSH_BATCH + 16);
    }

    /** Put a key with a buffered count on the flush queue, once. */
    private void enqueue(SharedKey sk) {
        if (sk.queued.get()) {
            return;
        }
        if (dirtyCount.get() >= dirtyBound()) {
            dirtyOverflowed = true; // the next flush scans the tables
            return;
        }
        if (sk.queued.compareAndSet(false, true)) {
            dirtyCount.incrementAndGet();
            dirty.add(sk);
        }
    }

    /**
     * Write this key's buffered count now, on its own, and take the fleet count the database
     * returns: one round trip that both counts this request fleet-wide and tells this instance
     * where the key stands. Used for a key that may have been evicted earlier in its window.
     * One request per key reads at a time; a request that finds a read in progress does not wait
     * for it but is buffered and judged on what this instance has, like any request between
     * flushes (L6 verification: a monitor held across the database call pinned the virtual
     * thread's carrier on JDK 21, so a slow database starved unrelated requests). On a database
     * error the count goes back in the buffer and the key is judged on what this instance has, as
     * during any outage.
     *
     * @return true when the buffered count was written (or dropped as unstorable), so the caller
     *         has nothing left to queue for the next flush
     */
    private boolean readFleetCount(SharedKey sk, Counters counters, long nowMillis) {
        if (nowMillis < flushCooldownUntilMillis) {
            return false;
        }
        if (!sk.reading.compareAndSet(false, true)) {
            return false; // another request for this key is reading it
        }
        try {
            if (sk.fleetCount.get() >= 0) {
                return false; // another request for this key just read it
            }
            if (!takeFleetRead(nowMillis)) {
                return false; // this second's reads are used up: wait for the next flush
            }
            long delta = sk.pending.getAndSet(0);
            if (delta <= 0) {
                return false; // a flush took it and is recording the fleet count
            }
            var update = new Counters.CounterUpdate(sk.counterKey, delta, Instant.ofEpochMilli(sk.windowEnd));
            try {
                Long n = counters.incrementBatch(List.of(update),
                    System.nanoTime() + flushTimeBudgetMillis * 1_000_000L).get(sk.counterKey);
                if (n != null) {
                    sk.fleetCount.accumulateAndGet(n, Math::max);
                }
                return true;
            } catch (RuntimeException e) {
                if (!Counters.isOutage(e) && Counters.isDataError(e)) {
                    return true; // the database refuses this key; its count is dropped, as in a flush
                }
                sk.pending.addAndGet(delta);
                flushCooldownUntilMillis = nowMillis + FLUSH_RETRY_COOLDOWN_MILLIS;
                Log.warn("rate-limiter: reading a shared counter failed, counting per-instance until retry — "
                    + Redactor.redactMessage(e.getMessage()));
                return false;
            }
        } finally {
            sk.reading.set(false);
        }
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
        if (unflushed.get() < flushThreshold()) {
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
            Log.warn("rate-limiter: shared-counter flush failed, counting per-instance until retry — " + Redactor.redactMessage(e.getMessage()));
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
     * {@link #flushTimeBudgetMillis}, restoring what it did not get to, and every statement it
     * runs carries a query timeout for what is left of that budget (final review: the budget was
     * only checked between statements, so one slow statement held the request for as long as it
     * took).
     *
     * <p>Final review: the flush drains {@link #dirty}, the keys counted since the last flush,
     * rather than walking every tracked key. A key evicted since it was counted is still in the
     * queue and is written like any other, so eviction loses no count; a result is recorded on the
     * key's own entry, never into a map, so a key evicted mid-flush cannot be re-inserted by it.
     *
     * @return false if the time budget ran out
     */
    private boolean flush(Counters counters, long nowMillis) {
        unflushed.set(0);
        var byKey = new LinkedHashMap<String, Pulled>();
        if (dirtyOverflowed) {
            // The queue was full (flushes had been failing): take every tracked key's count.
            dirtyOverflowed = false;
            sharedKeys.values().forEach(sk -> pull(sk, byKey, nowMillis));
            sharedOverflowKeys.values().forEach(sk -> pull(sk, byKey, nowMillis));
        }
        // Bounded: entries queued while this loop runs wait for the next flush.
        for (int left = dirtyCount.get(); left > 0; left--) {
            SharedKey sk = dirty.poll();
            if (sk == null) break;
            dirtyCount.decrementAndGet();
            sk.queued.set(false); // before reading the count, so a later request queues it again
            pull(sk, byKey, nowMillis);
        }
        if (byKey.isEmpty()) {
            return true;
        }
        return writeBisecting(counters, new ArrayList<>(byKey.values()),
            System.nanoTime() + flushTimeBudgetMillis * 1_000_000L);
    }

    /**
     * One counter key's share of a flush: the count to add, and the entries it came from. Usually
     * one entry; two when a key was evicted and tracked again between flushes (a statement must
     * not name a counter key twice).
     */
    private static final class Pulled {
        final SharedKey first;
        List<SharedKey> others;
        long delta;

        Pulled(SharedKey first) {
            this.first = first;
        }

        Counters.CounterUpdate update() {
            return new Counters.CounterUpdate(first.counterKey, delta, Instant.ofEpochMilli(first.windowEnd));
        }

        void record(long fleetCount) {
            first.fleetCount.accumulateAndGet(fleetCount, Math::max);
            if (others != null) {
                others.forEach(sk -> sk.fleetCount.accumulateAndGet(fleetCount, Math::max));
            }
        }
    }

    /** Take {@code sk}'s buffered count into the flush, unless its window has rolled (then it is dropped). */
    private static void pull(SharedKey sk, Map<String, Pulled> byKey, long nowMillis) {
        long delta = sk.pending.getAndSet(0);
        if (delta <= 0 || sk.windowEnd <= nowMillis) {
            return;
        }
        var pulled = byKey.get(sk.counterKey);
        if (pulled == null) {
            byKey.put(sk.counterKey, pulled = new Pulled(sk));
        } else if (pulled.first != sk) {
            if (pulled.others == null) pulled.others = new ArrayList<>(1);
            pulled.others.add(sk);
        }
        pulled.delta += delta;
    }

    /** Write {@code updates} in chunks, bisecting a chunk refused for its data; see {@link #flush}. */
    private boolean writeBisecting(Counters counters, List<Pulled> updates, long deadlineNanos) {
        var work = new java.util.ArrayDeque<List<Pulled>>();
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
                var statement = new ArrayList<Counters.CounterUpdate>(part.size());
                for (var p : part) statement.add(p.update());
                var counts = counters.incrementBatch(statement, deadlineNanos);
                for (var p : part) {
                    Long n = counts.get(p.first.counterKey);
                    if (n != null) p.record(n);
                }
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
    private void restore(List<Pulled> updates) {
        long restored = 0;
        for (var p : updates) {
            p.first.pending.addAndGet(p.delta);
            enqueue(p.first);
            restored += p.delta;
        }
        unflushed.addAndGet(restored);
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
                map.put("activeWindows", limiter.windows.size() + limiter.overflowWindows.size()
                    + limiter.sharedKeys.size() + limiter.sharedOverflowKeys.size());
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
        windows.values().removeIf(w -> w.expired(sweepMillis));
        overflowWindows.values().removeIf(w -> w.expired(sweepMillis));

        // M17: drop shared-counter entries whose windows have rolled. A counter key names its
        // window slot, so such an entry is never hit again; this sweep is what frees it. Each entry
        // is one self-contained object (L6 final review), so removing it from its table removes
        // everything the limiter holds for the key, apart from a place in the flush queue.
        long nowMillis = now.toEpochMilli();
        sharedKeys.values().removeIf(k -> k.windowEnd <= nowMillis);
        sharedOverflowKeys.values().removeIf(k -> k.windowEnd <= nowMillis);
        purgeFlushQueue(nowMillis);

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

    /**
     * Drop flush-queue entries whose windows have rolled. Without traffic no flush runs, so a
     * quiet limiter would otherwise hold them (fewer than a flush batch) indefinitely. Skipped
     * while a flush is running; the flush drops them itself.
     */
    private void purgeFlushQueue(long nowMillis) {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            for (int left = dirtyCount.get(); left > 0; left--) {
                SharedKey sk = dirty.poll();
                if (sk == null) break;
                if (sk.windowEnd <= nowMillis) {
                    dirtyCount.decrementAndGet();
                    sk.queued.set(false);
                    sk.pending.set(0);
                } else {
                    dirty.add(sk);
                }
            }
        } finally {
            flushing.set(false);
        }
    }

    // Visible for testing
    int windowCount() {
        return windows.size();
    }

    /** The size of this limiter's largest table: each of the four is bounded by the key cap. */
    int trackedEntries() {
        return Math.max(Math.max(windows.size(), overflowWindows.size()),
            Math.max(sharedKeys.size(), sharedOverflowKeys.size()));
    }

    /** Entries waiting in the flush queue; bounded by {@link #dirtyBound}. */
    int queuedForFlush() {
        return dirtyCount.get();
    }

    private class Window {
        final Instant start;
        final int count;
        /** {@link #touchSequence} when the key was last counted, for the eviction sampler's least-recently-used choice. */
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
