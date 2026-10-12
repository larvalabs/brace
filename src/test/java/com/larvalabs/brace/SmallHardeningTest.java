package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Low-severity items from the 2026-07 security review that are small enough to share a
 * test class: L4 (CSRF comparison charset), L5 (storage key traversal), L6 (CSRF field
 * escaping). L3 (dead weak-secret clause) and L7 (non-positive ops TTL) are covered by
 * {@code OpsIntegrationTest} and by inspection respectively.
 */
class SmallHardeningTest {

    // --- L4: explicit UTF-8 in the CSRF comparison ---

    @Test
    void csrfComparisonIsCharsetIndependent() {
        var session = new Session();
        // A non-ASCII token exercises the encoding; the framework's own tokens are base64url,
        // but the comparison must not depend on the platform default either way.
        session.set(Csrf.TOKEN_KEY, "tökén-välue");
        assertTrue(Csrf.validateToken(session, "tökén-välue"));
        assertFalse(Csrf.validateToken(session, "tökén-välun"));
        assertFalse(Csrf.validateToken(session, null));
    }

    @Test
    void csrfValidationStillRejectsAMissingSessionToken() {
        assertFalse(Csrf.validateToken(new Session(), "anything"));
    }

    // --- L6: the hidden field escapes its value ---

    @Test
    void hiddenFieldEscapesTheTokenValue() {
        var session = new Session();
        session.set(Csrf.TOKEN_KEY, "a\"><script>alert(1)</script>");
        String field = Csrf.hiddenField(session);
        assertFalse(field.contains("<script>"), field);
        assertFalse(field.contains("\"><"), field);
        // Masking base64url-encodes whatever is in the session, so markup can't survive it; the
        // escaping stays as a second line of defense.
        String value = field.substring(field.indexOf("value=\"") + 7, field.lastIndexOf('"'));
        assertTrue(Csrf.validateToken(session, value));
    }

    @Test
    void hiddenFieldCarriesAMaskedTokenThatValidates() {
        var session = new Session();
        Csrf.ensureToken(session);
        String field = Csrf.hiddenField(session);
        assertTrue(field.startsWith("<input type=\"hidden\" name=\"_csrf\" value=\""), field);
        String value = field.substring(field.indexOf("value=\"") + 7, field.lastIndexOf('"'));
        assertTrue(Csrf.validateToken(session, value));
    }

    // --- BREACH: the token rendered into pages is masked per render ---

    @Test
    void maskedTokensNeverRepeatAndNeverContainTheRawToken() {
        var session = new Session();
        Csrf.ensureToken(session);
        String raw = Csrf.getToken(session);
        String a = Csrf.maskedToken(session);
        String b = Csrf.maskedToken(session);
        assertNotEquals(a, b);
        assertFalse(a.contains(raw));
        assertTrue(Csrf.validateToken(session, a));
        assertTrue(Csrf.validateToken(session, b));
    }

    @Test
    void theRawTokenStillValidates() {
        // X-CSRF-Token headers built from Csrf.getToken keep working.
        var session = new Session();
        Csrf.ensureToken(session);
        assertTrue(Csrf.validateToken(session, Csrf.getToken(session)));
    }

    @Test
    void aMaskedTokenFromAnotherSessionIsRejected() {
        var mine = new Session();
        Csrf.ensureToken(mine);
        var theirs = new Session();
        Csrf.ensureToken(theirs);
        assertFalse(Csrf.validateToken(mine, Csrf.maskedToken(theirs)));
    }

    @Test
    void aTamperedOrMalformedMaskedTokenIsRejected() {
        var session = new Session();
        Csrf.ensureToken(session);
        String masked = Csrf.maskedToken(session);
        char c = masked.charAt(10);
        String tampered = masked.substring(0, 10) + (c == 'A' ? 'B' : 'A') + masked.substring(11);
        assertFalse(Csrf.validateToken(session, tampered));
        assertFalse(Csrf.validateToken(session, "not base64 !!"));
        assertFalse(Csrf.validateToken(session, masked.substring(0, 40)));
        assertNull(Csrf.maskedToken(new Session()));
    }

    // --- L5: storage keys cannot traverse ---

    @Test
    void traversalSegmentsAreRejected() {
        for (String key : new String[]{"../secret", "a/../../b", "a/./b", "..", "/leading"}) {
            assertThrows(IllegalArgumentException.class, () -> Storage.requireSafeKey(key),
                "should have rejected key: " + key);
        }
    }

    @Test
    void ordinaryKeysAreAccepted() {
        assertDoesNotThrow(() -> Storage.requireSafeKey("avatars/abc-123.jpg"));
        assertDoesNotThrow(() -> Storage.requireSafeKey("a/b/c/d.txt"));
        // A dot inside a segment is fine — only a whole "." or ".." segment traverses.
        assertDoesNotThrow(() -> Storage.requireSafeKey("a/..b/c"));
        assertDoesNotThrow(() -> Storage.requireSafeKey("report.2026.pdf"));
    }

    @Test
    void generatedKeysAlwaysPass() {
        assertDoesNotThrow(() -> Storage.requireSafeKey(Storage.safeKey("avatars", "../../etc/passwd")));
    }
}
