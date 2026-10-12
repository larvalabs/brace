package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gzip response compression ({@link CompressionHandler}, on by default). The JDK client sends no
 * {@code Accept-Encoding} on its own, so every request here that expects gzip asks for it.
 */
class CompressionTest {

    static final String PAGE = "<html><body>" + "<p>row</p>".repeat(500) + "</body></html>";
    static final String CSS = "body { color: #333; }\n".repeat(200);
    static final CountDownLatch released = new CountDownLatch(1);

    static Brace app;
    static int port;
    static Path staticDir;
    static HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeAll
    static void startApp() throws Exception {
        staticDir = Files.createTempDirectory("brace-gzip-static");
        Files.writeString(staticDir.resolve("site.css"), CSS);
        Files.write(staticDir.resolve("photo.png"), new byte[4096]);

        app = Brace.app().port(0).banner(false)
            .staticFiles("/assets", staticDir.toString())
            .maxUploadSize("64k");
        app.get("/page", req -> Result.html(PAGE));
        app.get("/small", req -> Result.html("<p>hi</p>"));
        app.get("/png", req -> Result.bytes(new byte[4096], "image/png"));
        app.post("/form", req -> Result.html(PAGE)).csrf(false);
        app.post("/echo-length", req -> Result.text("length:" + req.body().length())).csrf(false);
        app.get("/events", req -> Result.sse(events -> events.send("big", "x".repeat(4096))));
        // One line, then nothing until the test has read it: a deflater that held small writes
        // back would deadlock here until the latch times out.
        app.get("/progress", req -> Result.stream(out -> {
            try {
                out.write("first line\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (!released.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("never read");
                out.write("second line\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "application/x-ndjson"));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    // --- what gets compressed ---

    @Test
    void htmlIsGzippedForAClientThatAsks() throws Exception {
        var resp = send(get("/page").header("Accept-Encoding", "gzip"));

        assertEquals(200, resp.statusCode());
        assertEquals("gzip", header(resp, "Content-Encoding"));
        assertTrue(varyHas(resp, "Accept-Encoding"), header(resp, "Vary"));
        assertEquals(PAGE, gunzip(resp.body()));
        assertTrue(resp.body().length < PAGE.length() / 10, "compressed to " + resp.body().length);
    }

    @Test
    void aClientThatDoesNotAskGetsPlainBytesThatStillVary() throws Exception {
        var resp = send(get("/page"));

        assertNull(header(resp, "Content-Encoding"));
        assertEquals(PAGE, new String(resp.body(), StandardCharsets.UTF_8));
        assertTrue(varyHas(resp, "Accept-Encoding"), "a cache must not reuse this for a gzip client");
    }

    @Test
    void anHtmxResponseVariesOnBothHxRequestAndAcceptEncoding() throws Exception {
        var resp = send(get("/page").header("Accept-Encoding", "gzip").header("HX-Request", "true"));

        assertEquals("gzip", header(resp, "Content-Encoding"));
        assertTrue(varyHas(resp, "HX-Request"), header(resp, "Vary"));
        assertTrue(varyHas(resp, "Accept-Encoding"), header(resp, "Vary"));
    }

    @Test
    void postResponsesAreGzippedToo() throws Exception {
        var resp = send(HttpRequest.newBuilder(uri("/form"))
            .header("Accept-Encoding", "gzip")
            .POST(HttpRequest.BodyPublishers.noBody()));

        assertEquals("gzip", header(resp, "Content-Encoding"));
        assertEquals(PAGE, gunzip(resp.body()));
    }

    @Test
    void bodiesUnderAKilobyteAreLeftAlone() throws Exception {
        var resp = send(get("/small").header("Accept-Encoding", "gzip"));

        assertNull(header(resp, "Content-Encoding"));
        assertEquals("<p>hi</p>", new String(resp.body(), StandardCharsets.UTF_8));
    }

    @Test
    void alreadyCompressedTypesAreLeftAlone() throws Exception {
        var byType = send(get("/png").header("Accept-Encoding", "gzip"));
        assertNull(header(byType, "Content-Encoding"));
        assertEquals(4096, byType.body().length);

        var byExtension = send(get("/assets/photo.png").header("Accept-Encoding", "gzip"));
        assertNull(header(byExtension, "Content-Encoding"));
        assertEquals(4096, byExtension.body().length);
    }

    @Test
    void compressionCanBeTurnedOff() throws Exception {
        var plain = Brace.app().port(0).banner(false).compression(false);
        plain.get("/page", req -> Result.html(PAGE));
        plain.start();
        try {
            var resp = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + plain.actualPort() + "/page"))
                .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());

            assertNull(header(resp, "Content-Encoding"));
            assertFalse(varyHas(resp, "Accept-Encoding"));
            assertEquals(PAGE, new String(resp.body(), StandardCharsets.UTF_8));
        } finally {
            plain.stop();
        }
    }

    // --- streaming ---

    @Test
    void eventStreamsAreNeverCompressed() throws Exception {
        var resp = send(get("/events").header("Accept-Encoding", "gzip"));

        assertEquals("text/event-stream", header(resp, "Content-Type").split(";")[0]);
        assertNull(header(resp, "Content-Encoding"));
        assertTrue(new String(resp.body(), StandardCharsets.UTF_8).contains("event: big"));
    }

    @Test
    void aStreamedBodyStillArrivesAsItIsWritten() throws Exception {
        var resp = client.send(get("/progress").header("Accept-Encoding", "gzip").build(),
            HttpResponse.BodyHandlers.ofInputStream());
        assertEquals("gzip", resp.headers().firstValue("Content-Encoding").orElse(null));

        try (InputStream in = new GZIPInputStream(resp.body())) {
            String first = readLine(in);
            assertEquals("first line", first, "the first write should arrive before the second is made");
            released.countDown();
            assertEquals("second line", readLine(in));
        }
    }

    // --- conditional and range requests ---

    @Test
    void aGzippedStaticFileRevalidatesWithItsSuffixedEtag() throws Exception {
        var first = send(get("/assets/site.css").header("Accept-Encoding", "gzip"));
        assertEquals("gzip", header(first, "Content-Encoding"));
        assertEquals(CSS, gunzip(first.body()));
        String etag = header(first, "ETag");
        assertNotNull(etag);
        assertTrue(etag.endsWith("--gzip\""), etag);

        var again = send(get("/assets/site.css").header("Accept-Encoding", "gzip")
            .header("If-None-Match", etag));
        assertEquals(304, again.statusCode());
        assertEquals(etag, header(again, "ETag"));
    }

    @Test
    void theBundledHtmxRevalidatesWhenGzipped() throws Exception {
        var first = send(get("/__brace/htmx.min.js").header("Accept-Encoding", "gzip"));
        assertEquals(200, first.statusCode());
        assertEquals("gzip", header(first, "Content-Encoding"));
        String etag = header(first, "ETag");

        var again = send(get("/__brace/htmx.min.js").header("Accept-Encoding", "gzip")
            .header("If-None-Match", etag));
        assertEquals(304, again.statusCode());
    }

    @Test
    void aRangeRequestGetsUncompressedBytesOfTheFile() throws Exception {
        var resp = send(get("/assets/site.css").header("Accept-Encoding", "gzip")
            .header("Range", "bytes=0-99"));

        assertEquals(206, resp.statusCode());
        assertNull(header(resp, "Content-Encoding"));
        assertEquals("bytes 0-99/" + CSS.length(), header(resp, "Content-Range"));
        assertEquals(CSS.substring(0, 100), new String(resp.body(), StandardCharsets.UTF_8));
    }

    @Test
    void aResumeAgainstAGzippedEtagGetsTheWholeFile() throws Exception {
        String gzEtag = header(send(get("/assets/site.css").header("Accept-Encoding", "gzip")), "ETag");

        var resp = send(get("/assets/site.css").header("Accept-Encoding", "gzip")
            .header("Range", "bytes=100-").header("If-Range", gzEtag));

        assertEquals(200, resp.statusCode());
        assertEquals(CSS, new String(resp.body(), StandardCharsets.UTF_8));
    }

    // --- request bodies ---

    @Test
    void aGzippedRequestBodyIsNotInflatedPastTheUploadCap() throws Exception {
        byte[] bomb = gzip(new byte[1024 * 1024]); // 1 MB of zeros: about 1 KB on the wire

        var resp = send(HttpRequest.newBuilder(uri("/echo-length"))
            .header("Content-Encoding", "gzip")
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bomb)));

        // Inflated, the body would be 16x the 64 KB cap; the handler sees the compressed bytes.
        assertEquals(200, resp.statusCode());
        String length = new String(resp.body(), StandardCharsets.UTF_8);
        assertTrue(Integer.parseInt(length.substring("length:".length())) < 64 * 1024, length);
    }

    // --- Vary merging ---

    @Test
    void varyValuesMergeWithoutDuplicates() {
        assertEquals("Accept-Encoding, HX-Request", BraceHandler.mergeVary("Accept-Encoding", "HX-Request"));
        assertEquals("Accept-Encoding", BraceHandler.mergeVary("Accept-Encoding", "accept-encoding"));
        assertEquals("HX-Request", BraceHandler.mergeVary(null, "HX-Request"));
        assertEquals("*", BraceHandler.mergeVary("Accept-Encoding", "*"));
    }

    // --- helpers ---

    private static URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(uri(path)).GET();
    }

    private static HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).orElse(null);
    }

    private static boolean varyHas(HttpResponse<?> resp, String token) {
        return resp.headers().allValues("Vary").stream()
            .flatMap(v -> java.util.Arrays.stream(v.split(",")))
            .anyMatch(t -> t.trim().equalsIgnoreCase(token));
    }

    private static String gunzip(byte[] bytes) throws Exception {
        try (var in = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }

    /** Reads up to a newline one byte at a time, so nothing past the line is waited for. */
    private static String readLine(InputStream in) throws Exception {
        var line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') line.write(b);
        return line.toString(StandardCharsets.UTF_8);
    }
}
