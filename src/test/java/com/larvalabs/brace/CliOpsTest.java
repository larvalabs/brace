package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
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

        // Private key must be written with owner-only permissions (rw-------)
        assertPrivateKeyHasOwnerOnlyPermissions(privKey);
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

    // L1 follow-up (docs/2026-11-09-security-review-todos.md): the label is written after the key
    // unvalidated, and the server's keys-file grammar refuses some labels outright (startup fails)
    // and truncates others (a comment). Every label the CLI accepts must load to the scope asked for.

    static final java.util.List<String> ACCEPTED_LABELS = java.util.List.of(
        "ci", "ci-agent", "alice@laptop", "tester@example.com@mbp", "scope-ci", "read-agent", "readonly-bot",
        "my label", "foo #bar", "a#b", "x.y/z", "--x", "ünïcödé", "a #b", "tab\tsep", "Control-Plane");

    @Test
    void everyAcceptedLabelRoundTripsThroughTheKeysFileAtTheIntendedScope() throws Exception {
        int n = 0;
        for (String label : ACCEPTED_LABELS) {
            for (boolean readOnly : new boolean[]{true, false}) {
                assertRoundTrips(label, readOnly, projectDir.resolve("p" + n++));
            }
        }
        // A bare scope word is a fine label on a read-only key: the scope token comes first.
        for (String word : java.util.List.of("read", "ro", "readonly", "read-only", "control", "READ")) {
            assertRoundTrips(word, true, projectDir.resolve("p" + n++));
        }
    }

    private void assertRoundTrips(String label, boolean readOnly, Path dir) throws Exception {
        Files.createDirectories(dir);
        String[] args = readOnly ? new String[]{"--label", label, "--read-only"} : new String[]{"--label", label};
        String what = "label '" + label + "' readOnly=" + readOnly;
        assertEquals(0, CliOps.keypair(dir, args), what);
        String pub = OpsKeys.readKeyFile(dir.resolve("ops-private.key").toString()).publicKey();
        var loaded = OpsKeys.loadAuthorizedKeys(dir.resolve("ops-authorized-keys").toString());
        assertEquals(readOnly ? OpsScope.READ : OpsScope.CONTROL, loaded.get(pub), what);
        // The whole label survives on the line (no comment cut it short)...
        String line = Files.readAllLines(dir.resolve("ops-authorized-keys")).stream()
            .filter(l -> l.startsWith(pub)).findFirst().orElseThrow();
        String written = OpsKeys.stripComment(line).strip();
        assertEquals(line.strip(), written, what + ": the label must not start a comment");
        // ...and a rotation with the same label replaces the entry instead of orphaning it.
        Files.delete(dir.resolve("ops-private.key"));
        assertEquals(0, CliOps.keypair(dir, args), what);
        String pub2 = OpsKeys.readKeyFile(dir.resolve("ops-private.key").toString()).publicKey();
        var reloaded = OpsKeys.loadAuthorizedKeys(dir.resolve("ops-authorized-keys").toString());
        assertEquals(java.util.Map.of(pub2, readOnly ? OpsScope.READ : OpsScope.CONTROL), reloaded, what);
    }

    @Test
    void labelsTheKeysFileWouldRefuseAreRejectedBeforeAnyKeyIsGenerated() throws Exception {
        record Case(String label, boolean readOnly, String mentions) {}
        var cases = new java.util.ArrayList<Case>();
        for (String word : java.util.List.of("read", "ro", "readonly", "read-only", "control", "READ", " read ")) {
            cases.add(new Case(word, false, "--read-only"));
        }
        for (boolean ro : new boolean[]{true, false}) {
            for (String l : java.util.List.of("a:b", "k=v", "scope:read", "scope:control", "role:read", "x=")) {
                cases.add(new Case(l, ro, "':' or '='"));
            }
            for (String l : java.util.List.of("#team", " #x", "#")) cases.add(new Case(l, ro, "'#'"));
            cases.add(new Case("bell\u0007", ro, "control character"));
            cases.add(new Case("nul\u0000x", ro, "control character"));
        }
        int n = 0;
        var originalErr = System.err;
        for (var c : cases) {
            Path dir = projectDir.resolve("r" + n++);
            Files.createDirectories(dir);
            var err = new java.io.ByteArrayOutputStream();
            System.setErr(new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));
            int code;
            try {
                code = CliOps.keypair(dir, c.readOnly()
                    ? new String[]{"--label", c.label(), "--read-only"} : new String[]{"--label", c.label()});
            } finally {
                System.setErr(originalErr);
            }
            String what = "label '" + c.label() + "' readOnly=" + c.readOnly();
            assertEquals(1, code, what);
            assertFalse(Files.exists(dir.resolve("ops-private.key")), what + ": no key may be generated");
            assertFalse(Files.exists(dir.resolve("ops-authorized-keys")), what + ": the keys file is untouched");
            String message = err.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(message.contains(c.mentions()), what + ": the message names the rule: " + message);
        }
    }

    @Test
    void defaultLabelIsAlwaysAcceptedByTheKeysFile() {
        // The default label comes from git user.email and the host name, which the user didn't
        // type: characters the keys file reserves are replaced instead of refused.
        assertEquals("a-b-c@host", CliOps.safeDefaultLabel("a=b:c@host"));
        assertEquals("team@host", CliOps.safeDefaultLabel("#team@host"));
        assertNull(CliOps.labelProblem(CliOps.safeDefaultLabel("#=:#"), false));
    }

    @Test
    void aLabelOfOnlyUnicodeSpaceFallsBackToTheDefault() throws Exception {
        // An empty label would match (and replace) another developer's unlabeled entry.
        assertEquals(0, CliOps.keypair(projectDir, new String[]{"--label", "  "}));
        String content = Files.readString(projectDir.resolve("ops-authorized-keys"));
        assertTrue(content.contains(CliOps.defaultLabel(projectDir)), content);
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
            Files.getPosixFilePermissions(projectDir);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
