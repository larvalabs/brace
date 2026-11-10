package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final review fixes for L16 (docs/2026-11-09-security-review-todos.md): replay of an ops auth
 * request after a restart, where the start-time floor sits, and the nonce cache's work per call.
 */
class OpsAuthFinalReviewTest {

    private static final String ORIGIN = "https://ops.example.com";
    private static DatabaseFactory dbFactory;
    private final OpsKeys.Keypair key = OpsKeys.generateKeypair();

    @BeforeAll
    static void database() {
        dbFactory = new DatabaseFactory("jdbc:h2:mem:opsnoncedb;DB_CLOSE_DELAY=-1", null, null, List.of());
    }

    @AfterAll
    static void close() {
        dbFactory.close();
    }

    private OpsHandler handler() {
        var authorized = new LinkedHashMap<String, OpsScope>();
        authorized.put(key.publicKey(), OpsScope.CONTROL);
        var h = new OpsHandler(new Stats(), null, null, new Router(), authorized,
            OpsToken.deriveSecret("a-session-secret-at-least-32-chars-long"));
        h.setPinnedOrigins(List.of(ORIGIN));
        return h;
    }

    private String body(Instant timestamp, String signatureOverride) {
        String ts = timestamp.toString();
        byte[] n = new byte[16];
        new java.security.SecureRandom().nextBytes(n);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        String sig = signatureOverride != null ? signatureOverride
            : OpsKeys.sign(OpsKeys.v3AuthMessage(ORIGIN, key.publicKey(), ts, nonce), key.privateKey());
        return "{\"v\":\"3\",\"origin\":\"" + ORIGIN + "\",\"publicKey\":\"" + key.publicKey()
            + "\",\"timestamp\":\"" + ts + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + sig + "\"}";
    }

    private static Request post(String body) {
        return new Request("POST", "/ops/auth", Map.of(), Map.of(),
            Map.of("Content-Type", "application/json", "Host", "ops.example.com"), body);
    }

    // ---- L-B: a request the previous process accepted is not accepted by the next ----

    /** A client clock running ahead: timestamp now+25 s was after the new process's start. */
    @Test
    void replayAfterRestartIsRefusedEvenWhenTheClientClockRunsAhead() throws Exception {
        String captured = body(Instant.now().plusSeconds(25), null);
        var longRunning = handler();
        longRunning.setStartedAtForTest(System.currentTimeMillis() - 3_600_000);
        assertEquals(200, longRunning.auth(post(captured)).status(), "accepted before the restart");
        Thread.sleep(20);
        var restarted = handler();
        var replay = restarted.auth(post(captured));
        assertEquals(401, replay.status(), String.valueOf(replay.body()));
    }

    @Test
    void withoutADurableStoreLoginsWaitOutTheWindowAfterAStart() {
        var h = handler();
        var early = h.auth(post(body(Instant.now(), null)));
        assertEquals(401, early.status());
        String message = String.valueOf(early.body());
        assertTrue(message.contains("restart"), message);
        assertTrue(message.contains("clock"), message);
        assertTrue(message.toLowerCase().contains("retry") || message.toLowerCase().contains("again"), message);
        // Once the window has passed (here: a start time moved back), the same kind of request is fine.
        h.setStartedAtForTest(System.currentTimeMillis() - 61_000);
        assertEquals(200, h.auth(post(body(Instant.now(), null))).status());
        assertEquals(200, h.auth(post(body(Instant.now().minusSeconds(20), null))).status(), "a clock running behind");
    }

    // ---- L-C: the floor comes after the signature, and says what it is ----

    @Test
    void aBadSignatureIsToldSoWhateverItsTimestamp() {
        var h = handler();
        var forged = h.auth(post(body(Instant.now().minusSeconds(5), "AAAA")));
        assertEquals(401, forged.status());
        String message = String.valueOf(forged.body());
        assertTrue(message.contains("Invalid signature"), message);
        assertFalse(message.contains("restart"), "an unauthenticated caller learns nothing about the server's start: " + message);
    }

    // ---- With a database the nonce survives the restart, and no login is refused after one ----

    @Test
    void durableNoncesSurviveARestartAndNeedNoWaitingWindow() {
        var counters = new Counters(dbFactory);
        var before = handler();
        before.setDurableNonces(counters);
        String captured = body(Instant.now().plusSeconds(25), null);
        assertEquals(200, before.auth(post(captured)).status());

        var restarted = handler();
        restarted.setDurableNonces(counters);
        var replay = restarted.auth(post(captured));
        assertEquals(401, replay.status());
        assertTrue(String.valueOf(replay.body()).contains("already used"), String.valueOf(replay.body()));
        // A fresh request right after the start is accepted, a slightly slow client clock included.
        assertEquals(200, restarted.auth(post(body(Instant.now(), null))).status());
        assertEquals(200, restarted.auth(post(body(Instant.now().minusSeconds(20), null))).status());
    }

    @Test
    void aSecondInstanceRefusesANonceTheFirstAccepted() {
        var counters = new Counters(dbFactory);
        var a = handler();
        var b = handler();
        a.setDurableNonces(counters);
        b.setDurableNonces(counters);
        String request = body(Instant.now(), null);
        assertEquals(200, a.auth(post(request)).status());
        assertEquals(401, b.auth(post(request)).status(), "fleet-wide while the database is reachable");
    }

    @Test
    void aFailingDurableStoreFallsBackToTheWaitingWindow() {
        var broken = new Counters(null) {
            @Override
            long incrementAndGet(String k, long delta, Instant expiresAt) {
                throw new RuntimeException("simulated DB outage");
            }
        };
        var h = handler();
        h.setDurableNonces(broken);
        var early = h.auth(post(body(Instant.now(), null)));
        assertEquals(401, early.status());
        assertTrue(String.valueOf(early.body()).contains("restart"), String.valueOf(early.body()));
        h.setStartedAtForTest(System.currentTimeMillis() - 61_000);
        assertEquals(200, h.auth(post(body(Instant.now(), null))).status(), "ops sign-in works with the database down");
    }

    // ---- Brace.start() wires both layers ----

    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private java.net.http.HttpResponse<String> signIn(int port, String body) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/ops/auth"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        return java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private String bodyFor(String origin) {
        String ts = Instant.now().toString();
        byte[] n = new byte[16];
        new java.security.SecureRandom().nextBytes(n);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        String sig = OpsKeys.sign(OpsKeys.v3AuthMessage(origin, key.publicKey(), ts, nonce), key.privateKey());
        return "{\"v\":\"3\",\"origin\":\"" + origin + "\",\"publicKey\":\"" + key.publicKey()
            + "\",\"timestamp\":\"" + ts + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + sig + "\"}";
    }

    @Test
    void aStartedAppUsesTheDatabaseWhenItHasOneAndTheWindowWhenItDoesNot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        var keys = tmp.resolve("authorized-keys");
        java.nio.file.Files.writeString(keys, key.publicKey() + " scope:control test\n");
        int port = freePort();
        String origin = "http://127.0.0.1:" + port;

        var noDatabase = Brace.app().port(port).banner(false).opsSecret("ops-final-review-secret-at-least-32-chars")
            .ops(keys.toString()).opsOrigins(origin);
        noDatabase.start();
        try {
            var refused = signIn(port, bodyFor(origin));
            assertEquals(401, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("restart"), refused.body());
        } finally {
            noDatabase.stop();
        }

        // Its own database: stop() closes the one it was given.
        var ownDatabase = new DatabaseFactory("jdbc:h2:mem:opsnoncewiring;DB_CLOSE_DELAY=-1", null, null, List.of());
        var withDatabase = Brace.app().port(port).banner(false).opsSecret("ops-final-review-secret-at-least-32-chars")
            .ops(keys.toString()).opsOrigins(origin).database(ownDatabase);
        withDatabase.start();
        try {
            String request = bodyFor(origin);
            assertEquals(200, signIn(port, request).statusCode(), "no waiting window with a database");
        } finally {
            withDatabase.stop();
        }
    }

    // ---- nits: bounded work per call; the per-key quota fits inside the overall cap ----

    @Test
    void aCallDoesBoundedWorkHoweverManyEntriesHaveExpired() {
        var cache = new OpsHandler.NonceCache(10, 100_000, 100_000);
        for (int i = 0; i < 10_000; i++) assertTrue(cache.checkAndRecord("a", "n" + i, 1_000));
        assertEquals(10_000, cache.size());
        // All 10,000 have expired. One call frees a bounded number of them, not the whole map.
        assertTrue(cache.checkAndRecord("a", "later", 60_000));
        assertEquals(10_000 - OpsHandler.NonceCache.MAX_EVICTIONS_PER_CALL + 1, cache.size());
    }

    @Test
    void aRefusedCallAtTheCapDoesBoundedWorkToo() {
        var cache = new OpsHandler.NonceCache(120_000, 1_000, 1_000);
        for (int i = 0; i < 1_000; i++) assertTrue(cache.checkAndRecord("a", "n" + i, 1_000));
        assertFalse(cache.checkAndRecord("a", "over", 2_000), "full, nothing expired: refused");
        assertEquals(1_000, cache.size());
        assertTrue(cache.checkAndRecord("a", "after-expiry", 1_000 + 120_001), "expired entries make room");
        assertFalse(cache.checkAndRecord("a", "n999", 1_000 + 120_001 - 2), "an unexpired nonce is still a replay");
    }

    @Test
    void expiredReplayedNonceIsAcceptedOnceAndCountedOnce() {
        var cache = new OpsHandler.NonceCache(100, 10, 2);
        assertTrue(cache.checkAndRecord("a", "x", 1_000));
        assertTrue(cache.checkAndRecord("a", "x", 2_000), "expired: fresh again");
        assertFalse(cache.checkAndRecord("a", "x", 2_001));
        assertEquals(1, cache.size());
        assertTrue(cache.checkAndRecord("a", "y", 2_002), "the key's quota counted x once");
    }

    @Test
    void everyKeysQuotaFitsInsideTheOverallCap() {
        assertEquals(5_000, OpsHandler.nonceQuotaPerKey(1));
        assertEquals(5_000, OpsHandler.nonceQuotaPerKey(20));
        assertEquals(4_761, OpsHandler.nonceQuotaPerKey(21));
        assertEquals(100, OpsHandler.nonceQuotaPerKey(1_000));
        assertEquals(1, OpsHandler.nonceQuotaPerKey(1_000_000));
        for (int keys : new int[] {1, 20, 21, 500, 100_000}) {
            assertTrue((long) OpsHandler.nonceQuotaPerKey(keys) * keys <= OpsHandler.NONCE_CAP, "keys=" + keys);
        }
    }
}
