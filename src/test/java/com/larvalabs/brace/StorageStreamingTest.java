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
    static final AtomicReference<String> receivedDisposition = new AtomicReference<>();
    /** Whether the request's SigV4 signature verifies against the headers actually received. */
    static final AtomicReference<Boolean> signatureValid = new AtomicReference<>();
    static final AtomicReference<String> receivedSignedHeaders = new AtomicReference<>();

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
            receivedDisposition.set(exchange.getRequestHeaders().getFirst("Content-Disposition"));
            verifySignature(exchange);
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
        receivedDisposition.set(null);
        signatureValid.set(null);
        receivedSignedHeaders.set(null);
    }

    @Test
    void putBytesSendsSignedDigestAndExactBody() {
        byte[] payload = ramp(4096);

        storage.put("files/a.bin", payload, "application/octet-stream");

        assertArrayEquals(payload, receivedBody.get());
        assertEquals(sha256Hex(payload), receivedSha.get(),
            "x-amz-content-sha256 must be the real digest — it is part of the signature");
        assertEquals("application/octet-stream", receivedContentType.get());
        assertTrue(signatureValid.get(), "baseline: the test's SigV4 check accepts a plain put");
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

    // --- M7: the stored type comes from the key, never from the uploader ---

    @Test
    void putIgnoresAClientClaimedHtmlType() {
        var upload = new UploadedFile("evil.html", "text/html", SCRIPT);

        storage.put("files/evil.html", upload);

        assertEquals("application/octet-stream", receivedContentType.get());
        assertEquals("attachment", receivedDisposition.get());
        assertTrue(signatureValid.get(), "the signature must cover the headers actually sent");
        assertTrue(receivedSignedHeaders.get().contains("content-disposition"), receivedSignedHeaders.get());
    }

    @Test
    void putTypesARasterImageByItsKeyAndKeepsItInline() {
        // The client claims text/html for a .png key: the key wins.
        storage.put("files/a.png", new UploadedFile("a.png", "text/html", SCRIPT));

        assertEquals("image/png", receivedContentType.get());
        assertNull(receivedDisposition.get(), "raster images stay inline");
        assertTrue(signatureValid.get());
    }

    @Test
    void putTypesSvgAndExtensionlessKeysAsOctetStream() {
        storage.put("files/a.svg", new UploadedFile("a.svg", "image/svg+xml", SCRIPT));
        assertEquals("application/octet-stream", receivedContentType.get());
        assertEquals("attachment", receivedDisposition.get());

        storage.put("files/avatar", new UploadedFile("a.png", "image/png", SCRIPT));
        assertEquals("application/octet-stream", receivedContentType.get());
    }

    @Test
    void putDocumentsKeepTheirTypeAsAttachments() {
        storage.put("files/a.pdf", new UploadedFile("a.pdf", "text/html", SCRIPT));
        assertEquals("application/pdf", receivedContentType.get());
        assertEquals("attachment", receivedDisposition.get());
        assertTrue(signatureValid.get());
    }

    @Test
    void putGeneratedNeutralizesTheKeyAndTheType() {
        var stored = storage.putGenerated("u", new UploadedFile("x.SVG", "image/svg+xml", SCRIPT));

        assertTrue(stored.key().endsWith(".bin"), stored.key());
        assertEquals("application/octet-stream", receivedContentType.get());
        assertEquals("attachment", receivedDisposition.get());
    }

    @Test
    void anExplicitTypeIsStoredAsGiven() {
        storage.put("files/logo.svg", new UploadedFile("logo.svg", "text/html", SCRIPT), "image/svg+xml");

        assertEquals("image/svg+xml", receivedContentType.get());
        assertNull(receivedDisposition.get());
        assertTrue(signatureValid.get());
        assertFalse(receivedSignedHeaders.get().contains("content-disposition"));
    }

    // --- helpers ---

    static final byte[] SCRIPT = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

    /**
     * Recomputes the SigV4 signature from the request as received, the way S3 does, so a header
     * that is signed with one value and sent with another (or sent but not signed) fails here.
     */
    private static void verifySignature(com.sun.net.httpserver.HttpExchange exchange) {
        var h = exchange.getRequestHeaders();
        String auth = h.getFirst("Authorization");
        if (auth == null) return;
        String signedHeaders = auth.replaceFirst(".*SignedHeaders=([^,]+),.*", "$1");
        String signature = auth.replaceFirst(".*Signature=([0-9a-f]+).*", "$1");
        receivedSignedHeaders.set(signedHeaders);
        var canonicalHeaders = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            canonicalHeaders.append(name).append(':').append(h.getFirst(name).strip()).append('\n');
        }
        String canonicalRequest = exchange.getRequestMethod() + "\n"
            + exchange.getRequestURI().getRawPath() + "\n\n"
            + canonicalHeaders + "\n" + signedHeaders + "\n" + h.getFirst("x-amz-content-sha256");
        String amzDate = h.getFirst("x-amz-date");
        String dateStamp = amzDate.substring(0, 8);
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + dateStamp + "/us-east-1/s3/aws4_request\n"
            + Storage.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String expected = Storage.bytesToHex(Storage.hmacSha256(
            Storage.getSignatureKey("secret", dateStamp, "us-east-1", "s3"), stringToSign));
        signatureValid.set(expected.equals(signature));
    }

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
