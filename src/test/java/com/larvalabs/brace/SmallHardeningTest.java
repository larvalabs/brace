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
        assertTrue(field.contains("&lt;script&gt;"), field);
    }

    @Test
    void hiddenFieldIsUnchangedForARealToken() {
        var session = new Session();
        Csrf.ensureToken(session);
        String token = Csrf.getToken(session);
        assertEquals("<input type=\"hidden\" name=\"_csrf\" value=\"" + token + "\">",
            Csrf.hiddenField(session));
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
