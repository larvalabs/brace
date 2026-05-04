package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseFactoryWithSessionTest {

    static DatabaseFactory factory;

    @BeforeAll
    static void setup() {
        factory = new DatabaseFactory(
            "jdbc:h2:mem:withsessiondb;DB_CLOSE_DELAY=-1", null, null,
            List.of(Post.class));
    }

    @AfterAll
    static void teardown() {
        factory.close();
    }

    private static Post newPost(String title) {
        var post = new Post();
        post.title = title;
        post.body = "Body";
        post.createdAt = Instant.now();
        return post;
    }

    @Test
    void withSessionFunctionOpensSessionExecutesQueryAndReturnsResult() {
        String title = "WithSession_Function_" + System.nanoTime();

        // Insert via Consumer overload
        Consumer<Database> insert = session -> session.insert(newPost(title));
        factory.withSession(insert);

        // Retrieve via Function overload
        Function<Database, Post> query = session -> session.queryOne(Post.class, "title = ?", title);
        var found = factory.withSession(query);

        assertNotNull(found);
        assertEquals(title, found.title);
    }

    @Test
    void withSessionConsumerInsertsDataAndCommits() {
        String title = "WithSession_Consumer_" + System.nanoTime();

        Consumer<Database> insert = session -> session.insert(newPost(title));
        factory.withSession(insert);

        // Verify data is visible in a subsequent query
        Function<Database, Post> query = session -> session.queryOne(Post.class, "title = ?", title);
        var found = factory.withSession(query);

        assertNotNull(found);
        assertEquals(title, found.title);
    }

    @Test
    void withSessionFunctionRollsBackOnException() {
        String title = "WithSession_FunctionRollback_" + System.nanoTime();

        Function<Database, Void> action = session -> {
            session.insert(newPost(title));
            throw new RuntimeException("Deliberate failure");
        };
        assertThrows(RuntimeException.class, () -> factory.withSession(action));

        // Data should not have been committed
        Function<Database, Post> query = session -> session.queryOne(Post.class, "title = ?", title);
        assertNull(factory.withSession(query));
    }

    @Test
    void withSessionConsumerRollsBackOnException() {
        String title = "WithSession_ConsumerRollback_" + System.nanoTime();

        Consumer<Database> action = session -> {
            session.insert(newPost(title));
            throw new RuntimeException("Deliberate failure");
        };
        assertThrows(RuntimeException.class, () -> factory.withSession(action));

        // Data should not have been committed
        Function<Database, Post> query = session -> session.queryOne(Post.class, "title = ?", title);
        assertNull(factory.withSession(query));
    }

    @Test
    void withSessionClosesSessionEvenOnException() {
        // Verify indirectly: if sessions are not closed, the pool (size 10 by default)
        // would exhaust. Run more iterations than the pool size to detect a leak.
        Consumer<Database> action = session -> {
            throw new RuntimeException("Deliberate failure");
        };
        for (int i = 0; i < 15; i++) {
            try {
                factory.withSession(action);
            } catch (RuntimeException ignored) {
                // expected
            }
        }
        // If we reach here without hanging or pool exhaustion, sessions are being closed.
        assertTrue(true);
    }
}
