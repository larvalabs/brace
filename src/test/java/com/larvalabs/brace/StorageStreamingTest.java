package com.larvalabs.brace;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Storage} uploads stream: the payload is hashed and sent without being buffered, and the
 * request still carries a real signed digest and a Content-Length.
 *
 * <p>Runs against an in-process stand-in for S3 rather than a real bucket, because what needs
 * pinning is what Brace puts on the wire — the digest header, the framing, and the bytes.
 */
class StorageStreamingTest {

    static HttpServer server;
    static Storage storage;

    static final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    static final AtomicReference<String> receivedSha = new AtomicReference<>();
    static final AtomicReference<String> receivedLength = new AtomicReference<>();
    static final AtomicReference<String> receivedEncoding = new AtomicReference<>();
    static final AtomicReference<String> receivedContentType = new AtomicReference<>();
    static final AtomicReference<String> receivedHost = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            receivedSha.set(exchange.getRequestHeaders().getFirst("x-amz-content-sha256"));
            receivedLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            receivedEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedHost.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        storage = new Storage("AKIATEST", "secret", "test-bucket", "us-east-1",
            "http://127.0.0.1:" + server.getAddress().getPort(), null);
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        receivedBody.set(null);
        receivedSha.set(null);
        receivedLength.set(null);
        receivedEncoding.set(null);
        receivedContentType.set(null);
        receivedHost.set(null);
    }

    @Test
    void putBytesSendsSignedDigestAndExactBody() {
        byte[] payload = ramp(4096);

        storage.put("files/a.bin", payload, "application/octet-stream");

        assertArrayEquals(payload, receivedBody.get());
        assertEquals(sha256Hex(payload), receivedSha.get(),
            "x-amz-content-sha256 must be the real digest — it is part of the signature");
        assertEquals("application/octet-stream", receivedContentType.get());
    }

    @Test
    void putFromPathStreamsWithAContentLength() throws Exception {
        Path file = Files.createTempFile("brace-storage", ".bin");
        byte[] payload = ramp(200_000);
        Files.write(file, payload);

        storage.put("files/b.bin", file, "image/png");

        assertArrayEquals(payload, receivedBody.get());
        assertEquals(sha256Hex(payload), receivedSha.get());
        assertEquals("image/png", receivedContentType.get());
        // The framing matters: ofInputStream alone publishes with an unknown length and the JDK
        // client falls back to chunked, which S3 rejects for a plain PUT.
        assertEquals(String.valueOf(payload.length), receivedLength.get(),
            "a streamed upload must still declare Content-Length");
        assertNull(receivedEncoding.get(), "must not fall back to chunked transfer encoding");
        Files.deleteIfExists(file);
    }

    @Test
    void putFromSpilledUploadStreams(@TempDir Path dir) throws Exception {
        byte[] payload = ramp(150_000);
        Path spilled = dir.resolve("part.bin");
        Files.write(spilled, payload);
        var upload = fileBackedUpload(spilled, "photo.png", "image/png");

        var stored = storage.put("files/c.png", upload);

        assertEquals("files/c.png", stored.key());
        assertArrayEquals(payload, receivedBody.get());
        assertEquals(sha256Hex(payload), receivedSha.get());
        assertEquals(String.valueOf(payload.length), receivedLength.get());
    }

    @Test
    void putGeneratedKeepsTheExtensionAndStreams(@TempDir Path dir) throws Exception {
        byte[] payload = ramp(70_000);
        Path spilled = dir.resolve("part.bin");
        Files.write(spilled, payload);
        var upload = fileBackedUpload(spilled, "holiday snap.JPG", "image/jpeg");

        var stored = storage.putGenerated("photos", upload);

        assertTrue(stored.key().startsWith("photos/"), stored.key());
        assertTrue(stored.key().endsWith(".jpg"), stored.key());
        assertArrayEquals(payload, receivedBody.get());
        assertEquals(sha256Hex(payload), receivedSha.get());
    }

    @Test
    void objectsOverTheSinglePutCeilingAreRejectedByName() {
        // Not sent: the point is that the 5 GiB limit is reported as such rather than surfacing as
        // an opaque error from the endpoint after a long upload.
        var huge = new UploadedFile("huge.bin", "application/octet-stream", new byte[0]) {
            @Override
            public long size() {
                return Storage.MAX_SINGLE_PUT_BYTES + 1;
            }
        };

        var e = assertThrows(IllegalArgumentException.class, () -> storage.put("files/huge.bin", huge));
        assertTrue(e.getMessage().contains("multipart upload API"), e.getMessage());
        assertNull(receivedBody.get(), "nothing should have been sent");
    }

    /**
     * SigV4 signs the {@code host} header, so the value we sign has to be the value that goes on
     * the wire. Brace cannot set {@code Host} itself — the JDK client rejects it as a restricted
     * header — so it relies on the client deriving the same value from the request URI.
     */
    @Test
    void signedHostMatchesTheHostActuallySent() {
        storage.put("files/d.bin", ramp(16), "application/octet-stream");

        String expected = "127.0.0.1:" + server.getAddress().getPort();
        assertEquals(expected, receivedHost.get(),
            "the Host the client sends must equal the host that was signed");
    }

    @Test
    void streamingDigestMatchesTheOneShotDigest() {
        byte[] payload = ramp(300_000);

        assertEquals(Storage.sha256Hex(payload),
            Storage.sha256Hex(() -> new java.io.ByteArrayInputStream(payload)),
            "the streaming and byte[] digests must agree, or signatures break by payload size");
    }

    // --- helpers ---

    /** An UploadedFile whose content lives on disk, as a spilled multipart part would. */
    private static UploadedFile fileBackedUpload(Path path, String filename, String contentType)
            throws Exception {
        return new UploadedFile(filename, contentType, Files.readAllBytes(path)) {
            @Override
            public java.io.InputStream stream() {
                try {
                    return Files.newInputStream(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static byte[] ramp(int n) {
        var b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i % 256);
        return b;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
