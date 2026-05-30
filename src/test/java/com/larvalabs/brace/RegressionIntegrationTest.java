package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end: a new error kind appears in /ops/regressions and can be acknowledged. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegressionIntegrationTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @TempDir
    static Path tmpDir;

    static OpsKeys.Keypair controlKey;
    static OpsKeys.Keypair readKey;

    @BeforeAll
    static void startApp() throws Exception {
        controlKey = OpsKeys.generateKeypair();
        readKey = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile,
            controlKey.publicKey() + "  ops-laptop\n" +
            readKey.publicKey() + "  scope:read  oncall-agent\n");

        var db = new DatabaseFactory(
            "jdbc:h2:mem:regrdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null, List.of());

        // warmup 0 so an error triggered right after startup is flagged immediately.
        app = Brace.app().port(0).ops(keysFile.toString()).database(db).regressionsWarmup(0);
        app.get("/boom", req -> { throw new RuntimeException("kaboom"); });
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private static String token(OpsKeys.Keypair kp) throws Exception {
        String ts = java.time.Instant.now().toString();
        String sig = OpsKeys.sign(ts, kp.privateKey());
        String body = "{\"publicKey\":\"" + kp.publicKey() + "\",\"timestamp\":\"" + ts + "\",\"signature\":\"" + sig + "\"}";
        var resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        return Json.mapper().readTree(resp.body()).get("token").asText();
    }

    private JsonNode regressions(String tok) throws Exception {
        var resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/regressions"))
                .header("Authorization", "Bearer " + tok).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        return Json.mapper().readTree(resp.body());
    }

    @Test
    @Order(1)
    void newErrorAppearsAndCanBeAcknowledged() throws Exception {
        // Trigger a brand-new error kind, then wait for the async error-record + tracker hook.
        client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/boom")).GET().build(),
            HttpResponse.BodyHandlers.discarding());

        String control = token(controlKey);
        JsonNode regs = null;
        for (int i = 0; i < 20 && (regs == null || regs.size() == 0); i++) {
            Thread.sleep(50);
            regs = regressions(control);
        }
        assertNotNull(regs);
        assertEquals(1, regs.size(), "the new error kind should be a regression");
        var r = regs.get(0);
        assertEquals("RuntimeException", r.get("errorType").asText());
        assertEquals("GET /boom", r.get("route").asText());
        assertFalse(r.get("acknowledged").asBoolean());
        long id = r.get("id").asLong();

        // Acknowledge it (control action) and confirm it flips.
        var ack = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/regressions/" + id + "/acknowledge"))
                .header("Authorization", "Bearer " + control)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ack.statusCode());
        assertTrue(regressions(control).get(0).get("acknowledged").asBoolean());
    }

    @Test
    @Order(2)
    void readTokenCanListButNotAcknowledge() throws Exception {
        String read = token(readKey);
        // Read scope can list regressions.
        assertEquals(200, client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/regressions"))
                .header("Authorization", "Bearer " + read).GET().build(),
            HttpResponse.BodyHandlers.discarding()).statusCode());
        // But acknowledging is a control action — denied.
        var ack = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/regressions/1/acknowledge"))
                .header("Authorization", "Bearer " + read)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding());
        assertEquals(401, ack.statusCode(), "read token must not acknowledge");
    }
}
