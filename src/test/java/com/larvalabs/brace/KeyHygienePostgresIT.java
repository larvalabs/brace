package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L5 on real Postgres: request-controlled text in a rate-limit key or a cache key must not break
 * the shared stores. pgjdbc refuses a string parameter containing a NUL ("Zero bytes may not occur
 * in string parameters") and a btree entry over ~2.7 KB is refused on insert; H2 accepts both, so
 * only this tier can see either failure.
 */
class KeyHygienePostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;

    @BeforeAll
    static void buildFactory() {
        dbFactory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void closeFactory() {
        if (dbFactory != null) dbFactory.close();
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("brace_counters", "brace_cache", "brace_cache_counters");
    }

    @AfterEach
    void removeBackend() {
        RateLimiter.disableSharedBackend();
    }

    private static Request withUser(String user) {
        return new Request("POST", "/login", Map.of(), Map.of(), Map.of("X-User", user), null, Map.of(),
                "10.0.0.1", null);
    }

    private static long counterRowsMatching(String fragment) throws Exception {
        try (var c = connect();
             var ps = c.prepareStatement("SELECT coalesce(sum(n), 0) FROM brace_counters WHERE counter_key LIKE ?")) {
            ps.setString(1, "%" + fragment + "%");
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long counterRows() throws Exception {
        try (var c = connect(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM brace_counters")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * The repro: {@code username=a%00} yields a key with a NUL. Before the fix the multi-row counter
     * statement was refused, every delta (the innocent keys' too) was restored, and the poison key
     * kept the next flushes failing, so the fleet stopped sharing counts.
     */
    @Test
    void nulInRateLimitKeyDoesNotPoisonSharedCounting() throws Exception {
        RateLimiter.useSharedBackend(new Counters(dbFactory));
        var limiter = RateLimiter.perKey(req -> req.header("X-User"), 5, "1m"); // flushes every request

        assertNull(limiter.handle(withUser("a\u0000")));
        assertNull(limiter.handle(withUser("alice")));

        assertEquals(1, counterRowsMatching("alice"), "alice's increment must reach the shared counter");
        assertEquals(2, counterRows(), "the NUL key is stored too (hashed), not left to fail every flush");
    }

    /**
     * The limiter's per-key retry discards a key only for a data error, and restores on an outage.
     * Check that real pgjdbc's refusal of a raw NUL key classifies as a data error, not an outage.
     */
    @Test
    void rawNulCounterKeyIsClassifiedAsDataErrorNotOutage() {
        var counters = new Counters(dbFactory);
        var expires = java.time.Instant.now().plusSeconds(60);
        var e = assertThrows(RuntimeException.class, () -> counters.incrementBatch(List.of(
                new Counters.CounterUpdate("ok", 1, expires),
                new Counters.CounterUpdate("bad\u0000", 1, expires))));
        assertTrue(Counters.isDataError(e), "NUL refusal is a data error: " + e);
        assertFalse(Counters.isOutage(e), "NUL refusal is not an outage: " + e);
    }

    @Test
    void controlCharacterKeysStillCountFleetWide() {
        RateLimiter.useSharedBackend(new Counters(dbFactory));
        // Two "instances" of the same logical limiter.
        var a = RateLimiter.perKey(req -> req.header("X-User"), 3, "1m");
        var b = RateLimiter.perKey(req -> req.header("X-User"), 3, "1m");
        int allowed = 0;
        for (int i = 0; i < 6; i++) {
            if ((i % 2 == 0 ? a : b).handle(withUser("x\u0000y")) == null) allowed++;
        }
        assertEquals(3, allowed, "a key with a NUL is limited once across the fleet");
    }

    private static String incompressible(int length) {
        var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        var random = new Random(42);
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }

    @Test
    void cacheKeyWithNulWorksAndIsDistinct() {
        var cache = new Cache(new PostgresBackend(dbFactory));
        try {
            cache.set("user:a\u0000b", "nul", "1h");
            cache.set("user:a", "plain", "1h");
            assertEquals("nul", cache.get("user:a\u0000b", String.class));
            assertEquals("plain", cache.get("user:a", String.class));
            assertEquals("computed", cache.getOrSet("q:\u0000", "1h", () -> "computed"));
            assertEquals(1, cache.incr("n:\u0000"));
            cache.delete("user:a\u0000b");
            assertNull(cache.get("user:a\u0000b", String.class));
        } finally {
            cache.close();
        }
    }

    @Test
    void oversizedCacheKeyWorks() {
        var cache = new Cache(new PostgresBackend(dbFactory));
        try {
            var key = "page:GET:/posts/" + incompressible(20_000);
            cache.set(key, "big", "1h");
            assertEquals("big", cache.get(key, String.class));
            assertNull(cache.get(key + "x", String.class), "a different long key is a different entry");
        } finally {
            cache.close();
        }
    }

    @Test
    void hashedKeysAreStillInvalidatedByTagAndPrefix() {
        var cache = new Cache(new PostgresBackend(dbFactory));
        try {
            var longKey = "posts:" + incompressible(5_000);
            var nulKey = "posts:\u0000";
            cache.set(longKey, "long", "1h", "posts");
            cache.set(nulKey, "nul", "1h", "posts");
            cache.clearTag("posts");
            assertNull(cache.get(longKey, String.class), "tag invalidation reaches a hashed long key");
            assertNull(cache.get(nulKey, String.class), "tag invalidation reaches a hashed NUL key");

            cache.set(longKey, "long", "1h");
            cache.set(nulKey, "nul", "1h");
            cache.set("other:" + incompressible(5_000), "keep", "1h");
            cache.deletePrefix("posts:");
            assertNull(cache.get(longKey, String.class), "a prefix clear reaches a hashed long key");
            assertNull(cache.get(nulKey, String.class), "a prefix clear reaches a hashed NUL key");
            assertEquals(1, cache.size(), "the other prefix survives");
        } finally {
            cache.close();
        }
    }
}
