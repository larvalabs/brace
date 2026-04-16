package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliInitTest {

    @TempDir Path tmp;

    @BeforeEach
    void scaffoldProjectDir() throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
    }

    @Test
    void createsMissingBraceFile() throws Exception {
        var result = CliInit.run(tmp);
        assertTrue(Files.exists(tmp.resolve(".brace")));
        assertTrue(Files.readString(tmp.resolve(".brace")).contains("ops.local.url=http://localhost:8080"));
        assertTrue(result.actions().stream().anyMatch(a -> a.contains(".brace")));
    }

    @Test
    void createsMissingBraceLocalFile() throws Exception {
        CliInit.run(tmp);
        assertTrue(Files.exists(tmp.resolve(".brace.local")));
        assertTrue(Files.readString(tmp.resolve(".brace.local")).contains("ops.key=ops-private.key"));
    }

    @Test
    void appendsGitignoreEntries() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "target/\n");
        CliInit.run(tmp);
        String gitignore = Files.readString(tmp.resolve(".gitignore"));
        assertTrue(gitignore.contains(".brace.local"));
        assertTrue(gitignore.contains("ops-private.key"));
        assertTrue(gitignore.contains("target/"));
    }

    @Test
    void createsGitignoreIfMissing() throws Exception {
        CliInit.run(tmp);
        assertTrue(Files.exists(tmp.resolve(".gitignore")));
    }

    @Test
    void doesNotOverwriteExistingBraceFile() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://custom:1234\n");
        CliInit.run(tmp);
        assertTrue(Files.readString(tmp.resolve(".brace")).contains("custom:1234"));
    }

    @Test
    void reportsMissingKeypair() throws Exception {
        var result = CliInit.run(tmp);
        assertTrue(result.actions().stream().anyMatch(a -> a.contains("brace ops keypair")));
        assertFalse(result.ok());
    }

    @Test
    void okWhenKeypairPresent() throws Exception {
        Files.writeString(tmp.resolve("ops-authorized-keys"), "ed25519:abc test\n");
        Files.writeString(tmp.resolve("ops-private.key"), "ed25519:abc\nprivate\n");
        var result = CliInit.run(tmp);
        assertTrue(result.ok(), String.join("; ", result.actions()));
    }

    @Test
    void idempotent() throws Exception {
        Files.writeString(tmp.resolve("ops-authorized-keys"), "ed25519:abc test\n");
        Files.writeString(tmp.resolve("ops-private.key"), "ed25519:abc\nprivate\n");
        CliInit.run(tmp);
        var second = CliInit.run(tmp);
        assertTrue(second.ok());
    }
}
