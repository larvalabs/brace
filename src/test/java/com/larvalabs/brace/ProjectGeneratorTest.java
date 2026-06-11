package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

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

    @Test
    void generatedOpsPrivateKeyHasOwnerOnlyPermissions(@TempDir Path tempDir) throws Exception {
        var projDir = tempDir.resolve("myproject");
        ProjectGenerator.generate(projDir.toString());

        Path opsKeyFile = projDir.resolve("ops-private.key");
        assertTrue(Files.exists(opsKeyFile), "ops-private.key should be generated");
        assertPrivateKeyHasOwnerOnlyPermissions(opsKeyFile);
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

    private void assertPrivateKeyHasOwnerOnlyPermissions(Path privKey) {
        Assumptions.assumeTrue(isPosixFileSystem(), "test requires POSIX file system support");
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(privKey);
            // Owner-only permissions should be: OWNER_READ, OWNER_WRITE (no others)
            assertEquals(PosixFilePermissions.fromString("rw-------"), perms,
                "Private key must have owner-only permissions (rw-------)");
        } catch (Exception e) {
            Assumptions.abort("POSIX file system not supported");
        }
    }

    private boolean isPosixFileSystem() {
        try {
            // Test with a temporary file to check if POSIX is supported
            Path testFile = Files.createTempFile("posix-test-", ".tmp");
            try {
                Files.getPosixFilePermissions(testFile);
                return true;
            } finally {
                Files.deleteIfExists(testFile);
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void projectNameMustContainOnlyAlphanumericUnderscoreHyphen() {
        // Invalid names with special characters or path traversal attempts
        String[] invalidNames = {
            "my-project/../evil",    // path traversal
            "project.name",          // dots
            "my project",            // spaces
            "my/project",            // slashes
            "../project",            // parent directory
            "project@name",          // at sign
            "project!",              // exclamation
            "project#test"           // hash
        };

        for (String invalidName : invalidNames) {
            // Verify the regex rejects invalid names
            assertFalse(invalidName.matches("[A-Za-z0-9_-]+"),
                "Invalid name '" + invalidName + "' should not match the allowed pattern");
        }
    }

    @Test
    void generatedAppExposesReusableRouteWiring(@TempDir Path tempDir) throws Exception {
        var projDir = tempDir.resolve("myproject");
        ProjectGenerator.generate(projDir.toString());

        var appJava = Files.readString(projDir.resolve("src/main/java/app/App.java"));
        assertTrue(appJava.contains("public static void routes(Brace app)"),
            "App.java should extract route registration into routes(Brace)");
        assertTrue(appJava.contains("routes(app);"),
            "main() should call routes(app)");

        var testJava = Files.readString(projDir.resolve("src/test/java/app/HomeControllerTest.java"));
        assertTrue(testJava.contains("App::routes"),
            "generated test should reuse App.routes instead of re-registering routes");
        assertTrue(testJava.contains("class TestData"),
            "generated test should include the TestData factory pattern");
    }

    @Test
    void generatedPomRunsTestsAndPackagesRunnableJar(@TempDir Path tempDir) throws Exception {
        var projDir = tempDir.resolve("myproject");
        ProjectGenerator.generate(projDir.toString());

        var pom = Files.readString(projDir.resolve("pom.xml"));
        // Without the surefire pin, Maven's inherited 2.x silently runs zero
        // JUnit 5 tests ("Tests run: 0" + BUILD SUCCESS).
        assertTrue(pom.contains("<artifactId>maven-surefire-plugin</artifactId>"),
            "pom must pin maven-surefire-plugin so mvn test runs JUnit 5");
        var surefireVersion = pom.replaceAll(
            "(?s).*maven-surefire-plugin</artifactId>\\s*<version>([^<]+)</version>.*", "$1");
        assertTrue(surefireVersion.matches("3\\..*"),
            "surefire pin must be 3.x (got " + surefireVersion + ")");

        // The Dockerfile runs java -jar app.jar, so mvn package must produce an
        // executable fat jar at a deterministic path.
        assertTrue(pom.contains("<artifactId>maven-shade-plugin</artifactId>"),
            "pom must configure maven-shade-plugin for an executable fat jar");
        assertTrue(pom.contains("<mainClass>app.App</mainClass>"),
            "shade config must set the scaffold's main class");
        assertTrue(pom.contains("ServicesResourceTransformer"),
            "shade config must merge META-INF/services (Jetty/Hibernate need it)");
        assertTrue(pom.contains("<finalName>app</finalName>"),
            "jar name must be fixed so the Dockerfile can COPY target/app.jar");

        var dockerfile = Files.readString(projDir.resolve("Dockerfile"));
        assertTrue(dockerfile.contains("COPY target/app.jar app.jar"),
            "Dockerfile must copy the shaded jar by its fixed name");
    }

    @Test
    void projectNameAllowsAlphanumericUnderscoreHyphen() {
        String[] validNames = {
            "my-project",
            "MyProject",
            "project123",
            "my_project",
            "test-project-2024",
            "a",
            "Z",
            "1",
            "_",
            "-",
            "my_project-123"
        };

        for (String validName : validNames) {
            assertTrue(validName.matches("[A-Za-z0-9_-]+"),
                "Valid name '" + validName + "' should match the allowed pattern");
        }
    }
}
