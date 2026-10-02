package com.larvalabs.brace;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streaming responses and Server-Sent Events in the {@link Http} client, against a raw
 * in-process server that can pace, stall and fail its output at will.
 */
class HttpStreamTest {

    static HttpServer server;
    static int port;

    // Released by the test's event consumer; the /sse/handshake server waits for it.
    static final CountDownLatch firstEventSeen = new CountDownLatch(1);
    static final AtomicBoolean handshakeSawClient = new AtomicBoolean();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/sse/full", ex -> stream(ex, 200, out -> write(out,
            "﻿: a comment, ignored\n"
                + "data: plain\n\n"
                + "event: update\r\n"
                + "id: 7\r\n"
                + "data: line one\r\n"
                + "data:line two\r\n"
                + "data:  two spaces\r\n\r\n"
                + "retry: 2500\rdata\r\r"
                + "id\n"
                + "retry: soon\n"
                + "unknown: field\n"
                + "data: {\"n\":1}\n\n"
                + "event: no-data\n\n"
                + "id: has\u0000nul\n"
                + "data: trailing without blank line")));

        server.createContext("/sse/handshake", ex -> stream(ex, 200, out -> {
            write(out, "data: first\n\n");
            handshakeSawClient.set(await(firstEventSeen, 5000));
            write(out, "data: second\n\n");
        }));

        server.createContext("/sse/stall", ex -> stream(ex, 200, out -> {
            write(out, "data: before stall\n\n");
            sleep(5000);
        }));

        server.createContext("/sse/forever", ex -> stream(ex, 200, out -> {
            for (int i = 0; i < 100; i++) {
                write(out, "data: tick " + i + "\n\n");
                sleep(50);
            }
        }));

        server.createContext("/sse/burst", ex -> stream(ex, 200, out ->
            write(out, "data: 1\n\ndata: 2\n\ndata: 3\n\n")));

        server.createContext("/sse/slow-headers", ex -> {
            sleep(3000);
            stream(ex, 200, out -> write(out, "data: too late\n\n"));
        });

        server.createContext("/sse/error", ex -> {
            ex.getResponseHeaders().add("Retry-After", "30");
            stream(ex, 503, out -> write(out, "data: not an event\n\n{\"error\":\"overloaded\"}"));
        });

        server.createContext("/sse/echo", ex -> {
            var accept = ex.getRequestHeaders().getFirst("Accept");
            var contentType = ex.getRequestHeaders().getFirst("Content-Type");
            var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            stream(ex, 200, out -> write(out,
                "event: method\ndata: " + ex.getRequestMethod() + "\n\n"
                    + "event: accept\ndata: " + accept + "\n\n"
                    + "event: content-type\ndata: " + contentType + "\n\n"
                    + "event: has-field\ndata: " + body.contains("name=\"prompt\"") + "\n\n"));
        });

        server.createContext("/lines", ex -> {
            ex.getResponseHeaders().add("X-Model", "test-model");
            stream(ex, 200, out -> {
                write(out, "{\"n\":1}\n");
                sleep(100);
                write(out, "{\"n\":2}\n");
                sleep(100);
                write(out, "{\"n\":3}\n");
            });
        });

        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    // --- Events ---

    @Test
    void parsesTheEventStreamFormat() {
        var events = new ArrayList<Http.Event>();
        var response = Http.get(url("/sse/full")).fetchEvents(events::add);

        assertTrue(response.ok());
        assertEquals("", response.body());
        assertEquals(List.of(
            new Http.Event("message", "plain", null, null),
            new Http.Event("update", "line one\nline two\n two spaces", "7", null),
            new Http.Event("message", "", "7", Duration.ofMillis(2500)),
            new Http.Event("message", "{\"n\":1}", "", Duration.ofMillis(2500)),
            new Http.Event("message", "trailing without blank line", "", Duration.ofMillis(2500))
        ), events);
        assertEquals(1, events.get(3).as(Map.class).get("n"));
    }

    @Test
    void eventsArriveBeforeTheStreamEnds() {
        var events = new ArrayList<String>();
        Http.get(url("/sse/handshake")).timeout(Duration.ofSeconds(10)).fetchEvents(event -> {
            events.add(event.data());
            firstEventSeen.countDown();
        });

        // The server held back the second event until the first reached the consumer.
        assertTrue(handshakeSawClient.get(), "first event was buffered until the stream ended");
        assertEquals(List.of("first", "second"), events);
    }

    @Test
    void nonOkStatusReturnsTheErrorBodyWithoutParsingEvents() {
        var events = new ArrayList<Http.Event>();
        var response = Http.post(url("/sse/error")).bodyJson(Map.of("q", 1)).fetchEvents(events::add);

        assertFalse(response.ok());
        assertEquals(503, response.status());
        assertEquals("30", response.header("Retry-After"));
        assertEquals("data: not an event\n\n{\"error\":\"overloaded\"}", response.body());
        assertTrue(events.isEmpty());
    }

    @Test
    void sendsAcceptAndWorksWithMultipart() {
        var events = new java.util.LinkedHashMap<String, String>();
        Http.post(url("/sse/echo")).multipart()
            .field("prompt", "hello")
            .fetchEvents(event -> events.put(event.type(), event.data()));

        assertEquals("POST", events.get("method"));
        assertEquals("text/event-stream", events.get("accept"));
        assertTrue(events.get("content-type").startsWith("multipart/form-data; boundary="));
        assertEquals("true", events.get("has-field"));
    }

    @Test
    void explicitAcceptHeaderIsKept() {
        var events = new java.util.LinkedHashMap<String, String>();
        Http.get(url("/sse/echo")).header("accept", "application/x-ndjson")
            .fetchEvents(event -> events.put(event.type(), event.data()));

        assertEquals("application/x-ndjson", events.get("accept"));
    }

    // --- Timeouts ---

    @Test
    void idleTimeoutClosesAStalledStream() {
        var events = new CopyOnWriteArrayList<String>();
        long start = System.nanoTime();
        var e = assertThrows(Http.StreamTimeoutException.class, () ->
            Http.get(url("/sse/stall"))
                .timeout(Duration.ofSeconds(10))
                .idleTimeout(Duration.ofMillis(300))
                .fetchEvents(event -> events.add(event.data())));

        assertTrue(e.idle());
        assertEquals(Duration.ofMillis(300), e.limit());
        assertTrue(e.getMessage().contains("no data for 300ms"), e.getMessage());
        assertEquals(List.of("before stall"), events);
        assertTrue(elapsedMillis(start) < 3000, "idle timeout took " + elapsedMillis(start) + "ms");
    }

    @Test
    void deadlineBoundsAStreamThatKeepsSending() {
        var events = new CopyOnWriteArrayList<String>();
        long start = System.nanoTime();
        var e = assertThrows(Http.StreamTimeoutException.class, () ->
            Http.get(url("/sse/forever"))
                .timeout(Duration.ofMillis(600))
                .idleTimeout(Duration.ofSeconds(2))
                .fetchEvents(event -> events.add(event.data())));

        assertFalse(e.idle());
        assertEquals(Duration.ofMillis(600), e.limit());
        assertTrue(e.getMessage().contains("exceeded 600ms deadline"), e.getMessage());
        assertTrue(events.size() >= 3, "expected events before the deadline, got " + events);
        assertTrue(elapsedMillis(start) < 3000, "deadline took " + elapsedMillis(start) + "ms");
    }

    @Test
    void deadlineCoversTheWaitForHeaders() {
        long start = System.nanoTime();
        var e = assertThrows(Http.StreamTimeoutException.class, () ->
            Http.get(url("/sse/slow-headers")).timeout(Duration.ofMillis(300)).stream());

        assertFalse(e.idle());
        assertTrue(elapsedMillis(start) < 2500, "deadline took " + elapsedMillis(start) + "ms");
    }

    @Test
    void slowConsumerDoesNotTripTheIdleTimeout() {
        var events = new ArrayList<String>();
        Http.get(url("/sse/burst")).idleTimeout(Duration.ofMillis(100)).fetchEvents(event -> {
            sleep(250);
            events.add(event.data());
        });

        assertEquals(List.of("1", "2", "3"), events);
    }

    @Test
    void timeoutSurfacesFromRawBodyReads() throws IOException {
        try (var response = Http.get(url("/sse/stall")).idleTimeout(Duration.ofMillis(200)).stream()) {
            var body = response.body();
            var first = new byte[64];
            assertTrue(body.read(first) > 0);
            long start = System.nanoTime();
            var e = assertThrows(Http.StreamTimeoutException.class, () -> body.read(first));
            assertTrue(e.idle());
            assertTrue(elapsedMillis(start) < 3000, "idle timeout took " + elapsedMillis(start) + "ms");
        }
    }

    @Test
    void timersShareOneDaemonThread() {
        for (int i = 0; i < 5; i++) {
            Http.get(url("/sse/burst")).idleTimeout(Duration.ofSeconds(1)).fetchEvents(event -> { });
        }
        var timers = Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().equals("brace-http-stream-timer"))
            .toList();
        assertEquals(1, timers.size());
        assertTrue(timers.get(0).isDaemon());
    }

    // --- Raw stream ---

    @Test
    void streamReadsLinesAsTheyArrive() {
        try (var response = Http.get(url("/lines")).stream()) {
            assertTrue(response.ok());
            assertEquals(200, response.status());
            assertEquals("test-model", response.header("X-Model"));
            var lines = response.lines().collect(Collectors.toList());
            assertEquals(List.of("{\"n\":1}", "{\"n\":2}", "{\"n\":3}"), lines);
        }
    }

    @Test
    void streamExposesNonOkStatusAndErrorBody() {
        try (var response = Http.get(url("/sse/error")).stream()) {
            assertFalse(response.ok());
            assertEquals(503, response.status());
            assertTrue(response.readString().endsWith("{\"error\":\"overloaded\"}"));
        }
    }

    @Test
    void closingEarlyReturnsPromptly() {
        long start = System.nanoTime();
        try (var response = Http.get(url("/sse/stall")).timeout(Duration.ofSeconds(10)).stream()) {
            assertEquals("data: before stall", response.lines().findFirst().orElseThrow());
        }
        assertTrue(elapsedMillis(start) < 2000, "close took " + elapsedMillis(start) + "ms");
    }

    // --- Parser edge cases ---

    @Test
    void parserDispatchesOnlyEventsWithData() {
        var events = new ArrayList<Http.Event>();
        var parser = new Http.EventParser(events::add);
        for (var line : List.of("event: ping", "", ":keepalive", "", "data", "", "data:", "data:", "")) {
            parser.line(line);
        }
        parser.end();
        assertEquals(List.of(
            new Http.Event("message", "", null, null),
            new Http.Event("message", "\n", null, null)
        ), events);
    }

    // --- Helpers ---

    private interface Body { void write(OutputStream out) throws IOException; }

    private static void stream(HttpExchange ex, int status, Body body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(status, 0);
        try (var out = ex.getResponseBody()) {
            body.write(out);
        } catch (IOException ignored) {
            // The client hung up (timeout or early close); that's what several tests do.
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean await(CountDownLatch latch, long millis) {
        try {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
