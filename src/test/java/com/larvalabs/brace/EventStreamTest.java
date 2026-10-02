package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.User;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Server-Sent Events: the wire format, events reaching the client as they are sent, a client
 * disconnect ending the producer, and the stream running without a database connection.
 */
class EventStreamTest {

    static TestApp testApp;
    static final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    static CountDownLatch release;
    static CountDownLatch producerEnded;
    static final AtomicReference<Throwable> producerExit = new AtomicReference<>();
    static final AtomicReference<Boolean> openAfterExit = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        testApp = Brace.test().start(app -> {
            // A pool of one: if a stream held the request's connection, every other database
            // request would wait for it.
            app.database(new DatabaseFactory("jdbc:h2:mem:sse-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                null, null, List.of(User.class), 1));

            // Sends one event, waits for the test to have read it, then sends a second.
            app.get("/two", req -> Result.sse(events -> {
                events.send("first", "one", "1");
                assertTrue(release.await(5, TimeUnit.SECONDS));
                events.send("second", "two", "2");
            }));

            app.get("/resume", req -> Result.sse(events ->
                events.send("resumed", "after " + req.header("Last-Event-ID"))));

            // Blocks until interrupted: only the heartbeat can notice the client leaving.
            app.get("/wait", req -> Result.sse(events -> {
                events.heartbeat(Duration.ofMillis(50));
                events.send("hello");
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (Throwable t) {
                    producerExit.set(t);
                    openAfterExit.set(events.isOpen());
                    producerEnded.countDown();
                    throw t;
                }
            }));

            // Sends in a loop with no heartbeat: the failed send ends it.
            app.get("/loop", req -> Result.sse(events -> {
                events.heartbeat(Duration.ZERO);
                try {
                    while (true) {
                        events.send("tick");
                        Thread.sleep(20);
                    }
                } catch (Throwable t) {
                    producerExit.set(t);
                    openAfterExit.set(events.isOpen());
                    producerEnded.countDown();
                    throw t;
                }
            }));

            app.get("/db-stream", (DbHandler) (req, db) -> {
                db.sqlQuery("SELECT 1");
                return Result.sse(events -> {
                    events.send("opened");
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    events.send("done");
                });
            });
            app.get("/db-query", (DbHandler) (req, db) ->
                Result.text("rows=" + db.sqlQuery("SELECT 1").size()));

            // Generated bodies, for the latency contrast with event streams: these are recorded
            // after the write, so their generation time is latency.
            app.get("/slow-writer", req -> Result.stream(out -> {
                try {
                    Thread.sleep(300);
                    out.write("done".getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "text/plain"));
            app.get("/failing-writer", req -> Result.stream(out -> {
                try {
                    out.write("partial\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                throw new IllegalStateException("generator exploded");
            }, "text/plain"));
            // Writes until the client goes away.
            app.get("/endless-writer", req -> Result.stream(out -> {
                try {
                    while (true) {
                        out.write("tick\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(20);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "text/plain"));

            app.get("/fails", req -> Result.sse(events -> {
                events.send("partial");
                throw new IllegalStateException("producer exploded");
            }));
        });
    }

    @AfterAll
    static void stop() throws Exception {
        testApp.stop();
    }

    @BeforeEach
    void reset() {
        release = new CountDownLatch(1);
        producerEnded = new CountDownLatch(1);
        producerExit.set(null);
        openAfterExit.set(null);
    }

    @Test
    void eventsArriveAsTheyAreSent() throws Exception {
        try (var stream = open("/two", null)) {
            assertEquals(200, stream.response.statusCode());
            assertEquals("text/event-stream", stream.header("Content-Type"));
            assertEquals("no-cache", stream.header("Cache-Control"));

            // The producer is blocked until we release it, so this event can only have arrived
            // if it was flushed on send.
            assertEquals(List.of("event: first", "id: 1", "data: one"), stream.nextFrame());
            release.countDown();
            assertEquals(List.of("event: second", "id: 2", "data: two"), stream.nextFrame());
            assertNull(stream.reader.readLine(), "the stream ends when the producer returns");
        }
    }

    /**
     * A stream is recorded once, when it opens: the choke point records before it writes, so the
     * minutes a client stays connected never become one enormous "request" in the latency figures.
     */
    @Test
    void streamIsCountedOnceWhenItOpensAndItsLifetimeIsNotLatency() throws Exception {
        var stats = testApp.app().stats();
        long before = routeCount(stats, "GET /two");
        try (var stream = open("/two", null)) {
            assertEquals(List.of("event: first", "id: 1", "data: one"), stream.nextFrame());
            assertEquals(before + 1, routeCount(stats, "GET /two"), "counted as soon as it opens");

            Thread.sleep(500); // hold the stream open well past any plausible handler latency
            release.countDown();
            assertEquals(List.of("event: second", "id: 2", "data: two"), stream.nextFrame());
            assertNull(stream.reader.readLine());
        }
        assertEquals(before + 1, routeCount(stats, "GET /two"), "and not again when it ends");
        assertTrue(stats.routeStats().get("GET /two").avgLatencyMs() < 500,
            "the stream's lifetime must not be recorded as latency");
    }

    /**
     * The other side of the rule above: every non-SSE response is recorded after it is written,
     * so a generated body's generation time is latency, and it is still counted exactly once
     * when the generator fails or the client leaves mid-body.
     */
    @Test
    void generatedBodyIsCountedOnceAfterItIsWrittenWithItsGenerationTimeAsLatency() throws Exception {
        var stats = testApp.app().stats();
        var response = client.send(HttpRequest.newBuilder(URI.create(testApp.url("/slow-writer"))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals("done", response.body());
        awaitRouteCount(stats, "GET /slow-writer", 1);
        assertTrue(stats.routeStats().get("GET /slow-writer").avgLatencyMs() >= 300,
            "generation time must be recorded as latency, got "
                + stats.routeStats().get("GET /slow-writer").avgLatencyMs() + "ms");
    }

    @Test
    void failedGeneratorIsCountedOnce() throws Exception {
        var stats = testApp.app().stats();
        try (var stream = open("/failing-writer", null)) {
            assertEquals("partial", stream.reader.readLine());
            assertThrows(java.io.IOException.class, () -> {
                while (stream.reader.readLine() != null) { /* drain */ }
            });
        }
        awaitRouteCount(stats, "GET /failing-writer", 1);
        Thread.sleep(200);
        assertEquals(1, routeCount(stats, "GET /failing-writer"), "counted exactly once");
    }

    @Test
    void generatedBodyCutOffByTheClientIsCountedOnce() throws Exception {
        var stats = testApp.app().stats();
        try (var stream = open("/endless-writer", null)) {
            assertEquals("tick", stream.reader.readLine());
            assertEquals(0, routeCount(stats, "GET /endless-writer"), "not recorded while still writing");
        }
        awaitRouteCount(stats, "GET /endless-writer", 1);
        Thread.sleep(200);
        assertEquals(1, routeCount(stats, "GET /endless-writer"), "counted exactly once");
    }

    private static void awaitRouteCount(Stats stats, String key, long expected) throws InterruptedException {
        for (int i = 0; i < 100 && routeCount(stats, key) < expected; i++) Thread.sleep(50);
        assertEquals(expected, routeCount(stats, key), key);
    }

    private static long routeCount(Stats stats, String key) {
        var route = stats.routeStats().get(key);
        return route == null ? 0 : route.count();
    }

    @Test
    void lastEventIdComesFromTheRequestHeader() throws Exception {
        try (var stream = open("/resume", "41")) {
            assertEquals(List.of("event: resumed", "data: after 41"), stream.nextFrame());
        }
    }

    @Test
    void heartbeatDetectsADisconnectAndInterruptsTheProducer() throws Exception {
        try (var stream = open("/wait", null)) {
            assertEquals(List.of("data: hello"), stream.nextFrame());
            assertEquals(List.of(":"), stream.nextFrame(), "an idle stream sends heartbeat comments");
        }
        assertTrue(producerEnded.await(10, TimeUnit.SECONDS), "producer should be woken by the disconnect");
        assertInstanceOf(InterruptedException.class, producerExit.get());
        assertFalse(openAfterExit.get());
    }

    @Test
    void sendAfterADisconnectThrowsAndEndsTheLoop() throws Exception {
        try (var stream = open("/loop", null)) {
            assertEquals(List.of("data: tick"), stream.nextFrame());
        }
        assertTrue(producerEnded.await(10, TimeUnit.SECONDS), "a failed send should end the producer");
        assertInstanceOf(UncheckedIOException.class, producerExit.get());
        assertFalse(openAfterExit.get());
    }

    @Test
    void streamHoldsNoDatabaseConnection() throws Exception {
        try (var stream = open("/db-stream", null)) {
            assertEquals(List.of("data: opened"), stream.nextFrame());

            // The stream is open and its producer is blocked. With a pool of one, this request
            // only gets a connection if the stream gave its own back.
            var other = client.send(HttpRequest.newBuilder(URI.create(testApp.url("/db-query")))
                    .timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, other.statusCode());
            assertEquals("rows=1", other.body());

            release.countDown();
            assertEquals(List.of("data: done"), stream.nextFrame());
        }
    }

    @Test
    void producerFailureAbortsTheResponse() throws Exception {
        try (var stream = open("/fails", null)) {
            assertEquals(List.of("data: partial"), stream.nextFrame());
            // Not a clean end of stream: the connection is aborted, so the client cannot mistake
            // a crashed producer for one that finished.
            assertThrows(java.io.IOException.class, () -> {
                while (stream.reader.readLine() != null) { /* drain */ }
            });
        }
    }

    // --- wire format, without a server ---

    @Test
    void wireFormat() {
        var out = new ByteArrayOutputStream();
        var events = new EventStream(out);
        events.send("line one\nline two\r\nline three");
        events.send("update", "x", "7");
        events.sendJson("json", java.util.Map.of("a", 1), "8");
        events.comment("keep\nalive");
        events.retry(Duration.ofSeconds(3));

        assertEquals("""
            data: line one
            data: line two
            data: line three

            event: update
            id: 7
            data: x

            event: json
            id: 8
            data: {"a":1}

            : keep
            : alive

            retry: 3000

            """, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void lineBreaksInFieldsAreRejected() {
        var events = new EventStream(new ByteArrayOutputStream());
        assertThrows(IllegalArgumentException.class, () -> events.send("a\nb", "x"));
        assertThrows(IllegalArgumentException.class, () -> events.send(null, "x", "1\r2"));
    }

    // --- helpers ---

    static Stream open(String path, String lastEventId) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(testApp.url(path)))
            .header("Accept", "text/event-stream");
        if (lastEventId != null) builder.header("Last-Event-ID", lastEventId);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new Stream(response);
    }

    static final class Stream implements AutoCloseable {
        final HttpResponse<InputStream> response;
        final BufferedReader reader;

        Stream(HttpResponse<InputStream> response) {
            this.response = response;
            this.reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        }

        String header(String name) {
            return response.headers().firstValue(name).orElse(null);
        }

        /** The lines of the next event, up to the blank line that ends it. */
        List<String> nextFrame() throws Exception {
            var lines = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                lines.add(line);
            }
            return lines;
        }

        @Override
        public void close() throws Exception {
            response.body().close();
        }
    }
}
