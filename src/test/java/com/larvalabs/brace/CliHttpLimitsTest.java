package com.larvalabs.brace;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The ops CLI against a server that never answers, drips its response or never stops sending
 * (L11 final review): every request must end within its deadline with a non-zero exit and a
 * one-line error, and a response body is capped.
 */
class CliHttpLimitsTest {

    static HttpServer server;
    /** Per-path behaviour: "silent", "drip", "endless"; anything else answers normally. */
    static final Map<String, String> modes = new ConcurrentHashMap<>();
    @TempDir static Path projectDir;

    @BeforeAll
    static void start() throws Exception {
        var kp = OpsKeys.generateKeypair();
        Files.writeString(projectDir.resolve("ops-private.key"), kp.privateKey() + "\n" + kp.publicKey() + "\n");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String mode = modes.getOrDefault(path, "");
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                switch (mode) {
                    case "silent" -> Thread.sleep(20_000);
                    case "drip" -> {
                        exchange.sendResponseHeaders(200, 0);
                        var out = exchange.getResponseBody();
                        for (int i = 0; i < 40; i++) {
                            out.write('x');
                            out.flush();
                            Thread.sleep(200);
                        }
                    }
                    case "endless" -> {
                        exchange.sendResponseHeaders(200, 0);
                        var out = exchange.getResponseBody();
                        byte[] chunk = new byte[16 * 1024];
                        java.util.Arrays.fill(chunk, (byte) 'x');
                        for (int i = 0; i < 20_000; i++) out.write(chunk);
                    }
                    // Until the client hangs up (the write then throws).
                    case "drip-forever" -> {
                        exchange.getResponseHeaders().set("Content-Type", "text/html");
                        exchange.sendResponseHeaders(200, 0);
                        var out = exchange.getResponseBody();
                        while (true) {
                            out.write('x');
                            out.flush();
                            Thread.sleep(200);
                        }
                    }
                    case "endless-forever" -> {
                        exchange.getResponseHeaders().set("Content-Type", "text/html");
                        exchange.sendResponseHeaders(200, 0);
                        var out = exchange.getResponseBody();
                        byte[] chunk = new byte[16 * 1024];
                        java.util.Arrays.fill(chunk, (byte) 'x');
                        while (true) out.write(chunk);
                    }
                    default -> {
                        String body = path.equals("/ops/auth")
                            ? "{\"token\":\"tok\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}"
                            : "{\"app\":{}}";
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                }
            } catch (Exception ignored) {
                // the client hung up: expected
            } finally {
                exchange.close();
            }
        });
        server.start();
        Files.writeString(projectDir.resolve(".brace"),
            "ops.local.url=http://127.0.0.1:" + server.getAddress().getPort() + "\n");
        Files.writeString(projectDir.resolve(".brace.local"),
            "ops.key=" + projectDir.resolve("ops-private.key") + "\n");
    }

    @AfterAll
    static void stop() throws Exception {
        CliAuth.clearCache(projectDir);
        server.stop(0);
    }

    @BeforeEach
    void reset() throws Exception {
        modes.clear();
        CliAuth.clearCache(projectDir);
        CliHttp.requestTimeout = Duration.ofSeconds(1);
        CliHttp.maxBodyBytes = 64 * 1024;
    }

    @AfterEach
    void restore() {
        CliHttp.requestTimeout = Duration.ofSeconds(30);
        CliHttp.maxBodyBytes = 16L * 1024 * 1024;
    }

    private record Run(int code, String out, String err) {}

    private static Run status() throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> CliCommands.status(projectDir, new String[]{"--json"}));
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void assertFailedFast(Run r) {
        assertNotEquals(0, r.code(), r.out() + r.err());
        assertEquals(1, r.err().strip().lines().count(), "one line: " + r.err());
        assertTrue(r.err().length() < 1000, r.err());
    }

    @Test
    void anAuthEndpointThatNeverAnswersTimesOut() throws Exception {
        modes.put("/ops/auth", "silent");
        assertFailedFast(status());
    }

    @Test
    void aDataEndpointThatNeverAnswersTimesOut() throws Exception {
        modes.put("/ops/status", "silent");
        assertFailedFast(status());
    }

    @Test
    void aBodyDrippedAByteAtATimeIsCutOffAtTheDeadline() throws Exception {
        modes.put("/ops/status", "drip");
        var r = status();
        assertFailedFast(r);
        assertTrue(r.err().contains("not complete"), r.err());
    }

    @Test
    void anAuthBodyDrippedAByteAtATimeIsCutOffAtTheDeadline() throws Exception {
        modes.put("/ops/auth", "drip");
        assertFailedFast(status());
    }

    @Test
    void anEndlessBodyIsCappedNotBuffered() throws Exception {
        modes.put("/ops/status", "endless");
        var r = status();
        assertFailedFast(r);
        assertTrue(r.err().contains("larger than"), r.err());
    }

    @Test
    void anEndlessAuthBodyIsCapped() throws Exception {
        modes.put("/ops/auth", "endless");
        assertFailedFast(status());
    }

    @Test
    void aNormalResponseStillWorks() throws Exception {
        var r = status();
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("\"app\""), r.out());
    }

    @Test
    void aTrailingSlashInTheOpsUrlStillReachesTheServer() throws Exception {
        Files.writeString(projectDir.resolve(".brace"),
            "ops.local.url=http://127.0.0.1:" + server.getAddress().getPort() + "/\n");
        try {
            var r = status();
            assertEquals(0, r.code(), r.err());
        } finally {
            Files.writeString(projectDir.resolve(".brace"),
                "ops.local.url=http://127.0.0.1:" + server.getAddress().getPort() + "\n");
        }
    }

    // ---------- brace check's unauthenticated GET / (verification pass) ----------

    @Test
    void theRootProbeIsBoundedAgainstADrippingRoot() {
        // The probe had its own client with a discarding body: its 10 s timeout covered the headers
        // only, so a root that drips a byte every 200 ms held `brace check` indefinitely.
        modes.put("/", "drip-forever");
        var probe = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> CliCheck.probeRoot("http://127.0.0.1:" + server.getAddress().getPort()));
        assertNull(probe, "a probe that cannot finish is skipped, like an unreachable root");
    }

    @Test
    void theRootProbeIsBoundedAgainstAnEndlessRoot() {
        modes.put("/", "endless-forever");
        var probe = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> CliCheck.probeRoot("http://127.0.0.1:" + server.getAddress().getPort()));
        assertNull(probe);
    }

    // ---------- toolchain downloads: idle timeout, large ceiling ----------

    @Test
    void aDownloadDrippedFasterThanTheIdleTimeoutStopsAtTheTotalDeadline() {
        // Verification pass: a byte every 100 ms never trips a 60 s idle timeout, so without a
        // total deadline it held the launcher indefinitely.
        var drip = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException {
                try { Thread.sleep(100); } catch (InterruptedException e) { throw new java.io.InterruptedIOException(); }
                return 'x';
            }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                b[off] = (byte) read();
                return 1;
            }
        };
        var e = assertThrows(java.io.IOException.class, () -> assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> Toolchains.readWithIdleTimeout(drip, Duration.ofSeconds(5), Duration.ofSeconds(1), 1L << 30)));
        assertTrue(e.getMessage().contains("did not finish within"), e.getMessage());
        assertEquals(Duration.ofMinutes(30), Toolchains.DOWNLOAD_TOTAL_TIMEOUT);
    }

    @Test
    void aStalledDownloadIsAbandonedAfterTheIdleTimeout() throws Exception {
        var never = new java.io.PipedInputStream(new java.io.PipedOutputStream());   // nothing is ever written
        var e = assertThrows(java.io.IOException.class, () -> assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> Toolchains.readWithIdleTimeout(never, Duration.ofMillis(500), 1L << 30)));
        assertTrue(e.getMessage().contains("stalled"), e.getMessage());
    }

    @Test
    void aLargeSlowButSteadyDownloadIsKept() throws Exception {
        byte[] data = new byte[3 * 1024 * 1024];
        assertEquals(data.length, Toolchains.readWithIdleTimeout(
            new java.io.ByteArrayInputStream(data), Duration.ofSeconds(5), 1L << 30).length);
    }

    @Test
    void anEndlessDownloadStopsAtTheCeiling() {
        var endless = new java.io.InputStream() {
            @Override public int read() { return 'x'; }
            @Override public int read(byte[] b, int off, int len) { return len; }
        };
        assertThrows(java.io.IOException.class, () -> assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> Toolchains.readWithIdleTimeout(endless, Duration.ofSeconds(5), 1L << 20)));
    }

    // ---------- the key path ----------

    private static Run statusWithKey(Path dir, Path key) throws Exception {
        Files.writeString(dir.resolve(".brace"),
            "ops.local.url=http://127.0.0.1:" + server.getAddress().getPort() + "\n");
        Files.writeString(dir.resolve(".brace.local"), "ops.key=" + key + "\n");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> CliCommands.status(dir, new String[]{"--json"}));
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
            CliAuth.clearCache(dir);
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aFifoAsTheKeyFileIsRefusedNotBlockedOn(@TempDir Path dir) throws Exception {
        Path fifo = dir.resolve("key.fifo");
        Process p;
        try {
            p = new ProcessBuilder("mkfifo", fifo.toString()).start();
            Assumptions.assumeTrue(p.waitFor() == 0, "mkfifo failed");
        } catch (java.io.IOException noMkfifo) {
            Assumptions.assumeTrue(false, "mkfifo unavailable");
            return;
        }
        var r = statusWithKey(dir, fifo);
        assertFailedFast(r);
        assertTrue(r.err().contains("regular file"), r.err());
    }

    @Test
    void anOversizedKeyFileIsRefused(@TempDir Path dir) throws Exception {
        Path big = dir.resolve("big.key");
        Files.write(big, new byte[200 * 1024]);
        var r = statusWithKey(dir, big);
        assertFailedFast(r);
        assertTrue(r.err().contains("too large"), r.err());
    }

    @Test
    void aSymlinkedKeyFileIsStillAccepted(@TempDir Path dir) throws Exception {
        Path link = dir.resolve("link.key");
        try {
            Files.createSymbolicLink(link, projectDir.resolve("ops-private.key"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.assumeTrue(false, "no symlinks");
        }
        var r = statusWithKey(dir, link);
        assertEquals(0, r.code(), r.err());
    }
}
