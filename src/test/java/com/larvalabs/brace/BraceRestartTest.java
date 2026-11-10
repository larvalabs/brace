package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stopped app can be started again, and the second stop() really stops it. The idempotence flag
 * that makes stop() safe to call twice (shutdown hook plus explicit stop) was never reset by
 * start(), so after start, stop, start the second stop() was a no-op: Jetty kept serving, the
 * shutdown hook stayed registered and the router stayed frozen.
 */
class BraceRestartTest {

    @Test
    void stopAfterARestartClosesThePort() throws Exception {
        var app = Brace.app().opsUnpinnedForTests().port(0).banner(false);
        app.get("/", req -> Result.text("up"));

        app.start();
        int first = app.actualPort();
        assertTrue(accepts(first), "serving after the first start");
        app.stop();
        assertFalse(accepts(first), "the first stop closes the port");

        app.start();
        int second = app.actualPort();
        assertTrue(accepts(second), "serving after the restart");
        assertNotNull(app.shutdownHook(), "the restart registers a shutdown hook again");
        app.stop();
        assertFalse(accepts(second), "the second stop closes the port");
        assertNull(app.shutdownHook(), "the second stop deregisters the shutdown hook");

        // Stopped again, so configurable again (the router is unfrozen).
        assertDoesNotThrow(() -> app.get("/later", req -> Result.text("later")));
        app.stop(); // still idempotent
    }

    @Test
    void aFailedRestartCleansUpLikeAFailedFirstStart() throws Exception {
        var app = Brace.app().opsUnpinnedForTests().port(0).banner(false);
        app.start();
        int port = app.actualPort();
        app.stop();

        // Restart on a port that is taken: start() throws, and its cleanup stop() must run (not
        // be skipped as "already stopped") so nothing is left half-started.
        try (var blocker = new java.net.ServerSocket(0)) {
            var again = app.port(blocker.getLocalPort());
            assertThrows(Exception.class, again::start);
            assertNull(app.shutdownHook(), "a failed restart leaves no shutdown hook behind");
            // start() froze the router before the bind failed; only the cleanup stop() unfreezes it.
            assertDoesNotThrow(() -> app.get("/after-failed-restart", req -> Result.text("x")),
                "the cleanup stop() ran and unfroze the router");
        }
        assertFalse(accepts(port));
    }

    private static boolean accepts(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
