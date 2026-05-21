package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the large non-multipart request body hang.
 *
 * <p>{@code BraceHandler.handle()} reads non-multipart bodies with the blocking
 * {@code Content.Source.asString}. When the body arrives in more than one chunk
 * that read parks the handler on a {@code demand()} until the next chunk shows
 * up. That only works if handlers run on real virtual threads — parking one
 * frees its carrier to read the next chunk. Before the fix, {@code Brace.start()}
 * passed {@code Runnable::run} to {@code setVirtualThreadsExecutor}, which made
 * Jetty believe virtual threads were enabled while running handlers inline on
 * the producer thread; a multi-chunk body read then deadlocked the producer
 * until the 30s idle timeout fired and the request failed with a 500.
 *
 * <p>The bug only manifests when the body genuinely arrives split across
 * separate network reads. A normal client over loopback hands the whole body to
 * the socket buffer at once, so the handler never has to park — which is why
 * this test writes the request body in two halves over a raw socket with a
 * deliberate gap between them. On the buggy code the second half is never read;
 * the 8s socket timeout here turns that 30s stall into a fast failure.
 */
class RequestBodyTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0).banner(false);
        // Echo the received body straight back so the test can assert the
        // handler saw every byte, not just a 200 status.
        app.post("/echo", req -> Result.text(req.body()));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    /**
     * POSTs a body of {@code size} bytes in two halves with a gap between them,
     * forcing the handler to park mid-read, and asserts it round-trips intact.
     */
    private void assertRoundTripsChunked(int size) throws Exception {
        byte[] body = buildBody(size);
        int firstHalf = size / 2;

        String headers = "POST /echo HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Type: text/plain\r\n"
            + "Content-Length: " + size + "\r\n"
            + "Connection: close\r\n"
            + "\r\n";

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 8_000);
            socket.setSoTimeout(8_000); // bug stalls ~30s — fail fast instead

            long startNanos = System.nanoTime();
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(body, 0, firstHalf);
            out.flush();
            // Gap: the handler does its first read, then parks demanding the rest.
            Thread.sleep(250);
            out.write(body, firstHalf, size - firstHalf);
            out.flush();

            String raw = readAll(socket.getInputStream());
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            int headerEnd = raw.indexOf("\r\n\r\n");
            assertTrue(headerEnd >= 0, "no complete response received for " + size + "-byte body");
            int status = Integer.parseInt(raw.substring(9, 12));
            String responseBody = raw.substring(headerEnd + 4);

            assertEquals(200, status, size + "-byte chunked body should not 500");
            assertEquals(size, responseBody.length(), size + "-byte body should round-trip fully");
            assertEquals(new String(body, StandardCharsets.US_ASCII), responseBody,
                size + "-byte body should round-trip intact");
            assertTrue(elapsedMs < 5_000,
                size + "-byte body completed in " + elapsedMs + "ms (the bug stalls ~30s)");
        }
    }

    private static String readAll(InputStream in) throws Exception {
        var buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    /** A printable, non-repeating-enough pattern so truncation shows up as a mismatch. */
    private static byte[] buildBody(int size) {
        byte[] body = new byte[size];
        for (int i = 0; i < size; i++) {
            body[i] = (byte) ('!' + (i % 90));
        }
        return body;
    }

    @Test
    void smallBodyRoundTrips() throws Exception {
        assertRoundTripsChunked(1024); // straddles a read even when small
    }

    @Test
    void bodyAtChunkBoundaryRoundTrips() throws Exception {
        assertRoundTripsChunked(64 * 1024); // ~the empirical failure threshold
    }

    @Test
    void mediumBodyRoundTrips() throws Exception {
        assertRoundTripsChunked(256 * 1024);
    }

    @Test
    void largeBodyRoundTrips() throws Exception {
        assertRoundTripsChunked(1024 * 1024);
    }
}
