package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Short-lived HMAC-SHA256 signed token for ops endpoint authentication.
 * Format: base64url(json).base64url(hmac) — same as Brace session cookies.
 *
 * <p>The payload carries {@code exp} (expiry, epoch seconds), an optional
 * {@code scope} ({@link OpsScope}, default {@code CONTROL} when absent for
 * backward compatibility), and an optional {@code kid} (a fingerprint of the
 * minting key, so ops access can be attributed to a key in an audit log).
 */
public class OpsToken {

    /** Verified token claims. {@code kid} may be null on legacy tokens. */
    public record Claims(long exp, OpsScope scope, String kid) {}

    /** Generate a random 32-byte secret, base64-encoded. */
    public static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Create a full-scope ({@code CONTROL}) token with no key id. */
    public static String create(String secret, int ttlSeconds) {
        return create(secret, ttlSeconds, OpsScope.CONTROL, null);
    }

    /** Create a token that expires in {@code ttlSeconds}, carrying the given scope and (optional) key id. */
    public static String create(String secret, int ttlSeconds, OpsScope scope, String kid) {
        long exp = System.currentTimeMillis() / 1000 + ttlSeconds;
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("exp", exp);
        if (scope != null) node.put("scope", scope.wire());
        if (kid != null) node.put("kid", kid);
        String payload;
        try {
            payload = Json.mapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ops token payload", e);
        }
        String encodedPayload = base64Encode(payload.getBytes(StandardCharsets.UTF_8));
        String sig = sign(encodedPayload, secret);
        return encodedPayload + "." + sig;
    }

    /** Validate a token: check format, HMAC, and expiry. */
    public static boolean validate(String token, String secret) {
        return verify(token, secret) != null;
    }

    /**
     * Verify a token's HMAC and expiry and return its claims, or {@code null} if the
     * token is missing, malformed, tampered, or expired. A payload with no {@code scope}
     * field is treated as {@link OpsScope#CONTROL} (tokens minted before scoping existed).
     */
    public static Claims verify(String token, String secret) {
        if (token == null || token.isEmpty()) return null;
        int dot = token.indexOf('.');
        if (dot < 0 || dot == token.length() - 1) return null;

        String encodedPayload = token.substring(0, dot);
        String sig = token.substring(dot + 1);

        // Verify HMAC
        String expected = sign(encodedPayload, secret);
        if (!constantTimeEquals(expected, sig)) return null;

        // Decode and check claims
        try {
            byte[] json = Base64.getUrlDecoder().decode(encodedPayload);
            JsonNode n = Json.mapper().readTree(json);
            if (!n.hasNonNull("exp")) return null;
            long exp = n.get("exp").asLong();
            if (System.currentTimeMillis() / 1000 >= exp) return null;
            OpsScope scope = OpsScope.parse(n.hasNonNull("scope") ? n.get("scope").asText() : null, OpsScope.CONTROL);
            String kid = n.hasNonNull("kid") ? n.get("kid").asText() : null;
            return new Claims(exp, scope, kid);
        } catch (Exception e) {
            return null;
        }
    }

    private static String base64Encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return base64Encode(hmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
