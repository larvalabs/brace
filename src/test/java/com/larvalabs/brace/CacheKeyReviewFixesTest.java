package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review fixes for L5 (docs/2026-11-09-security-review-todos.md): {@code deletePrefix} with a
 * prefix that had to be cut cleared a whole namespace, and keys with an unpaired surrogate
 * collided on Postgres. {@link CacheKeyReviewFixesPostgresIT} runs the same checks on Postgres.
 */
class CacheKeyReviewFixesTest {

    /** Shared by the in-memory test and the Postgres IT. */
    static void deletePrefixWithACutPrefixClearsOnlyWhatItMayHaveCovered(Cache cache) {
        cache.set("search:alpha", "a");
        cache.set("search:beta", "b");

        cache.deletePrefix("search:#sha256:");
        assertEquals("a", cache.get("search:alpha", String.class), "a marker in the prefix cleared the namespace");
        cache.deletePrefix("search:\u0001");
        assertEquals("a", cache.get("search:alpha", String.class), "a control character in the prefix cleared the namespace");
        assertEquals("b", cache.get("search:beta", String.class));

        // Keys that do start with such a prefix are still cleared.
        cache.set("search:\u0001x", "c");
        cache.set("search:#sha256:y", "d");
        cache.deletePrefix("search:\u0001");
        assertNull(cache.get("search:\u0001x", String.class));
        cache.deletePrefix("search:#sha256:");
        assertNull(cache.get("search:#sha256:y", String.class));

        // A prefix longer than the kept part: plain keys must start with the whole prefix.
        String common = "p".repeat(Cache.RETAINED_PREFIX_CHARS);
        cache.set(common + "-match-and-more", "e");
        cache.set(common + "-other", "f");
        cache.deletePrefix(common + "-match");
        assertNull(cache.get(common + "-match-and-more", String.class));
        assertEquals("f", cache.get(common + "-other", String.class), "a long prefix cleared a plain key it does not cover");
        assertEquals("a", cache.get("search:alpha", String.class));
    }

    @Test
    void deletePrefixInMemory() {
        var cache = new Cache();
        try {
            deletePrefixWithACutPrefixClearsOnlyWhatItMayHaveCovered(cache);
        } finally {
            cache.close();
        }
    }

    // ---- F11: unpaired surrogates ----

    @Test
    void rateLimitKeysWithAnUnpairedSurrogateAreHashed() {
        var a = RateLimiter.normalizeKey("user\uD800");
        var b = RateLimiter.normalizeKey("user\uD801");
        assertTrue(a.matches("[0-9a-f]{64}"), a);
        assertNotEquals(a, b, "two lone surrogates must not share a bucket");
        assertNotEquals(RateLimiter.normalizeKey("user?"), a);
        assertNotEquals(RateLimiter.normalizeKey("user\uDC00"), a);
        assertEquals("user\uD83D\uDE00", RateLimiter.normalizeKey("user\uD83D\uDE00"), "a proper pair is left alone");
    }

    @Test
    void cacheKeysWithAnUnpairedSurrogateAreHashed() {
        var a = Cache.storageKey("k:\uD800");
        var b = Cache.storageKey("k:\uDBFF");
        assertTrue(a.startsWith("k:" + Cache.HASHED_KEY_MARKER), a);
        assertNotEquals(a, b);
        assertNotEquals(Cache.storageKey("k:?"), a);
        for (var stored : new String[]{a, b, Cache.storageKey("x\uDC00y"), Cache.storageKey("\uD800" + "z".repeat(600))}) {
            for (int i = 0; i < stored.length(); i++) {
                assertFalse(Character.isSurrogate(stored.charAt(i)), "stored key keeps no surrogate: " + stored);
            }
        }
        assertEquals("k:\uD83D\uDE00", Cache.storageKey("k:\uD83D\uDE00"));
        var cache = new Cache();
        try {
            cache.set("k:\uD800", "one");
            cache.set("k:\uD801", "two");
            assertEquals("one", cache.get("k:\uD800", String.class));
            assertEquals("two", cache.get("k:\uD801", String.class));
        } finally {
            cache.close();
        }
    }
}
