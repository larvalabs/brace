package com.larvalabs.brace;

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

    @Test
    void reportsMissingProdUrl() throws Exception {
        var result = CliInit.run(tmp);
        var prodCheck = result.local().stream()
            .filter(c -> c.name().equals("ops.prod.url"))
            .findFirst()
            .orElseThrow();
        assertTrue(prodCheck.detail().toLowerCase().contains("not set"), prodCheck.detail());
    }

    @Test
    void reportsConfiguredProdUrl() throws Exception {
        Files.writeString(tmp.resolve(".brace"),
            "ops.local.url=http://localhost:8080\n" +
            "ops.prod.url=https://app.example.com\n");
        var result = CliInit.run(tmp);
        var prodCheck = result.local().stream()
            .filter(c -> c.name().equals("ops.prod.url"))
            .findFirst()
            .orElseThrow();
        assertEquals("configured", prodCheck.detail());
    }

    @Test
    void remoteChecksSkippedWhenNoProdUrl() throws Exception {
        Files.writeString(tmp.resolve(".brace"), "ops.local.url=http://localhost:8080\n");
        Files.writeString(tmp.resolve("ops-authorized-keys"), "ed25519:abc test\n");
        Files.writeString(tmp.resolve("ops-private.key"), "ed25519:abc\nprivate\n");

        var result = CliInit.runWithRemote(tmp);
        assertTrue(result.remote().isEmpty());
    }

    @Test
    void remoteCheckSucceedsAgainstAuthorizedServer() throws Exception {
        var keypair = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("ops-authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");
        Files.writeString(tmp.resolve("ops-private.key"),
            keypair.privateKey() + "\n" + keypair.publicKey() + "\n");

        var app = Brace.app().port(0).ops(keysFile.toString());
        app.start();
        try {
            Files.writeString(tmp.resolve(".brace"),
                "ops.local.url=http://localhost:8080\n" +
                "ops.prod.url=http://localhost:" + app.actualPort() + "\n");

            var result = CliInit.runWithRemote(tmp);
            assertFalse(result.remote().isEmpty(), "expected remote checks to run");
            assertTrue(result.remote().stream().allMatch(c -> c.ok()), result.remote().toString());
        } finally {
            app.stop();
        }
    }

    @Test
    void remoteCheckCsrfBlockedSuggestsServerUpgradeNotKeyAdd() throws Exception {
        // Server has sessions on, so /ops/auth is csrf-blocked at the framework layer
        // (this is the wendell repro). The CLI's key IS authorized — the failure is
        // structural, not a key problem.
        var keypair = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("ops-authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");
        Files.writeString(tmp.resolve("ops-private.key"),
            keypair.privateKey() + "\n" + keypair.publicKey() + "\n");

        var app = Brace.app().port(0)
            .sessions("init-csrf-test-secret-at-least-32-characters")
            .ops(keysFile.toString());
        app.start();
        // Force the broken-CSRF behavior to reproduce the wendell incident.
        for (var route : app.routesForTesting()) {
            if ("POST".equals(route.method()) && "/ops/auth".equals(route.pattern())) {
                route.setCsrfRequired(true);
            }
        }
        try {
            Files.writeString(tmp.resolve(".brace"),
                "ops.local.url=http://localhost:8080\n" +
                "ops.prod.url=http://localhost:" + app.actualPort() + "\n");

            var result = CliInit.runWithRemote(tmp);

            assertFalse(result.ok());
            // Should NOT recommend adding the key (it's already authorized).
            assertFalse(result.actions().stream().anyMatch(
                a -> a.toLowerCase().contains("add to server's ops-authorized-keys")
                  || a.toLowerCase().contains("add your public key")),
                "must not blame missing key when CSRF is the actual cause; actions=" + result.actions());
            // Should mention CSRF / version remediation.
            assertTrue(result.actions().stream().anyMatch(
                a -> a.toLowerCase().contains("csrf") || a.toLowerCase().contains("upgrade")),
                "expected csrf/upgrade guidance; actions=" + result.actions());
            // The remote check detail should include the structured code.
            assertTrue(result.remote().stream().anyMatch(
                c -> c.detail().contains("csrf_required")),
                "remote detail should surface the error code: " + result.remote());
        } finally {
            app.stop();
        }
    }

    @Test
    void remoteCheckKeyAlreadyAuthorizedDoesNotSuggestKeyAdd() throws Exception {
        // Server returns 401 with an unrecognized error code. The CLI's key IS in the
        // local authorized-keys file, so blaming the operator for missing key
        // distribution is wrong — the local/server files have probably drifted.
        var keypair = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("ops-authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");
        Files.writeString(tmp.resolve("ops-private.key"),
            keypair.privateKey() + "\n" + keypair.publicKey() + "\n");

        // Server with a DIFFERENT authorized-keys file → 401 against this client.
        var serverKey = OpsKeys.generateKeypair();
        Path serverKeysFile = tmp.resolve("server-keys");
        Files.writeString(serverKeysFile, serverKey.publicKey() + " server-only\n");

        var app = Brace.app().port(0).ops(serverKeysFile.toString());
        app.start();
        try {
            Files.writeString(tmp.resolve(".brace"),
                "ops.local.url=http://localhost:8080\n" +
                "ops.prod.url=http://localhost:" + app.actualPort() + "\n");

            var result = CliInit.runWithRemote(tmp);

            assertFalse(result.ok());
            assertFalse(result.actions().stream().anyMatch(
                a -> a.toLowerCase().contains("add to server's ops-authorized-keys")
                  || a.toLowerCase().contains("add your public key")),
                "must not suggest adding key already in local file; actions=" + result.actions());
            assertTrue(result.actions().stream().anyMatch(
                a -> a.toLowerCase().contains("already in local")
                  || a.toLowerCase().contains("differs")
                  || a.toLowerCase().contains("stale deploy")),
                "expected drift/clock-skew guidance; actions=" + result.actions());
        } finally {
            app.stop();
        }
    }

    @Test
    void remoteCheckFailsAgainstUnauthorizedServer() throws Exception {
        var serverKey = OpsKeys.generateKeypair();
        var clientKey = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("ops-authorized-keys");
        Files.writeString(keysFile, serverKey.publicKey() + " server-only\n");
        Files.writeString(tmp.resolve("ops-private.key"),
            clientKey.privateKey() + "\n" + clientKey.publicKey() + "\n");

        var app = Brace.app().port(0).ops(keysFile.toString());
        app.start();
        try {
            Files.writeString(tmp.resolve(".brace"),
                "ops.local.url=http://localhost:8080\n" +
                "ops.prod.url=http://localhost:" + app.actualPort() + "\n");

            var result = CliInit.runWithRemote(tmp);
            assertFalse(result.ok());
            assertTrue(result.actions().stream().anyMatch(a -> a.toLowerCase().contains("authorized")));
        } finally {
            app.stop();
        }
    }
}
