package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 (2026-11 review, post-merge): the server refuses to start on an {@code ops-authorized-keys}
 * line with no scope token, so every keys-file line the CLI writes or tells the user to add must
 * load with {@link OpsKeys#loadAuthorizedKeys(String)}. {@code brace init} used to print
 * {@code KEY  <label>}, which fails startup.
 */
class CliKeysFileLinesTest {

    @TempDir Path tmp;

    private static Path writeAndLoad(Path dir, String content) throws Exception {
        Path file = dir.resolve("loaded-keys-" + System.nanoTime());
        Files.writeString(file, content);
        assertFalse(OpsKeys.loadAuthorizedKeys(file.toString()).isEmpty(), "no key loaded from: " + content);
        return file;
    }

    @Test
    void initPrintsAKeysLineTheServerAccepts() throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        var serverKey = OpsKeys.generateKeypair();
        var clientKey = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("ops-authorized-keys");
        Files.writeString(keysFile, serverKey.publicKey() + " scope:control server-only\n");
        Files.writeString(tmp.resolve("ops-private.key"),
            clientKey.privateKey() + "\n" + clientKey.publicKey() + "\n");

        var app = Brace.app().opsUnpinnedForTests().port(0).ops(keysFile.toString());
        app.start();
        try {
            Files.writeString(tmp.resolve(".brace"),
                "ops.local.url=http://localhost:8080\n"
                + "ops.prod.url=http://localhost:" + app.actualPort() + "\n");
            var result = CliInit.runWithRemote(tmp);
            String action = result.actions().stream()
                .filter(a -> a.contains(clientKey.publicKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no action names the key: " + result.actions()));
            String line = action.substring(action.indexOf(clientKey.publicKey()));
            var loaded = OpsKeys.loadAuthorizedKeys(writeAndLoad(tmp, line + "\n").toString());
            assertEquals(OpsScope.CONTROL, loaded.get(clientKey.publicKey()), action);
        } finally {
            app.stop();
        }
    }

    @Test
    void keypairWritesLinesTheServerAccepts() throws Exception {
        PrintStream out = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            assertEquals(0, CliOps.keypair(tmp, new String[] {"--label", "alice@laptop"}));
            Files.delete(tmp.resolve("ops-private.key"));
            assertEquals(0, CliOps.keypair(tmp, new String[] {"--label", "oncall", "--read-only"}));
        } finally {
            System.setOut(out);
        }
        var loaded = OpsKeys.loadAuthorizedKeys(tmp.resolve("ops-authorized-keys").toString());
        assertEquals(List.of(OpsScope.CONTROL, OpsScope.READ), List.copyOf(loaded.values()));
    }

    @Test
    void keysFileLineLoadsForBothScopes() throws Exception {
        String pub = OpsKeys.generateKeypair().publicKey();
        assertEquals(OpsScope.READ, OpsKeys.loadAuthorizedKeys(
            writeAndLoad(tmp, CliOps.keysFileLine(pub, "ci", true) + "\n").toString()).get(pub));
        assertEquals(OpsScope.CONTROL, OpsKeys.loadAuthorizedKeys(
            writeAndLoad(tmp, CliOps.keysFileLine(pub, "ci", false) + "\n").toString()).get(pub));
    }

    @Test
    void newProjectKeysFileLoads() throws Exception {
        Path proj = tmp.resolve("newproj");
        PrintStream out = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            ProjectGenerator.generate(proj.toString());
        } finally {
            System.setOut(out);
        }
        var loaded = OpsKeys.loadAuthorizedKeys(proj.resolve("ops-authorized-keys").toString());
        assertEquals(List.of(OpsScope.CONTROL), List.copyOf(loaded.values()));
    }
}
