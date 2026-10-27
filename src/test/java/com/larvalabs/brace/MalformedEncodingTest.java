package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A bad percent-escape in a query string or form body is a 400 that records no error. As a 500,
 * every path a client sent it to filed a new error kind and a new regression notification.
 * Errors that do record are keyed by route pattern, not by concrete path.
 */
class MalformedEncodingTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        app = Brace.app().port(0).banner(false).sessions("malformed-encoding-test-secret-0123456789");
        app.get("/search", req -> Result.text("q=" + req.queryParam("q"))).publicRoute();
        app.post("/login", req -> Result.text("ok")).publicRoute();
        app.get("/boom/{id}", req -> { throw new IllegalStateException("kaboom"); }).publicRoute();
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        app.stop();
    }

    /** Raw socket: java.net.http refuses to send a malformed URI. Returns the status line. */
    private static String send(String requestLine, String body) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            String req = requestLine + "\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n"
                + (body == null ? "" : "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: "
                    + body.length() + "\r\n")
                + "\r\n" + (body == null ? "" : body);
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return response.substring(0, response.indexOf("\r\n"));
        }
    }

    @Test
    void badEscapesAreA400AndRecordNoError() throws Exception {
        int before = app.stats().recentErrors().size();
        for (int i = 0; i < 3; i++) {
            assertTrue(send("GET /nope-" + i + "?q=%zz HTTP/1.1", null).contains(" 400 "));
        }
        assertTrue(send("GET /search?q=%zz HTTP/1.1", null).contains(" 400 "));
        // In a form body, read for the CSRF check before the handler runs.
        assertTrue(send("POST /login HTTP/1.1", "_csrf=%zz").contains(" 400 "));
        assertEquals(before, app.stats().recentErrors().size(), "no error recorded");
        // Well-formed input is unaffected.
        assertTrue(send("GET /search?q=%41 HTTP/1.1", null).contains(" 200 "));
    }

    @Test
    void errorsAreKeyedByRoutePattern() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertTrue(send("GET /boom/" + i + " HTTP/1.1", null).contains(" 500 "));
        }
        var boom = app.stats().recentErrors().stream()
            .filter(e -> "IllegalStateException".equals(e.type)).toList();
        assertEquals(1, boom.size(), "one error kind for the route, not one per id");
        assertEquals("GET /boom/{id}", boom.get(0).route);
    }
}
