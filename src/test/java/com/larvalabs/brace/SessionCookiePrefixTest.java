package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M10 (2026-10 security review): in production the session cookie is {@code __Host-brace_session},
 * which a sibling subdomain can't set, and a request reads only that name — so a {@code
 * brace_session} cookie tossed in from a subdomain is ignored.
 */
class SessionCookiePrefixTest {

    static final String SECRET = "cookie-prefix-test-secret-at-least-32-chars";
    static Brace app;

    @BeforeAll
    static void start() throws Exception {
        app = Brace.app().port(0).banner(false).sessions(SECRET);
        app.getSession("/login-as", (req, s) -> { s.set("userId", "7"); return Result.text("ok"); });
        app.getSession("/me", (req, s) -> Result.text("me:" + s.get("userId")));
        app.start();
    }

    @AfterAll
    static void stop() throws Exception {
        app.stop();
    }

    @Test
    void productionRequestGetsTheHostPrefixedCookie() throws Exception {
        String res = raw("GET /login-as HTTP/1.1\r\nHost: app.example.com\r\nConnection: close\r\n\r\n");
        assertTrue(res.contains("Set-Cookie: __Host-brace_session="), res);
        assertTrue(res.contains("; Secure"), res);
    }

    @Test
    void productionRequestReadsOnlyThePrefixedName() throws Exception {
        String sealed = Session.of("userId", "evil").toCookie(SECRET);
        String tossed = raw("GET /me HTTP/1.1\r\nHost: app.example.com\r\nCookie: brace_session=" + sealed
            + "\r\nConnection: close\r\n\r\n");
        assertTrue(tossed.endsWith("me:null"), "an unprefixed cookie is ignored where the prefix applies: " + tossed);
        String real = raw("GET /me HTTP/1.1\r\nHost: app.example.com\r\nCookie: __Host-brace_session=" + sealed
            + "\r\nConnection: close\r\n\r\n");
        assertTrue(real.endsWith("me:evil"), real);
    }

    @Test
    void plainHttpLocalhostKeepsTheUnprefixedName() throws Exception {
        String res = raw("GET /login-as HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertTrue(res.contains("Set-Cookie: brace_session="), res);
    }

    private static String raw(String request) throws IOException {
        try (var socket = new Socket("localhost", app.actualPort())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
