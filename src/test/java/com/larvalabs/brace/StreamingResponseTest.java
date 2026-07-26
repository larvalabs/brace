package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streaming responses: file, stream, generated-writer and download bodies, plus {@code Range}
 * support and the guards around what a streaming response cannot do.
 */
class StreamingResponseTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;
    static Path staticDir;
    static Path bigFile;
    static byte[] bigContent;

    @BeforeAll
    static void startApp() throws Exception {
        staticDir = Files.createTempDirectory("brace-stream-static");
        bigContent = ramp(300_000);
        bigFile = staticDir.resolve("movie.mp4");
        Files.write(bigFile, bigContent);
        Files.writeString(staticDir.resolve("hello.txt"), "hello static");

        app = Brace.app().port(0).staticFiles("/assets", staticDir.toString());

        app.get("/file", req -> Result.file(bigFile));
        app.get("/file-typed", req -> Result.file(bigFile, "video/mp4"));
        app.get("/download", req -> Result.download(bigFile, "my report.csv"));
        app.get("/stream-unknown", req ->
            Result.stream(new java.io.ByteArrayInputStream(bigContent), "application/octet-stream"));
        app.get("/stream-known", req -> Result.stream(
            new java.io.ByteArrayInputStream(bigContent), "application/octet-stream", bigContent.length));
        app.get("/generated", req -> Result.stream(out -> {
            var w = new PrintWriter(out);
            w.println("id,name");
            for (int i = 0; i < 1000; i++) w.println(i + ",row" + i);
            w.flush();
        }, "text/csv"));
        app.get("/generated-fails", req -> Result.stream(out -> {
            try {
                out.write("partial".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            throw new RuntimeException("generator exploded halfway");
        }, "text/plain"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    // --- bodies ---

    @Test
    void fileIsStreamedWholeWithLengthAndType() throws Exception {
        var resp = getBytes("/file");

        assertEquals(200, resp.statusCode());
        assertArrayEquals(bigContent, resp.body());
        assertEquals("video/mp4", header(resp, "Content-Type"));
        assertEquals(String.valueOf(bigContent.length), header(resp, "Content-Length"));
        assertEquals("bytes", header(resp, "Accept-Ranges"));
    }

    @Test
    void explicitContentTypeWins() throws Exception {
        assertEquals("video/mp4", header(getBytes("/file-typed"), "Content-Type"));
    }

    @Test
    void downloadSetsAnEscapedContentDisposition() throws Exception {
        var resp = getBytes("/download");

        assertEquals(200, resp.statusCode());
        assertArrayEquals(bigContent, resp.body());
        String disposition = header(resp, "Content-Disposition");
        assertTrue(disposition.startsWith("attachment; filename=\"my report.csv\""), disposition);
        assertTrue(disposition.contains("filename*=UTF-8''my%20report.csv"), disposition);
    }

    @Test
    void unknownLengthStreamIsChunked() throws Exception {
        var resp = getBytes("/stream-unknown");

        assertEquals(200, resp.statusCode());
        assertArrayEquals(bigContent, resp.body());
        assertNull(header(resp, "Content-Length"), "an unknown-length body must not claim a length");
    }

    @Test
    void knownLengthStreamDeclaresContentLength() throws Exception {
        var resp = getBytes("/stream-known");

        assertEquals(200, resp.statusCode());
        assertArrayEquals(bigContent, resp.body());
        assertEquals(String.valueOf(bigContent.length), header(resp, "Content-Length"));
    }

    @Test
    void generatedContentStreams() throws Exception {
        var resp = getString("/generated");

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().startsWith("id,name"), resp.body().substring(0, 40));
        assertTrue(resp.body().contains("999,row999"));
    }

    /**
     * The status line is already on the wire when a generator fails, so the response cannot become
     * a 500. Aborting is the only honest signal — what must never happen is a clean 200 that is
     * silently short.
     */
    @Test
    void generatorFailingMidStreamAbortsRatherThanTruncatingCleanly() {
        assertThrows(java.io.IOException.class, () -> getString("/generated-fails"),
            "a failed stream must abort the connection, not complete as a valid short response");
    }

    // --- range ---

    @Test
    void rangeServesPartialContent() throws Exception {
        var resp = getRange("/file", "bytes=100-199");

        assertEquals(206, resp.statusCode());
        assertEquals(100, resp.body().length);
        assertArrayEquals(java.util.Arrays.copyOfRange(bigContent, 100, 200), resp.body());
        assertEquals("bytes 100-199/" + bigContent.length, header(resp, "Content-Range"));
        assertEquals("100", header(resp, "Content-Length"));
    }

    @Test
    void openEndedRangeRunsToTheEnd() throws Exception {
        var resp = getRange("/file", "bytes=299000-");

        assertEquals(206, resp.statusCode());
        assertEquals(1000, resp.body().length);
        assertArrayEquals(java.util.Arrays.copyOfRange(bigContent, 299_000, 300_000), resp.body());
    }

    @Test
    void suffixRangeServesTheTail() throws Exception {
        var resp = getRange("/file", "bytes=-500");

        assertEquals(206, resp.statusCode());
        assertEquals(500, resp.body().length);
        assertArrayEquals(
            java.util.Arrays.copyOfRange(bigContent, bigContent.length - 500, bigContent.length),
            resp.body());
    }

    @Test
    void rangePastTheEndIsClamped() throws Exception {
        var resp = getRange("/file", "bytes=299500-999999");

        assertEquals(206, resp.statusCode(), "asking past the end is normal, not an error");
        assertEquals(500, resp.body().length);
    }

    @Test
    void rangeStartingPastTheEndIs416() throws Exception {
        var resp = getRange("/file", "bytes=999999-");

        assertEquals(416, resp.statusCode());
        assertEquals("bytes */" + bigContent.length, header(resp, "Content-Range"));
    }

    @Test
    void multiRangeFallsBackToTheWholeBody() throws Exception {
        var resp = getRange("/file", "bytes=0-99,200-299");

        assertEquals(200, resp.statusCode(), "multi-range is served whole rather than as multipart");
        assertArrayEquals(bigContent, resp.body());
    }

    @Test
    void malformedRangeIsIgnored() throws Exception {
        assertEquals(200, getRange("/file", "bytes=abc-def").statusCode());
        assertEquals(200, getRange("/file", "widgets=0-10").statusCode());
        assertEquals(200, getRange("/file", "bytes=50-10").statusCode());
    }

    @Test
    void nonSeekableStreamsIgnoreRange() throws Exception {
        var resp = getRange("/stream-known", "bytes=0-99");

        assertEquals(200, resp.statusCode(),
            "a one-shot stream cannot seek, so it must serve the whole body rather than lie");
        assertArrayEquals(bigContent, resp.body());
    }

    // --- static files ---

    @Test
    void staticFilesStreamAndSupportRange() throws Exception {
        var whole = getBytes("/assets/movie.mp4");
        assertEquals(200, whole.statusCode());
        assertArrayEquals(bigContent, whole.body());
        assertEquals("bytes", header(whole, "Accept-Ranges"));
        assertEquals("nosniff", header(whole, "X-Content-Type-Options"));

        var partial = getRange("/assets/movie.mp4", "bytes=1000-1099");
        assertEquals(206, partial.statusCode());
        assertEquals(100, partial.body().length);
        assertArrayEquals(java.util.Arrays.copyOfRange(bigContent, 1000, 1100), partial.body());
    }

    @Test
    void staticFileValidatorsSurviveStreaming() throws Exception {
        var first = getBytes("/assets/hello.txt");
        assertEquals(200, first.statusCode());
        String etag = header(first, "ETag");
        assertNotNull(etag);
        assertNotNull(header(first, "Last-Modified"));

        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assets/hello.txt"))
            .header("If-None-Match", etag).GET().build();
        assertEquals(304, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(),
            "conditional GETs must keep working now that the body is streamed");
    }

    @Test
    void ifRangeWithAStaleValidatorServesTheWholeFile() throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assets/movie.mp4"))
            .header("Range", "bytes=0-99")
            .header("If-Range", "\"not-the-current-etag\"")
            .GET().build();
        var resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, resp.statusCode(),
            "a range against a stale copy must return the whole current file, not a spliced one");
        assertArrayEquals(bigContent, resp.body());
    }

    @Test
    void ifRangeWithTheCurrentValidatorServesTheRange() throws Exception {
        String etag = header(getBytes("/assets/movie.mp4"), "ETag");

        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assets/movie.mp4"))
            .header("Range", "bytes=0-99")
            .header("If-Range", etag)
            .GET().build();
        var resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(206, resp.statusCode());
        assertEquals(100, resp.body().length);
    }

    // --- guards ---

    @Test
    void streamingResultsCannotBePageCached() {
        var e = assertThrows(IllegalStateException.class,
            () -> Cache.RenderedResponse.from(Result.file(bigFile)));
        assertTrue(e.getMessage().contains("cannot be page cached"), e.getMessage());
    }

    @Test
    void streamingResultsReportThemselvesAsSuch() {
        assertTrue(Result.file(bigFile).isStreaming());
        assertTrue(Result.stream(out -> {}, "text/plain").isStreaming());
        assertFalse(Result.text("plain").isStreaming());
        assertFalse(Result.json(java.util.Map.of("a", 1)).isStreaming());
    }

    @Test
    void streamingResultsHaveNoMaterializedBody() {
        var result = Result.file(bigFile);
        assertNull(result.rawBytes());
        assertNull(result.body(),
            "after-middleware that rewrites bodies must see null, not a silently empty string");
    }

    // --- helpers ---

    private static byte[] ramp(int n) {
        var b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i % 251);
        return b;
    }

    private static String header(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).orElse(null);
    }

    private static HttpResponse<byte[]> getBytes(String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<String> getString(String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<byte[]> getRange(String path, String range) throws Exception {
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Range", range).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    }
}
