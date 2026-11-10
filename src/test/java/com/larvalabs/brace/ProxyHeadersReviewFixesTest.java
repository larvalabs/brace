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
 * Review fixes for L3 (docs/2026-11-09-security-review-todos.md): the unchosen forwarding header,
 * non-address entries in the chosen one, X-Forwarded-Proto/Host from all lines, and cookies split
 * across several {@code Cookie} lines.
 */
class ProxyHeadersReviewFixesTest {

    static final String SECRET = "proxy-headers-review-secret-at-least-32-chars";
    static Brace app;

    @BeforeAll
    static void start() throws Exception {
        app = Brace.app().port(0).banner(false).sessions(SECRET).trustedProxies("127.0.0.1", "::1")
            .defaultAccess(Access.none());
        app.get("/host", req -> Result.text(String.valueOf(req.host())));
        app.get("/cookie", req -> Result.text(req.cookie("x") + "|" + req.cookie("y")));
        app.get("/touch", (SessionHandler) (req, s) -> {
            s.set("t", "1");
            return Result.text("ok");
        });
        app.get("/whoami", (SessionHandler) (req, s) -> Result.text("user:" + s.get("userId")));
        app.ws("/live", ctx -> new Object()).access(Access.session("userId", "/login"));
        app.start();
    }

    @AfterAll
    static void stop() throws Exception {
        app.stop();
    }

    @BeforeEach
    void resetWarning() {
        Request.resetForwardingHeaderWarning();
    }

    private static String send(String requestLine, String... headerLines) throws Exception {
        try (var socket = new Socket("127.0.0.1", app.actualPort())) {
            socket.setSoTimeout(10_000);
            var req = new StringBuilder(requestLine + " HTTP/1.1\r\nConnection: close\r\n");
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
            return out.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static String body(String response) {
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        return response.substring(response.indexOf("\r\n\r\n") + 4).strip();
    }

    private static Request trusted(Map<String, String> headers, TrustedProxies proxies) {
        return new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "127.0.0.1", proxies);
    }

    // ---- F3: the unchosen header alone, from a trusted peer, is reported ----

    @Test
    void forwardedAloneWithXffChosenWarnsOnce() {
        var proxies = new TrustedProxies("127.0.0.1");
        // Final review: "unknown", not the proxy's own address (it is proxying for someone).
        assertEquals("unknown", trusted(Map.of("Forwarded", "for=203.0.113.9"), proxies).ip());
        assertTrue(Request.warnedForwardingHeader(), "a trusted proxy sending only Forwarded is reported");
    }

    @Test
    void xffAloneWithForwardedChosenWarnsOnce() {
        var proxies = new TrustedProxies("127.0.0.1").useForwardedHeader();
        assertEquals("unknown", trusted(Map.of("X-Forwarded-For", "203.0.113.9"), proxies).ip());
        assertTrue(Request.warnedForwardingHeader());
    }

    @Test
    void noWarningWhenTheChosenHeaderIsPresentOrThePeerIsUntrusted() {
        var proxies = new TrustedProxies("127.0.0.1");
        trusted(Map.of("X-Forwarded-For", "203.0.113.9", "Forwarded", "for=6.6.6.6"), proxies).ip();
        assertFalse(Request.warnedForwardingHeader());
        new Request("GET", "/", Map.of(), Map.of(), Map.of("Forwarded", "for=6.6.6.6"), null, Map.of(),
            "203.0.113.50", proxies).ip();
        assertFalse(Request.warnedForwardingHeader(), "an untrusted peer's headers are noise, not a misconfiguration");
    }

    // ---- F8: req.ip() is only ever an address literal or "unknown" ----

    @Test
    void nonAddressInXffIsUnknown() {
        // Final review: "unknown", where the first fix returned the socket peer (the proxy).
        var proxies = new TrustedProxies("127.0.0.1");
        assertEquals("unknown", trusted(Map.of("X-Forwarded-For", "<script>"), proxies).ip());
        assertEquals("unknown", trusted(Map.of("X-Forwarded-For", "203.0.113.5, unknown"), proxies).ip());
        assertEquals("203.0.113.5", trusted(Map.of("X-Forwarded-For", "203.0.113.5:4711"), proxies).ip());
        assertEquals("2001:db8::1", trusted(Map.of("X-Forwarded-For", "[2001:db8::1]:443"), proxies).ip());
    }

    @Test
    void forwardedIsParsedWithQuotedStrings() {
        var proxies = new TrustedProxies("127.0.0.1", "10.0.0.7").useForwardedHeader();
        assertEquals("unknown",
            trusted(Map.of("Forwarded", "for=\"[2001:db8::1]:1234\", for=\"a,b\""), proxies).ip(),
            "a quoted comma is not a separator, and \"a,b\" is not an address");
        assertEquals("unknown", trusted(Map.of("Forwarded", "for=_hidden"), proxies).ip());
        assertEquals("unknown", trusted(Map.of("Forwarded", "for=unknown"), proxies).ip());
        assertEquals("unknown", trusted(Map.of("Forwarded", "for=\"1.2.3.4;x\""), proxies).ip());
        assertEquals("2001:db8::1", trusted(Map.of("Forwarded", "for=\"[2001:db8::1]:1234\""), proxies).ip());
        assertEquals("203.0.113.7",
            trusted(Map.of("Forwarded", "for=\"203.0.113.7\";proto=\"h,t\\\"tp\", for=10.0.0.7"), proxies).ip());
        assertEquals("203.0.113.8", trusted(Map.of("Forwarded", "proto=https;For=203.0.113.8"), proxies).ip());
    }

    // ---- F9: X-Forwarded-Proto and X-Forwarded-Host take the rightmost entry of every line ----

    @Test
    void forwardedHostIsTheRightmostEntryAcrossLines() throws Exception {
        assertEquals("app.example", body(send("GET /host", "X-Forwarded-Host: evil.example, app.example")));
        assertEquals("app.example", body(send("GET /host", "X-Forwarded-Host: evil.example",
            "X-Forwarded-Host: app.example")));
        assertEquals("app.example", body(send("GET /host", "X-Forwarded-Host: app.example",
            "X-Forwarded-Host: x, app.example")));
    }

    @Test
    void forwardedProtoIsTheRightmostEntryAcrossLines() throws Exception {
        // A loopback Host, so X-Forwarded-Proto alone decides the session cookie's Secure.
        var plain = send("GET /touch", "X-Forwarded-Proto: https, http");
        assertFalse(setCookie(plain).contains("Secure"), "rightmost is http: " + setCookie(plain));
        var twoLines = send("GET /touch", "X-Forwarded-Proto: http", "X-Forwarded-Proto: https, http");
        assertFalse(setCookie(twoLines).contains("Secure"), setCookie(twoLines));
        var https = send("GET /touch", "X-Forwarded-Proto: http, https");
        assertTrue(setCookie(https).contains("Secure"), setCookie(https));
    }

    private static String setCookie(String response) {
        for (var line : response.split("\r\n")) {
            if (line.regionMatches(true, 0, "Set-Cookie:", 0, 11)) return line;
        }
        return "";
    }

    // ---- F10: several Cookie lines are one cookie list, on HTTP and WebSocket alike ----

    @Test
    void cookiesOnSeveralLinesAreJoinedAndTheFirstNameWins() throws Exception {
        assertEquals("first|2", body(send("GET /cookie", "Cookie: x=first", "Cookie: x=second; y=2")));
    }

    @Test
    void sessionCookieOnAnEarlierLineIsReadOnBothPaths() throws Exception {
        String valid = "brace_session=" + Session.of("userId", "42").toCookie(SECRET);
        assertEquals("user:42", body(send("GET /whoami", "Cookie: " + valid, "Cookie: theme=dark")));
        assertTrue(upgrade("Cookie: " + valid, "Cookie: theme=dark").startsWith("HTTP/1.1 101"));
        // A duplicate name resolves the same way on both paths: the first one.
        assertEquals("user:42", body(send("GET /whoami", "Cookie: " + valid, "Cookie: brace_session=garbage")));
        assertTrue(upgrade("Cookie: " + valid, "Cookie: brace_session=garbage").startsWith("HTTP/1.1 101"));
        assertEquals("user:null", body(send("GET /whoami", "Cookie: brace_session=garbage", "Cookie: " + valid)));
        assertFalse(upgrade("Cookie: brace_session=garbage", "Cookie: " + valid).startsWith("HTTP/1.1 101"));
    }

    private static String upgrade(String... headerLines) throws Exception {
        var lines = new String[headerLines.length + 4];
        lines[0] = "Upgrade: websocket";
        lines[1] = "Connection: Upgrade";
        lines[2] = "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==";
        lines[3] = "Sec-WebSocket-Version: 13";
        System.arraycopy(headerLines, 0, lines, 4, headerLines.length);
        try (var socket = new Socket("127.0.0.1", app.actualPort())) {
            socket.setSoTimeout(10_000);
            var req = new StringBuilder("GET /live HTTP/1.1\r\nHost: localhost\r\n");
            for (var line : lines) req.append(line).append("\r\n");
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readNBytes(12), StandardCharsets.ISO_8859_1);
        }
    }
}
