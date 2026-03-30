package io.brace;

import io.brace.testmodels.Post;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    static DatabaseFactory factory;

    @BeforeAll
    static void setup() {
        factory = new DatabaseFactory(
            "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", null, null,
            List.of(Post.class));
    }

    @AfterAll
    static void teardown() {
        factory.close();
    }

    @Test
    void insertAndFind() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "Hello";
            post.body = "World";
            post.createdAt = Instant.now();
            db.insert(post);
            assertNotNull(post.id);
            db.commitTransaction();

            db.beginTransaction();
            var found = db.find(Post.class, post.id);
            assertNotNull(found);
            assertEquals("Hello", found.title);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void findReturnsNullForMissing() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var found = db.find(Post.class, 99999L);
            assertNull(found);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryWithCondition() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "QueryTest_" + System.nanoTime();
            post.body = "Content";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            var results = db.query(Post.class, "title = ?", post.title);
            assertFalse(results.isEmpty());
            assertEquals(post.title, results.get(0).title);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryOne() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "QueryOneTest_" + System.nanoTime();
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            var found = db.queryOne(Post.class, "title = ?", post.title);
            assertNotNull(found);
            assertEquals(post.title, found.title);

            var missing = db.queryOne(Post.class, "title = ?", "Nonexistent_" + System.nanoTime());
            assertNull(missing);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void count() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            long count = db.count(Post.class);
            assertTrue(count >= 0);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void update() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "Before Update";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            post.title = "After Update";
            db.update(post);
            db.commitTransaction();

            db.beginTransaction();
            var found = db.find(Post.class, post.id);
            assertEquals("After Update", found.title);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void delete() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "To Delete";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            var id = post.id;
            db.commitTransaction();

            db.beginTransaction();
            db.delete(post);
            db.commitTransaction();

            db.beginTransaction();
            var found = db.find(Post.class, id);
            assertNull(found);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void findAll() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var posts = db.findAll(Post.class);
            assertNotNull(posts);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void nativeSql() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "SQL Test";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            db.sql("UPDATE posts SET title = ? WHERE id = ?", "SQL Updated", post.id);
            db.commitTransaction();

            db.beginTransaction();
            var found = db.find(Post.class, post.id);
            assertEquals("SQL Updated", found.title);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }
}
