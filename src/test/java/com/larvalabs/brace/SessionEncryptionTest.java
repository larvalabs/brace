package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class SessionEncryptionTest {

    @Test
    public void testEncryptionProducesOpaqueValue() {
        var session = Session.of("userId", "12345", "email", "user@example.com");
        String cookie = session.toCookie("test-secret");

        // Cookie should not contain plaintext data
        assertFalse(cookie.contains("userId"));
        assertFalse(cookie.contains("12345"));
        assertFalse(cookie.contains("user@example.com"));
        assertFalse(cookie.contains("email"));
    }

    @Test
    public void testDecryptionRoundtrip() {
        var original = Session.of("userId", "12345", "role", "admin", "theme", "dark");
        String cookie = original.toCookie("test-secret");

        var decrypted = Session.fromCookie(cookie, "test-secret");

        assertEquals("12345", decrypted.get("userId"));
        assertEquals("admin", decrypted.get("role"));
        assertEquals("dark", decrypted.get("theme"));
    }

    @Test
    public void testWrongSecretFailsDecryption() {
        var session = Session.of("userId", "12345");
        String cookie = session.toCookie("correct-secret");

        var decrypted = Session.fromCookie(cookie, "wrong-secret");

        // Should return empty session on decryption failure
        assertNull(decrypted.get("userId"));
    }

    @Test
    public void testTamperedCookieFailsDecryption() {
        var session = Session.of("userId", "12345");
        String cookie = session.toCookie("test-secret");

        // Tamper with the cookie by changing a character
        String tampered = cookie.substring(0, cookie.length() - 5) + "XXXXX";

        var decrypted = Session.fromCookie(tampered, "test-secret");

        // Should return empty session on authentication failure
        assertNull(decrypted.get("userId"));
    }

    @Test
    public void testNonceIsRandomPerCookie() {
        var session = Session.of("userId", "12345");
        String cookie1 = session.toCookie("test-secret");
        String cookie2 = session.toCookie("test-secret");

        // Same data, same secret -> different cookies (random nonce)
        assertNotEquals(cookie1, cookie2);

        // Both should decrypt correctly
        assertEquals("12345", Session.fromCookie(cookie1, "test-secret").get("userId"));
        assertEquals("12345", Session.fromCookie(cookie2, "test-secret").get("userId"));
    }

    @Test
    public void testMalformedCookieReturnsEmptySession() {
        assertNotNull(Session.fromCookie("not-valid-base64!@#", "secret"));
        assertNotNull(Session.fromCookie("", "secret"));
        assertNotNull(Session.fromCookie(null, "secret"));
    }

    @Test
    public void testTooShortCookieReturnsEmptySession() {
        // Create a cookie that's too short (less than nonce + auth tag)
        String tooShort = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[20]);
        var session = Session.fromCookie(tooShort, "secret");
        assertNotNull(session);
    }

    @Test
    public void testEmptySessionEncryptDecrypt() {
        var empty = new Session();
        String cookie = empty.toCookie("secret");

        var decrypted = Session.fromCookie(cookie, "secret");
        assertNotNull(decrypted);
        assertNull(decrypted.get("anything"));
    }

    @Test
    public void testLargeSessionData() {
        var session = new Session();
        // Add multiple keys to create larger payload
        for (int i = 0; i < 50; i++) {
            session.set("key" + i, "value" + i);
        }

        String cookie = session.toCookie("secret");
        var decrypted = Session.fromCookie(cookie, "secret");

        // Verify all data survived encryption/decryption
        for (int i = 0; i < 50; i++) {
            assertEquals("value" + i, decrypted.get("key" + i));
        }
    }

    @Test
    public void testSpecialCharactersInSessionData() {
        var session = Session.of(
            "name", "O'Brien",
            "bio", "Quotes: \"Hello\"\nNewlines\tand\ttabs",
            "unicode", "日本語 émojis 🎉"
        );

        String cookie = session.toCookie("secret");
        var decrypted = Session.fromCookie(cookie, "secret");

        assertEquals("O'Brien", decrypted.get("name"));
        assertEquals("Quotes: \"Hello\"\nNewlines\tand\ttabs", decrypted.get("bio"));
        assertEquals("日本語 émojis 🎉", decrypted.get("unicode"));
    }

    @Test
    public void testDifferentSecretsProduceDifferentCiphertexts() {
        var session = Session.of("userId", "12345");
        String cookie1 = session.toCookie("secret1");
        String cookie2 = session.toCookie("secret2");

        // Different secrets should produce different ciphertexts
        assertNotEquals(cookie1, cookie2);

        // Each should only decrypt with its own secret
        assertEquals("12345", Session.fromCookie(cookie1, "secret1").get("userId"));
        assertNull(Session.fromCookie(cookie1, "secret2").get("userId"));
        assertEquals("12345", Session.fromCookie(cookie2, "secret2").get("userId"));
        assertNull(Session.fromCookie(cookie2, "secret1").get("userId"));
    }

    @Test
    public void testCookieFormatIsBase64UrlSafe() {
        var session = Session.of("userId", "12345");
        String cookie = session.toCookie("secret");

        // Should be valid base64url (no +, /, or =)
        assertFalse(cookie.contains("+"));
        assertFalse(cookie.contains("/"));
        assertFalse(cookie.contains("="));

        // Should decode without error
        assertDoesNotThrow(() -> Base64.getUrlDecoder().decode(cookie));
    }
}
