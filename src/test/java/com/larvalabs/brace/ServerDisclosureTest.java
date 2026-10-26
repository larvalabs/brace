package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security review L1: no {@code Server: Jetty(x.y.z)} header, and requests Jetty rejects before
 * {@link BraceHandler} runs get a short plain-text body, not Jetty's HTML error page (which shows
 * the version and echoes the request URI).
 *
 * <p>Raw sockets, because {@link java.net.http.HttpClient} normalizes the paths that trigger
 * Jetty's URI-compliance rejections.
 */
class ServerDisclosureTest {

    static Brace app;
    static int port;
    static Path assetDir;

    @BeforeAll
    static void startApp() throws Exception {
        assetDir = Files.createTempDirectory("brace-l1-assets");
        Files.writeString(assetDir.resolve("plain.css"), "body{}");
        app = Brace.app().port(0).staticFiles("/assets", assetDir.toString());
        app.get("/hello", req -> Result.text("hi"));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @Test
    void normalResponseCarriesNoServerOrPoweredByHeader() throws Exception {
        String raw = send("GET /hello HTTP/1.1");
        assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
        assertNoDisclosure(raw);
    }

    @Test
    void staticFileResponseCarriesNoServerHeader() throws Exception {
        String raw = send("GET /assets/plain.css HTTP/1.1");
        assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
        assertNoDisclosure(raw);
    }

    @Test
    void ambiguousEmptySegmentIsAPlainTextBadRequest() throws Exception {
        String raw = send("GET //app/secret-path HTTP/1.1");
        assertPlainTextError(raw, 400, "Bad Request", "secret-path");
    }

    @Test
    void ambiguousDotSegmentIsAPlainTextBadRequest() throws Exception {
        String raw = send("GET /assets/%2e%2e/secret-path HTTP/1.1");
        assertPlainTextError(raw, 400, "Bad Request", "secret-path");
    }

    @Test
    void malformedRequestLineIsAPlainTextError() throws Exception {
        String raw = send("GET /bad path HTTP/1.1 junk");
        assertTrue(raw.startsWith("HTTP/1.1 4"), raw);
        assertNoDisclosure(raw);
        assertFalse(raw.contains("<html"), raw);
    }

    private static void assertPlainTextError(String raw, int status, String phrase, String echoedPath) {
        assertTrue(raw.startsWith("HTTP/1.1 " + status), raw);
        String lower = raw.toLowerCase();
        assertTrue(lower.contains("content-type: text/plain; charset=utf-8"), raw);
        assertTrue(lower.contains("x-content-type-options: nosniff"), raw);
        String body = raw.substring(raw.indexOf("\r\n\r\n") + 4);
        assertEquals(phrase, body, "body is only the reason phrase");
        assertFalse(raw.contains(echoedPath), "the request path must not be echoed: " + raw);
        assertNoDisclosure(raw);
    }

    private static void assertNoDisclosure(String raw) {
        String lower = raw.toLowerCase();
        assertFalse(lower.contains("\r\nserver:"), "Server header present: " + raw);
        assertFalse(lower.contains("x-powered-by"), "X-Powered-By header present: " + raw);
        assertFalse(lower.contains("jetty"), "Jetty mentioned: " + raw);
    }

    private static String send(String requestLine) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            var request = requestLine + "\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var out = new ByteArrayOutputStream();
            var in = socket.getInputStream();
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (SocketTimeoutException e) {
                fail("server did not close the connection; read so far: " + out.toString(StandardCharsets.ISO_8859_1));
            }
            return out.toString(StandardCharsets.ISO_8859_1);
        }
    }
}
