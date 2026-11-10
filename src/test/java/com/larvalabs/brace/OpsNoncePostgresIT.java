package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L16 final review on real Postgres: an accepted ops sign-in nonce is recorded in
 * {@code brace_counters}, so a replay is refused by another instance and by the next process.
 */
class OpsNoncePostgresIT extends PostgresTestBase {

    private static final String ORIGIN = "https://ops.example.com";
    static DatabaseFactory dbFactory;
    private final OpsKeys.Keypair key = OpsKeys.generateKeypair();

    @BeforeAll
    static void factory() {
        dbFactory = new DatabaseFactory(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void close() {
        if (dbFactory != null) dbFactory.close();
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("brace_counters");
    }

    private OpsHandler instance() {
        var authorized = new LinkedHashMap<String, OpsScope>();
        authorized.put(key.publicKey(), OpsScope.CONTROL);
        var h = new OpsHandler(new Stats(), null, null, new Router(), authorized,
            OpsToken.deriveSecret("a-session-secret-at-least-32-chars-long"));
        h.setPinnedOrigins(List.of(ORIGIN));
        h.setDurableNonces(new Counters(dbFactory));
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
    void replayIsRefusedByTheNextProcessAndByAnotherInstance() throws Exception {
        var request = signed(Instant.now().plusSeconds(25));
        assertEquals(200, instance().auth(request).status());
        var afterRestart = instance();
        var replay = afterRestart.auth(request);
        assertEquals(401, replay.status());
        assertTrue(String.valueOf(replay.body()).contains("already used"), String.valueOf(replay.body()));
        // No waiting window: a new sign-in right after the start is accepted.
        assertEquals(200, afterRestart.auth(signed(Instant.now())).status());
        try (var c = connect(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*), min(expires_at) > now() FROM brace_counters "
                 + "WHERE counter_key LIKE 'ops-nonce:%'")) {
            rs.next();
            assertEquals(2, rs.getLong(1), "one row per accepted nonce");
            assertTrue(rs.getBoolean(2), "each with an expiry, so the sweep removes it");
        }
    }

    /** L16 verification: a locked counters table neither holds the sign-in nor leaves a statement waiting. */
    @Test
    void aLockedCountersTableDoesNotHoldASignIn() throws Exception {
        try (var locker = connect()) {
            locker.setAutoCommit(false);
            try (var st = locker.createStatement()) {
                st.execute("LOCK TABLE brace_counters IN ACCESS EXCLUSIVE MODE");
            }
            var h = instance();
            h.setStartedAtForTest(System.currentTimeMillis() - 61_000);
            long start = System.nanoTime();
            assertEquals(200, h.auth(signed(Instant.now())).status());
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 2_500, "answered in " + millis + " ms");
            // The abandoned insert is cancelled by its query timeout, not left behind the lock.
            Thread.sleep(2_000);
            try (var c = connect(); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE state = 'active' "
                     + "AND query LIKE 'INSERT INTO brace_counters%'")) {
                rs.next();
                assertEquals(0, rs.getLong(1));
            }
            locker.rollback();
        }
    }

    @Test
    void oneNonceRacedAcrossEightInstancesIsAcceptedOnce() throws Exception {
        var request = signed(Instant.now());
        int instances = 8;
        var accepted = new AtomicInteger();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(instances);
        for (int i = 0; i < instances; i++) {
            var h = instance();
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    if (h.auth(request).status() == 200) accepted.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, accepted.get());
    }
}
