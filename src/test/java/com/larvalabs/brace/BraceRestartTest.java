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

    // ---- Final review (lifecycle): one lock, one explicit state ----

    private static Brace opsApp(java.nio.file.Path dir) throws Exception {
        var keys = dir.resolve("authorized-keys");
        java.nio.file.Files.writeString(keys, OpsKeys.generateKeypair().publicKey() + " scope:control test\n");
        var app = Brace.app().opsUnpinnedForTests().port(0).banner(false)
            .opsSecret("brace-restart-test-ops-secret-at-least-32-chars").ops(keys.toString());
        app.get("/", req -> Result.text("up")).publicRoute();
        return app;
    }

    /** L-D: the second start() threw "POST /ops/auth is registered twice". */
    @Test
    void anAppWithOpsCanBeRestarted(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var app = opsApp(dir);
        app.start();
        app.stop();
        assertDoesNotThrow(app::start, "a stopped app with ops starts again");
        int port = app.actualPort();
        assertTrue(accepts(port));
        assertEquals(401, status(port, "/ops/status"), "the ops routes are registered once, and guarded");
        app.stop();
        assertFalse(accepts(port));
        // And a third time, with a route added in between.
        app.get("/added", req -> Result.text("added")).publicRoute();
        app.start();
        assertEquals(200, status(app.actualPort(), "/added"));
        app.stop();
    }

    /** L-D: after a failed bind the retry threw the same "registered twice". */
    @Test
    void aFailedBindCanBeRetried(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var app = opsApp(dir);
        try (var blocker = new java.net.ServerSocket(0)) {
            app.port(blocker.getLocalPort());
            assertThrows(Exception.class, app::start);
            assertNull(app.shutdownHook(), "a failed start leaves no shutdown hook");
            assertDoesNotThrow(app::stop, "and the app is cleanly stoppable");
        }
        app.port(0);
        assertDoesNotThrow(app::start, "and restartable");
        int port = app.actualPort();
        assertTrue(accepts(port));
        app.stop();
        assertFalse(accepts(port));
    }

    /** L-E: a second start() without stop() replaced the server field and leaked the first server. */
    @Test
    void startingARunningAppThrowsAndLeavesItRunning() throws Exception {
        var app = Brace.app().opsUnpinnedForTests().port(0).banner(false);
        app.get("/", req -> Result.text("up"));
        app.start();
        int port = app.actualPort();
        var ex = assertThrows(IllegalStateException.class, app::start);
        assertTrue(ex.getMessage().contains("already"), ex.getMessage());
        assertEquals(port, app.actualPort());
        assertTrue(accepts(port), "the running server is untouched");
        app.stop();
        assertFalse(accepts(port), "and stop() still stops it");
    }

    /**
     * L-E: stop() racing start() left a running server with an unfrozen router that stop() no
     * longer stopped (30 of 30 rounds). Whatever the interleaving, once both calls have returned
     * one more stop() leaves nothing running and the app configurable.
     */
    @Test
    void stopRacingStartNeverLeavesAServerBehind() throws Exception {
        for (int round = 0; round < 30; round++) {
            var app = Brace.app().opsUnpinnedForTests().port(0).banner(false);
            app.get("/", req -> Result.text("up"));
            var go = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread starter = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    app.start();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    if ((System.nanoTime() & 1) == 0) Thread.onSpinWait();
                    app.stop();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            go.countDown();
            starter.join(30_000);
            stopper.join(30_000);
            assertFalse(starter.isAlive() || stopper.isAlive(), "round " + round + ": start/stop deadlocked");
            assertNull(failure.get(), "round " + round + ": " + failure.get());
            int port = app.actualPort();
            app.stop();
            assertFalse(port > 0 && accepts(port), "round " + round + ": a server was left running on " + port);
            assertNull(app.shutdownHook(), "round " + round + ": a shutdown hook was left registered");
            int r = round;
            assertDoesNotThrow(() -> app.get("/later" + r, req -> Result.text("later")),
                "round " + round + ": the router is unfrozen once stopped");
        }
    }

    @Test
    void concurrentStopsAreSafe() throws Exception {
        var app = Brace.app().opsUnpinnedForTests().port(0).banner(false);
        app.get("/", req -> Result.text("up"));
        app.start();
        int port = app.actualPort();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    app.stop();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }));
        }
        for (var t : threads) t.join(30_000);
        assertNull(failure.get());
        assertFalse(accepts(port));
    }

    private static int status(int port, String path) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path)).build();
        return java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
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
