package com.larvalabs.brace;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Buffered {@code Http.fetch*} calls bound the response body, not just the wait for headers
 * (security review 2026-11, M8). A server that answers headers at once and then stalls, or drips
 * a byte at a time, used to hold the caller's thread (and any pooled DB connection) indefinitely.
 */
class HttpBodyTimeoutTest {

    static HttpServer server;
    static int port;
    /** Released after the class, so stalled server handlers end. */
    static final CountDownLatch release = new CountDownLatch(1);
    static final int LARGE = 5 * 1024 * 1024;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Headers and 3 of the 100 declared bytes, then nothing: the review's reproduction.
        server.createContext("/stall", ex -> respond(ex, 200, 100, out -> {
            write(out, "{\"a");
            await(5000);
        }));

        // One byte every 100ms for up to 5s: never idle long enough to trip an idle timeout.
        server.createContext("/drip", ex -> respond(ex, 200, 0, out -> drip(out)));
        server.createContext("/drip-error", ex -> respond(ex, 503, 0, out -> drip(out)));

        server.createContext("/large", ex -> respond(ex, 200, LARGE, out -> {
            var chunk = new byte[64 * 1024];
            java.util.Arrays.fill(chunk, (byte) 'x');
            for (int sent = 0; sent < LARGE; sent += chunk.length) out.write(chunk);
        }));

        server.createContext("/ok", ex -> respond(ex, 200, 0, out -> write(out, "{\"n\":1}")));

        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        release.countDown();
        server.stop(0);
    }

    // --- Idle timeout: a body that stalls after the headers ---

    @Test
    void everyBufferedFetchFailsWithinTheIdleTimeoutWhenTheBodyStalls() {
        record Call(String name, Function<Http, Object> run) {}
        var calls = java.util.List.of(
            new Call("fetch", Http::fetch),
            new Call("fetchString", Http::fetchString),
            new Call("fetchBytes", Http::fetchBytes),
            new Call("fetchJson", h -> h.fetchJson(java.util.Map.class)));
        for (var call : calls) {
            long start = System.nanoTime();
            var e = assertThrows(Http.StreamTimeoutException.class, () ->
                call.run().apply(Http.get(url("/stall")).trusted().idleTimeout(Duration.ofMillis(300))),
                call.name());
            assertTrue(e.idle(), call.name());
            assertEquals(Duration.ofMillis(300), e.limit(), call.name());
            assertTrue(e.getMessage().contains("no data for 300ms"), e.getMessage());
            assertTrue(elapsedMillis(start) < 3000, call.name() + " took " + elapsedMillis(start) + "ms");
        }
    }

    @Test
    void theDefaultsBoundAStalledBodyWithoutAnyConfiguration() {
        // Defaults: 30s idle, 30s total. Moving the call's wall clock past 30s stands in for the
        // wait (the watchdog goes by whichever clock moved further), so the test stays fast.
        var later = new AtomicLong();
        var http = Http.get(url("/stall")).trusted();
        http.wallClock = () -> System.currentTimeMillis() + later.get();
        Thread.ofVirtual().start(() -> {
            sleep(300);
            later.set(Duration.ofSeconds(31).toMillis());
        });
        long start = System.nanoTime();
        var e = assertThrows(Http.StreamTimeoutException.class, http::fetch);
        assertEquals(Duration.ofSeconds(30), e.limit());
        assertTrue(elapsedMillis(start) < 3000, "took " + elapsedMillis(start) + "ms");
    }

    // --- Total deadline: a body that keeps dripping ---

    @Test
    void timeoutBoundsTheWholeBufferedExchange() {
        long start = System.nanoTime();
        var e = assertThrows(Http.StreamTimeoutException.class, () ->
            Http.get(url("/drip")).trusted()
                .timeout(Duration.ofMillis(600))
                .idleTimeout(Duration.ofSeconds(2))
                .fetchBytes());
        assertFalse(e.idle());
        assertEquals(Duration.ofMillis(600), e.limit());
        assertTrue(e.getMessage().contains("exceeded 600ms deadline"), e.getMessage());
        long took = elapsedMillis(start);
        assertTrue(took >= 500 && took < 3000, "deadline took " + took + "ms");
    }

    @Test
    void fetchEventsErrorBodyHasADeadlineEvenWithoutTimeout() {
        // fetchEvents has no total deadline by default (a stream may run for minutes), but its
        // error body is buffered: it gets the buffered default (30s) so a drip can't hold it.
        var later = new AtomicLong();
        var http = Http.get(url("/drip-error")).trusted().idleTimeout(Duration.ZERO);
        http.wallClock = () -> System.currentTimeMillis() + later.get();
        Thread.ofVirtual().start(() -> {
            sleep(300);
            later.set(Duration.ofSeconds(31).toMillis());
        });
        long start = System.nanoTime();
        var e = assertThrows(Http.StreamTimeoutException.class, () -> http.fetchEvents(ev -> { }));
        assertFalse(e.idle());
        assertEquals(Duration.ofSeconds(30), e.limit());
        assertTrue(elapsedMillis(start) < 3000, "took " + elapsedMillis(start) + "ms");
    }

    // --- M8 review fixes: timeout(null), zero/negative, overflow ---

    @Test
    void timeoutNullOnABufferedFetchMeansTheDefaultDeadline() {
        // Reproduction: a 1-byte/100ms drip under idleTimeout(500ms) never trips the idle
        // timeout, and timeout(null) used to leave the buffered call with no deadline at all.
        var later = new AtomicLong();
        var http = Http.get(url("/drip")).trusted().timeout(null).idleTimeout(Duration.ofMillis(500));
        http.wallClock = () -> System.currentTimeMillis() + later.get();
        Thread.ofVirtual().start(() -> {
            sleep(300);
            later.set(Duration.ofSeconds(31).toMillis());
        });
        long start = System.nanoTime();
        var e = assertThrows(Http.StreamTimeoutException.class, http::fetchBytes);
        assertFalse(e.idle());
        assertEquals(Duration.ofSeconds(30), e.limit());
        assertTrue(elapsedMillis(start) < 3000, "took " + elapsedMillis(start) + "ms");
    }

    @Test
    void zeroOrNegativeTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Http.get(url("/ok")).timeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Http.get(url("/ok")).timeout(Duration.ofSeconds(-1)));
    }

    @Test
    void aHugeTimeoutIsClampedNotAnArithmeticException() {
        var forever = Duration.ofSeconds(Long.MAX_VALUE);
        assertEquals("{\"n\":1}", Http.get(url("/ok")).trusted().timeout(forever).fetchString());
        assertEquals("{\"n\":1}", Http.get(url("/ok")).trusted().idleTimeout(forever).fetchString());
        try (var response = Http.get(url("/ok")).trusted().timeout(forever).idleTimeout(forever).stream()) {
            assertEquals(200, response.status());
        }
    }

    // --- Unaffected: a normal body, large or small ---

    @Test
    void aNormalLargeBodyIsUnaffected() {
        var bytes = Http.get(url("/large")).trusted()
            .idleTimeout(Duration.ofMillis(500)).timeout(Duration.ofSeconds(20)).fetchBytes();
        assertEquals(LARGE, bytes.length);
        assertEquals("{\"n\":1}", Http.get(url("/ok")).trusted().fetchString());
    }

    // --- No leak: the watchdog's timer task is released whatever the outcome ---

    @Test
    void watchdogTimersAreReleasedOnSuccessAndFailure() {
        for (int i = 0; i < 20; i++) {
            Http.get(url("/ok")).trusted().fetch();
        }
        for (int i = 0; i < 3; i++) {
            assertThrows(Http.StreamTimeoutException.class, () ->
                Http.get(url("/stall")).trusted().idleTimeout(Duration.ofMillis(100)).fetch());
        }
        assertThrows(Http.ResponseTooLargeException.class, () ->
            Http.get(url("/large")).trusted().maxResponseBytes(1024).fetch());
        // fetchBytes on a non-2xx throws before reading the body.
        assertThrows(RuntimeException.class, () -> Http.get(url("/missing")).trusted().fetchBytes());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Http.pendingWatchdogTasks() > 0 && System.nanoTime() < deadline) sleep(20);
        assertEquals(0, Http.pendingWatchdogTasks(), "watchdog timer tasks left behind");
        var timers = Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().equals("brace-http-stream-timer")).toList();
        assertEquals(1, timers.size(), "one shared timer thread, not one per call");
    }

    // --- Helpers ---

    private interface Body { void write(OutputStream out) throws IOException; }

    private static void respond(HttpExchange ex, int status, long length, Body body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, length);
        try (var out = ex.getResponseBody()) {
            body.write(out);
        } catch (IOException ignored) {
            // The client hung up (timeout); that's what most tests here do.
        }
    }

    private static void drip(OutputStream out) throws IOException {
        for (int i = 0; i < 50 && release.getCount() > 0; i++) {
            write(out, "x");
            sleep(100);
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void await(long millis) {
        try {
            release.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
