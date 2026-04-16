package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliOpsTest {

    @TempDir Path projectDir;

    @BeforeEach
    void scaffoldProject() throws Exception {
        Files.createDirectories(projectDir.resolve("src/main/java"));
    }

    @Test
    void keypairCreatesAuthorizedKeysFile() throws Exception {
        int code = CliOps.keypair(projectDir, new String[]{"--label", "ci"});
        assertEquals(0, code);
        assertTrue(Files.exists(projectDir.resolve("ops-authorized-keys")));
        String content = Files.readString(projectDir.resolve("ops-authorized-keys"));
        assertTrue(content.contains("ci"));
    }

    @Test
    void keypairAppendsWhenFileExists() throws Exception {
        Files.writeString(projectDir.resolve("ops-authorized-keys"), "# header\n");
        CliOps.keypair(projectDir, new String[]{"--label", "k1"});
        CliOps.keypair(projectDir, new String[]{"--label", "k2"});
        var lines = Files.readAllLines(projectDir.resolve("ops-authorized-keys"));
        long keyLines = lines.stream().filter(l -> l.contains("ed25519:")).count();
        assertEquals(2, keyLines);
    }

    @Test
    void dashboardFailsWithoutKey() throws Exception {
        Files.writeString(projectDir.resolve(".brace"), "ops.local.url=http://localhost:8080\n");
        int code = CliOps.dashboard(projectDir, new String[]{});
        assertNotEquals(0, code);
    }
}
