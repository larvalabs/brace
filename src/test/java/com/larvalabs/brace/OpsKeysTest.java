package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OpsKeysTest {

    @Test
    void generateKeypairProducesValidPair() {
        var kp = OpsKeys.generateKeypair();
        assertNotNull(kp.publicKey());
        assertNotNull(kp.privateKey());
        assertFalse(kp.publicKey().isEmpty());
        assertFalse(kp.privateKey().isEmpty());
    }

    @Test
    void signAndVerify() {
        var kp = OpsKeys.generateKeypair();
        String message = "test message";
        String signature = OpsKeys.sign(message, kp.privateKey());
        assertTrue(OpsKeys.verify(message, signature, kp.publicKey()));
    }

    @Test
    void verifyRejectsWrongMessage() {
        var kp = OpsKeys.generateKeypair();
        String signature = OpsKeys.sign("original", kp.privateKey());
        assertFalse(OpsKeys.verify("tampered", signature, kp.publicKey()));
    }

    @Test
    void verifyRejectsWrongKey() {
        var kp1 = OpsKeys.generateKeypair();
        var kp2 = OpsKeys.generateKeypair();
        String signature = OpsKeys.sign("message", kp1.privateKey());
        assertFalse(OpsKeys.verify("message", signature, kp2.publicKey()));
    }

    @Test
    void loadAuthorizedKeysFromFile(@TempDir Path tmpDir) throws Exception {
        var kp1 = OpsKeys.generateKeypair();
        var kp2 = OpsKeys.generateKeypair();

        Path file = tmpDir.resolve("authorized-keys");
        Files.writeString(file,
            "# This is a comment\n" +
            kp1.publicKey() + " deploy-bot\n" +
            "\n" +
            kp2.publicKey() + "\n" +
            "# Another comment\n");

        var keys = OpsKeys.loadAuthorizedKeys(file.toString());
        assertEquals(2, keys.size());
        assertTrue(keys.containsKey(kp1.publicKey()));
        assertTrue(keys.containsKey(kp2.publicKey()));
        // Unscoped entries default to the CONTROL ceiling (backward compatible).
        assertEquals(OpsScope.CONTROL, keys.get(kp1.publicKey()));
        assertEquals(OpsScope.CONTROL, keys.get(kp2.publicKey()));
    }

    @Test
    void loadAuthorizedKeysParsesScopeMarker(@TempDir Path tmpDir) throws Exception {
        var readKp = OpsKeys.generateKeypair();
        var controlKp = OpsKeys.generateKeypair();
        var bareKp = OpsKeys.generateKeypair();

        Path file = tmpDir.resolve("scoped-keys");
        Files.writeString(file,
            readKp.publicKey() + "  scope:read  oncall-agent\n" +
            controlKp.publicKey() + "  scope:control  ops-laptop\n" +
            bareKp.publicKey() + "  some-label\n");

        var keys = OpsKeys.loadAuthorizedKeys(file.toString());
        assertEquals(OpsScope.READ, keys.get(readKp.publicKey()));
        assertEquals(OpsScope.CONTROL, keys.get(controlKp.publicKey()));
        assertEquals(OpsScope.CONTROL, keys.get(bareKp.publicKey()), "no marker defaults to control");
    }

    @Test
    void malformedScopeTokensFailStartup(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        // Any token with ':' or '=' that isn't exactly scope:read / scope:control, with or without a label.
        for (String bad : new String[] {"scope:readonly", "scope:read-only", "scope: read", "Scope:read",
                "scope=read", "scope:", "--scope=read", "role:read", "scpoe:read", "scope:admin",
                "label=x", "scope:read scope:control", "scope:read scope:read"}) {
            for (String suffix : new String[] {"", " ci-agent"}) {
                assertRejected(tmpDir, kp, bad + suffix);
            }
        }
        // A bare scope-like word as the ONLY token after the key: the classic `KEY read` mistake.
        for (String bare : new String[] {"read", "ro", "control", "readonly", "read-only", "READ",
                "read # the CI agent"}) {
            var ex = assertRejected(tmpDir, kp, bare);
            assertTrue(ex.getMessage().contains("scope:"), ex.getMessage());
        }
    }

    private static IllegalStateException assertRejected(Path tmpDir, OpsKeys.Keypair kp, String rest) throws Exception {
        Path file = tmpDir.resolve("bad-" + Math.abs(rest.hashCode()));
        Files.writeString(file, "# keys\n" + kp.publicKey() + " " + rest + "\n");
        var ex = assertThrows(IllegalStateException.class,
            () -> OpsKeys.loadAuthorizedKeys(file.toString()), rest);
        assertTrue(ex.getMessage().contains("line 2"), ex.getMessage());
        assertFalse(ex.getMessage().contains(kp.publicKey()), "never echo key material");
        return ex;
    }

    // --- L1 review fixes: the grammar is simple to state, and legitimate lines load ---

    @Test
    void legitimateLabelsAndCommentsLoad(@TempDir Path tmpDir) throws Exception {
        record Case(String rest, OpsScope expected) {}
        var cases = new Case[] {
            new Case("scope:read control", OpsScope.READ),          // a label equal to a scope word
            new Case("scope:control ro", OpsScope.CONTROL),
            new Case("scope:read read", OpsScope.READ),
            new Case("--label scope-ci", OpsScope.CONTROL),         // a label starting with "scope"
            new Case("scope-ci", OpsScope.CONTROL),
            new Case("scope:read # read-only CI key", OpsScope.READ),
            new Case("scope:READ ci", OpsScope.READ),              // the value is case-insensitive
            new Case("ci-agent scope:control", OpsScope.CONTROL),   // any order
            new Case("read-only agent", OpsScope.CONTROL),          // two label words, no scope token
            new Case("ci#1 scope:read", OpsScope.READ),             // '#' not after whitespace is text
            new Case("# all comment", OpsScope.CONTROL),
            new Case("", OpsScope.CONTROL),
        };
        for (var c : cases) {
            var kp = OpsKeys.generateKeypair();
            Path file = tmpDir.resolve("ok-" + Math.abs(c.rest().hashCode()));
            Files.writeString(file, kp.publicKey() + (c.rest().isEmpty() ? "" : " " + c.rest()) + "\n");
            assertEquals(c.expected(), OpsKeys.loadAuthorizedKeys(file.toString()).get(kp.publicKey()), c.rest());
        }
    }

    @Test
    void aCommentAfterTheKeyDoesNotMakeTheLineReadOnlyByAccident(@TempDir Path tmpDir) throws Exception {
        // "scope:read" inside a comment is not a scope token.
        var kp = OpsKeys.generateKeypair();
        Path file = tmpDir.resolve("comment");
        Files.writeString(file, kp.publicKey() + " deploy-bot # TODO scope:read\n");
        assertEquals(OpsScope.CONTROL, OpsKeys.loadAuthorizedKeys(file.toString()).get(kp.publicKey()));
    }

    @Test
    void aLeadingByteOrderMarkIsStripped(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path file = tmpDir.resolve("bom");
        Files.writeString(file, "﻿" + kp.publicKey() + " scope:read ci\n");
        var keys = OpsKeys.loadAuthorizedKeys(file.toString());
        assertEquals(OpsScope.READ, keys.get(kp.publicKey()), "the first key must match without the BOM");
    }

    @Test
    void duplicateKeyKeepsLowerScope(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path file = tmpDir.resolve("dup");
        Files.writeString(file, kp.publicKey() + " scope:read a\n" + kp.publicKey() + " b\n");
        assertEquals(OpsScope.READ, OpsKeys.loadAuthorizedKeys(file.toString()).get(kp.publicKey()));
        Files.writeString(file, kp.publicKey() + " b\n" + kp.publicKey() + " scope:read a\n");
        assertEquals(OpsScope.READ, OpsKeys.loadAuthorizedKeys(file.toString()).get(kp.publicKey()));
    }

    @Test
    void fingerprintIsStableAndShort() {
        var kp = OpsKeys.generateKeypair();
        String fp1 = OpsKeys.fingerprint(kp.publicKey());
        String fp2 = OpsKeys.fingerprint(kp.publicKey());
        assertEquals(fp1, fp2, "fingerprint must be deterministic");
        assertEquals(12, fp1.length());
        assertNotEquals(fp1, OpsKeys.fingerprint(OpsKeys.generateKeypair().publicKey()));
    }

    @Test
    void loadAuthorizedKeysStripsLegacyEd25519Prefix(@TempDir Path tmpDir) throws Exception {
        // Older `brace ops keypair` wrote keys as "ed25519:<base64>". Servers running such a file
        // must still match the raw base64 the client presents, so the prefix is stripped on load.
        var kp = OpsKeys.generateKeypair();
        Path file = tmpDir.resolve("authorized-keys");
        Files.writeString(file, "ed25519:" + kp.publicKey() + "  legacy-bot\n");

        var keys = OpsKeys.loadAuthorizedKeys(file.toString());
        assertTrue(keys.containsKey(kp.publicKey()),
            "legacy ed25519: prefix must be stripped so the raw key matches");
        assertFalse(keys.containsKey("ed25519:" + kp.publicKey()));
    }

    @Test
    void loadAuthorizedKeysHandlesEmptyFile(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("empty-keys");
        Files.writeString(file, "# only comments\n\n");

        var keys = OpsKeys.loadAuthorizedKeys(file.toString());
        assertTrue(keys.isEmpty());
    }

    @Test
    void keypairPublicKeyFormat() {
        var kp = OpsKeys.generateKeypair();
        // Should be valid base64
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(kp.publicKey()));
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(kp.privateKey()));
    }

    @Test
    void readKeyFile(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keyFile = tmpDir.resolve("ops-private.key");
        Files.writeString(keyFile, kp.privateKey() + "\n" + kp.publicKey() + "\n");

        var read = OpsKeys.readKeyFile(keyFile.toString());
        assertEquals(kp.privateKey(), read.privateKey());
        assertEquals(kp.publicKey(), read.publicKey());

        // Should work for sign/verify
        String msg = "test message";
        String sig = OpsKeys.sign(msg, read.privateKey());
        assertTrue(OpsKeys.verify(msg, sig, read.publicKey()));
    }

    @Test
    void readKeyFileIgnoresComments(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keyFile = tmpDir.resolve("ops-private.key");
        Files.writeString(keyFile, "# Private key\n" + kp.privateKey() + "\n# Public key\n" + kp.publicKey() + "\n");

        var read = OpsKeys.readKeyFile(keyFile.toString());
        assertEquals(kp.privateKey(), read.privateKey());
        assertEquals(kp.publicKey(), read.publicKey());
    }

    @Test
    void differentKeypairsAreUnique() {
        var kp1 = OpsKeys.generateKeypair();
        var kp2 = OpsKeys.generateKeypair();
        assertNotEquals(kp1.publicKey(), kp2.publicKey());
        assertNotEquals(kp1.privateKey(), kp2.privateKey());
    }
}
