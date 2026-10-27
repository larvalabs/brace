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
        var now = new AtomicLong(10_000_000L);
        var receiver = new MessageBusSigner(SECRET_A, now::get);
        now.addAndGet(5);
        String sealed = new MessageBusSigner(SECRET_A, now::get).seal(Map.of("r", "lobby", "m", "<div>hi</div>"));
        Map<String, Object> body = receiver.open(sealed);
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
        // Each receiver has its own clock, started well before the message (a receiver refuses
        // anything issued before it started).
        long issued = 10_000_000L;
        String sealed = new MessageBusSigner(SECRET_A, () -> issued).seal(Map.of("r", "lobby", "m", "hello"));

        assertEquals(MessageBusSigner.Rejection.EXPIRED,
            rejection(receiverStartedAt(issued - 1_000, issued + MessageBusSigner.WINDOW_MS + 1), sealed));
        assertEquals(MessageBusSigner.Rejection.EXPIRED,
            rejection(receiverStartedAt(issued - MessageBusSigner.WINDOW_MS - 10_000, issued - MessageBusSigner.WINDOW_MS - 1), sealed));
        // Within the window either way (clock skew between instances), it's accepted.
        receiverStartedAt(issued - 20_000, issued - 5_000).open(sealed);
        receiverStartedAt(issued - 20_000, issued + 5_000).open(sealed);
    }

    /** A receiver whose clock read {@code start} when it was created and reads {@code now} after. */
    private static MessageBusSigner receiverStartedAt(long start, long now) {
        var clock = new AtomicLong(start);
        var receiver = new MessageBusSigner(SECRET_A, clock::get);
        clock.set(now);
        return receiver;
    }

    // --- Review fixes: replays the nonce cache alone missed ---

    @Test
    void aMessageIssuedBeforeARestartIsRefused() throws Exception {
        // A restarted instance has an empty nonce cache; anything issued before it started is out.
        var now = new AtomicLong(10_000_000L);
        String sealed = new MessageBusSigner(SECRET_A, now::get).seal(Map.of("r", "lobby", "m", "hello"));
        new MessageBusSigner(SECRET_A, now::get).open(sealed); // delivered once, before the restart
        now.addAndGet(10);
        var restarted = new MessageBusSigner(SECRET_A, now::get);
        assertEquals(MessageBusSigner.Rejection.REPLAYED, rejection(restarted, sealed));
    }

    @Test
    void aNonceEvictedFromAFullCacheCantBeReplayed() throws Exception {
        var now = new AtomicLong(10_000_000L);
        var receiver = new MessageBusSigner(SECRET_A, now::get);
        now.addAndGet(1);
        var sender = new MessageBusSigner(SECRET_A, now::get);
        String first = sender.seal(Map.of("r", "lobby", "m", "first"));
        receiver.open(first);
        now.addAndGet(1);
        for (int i = 0; i < MessageBusSigner.MAX_SEEN; i++) {
            receiver.open(sender.seal(Map.of("r", "lobby", "m", "m" + i)));
        }
        assertEquals(MessageBusSigner.Rejection.REPLAYED, rejection(receiver, first));
        // Newer messages still flow.
        now.addAndGet(1);
        receiver.open(sender.seal(Map.of("r", "lobby", "m", "next")));
    }

    @Test
    void aClockSteppingBackCantRevealATrimmedNonce() throws Exception {
        var now = new AtomicLong(10_000_000L);
        var receiver = new MessageBusSigner(SECRET_A, now::get);
        now.addAndGet(1);
        var sender = new MessageBusSigner(SECRET_A, now::get);
        String sealed = sender.seal(Map.of("r", "lobby", "m", "hello"));
        receiver.open(sealed);
        now.addAndGet(MessageBusSigner.WINDOW_MS + 1);
        receiver.open(sender.seal(Map.of("r", "lobby", "m", "later"))); // trims the first nonce
        now.addAndGet(-31_000);                                            // NTP steps back
        assertEquals(MessageBusSigner.Rejection.EXPIRED, rejection(receiver, sealed));
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
