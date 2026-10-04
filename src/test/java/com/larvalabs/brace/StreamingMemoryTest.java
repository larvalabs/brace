package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The test that actually proves the thesis: move a body several times larger than the heap, in both
 * directions, and finish with a 200.
 *
 * <p>Runs in its own forked JVM with a deliberately small {@code -Xmx} — see the {@code
 * memory-tests} surefire execution in {@code pom.xml}. Every other test in the suite would pass
 * just as happily against the old fully-buffered implementation; this one would not. Before the
 * streaming work it fails with an OutOfMemoryError rather than an assertion.
 *
 * <p>Nothing here may materialize the payload, on either side — the client shares the constrained
 * heap with the server, so the request body is generated as it is sent and the response body is
 * counted as it arrives.
 */
class StreamingMemoryTest {

    /** Comfortably larger than the forked heap; see pom.xml. */
    static final long PAYLOAD_BYTES = 256L * 1024 * 1024;

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;
    static Path tempDir;
    static Path savedFile;

    static final AtomicLong reportedSize = new AtomicLong(-1);
    static final AtomicReference<String> failure = new AtomicReference<>();

    @BeforeAll
    static void startApp() throws Exception {
        tempDir = Files.createTempDirectory("brace-memory-test");
        savedFile = tempDir.resolve("saved.bin");

        app = Brace.app().port(0)
            .maxUploadSize(PAYLOAD_BYTES * 2)
            .uploadMemoryThreshold(64 * 1024)
            .uploadTempDir(tempDir.resolve("spill"));

        app.post("/upload", req -> {
            try {
                var file = req.file("file");
                reportedSize.set(file.size());
                file.saveTo(savedFile);
                return Result.text("saved " + Files.size(savedFile));
            } catch (Throwable t) {
                failure.set(t.toString());
                return Result.error(500, t.toString());
            }
        }).csrf(false);

        app.get("/download", req -> Result.file(savedFile, "application/octet-stream"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
        deleteRecursively(tempDir);
    }

    @Test
    void movesAPayloadLargerThanTheHeapInBothDirections() throws Exception {
        long maxHeap = Runtime.getRuntime().maxMemory();
        assertTrue(maxHeap < PAYLOAD_BYTES,
            "this test is meaningless unless the payload exceeds the heap; -Xmx is "
                + (maxHeap / (1024 * 1024)) + "MB but the payload is "
                + (PAYLOAD_BYTES / (1024 * 1024)) + "MB. Check the memory-tests surefire execution.");

        // --- upload ---
        var boundary = "----MemoryBoundary";
        var preamble = ("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"huge.bin\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/upload"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            // Content-Length is a restricted header for the JDK client; fromPublisher's declared
            // length is what sets it.
            .POST(HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new GeneratedStream(preamble, PAYLOAD_BYTES, epilogue)),
                preamble.length + PAYLOAD_BYTES + epilogue.length))
            .build();

        var uploadResp = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertNull(failure.get(), "handler failed: " + failure.get());
        assertEquals(200, uploadResp.statusCode(), uploadResp.body());
        assertEquals("saved " + PAYLOAD_BYTES, uploadResp.body());
        assertEquals(PAYLOAD_BYTES, reportedSize.get(),
            "size() must be truthful for a spilled part without materializing it");

        // --- download ---
        var downloadResp = client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/download")).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, downloadResp.statusCode());
        assertEquals(String.valueOf(PAYLOAD_BYTES),
            downloadResp.headers().firstValue("Content-Length").orElse(null));

        long received = 0;
        long checksum = 0;
        var buf = new byte[64 * 1024];
        try (InputStream in = downloadResp.body()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) checksum += buf[i] & 0xFF;
                received += n;
            }
        }
        assertEquals(PAYLOAD_BYTES, received, "the whole file must come back");
        assertEquals(expectedChecksum(PAYLOAD_BYTES), checksum, "and come back unaltered");
    }

    @Test
    void rangeOverAHugeFileReadsOnlyItsSlice() throws Exception {
        // Depends on the upload above having run; ordered by name is not guaranteed, so do it here.
        if (!Files.exists(savedFile)) movesAPayloadLargerThanTheHeapInBothDirections();

        var resp = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/download"))
            .header("Range", "bytes=" + (PAYLOAD_BYTES - 1000) + "-")
            .GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(206, resp.statusCode());
        assertEquals(1000, resp.body().length,
            "a range request must read its slice, not the whole file, whatever the file's size");
    }

    // --- helpers ---

    /** The payload, generated as it is read. Byte at index i is {@code i % 251}. */
    private static final class GeneratedStream extends InputStream {
        private final byte[] preamble;
        private final long payload;
        private final byte[] epilogue;
        private long position;

        GeneratedStream(byte[] preamble, long payload, byte[] epilogue) {
            this.preamble = preamble;
            this.payload = payload;
            this.epilogue = epilogue;
        }

        @Override
        public int read() {
            var one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            long total = preamble.length + payload + epilogue.length;
            if (position >= total) return -1;
            int written = 0;
            while (written < len && position < total) {
                if (position < preamble.length) {
                    b[off + written] = preamble[(int) position];
                } else if (position < preamble.length + payload) {
                    b[off + written] = (byte) ((position - preamble.length) % 251);
                } else {
                    b[off + written] = epilogue[(int) (position - preamble.length - payload)];
                }
                position++;
                written++;
            }
            return written;
        }
    }

    private static long expectedChecksum(long length) {
        long sum = 0;
        for (long i = 0; i < length; i++) sum += (byte) (i % 251) & 0xFF;
        return sum;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
