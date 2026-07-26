package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness review M12: {@code sameSite("None")} must imply {@code Secure}. Browsers reject
 * {@code SameSite=None} without {@code Secure} outright, so the combination did not weaken the
 * session cookie — it discarded it, and the symptom (nobody stays logged in) pointed nowhere near
 * the config line. {@code sameSiteNone()} always set Secure; the string form silently did not.
 */
class SessionOptionsSameSiteTest {

    private static final String SECRET = "session-options-same-site-secret-32+";

    @Test
    void stringNoneImpliesSecure() {
        var opts = SessionOptions.of(SECRET).sameSite("None");
        assertTrue(opts.secure(), "SameSite=None must imply Secure");
        assertTrue(opts.buildSetCookie("v").contains("; Secure"));
        assertTrue(opts.buildSetCookie("v").contains("; SameSite=None"));
    }

    @Test
    void stringNoneIsCaseInsensitive() {
        assertTrue(SessionOptions.of(SECRET).sameSite("none").secure());
        assertTrue(SessionOptions.of(SECRET).sameSite("NONE").secure());
    }

    @Test
    void valuesAreNormalizedToTheCanonicalSpelling() {
        assertEquals("Strict", SessionOptions.of(SECRET).sameSite("strict").sameSite());
        assertEquals("Lax", SessionOptions.of(SECRET).sameSite("LAX").sameSite());
    }

    @Test
    void strictAndLaxDoNotForceSecure() {
        assertFalse(SessionOptions.of(SECRET).sameSite("Strict").secure());
        assertFalse(SessionOptions.of(SECRET).sameSite("Lax").secure());
    }

    @Test
    void invalidValuesAreRejectedRatherThanWrittenVerbatim() {
        // A bogus value used to reach the header, where browsers ignore the attribute entirely
        // and fall back to their own default — a silent downgrade.
        assertThrows(IllegalArgumentException.class, () -> SessionOptions.of(SECRET).sameSite("Loose"));
        assertThrows(IllegalArgumentException.class, () -> SessionOptions.of(SECRET).sameSite(""));
        assertThrows(IllegalArgumentException.class, () -> SessionOptions.of(SECRET).sameSite(null));
    }

    @Test
    void fluentNoneHelperStillBehavesTheSame() {
        var opts = SessionOptions.of(SECRET).sameSiteNone();
        assertTrue(opts.secure());
        assertEquals("None", opts.sameSite());
    }
}
