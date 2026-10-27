package com.larvalabs.brace;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Signs and verifies the payloads {@link PostgresMessageBus} sends over {@code LISTEN}/{@code NOTIFY}
 * (L6), and names the app's channel.
 *
 * <p>Postgres puts no access control on {@code NOTIFY} or {@code LISTEN}: any role that can connect
 * can send to, or listen on, any channel. Unsigned, a forged {@code NOTIFY} pushed frames to room
 * members, and two apps on one database shared {@code brace_ws} and leaked broadcasts into each
 * other's same-named rooms. So every payload is {@code <sig>.<body>}: {@code body} is JSON carrying
 * an issued-at time {@code t} and a random nonce {@code n}, and {@code sig} is the unpadded
 * base64url HMAC-SHA256 of the body's exact bytes (no canonicalization, so no ambiguity about what
 * was signed).
 *
 * <p>The key is derived from a secret every instance of the app shares (the session secret, else
 * the ops secret; see {@code Brace.start}), under its own domain-separation label, so it is never
 * the session or ops key itself. The channel name is a hash of that key: instances of one app agree
 * on it, two apps with different secrets never share a channel, and the name reveals nothing usable
 * about the key.
 *
 * <p><b>Replay.</b> {@code LISTEN} needs no privilege either, so whoever can forge a {@code NOTIFY}
 * can also capture valid ones. A message is accepted only within {@link #WINDOW_MS} of its issued-at
 * time, and each nonce only once inside that window, so a captured message can't be re-sent to
 * flood a room. Instances' clocks must agree to well within the window (NTP does).
 */
final class MessageBusSigner {

    /** How far a message's issued-at time may be from this instance's clock, either way. */
    static final long WINDOW_MS = 60_000;
    /** Unpadded base64url of a 32-byte HMAC. */
    static final int SIG_LENGTH = 43;
    // Bounds the nonce cache under a very high legitimate broadcast rate (forgeries never reach it).
    // Past this, the oldest nonces are forgotten early and fall back to the time window alone.
    private static final int MAX_SEEN = 100_000;
    private static final String KEY_LABEL = "brace-ws-message-bus/v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Why {@link #open} refused a payload. */
    enum Rejection { MALFORMED, BAD_SIGNATURE, EXPIRED, REPLAYED }

    /** Thrown by {@link #open} for a payload that must not be delivered. */
    static final class RejectedException extends Exception {
        final Rejection reason;

        RejectedException(Rejection reason) {
            super(reason.name());
            this.reason = reason;
        }
    }

    private final byte[] key;
    private final String channel;
    private final LongSupplier clock;
    // Nonce -> the time after which the timestamp check alone rejects it. Insertion order is close
    // to expiry order, so expired entries are trimmed from the head.
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    MessageBusSigner(String baseSecret) {
        this(baseSecret, System::currentTimeMillis);
    }

    MessageBusSigner(String baseSecret, LongSupplier clock) {
        if (baseSecret == null || baseSecret.isEmpty()) {
            throw new IllegalArgumentException("message bus secret cannot be null or empty");
        }
        // HMAC as a PRF over a fixed label, as OpsToken.deriveSecret does: a key for this purpose
        // only, so it can't be confused with (or reveal) the session or ops key.
        this.key = hmac(baseSecret.getBytes(StandardCharsets.UTF_8),
                KEY_LABEL.getBytes(StandardCharsets.UTF_8));
        this.channel = channelFor(key);
        this.clock = clock;
    }

    /**
     * The {@code LISTEN}/{@code NOTIFY} channel: {@code brace_ws_} and 16 hex digits of SHA-256 of
     * the key. Lowercase letters, digits and underscores only, 25 bytes, so it is a valid identifier
     * whether quoted or not (well under Postgres's 63-byte limit) and can't carry SQL.
     */
    String channel() {
        return channel;
    }

    private static String channelFor(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "brace_ws_" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Sign a broadcast body. {@code fields} becomes the JSON body with {@code t} (issued-at, epoch
     * millis) and {@code n} (a fresh nonce) added; the result is {@code <sig>.<body>}.
     */
    String seal(Map<String, Object> fields) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("t", clock.getAsLong());
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        body.put("n", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        body.putAll(fields);
        String json = Json.mapper().writeValueAsString(body);
        return sign(json) + "." + json;
    }

    /**
     * Verify a payload and return its JSON body as a map. Rejects (in this order) anything not
     * shaped {@code <sig>.<json>}, a signature that doesn't match (constant-time), an issued-at
     * outside the window, and a nonce already accepted. Only a fully verified message records its
     * nonce, so forgeries can't fill the cache.
     */
    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> open(String payload) throws RejectedException {
        if (payload == null || payload.length() <= SIG_LENGTH + 1 || payload.charAt(SIG_LENGTH) != '.') {
            throw new RejectedException(Rejection.MALFORMED);
        }
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(payload.substring(0, SIG_LENGTH));
        } catch (IllegalArgumentException e) {
            throw new RejectedException(Rejection.MALFORMED);
        }
        String json = payload.substring(SIG_LENGTH + 1);
        byte[] expected = hmac(key, json.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, given)) {
            throw new RejectedException(Rejection.BAD_SIGNATURE);
        }
        Map<String, Object> body;
        try {
            body = Json.mapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RejectedException(Rejection.MALFORMED);
        }
        if (!(body.get("t") instanceof Number t) || !(body.get("n") instanceof String nonce)) {
            throw new RejectedException(Rejection.MALFORMED);
        }
        long now = clock.getAsLong();
        long issued = t.longValue();
        if (Math.abs(now - issued) > WINDOW_MS) {
            throw new RejectedException(Rejection.EXPIRED);
        }
        trimSeen(now);
        if (seen.putIfAbsent(nonce, issued + WINDOW_MS) != null) {
            throw new RejectedException(Rejection.REPLAYED);
        }
        return body;
    }

    private void trimSeen(long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> head = it.next();
            if (head.getValue() >= now && seen.size() < MAX_SEEN) {
                break;
            }
            it.remove();
        }
    }

    /** For tests: how many nonces are remembered. */
    synchronized int seenCount() {
        return seen.size();
    }

    private String sign(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(key, json.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
