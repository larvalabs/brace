package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 (2026-07 security review): WebSocket upgrades were accepted from any origin and the
 * session cookie was decrypted straight into the handler's WsContext — cross-site WebSocket
 * hijacking, held off only by the default {@code SameSite=Lax} cookie, which an app can turn
 * off with {@code sameSiteNone()}.
 */
class WsOriginTest {

    private static final List<String> NONE = List.of();

    @Test
    void sameOriginIsAllowed() {
        assertTrue(Brace.originAllowed("https://app.example.com", "app.example.com", NONE));
    }

    @Test
    void sameHostAcrossSchemeAndPortIsAllowed() {
        // TLS terminates at the proxy: the browser's origin is https on 443 while the app is
        // reached as host:8080. Comparing host only is what makes that work.
        assertTrue(Brace.originAllowed("https://app.example.com", "app.example.com:8080", NONE));
        assertTrue(Brace.originAllowed("https://app.example.com:443", "app.example.com", NONE));
        assertTrue(Brace.originAllowed("http://localhost:3000", "localhost:3000", NONE));
    }

    @Test
    void foreignOriginIsRejected() {
        assertFalse(Brace.originAllowed("https://evil.com", "app.example.com", NONE));
        assertFalse(Brace.originAllowed("http://evil.com", "app.example.com", NONE));
    }

    @Test
    void lookalikeOriginsAreRejected() {
        assertFalse(Brace.originAllowed("https://app.example.com.evil.com", "app.example.com", NONE));
        assertFalse(Brace.originAllowed("https://evil.com/app.example.com", "app.example.com", NONE));
        assertFalse(Brace.originAllowed("https://notapp.example.com", "app.example.com", NONE));
    }

    @Test
    void missingOriginIsAllowedForNonBrowserClients() {
        assertTrue(Brace.originAllowed(null, "app.example.com", NONE));
        assertTrue(Brace.originAllowed("", "app.example.com", NONE));
        assertTrue(Brace.originAllowed("   ", "app.example.com", NONE));
    }

    @Test
    void configuredOriginIsAllowed() {
        assertTrue(Brace.originAllowed("https://studio.example.com", "app.example.com",
            List.of("https://studio.example.com")));
        // A bare host entry matches any scheme/port.
        assertTrue(Brace.originAllowed("http://studio.example.com:5173", "app.example.com",
            List.of("studio.example.com")));
        assertFalse(Brace.originAllowed("https://other.example.com", "app.example.com",
            List.of("https://studio.example.com")));
    }

    @Test
    void wildcardDisablesTheCheck() {
        assertTrue(Brace.originAllowed("https://evil.com", "app.example.com", List.of("*")));
    }

    @Test
    void unparseableOriginFailsClosed() {
        assertFalse(Brace.originAllowed("https://", "app.example.com", NONE));
        assertFalse(Brace.originAllowed("null", "app.example.com", NONE),
            "the literal \"null\" origin (sandboxed iframe, file://) is not the app's host");
    }

    @Test
    void missingHostHeaderFallsBackToTheAllowlist() {
        assertFalse(Brace.originAllowed("https://evil.com", null, NONE));
        assertTrue(Brace.originAllowed("https://ok.example.com", null, List.of("ok.example.com")));
    }
}
