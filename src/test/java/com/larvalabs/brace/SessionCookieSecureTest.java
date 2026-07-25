package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2 (2026-07 security review): the session cookie used to ship without the {@code Secure}
 * attribute for every app built the documented way ({@code .sessions(secret)}), disclosing it
 * on any cleartext request to the domain. It is now resolved per request — on everywhere
 * except loopback — with an explicit {@code .secure(...)} always winning.
 */
class SessionCookieSecureTest {

    // --- SessionOptions.resolveSecure ---

    @Test
    void defaultIsSecureForARealHost() {
        var opts = SessionOptions.of("a-secret-that-is-at-least-32-chars-x");
        assertTrue(opts.resolveSecure(false, false), "non-loopback request must get Secure");
    }

    @Test
    void defaultIsNotSecureOnLoopback() {
        var opts = SessionOptions.of("a-secret-that-is-at-least-32-chars-x");
        assertFalse(opts.resolveSecure(true, false),
            "localhost must stay usable over plain http for dev and in-process tests");
    }

    @Test
    void trustedForwardedHttpsWinsOverALoopbackHost() {
        // A proxy that rewrites Host to the upstream would otherwise read as localhost.
        var opts = SessionOptions.of("a-secret-that-is-at-least-32-chars-x");
        assertTrue(opts.resolveSecure(true, true));
    }

    @Test
    void explicitSettingWinsInBothDirections() {
        var secret = "a-secret-that-is-at-least-32-chars-x";
        var off = SessionOptions.of(secret).secure(false);
        assertFalse(off.resolveSecure(false, false), "explicit false must survive a real host");
        assertFalse(off.resolveSecure(false, true), "explicit false must survive forwarded https");

        var on = SessionOptions.of(secret).secure(true);
        assertTrue(on.resolveSecure(true, false), "explicit true must survive a loopback host");
    }

    @Test
    void secureFactoryAndSameSiteNoneStillForceSecure() {
        var secret = "a-secret-that-is-at-least-32-chars-x";
        assertTrue(SessionOptions.secure(secret).resolveSecure(true, false));
        assertTrue(SessionOptions.of(secret).sameSiteNone().resolveSecure(true, false));
    }

    // --- Host-header classification ---

    @Test
    void loopbackHostsAreRecognised() {
        assertTrue(BraceHandler.isLoopbackHost("localhost"));
        assertTrue(BraceHandler.isLoopbackHost("localhost:8080"));
        assertTrue(BraceHandler.isLoopbackHost("LOCALHOST:8080"));
        assertTrue(BraceHandler.isLoopbackHost("127.0.0.1"));
        assertTrue(BraceHandler.isLoopbackHost("127.0.0.1:3000"));
        assertTrue(BraceHandler.isLoopbackHost("127.1.2.3"));
        assertTrue(BraceHandler.isLoopbackHost("[::1]:8080"));
        assertTrue(BraceHandler.isLoopbackHost("::1"));
    }

    @Test
    void realHostsAreNotLoopback() {
        assertFalse(BraceHandler.isLoopbackHost("example.com"));
        assertFalse(BraceHandler.isLoopbackHost("example.com:443"));
        assertFalse(BraceHandler.isLoopbackHost("10.0.0.5"));
        assertFalse(BraceHandler.isLoopbackHost("127.evil.com"), "must not prefix-match a hostname");
        assertFalse(BraceHandler.isLoopbackHost("localhost.evil.com"));
    }

    @Test
    void missingHostFailsSafe() {
        assertFalse(BraceHandler.isLoopbackHost(null));
        assertFalse(BraceHandler.isLoopbackHost(""));
        assertFalse(BraceHandler.isLoopbackHost("   "));
    }
}
