package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L16 (2026-11 security review): the ops-auth nonce cache is lost on restart, so a request captured
 * before a deploy replayed within its +-30 s window; one key could fill the shared budget and
 * block every other operator; and every call swept the whole map.
 */
class OpsAuthReplayHardeningTest {

    private static final String ORIGIN = "https://ops.example.com";
    private final OpsKeys.Keypair key = OpsKeys.generateKeypair();

    private OpsHandler handler() {
        var authorized = new LinkedHashMap<String, OpsScope>();
        authorized.put(key.publicKey(), OpsScope.CONTROL);
        var h = new OpsHandler(new Stats(), null, null, new Router(), authorized,
            OpsToken.deriveSecret("a-session-secret-at-least-32-chars-long"));
        h.setPinnedOrigins(List.of(ORIGIN));
        return h;
    }

    private Request signed(Instant timestamp) {
        String ts = timestamp.toString();
        byte[] n = new byte[16];
        new java.security.SecureRandom().nextBytes(n);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        String sig = OpsKeys.sign(OpsKeys.v3AuthMessage(ORIGIN, key.publicKey(), ts, nonce), key.privateKey());
        String body = "{\"v\":\"3\",\"origin\":\"" + ORIGIN + "\",\"publicKey\":\"" + key.publicKey()
            + "\",\"timestamp\":\"" + ts + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + sig + "\"}";
        return new Request("POST", "/ops/auth", Map.of(), Map.of(),
            Map.of("Content-Type", "application/json", "Host", "ops.example.com"), body);
    }

    @Test
    void aTimestampFromBeforeThisProcessStartedIsRefused() throws Exception {
        var h = handler();
        Thread.sleep(20);
        // Inside the +-30 s window, but issued before this process existed: a request captured
        // before the restart, whose nonce this process cannot remember.
        var replay = h.auth(signed(Instant.now().minusSeconds(5)));
        assertEquals(401, replay.status());
        assertTrue(String.valueOf(replay.body()).contains("before"), String.valueOf(replay.body()));
        assertEquals(200, h.auth(signed(Instant.now())).status());
    }

    @Test
    void oneKeyFillingItsQuotaDoesNotBlockAnotherKey() {
        var cache = new OpsHandler.NonceCache(120_000, 100, 10);
        for (int i = 0; i < 10; i++) assertTrue(cache.checkAndRecord("keyA", "a" + i, 1_000));
        assertFalse(cache.checkAndRecord("keyA", "a-extra", 1_000), "key A is at its own quota");
        assertTrue(cache.checkAndRecord("keyB", "b0", 1_000), "key B is unaffected");
        assertFalse(cache.checkAndRecord("keyB", "b0", 1_000), "a replay is still refused");
    }

    @Test
    void theOverallCapStillFailsClosed() {
        var cache = new OpsHandler.NonceCache(120_000, 3, 2);
        assertTrue(cache.checkAndRecord("a", "1", 1_000));
        assertTrue(cache.checkAndRecord("a", "2", 1_000));
        assertTrue(cache.checkAndRecord("b", "3", 1_000));
        assertFalse(cache.checkAndRecord("c", "4", 1_000));
    }

    @Test
    void expiredNoncesAreStillEvictedAndFreeTheirKeysQuota() {
        var cache = new OpsHandler.NonceCache(120_000, 100, 2);
        assertTrue(cache.checkAndRecord("a", "1", 1_000));
        assertTrue(cache.checkAndRecord("a", "2", 1_000));
        assertFalse(cache.checkAndRecord("a", "3", 1_000));
        assertTrue(cache.checkAndRecord("a", "3", 1_000 + 120_001), "quota freed once the entries expire");
        assertEquals(1, cache.size());
    }

    @Test
    void sweepsAreAmortizedNotRunOnEveryCall() {
        var cache = new OpsHandler.NonceCache(10, 1000, 1000);
        assertTrue(cache.checkAndRecord("a", "1", 5_000));
        // Expired at 5_011, but a second call 1 ms later does not sweep again yet...
        assertTrue(cache.checkAndRecord("a", "2", 5_012));
        assertEquals(2, cache.size(), "no full sweep on every call");
        // ...the next sweep, a second on, evicts both.
        assertTrue(cache.checkAndRecord("a", "3", 7_000));
        assertEquals(1, cache.size());
    }
}
