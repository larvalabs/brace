package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the per-instance seen-nonce set backing v2 ops auth replay
 * suppression (M3). Time is passed in explicitly, so expiry/sweeping is tested
 * without sleeping.
 */
class OpsAuthNonceCacheTest {

    @Test
    void freshNonceAcceptedOnceThenRejected() {
        var cache = new OpsHandler.NonceCache(120_000, 100);
        assertTrue(cache.checkAndRecord("nonce-a", 1_000));
        assertFalse(cache.checkAndRecord("nonce-a", 2_000), "same nonce within TTL is a replay");
        assertTrue(cache.checkAndRecord("nonce-b", 2_000), "a different nonce is unaffected");
    }

    @Test
    void expiredNoncesAreSweptAndReusable() {
        var cache = new OpsHandler.NonceCache(120_000, 100);
        assertTrue(cache.checkAndRecord("nonce-a", 1_000));
        assertEquals(1, cache.size());
        // Past the TTL the entry is swept on the next call and the nonce is acceptable
        // again (the timestamp freshness check is what bounds the real-world window).
        assertTrue(cache.checkAndRecord("nonce-a", 1_000 + 120_001));
        assertEquals(1, cache.size(), "expired entry should have been swept, not accumulated");
    }

    @Test
    void boundedSizeFailsClosedWhenFull() {
        var cache = new OpsHandler.NonceCache(120_000, 3);
        assertTrue(cache.checkAndRecord("n1", 1_000));
        assertTrue(cache.checkAndRecord("n2", 1_000));
        assertTrue(cache.checkAndRecord("n3", 1_000));
        assertFalse(cache.checkAndRecord("n4", 1_000), "at capacity the cache rejects new nonces");
        assertEquals(3, cache.size());
        // Once existing entries expire, capacity frees up.
        assertTrue(cache.checkAndRecord("n4", 1_000 + 120_001));
        assertEquals(1, cache.size());
    }
}
