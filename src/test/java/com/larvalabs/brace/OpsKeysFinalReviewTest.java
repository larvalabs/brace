package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final review fixes for L1 (docs/2026-11-09-security-review-todos.md): every key line of an ops
 * authorized-keys file must say its scope, so no way of writing a line makes a guess at it.
 */
class OpsKeysFinalReviewTest {

    @TempDir
    Path tmp;
    private int files;

    private final OpsKeys.Keypair kp = OpsKeys.generateKeypair();

    private Path write(String content) throws Exception {
        Path file = tmp.resolve("keys-" + files++);
        Files.writeString(file, content);
        return file;
    }

    private IllegalStateException refused(String content) throws Exception {
        Path file = write(content);
        var ex = assertThrows(IllegalStateException.class, () -> OpsKeys.loadAuthorizedKeys(file.toString()),
            () -> "loaded: " + content.replace(kp.publicKey(), "KEY"));
        assertFalse(ex.getMessage().contains(kp.publicKey()), "never echo key material: " + ex.getMessage());
        return ex;
    }

    // ---- L-A: scope words written as labels no longer load as CONTROL ----

    @Test
    void aLineWithoutAScopeTokenFailsStartup() throws Exception {
        for (String rest : new String[] {"", " ci-agent", " read ci-agent", " ci-agent read-only", " ro ci", " (read)",
                " scope read", " scope-read", " read-only agent", " --label scope-ci", " # all comment"}) {
            var ex = refused("# keys\n" + kp.publicKey() + rest + "\n");
            String message = ex.getMessage();
            assertTrue(message.contains("line 2"), message);
            assertTrue(message.contains("scope:read") && message.contains("scope:control"), message);
            assertTrue(message.contains("scope:control to keep"), "says what to add to keep the old meaning: " + message);
        }
    }

    @Test
    void scopeInACommentOnAKeyLineIsRefused() throws Exception {
        for (String rest : new String[] {" label #1 scope:read", " #scope:read", " deploy-bot # TODO scope:read",
                " scope:control ci # was Scope: read", " scope:control # scope=read"}) {
            var ex = refused(kp.publicKey() + rest + "\n");
            assertTrue(ex.getMessage().contains("comment"), ex.getMessage());
            assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
        }
        // A comment line of its own may describe the format.
        Path ok = write("# Format: KEY scope:read|scope:control label\n" + kp.publicKey() + " scope:read ci # the CI agent\n");
        assertEquals(OpsScope.READ, OpsKeys.loadAuthorizedKeys(ok.toString()).get(kp.publicKey()));
    }

    @Test
    void nonAsciiSpacesAndColonLookAlikesAreRefused() throws Exception {
        for (String rest : new String[] {" scope：read", " scope:control scope：read", " scope:read",
                " scope:read ci", " scope:read ci　x", " scope﹕read scope:control",
                " scope꞉read scope:control", " scope∶read scope:control", " scope＝read scope:control"}) {
            var ex = refused(kp.publicKey() + rest + "\n");
            assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
            assertTrue(ex.getMessage().contains("U+"), "names the character: " + ex.getMessage());
        }
    }

    @Test
    void aByteOrderMarkAnywhereButTheStartOfTheFileIsRefused() throws Exception {
        var ex = refused("# keys\n﻿" + kp.publicKey() + " scope:read ci\n");
        assertTrue(ex.getMessage().contains("line 2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("byte-order mark"), ex.getMessage());
        refused(kp.publicKey() + " scope:read﻿ ci\n");
        Path leading = write("﻿" + kp.publicKey() + " scope:read ci\n");
        assertEquals(OpsScope.READ, OpsKeys.loadAuthorizedKeys(leading.toString()).get(kp.publicKey()));
    }

    // ---- a key that can never match fails at startup ----

    @Test
    void aKeyThatIsNotAnEd25519PublicKeyIsRefused() throws Exception {
        String key = kp.publicKey();
        for (String bad : new String[] {"not-base64!", key.substring(0, key.length() - 4), key + "AAAA",
                "AAAA", key.replace('A', '*'), "scope:read", "ed25519:", "ed25519:zzzz",
                java.util.Base64.getEncoder().encodeToString(new byte[44])}) {
            Path file = write(bad + " scope:control label\n");
            var ex = assertThrows(IllegalStateException.class, () -> OpsKeys.loadAuthorizedKeys(file.toString()), bad);
            assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
            assertTrue(ex.getMessage().contains("brace ops keypair"), ex.getMessage());
        }
    }

    @Test
    void anOpenSshLineGetsItsOwnMessage() throws Exception {
        String ssh = "AAAAC3NzaC1lZDI1NTE5AAAAIJ" + "x".repeat(42);
        for (String line : new String[] {"ssh-ed25519 " + ssh + " me@laptop", "ssh-ed25519 " + ssh + " scope:read me",
                ssh + " scope:read me", "ssh-rsa AAAAB3NzaC1yc2E scope:control me"}) {
            Path file = write(line + "\n");
            var ex = assertThrows(IllegalStateException.class, () -> OpsKeys.loadAuthorizedKeys(file.toString()), line);
            assertTrue(ex.getMessage().contains("OpenSSH"), ex.getMessage());
            assertTrue(ex.getMessage().contains("brace ops keypair"), ex.getMessage());
        }
    }

    // ---- what loads ----

    @Test
    void explicitLinesLoad() throws Exception {
        record Case(String rest, OpsScope expected) {}
        for (var c : new Case[] {
                new Case("scope:read", OpsScope.READ),
                new Case("scope:control", OpsScope.CONTROL),
                new Case("scope:read control", OpsScope.READ),
                new Case("scope:control read", OpsScope.CONTROL),
                new Case("scope:control read-only agent", OpsScope.CONTROL),
                new Case("ci-agent scope:control", OpsScope.CONTROL),
                new Case("scope:READ ci", OpsScope.READ),
                new Case("ci#1 scope:read", OpsScope.READ),
                new Case("scope:read josé@laptop", OpsScope.READ),
                new Case("\tscope:read\t ci  # the CI agent", OpsScope.READ)}) {
            Path file = write(kp.publicKey() + " " + c.rest() + "\n");
            assertEquals(c.expected(), OpsKeys.loadAuthorizedKeys(file.toString()).get(kp.publicKey()), c.rest());
        }
        Path legacyPrefix = write("ed25519:" + kp.publicKey() + " scope:control legacy\r\n");
        assertEquals(OpsScope.CONTROL, OpsKeys.loadAuthorizedKeys(legacyPrefix.toString()).get(kp.publicKey()));
    }

    // ---- the tools that write the file always write the token ----

    @Test
    void theScaffoldsKeysFileLoads() throws Exception {
        Path project = tmp.resolve("scaffold");
        ProjectGenerator.generate(project.toString());
        var keys = OpsKeys.loadAuthorizedKeys(project.resolve("ops-authorized-keys").toString());
        assertEquals(1, keys.size());
        assertTrue(keys.containsValue(OpsScope.CONTROL));
        assertTrue(Files.readString(project.resolve("ops-authorized-keys")).contains(" scope:control "));
    }
}
