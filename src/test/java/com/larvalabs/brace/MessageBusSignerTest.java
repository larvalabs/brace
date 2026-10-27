package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L6: the signing, verification and channel naming behind {@link PostgresMessageBus}. The
 * end-to-end checks over a real {@code LISTEN}/{@code NOTIFY} are in
 * {@code WebSocketBusSigningPostgresIT}.
 */
class MessageBusSignerTest {

    static final String SECRET_A = "a-test-secret-that-is-long-enough-0123456789";
    static final String SECRET_B = "b-test-secret-that-is-long-enough-0123456789";

    private static MessageBusSigner.Rejection rejection(MessageBusSigner signer, String payload) {
        var e = assertThrows(MessageBusSigner.RejectedException.class, () -> signer.open(payload));
        return e.reason;
    }

    @Test
    void sealedMessageOpens() throws Exception {
        var signer = new MessageBusSigner(SECRET_A);
        String sealed = signer.seal(Map.of("r", "lobby", "m", "<div>hi</div>"));
        Map<String, Object> body = new MessageBusSigner(SECRET_A).open(sealed);
        assertEquals("lobby", body.get("r"));
        assertEquals("<div>hi</div>", body.get("m"));
    }

    @Test
    void unsignedLegacyPayloadIsRejected() {
        // Exactly what a forger (or a pre-0.1.11 instance) sends.
        var signer = new MessageBusSigner(SECRET_A);
        assertEquals(MessageBusSigner.Rejection.MALFORMED,
                rejection(signer, "{\"r\":\"lobby\",\"m\":\"<img src=x onerror=alert(1)>\"}"));
        assertEquals(MessageBusSigner.Rejection.MALFORMED, rejection(signer, ""));
        assertEquals(MessageBusSigner.Rejection.MALFORMED, rejection(signer, null));
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        var signer = new MessageBusSigner(SECRET_A);
        String sealed = signer.seal(Map.of("r", "lobby", "m", "hello"));
        String tampered = sealed.replace("\"hello\"", "\"pwned\"");
        assertNotEquals(sealed, tampered);
        assertEquals(MessageBusSigner.Rejection.BAD_SIGNATURE, rejection(signer, tampered));
    }

    @Test
    void messageSignedWithAnotherSecretIsRejected() throws Exception {
        String sealed = new MessageBusSigner(SECRET_B).seal(Map.of("r", "lobby", "m", "hello"));
        assertEquals(MessageBusSigner.Rejection.BAD_SIGNATURE,
                rejection(new MessageBusSigner(SECRET_A), sealed));
    }

    @Test
    void garbageSignatureIsRejected() {
        var signer = new MessageBusSigner(SECRET_A);
        String body = "{\"t\":" + System.currentTimeMillis() + ",\"n\":\"x\",\"r\":\"lobby\",\"m\":\"hi\"}";
        assertEquals(MessageBusSigner.Rejection.BAD_SIGNATURE,
                rejection(signer, "A".repeat(MessageBusSigner.SIG_LENGTH) + "." + body));
        assertEquals(MessageBusSigner.Rejection.MALFORMED,
                rejection(signer, "*".repeat(MessageBusSigner.SIG_LENGTH) + "." + body));
    }

    @Test
    void replayedMessageIsRejected() throws Exception {
        var signer = new MessageBusSigner(SECRET_A);
        String sealed = signer.seal(Map.of("r", "lobby", "m", "hello"));
        signer.open(sealed);
        assertEquals(MessageBusSigner.Rejection.REPLAYED, rejection(signer, sealed));
    }

    @Test
    void identicalBroadcastsBothDeliver() throws Exception {
        // The nonce, not the content, identifies a message: an app may legitimately send the same
        // text to the same room twice in one millisecond.
        var signer = new MessageBusSigner(SECRET_A, () -> 1_000_000L);
        String first = signer.seal(Map.of("r", "lobby", "m", "ping"));
        String second = signer.seal(Map.of("r", "lobby", "m", "ping"));
        assertNotEquals(first, second);
        signer.open(first);
        signer.open(second);
    }

    @Test
    void staleOrFutureMessageIsRejected() throws Exception {
        var now = new AtomicLong(10_000_000L);
        var sender = new MessageBusSigner(SECRET_A, now::get);
        var receiver = new MessageBusSigner(SECRET_A, now::get);
        String sealed = sender.seal(Map.of("r", "lobby", "m", "hello"));

        now.addAndGet(MessageBusSigner.WINDOW_MS + 1);
        assertEquals(MessageBusSigner.Rejection.EXPIRED, rejection(receiver, sealed));

        now.set(10_000_000L - MessageBusSigner.WINDOW_MS - 1);
        assertEquals(MessageBusSigner.Rejection.EXPIRED, rejection(receiver, sealed));

        // Within the window either way (clock skew between instances), it's accepted.
        now.set(10_000_000L - 5_000);
        receiver.open(sealed);
    }

    @Test
    void nonceCacheForgetsExpiredEntries() throws Exception {
        var now = new AtomicLong(10_000_000L);
        var signer = new MessageBusSigner(SECRET_A, now::get);
        for (int i = 0; i < 5; i++) {
            signer.open(signer.seal(Map.of("r", "lobby", "m", "m" + i)));
        }
        assertEquals(5, signer.seenCount());
        now.addAndGet(MessageBusSigner.WINDOW_MS + 1);
        signer.open(signer.seal(Map.of("r", "lobby", "m", "later")));
        assertEquals(1, signer.seenCount());
    }

    @Test
    void forgeriesDoNotGrowTheNonceCache() {
        var signer = new MessageBusSigner(SECRET_A);
        String body = "{\"t\":" + System.currentTimeMillis() + ",\"n\":\"x\",\"r\":\"lobby\",\"m\":\"hi\"}";
        for (int i = 0; i < 100; i++) {
            assertThrows(MessageBusSigner.RejectedException.class,
                    () -> signer.open("A".repeat(MessageBusSigner.SIG_LENGTH) + "." + body));
        }
        assertEquals(0, signer.seenCount());
    }

    @Test
    void channelIsStablePerSecretAndDistinctAcrossSecrets() {
        String a = new MessageBusSigner(SECRET_A).channel();
        assertEquals(a, new MessageBusSigner(SECRET_A).channel(), "every instance of one app agrees");
        assertNotEquals(a, new MessageBusSigner(SECRET_B).channel(), "two apps never share a channel");
        assertNotEquals("brace_ws", a);
        assertTrue(a.matches("brace_ws_[0-9a-f]{16}"), a);
        assertTrue(a.length() <= 63, "Postgres identifiers are at most 63 bytes");
    }

    @Test
    void channelDoesNotContainTheSecret() {
        String channel = new MessageBusSigner(SECRET_A).channel();
        assertFalse(channel.contains(SECRET_A.substring(0, 8)));
    }

    @Test
    void signingOverheadLeavesRoomUnderTheNotifyCap() throws Exception {
        // INLINE_LIMIT_BYTES is applied to the whole signed payload; the overhead is the signature,
        // the separator and the t/n fields, and must leave a NOTIFY under Postgres's 8000-byte cap.
        var signer = new MessageBusSigner(SECRET_A);
        String raw = Json.mapper().writeValueAsString(Map.of("r", "lobby", "m", ""));
        String sealed = signer.seal(Map.of("r", "lobby", "m", ""));
        int overhead = sealed.getBytes(StandardCharsets.UTF_8).length - raw.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(overhead < 100, "overhead " + overhead);
        assertTrue(PostgresMessageBus.INLINE_LIMIT_BYTES < 8000);
    }

    @Test
    void emptySecretIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new MessageBusSigner(""));
        assertThrows(IllegalArgumentException.class, () -> new MessageBusSigner(null));
    }
}
