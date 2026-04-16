package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

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
        int code = CliCommands.errors(projectDir, new String[]{"--json"});
        assertEquals(0, code);
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

    // --- Task 14: status ---

    @Test
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
