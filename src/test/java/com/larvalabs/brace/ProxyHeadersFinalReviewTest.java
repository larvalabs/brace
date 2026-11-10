package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final review fixes for L3 (docs/2026-11-09-security-review-todos.md): a client's unbalanced
 * quote in {@code Forwarded} must not swallow the proxy's own element, a trusted proxy's address
 * is never returned as the client's when the forwarding header names no usable address,
 * {@code X-Forwarded-Host} must be a host, and {@code req.ip()} is canonical.
 */
class ProxyHeadersFinalReviewTest {

    static Brace forwardedApp;
    static Brace xffApp;

    @BeforeAll
    static void start() throws Exception {
        forwardedApp = Brace.app().port(0).banner(false)
            .trustedProxies(new TrustedProxies("127.0.0.1", "::1").useForwardedHeader())
            .defaultAccess(Access.none());
        forwardedApp.get("/ip", req -> Result.text(req.ip()));
        forwardedApp.start();
        xffApp = Brace.app().port(0).banner(false).trustedProxies("127.0.0.1", "::1")
            .defaultAccess(Access.none());
        xffApp.get("/ip", req -> Result.text(req.ip()));
        xffApp.get("/host", req -> Result.text(String.valueOf(req.host())));
        xffApp.start();
    }

    @AfterAll
    static void stop() throws Exception {
        forwardedApp.stop();
        xffApp.stop();
    }

    @BeforeEach
    void resetWarning() {
        Request.resetForwardingHeaderWarning();
    }

    private static String get(Brace app, String path, String... headerLines) throws Exception {
        try (var socket = new Socket("127.0.0.1", app.actualPort())) {
            socket.setSoTimeout(10_000);
            var req = new StringBuilder("GET " + path + " HTTP/1.1\r\nConnection: close\r\n");
            boolean hasHost = false;
            for (var line : headerLines) {
                req.append(line).append("\r\n");
                hasHost |= line.regionMatches(true, 0, "Host:", 0, 5);
            }
            if (!hasHost) req.append("Host: localhost\r\n");
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            var out = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(out);
            var response = out.toString(StandardCharsets.ISO_8859_1);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            return response.substring(response.indexOf("\r\n\r\n") + 4).strip();
        }
    }

    private static Request from(String peer, Map<String, String> headers, TrustedProxies proxies) {
        return new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), peer, proxies);
    }

    private static String forwardedIp(String value) {
        return from("127.0.0.1", Map.of("Forwarded", value),
            new TrustedProxies("127.0.0.1", "10.0.0.7").useForwardedHeader()).ip();
    }

    private static String xffIp(String value) {
        return from("127.0.0.1", Map.of("X-Forwarded-For", value), new TrustedProxies("127.0.0.1", "10.0.0.7")).ip();
    }

    // ---- M-A part 1: the proxy's element is never absorbed by earlier client text ----

    @Test
    void unbalancedQuoteOnAClientLineDoesNotSwallowTheProxysLine() throws Exception {
        assertEquals("203.0.113.9", get(forwardedApp, "/ip", "Forwarded: for=\"x", "Forwarded: for=203.0.113.9"));
        assertEquals("203.0.113.9", get(forwardedApp, "/ip", "Forwarded: by=\"x", "Forwarded: for=203.0.113.9"));
        assertEquals("203.0.113.9", get(forwardedApp, "/ip", "Forwarded: for=\"a\\", "Forwarded: for=203.0.113.9"));
        assertEquals("203.0.113.9", get(forwardedApp, "/ip", "Forwarded: for=\"x, for=203.0.113.9"));
    }

    @Test
    void unbalancedQuoteCannotPairWithAQuoteInTheProxysElement() {
        assertEquals("2001:db8::9", forwardedIp("for=\"x, for=\"[2001:db8::9]:4711\""));
        assertEquals("203.0.113.9", forwardedIp("for=\"x, for=\"203.0.113.9\";proto=https"));
        assertEquals("203.0.113.9", forwardedIp("for=\"x\\\", for=203.0.113.9"));
        assertEquals("203.0.113.9", forwardedIp("\"\"\", for=6.6.6.6;by=\", for=203.0.113.9"));
    }

    @Test
    void aMalformedEarlierElementOnlyInvalidatesItself() {
        // 10.0.0.7 is a trusted hop, so the walk continues to the element before it.
        assertEquals("203.0.113.9", forwardedIp("for=\"x, for=203.0.113.9, for=10.0.0.7"));
        // The element the walk selects is the malformed one: not an address, so "unknown".
        assertEquals("unknown", forwardedIp("for=203.0.113.9, for=\"x, for=10.0.0.7"));
        // A well-formed quoted comma is still one value.
        assertEquals("203.0.113.7", forwardedIp("for=\"203.0.113.7\";proto=\"h,t\\\"tp\", for=10.0.0.7"));
    }

    // ---- M-A part 2: a trusted proxy's own address is never the client's by default ----

    @Test
    void nonAddressSelectedEntryIsUnknownNotTheProxy() {
        assertEquals("unknown", forwardedIp("for=\"a,b\""));
        assertEquals("unknown", forwardedIp("for=_hidden"));
        assertEquals("unknown", forwardedIp("for=unknown"));
        assertEquals("unknown", forwardedIp("proto=https"), "no for= at all");
        assertEquals("unknown", forwardedIp(","));
        assertEquals("unknown", xffIp("<script>"));
        assertEquals("unknown", xffIp("203.0.113.5, unknown"));
        assertEquals("unknown", xffIp(","));
        assertEquals("unknown", xffIp(" "));
    }

    @Test
    void theOtherForwardingHeaderAloneIsUnknown() {
        // The peer is proxying (it sent a forwarding header), but not the one Brace reads.
        assertEquals("unknown", from("127.0.0.1", Map.of("Forwarded", "for=203.0.113.9"),
            new TrustedProxies("127.0.0.1")).ip());
        assertTrue(Request.warnedForwardingHeader());
        assertEquals("unknown", from("127.0.0.1", Map.of("X-Forwarded-For", "203.0.113.9"),
            new TrustedProxies("127.0.0.1").useForwardedHeader()).ip());
    }

    @Test
    void aTrustedPeerSendingNoForwardingHeaderIsItself() {
        // A health check or a local process on the proxy host: nothing was forwarded.
        assertEquals("127.0.0.1", from("127.0.0.1", Map.of(), new TrustedProxies("127.0.0.1")).ip());
    }

    @Test
    void anUntrustedPeerIsAlwaysItsSocketAddress() {
        var proxies = new TrustedProxies("10.0.0.0/8").useForwardedHeader();
        assertEquals("203.0.113.50", from("203.0.113.50", Map.of("Forwarded", "for=\"x"), proxies).ip());
        assertEquals("203.0.113.50", from("203.0.113.50", Map.of("X-Forwarded-For", "junk"),
            new TrustedProxies("10.0.0.0/8")).ip());
        assertEquals("203.0.113.50", from("203.0.113.50", Map.of("X-Forwarded-For", "junk"), null).ip());
    }

    @Test
    void unknownSharesOnePerIpBucketAndMatchesNoProxyRange() {
        assertEquals("unknown", RateLimiter.ipBucket("unknown"));
        assertFalse(new TrustedProxies("0.0.0.0/0", "::/0").isTrusted("unknown"),
            "an address allowlist built on TrustedProxies fails closed on the sentinel");
    }

    // ---- nit: X-Forwarded-Host must be a host[:port] ----

    @Test
    void forwardedHostThatIsNotAHostIsIgnored() throws Exception {
        assertEquals("real.example", get(xffApp, "/host", "Host: real.example",
            "X-Forwarded-Host: app.example, \"evil,x\""));
        assertEquals("real.example", get(xffApp, "/host", "Host: real.example", "X-Forwarded-Host: a b"));
        assertEquals("real.example", get(xffApp, "/host", "Host: real.example", "X-Forwarded-Host: evil.example/x"));
        assertEquals("real.example", get(xffApp, "/host", "Host: real.example", "X-Forwarded-Host: user@evil.example"));
        assertEquals("real.example", get(xffApp, "/host", "Host: real.example", "X-Forwarded-Host: app.example:99999"));
        assertEquals("app.example", get(xffApp, "/host", "Host: real.example", "X-Forwarded-Host: app.example:8443"));
        assertEquals("app.example", get(xffApp, "/host", "Host: real.example", "X-Forwarded-Host: evil, app.example"));
        assertEquals("2001:db8::1", get(xffApp, "/host", "Host: real.example", "X-Forwarded-Host: [2001:db8::1]:8443"));
    }

    @Test
    void validHostPort() {
        for (var ok : new String[] {"app.example", "app.example:443", "localhost", "10.0.0.1:8080", "[::1]", "[::1]:80",
                "a_b.example", "xn--bcher-kva.example"}) {
            assertTrue(ProxyHeaders.isHostPort(ok), ok);
        }
        for (var bad : new String[] {"", "x\"", "a b", "a/b", "a@b", "a:b", "a:", ":80", "[::1", "::1", "[zz]", "a,b",
                "a:1:2", "é.example", "a:123456", ".", "a..b"}) {
            assertFalse(ProxyHeaders.isHostPort(bad), bad);
        }
    }

    // ---- nit: req.ip() is canonical ----

    @Test
    void ipIsCanonical() {
        assertEquals("2001:db8::5", xffIp("2001:DB8:0:0:0:0:0:5"));
        assertEquals("6.6.6.6", xffIp("::ffff:6.6.6.6"));
        assertEquals("6.6.6.6", xffIp("::FFFF:0606:0606"));
        assertEquals("2001:db8::1", xffIp("[2001:0db8:0000:0000:0000:0000:0000:0001]:443"));
        assertEquals("::1", from("0:0:0:0:0:0:0:1", Map.of(), null).ip());
        assertEquals("::1", from("[0:0:0:0:0:0:0:1]", Map.of(), null).ip());
        assertEquals("::", from("0:0:0:0:0:0:0:0", Map.of(), null).ip());
        assertEquals("203.0.113.5", from("203.0.113.5", Map.of(), null).ip());
    }

    @Test
    void canonicalFormFollowsRfc5952() {
        assertEquals("2001:db8::1:0:0:1", TrustedProxies.canonical("2001:db8:0:0:1:0:0:1"), "first of two equal runs");
        assertEquals("2001:db8:0:1:1:1:1:1", TrustedProxies.canonical("2001:db8:0:1:1:1:1:1"), "a single zero group stays");
        assertEquals("1::", TrustedProxies.canonical("1:0:0:0:0:0:0:0"));
        assertEquals("::2:0:0:0", TrustedProxies.canonical("0:0:0:0:2:0:0:0"), "the longer run wins");
        assertEquals("fe80::1%lo0", TrustedProxies.canonical("FE80:0:0:0:0:0:0:1%lo0"), "the zone id is kept as written");
        assertEquals("fe80::1%nosuchif9", TrustedProxies.canonical("fe80::1%nosuchif9"));
        assertEquals("64:ff9b::606:606", TrustedProxies.canonical("64:ff9b::6.6.6.6"));
        assertNull(TrustedProxies.canonical("example.com"));
        assertNull(TrustedProxies.canonical("zz:1"));
        assertNull(TrustedProxies.canonical(null));
    }

    @Test
    void socketPeerOverIpv6IsCanonical() throws Exception {
        String body;
        try (var socket = new Socket("::1", xffApp.actualPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /ip HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + "X-Forwarded-For: 2001:DB8:0:0:0:0:0:5\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            var out = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(out);
            var response = out.toString(StandardCharsets.ISO_8859_1);
            body = response.substring(response.indexOf("\r\n\r\n") + 4).strip();
        } catch (java.net.ConnectException | java.net.NoRouteToHostException e) {
            org.junit.jupiter.api.Assumptions.abort("no IPv6 loopback on this machine");
            return;
        }
        assertEquals("2001:db8::5", body);
    }
}
