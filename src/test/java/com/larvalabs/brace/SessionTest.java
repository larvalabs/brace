package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionTest {
    private static final String SECRET = "test-secret-key-at-least-32-characters-long";

    @Test
    void setAndGet() {
        var session = new Session();
        session.set("userId", 42);
        assertEquals("42", session.get("userId"));
        assertEquals(42, session.getInt("userId"));
    }

    @Test
    void has() {
        var session = new Session();
        assertFalse(session.has("key"));
        session.set("key", "value");
        assertTrue(session.has("key"));
    }

    @Test
    void remove() {
        var session = new Session();
        session.set("key", "value");
        session.remove("key");
        assertFalse(session.has("key"));
    }

    @Test
    void clear() {
        var session = new Session();
        session.set("a", "1");
        session.set("b", "2");
        session.clear();
        assertFalse(session.has("a"));
        assertFalse(session.has("b"));
    }

    @Test
    void toCookieAndFromCookie() {
        var session = new Session();
        session.set("userId", 42);
        session.set("role", "admin");
        var cookie = session.toCookie(SECRET);

        var restored = Session.fromCookie(cookie, SECRET);
        assertEquals("42", restored.get("userId"));
        assertEquals("admin", restored.get("role"));
    }

    @Test
    void invalidSignatureReturnsEmptySession() {
        var session = new Session();
        session.set("userId", 42);
        var cookie = session.toCookie(SECRET);

        var tampered = Session.fromCookie(cookie, "wrong-secret");
        assertFalse(tampered.has("userId"));
    }

    @Test
    void nullCookieReturnsEmptySession() {
        var session = Session.fromCookie(null, SECRET);
        assertFalse(session.has("anything"));
    }

    @Test
    void ofFactory() {
        var session = Session.of("userId", 1, "role", "admin");
        assertEquals(1, session.getInt("userId"));
        assertEquals("admin", session.get("role"));
    }

    @Test
    void modifiedTracking() {
        var session = new Session();
        assertFalse(session.isModified());
        session.set("key", "value");
        assertTrue(session.isModified());
    }

    @Test
    void flashStoresDataWithPrefix() {
        var session = new Session();
        session.flash("success", "Item saved!");
        assertEquals("Item saved!", session.get("_flash:success"));
    }

    @Test
    void consumeFlashMovesDataToFlashMap() {
        var session = new Session();
        session.flash("success", "Item saved!");
        session.flash("error", "Something failed");
        session.consumeFlash();

        // Flash data available via flash(key) and flashData()
        assertEquals("Item saved!", session.flash("success"));
        assertEquals("Something failed", session.flash("error"));
        assertEquals(2, session.flashData().size());

        // Removed from session data
        assertFalse(session.has("_flash:success"));
        assertFalse(session.has("_flash:error"));
    }

    @Test
    void flashDataGoneAfterSecondConsume() {
        var session = new Session();
        session.flash("success", "Item saved!");

        // First consume: flash is available
        session.consumeFlash();
        assertEquals("Item saved!", session.flash("success"));

        // Serialize and deserialize (simulating a new request)
        var cookie = session.toCookie(SECRET);
        var restored = Session.fromCookie(cookie, SECRET);
        restored.consumeFlash();

        // Flash data should not be present
        assertNull(restored.flash("success"));
        assertTrue(restored.flashData().isEmpty());
    }

    @Test
    void derivedKeyIsCached() {
        // Clear cache to start fresh
        Session.clearKeyCache();

        // Get the same key twice for the same secret
        var key1 = Session.getCachedKey(SECRET);
        var key2 = Session.getCachedKey(SECRET);

        // Verify object identity: caching returns the same instance
        assertSame(key1, key2, "Derived keys for the same secret should return the same cached instance");
    }

    // -------------------------------------------------------------------------
    // M2: server-side session expiry (_exp in encrypted payload)
    // -------------------------------------------------------------------------

    @Test
    void expiredSessionReturnsEmpty() {
        // Encrypted payload with an _exp deadline already in the past → emptied on read.
        var expired = expiredCookie(SECRET, 42);
        var restored = Session.fromCookie(expired, SECRET);
        assertFalse(restored.has("userId"), "Expired session must be emptied");
        assertNull(restored.get("userId"));
    }

    @Test
    void futureExpiryKeepsDataIntact() {
        var session = new Session();
        session.set("userId", 42);
        session.set("role", "admin");
        session.maxAgeSeconds(3600); // 1 hour in the future
        var cookie = session.toCookie(SECRET);

        var restored = Session.fromCookie(cookie, SECRET);
        assertEquals("42", restored.get("userId"));
        assertEquals("admin", restored.get("role"));
    }

    @Test
    void legacyCookieWithoutExpiryIsAccepted() {
        // Simulate a ≤0.1.6 cookie: encrypted payload with NO _exp entry.
        var legacy = legacyCookieWithoutExp(SECRET, "userId", "42");
        var restored = Session.fromCookie(legacy, SECRET);
        assertEquals("42", restored.get("userId"),
            "Legacy cookies without _exp are accepted for this release");
    }

    @Test
    void expiryKeyNotVisibleViaSessionApi() {
        var session = new Session();
        session.set("userId", 42);
        session.maxAgeSeconds(3600);
        var cookie = session.toCookie(SECRET);

        var restored = Session.fromCookie(cookie, SECRET);
        assertFalse(restored.has(Session.EXPIRY_KEY), "_exp must be stripped from user-visible data");
        assertNull(restored.get(Session.EXPIRY_KEY));
        assertNull(restored.get("_exp"));
    }

    @Test
    void handlerCannotSetExpiryKey() {
        var session = new Session();
        session.set(Session.EXPIRY_KEY, "9999999999");
        assertFalse(session.has(Session.EXPIRY_KEY), "set(\"_exp\", ...) must be silently ignored");
        assertFalse(session.isModified(), "Reserved-key set must not mark the session modified");

        // And a forged _exp does not survive a round trip either — the real one is stamped fresh.
        session.set("userId", 7);
        var restored = Session.fromCookie(session.toCookie(SECRET), SECRET);
        assertEquals("7", restored.get("userId"));
        assertFalse(restored.has("_exp"));
    }

    @Test
    void defaultHorizonAppliedWhenNoMaxAge() {
        // No maxAgeSeconds set → DEFAULT_MAX_AGE_SECONDS (14 days). The cookie must still be
        // valid now and carry a future expiry.
        var session = new Session();
        session.set("userId", 42);
        var cookie = session.toCookie(SECRET);

        var restored = Session.fromCookie(cookie, SECRET);
        assertEquals("42", restored.get("userId"));
        assertEquals(14L * 24 * 60 * 60, Session.DEFAULT_MAX_AGE_SECONDS);
    }

    @Test
    void customMaxAgeFlowsThrough() {
        // A very short custom horizon that is still in the future stays valid.
        var session = new Session();
        session.set("userId", 42);
        session.maxAgeSeconds(60);
        var cookie = session.toCookie(SECRET);
        assertEquals("42", Session.fromCookie(cookie, SECRET).get("userId"));
    }

    /** Build an encrypted cookie whose _exp is already in the past, reusing the real codec. */
    private static String expiredCookie(String secret, int userId) {
        // toCookie always stamps a future _exp, so we hand-roll a payload via the legacy helper
        // shape but with a past _exp value.
        return encryptPayload(secret, "{\"userId\":\"" + userId + "\",\"_exp\":\"1\"}");
    }

    /** Build an encrypted cookie with NO _exp entry, emulating a ≤0.1.6 cookie. */
    private static String legacyCookieWithoutExp(String secret, String key, String value) {
        return encryptPayload(secret, "{\"" + key + "\":\"" + value + "\"}");
    }

    /** Encrypt an arbitrary JSON payload with the same AES-256-GCM scheme Session uses. */
    private static String encryptPayload(String secret, String json) {
        try {
            var key = Session.getCachedKey(secret);
            byte[] nonce = new byte[12];
            new java.security.SecureRandom().nextBytes(nonce);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key,
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
            byte[] ct = cipher.doFinal(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ct, 0, combined, nonce.length, ct.length);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void cookieRoundTripWithCachedKey() {
        // Clear cache to start fresh
        Session.clearKeyCache();

        var session = new Session();
        session.set("userId", 123);
        session.set("role", "user");

        // First cookie creation (fills cache)
        var cookie1 = session.toCookie(SECRET);

        // Second cookie creation (uses cache)
        var cookie2 = session.toCookie(SECRET);

        // Both should decrypt successfully
        var restored1 = Session.fromCookie(cookie1, SECRET);
        var restored2 = Session.fromCookie(cookie2, SECRET);

        assertEquals("123", restored1.get("userId"));
        assertEquals("user", restored1.get("role"));
        assertEquals("123", restored2.get("userId"));
        assertEquals("user", restored2.get("role"));
    }
}
