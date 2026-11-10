package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// The deprecated 2-arg OpsToken.create(secret, ttl) is exercised deliberately: its legacy
// CONTROL-default behavior must stay stable for tokens minted before scoping existed (H1).
@SuppressWarnings("deprecation")
class OpsTokenTest {
    @Test
    void createsAndValidatesToken() {
        var secret = OpsToken.generateSecret();
        var token = OpsToken.create(secret, 3600);
        assertTrue(OpsToken.validate(token, secret));
    }

    @Test
    void rejectsExpiredToken() {
        var secret = OpsToken.generateSecret();
        var token = OpsToken.create(secret, -1);
        assertFalse(OpsToken.validate(token, secret));
    }

    @Test
    void rejectsTamperedToken() {
        var secret = OpsToken.generateSecret();
        var token = OpsToken.create(secret, 3600);
        var tampered = token.substring(0, token.length() - 2) + "XX";
        assertFalse(OpsToken.validate(tampered, secret));
    }

    @Test
    void rejectsTokenWithWrongSecret() {
        var secret1 = OpsToken.generateSecret();
        var secret2 = OpsToken.generateSecret();
        var token = OpsToken.create(secret1, 3600);
        assertFalse(OpsToken.validate(token, secret2));
    }

    @Test
    void rejectsMalformedToken() {
        var secret = OpsToken.generateSecret();
        assertFalse(OpsToken.validate("not-a-token", secret));
        assertFalse(OpsToken.validate("", secret));
        assertFalse(OpsToken.validate(null, secret));
    }

    @Test
    void scopeAndKidRoundTrip() {
        var secret = OpsToken.generateSecret();
        var token = OpsToken.create(secret, 3600, OpsScope.READ, "abc123def456");
        var claims = OpsToken.verify(token, secret);
        assertNotNull(claims);
        assertEquals(OpsScope.READ, claims.scope());
        assertEquals("abc123def456", claims.kid());
    }

    @Test
    void legacyTwoArgCreateMintsAnExplicitControlToken() {
        // The deprecated two-arg create writes scope:control into the payload explicitly.
        var secret = OpsToken.generateSecret();
        var claims = OpsToken.verify(OpsToken.create(secret, 3600), secret);
        assertNotNull(claims);
        assertEquals(OpsScope.CONTROL, claims.scope());
        assertNull(claims.kid());
    }

    // --- L1 review fixes: a scope claim that is missing or unknown fails closed to READ ---

    @Test
    void missingScopeClaimIsRead() {
        var secret = OpsToken.generateSecret();
        long exp = System.currentTimeMillis() / 1000 + 3600;
        var claims = OpsToken.verify(OpsToken.create(secret, exp, null, "kid", OpsToken.Type.BEARER), secret);
        assertNotNull(claims);
        assertEquals(OpsScope.READ, claims.scope(), "no scope claim must never mean CONTROL");
        assertEquals(OpsScope.READ, OpsToken.verify(signed(secret, "{\"exp\":" + exp + "}"), secret).scope());
    }

    @Test
    void unknownScopeClaimIsRead() {
        var secret = OpsToken.generateSecret();
        long exp = System.currentTimeMillis() / 1000 + 3600;
        for (String scope : new String[] {"admin", "CONTROL!", "", "control,read"}) {
            var claims = OpsToken.verify(signed(secret, "{\"exp\":" + exp + ",\"scope\":\"" + scope + "\"}"), secret);
            assertNotNull(claims, scope);
            assertEquals(OpsScope.READ, claims.scope(), scope);
        }
    }

    /** A token over an arbitrary payload, signed as OpsToken signs. */
    private static String signed(String secret, String json) {
        try {
            var enc = java.util.Base64.getUrlEncoder().withoutPadding();
            String payload = enc.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return payload + "." + enc.encodeToString(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void verifyRejectsTamperedToken() {
        var secret = OpsToken.generateSecret();
        var token = OpsToken.create(secret, 3600, OpsScope.READ, "kid0");
        var tampered = token.substring(0, token.length() - 2) + "XX";
        assertNull(OpsToken.verify(tampered, secret), "tampered token must not verify");
    }

    @Test
    void generateSecretProducesUniqueValues() {
        var s1 = OpsToken.generateSecret();
        var s2 = OpsToken.generateSecret();
        assertNotEquals(s1, s2);
        assertEquals(32, java.util.Base64.getDecoder().decode(s1).length);
    }
}
