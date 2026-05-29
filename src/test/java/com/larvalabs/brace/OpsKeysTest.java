package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

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

        Set<String> keys = OpsKeys.loadAuthorizedKeys(file.toString());
        assertEquals(2, keys.size());
        assertTrue(keys.contains(kp1.publicKey()));
        assertTrue(keys.contains(kp2.publicKey()));
    }

    @Test
    void loadAuthorizedKeysStripsLegacyEd25519Prefix(@TempDir Path tmpDir) throws Exception {
        // Older `brace ops keypair` wrote keys as "ed25519:<base64>". Servers running such a file
        // must still match the raw base64 the client presents, so the prefix is stripped on load.
        var kp = OpsKeys.generateKeypair();
        Path file = tmpDir.resolve("authorized-keys");
        Files.writeString(file, "ed25519:" + kp.publicKey() + "  legacy-bot\n");

        Set<String> keys = OpsKeys.loadAuthorizedKeys(file.toString());
        assertTrue(keys.contains(kp.publicKey()),
            "legacy ed25519: prefix must be stripped so the raw key matches");
        assertFalse(keys.contains("ed25519:" + kp.publicKey()));
    }

    @Test
    void loadAuthorizedKeysHandlesEmptyFile(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("empty-keys");
        Files.writeString(file, "# only comments\n\n");

        Set<String> keys = OpsKeys.loadAuthorizedKeys(file.toString());
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
