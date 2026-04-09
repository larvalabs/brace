package io.brace;

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
 */
public class OpsToken {

    /** Generate a random 32-byte secret, base64-encoded. */
    public static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Create a token that expires in ttlSeconds. */
    public static String create(String secret, int ttlSeconds) {
        long exp = System.currentTimeMillis() / 1000 + ttlSeconds;
        String payload = "{\"exp\":" + exp + "}";
        String encodedPayload = base64Encode(payload.getBytes(StandardCharsets.UTF_8));
        String sig = sign(encodedPayload, secret);
        return encodedPayload + "." + sig;
    }

    /** Validate a token: check format, HMAC, and expiry. */
    public static boolean validate(String token, String secret) {
        if (token == null || token.isEmpty()) return false;
        int dot = token.indexOf('.');
        if (dot < 0 || dot == token.length() - 1) return false;

        String encodedPayload = token.substring(0, dot);
        String sig = token.substring(dot + 1);

        // Verify HMAC
        String expected = sign(encodedPayload, secret);
        if (!constantTimeEquals(expected, sig)) return false;

        // Decode and check expiry
        try {
            String json = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            // Simple parse: {"exp":123456}
            int expIdx = json.indexOf("\"exp\":");
            if (expIdx < 0) return false;
            String expStr = json.substring(expIdx + 6).replaceAll("[^0-9]", "");
            long exp = Long.parseLong(expStr);
            return System.currentTimeMillis() / 1000 < exp;
        } catch (Exception e) {
            return false;
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
