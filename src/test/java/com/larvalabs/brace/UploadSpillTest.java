package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uploads above the memory threshold spill to disk instead of the heap, and the temp files they
 * spill to are released when the request ends — on every exit path, not just the happy one.
 *
 * <p>The threshold here is deliberately tiny (1 KB) so an ordinary test-sized body exercises the
 * file-backed path. {@link FileUploadTest} covers the in-memory path with the default threshold and
 * is the compatibility oracle: it must keep passing untouched.
 */
class UploadSpillTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;
    static Path tempDir;

    /** Temp-dir contents observed from *inside* the handler, i.e. while the upload is live. */
    static final AtomicReference<Integer> filesDuringRequest = new AtomicReference<>();
    static final AtomicReference<Path> savedTo = new AtomicReference<>();

    static final int BIG = 64 * 1024;

    @BeforeAll
    static void startApp() throws Exception {
        tempDir = Files.createTempDirectory("brace-spill-test");
        app = Brace.app().port(0)
            .uploadMemoryThreshold(1024)
            .uploadTempDir(tempDir);

        app.post("/echo-size", req -> {
            var file = req.file("file");
            filesDuringRequest.set(countFiles(tempDir));
            return Result.text(file.filename() + "|" + file.size());
        });

        app.post("/read-twice", req -> {
            var file = req.file("file");
            try {
                // stream() must be repeatable — Phase 2's hash-then-send upload depends on it.
                var first = drain(file.stream());
                var second = drain(file.stream());
                return Result.text(first.length + "|" + second.length + "|"
                    + java.util.Arrays.equals(first, second));
            } catch (Exception e) {
                return Result.text("THREW: " + e);
            }
        });

        app.post("/bytes", req -> {
            var file = req.file("file");
            byte[] b = file.bytes();
            return Result.text(b.length + "|" + (b[0] & 0xFF) + "|" + (b[b.length - 1] & 0xFF));
        });

        app.post("/save", req -> {
            var file = req.file("file");
            try {
                Path dest = Files.createTempDirectory("brace-save").resolve("out.bin");
                file.saveTo(dest);
                savedTo.set(dest);
                filesDuringRequest.set(countFiles(tempDir));
                return Result.text(Files.size(dest) + "");
            } catch (Exception e) {
                return Result.text("THREW: " + e);
            }
        });

        app.post("/big-field", req -> Result.text("" + req.formParam("notes").length()));

        app.post("/boom", req -> {
            req.file("file"); // force the parse, then fail
            filesDuringRequest.set(countFiles(tempDir));
            throw new RuntimeException("handler exploded");
        });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @BeforeEach
    void reset() {
        filesDuringRequest.set(null);
        savedTo.set(null);
    }

    // --- spilling ---

    @Test
    void largeUploadSpillsToDiskAndIsCleanedUp() throws Exception {
        var resp = postFile("/echo-size", "big.bin", bytes(BIG));

        assertEquals(200, resp.statusCode());
        assertEquals("big.bin|" + BIG, resp.body());
        assertEquals(1, filesDuringRequest.get(),
            "a part over the threshold should be backed by a temp file during the request");
        awaitNoFiles("temp file should be released when the request ends");
    }

    @Test
    void smallUploadStaysInMemory() throws Exception {
        var resp = postFile("/echo-size", "small.bin", bytes(64));

        assertEquals(200, resp.statusCode());
        assertEquals("small.bin|64", resp.body());
        assertEquals(0, filesDuringRequest.get(),
            "a part under the threshold should never touch the disk");
    }

    @Test
    void spilledUploadReadsBackIdentically() throws Exception {
        var resp = postFile("/bytes", "big.bin", bytes(BIG));

        assertEquals(200, resp.statusCode());
        // bytes(BIG) is a repeating 0..255 ramp, so the first and last byte pin the content.
        assertEquals(BIG + "|0|" + ((BIG - 1) % 256), resp.body());
    }

    @Test
    void spilledUploadStreamIsRepeatable() throws Exception {
        var resp = postFile("/read-twice", "big.bin", bytes(BIG));

        assertEquals(200, resp.statusCode());
        assertEquals(BIG + "|" + BIG + "|true", resp.body());
    }

    @Test
    void saveToMovesTheSpilledFile() throws Exception {
        var resp = postFile("/save", "big.bin", bytes(BIG));

        assertEquals(200, resp.statusCode());
        assertEquals("" + BIG, resp.body());
        assertEquals(0, filesDuringRequest.get(),
            "saveTo should move the temp file out, not copy it");
        assertTrue(Files.exists(savedTo.get()));
        assertEquals(BIG, Files.size(savedTo.get()));
        awaitNoFiles("nothing should remain after a saveTo move");
        Files.deleteIfExists(savedTo.get());
    }

    /**
     * Jetty treats maxMemoryFileSize as a hard limit for parts with no filename, failing the
     * request rather than spilling — unless useFilesForPartsWithoutFileName is set. Without that,
     * a form field over the threshold would 500.
     */
    @Test
    void largeNonFileFieldStillWorks() throws Exception {
        String notes = "x".repeat(BIG);
        var boundary = "----SpillBoundary";
        var body = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"notes\"\r\n\r\n"
            + notes + "\r\n"
            + "--" + boundary + "--\r\n";

        var resp = post("/big-field", body.getBytes(StandardCharsets.UTF_8), boundary);

        assertEquals(200, resp.statusCode(), "a form field over the threshold must not fail");
        assertEquals("" + BIG, resp.body());
        assertEquals(0, countFiles(tempDir));
    }

    // --- cleanup on non-happy paths ---

    @Test
    void tempFileIsReleasedWhenTheHandlerThrows() throws Exception {
        var resp = postFile("/boom", "big.bin", bytes(BIG));

        assertEquals(500, resp.statusCode());
        assertEquals(1, filesDuringRequest.get());
        awaitNoFiles("a thrown 500 must still release the temp file");
    }

    @Test
    void oversizedMultipartReturns413AndLeavesNoSpill() throws Exception {
        var small = Brace.app().port(0)
            .maxUploadSize(32 * 1024)
            .uploadMemoryThreshold(1024)
            .uploadTempDir(tempDir);
        small.post("/upload", req -> Result.text("ok"));
        small.start();
        try {
            var resp = postFile(small.actualPort(), "/upload", "big.bin", bytes(BIG));
            // Was a 500 before this change: the Content-Length fast-reject sat below the multipart
            // branch, so oversized uploads hit Jetty's own cap and surfaced as a framework error.
            assertEquals(413, resp.statusCode(), "an oversized upload is a client error, not a 500");
            awaitNoFiles("a 413 must not leave the partial spill behind");
        } finally {
            small.stop();
        }
    }

    /**
     * The chunked variant: no Content-Length, so the fast-reject cannot see the size up front and
     * the 413 has to come from mapping Jetty's own cap violation. This is the case that pins the
     * message matching in {@code isSizeViolation} — if a Jetty upgrade renames those messages, this
     * test fails rather than silently reverting to 500s in the error store.
     */
    @Test
    void oversizedChunkedMultipartReturns413() throws Exception {
        var small = Brace.app().port(0)
            .maxUploadSize(32 * 1024)
            .uploadMemoryThreshold(1024)
            .uploadTempDir(tempDir);
        small.post("/upload", req -> Result.text("ok"));
        small.start();
        try {
            var boundary = "----SpillBoundary";
            var out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes(BIG));
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] body = out.toByteArray();

            // ofInputStream publishes with no known length, so the JDK client sends it chunked.
            var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + small.actualPort() + "/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(body)))
                .build();
            var resp = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(413, resp.statusCode(),
                "a chunked oversized upload must be a 413, got: " + resp.body());
            assertEquals(0, countFiles(tempDir));
        } finally {
            small.stop();
        }
    }

    @Test
    void tempFileIsReleasedWhenNoRouteMatches() throws Exception {
        var resp = postFile("/no-such-route", "big.bin", bytes(BIG));

        assertEquals(404, resp.statusCode());
        awaitNoFiles("a 404 must still release the temp file");
    }

    // --- directory hygiene ---

    @Test
    void tempDirIsOwnerOnly() throws Exception {
        Path fresh = Files.createTempDirectory("brace-perm-test").resolve("uploads");
        BraceHandler.prepareUploadTempDir(fresh);

        assertTrue(Files.isDirectory(fresh));
        var perms = Files.getPosixFilePermissions(fresh);
        assertEquals("rwx------",
            java.nio.file.attribute.PosixFilePermissions.toString(perms),
            "upload spill directory holds untrusted content and must not be group/world readable");
    }

    @Test
    void sweepDeletesOrphansButSparesLiveFiles() throws Exception {
        Path dir = Files.createTempDirectory("brace-sweep-test");
        Path orphan = Files.writeString(dir.resolve("MultiPart-old"), "stale");
        Path live = Files.writeString(dir.resolve("MultiPart-new"), "fresh");
        Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(
            System.currentTimeMillis() - BraceHandler.UPLOAD_ORPHAN_AGE_MS - 60_000));

        BraceHandler.sweepOrphanedUploads(dir);

        assertFalse(Files.exists(orphan), "a spill file older than the orphan age is from a dead process");
        assertTrue(Files.exists(live), "a recent spill file may belong to an in-flight request");
    }

    // --- helpers ---

    /**
     * Cleanup runs in handle()'s finally, which is not ordered against the client receiving the
     * response — the response write is asynchronous, so a test that checks the directory the
     * instant the body arrives is racing the server. Poll instead of sleeping a fixed amount.
     */
    private static void awaitNoFiles(String message) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (countFiles(tempDir) == 0) return;
            Thread.sleep(10);
        }
        assertEquals(0, countFiles(tempDir), message);
    }

    private static byte[] bytes(int n) {
        var b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i % 256);
        return b;
    }

    private static byte[] drain(java.io.InputStream in) throws Exception {
        try (in) {
            return in.readAllBytes();
        }
    }

    private static int countFiles(Path dir) {
        try (var s = Files.list(dir)) {
            return (int) s.count();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpResponse<String> postFile(String path, String filename, byte[] content)
            throws Exception {
        return postFile(port, path, filename, content);
    }

    private static HttpResponse<String> postFile(int p, String path, String filename, byte[] content)
            throws Exception {
        var boundary = "----SpillBoundary";
        var out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return post(p, path, out.toByteArray(), boundary);
    }

    private static HttpResponse<String> post(String path, byte[] body, String boundary)
            throws Exception {
        return post(port, path, body, boundary);
    }

    private static HttpResponse<String> post(int p, String path, byte[] body, String boundary)
            throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + p + path))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
