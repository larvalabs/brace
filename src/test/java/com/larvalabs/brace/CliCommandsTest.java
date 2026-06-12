package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CliCommandsTest {

    static Brace app;
    static int port;
    static OpsKeys.Keypair keypair;

    @TempDir static Path projectDir;

    @BeforeAll
    static void start() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keysFile = projectDir.resolve("ops-authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");
        Files.writeString(projectDir.resolve("ops-private.key"),
            keypair.privateKey() + "\n" + keypair.publicKey() + "\n");

        app = Brace.app().port(0).ops(keysFile.toString());
        app.get("/boom", req -> { throw new RuntimeException("cli status test error"); });
        app.start();
        port = app.actualPort();

        Files.writeString(projectDir.resolve(".brace"),
            "ops.local.url=http://localhost:" + port + "\n");
        Files.writeString(projectDir.resolve(".brace.local"),
            "ops.key=" + projectDir.resolve("ops-private.key") + "\n");
    }

    @AfterAll
    static void stop() throws Exception { app.stop(); }

    @BeforeEach
    void resetCache() throws Exception { CliAuth.clearCache(projectDir); }

    // --- Task 12: errors ---

    @Test
    void errorsCommandSucceedsWithEmptyList() throws Exception {
        clearInMemoryErrors();
        int code = CliCommands.errors(projectDir, new String[]{"--json"});
        assertEquals(0, code);
    }

    /**
     * /ops/errors serves the in-memory Stats records on no-database apps (F8 fix), so
     * tests asserting the empty-list contract must clear whatever earlier tests provoked.
     */
    private static void clearInMemoryErrors() {
        for (var rec : app.stats().recentErrors()) {
            app.stats().resolveError(rec.id);
        }
    }

    @Test
    void errorsCommandHitsServer() throws Exception {
        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        try {
            CliCommands.errors(projectDir, new String[]{"--json"});
        } finally {
            System.setOut(prev);
        }
        String out = bout.toString().trim();
        assertTrue(out.startsWith("[") || out.startsWith("{"), "got: " + out);
    }

    @Test
    void errorsFullFlagSucceedsWithEmptyList() throws Exception {
        clearInMemoryErrors();
        int code = CliCommands.errors(projectDir, new String[]{"--full", "--json"});
        assertEquals(0, code);
    }

    @Test
    void errorsDetailUnknownIdReturnsNonZero() throws Exception {
        // Unknown id — /ops/errors/{id} 404s (in-memory fallback has no such record),
        // the CLI reports not-found.
        int code = CliCommands.errors(projectDir, new String[]{"999999", "--json"});
        assertEquals(1, code);
    }

    // --- Task 13: logs ---

    @Test
    void logsCommandReturnsTappedEntries() throws Exception {
        LogTap.clear();
        Log.info("test-log-message");

        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        try {
            int code = CliCommands.logs(projectDir, new String[]{"--json"});
            assertEquals(0, code);
        } finally {
            System.setOut(prev);
        }
        assertTrue(bout.toString().contains("test-log-message"));
    }

    @Test
    void logsCommandWithSinceExitsCleanly() throws Exception {
        LogTap.clear();
        Log.info("recent-message");

        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        try {
            int code = CliCommands.logs(projectDir, new String[]{"--json", "--since", "1s"});
            assertEquals(0, code);
        } finally {
            System.setOut(prev);
        }
        assertTrue(bout.toString().contains("recent-message"), bout.toString());
    }

    @Test
    void logsLimitFlagIsPassedThroughToServer() throws Exception {
        LogTap.clear();
        for (int i = 0; i < 5; i++) Log.info("limit-msg-" + i);

        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        try {
            int code = CliCommands.logs(projectDir, new String[]{"--json", "--limit", "2"});
            assertEquals(0, code);
        } finally {
            System.setOut(prev);
        }
        // JSON mode prints one compact line per entry; the server caps at ?limit=2.
        // The capture also picks up the app's own stdout log lines (Log writes JSON to
        // stdout), so count only the CLI-rendered tap entries — those carry an "id".
        long rendered = bout.toString().lines().filter(l -> l.startsWith("{\"id\":")).count();
        assertEquals(2, rendered, bout.toString());
    }

    // --- Task 14: status ---
    // The two status tests are ordered: the healthy check must run before /boom puts an
    // error into the (no-database) app's in-memory recent-error list.

    @Test
    @Order(1)
    void statusCommandReturnsZeroAgainstHealthyApp() throws Exception {
        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        try {
            int code = CliCommands.status(projectDir, new String[]{"--json"});
            assertEquals(0, code);
        } finally {
            System.setOut(prev);
        }
        assertTrue(bout.toString().contains("\"app\""));
    }

    @Test
    @Order(2)
    void statusCommandExitsNonZeroWhenErrorsExist() throws Exception {
        // Pre-H6 /ops/status never emitted errors.count, so `brace status` always exited 0
        // even with unresolved errors. Provoke one and verify the contract works now.
        java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + "/boom")).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());

        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        int code;
        try {
            code = CliCommands.status(projectDir, new String[]{"--json"});
        } finally {
            System.setOut(prev);
        }
        assertEquals(1, code, bout.toString());
        // The redirected System.out also catches the app's own log lines; the status JSON
        // is the last line the command printed.
        var lines = bout.toString().trim().split("\n");
        var root = Json.mapper().readTree(lines[lines.length - 1]);
        assertTrue(root.path("errors").path("count").asLong(0) >= 1, bout.toString());
    }

    // --- Task 15: cache ---

    @Test
    void cacheCommandShowsStats() throws Exception {
        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        try {
            int code = CliCommands.cache(projectDir, new String[]{"--json"});
            assertEquals(0, code);
        } finally {
            System.setOut(prev);
        }
        assertTrue(bout.toString().contains("enabled"));
    }

    @Test
    void cacheClearReturnsZero() throws Exception {
        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        try {
            int code = CliCommands.cacheClear(projectDir, new String[]{});
            assertEquals(0, code);
        } finally {
            System.setOut(prev);
        }
    }

    // --- Task 16: resolve ---

    @Test
    void resolveNonExistentReturnsNonZero() throws Exception {
        int code = CliCommands.resolve(projectDir, new String[]{"999999"});
        assertNotEquals(0, code);
    }
}
