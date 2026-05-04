package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
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
    void queryInWithMultipleIds() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post1 = new Post();
            post1.title = "QueryIn_1_" + System.nanoTime();
            post1.body = "Body1";
            post1.createdAt = Instant.now();
            db.insert(post1);
            var post2 = new Post();
            post2.title = "QueryIn_2_" + System.nanoTime();
            post2.body = "Body2";
            post2.createdAt = Instant.now();
            db.insert(post2);
            db.commitTransaction();

            db.beginTransaction();
            var results = db.queryIn(Post.class, "id", List.of(post1.id, post2.id));
            assertEquals(2, results.size());
            assertTrue(results.stream().anyMatch(p -> p.id.equals(post1.id)));
            assertTrue(results.stream().anyMatch(p -> p.id.equals(post2.id)));
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryInWithEmptyListReturnsEmpty() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var results = db.queryIn(Post.class, "id", List.of());
            assertTrue(results.isEmpty());
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryInWithSingleValue() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "QueryInSingle_" + System.nanoTime();
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            var results = db.queryIn(Post.class, "id", List.of(post.id));
            assertEquals(1, results.size());
            assertEquals(post.id, results.get(0).id);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryInByNonIdField() {
        var db = new Database(factory.openSession());
        try {
            long ts = System.nanoTime();
            db.beginTransaction();
            var post1 = new Post();
            post1.title = "QueryInField_A_" + ts;
            post1.body = "Body";
            post1.createdAt = Instant.now();
            db.insert(post1);
            var post2 = new Post();
            post2.title = "QueryInField_B_" + ts;
            post2.body = "Body";
            post2.createdAt = Instant.now();
            db.insert(post2);
            db.commitTransaction();

            db.beginTransaction();
            var results = db.queryIn(Post.class, "title", List.of(post1.title, post2.title));
            assertEquals(2, results.size());
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryInWithNoMatchesReturnsEmpty() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var results = db.queryIn(Post.class, "id", List.of(-1L, -2L, -3L));
            assertTrue(results.isEmpty());
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
    void queryCountTracksOperations() {
        var db = new Database(factory.openSession());
        try {
            assertEquals(0, db.queryCount());
            assertEquals(0, db.queryDurationUs());

            db.beginTransaction();
            var post = new Post();
            post.title = "CountTest_" + System.nanoTime();
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);       // 1
            db.find(Post.class, post.id); // 2
            db.query(Post.class, "title = ?", post.title); // 3
            db.count(Post.class);  // 4
            db.commitTransaction();

            assertEquals(4, db.queryCount());
            assertTrue(db.queryDurationUs() > 0);
        } finally {
            db.close();
        }
    }

    @Test
    void queryCountSurvivesClose() {
        var db = new Database(factory.openSession());
        db.beginTransaction();
        db.count(Post.class);
        db.commitTransaction();
        db.close();

        // Counters readable after close
        assertEquals(1, db.queryCount());
        assertTrue(db.queryDurationUs() >= 0);
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
