package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliOpsTest {

    @TempDir Path projectDir;

    @BeforeEach
    void scaffoldProject() throws Exception {
        Files.createDirectories(projectDir.resolve("src/main/java"));
    }

    @Test
    void keypairWritesPrivateKeyAndAuthorizedKeys() throws Exception {
        int code = CliOps.keypair(projectDir, new String[]{"--label", "ci"});
        assertEquals(0, code);

        // Private key file is written in the format readKeyFile expects, and round-trips.
        Path privKey = projectDir.resolve("ops-private.key");
        assertTrue(Files.exists(privKey), "ops-private.key must be written");
        var kp = OpsKeys.readKeyFile(privKey.toString());
        String msg = "test";
        assertTrue(OpsKeys.verify(msg, OpsKeys.sign(msg, kp.privateKey()), kp.publicKey()));

        // Authorized-keys carries the label and the raw base64 public key — no "ed25519:" prefix.
        Path authKeys = projectDir.resolve("ops-authorized-keys");
        assertTrue(Files.exists(authKeys));
        String content = Files.readString(authKeys);
        assertTrue(content.contains("ci"));
        assertFalse(content.contains("ed25519:"), "must not write a legacy algorithm prefix");
    }

    @Test
    void keypairRefusesToClobberExistingPrivateKey() throws Exception {
        assertEquals(0, CliOps.keypair(projectDir, new String[]{"--label", "k1"}));
        String firstKey = Files.readString(projectDir.resolve("ops-private.key"));
        // A second run must not silently overwrite a key already trusted by a deployed server.
        assertNotEquals(0, CliOps.keypair(projectDir, new String[]{"--label", "k2"}));
        assertEquals(firstKey, Files.readString(projectDir.resolve("ops-private.key")));
    }

    @Test
    void generatedKeyIsAcceptedByAuthorizedKeysLookup() throws Exception {
        // End-to-end guard against the prefix bug: the public key the client presents (from the
        // private key file) must be present in the set the server builds from ops-authorized-keys.
        CliOps.keypair(projectDir, new String[]{"--label", "k1"});
        String clientPublicKey =
            OpsKeys.readKeyFile(projectDir.resolve("ops-private.key").toString()).publicKey();
        var authorized =
            OpsKeys.loadAuthorizedKeys(projectDir.resolve("ops-authorized-keys").toString());
        assertTrue(authorized.containsKey(clientPublicKey),
            "server's authorized-keys set must contain the raw public key the client sends");
    }

    @Test
    void keypairRotateReplacesEntryInPlace() throws Exception {
        assertEquals(0, CliOps.keypair(projectDir, new String[]{"--label", "dev1"}));
        String firstPub = OpsKeys.readKeyFile(projectDir.resolve("ops-private.key").toString()).publicKey();

        // Rotate: delete the private key, regenerate with the same label.
        Files.delete(projectDir.resolve("ops-private.key"));
        assertEquals(0, CliOps.keypair(projectDir, new String[]{"--label", "dev1"}));
        String secondPub = OpsKeys.readKeyFile(projectDir.resolve("ops-private.key").toString()).publicKey();
        assertNotEquals(firstPub, secondPub);

        var keyLines = Files.readAllLines(projectDir.resolve("ops-authorized-keys")).stream()
            .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        assertEquals(1, keyLines.size(), "rotation must replace the prior entry, not orphan it");
        var authorized = OpsKeys.loadAuthorizedKeys(projectDir.resolve("ops-authorized-keys").toString());
        assertTrue(authorized.containsKey(secondPub));
        assertFalse(authorized.containsKey(firstPub), "the rotated-out key must no longer be trusted");
    }

    @Test
    void keypairDifferentLabelsCoexist() throws Exception {
        // Two developers (different labels) sharing the committed file must not clobber each other.
        assertEquals(0, CliOps.keypair(projectDir, new String[]{"--label", "alice"}));
        Files.delete(projectDir.resolve("ops-private.key"));
        assertEquals(0, CliOps.keypair(projectDir, new String[]{"--label", "bob"}));

        var keyLines = Files.readAllLines(projectDir.resolve("ops-authorized-keys")).stream()
            .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        assertEquals(2, keyLines.size(), "distinct labels must each get their own entry");
        String content = Files.readString(projectDir.resolve("ops-authorized-keys"));
        assertTrue(content.contains("alice"));
        assertTrue(content.contains("bob"));
    }

    @Test
    void defaultLabelUsedWhenNoLabelGiven() throws Exception {
        assertEquals(0, CliOps.keypair(projectDir, new String[]{}));
        String expected = CliOps.defaultLabel(projectDir);
        assertFalse(expected.isBlank());
        assertFalse(expected.contains(" "), "label must be a single token");
        String content = Files.readString(projectDir.resolve("ops-authorized-keys"));
        assertTrue(content.contains(expected), "authorized-keys must carry the default identity label");
    }

    @Test
    void defaultLabelUsesGitEmailWhenAvailable() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git not available");
        runGit("init");
        runGit("config", "user.email", "tester@example.com");
        String label = CliOps.defaultLabel(projectDir);
        assertTrue(label.startsWith("tester@example.com"),
            "default label should start with the git user.email, was: " + label);
    }

    @Test
    void keypairAppendsWhenFileExists() throws Exception {
        // A pre-existing authorized-keys file (e.g. another developer's key already committed) is
        // appended to, not overwritten.
        var existing = OpsKeys.generateKeypair();
        Files.writeString(projectDir.resolve("ops-authorized-keys"),
            "# header\n" + existing.publicKey() + "  existing\n");
        CliOps.keypair(projectDir, new String[]{"--label", "k1"});
        var keyLines = Files.readAllLines(projectDir.resolve("ops-authorized-keys")).stream()
            .map(String::strip)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .count();
        assertEquals(2, keyLines);
    }

    @Test
    void dashboardFailsWithoutKey() throws Exception {
        Files.writeString(projectDir.resolve(".brace"), "ops.local.url=http://localhost:8080\n");
        int code = CliOps.dashboard(projectDir, new String[]{});
        assertNotEquals(0, code);
    }

    private void runGit(String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        java.util.Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).directory(projectDir.toFile())
            .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    private boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
