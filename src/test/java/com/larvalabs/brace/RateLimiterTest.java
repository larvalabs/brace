package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private Request fakeRequest(String ip) {
        return new Request("GET", "/test", Map.of(), Map.of(),
            Map.of(), null, Map.of(), ip, null);
    }

    private Request fakeRequestWithHeader(String headerName, String headerValue) {
        return new Request("GET", "/test", Map.of(), Map.of(),
            Map.of(headerName, headerValue), null, Map.of(), "1.2.3.4", null);
    }

    @AfterEach
    void resetSharedBackend() {
        RateLimiter.disableSharedBackend();
    }

    @Test
    void requestsUnderLimitAreAllowed() {
        var limiter = RateLimiter.perIp(5, "1m");
        var req = fakeRequest("10.0.0.1");
        for (int i = 0; i < 5; i++) {
            assertNull(limiter.handle(req), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void requestsOverLimitReturn429() {
        var limiter = RateLimiter.perIp(3, "1m");
        var req = fakeRequest("10.0.0.2");
        for (int i = 0; i < 3; i++) {
            assertNull(limiter.handle(req));
        }
        var result = limiter.handle(req);
        assertNotNull(result);
        assertEquals(429, result.status());
        assertEquals("Too Many Requests", result.body());
    }

    @Test
    void retryAfterHeaderIsPresent() {
        var limiter = RateLimiter.perIp(1, "1m");
        var req = fakeRequest("10.0.0.3");
        assertNull(limiter.handle(req));
        var result = limiter.handle(req);
        assertNotNull(result);
        assertEquals(429, result.status());
        var retryAfter = result.header("Retry-After");
        assertNotNull(retryAfter, "Retry-After header should be present");
        assertTrue(Integer.parseInt(retryAfter) > 0, "Retry-After should be positive");
    }

    @Test
    void differentIpsHaveSeparateLimits() {
        var limiter = RateLimiter.perIp(2, "1m");
        var req1 = fakeRequest("10.0.0.10");
        var req2 = fakeRequest("10.0.0.11");

        // Both IPs can make 2 requests
        assertNull(limiter.handle(req1));
        assertNull(limiter.handle(req1));
        assertNull(limiter.handle(req2));
        assertNull(limiter.handle(req2));

        // Both IPs are now at limit
        assertNotNull(limiter.handle(req1));
        assertNotNull(limiter.handle(req2));
    }

    @Test
    void perKeyWithCustomKeyFunction() {
        var limiter = RateLimiter.perKey(req -> req.header("Authorization"), 2, "1m");

        var reqA = fakeRequestWithHeader("Authorization", "token-A");
        var reqB = fakeRequestWithHeader("Authorization", "token-B");

        assertNull(limiter.handle(reqA));
        assertNull(limiter.handle(reqA));
        // token-A is at limit
        assertNotNull(limiter.handle(reqA));
        // token-B still has quota
        assertNull(limiter.handle(reqB));
    }

    // ---- M4 / D2: a missing key is limited by default; the exemption is opt-in ----

    /**
     * Null key → counted in one shared missing-key bucket (M4). Before the fix a request without
     * the key header skipped the limiter entirely, so the documented API limiter
     * {@code perKey(req -> req.header("Authorization"), …)} admitted unlimited anonymous traffic.
     */
    @Test
    void nullKeyIsLimitedByDefault() {
        var limiter = RateLimiter.perKey(req -> req.header("X-Missing"), 2, "1m");
        var req = fakeRequest("10.0.0.4");
        assertNull(limiter.handle(req));
        assertNull(limiter.handle(req));
        var blocked = limiter.handle(req);
        assertNotNull(blocked, "a missing key must not bypass the limiter");
        assertEquals(429, blocked.status());
    }

    /** Blank and null keys share the one missing-key bucket. */
    @Test
    void blankAndNullKeysShareOneBucket() {
        var calls = new AtomicInteger();
        var limiter = RateLimiter.perKey(req -> calls.getAndIncrement() % 2 == 0 ? null : " \t ", 2, "1m");
        var req = fakeRequest("10.0.0.5");
        assertNull(limiter.handle(req)); // null
        assertNull(limiter.handle(req)); // blank
        assertNotNull(limiter.handle(req), "null and blank keys count against the same bucket");
    }

    /** {@code exemptMissingKey()} restores the old pass-through for null and blank keys. */
    @Test
    void exemptMissingKeyOptInPassesNullAndBlankKeys() {
        var limiter = RateLimiter.perKey(req -> req.header("X-Missing"), 1, "1m").exemptMissingKey();
        var req = fakeRequest("10.0.0.6");
        for (int i = 0; i < 10; i++) {
            assertNull(limiter.handle(req), "opted-in limiter exempts a missing key");
        }
        var blank = RateLimiter.perKey(r -> "  ", 1, "1m").exemptMissingKey();
        for (int i = 0; i < 5; i++) {
            assertNull(blank.handle(req));
        }
        // A present key is still limited on an opted-in limiter.
        var keyed = RateLimiter.perKey(r -> r.header("X-User"), 1, "1m").exemptMissingKey();
        var alice = fakeRequestWithHeader("X-User", "alice");
        assertNull(keyed.handle(alice));
        assertNotNull(keyed.handle(alice));
    }

    /**
     * A client cannot reach the missing-key bucket by sending a key that spells its label: neither
     * draining it for everyone else nor riding on it.
     */
    @Test
    void clientSentLiteralCannotTouchMissingKeyBucket() {
        for (var literal : new String[]{"(none)", " (none)", "(none) ", RateLimiter.MISSING_KEY}) {
            var limiter = RateLimiter.perKey(req -> req.header("X-User"), 1, "1m");
            var attacker = fakeRequestWithHeader("X-User", literal);
            var anonymous = fakeRequest("10.0.0.7");

            assertNull(limiter.handle(attacker), "literal '" + literal + "' gets its own bucket");
            assertNotNull(limiter.handle(attacker));
            assertNull(limiter.handle(anonymous),
                "literal '" + literal + "' must not have drained the missing-key bucket");
            assertNotNull(limiter.handle(anonymous));
        }
    }

    /** Keys are stripped, so a trailing space is not a fresh bucket. Case is left to the extractor. */
    @Test
    void keysAreStrippedButNotCaseFolded() {
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 1, "1m");
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "victim")));
        assertNotNull(limiter.handle(fakeRequestWithHeader("X-User", " victim ")),
            "' victim ' must share the 'victim' bucket");
        // Tokens are case-sensitive: the framework does not fold case.
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "Victim")));
    }

    // ---- Key normalization (M7a) ----

    @Test
    void normalizeKey_nullReturnsNull() {
        // null/blank keys are exempted by check() before normalizeKey() is called;
        // normalizeKey handles them defensively by returning null.
        assertNull(RateLimiter.normalizeKey(null));
    }

    @Test
    void normalizeKey_blankReturnsNull() {
        assertNull(RateLimiter.normalizeKey("   "));
    }

    @Test
    void normalizeKey_shortKeyPassesThrough() {
        var key = "user:42";
        assertEquals(key, RateLimiter.normalizeKey(key));
    }

    @Test
    void normalizeKey_exactlyMaxLengthPassesThrough() {
        var key = "x".repeat(RateLimiter.MAX_KEY_LENGTH);
        assertEquals(key, RateLimiter.normalizeKey(key));
    }

    @Test
    void normalizeKey_longKeyIsHashed() {
        // A key longer than MAX_KEY_LENGTH must produce a fixed-length (64 char) hex digest.
        var longKey = "a".repeat(RateLimiter.MAX_KEY_LENGTH + 1);
        var normalized = RateLimiter.normalizeKey(longKey);
        assertNotEquals(longKey, normalized);
        assertEquals(64, normalized.length(), "SHA-256 hex digest is always 64 chars");
        assertTrue(normalized.matches("[0-9a-f]{64}"), "must be hex");
    }

    @Test
    void normalizeKey_sameLongKeyProducesSameHash() {
        // Rate limiting must be idempotent: same long key → same bucket across requests.
        var longKey = "Bearer " + "x".repeat(200);
        assertEquals(RateLimiter.normalizeKey(longKey), RateLimiter.normalizeKey(longKey));
    }

    @Test
    void normalizeKey_differentLongKeysDontCollide() {
        // Two distinct long keys must not map to the same bucket.
        var keyA = "a".repeat(100);
        var keyB = "b".repeat(100);
        assertNotEquals(RateLimiter.normalizeKey(keyA), RateLimiter.normalizeKey(keyB));
    }

    /**
     * End-to-end: a long key (>64 chars) is still rate-limited — the hash produces a consistent
     * bucket so the limit is enforced correctly across multiple requests with the same long key.
     */
    @Test
    void longKeyIsRateLimited() {
        var longToken = "Bearer " + "x".repeat(200); // well over 64 chars
        var limiter = RateLimiter.perKey(req -> req.header("Authorization"), 2, "1m");
        var req = fakeRequestWithHeader("Authorization", longToken);

        assertNull(limiter.handle(req));
        assertNull(limiter.handle(req));
        var result = limiter.handle(req);
        assertNotNull(result, "long key must still be rate-limited (consistent hash bucket)");
        assertEquals(429, result.status());
    }

    // ---- DB-failure fallback (M7b) ----

    /**
     * A broken shared-counter backend must not propagate as a 500. The limiter should fall back
     * to per-instance counting so the request is still served (and still locally limited).
     */
    @Test
    void sharedBackendFailureFallsBackToLocal() {
        // Install a Counters stub whose batch flush always throws, simulating a DB outage.
        var broken = new Counters(null) {
            @Override
            java.util.Map<String, Long> incrementBatch(java.util.List<CounterUpdate> updates) {
                throw new RuntimeException("simulated DB outage");
            }
        };
        RateLimiter.useSharedBackend(broken);

        var limiter = RateLimiter.perKey(req -> "user-1", 3, "1m");
        var req = fakeRequest("10.0.0.1");

        // Requests are still served (no 500) and locally limited to 3.
        assertNull(limiter.handle(req));
        assertNull(limiter.handle(req));
        assertNull(limiter.handle(req));
        var result = limiter.handle(req);
        assertNotNull(result, "local fallback must still enforce the limit");
        assertEquals(429, result.status());
    }

    /**
     * After a DB failure the local fallback correctly limits; a *different* long key on the same
     * limiter gets its own bucket under local counting too (collision sanity check under fallback).
     */
    @Test
    void sharedBackendFailureLocalFallbackIsolatesKeys() {
        var broken = new Counters(null) {
            @Override
            java.util.Map<String, Long> incrementBatch(java.util.List<CounterUpdate> updates) {
                throw new RuntimeException("simulated DB outage");
            }
        };
        RateLimiter.useSharedBackend(broken);

        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 1, "1m");
        var reqA = fakeRequestWithHeader("X-User", "alice");
        var reqB = fakeRequestWithHeader("X-User", "bob");

        // Each user gets their own budget of 1 under local fallback.
        assertNull(limiter.handle(reqA));
        assertNull(limiter.handle(reqB));
        assertNotNull(limiter.handle(reqA), "alice's local bucket exhausted");
        assertNotNull(limiter.handle(reqB), "bob's local bucket exhausted");
    }

    // ---- Batched shared backend (M17) ----

    /**
     * In-memory stand-in for the DB-backed {@link Counters}: applies batches atomically and counts
     * how many flush round trips and key writes happened, so a test can assert the rate limiter's
     * DB traffic without a database. Ignores expiry (single-window tests).
     */
    static class CountingCounters extends Counters {
        final AtomicInteger flushCalls = new AtomicInteger();
        final AtomicInteger keysWritten = new AtomicInteger();
        final Map<String, Long> store = new ConcurrentHashMap<>();

        CountingCounters() { super(null); }

        @Override
        synchronized Map<String, Long> incrementBatch(List<CounterUpdate> updates) {
            flushCalls.incrementAndGet();
            var result = new HashMap<String, Long>();
            for (var u : updates) {
                keysWritten.incrementAndGet();
                long v = store.merge(u.key(), u.delta(), Long::sum);
                result.put(u.key(), v);
            }
            return result;
        }
    }

    /**
     * The batched shared path still enforces the limit: with a divisor that makes the limiter flush
     * every request (small limit), enforcement is exact — N allowed, the next blocked.
     */
    @Test
    void sharedBackendEnforcesLimit() {
        RateLimiter.useSharedBackend(new CountingCounters());
        var limiter = RateLimiter.perIp(5, "1m"); // 5/10 → flushThreshold 1 → flush every request
        var req = fakeRequest("7.7.7.7");
        for (int i = 0; i < 5; i++) {
            assertNull(limiter.handle(req), "request " + (i + 1) + " should be allowed");
        }
        var blocked = limiter.handle(req);
        assertNotNull(blocked, "6th request is over the shared limit");
        assertEquals(429, blocked.status());
    }

    /**
     * Once a key is over the limit, the negative cache serves it locally — no further DB writes for
     * that key until the window rolls. This is the abusive-client load the design sheds.
     */
    @Test
    void negativeCacheStopsDbWritesAfterBlock() {
        var counters = new CountingCounters();
        RateLimiter.useSharedBackend(counters);
        var limiter = RateLimiter.perIp(3, "1m");
        var req = fakeRequest("6.6.6.6");

        // Drive it past the limit, then keep hammering.
        Result firstBlock = null;
        int blockedAtFlushCount = -1;
        for (int i = 0; i < 50; i++) {
            var r = limiter.handle(req);
            if (r != null && firstBlock == null) {
                firstBlock = r;
                blockedAtFlushCount = counters.flushCalls.get();
            }
        }
        assertNotNull(firstBlock, "the client must eventually be blocked");
        assertEquals(429, firstBlock.status());
        // No flush (DB write) happened after the block was armed: the negative cache short-circuits.
        assertEquals(blockedAtFlushCount, counters.flushCalls.get(),
            "negative cache must do zero DB writes once the key is blocked");
    }

    /**
     * DB writes are bounded by requests / flushThreshold regardless of key cardinality — even when
     * every request is a distinct key (the adversarial case for per-key batching). With limit 100
     * (divisor 10 → flushThreshold 10), 100 distinct-key requests cost ~10 batched flushes, not 100.
     */
    @Test
    void batchingBoundsDbWritesAcrossDistinctKeys() {
        var counters = new CountingCounters();
        RateLimiter.useSharedBackend(counters);
        var limiter = RateLimiter.perKey(req -> req.header("X-Key"), 100, "1m");

        for (int i = 0; i < 100; i++) {
            assertNull(limiter.handle(fakeRequestWithHeader("X-Key", "key-" + i)),
                "each distinct key is well under the limit");
        }
        // 100 requests, flushThreshold 10 → ~10 flush round trips, every key still counted once.
        assertEquals(10, counters.flushCalls.get(), "DB writes bounded by requests/flushThreshold");
        assertEquals(100, counters.keysWritten.get(), "every key is counted exactly once");
    }

    // ---- L5: control characters in keys; a failing counter batch ----

    @Test
    void normalizeKey_controlCharactersAreHashed() {
        for (var raw : new String[]{"a\u0000", "a\u0001b", "a\u001fb", "a\u007fb", "a\u0085b", "\u0000"}) {
            var normalized = RateLimiter.normalizeKey(raw);
            assertTrue(normalized.matches("[0-9a-f]{64}"), "control character in '" + raw.replace("\u0000", "\\0")
                + "' must be hashed, got " + normalized);
        }
        assertNotEquals(RateLimiter.normalizeKey("a\u0000"), RateLimiter.normalizeKey("a\u0001"));
        assertNotEquals(RateLimiter.normalizeKey("a\u0000"), RateLimiter.normalizeKey("a"));
        // Ordinary printable keys, non-ASCII included, are untouched.
        assertEquals("josé@example.com", RateLimiter.normalizeKey("josé@example.com"));
    }

    /**
     * Counters stand-in that refuses (as Postgres refuses a NUL or an oversized key) any statement
     * containing a key with {@code poison} in it, with a data-exception SQLState, and can be put in
     * an outage that fails every statement with a connection SQLState.
     */
    static class PickyCounters extends CountingCounters {
        volatile boolean outage;

        @Override
        synchronized Map<String, Long> incrementBatch(List<CounterUpdate> updates) {
            if (outage) {
                throw new RuntimeException(new java.sql.SQLException("connection refused", "08001"));
            }
            for (var u : updates) {
                if (u.key().contains("poison")) {
                    throw new RuntimeException(new java.sql.SQLException(
                        "Zero bytes may not occur in string parameters.", "22023"));
                }
            }
            return super.incrementBatch(updates);
        }
    }

    /**
     * Before the fix one refused key failed the whole batch, every delta was restored, and the key
     * kept every later flush failing until its window rolled: no innocent key reached the shared
     * counter, so the fleet limit became limit × instances.
     */
    @Test
    void refusedKeyIsDiscardedInsteadOfPoisoningEveryFlush() {
        var counters = new PickyCounters();
        RateLimiter.useSharedBackend(counters);
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 5, "1m"); // flush every request

        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "poison")));
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "alice")));
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "bob")));

        assertTrue(counters.store.keySet().stream().anyMatch(k -> k.contains(":alice:")),
            "alice's count must reach the shared counter despite the refused key");
        assertTrue(counters.store.keySet().stream().anyMatch(k -> k.contains(":bob:")),
            "the refused key must not be restored into the next flush");
    }

    /** A whole-database outage still restores every delta (nothing is discarded) and retries later. */
    @Test
    void outageRestoresCountsRatherThanDiscardingThem() throws Exception {
        var counters = new PickyCounters();
        RateLimiter.useSharedBackend(counters);
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 5, "1m");
        var alice = fakeRequestWithHeader("X-User", "alice");

        counters.outage = true;
        assertNull(limiter.handle(alice));
        assertNull(limiter.handle(alice));
        assertTrue(counters.store.isEmpty());

        counters.outage = false;
        Thread.sleep(1_100); // past the flush retry cooldown
        assertNull(limiter.handle(alice));
        assertEquals(3L, counters.store.values().stream().mapToLong(Long::longValue).sum(),
            "the counts buffered during the outage were kept and flushed once it ended");
    }

    // ---- L6: IPv6 /64 bucketing; a cap on distinct keys ----

    @Test
    void ipBucketKeysIpv6OnItsSlash64() {
        assertEquals(RateLimiter.ipBucket("2001:db8:1:2:3:4:5:6"), RateLimiter.ipBucket("2001:db8:1:2:ffff::1"));
        assertEquals("2001:db8:1:2::/64", RateLimiter.ipBucket("2001:0db8:0001:0002:aaaa:bbbb:cccc:dddd"));
        assertNotEquals(RateLimiter.ipBucket("2001:db8:1:2::1"), RateLimiter.ipBucket("2001:db8:1:3::1"));
        // IPv4 and IPv4-mapped IPv6 are one client, keyed on the full IPv4 address.
        assertEquals("203.0.113.7", RateLimiter.ipBucket("203.0.113.7"));
        assertEquals("203.0.113.7", RateLimiter.ipBucket("::ffff:203.0.113.7"));
        assertNotEquals(RateLimiter.ipBucket("203.0.113.7"), RateLimiter.ipBucket("203.0.113.8"));
        // Not an address literal: used as is, and never resolved.
        assertEquals("unknown", RateLimiter.ipBucket("unknown"));
        assertEquals("zz:not-an-address", RateLimiter.ipBucket("zz:not-an-address"));
        assertEquals("example.com", RateLimiter.ipBucket("example.com"));
    }

    @Test
    void perIpRotatingWithinASlash64SharesOneBucket() {
        var limiter = RateLimiter.perIp(2, "1m");
        assertNull(limiter.handle(fakeRequest("2001:db8:aa:bb::1")));
        assertNull(limiter.handle(fakeRequest("2001:db8:aa:bb::2")));
        var blocked = limiter.handle(fakeRequest("2001:db8:aa:bb:dead:beef:0:3"));
        assertNotNull(blocked, "a client rotating source addresses inside its /64 is one client");
        assertEquals(429, blocked.status());
        assertNull(limiter.handle(fakeRequest("2001:db8:aa:bc::1")), "the next /64 is someone else");
    }

    @Test
    void distinctKeysPastTheCapShareOneOverflowBucket() {
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 2, "1m").maxKeys(3);
        for (var user : new String[]{"a", "b", "c"}) {
            assertNull(limiter.handle(fakeRequestWithHeader("X-User", user)));
        }
        // Past the cap: d, e, f are new keys and fold into one overflow bucket of 2.
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "d")));
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "e")));
        assertNotNull(limiter.handle(fakeRequestWithHeader("X-User", "f")),
            "a key flood is throttled as one bucket");
        // Existing identities keep their own counts: a has used 1 of 2.
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "a")), "existing key keeps its own budget");
        assertNotNull(limiter.handle(fakeRequestWithHeader("X-User", "a")));
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "b")));
        assertEquals(4, limiter.windowCount(), "state stays bounded: 3 keys + overflow");
    }

    @Test
    void sharedModeAlsoCapsDistinctKeys() {
        var counters = new CountingCounters();
        RateLimiter.useSharedBackend(counters);
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 2, "1m").maxKeys(2); // flush every request
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "a")));
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "b")));
        for (int i = 0; i < 2; i++) {
            assertNull(limiter.handle(fakeRequestWithHeader("X-User", "flood-" + i)));
        }
        assertNotNull(limiter.handle(fakeRequestWithHeader("X-User", "flood-99")));
        assertNull(limiter.handle(fakeRequestWithHeader("X-User", "a")), "a keeps its own budget");
        assertTrue(counters.store.size() <= 3, "shared counter rows bounded: " + counters.store.keySet());
    }

    @Test
    void defaultKeyCapIsGenerousAndValidated() {
        assertEquals(100_000, RateLimiter.DEFAULT_MAX_KEYS);
        assertThrows(IllegalArgumentException.class, () -> RateLimiter.perKey(req -> "k", 1, "1m").maxKeys(0));
        assertThrows(IllegalArgumentException.class, () -> RateLimiter.perIp(1, "1m").maxKeys(-1));
    }

    // Integration test with a real Brace app

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0);
        app.before("/limited", RateLimiter.perIp(3, "1m"));
        app.get("/limited", req -> Result.text("ok"));
        app.get("/unlimited", req -> Result.text("ok"));

        // The documented login setup (docs/SECURITY.md "Rate Limiting"): count submissions only,
        // one bucket per normalized username.
        var loginLimit = RateLimiter.perKey(req -> {
            var username = req.formParam("username");
            return username == null ? null : username.strip().toLowerCase(java.util.Locale.ROOT);
        }, 2, "1m");
        app.before("/login", req -> "POST".equals(req.method()) ? loginLimit.handle(req) : null);
        app.get("/login", req -> Result.text("login page"));
        app.post("/login", req -> {
            var form = req.form(LoginForm.class);
            return Result.text("username=" + form.value().username());
        }).csrf(false);
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void integrationRateLimitReturns429() throws Exception {
        // First 3 requests should succeed
        for (int i = 0; i < 3; i++) {
            var response = get("/limited");
            assertEquals(200, response.statusCode(), "Request " + (i + 1) + " should succeed");
        }
        // 4th request should be rate limited
        var response = get("/limited");
        assertEquals(429, response.statusCode());
        assertTrue(response.headers().firstValue("Retry-After").isPresent());
    }

    @Test
    void integrationUnlimitedEndpointNotAffected() throws Exception {
        for (int i = 0; i < 10; i++) {
            var response = get("/unlimited");
            assertEquals(200, response.statusCode());
        }
    }

    public record LoginForm(String username, String password) {}

    private HttpResponse<String> postForm(String path, String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * M4 repro: {@code POST /login?username=victim} with only {@code password=} in the body. Before
     * the fix the limiter saw no username (body only) and skipped, while {@code req.form()} bound
     * {@code username=victim} from the query: unlimited guesses against the victim's account.
     */
    @Test
    void documentedLoginSetupLimitsUsernameInQueryString() throws Exception {
        for (int i = 0; i < 2; i++) {
            var r = postForm("/login?username=victim", "password=guess" + i);
            assertEquals(200, r.statusCode());
            assertEquals("username=null", r.body(), "the handler must not bind username from the query");
        }
        assertEquals(429, postForm("/login?username=victim", "password=guess3").statusCode(),
            "a submission without a username is counted, not exempt");
        // The login page itself is not counted.
        for (int i = 0; i < 5; i++) {
            assertEquals(200, get("/login").statusCode());
        }
        // A normalized username has its own bucket: case and padding variants share it.
        assertEquals(200, postForm("/login", "username=Alice&password=a").statusCode());
        assertEquals(200, postForm("/login", "username=+alice+&password=b").statusCode());
        assertEquals(429, postForm("/login", "username=ALICE&password=c").statusCode());
    }

    @Test
    void perIpWithoutTrustedProxiesTriggersStartupWarning() {
        var perIp = List.of(new Middleware.BoundBefore(null, RateLimiter.perIp(5, "1m")));
        assertTrue(Brace.perIpLimiterWithoutTrustedProxies(perIp, null));
        // Configured proxies → no warning
        assertFalse(Brace.perIpLimiterWithoutTrustedProxies(perIp, new TrustedProxies("127.0.0.1")));
        // Non-limiter middleware → no warning
        var plain = List.of(new Middleware.BoundBefore(null, req -> null));
        assertFalse(Brace.perIpLimiterWithoutTrustedProxies(plain, null));
        // perKey limiters don't key on IP → no warning
        var perKey = List.of(new Middleware.BoundBefore(null, RateLimiter.perKey(req -> "k", 5, "1m")));
        assertFalse(Brace.perIpLimiterWithoutTrustedProxies(perKey, null));
    }
}
