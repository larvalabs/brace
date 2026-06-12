package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErrorStoreTest {

    static DatabaseFactory dbFactory;
    static ErrorStore errorStore;

    @BeforeAll
    static void setup() {
        dbFactory = new DatabaseFactory(
            "jdbc:h2:mem:errorstoredb;DB_CLOSE_DELAY=-1", null, null,
            List.of(Post.class));
    }

    @AfterAll
    static void teardown() {
        dbFactory.close();
    }

    @BeforeEach
    void cleanTable() {
        var db = new Database(dbFactory.openSession());
        db.beginTransaction();
        db.sql("DELETE FROM ops_errors");
        db.commitTransaction();
        db.close();
        errorStore = new ErrorStore(dbFactory, 1000);
    }

    @AfterEach
    void closeStore() {
        errorStore.close();
    }

    @Test
    void recordsCoalescePerKindWithinOneFlush() {
        // H9: a storm of one kind is a single upsert carrying the coalesced count + latest payload.
        for (int i = 1; i <= 50; i++) {
            errorStore.record("RuntimeException", "msg " + i, "GET /storm", "stack " + i, "req");
        }
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        assertEquals(50, errors.get(0).get("occurrenceCount"));
        assertEquals("msg 50", errors.get(0).get("message"), "latest payload wins");
        assertEquals("stack 50", errors.get(0).get("stackTrace"));
    }

    @Test
    void newKindsBeyondPendingCapAreDroppedKnownKindsAreNot() {
        // Fill the pending buffer with MAX_PENDING_KINDS distinct kinds, then push more.
        for (int i = 0; i < ErrorStore.MAX_PENDING_KINDS; i++) {
            errorStore.record("E" + i, "m", "GET /r" + i, "s", "req");
        }
        // A new kind past the cap is dropped...
        errorStore.record("Overflow", "m", "GET /overflow", "s", "req");
        // ...but more occurrences of a buffered kind still count.
        errorStore.record("E0", "m2", "GET /r0", "s2", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(ErrorStore.MAX_PENDING_KINDS, errors.size(), "overflow kind must not appear");
        for (var e : errors) {
            assertNotEquals("Overflow", e.get("errorType"));
            if ("E0".equals(e.get("errorType"))) {
                assertEquals(2, e.get("occurrenceCount"), "known-kind occurrences are never dropped");
            }
        }
    }

    @Test
    void recordCreatesRow() {
        errorStore.record("RuntimeException", "test error", "GET /test", "stack...", "GET /test");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        assertEquals("RuntimeException", errors.get(0).get("errorType"));
        assertEquals("test error", errors.get(0).get("message"));
        assertEquals("GET /test", errors.get(0).get("route"));
        assertEquals(1, errors.get(0).get("occurrenceCount"));
    }

    @Test
    void recordCapturesQueriesBeforeAndRequestHeaders() {
        errorStore.record("RuntimeException", "boom", "GET /test", "stack",
            "GET /test", "{\"count\":3,\"durationMs\":12.4}",
            "{\"user-agent\":\"curl/8\",\"authorization\":\"[REDACTED]\"}");
        errorStore.flush();

        var err = errorStore.list(null).get(0);
        assertEquals("{\"count\":3,\"durationMs\":12.4}", err.get("queriesBefore"));
        String headers = (String) err.get("requestHeaders");
        assertNotNull(headers);
        assertTrue(headers.contains("curl/8"));
        assertTrue(headers.contains("[REDACTED]"), "sensitive headers must already be redacted at capture");
    }

    @Test
    void legacyFiveArgRecordLeavesContextNull() {
        errorStore.record("RuntimeException", "boom", "GET /test", "stack", "GET /test");
        errorStore.flush();
        var err = errorStore.list(null).get(0);
        assertNull(err.get("queriesBefore"));
        assertNull(err.get("requestHeaders"));
    }

    @Test
    void duplicateErrorsIncrementCount() {
        errorStore.record("RuntimeException", "error 1", "GET /test", "stack1", "req1");
        errorStore.flush();
        errorStore.record("RuntimeException", "error 2", "GET /test", "stack2", "req2");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).get("occurrenceCount"));
        // Should have the latest message and stack
        assertEquals("error 2", errors.get(0).get("message"));
        assertEquals("stack2", errors.get(0).get("stackTrace"));
    }

    @Test
    void resolvedErrorsGetNewRowsOnRecurrence() {
        errorStore.record("RuntimeException", "error", "GET /test", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        long id = ((Number) errors.get(0).get("id")).longValue();

        // Resolve it
        errorStore.resolve(id);

        // Record same error again - should create new row since previous is resolved
        errorStore.record("RuntimeException", "error again", "GET /test", "stack2", "req2");
        errorStore.flush();

        var unresolved = errorStore.list(null);
        assertEquals(1, unresolved.size());
        assertEquals("error again", unresolved.get(0).get("message"));
        assertEquals(1, unresolved.get(0).get("occurrenceCount"));

        var resolved = errorStore.list("resolved");
        assertEquals(1, resolved.size());
    }

    @Test
    void listReturnsUnresolvedByDefault() {
        errorStore.record("RuntimeException", "unresolved", "GET /a", "stack", "req");
        errorStore.flush();
        errorStore.record("NullPointerException", "to-resolve", "GET /b", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(2, errors.size());

        // Find the NullPointerException and resolve it
        long npeId = 0;
        for (var e : errors) {
            if ("NullPointerException".equals(e.get("errorType"))) {
                npeId = ((Number) e.get("id")).longValue();
                break;
            }
        }
        errorStore.resolve(npeId);

        var unresolved = errorStore.list(null);
        assertEquals(1, unresolved.size());
        assertEquals("RuntimeException", unresolved.get(0).get("errorType"));

        // status=unresolved should also return unresolved
        var unresolvedExplicit = errorStore.list("unresolved");
        assertEquals(1, unresolvedExplicit.size());
    }

    @Test
    void listWithStatusResolvedReturnsResolved() {
        errorStore.record("RuntimeException", "error", "GET /test", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        long id = ((Number) errors.get(0).get("id")).longValue();
        errorStore.resolve(id);

        var resolved = errorStore.list("resolved");
        assertEquals(1, resolved.size());
        assertNotNull(resolved.get(0).get("resolvedAt"));
    }

    @Test
    void resolveSetsResolvedAt() {
        errorStore.record("RuntimeException", "error", "GET /test", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        long id = ((Number) errors.get(0).get("id")).longValue();

        var resolved = errorStore.resolve(id);
        assertNotNull(resolved);
        assertNotNull(resolved.get("resolvedAt"));
        assertEquals(id, ((Number) resolved.get("id")).longValue());
    }

    @Test
    void pruningRemovesOldestResolvedFirst() {
        var smallStore = new ErrorStore(dbFactory, 3);

        // Insert 3 errors
        smallStore.record("Error1", "msg1", "GET /a", "stack", "req");
        smallStore.flush();
        smallStore.record("Error2", "msg2", "GET /b", "stack", "req");
        smallStore.flush();
        smallStore.record("Error3", "msg3", "GET /c", "stack", "req");
        smallStore.flush();

        // Resolve the first one
        var errors = smallStore.list(null);
        assertEquals(3, errors.size());

        // Find Error1 and resolve it
        long error1Id = 0;
        for (var e : errors) {
            if ("Error1".equals(e.get("errorType"))) {
                error1Id = ((Number) e.get("id")).longValue();
                break;
            }
        }
        smallStore.resolve(error1Id);

        // Insert a 4th error - should prune the resolved one (Error1)
        smallStore.record("Error4", "msg4", "GET /d", "stack", "req");
        smallStore.flush();

        var allUnresolved = smallStore.list(null);
        var allResolved = smallStore.list("resolved");

        // Error1 was resolved and should be pruned
        assertEquals(0, allResolved.size());
        assertEquals(3, allUnresolved.size());

        // Verify Error1 is gone
        for (var e : allUnresolved) {
            assertNotEquals("Error1", e.get("errorType"));
        }
    }

    @Test
    void differentRoutesSameTypeCreateSeparateRows() {
        errorStore.record("RuntimeException", "error", "GET /a", "stack", "req");
        errorStore.flush();
        errorStore.record("RuntimeException", "error", "GET /b", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(2, errors.size());
    }

    @Test
    void listSinceReturnsOnlyErrorsAfterTimestamp() throws Exception {
        var store = new ErrorStore(dbFactory, 100);
        store.record("OldError", "old", "/old", "stack", null);
        store.flush();
        Thread.sleep(50);
        var cutoff = java.time.Instant.now();
        Thread.sleep(50);
        store.record("NewError", "new", "/new", "stack", null);
        store.flush();

        var all = store.list(null);
        assertEquals(2, all.size());

        var recent = store.list(null, cutoff);
        assertEquals(1, recent.size());
        assertEquals("NewError", recent.get(0).get("errorType"));
    }

    // --- Integration tests for ops endpoints ---

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    private static String authenticateOps(int targetPort, OpsKeys.Keypair kp) throws Exception {
        String timestamp = java.time.Instant.now().toString();
        String signature = OpsKeys.sign(timestamp, kp.privateKey());
        String body = "{\"publicKey\":\"" + kp.publicKey() + "\",\"timestamp\":\"" + timestamp + "\",\"signature\":\"" + signature + "\"}";
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + targetPort + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        String respBody = response.body();
        int start = respBody.indexOf("\"token\":\"") + 9;
        int end = respBody.indexOf("\"", start);
        return respBody.substring(start, end);
    }

    @Test
    void opsErrorsEndpointReturnsErrors(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + "\n");

        app = Brace.app().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:opserrorsdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());

        app.get("/boom", req -> { throw new RuntimeException("kaboom"); });

        app.start();
        try {
            port = app.actualPort();

            // Trigger an error
            var errorResp = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/boom")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(500, errorResp.statusCode());

            // Persist the H9-buffered error deterministically
            app.errorStore().flush();

            // Check errors endpoint
            String token = authenticateOps(port, kp);
            var response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("RuntimeException"));
            assertTrue(response.body().contains("kaboom"));
        } finally {
            app.stop();
        }
    }

    @Test
    void opsResolveErrorEndpoint(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + "\n");

        app = Brace.app().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:opsresolvedb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());

        app.get("/boom", req -> { throw new RuntimeException("kaboom"); });

        app.start();
        try {
            port = app.actualPort();

            // Trigger an error
            client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/boom")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

            app.errorStore().flush();

            // Get errors to find the ID
            String token = authenticateOps(port, kp);
            var listResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listResp.statusCode());

            // Extract ID from JSON (simple parsing)
            String body = listResp.body();
            int idStart = body.indexOf("\"id\":") + 5;
            int idEnd = body.indexOf(",", idStart);
            String idStr = body.substring(idStart, idEnd).trim();
            long errorId = Long.parseLong(idStr);

            // Resolve it
            token = authenticateOps(port, kp);
            var resolveResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors/" + errorId + "/resolve"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resolveResp.statusCode());
            assertTrue(resolveResp.body().contains("Brace Ops"));

            // Verify it's now resolved
            token = authenticateOps(port, kp);
            var resolvedResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors?status=resolved"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resolvedResp.statusCode());
            assertTrue(resolvedResp.body().contains("RuntimeException"));
        } finally {
            app.stop();
        }
    }

    @Test
    void opsErrorsRequiresAuth(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + "\n");

        app = Brace.app().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:opserrorsauthdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());

        app.start();
        try {
            port = app.actualPort();

            var response = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ops/errors")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
        } finally {
            app.stop();
        }
    }

    /**
     * End-to-end: a route that throws with a secret-bearing path and an exception
     * message that contains a long token — the stored error record must not contain
     * the raw secret in either the route/requestDetail or the message field.
     */
    @Test
    void secretBearingPathAndMessageAreRedactedInStoredError(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + "\n");

        // A 32-char hex-like secret that should trigger value-shaped redaction
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";

        app = Brace.app().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:redacttest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());

        // Route whose path contains a secret token and whose exception message does too
        app.get("/password-reset/{token}", req ->
            { throw new RuntimeException("invalid token: " + secret); });

        app.start();
        try {
            port = app.actualPort();

            // Hit the route with the secret in the path
            var errorResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/password-reset/" + secret))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(500, errorResp.statusCode());

            // Persist the H9-buffered error deterministically
            app.errorStore().flush();

            // Fetch the stored error record via /ops/errors
            String token = authenticateOps(port, kp);
            var listResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listResp.statusCode());
            String body = listResp.body();

            // The redaction placeholder must be present
            assertTrue(body.contains("[redacted]"),
                "redaction placeholder must appear in stored error record; body: " + body);

            // Parse out the specific fields we care about — route, requestDetail, and message
            // must not contain the raw secret. (The stackTrace field contains the original
            // exception message verbatim, which is expected — we redact message, not the trace.)
            String routeField = extractJsonStringField(body, "route");
            String requestDetailField = extractJsonStringField(body, "requestDetail");
            String messageField = extractJsonStringField(body, "message");

            assertFalse(routeField != null && routeField.contains(secret),
                "route field must not contain raw secret: " + routeField);
            assertFalse(requestDetailField != null && requestDetailField.contains(secret),
                "requestDetail field must not contain raw secret: " + requestDetailField);
            assertFalse(messageField != null && messageField.contains(secret),
                "message field must not contain raw secret: " + messageField);
        } finally {
            app.stop();
        }
    }

    /**
     * Minimal JSON string-field extractor — finds the value of the first occurrence
     * of {@code "fieldName":"<value>"} in the given JSON string. Returns null if the
     * field is not found.
     */
    private static String extractJsonStringField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        // Walk forward, treating \" as an escaped quote inside the value
        var sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { sb.append('"'); i += 2; continue; }
                if (next == '\\') { sb.append('\\'); i += 2; continue; }
                if (next == 'n') { sb.append('\n'); i += 2; continue; }
            }
            if (c == '"') break; // end of string value
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
