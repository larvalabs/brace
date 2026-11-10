package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 500 path must record the error and answer with the framework's own response whatever the
 * request or the exception looks like (second review of M6, finding H-1). A query string the
 * redactor threw on ({@code ?x=jdbc:_x://h}) used to abort the catch block in
 * {@code BraceHandler.handle}: no {@code http.error} log line, nothing in the stats or the error
 * store, and a bare Jetty 500. So anyone who could cause a 500 could also hide it.
 */
class ErrorPathRobustnessTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        app = Brace.app().port(0).banner(false);
        app.get("/boom", req -> { throw new IllegalStateException("kaboom"); }).publicRoute();
        app.after((req, result) -> result.header("X-After", "ran"));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        app.stop();
    }

    /** Raw socket, so the query reaches the server byte for byte. Returns the whole response. */
    private static String send(String requestLine) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            String req = requestLine + "\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aQueryTheRedactorOnceThrewOnIsStillRecordedAndLogged() throws Exception {
        for (String query : new String[] {"x=jdbc:_x://h", "x=jdbc%3A_x%3A%2F%2Fh", "x=jdbc:my_driver://host/db"}) {
            LogTap.clear();
            String response = send("GET /boom?" + query + " HTTP/1.1");
            assertTrue(response.startsWith("HTTP/1.1 500 "), response);
            // The framework's own 500, decorated by after-middleware: not a bare Jetty error page.
            assertTrue(response.contains("X-After: ran"), response);
            assertTrue(response.endsWith("Internal Server Error"), response);

            var recorded = app.stats().recentErrors().stream()
                .filter(e -> "GET /boom".equals(e.route)).toList();
            assertEquals(1, recorded.size(), "the error is recorded");
            assertEquals("kaboom", recorded.get(0).message);
            assertEquals("GET /boom?" + query, recorded.get(0).requestDetail);

            var logged = LogTap.snapshot().stream()
                .filter(e -> "http.error".equals(e.fields().get("event"))).toList();
            assertEquals(1, logged.size(), "http.error is logged: " + LogTap.snapshot());
            assertEquals("/boom", logged.get(0).fields().get("path"));
            assertEquals("kaboom", logged.get(0).fields().get("message"));
        }
    }
}
