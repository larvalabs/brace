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
