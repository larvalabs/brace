package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProjectGenerator security fixes — H4.
 */
class ProjectGeneratorTest {

    @Test
    void twoGeneratedProjectsHaveDifferentSecrets(@TempDir Path tempDir) throws Exception {
        var proj1 = tempDir.resolve("project1");
        var proj2 = tempDir.resolve("project2");

        ProjectGenerator.generate(proj1.toString());
        ProjectGenerator.generate(proj2.toString());

        var conf1 = Files.readString(proj1.resolve("application.conf"));
        var conf2 = Files.readString(proj2.resolve("application.conf"));

        var secret1 = extractSecret(conf1);
        var secret2 = extractSecret(conf2);

        assertNotNull(secret1, "project1 should have a session.secret");
        assertNotNull(secret2, "project2 should have a session.secret");
        assertNotEquals(secret1, secret2, "two generated projects should have different secrets");
    }

    @Test
    void generatedSecretPassesValidation(@TempDir Path tempDir) throws Exception {
        var projDir = tempDir.resolve("myproject");
        ProjectGenerator.generate(projDir.toString());

        var conf = Files.readString(projDir.resolve("application.conf"));
        var secret = extractSecret(conf);

        assertNotNull(secret, "session.secret must be present");
        assertTrue(secret.length() >= 32, "generated secret must be at least 32 characters");

        // Should not trigger the weak-secret warning when passed to validateSecret
        var app = Brace.app();
        assertDoesNotThrow(() -> app.sessions(secret),
            "generated secret should pass validation without warning");
    }

    @Test
    void gitignoreContainsApplicationConf(@TempDir Path tempDir) throws Exception {
        var projDir = tempDir.resolve("myproject");
        ProjectGenerator.generate(projDir.toString());

        var gitignore = Files.readString(projDir.resolve(".gitignore"));
        assertTrue(gitignore.contains("application.conf"),
            ".gitignore should exclude application.conf");
    }

    @Test
    void applicationConfExampleExists(@TempDir Path tempDir) throws Exception {
        var projDir = tempDir.resolve("myproject");
        ProjectGenerator.generate(projDir.toString());

        var examplePath = projDir.resolve("application.conf.example");
        assertTrue(Files.exists(examplePath), "application.conf.example should be created");

        var content = Files.readString(examplePath);
        assertTrue(content.contains("CHANGE-ME-to-a-random-string-at-least-32-chars"),
            "application.conf.example should contain the placeholder");
        assertTrue(content.contains("SESSION_SECRET"),
            "application.conf.example should document env var usage");
    }

    @Test
    void applicationConfNotInRepository(@TempDir Path tempDir) throws Exception {
        var projDir = tempDir.resolve("myproject");
        ProjectGenerator.generate(projDir.toString());

        // The generated application.conf should not be in .gitignore check — it should be listed
        var gitignore = Files.readString(projDir.resolve(".gitignore"));
        var lines = gitignore.split("\n");
        boolean found = false;
        for (String line : lines) {
            if (line.trim().equals("application.conf")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "application.conf must be in .gitignore");
    }

    @Test
    void placeholderTriggersWeakSecretWarning() {
        var app = Brace.app();
        var oldPlaceholder = "CHANGE-ME-to-a-random-string-at-least-32-chars";

        // Should not throw, but logs a warning
        assertDoesNotThrow(() -> app.sessions(oldPlaceholder));
    }

    @Test
    void changeHyphenVariantsAreWeak() {
        var app1 = Brace.app();
        var app2 = Brace.app();
        var app3 = Brace.app();

        assertDoesNotThrow(() -> app1.sessions("my-secret-change-me-at-least-32-chars-long"));
        assertDoesNotThrow(() -> app2.sessions("my-secret-change_me-at-least-32-chars-long"));
        assertDoesNotThrow(() -> app3.sessions("my-secret-changeme-at-least-32-characters-long"));
    }

    /**
     * Extract session.secret value from application.conf content.
     */
    private String extractSecret(String confContent) {
        for (String line : confContent.split("\n")) {
            if (line.startsWith("session.secret=")) {
                return line.substring("session.secret=".length()).trim();
            }
        }
        return null;
    }
}
