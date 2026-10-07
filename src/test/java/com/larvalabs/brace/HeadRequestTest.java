package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HEAD is GET without the body (RFC 9110 §9.3.2): every GET route answers it with the status and
 * headers the GET would have sent, and no body bytes.
 *
 * <p>Requests go over a raw socket rather than {@link java.net.http.HttpClient}, which knows HEAD
 * responses carry no body and would hide body bytes that leaked onto the wire.
 */
class HeadRequestTest {

    static Brace app;
    static int port;
    static Path staticDir;
    static Path bigFile;
    static final AtomicInteger cachedRenders = new AtomicInteger();
    static final AtomicInteger producerRuns = new AtomicInteger();

    @BeforeAll
    static void startApp() throws Exception {
        staticDir = Files.createTempDirectory("brace-head-static");
        Files.writeString(staticDir.resolve("hello.txt"), "hello static");
        bigFile = staticDir.resolve("big.bin");
        Files.write(bigFile, new byte[200_000]);

        app = Brace.app().port(0).staticFiles("/assets", staticDir.toString());
        app.get("/", req -> Result.html("<h1>home</h1>"));
        app.get("/users/{id}", req -> Result.text("user " + req.pathParam("id")));
        app.get("/method", req -> Result.text("method=" + req.method()));
        app.get("/file", req -> Result.file(bigFile));
        app.get("/generated", req -> Result.stream(out -> {
            try {
                out.write("generated body".getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "text/plain"));
        app.get("/events", req -> Result.sse(events -> {
            producerRuns.incrementAndGet();
            while (events.isOpen()) {
                events.send("tick");
                Thread.sleep(50);
            }
        }));
        app.get("/explicit", req -> Result.text("from GET"));
        app.router().add("HEAD", "/explicit", req -> Result.text("from HEAD").header("X-Route", "head"));
        app.get("/cached", Brace.cache().wrap("5m", req -> {
            cachedRenders.incrementAndGet();
            return Result.text("cached body");
        }));
        app.post("/form", req -> Result.text("posted"));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @Test
    void headOnGetRouteReturnsGetStatusAndHeadersWithoutBody() throws Exception {
        var get = send("GET", "/");
        var head = send("HEAD", "/");

        assertEquals(200, head.status);
        assertEquals(get.headers.get("content-type"), head.headers.get("content-type"));
        assertEquals(String.valueOf("<h1>home</h1>".length()), head.headers.get("content-length"),
            "HEAD must advertise the length the GET would have sent");
        assertEquals("", head.body);
    }

    @Test
    void headMatchesDynamicAndTrailingSlashRoutes() throws Exception {
        var head = send("HEAD", "/users/42");
        assertEquals(200, head.status);
        assertEquals(String.valueOf("user 42".length()), head.headers.get("content-length"));
        assertEquals("", head.body);

        assertEquals(200, send("HEAD", "/users/42/").status);
    }

    @Test
    void handlerSeesHeadMethod() throws Exception {
        // The handler runs as for GET but can tell the difference; the length shows which it saw.
        var head = send("HEAD", "/method");
        assertEquals(String.valueOf("method=HEAD".length()), head.headers.get("content-length"));
    }

    @Test
    void unknownPathStill404s() throws Exception {
        assertEquals(404, send("HEAD", "/nope").status);
    }

    @Test
    void headDoesNotFallBackToOtherMethods() throws Exception {
        assertEquals(404, send("HEAD", "/form").status);
    }

    @Test
    void explicitHeadRouteWinsOverGet() throws Exception {
        var head = send("HEAD", "/explicit");
        assertEquals(200, head.status);
        assertEquals("head", head.headers.get("x-route"));
        assertEquals("", head.body);
    }

    @Test
    void staticFilesStillAnswerHead() throws Exception {
        var head = send("HEAD", "/assets/hello.txt");
        assertEquals(200, head.status);
        assertEquals(String.valueOf("hello static".length()), head.headers.get("content-length"));
        assertNotNull(head.headers.get("etag"));
        assertEquals("", head.body);
    }

    @Test
    void fileResultKeepsLengthAndEtagWithoutBody() throws Exception {
        var get = send("GET", "/file");
        var head = send("HEAD", "/file");
        assertEquals(200, head.status);
        assertEquals("200000", head.headers.get("content-length"));
        assertEquals(get.headers.get("etag"), head.headers.get("etag"));
        assertEquals("", head.body);
    }

    @Test
    void generatedStreamSendsNoBody() throws Exception {
        var head = send("HEAD", "/generated");
        assertEquals(200, head.status);
        assertEquals("text/plain", head.headers.get("content-type"));
        assertEquals("", head.body);
    }

    @Test
    void eventStreamIsNotOpenedForHead() throws Exception {
        int before = producerRuns.get();
        var head = send("HEAD", "/events");
        assertEquals(200, head.status);
        assertTrue(head.headers.get("content-type").startsWith("text/event-stream"));
        assertEquals("", head.body);
        assertEquals(before, producerRuns.get(),
            "a HEAD must not start a producer that would run until the client leaves");
    }

    @Test
    void headSharesTheGetCacheEntry() throws Exception {
        int before = cachedRenders.get();
        assertEquals(200, send("GET", "/cached").status);
        var head = send("HEAD", "/cached");
        assertEquals(200, head.status);
        assertEquals(String.valueOf("cached body".length()), head.headers.get("content-length"));
        assertEquals(1, cachedRenders.get() - before, "HEAD should be served from the GET's entry");
    }

    @Test
    void statsRecordHeadSeparately() throws Exception {
        send("HEAD", "/users/7");
        var routes = app.stats().routeStats();
        assertNotNull(routes.get("HEAD /users/{id}"), "HEAD keyed under its own method: " + routes.keySet());
    }

    // --- raw HTTP/1.1 client ---

    record RawResponse(int status, Map<String, String> headers, String body) {}

    static RawResponse send(String method, String path) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            var request = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String raw = readAll(socket.getInputStream());
            int split = raw.indexOf("\r\n\r\n");
            assertTrue(split > 0, "no header terminator in: " + raw);
            String[] lines = raw.substring(0, split).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, colon).strip().toLowerCase(), lines[i].substring(colon + 1).strip());
            }
            return new RawResponse(status, headers, raw.substring(split + 4));
        }
    }

    private static String readAll(InputStream in) throws Exception {
        var out = new ByteArrayOutputStream();
        var buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (SocketTimeoutException e) {
            // Connection: close should end every response; a timeout means the server held it open.
            fail("server did not close the connection; read so far: " + out.toString(StandardCharsets.ISO_8859_1));
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }
}
