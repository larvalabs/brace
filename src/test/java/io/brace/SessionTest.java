package io.brace;

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
}
