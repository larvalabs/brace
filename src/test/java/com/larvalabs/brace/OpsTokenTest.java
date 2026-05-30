package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
    void absentScopeDefaultsToControl() {
        // Legacy two-arg create (and tokens minted before scoping existed) verify as CONTROL.
        var secret = OpsToken.generateSecret();
        var claims = OpsToken.verify(OpsToken.create(secret, 3600), secret);
        assertNotNull(claims);
        assertEquals(OpsScope.CONTROL, claims.scope());
        assertNull(claims.kid());
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
