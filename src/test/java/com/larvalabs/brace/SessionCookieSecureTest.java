package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
    // --- Host-rewriting proxies (nginx's default proxy_pass) ---

    @Test
    void httpsPageOnARealHostIsRecognisedFromOriginOrReferer() {
        assertEquals("app.example.com", ProxyHeaders.httpsPageHost("https://app.example.com", null));
        assertEquals("app.example.com", ProxyHeaders.httpsPageHost(null, "https://app.example.com/login?x=1"));
        assertEquals("app.example.com", ProxyHeaders.httpsPageHost("null", "https://app.example.com/"),
            "Origin: null (privacy-sensitive context) falls back to Referer");
    }

    @Test
    void httpsPageSignalIgnoresHttpLoopbackAndGarbage() {
        assertNull(ProxyHeaders.httpsPageHost("http://app.example.com", null), "plain http is not a TLS proxy");
        assertNull(ProxyHeaders.httpsPageHost("https://localhost:8443", null), "local https dev");
        assertNull(ProxyHeaders.httpsPageHost(null, null));
        assertNull(ProxyHeaders.httpsPageHost("https://", null));
        assertNull(ProxyHeaders.httpsPageHost("null", null));
    }

    @Test
    void forwardedHostIsHonouredOnlyFromATrustedProxy() {
        Map<String, String> headers = Map.of("Host", "127.0.0.1:8080", "X-Forwarded-Host", "app.example.com, edge");
        var trusted = new TrustedProxies("10.0.0.0/8");
        assertEquals("app.example.com", ProxyHeaders.effectiveHost(headers::get, "10.1.2.3", trusted));
        assertEquals("127.0.0.1:8080", ProxyHeaders.effectiveHost(headers::get, "203.0.113.9", trusted),
            "a direct client cannot claim a host");
        assertEquals("127.0.0.1:8080", ProxyHeaders.effectiveHost(headers::get, "10.1.2.3", null));
    }

    @Test
    void cookieBehindAHostRewritingTlsProxyGetsSecure() throws Exception {
        // In-process: every request reaches the app as Host: localhost:<port>, exactly what nginx's
        // default proxy_pass produces. The browser's https Origin is what gives the proxy away.
        var testApp = Brace.test().sessions("a-secret-that-is-at-least-32-chars-x").start(app ->
            app.get("/touch", (SessionHandler) (req, session) -> {
                session.set("k", "v");
                return Result.text("ok");
            }));
        try {
            String viaProxy = testApp.request("GET", "/touch")
                .header("Origin", "https://app.example.com").send().header("Set-Cookie");
            assertTrue(viaProxy.contains("; Secure"), viaProxy);

            String fromReferer = testApp.request("GET", "/touch")
                .header("Referer", "https://app.example.com/account").send().header("Set-Cookie");
            assertTrue(fromReferer.contains("; Secure"), fromReferer);

            String localDev = testApp.request("GET", "/touch")
                .header("Referer", "http://localhost:8080/account").send().header("Set-Cookie");
            assertFalse(localDev.contains("; Secure"), "local dev over http keeps working: " + localDev);
        } finally {
            testApp.stop();
        }
    }
}
