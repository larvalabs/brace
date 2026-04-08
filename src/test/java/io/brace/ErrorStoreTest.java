package io.brace;

import io.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
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

    @Test
    void recordCreatesRow() {
        errorStore.record("RuntimeException", "test error", "GET /test", "stack...", "GET /test");

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        assertEquals("RuntimeException", errors.get(0).get("errorType"));
        assertEquals("test error", errors.get(0).get("message"));
        assertEquals("GET /test", errors.get(0).get("route"));
        assertEquals(1, errors.get(0).get("occurrenceCount"));
    }

    @Test
    void duplicateErrorsIncrementCount() {
        errorStore.record("RuntimeException", "error 1", "GET /test", "stack1", "req1");
        errorStore.record("RuntimeException", "error 2", "GET /test", "stack2", "req2");

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

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        long id = ((Number) errors.get(0).get("id")).longValue();

        // Resolve it
        errorStore.resolve(id);

        // Record same error again - should create new row since previous is resolved
        errorStore.record("RuntimeException", "error again", "GET /test", "stack2", "req2");

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
        errorStore.record("NullPointerException", "to-resolve", "GET /b", "stack", "req");

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
        smallStore.record("Error2", "msg2", "GET /b", "stack", "req");
        smallStore.record("Error3", "msg3", "GET /c", "stack", "req");

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
        errorStore.record("RuntimeException", "error", "GET /b", "stack", "req");

        var errors = errorStore.list(null);
        assertEquals(2, errors.size());
    }

    // --- Integration tests for ops endpoints ---

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @Test
    void opsErrorsEndpointReturnsErrors() throws Exception {
        app = Brace.app().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:opserrorsdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops("test-key");

        app.get("/boom", req -> { throw new RuntimeException("kaboom"); });

        app.start();
        try {
            port = app.actualPort();

            // Trigger an error
            var errorResp = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/boom")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(500, errorResp.statusCode());

            // Wait for virtual thread to persist
            Thread.sleep(200);

            // Check errors endpoint
            var response = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ops/errors?key=test-key")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("RuntimeException"));
            assertTrue(response.body().contains("kaboom"));
        } finally {
            app.stop();
        }
    }

    @Test
    void opsResolveErrorEndpoint() throws Exception {
        app = Brace.app().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:opsresolvedb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops("test-key");

        app.get("/boom", req -> { throw new RuntimeException("kaboom"); });

        app.start();
        try {
            port = app.actualPort();

            // Trigger an error
            client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/boom")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

            Thread.sleep(200);

            // Get errors to find the ID
            var listResp = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ops/errors?key=test-key")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listResp.statusCode());

            // Extract ID from JSON (simple parsing)
            String body = listResp.body();
            int idStart = body.indexOf("\"id\":") + 5;
            int idEnd = body.indexOf(",", idStart);
            String idStr = body.substring(idStart, idEnd).trim();
            long errorId = Long.parseLong(idStr);

            // Resolve it
            var resolveResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors/" + errorId + "/resolve?key=test-key"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resolveResp.statusCode());
            assertTrue(resolveResp.body().contains("Brace Ops"));

            // Verify it's now resolved
            var resolvedResp = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ops/errors?key=test-key&status=resolved")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resolvedResp.statusCode());
            assertTrue(resolvedResp.body().contains("RuntimeException"));
        } finally {
            app.stop();
        }
    }

    @Test
    void opsErrorsRequiresAuth() throws Exception {
        app = Brace.app().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:opserrorsauthdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops("test-key");

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
}
