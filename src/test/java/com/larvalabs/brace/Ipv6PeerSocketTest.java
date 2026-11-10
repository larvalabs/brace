package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L6 review F1: Jetty reports an IPv6 peer in brackets ({@code [0:0:0:0:0:0:0:1]}). The strict
 * literal parser added for L6 refused the brackets, so no IPv6 proxy was ever trusted (its
 * {@code X-Forwarded-For} was ignored and {@code req.ip()} was the proxy for every client) and
 * {@code perIp} never grouped a direct IPv6 client by its /64. These tests connect over the real
 * IPv6 loopback.
 */
class Ipv6PeerSocketTest {

    static Brace proxiedApp;
    static Brace directApp;
    static final AtomicReference<String> wsSeenIp = new AtomicReference<>();
    static boolean ipv6Loopback;

    @BeforeAll
    static void start() throws Exception {
        proxiedApp = Brace.app().port(0).trustedProxies("127.0.0.1", "::1", "10.0.0.7");
        proxiedApp.get("/ip", req -> Result.text(req.ip()));
        proxiedApp.before("/ws", req -> {
            wsSeenIp.set(req.ip());
            return Result.error(403, "recorded");
        });
        proxiedApp.ws("/ws", ctx -> new Object());
        proxiedApp.start();

        directApp = Brace.app().port(0);
        directApp.before("/limited", RateLimiter.perIp(2, "1m"));
        directApp.get("/limited", req -> Result.text(req.ip() + " " + RateLimiter.ipBucket(req.ip())));
        directApp.start();

        try (var s = new Socket(InetAddress.getByName("::1"), proxiedApp.actualPort())) {
            ipv6Loopback = s.isConnected();
        } catch (Exception e) {
            ipv6Loopback = false;
        }
    }

    @AfterAll
    static void stop() throws Exception {
        proxiedApp.stop();
        directApp.stop();
    }

    private static String send(Brace app, String path, String... headerLines) throws Exception {
        assumeTrue(ipv6Loopback, "no IPv6 loopback on this host");
        try (var socket = new Socket(InetAddress.getByName("::1"), app.actualPort())) {
            socket.setSoTimeout(10_000);
            var req = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            for (var line : headerLines) req.append(line).append("\r\n");
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var out = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(out);
            return out.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static String body(String response) {
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        return response.substring(response.indexOf("\r\n\r\n") + 4).strip();
    }

    @Test
    void trustedIpv6ProxyForwardsTheClientAddress() throws Exception {
        assertEquals("203.0.113.5", body(send(proxiedApp, "/ip", "X-Forwarded-For: 203.0.113.5")));
    }

    @Test
    void directIpv6ClientIpIsUnbracketed() throws Exception {
        assertEquals("0:0:0:0:0:0:0:1", body(send(proxiedApp, "/ip")));
    }

    @Test
    void webSocketUpgradeOverIpv6TrustsTheProxyToo() throws Exception {
        wsSeenIp.set(null);
        var response = send(proxiedApp, "/ws",
            "Upgrade: websocket", "Connection: Upgrade",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version: 13",
            "X-Forwarded-For: 203.0.113.6");
        assertTrue(response.startsWith("HTTP/1.1 403"), response);
        assertEquals("203.0.113.6", wsSeenIp.get());
    }

    @Test
    void directIpv6ClientIsBucketedOnItsSlash64() throws Exception {
        assertEquals("0:0:0:0:0:0:0:1 0:0:0:0::/64", body(send(directApp, "/limited")));
        body(send(directApp, "/limited"));
        assertTrue(send(directApp, "/limited").startsWith("HTTP/1.1 429"));
    }

    @Test
    void bracketedLiteralsParse() {
        assertTrue(new TrustedProxies("::1").isTrusted("[::1]"));
        assertTrue(new TrustedProxies("::1").isTrusted("[0:0:0:0:0:0:0:1]"));
        assertTrue(new TrustedProxies("2001:db8::/32").isTrusted("[2001:db8::1]"));
        assertFalse(new TrustedProxies("::1").isTrusted("[::1"));
        assertFalse(new TrustedProxies("::1").isTrusted("[[::1]]"));
        assertEquals(RateLimiter.ipBucket("2001:db8:1:2::9"), RateLimiter.ipBucket("[2001:db8:1:2::5]"));
        assertEquals("2001:db8:1:2::/64", RateLimiter.ipBucket("[2001:db8:1:2::5]"));
    }
}
