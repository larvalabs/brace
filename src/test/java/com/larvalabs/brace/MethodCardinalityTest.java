package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review 2026-11, second review of M5: the HTTP method is the client's to choose, and
 * Jetty passes any token through. Stats keys and error kinds are {@code method + " " + pattern},
 * so a made-up method per request ({@code ZZ0}, {@code ZZ1}, ...) minted one permanent stats key
 * per request, and one error kind (and one regression notification) per request wherever a 500
 * can be had without matching a route.
 */
class MethodCardinalityTest {

    static TestApp app;

    @BeforeAll
    static void setup() throws Exception {
        app = Brace.test().start(a -> {
            a.before("/*", req -> {
                if (req.path().startsWith("/boom")) throw new IllegalStateException("before-middleware failed");
                return null;
            });
            a.get("/ok", req -> Result.text("ok"));
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    /** A request line with an arbitrary method, which java.net.http would not all send. */
    private static int send(String method, String path) throws IOException {
        try (var socket = new Socket("127.0.0.1", app.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            return Integer.parseInt(response.substring(9, 12));
        }
    }

    @Test
    void madeUpMethodsShareOneStatsKeyAndOneErrorKind() throws Exception {
        long before = app.app().stats().totalRequests();
        int n = 30;
        for (int i = 0; i < n; i++) {
            assertEquals(404, send("ZZ" + i, "/nope" + i));
            assertEquals(500, send("QQ" + i, "/boom" + i));
        }
        TestWait.until(() -> app.app().stats().totalRequests() >= before + 2L * n,
            () -> "requests never all recorded: " + app.app().stats().totalRequests());

        var keys = app.app().stats().routeStats().keySet();
        assertTrue(keys.stream().noneMatch(k -> k.startsWith("ZZ") || k.startsWith("QQ")),
            "a client-chosen method must not become a stats key: " + keys);
        assertTrue(keys.contains("OTHER " + BraceHandler.UNMATCHED_ROUTE_KEY), keys.toString());
        assertTrue(keys.stream().noneMatch(k -> k.contains("nope") || k.contains("boom")), keys.toString());

        var kinds = app.app().stats().recentErrors().stream().map(e -> e.type + " | " + e.route).toList();
        assertEquals(List.of("IllegalStateException | OTHER " + BraceHandler.UNMATCHED_ROUTE_KEY), kinds);
        assertEquals(n, app.app().stats().recentErrors().get(0).count);
        // The request as it was sent is still in the record's detail.
        assertTrue(app.app().stats().recentErrors().get(0).requestDetail.startsWith("QQ"),
            app.app().stats().recentErrors().get(0).requestDetail);
    }

    @Test
    void methodKeyKeepsTheStandardMethodsAndFoldsTheRest() {
        for (String known : List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT")) {
            assertEquals(known, BraceHandler.methodKey(known));
        }
        for (String unknown : List.of("ZZ0", "get", "PROPFIND", "", "GET2", "WS", "OTHER")) {
            assertEquals("OTHER", BraceHandler.methodKey(unknown), unknown);
        }
        assertEquals("OTHER", BraceHandler.methodKey(null));
    }

    @Test
    void aStandardMethodKeepsItsOwnKey() throws Exception {
        long before = app.app().stats().totalRequests();
        assertEquals(404, send("PATCH", "/nothing-here"));
        TestWait.until(() -> app.app().stats().totalRequests() >= before + 1, "request never recorded");
        assertTrue(app.app().stats().routeStats().containsKey("PATCH " + BraceHandler.UNMATCHED_ROUTE_KEY));
    }
}
