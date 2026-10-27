package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M14 review: binding the CLI's login signature to the request's Host is no binding when the
 * relaying client chooses the Host (an app reachable directly, or X-Forwarded-Host passed through
 * a trusted proxy). With {@code app.opsOrigins(...)} the signature must name one of those origins
 * exactly, whatever Host or X-Forwarded-Host the request carries; prod mode requires it.
 */
class OpsOriginPinTest {

    static Brace app;
    static int port;
    static OpsKeys.Keypair keypair;
    @TempDir static Path tmp;

    @BeforeAll
    static void start() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keys = tmp.resolve("authorized-keys");
        Files.writeString(keys, keypair.publicKey() + " test\n");
        app = Brace.app().port(0).ops(keys.toString()).trustedProxies("127.0.0.1", "::1")
            .opsOrigins("https://app.example.com");
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        app.stop();
    }

    private static String body(String origin) {
        String ts = java.time.Instant.now().toString();
        byte[] n = new byte[16];
        new java.security.SecureRandom().nextBytes(n);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        String sig = OpsKeys.sign(OpsKeys.v3AuthMessage(Brace.normalizeOrigin(origin), keypair.publicKey(), ts, nonce),
            keypair.privateKey());
        return "{\"v\":\"3\",\"origin\":\"" + origin + "\",\"publicKey\":\"" + keypair.publicKey()
            + "\",\"timestamp\":\"" + ts + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + sig + "\"}";
    }

    /** POST /ops/auth with the Host (and X-Forwarded-Host) a relaying client would choose. */
    private static int post(String host, String forwardedHost, String body) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            var req = new StringBuilder("POST /ops/auth HTTP/1.1\r\nHost: " + host + "\r\n");
            if (forwardedHost != null) req.append("X-Forwarded-Host: ").append(forwardedHost).append("\r\n");
            req.append("Content-Type: application/json\r\nContent-Length: ").append(bytes.length)
                .append("\r\nConnection: close\r\n\r\n");
            var out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
            var status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    @Test
    void aSignatureForAnotherOriginIsRefusedWhateverTheHost() throws Exception {
        assertEquals(401, post("attacker.example", null, body("https://attacker.example")));
        assertEquals(401, post("app.example.com", "attacker.example", body("https://attacker.example")));
        assertEquals(401, post("127.0.0.1", "attacker.example, app.example.com", body("https://attacker.example")));
    }

    @Test
    void theConfiguredOriginWorksBehindAHostRewritingProxy() throws Exception {
        assertEquals(200, post("127.0.0.1:" + port, null, body("https://app.example.com")));
        assertEquals(200, post("app.example.com", null, body("https://App.Example.com:443/")));
    }

    @Test
    void schemeAndPortAreCompared() throws Exception {
        assertEquals(401, post("app.example.com", null, body("http://app.example.com")));
        assertEquals(401, post("app.example.com", null, body("https://app.example.com:8443")));
    }

    @Test
    void anOriginWithMoreThanSchemeHostAndPortIsRefused() throws Exception {
        for (String bad : new String[] {"https://app.example.com@evil.example", "https://app.example.com/x",
                "https://app.example.com?x=1", "https://app.example.com#x", "ftp://app.example.com"}) {
            assertEquals(401, post("app.example.com", null, body(bad)), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Brace.app().opsOrigins("https://app.example.com/path"));
        assertThrows(IllegalArgumentException.class, () -> Brace.app().opsOrigins("app.example.com"));
        assertDoesNotThrow(() -> Brace.app().opsOrigins((String) null).opsOrigins("https://a.example, https://b.example"));
    }

    @Test
    void prodModeRequiresOpsOrigins() throws Exception {
        Path keys = tmp.resolve("prod-keys");
        Files.writeString(keys, keypair.publicKey() + " test\n");
        System.setProperty("brace.mode", "prod");
        try {
            var unpinned = Brace.app().port(0).opsSecret("ops-origin-pin-test-secret-at-least-32-chars").ops(keys.toString());
            var e = assertThrows(IllegalStateException.class, unpinned::start);
            assertTrue(e.getMessage().contains("opsOrigins"), e.getMessage());
            unpinned.stop();
        } finally {
            System.clearProperty("brace.mode");
        }
    }

    @Test
    void theCliRefusesAnOpsUrlThatIsntAnOrigin() {
        for (String bad : new String[] {"https://app.example.com@evil.example", "https://app.example.com/some/path",
                "https://app.example.com?x=", "app.example.com"}) {
            var cfg = new CliConfig(bad, "k", "a", "prod", Map.of());
            assertThrows(IllegalArgumentException.class, () -> CliAuth.originOf(cfg), bad);
        }
        assertEquals("https://app.example.com", CliAuth.originOf(new CliConfig("https://app.example.com/", "k", "a", "prod", Map.of())));
    }
}
