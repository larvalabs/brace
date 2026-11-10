package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L3 over a raw socket, where repeated header lines are on the wire exactly as sent. Before the
 * fix {@code req.ip()} read {@code X-Forwarded-For} and fell back to {@code Forwarded}, from a
 * header map that kept only the last line of each name: {@code X-Forwarded-For: 6.6.6.6} plus the
 * proxy's {@code Forwarded: for=203.0.113.9} gave {@code 6.6.6.6}, and two XFF lines gave the
 * second.
 */
class ForwardedHeaderSocketTest {

    // 10.0.0.7 stands for a second trusted hop that appends its own line.
    static final String[] PROXIES = {"127.0.0.1", "::1", "10.0.0.7"};

    static Brace xffApp;
    static Brace forwardedApp;
    static final AtomicReference<String> wsSeenIp = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        xffApp = Brace.app().port(0).trustedProxies(PROXIES);
        xffApp.get("/ip", req -> Result.text(req.ip()));
        xffApp.before("/ws", req -> {
            wsSeenIp.set(req.ip());
            return Result.error(403, "recorded");
        });
        xffApp.ws("/ws", ctx -> new Object());
        xffApp.start();

        forwardedApp = Brace.app().port(0).trustedProxies(new TrustedProxies(PROXIES).useForwardedHeader());
        forwardedApp.get("/ip", req -> Result.text(req.ip()));
        forwardedApp.start();
    }

    @AfterAll
    static void stop() throws Exception {
        xffApp.stop();
        forwardedApp.stop();
    }

    /** Send a raw GET with the given extra header lines; return the response body. */
    private static String ipFrom(Brace app, String... headerLines) throws Exception {
        try (var socket = new Socket("127.0.0.1", app.actualPort())) {
            socket.setSoTimeout(10_000);
            var req = new StringBuilder("GET /ip HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            for (var line : headerLines) req.append(line).append("\r\n");
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var response = readAll(socket.getInputStream());
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            return response.substring(response.indexOf("\r\n\r\n") + 4).strip();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        var out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void defaultReadsXForwardedForAndIgnoresForwarded() throws Exception {
        assertEquals("6.6.6.6", ipFrom(xffApp, "X-Forwarded-For: 6.6.6.6", "Forwarded: for=203.0.113.9"));
        assertEquals("unknown", ipFrom(xffApp, "Forwarded: for=203.0.113.9"),
            "Forwarded alone is not read by default, and the proxy's own address is not the client's");
        assertEquals("127.0.0.1", ipFrom(xffApp), "a trusted peer forwarding nothing is itself the client");
    }

    @Test
    void forwardedOptionReadsForwardedAndIgnoresXForwardedFor() throws Exception {
        assertEquals("203.0.113.9",
            ipFrom(forwardedApp, "X-Forwarded-For: 6.6.6.6", "Forwarded: for=203.0.113.9"));
        assertEquals("unknown", ipFrom(forwardedApp, "X-Forwarded-For: 6.6.6.6"),
            "X-Forwarded-For alone is ignored once Forwarded is chosen");
        assertEquals("203.0.113.9", ipFrom(forwardedApp, "Forwarded: for=\"203.0.113.9:4711\";proto=https"));
    }

    @Test
    void repeatedXForwardedForLinesAreJoinedInOrder() throws Exception {
        // Joined: "1.1.1.1,10.0.0.7" → rightmost untrusted is 1.1.1.1. Last-line-wins saw only the
        // trusted hop and returned it.
        assertEquals("1.1.1.1", ipFrom(xffApp, "X-Forwarded-For: 1.1.1.1", "X-Forwarded-For: 10.0.0.7"));
        assertEquals("2.2.2.2", ipFrom(xffApp, "X-Forwarded-For: 9.9.9.9, 1.1.1.1", "X-Forwarded-For: 2.2.2.2"));
    }

    @Test
    void repeatedForwardedLinesAreJoinedInOrder() throws Exception {
        assertEquals("1.1.1.1", ipFrom(forwardedApp, "Forwarded: for=1.1.1.1", "Forwarded: for=10.0.0.7"));
    }

    @Test
    void rightmostUntrustedStillHoldsAcrossLines() throws Exception {
        // A client-sent first line cannot pick the address: the proxy's line is to its right.
        assertEquals("203.0.113.5", ipFrom(xffApp, "X-Forwarded-For: 6.6.6.6", "X-Forwarded-For: 203.0.113.5"));
        assertEquals("203.0.113.5",
            ipFrom(xffApp, "X-Forwarded-For: 6.6.6.6", "X-Forwarded-For: 203.0.113.5, 10.0.0.7"));
    }

    @Test
    void webSocketUpgradeJoinsLinesToo() throws Exception {
        wsSeenIp.set(null);
        try (var socket = new Socket("127.0.0.1", xffApp.actualPort())) {
            socket.setSoTimeout(10_000);
            var req = "GET /ws HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "X-Forwarded-For: 1.1.1.1\r\n"
                + "X-Forwarded-For: 10.0.0.7\r\n\r\n";
            socket.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var status = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
            assertTrue(status.contains("403"), status);
        }
        assertEquals("1.1.1.1", wsSeenIp.get());
    }
}
