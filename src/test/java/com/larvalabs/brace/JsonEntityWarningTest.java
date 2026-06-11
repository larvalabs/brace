package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import com.larvalabs.brace.testmodels.User;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JsonEntityWarningTest {

    @BeforeEach
    void reset() {
        LogTap.clear();
        Json.warnedClassesForTesting().clear();
    }

    @Test
    void warnsWhenSerializingAnEntity() {
        var user = new User();
        user.email = "test@example.com";
        user.passwordHash = "secret";

        Json.of(user);

        var snap = LogTap.snapshot();
        var warnLog = snap.stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .findFirst();

        assertTrue(warnLog.isPresent(), "Should have logged a WARN");
        var message = (String) warnLog.get().fields().get("message");
        assertTrue(message.contains("Json.of()"), "Message should mention Json.of()");
        assertTrue(message.contains("JPA entity"), "Message should mention JPA entity");
        assertTrue(message.contains("User"), "Message should mention the class name");
        assertTrue(message.contains("passwordHash"), "Message should mention the leaked field");
    }

    @Test
    void warnsOncePerEntityClass() {
        var user1 = new User();
        var user2 = new User();

        Json.of(user1);
        Json.of(user2);

        var warns = LogTap.snapshot().stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .count();

        assertEquals(1, warns, "Should warn only once per entity class");
    }

    @Test
    void warnsWhenSerializingCollectionOfEntities() {
        var user1 = new User();
        var user2 = new User();

        Json.of(List.of(user1, user2));

        var snap = LogTap.snapshot();
        var warnLog = snap.stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .findFirst();

        assertTrue(warnLog.isPresent(), "Should warn for a collection of entities");
        var message = (String) warnLog.get().fields().get("message");
        assertTrue(message.contains("User"), "Message should mention the entity class");
    }

    @Test
    void doesNotWarnWhenSerializingARecord() {
        var record = new SimpleDto("test", "value");

        Json.of(record);

        var warns = LogTap.snapshot().stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .count();

        assertEquals(0, warns, "Should not warn for non-entity records");
    }

    @Test
    void doesNotWarnWhenSerializingAString() {
        Json.of("hello");

        var warns = LogTap.snapshot().stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .count();

        assertEquals(0, warns, "Should not warn for strings");
    }

    @Test
    void serializationOutputUnchanged() {
        var post = new Post();
        post.id = 1L;
        post.title = "Test Post";
        post.body = "Test body";

        var json = Json.of(post);

        // Verify the JSON body contains the expected fields
        var body = json.body();
        assertTrue(body.contains("\"title\""), "JSON should contain title field");
        assertTrue(body.contains("\"body\""), "JSON should contain body field");
    }

    @Test
    void warnsOnEmptyCollection() {
        // Empty collection: should not warn (no element to check)
        Json.of(List.of());

        var warns = LogTap.snapshot().stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .count();

        assertEquals(0, warns, "Should not warn for an empty collection");
    }

    private record SimpleDto(String field1, String field2) {}
}
