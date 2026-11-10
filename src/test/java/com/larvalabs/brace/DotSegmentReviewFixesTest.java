package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L9 review fixes: the WebSocket upgrade answers only at its registered path, spelled exactly
 * (Jetty matches the upgrade against the decoded, dot-segment-resolved path, the proxy
 * disagreement L9 exists for), and the dot-segment 404 goes through after-middleware like any
 * other 404. Raw sockets, so no client normalizes the request target first.
 */
class DotSegmentReviewFixesTest {

    static Brace app;

    public static class Echo {
        public Echo(WsContext ws) {}
    }

    @BeforeAll
    static void setup() throws Exception {
        Path dir = Files.createTempDirectory("brace-l9-review");
        Files.writeString(dir.resolve("a.txt"), "a");
        app = Brace.app().port(0).banner(false)
            .staticFiles("/files", dir.toString()).publicFiles();
        app.after(SecurityHeaders.defaults());
        app.get("/open", req -> Result.text("open"));
        app.ws("/ws", Echo::new);
        app.ws("/pubws", Echo::new);
        app.start();
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    /** The status of a WebSocket handshake sent to {@code target} byte-for-byte. */
    private static int upgradeStatus(String target) throws Exception {
        try (Socket socket = new Socket("localhost", app.actualPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\n"
                + "Host: localhost:" + app.actualPort() + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            // Only the status line: a 101 leaves the connection open.
            String statusLine = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertNotNull(statusLine, target);
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }

    private static String get(String path) throws Exception {
        try (Socket socket = new Socket("localhost", app.actualPort())) {
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            var buf = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private static String header(String raw, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : raw.substring(0, Math.max(0, raw.indexOf("\r\n\r\n"))).split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) return line.substring(prefix.length()).strip();
        }
        return null;
    }

    @Test
    void theRegisteredPathUpgrades() throws Exception {
        assertEquals(101, upgradeStatus("/ws"));
        assertEquals(101, upgradeStatus("/pubws"));
        assertEquals(101, upgradeStatus("/ws?token=abc"));
    }

    @Test
    void anotherSpellingOfTheSocketPathDoesNotUpgrade() throws Exception {
        for (String target : new String[]{
                "/ws/../ws",
                "/pubws/../ws",
                "/files/../ws",
                "/%77s",
                "/ws;x",
                "/ws;x=1?token=abc",
                "/./ws",
                "/ws/.",
                "/x/../ws?token=abc"}) {
            int status = upgradeStatus(target);
            assertNotEquals(101, status, target + " upgraded");
            assertTrue(status == 404 || status == 400, target + " got " + status);
        }
    }

    @Test
    void theDotSegment404CarriesAfterMiddlewareHeaders() throws Exception {
        String unmatched = get("/nope");
        assertTrue(unmatched.startsWith("HTTP/1.1 404"), unmatched);
        assertEquals("nosniff", header(unmatched, "X-Content-Type-Options"));
        assertNotNull(header(unmatched, "X-Frame-Options"));

        for (String path : new String[]{"/open/..", "/open/.", "/files/../open", "/files/./a.txt"}) {
            String dotted = get(path);
            assertTrue(dotted.startsWith("HTTP/1.1 404"), path + "\n" + dotted);
            assertEquals("nosniff", header(dotted, "X-Content-Type-Options"), path);
            assertEquals(header(unmatched, "X-Frame-Options"), header(dotted, "X-Frame-Options"), path);
            assertEquals(header(unmatched, "Referrer-Policy"), header(dotted, "Referrer-Policy"), path);
        }
    }
}
