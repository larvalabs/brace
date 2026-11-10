package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L16 verification fix (docs/2026-11-09-security-review-todos.md): an ops sign-in never waits on
 * the database. The durable nonce write is bounded (connection wait included), a timeout counts as
 * a failed write, and a cool-down after a failure keeps sign-ins during an outage fast.
 */
class OpsNonceTimeoutTest {

    private static final String ORIGIN = "https://ops.example.com";
    private final OpsKeys.Keypair key = OpsKeys.generateKeypair();

    private OpsHandler handler(Counters counters) {
        var authorized = new LinkedHashMap<String, OpsScope>();
        authorized.put(key.publicKey(), OpsScope.CONTROL);
        var h = new OpsHandler(new Stats(), null, null, new Router(), authorized,
            OpsToken.deriveSecret("a-session-secret-at-least-32-chars-long"));
        h.setPinnedOrigins(List.of(ORIGIN));
        h.setDurableNonces(counters);
        // Past the restart window, so a sign-in that falls back to it is accepted.
        h.setStartedAtForTest(System.currentTimeMillis() - 61_000);
        return h;
    }

    private Request signed() {
        String ts = Instant.now().toString();
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
    void anExhaustedPoolDoesNotHoldASignIn() throws Exception {
        var dbFactory = new DatabaseFactory("jdbc:h2:mem:opsnoncepool;DB_CLOSE_DELAY=-1", null, null, List.of(), 1);
        var holder = new Database(dbFactory.openSession());
        try {
            holder.beginTransaction();
            Boolean valid = holder.jdbc(conn -> { return conn.isValid(1); }); // takes the pool's only connection
            assertTrue(valid);
            var h = handler(new Counters(dbFactory));

            long start = System.nanoTime();
            var first = h.auth(signed());
            long firstMillis = (System.nanoTime() - start) / 1_000_000;
            assertEquals(200, first.status(), String.valueOf(first.body()));
            assertTrue(firstMillis < 2_500, "answered in " + firstMillis + " ms");

            // During the cool-down the database is not tried at all.
            start = System.nanoTime();
            var second = h.auth(signed());
            long secondMillis = (System.nanoTime() - start) / 1_000_000;
            assertEquals(200, second.status(), String.valueOf(second.body()));
            assertTrue(secondMillis < 500, "answered in " + secondMillis + " ms");
        } finally {
            holder.rollbackTransaction();
            holder.close();
            dbFactory.close();
        }
    }

    @Test
    void aWriteThatNeverReturnsCountsAsAFailedWrite() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var stuck = new Counters(null) {
            @Override
            long incrementAndGet(String k, long delta, Instant expiresAt, long deadlineNanos) {
                calls.incrementAndGet();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("interrupted");
            }
        };
        var h = handler(stuck);
        long start = System.nanoTime();
        assertEquals(200, h.auth(signed()).status());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_500);
        assertEquals(200, h.auth(signed()).status());
        assertEquals(1, calls.get(), "the second sign-in, in the cool-down, does not try the database");

        // Within the restart window the fallback still refuses, as for any failed write.
        var early = handler(stuck);
        early.setStartedAtForTest(System.currentTimeMillis());
        var refused = early.auth(signed());
        assertEquals(401, refused.status());
        assertTrue(String.valueOf(refused.body()).contains("restart"), String.valueOf(refused.body()));
    }

    @Test
    void aUniqueViolationIsAReplayNotAFailedWrite() {
        var racing = new Counters(null) {
            @Override
            long incrementAndGet(String k, long delta, Instant expiresAt, long deadlineNanos) {
                throw new RuntimeException("insert lost a race",
                    new java.sql.SQLIntegrityConstraintViolationException("duplicate key", "23505"));
            }
        };
        var response = handler(racing).auth(signed());
        assertEquals(401, response.status());
        assertTrue(String.valueOf(response.body()).contains("already used"), String.valueOf(response.body()));
    }

    @Test
    void aHealthyDatabaseStillRecordsEveryNonce() {
        var dbFactory = new DatabaseFactory("jdbc:h2:mem:opsnoncehealthy;DB_CLOSE_DELAY=-1", null, null, List.of());
        try {
            var counters = new Counters(dbFactory);
            var a = handler(counters);
            var b = handler(counters);
            var request = signed();
            assertEquals(200, a.auth(request).status());
            assertEquals(401, b.auth(request).status(), "refused by another instance");
            // Not refused by the restart window: the record was made.
            var fresh = handler(counters);
            fresh.setStartedAtForTest(System.currentTimeMillis());
            assertEquals(200, fresh.auth(signed()).status());
        } finally {
            dbFactory.close();
        }
    }
}
