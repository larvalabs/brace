package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StaticFilesTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;
    static Path tempDir;

    @BeforeAll
    static void startApp() throws Exception {
        // Create a temporary directory with static files
        tempDir = Files.createTempDirectory("brace-static-test");

        // Text files
        Files.writeString(tempDir.resolve("hello.html"), "<h1>Hello</h1>");
        Files.writeString(tempDir.resolve("style.css"), "body { color: red; }");
        Files.writeString(tempDir.resolve("app.js"), "console.log('hello');");

        // Subdirectory
        var imagesDir = Files.createDirectory(tempDir.resolve("images"));

        // Binary file (a minimal valid PNG: 1x1 red pixel)
        byte[] pngBytes = minimalPng();
        Files.write(imagesDir.resolve("pixel.png"), pngBytes);

        // A second static directory for testing multiple prefixes
        var otherDir = Files.createTempDirectory("brace-other-static-test");
        Files.writeString(otherDir.resolve("other.txt"), "other content");

        app = Brace.app().port(0)
            .staticFiles("/assets", tempDir.toString())
            .staticFiles("/files", otherDir.toString());

        app.get("/hello", req -> Result.text("dynamic hello"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private String getBody(String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void servesHtmlFile() throws Exception {
        var response = get("/assets/hello.html");
        assertEquals(200, response.statusCode());
        assertEquals("<h1>Hello</h1>", response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
    }

    @Test
    void servesCssFile() throws Exception {
        var response = get("/assets/style.css");
        assertEquals(200, response.statusCode());
        assertEquals("body { color: red; }", response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/css"));
    }

    @Test
    void servesJsFile() throws Exception {
        var response = get("/assets/app.js");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("javascript"));
    }

    @Test
    void servesBinaryPng() throws Exception {
        var response = getBytes("/assets/images/pixel.png");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("image/png"));
        // Verify it starts with the PNG signature
        byte[] body = response.body();
        assertTrue(body.length > 8);
        assertEquals((byte) 0x89, body[0]);
        assertEquals((byte) 'P', body[1]);
        assertEquals((byte) 'N', body[2]);
        assertEquals((byte) 'G', body[3]);
    }

    @Test
    void returnsNotFoundForMissingFile() throws Exception {
        var response = get("/assets/nonexistent.html");
        assertEquals(404, response.statusCode());
    }

    @Test
    void returnsNotFoundForDirectory() throws Exception {
        var response = get("/assets/images");
        assertEquals(404, response.statusCode());
    }

    @Test
    void rejectsDirectoryTraversalWithDotDot() throws Exception {
        var response = get("/assets/../hello.html");
        // Jetty normalizes paths, but we also check for ".." in our handler
        // Either way, it should not serve the file (404 or the path gets normalized away)
        assertNotEquals(200, response.statusCode());
    }

    @Test
    void rejectsDirectoryTraversalWithEncodedDotDot() throws Exception {
        var response = get("/assets/images/../../hello.html");
        assertNotEquals(200, response.statusCode());
    }

    @Test
    void multiplePrefixesWork() throws Exception {
        var response = get("/files/other.txt");
        assertEquals(200, response.statusCode());
        assertEquals("other content", response.body());
    }

    @Test
    void dynamicRouteTakesPrecedenceOverStaticFiles() throws Exception {
        // /hello is a registered dynamic route, should be served by the handler not static files
        var response = get("/hello");
        assertEquals(200, response.statusCode());
        assertEquals("dynamic hello", response.body());
    }

    @Test
    void unrelatedPathReturns404() throws Exception {
        var response = get("/notaprefix/hello.html");
        assertEquals(404, response.statusCode());
    }

    @Test
    void servesFileInSubdirectory() throws Exception {
        var response = get("/assets/images/pixel.png");
        assertEquals(200, response.statusCode());
    }

    @Test
    void staticFilesIncludeNoSniffHeader() throws Exception {
        // Verify L7: X-Content-Type-Options: nosniff is set on static file responses
        var htmlResponse = get("/assets/hello.html");
        assertEquals(200, htmlResponse.statusCode());
        assertEquals("nosniff", htmlResponse.headers().firstValue("X-Content-Type-Options").orElse(null),
                     "Static HTML file should have X-Content-Type-Options: nosniff");

        var cssResponse = get("/assets/style.css");
        assertEquals(200, cssResponse.statusCode());
        assertEquals("nosniff", cssResponse.headers().firstValue("X-Content-Type-Options").orElse(null),
                     "Static CSS file should have X-Content-Type-Options: nosniff");

        var jsResponse = get("/assets/app.js");
        assertEquals(200, jsResponse.statusCode());
        assertEquals("nosniff", jsResponse.headers().firstValue("X-Content-Type-Options").orElse(null),
                     "Static JS file should have X-Content-Type-Options: nosniff");

        var pngResponse = getBytes("/assets/images/pixel.png");
        assertEquals(200, pngResponse.statusCode());
        assertEquals("nosniff", pngResponse.headers().firstValue("X-Content-Type-Options").orElse(null),
                     "Static image file should have X-Content-Type-Options: nosniff");
    }

    @Test
    void staticFilesEmitValidatorsAndRevalidateCacheControl() throws Exception {
        // L21: unfingerprinted URLs get an ETag + Last-Modified and a revalidate-always policy.
        var response = get("/assets/style.css");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("ETag").isPresent(), "expected an ETag");
        assertTrue(response.headers().firstValue("Last-Modified").isPresent(), "expected Last-Modified");
        assertEquals("public, max-age=0, must-revalidate",
            response.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void fingerprintedUrlGetsImmutableCacheControl() throws Exception {
        // L21: a GENUINE current "?v=" fingerprint (the value Assets.url mints) is content-addressed
        // → cache for a year. Must use the real hash, since immutability is now verified against it.
        String fingerprinted = Assets.url("/assets/style.css"); // /assets/style.css?v=<realhash>
        assertTrue(fingerprinted.contains("?v="), "expected a fingerprinted URL, got: " + fingerprinted);
        var response = get(fingerprinted);
        assertEquals(200, response.statusCode());
        assertEquals("public, max-age=31536000, immutable",
            response.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void staleOrBogusVersionParamDoesNotGetImmutable() throws Exception {
        // A "?v=" that is NOT the file's current fingerprint (a stale hash, a hand-rolled value,
        // or one appended by a CDN/client) must fall back to revalidate-always — never pinned as
        // immutable content-addressed, or wrong/old bytes could be cached for a year.
        var response = get("/assets/style.css?v=deadbeef");
        assertEquals(200, response.statusCode());
        assertEquals("public, max-age=0, must-revalidate",
            response.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void htmxBundleIsCacheableAndRevalidates() throws Exception {
        var first = get("/__brace/htmx.min.js");
        assertEquals(200, first.statusCode());
        String etag = first.headers().firstValue("ETag").orElse(null);
        assertNotNull(etag, "bundled htmx should carry an ETag");
        // Version-pinned, not immutable: revalidate so a brace upgrade isn't masked by a year-long cache.
        assertEquals("public, max-age=0, must-revalidate",
            first.headers().firstValue("Cache-Control").orElse(null));

        var conditional = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/__brace/htmx.min.js"))
            .header("If-None-Match", etag)
            .GET()
            .build();
        var revalidated = client.send(conditional, HttpResponse.BodyHandlers.ofString());
        assertEquals(304, revalidated.statusCode(), "matching ETag should revalidate to 304");
    }

    @Test
    void conditionalGetWithIfNoneMatchReturns304() throws Exception {
        var first = get("/assets/style.css");
        String etag = first.headers().firstValue("ETag").orElseThrow();

        var conditional = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assets/style.css"))
            .header("If-None-Match", etag)
            .GET()
            .build();
        var response = client.send(conditional, HttpResponse.BodyHandlers.ofString());
        assertEquals(304, response.statusCode());
        assertEquals("", response.body(), "304 must not carry a body");
        assertEquals(etag, response.headers().firstValue("ETag").orElse(null));
    }

    @Test
    void conditionalGetWithIfModifiedSinceReturns304() throws Exception {
        var first = get("/assets/style.css");
        String lastModified = first.headers().firstValue("Last-Modified").orElseThrow();

        var conditional = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assets/style.css"))
            .header("If-Modified-Since", lastModified)
            .GET()
            .build();
        var response = client.send(conditional, HttpResponse.BodyHandlers.ofString());
        assertEquals(304, response.statusCode());
    }

    // Generates a minimal valid 1x1 red pixel PNG
    private static byte[] minimalPng() throws Exception {
        // PNG signature
        byte[] sig = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        // IHDR: 1x1, 8-bit depth, RGB color type (2), no compression/filter/interlace
        byte[] ihdrData = new byte[13];
        putInt(ihdrData, 0, 1); // width
        putInt(ihdrData, 4, 1); // height
        ihdrData[8] = 8;  // bit depth
        ihdrData[9] = 2;  // color type = RGB
        byte[] ihdrChunk = makeChunk("IHDR", ihdrData);

        // IDAT: deflate-compressed scanline: filter_byte=0, R=255, G=0, B=0
        byte[] raw = {0x00, (byte)0xFF, 0x00, 0x00};
        java.util.zip.Deflater def = new java.util.zip.Deflater();
        def.setInput(raw);
        def.finish();
        byte[] compressed = new byte[64];
        int compLen = def.deflate(compressed);
        def.end();
        byte[] idatData = java.util.Arrays.copyOf(compressed, compLen);
        byte[] idatChunk = makeChunk("IDAT", idatData);

        // IEND
        byte[] iendChunk = makeChunk("IEND", new byte[0]);

        // Concatenate everything
        int total = sig.length + ihdrChunk.length + idatChunk.length + iendChunk.length;
        byte[] result = new byte[total];
        int pos = 0;
        System.arraycopy(sig, 0, result, pos, sig.length); pos += sig.length;
        System.arraycopy(ihdrChunk, 0, result, pos, ihdrChunk.length); pos += ihdrChunk.length;
        System.arraycopy(idatChunk, 0, result, pos, idatChunk.length); pos += idatChunk.length;
        System.arraycopy(iendChunk, 0, result, pos, iendChunk.length);
        return result;
    }

    private static byte[] makeChunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] chunk = new byte[4 + 4 + data.length + 4];
        putInt(chunk, 0, data.length);
        System.arraycopy(typeBytes, 0, chunk, 4, 4);
        System.arraycopy(data, 0, chunk, 8, data.length);
        byte[] crcInput = new byte[4 + data.length];
        System.arraycopy(typeBytes, 0, crcInput, 0, 4);
        System.arraycopy(data, 0, crcInput, 4, data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(crcInput);
        putInt(chunk, 8 + data.length, (int) crc.getValue());
        return chunk;
    }

    private static void putInt(byte[] buf, int offset, int value) {
        buf[offset]     = (byte)(value >>> 24);
        buf[offset + 1] = (byte)(value >>> 16);
        buf[offset + 2] = (byte)(value >>>  8);
        buf[offset + 3] = (byte)(value        );
    }
}
