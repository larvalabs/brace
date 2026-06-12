package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each {@code Brace.test()} builder must get its own H2 database by default (L10):
 * data inserted through one TestApp must not be visible through another. Before the
 * per-builder counter, both apps here would have shared {@code jdbc:h2:mem:test} and
 * the second assertion would see the first app's row.
 */
class TestDbIsolationTest {

    static TestApp appA;
    static TestApp appB;

    @BeforeAll
    static void setup() throws Exception {
        appA = Brace.test()
            .entities(Post.class)
            .start(app -> app.get("/posts", (DbHandler) (req, db) -> Json.of(db.findAll(Post.class))));
        appB = Brace.test()
            .entities(Post.class)
            .start(app -> app.get("/posts", (DbHandler) (req, db) -> Json.of(db.findAll(Post.class))));
    }

    @AfterAll
    static void teardown() throws Exception {
        appA.stop();
        appB.stop();
    }

    @Test
    void insertsInOneAppAreInvisibleInTheOther() {
        appA.withDb(db -> {
            var post = new Post();
            post.title = "only in A";
            post.body = "body";
            post.createdAt = Instant.now();
            db.insert(post);
        });

        assertEquals(1, appA.get("/posts").json().size(), "app A sees its own insert");
        assertEquals("[]", appB.get("/posts").body(), "app B's database is isolated from app A's");
    }
}
