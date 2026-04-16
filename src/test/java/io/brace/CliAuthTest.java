package io.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliAuthTest {

    static Brace app;
    static int port;
    static OpsKeys.Keypair keypair;
    @TempDir static Path tmp;

    @BeforeAll
    static void start() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keysFile = tmp.resolve("authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test\n");
        // readKeyFile expects: line 0 = private key, line 1 = public key
        Files.writeString(tmp.resolve("ops-private.key"), keypair.privateKey() + "\n" + keypair.publicKey() + "\n");

        app = Brace.app().port(0).ops(keysFile.toString());
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception { app.stop(); }

    @BeforeEach
    void clearCache() throws Exception {
        Path cache = tmp.resolve("target").resolve(".brace-token");
        Files.deleteIfExists(cache);
    }

    @Test
    void freshFetchReturnsBearerToken() throws Exception {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("ops-private.key").toString(),
            "authorized-keys", "local");
        String token = CliAuth.bearer(cfg, tmp);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void cachedTokenReused() throws Exception {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("ops-private.key").toString(),
            "authorized-keys", "local");
        String first = CliAuth.bearer(cfg, tmp);
        String second = CliAuth.bearer(cfg, tmp);
        assertEquals(first, second);
    }

    @Test
    void missingKeyFileThrows() {
        var cfg = new CliConfig("http://localhost:" + port,
            tmp.resolve("does-not-exist.key").toString(),
            "authorized-keys", "local");
        var ex = assertThrows(Exception.class, () -> CliAuth.bearer(cfg, tmp));
        assertTrue(ex.getMessage().toLowerCase().contains("key"));
    }
}
