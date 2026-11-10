package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification fix for L3 (docs/2026-11-09-security-review-todos.md): the element the trusted
 * peer appended (the rightmost) decides. When it names no client (no {@code for=}, empty, not an
 * address) the answer is {@code unknown}; the walk never skips past it to an earlier element,
 * which the client wrote. Before, {@code Forwarded: for=6.6.6.6} plus the proxy's
 * {@code Forwarded: proto=https} gave {@code 6.6.6.6}.
 */
class ForwardedRightmostElementTest {

    static final String[] PROXIES = {"127.0.0.1", "::1", "10.0.0.7"};

    static Brace xffApp;
    static Brace forwardedApp;

    @BeforeAll
    static void start() throws Exception {
        xffApp = Brace.app().host("127.0.0.1").port(0).banner(false).trustedProxies(PROXIES);
        xffApp.get("/ip", req -> Result.text(req.ip()));
        xffApp.start();
        forwardedApp = Brace.app().host("127.0.0.1").port(0).banner(false)
            .trustedProxies(new TrustedProxies(PROXIES).useForwardedHeader());
        forwardedApp.get("/ip", req -> Result.text(req.ip()));
        forwardedApp.start();
    }

    @AfterAll
    static void stop() throws Exception {
        xffApp.stop();
        forwardedApp.stop();
    }

    private static String ipFrom(Brace app, String... headerLines) throws Exception {
        try (var socket = new Socket("127.0.0.1", app.actualPort())) {
            socket.setSoTimeout(10_000);
            var req = new StringBuilder("GET /ip HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            for (var line : headerLines) req.append(line).append("\r\n");
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var out = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(out);
            var response = out.toString(StandardCharsets.ISO_8859_1);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            return response.substring(response.indexOf("\r\n\r\n") + 4).strip();
        }
    }

    // ---- Forwarded mode ----

    @Test
    void aProxyElementWithoutForIsUnknown() throws Exception {
        assertEquals("unknown", ipFrom(forwardedApp, "Forwarded: for=6.6.6.6", "Forwarded: proto=https"));
        assertEquals("unknown", ipFrom(forwardedApp, "Forwarded: for=6.6.6.6, proto=https"));
    }

    @Test
    void anEmptyProxyElementIsUnknown() throws Exception {
        assertEquals("unknown", ipFrom(forwardedApp, "Forwarded: for=6.6.6.6,", "Forwarded:"));
        assertEquals("unknown", ipFrom(forwardedApp, "Forwarded: for=6.6.6.6", "Forwarded:"));
    }

    @Test
    void trustedProxyElementsAreStillWalkedPast() throws Exception {
        assertEquals("203.0.113.9", ipFrom(forwardedApp, "Forwarded: for=203.0.113.9", "Forwarded: for=10.0.0.7"));
        // ... but an element with no client to the left of a trusted hop is unknown too.
        assertEquals("unknown", ipFrom(forwardedApp, "Forwarded: for=6.6.6.6, proto=http", "Forwarded: for=10.0.0.7"));
    }

    // ---- X-Forwarded-For mode ----

    @Test
    void anEmptyXForwardedForLineIsUnknown() throws Exception {
        assertEquals("unknown", ipFrom(xffApp, "X-Forwarded-For: 6.6.6.6", "X-Forwarded-For:"));
        assertEquals("unknown", ipFrom(xffApp, "X-Forwarded-For: 6.6.6.6,"));
        assertEquals("unknown", ipFrom(xffApp, "X-Forwarded-For: 6.6.6.6, , 10.0.0.7"));
    }

    @Test
    void xffStillWalksPastTrustedHops() throws Exception {
        assertEquals("203.0.113.5", ipFrom(xffApp, "X-Forwarded-For: 6.6.6.6", "X-Forwarded-For: 203.0.113.5, 10.0.0.7"));
    }

    // ---- hand-built requests (single-value header) ----

    @Test
    void handBuiltRequestsFollowTheSameRule() {
        var proxies = new TrustedProxies(PROXIES);
        var xff = new Request("GET", "/", Map.of(), Map.of(), Map.of("X-Forwarded-For", "6.6.6.6, "), null,
            Map.of(), "127.0.0.1", proxies);
        assertEquals("unknown", xff.ip());
        var fwd = new Request("GET", "/", Map.of(), Map.of(), Map.of("Forwarded", "for=6.6.6.6, proto=https"), null,
            Map.of(), "127.0.0.1", new TrustedProxies(PROXIES).useForwardedHeader());
        assertEquals("unknown", fwd.ip());
    }
}
