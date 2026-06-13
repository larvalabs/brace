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
    void findOr404ReturnsRowWhenPresent() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "Found";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            var found = db.findOr404(Post.class, post.id);
            assertEquals("Found", found.title);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void findOr404ThrowsNotFoundForMissing() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            assertThrows(NotFoundException.class, () -> db.findOr404(Post.class, 99999L));
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryOneOr404ReturnsRowAndThrowsForMissing() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "QueryOne404";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            var found = db.queryOneOr404(Post.class, "title = ?", "QueryOne404");
            assertEquals(post.id, found.id);
            assertThrows(NotFoundException.class,
                () -> db.queryOneOr404(Post.class, "title = ?", "no-such-title"));
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

    // --- M4: ORDER BY in the where-fragment is supported semantics, and queryPage ---

    /** Insert {@code n} posts titled {@code prefix_0 … prefix_(n-1)} and return the prefix. */
    private String seedPosts(Database db, int n) {
        String prefix = "OrderTest_" + System.nanoTime();
        db.beginTransaction();
        for (int i = 0; i < n; i++) {
            var post = new Post();
            post.title = prefix + "_" + i;
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
        }
        db.commitTransaction();
        return prefix;
    }

    @Test
    void queryHonorsOrderByInWhereFragment() {
        // ORDER BY inside the where-fragment is documented, pinned behavior — not an accident
        // of concatenation. Descending by id must return newest-inserted first.
        var db = new Database(factory.openSession());
        try {
            String prefix = seedPosts(db, 3);

            db.beginTransaction();
            var desc = db.query(Post.class, "title LIKE ? ORDER BY id DESC", prefix + "%");
            assertEquals(3, desc.size());
            assertTrue(desc.get(0).id > desc.get(1).id);
            assertTrue(desc.get(1).id > desc.get(2).id);
            assertEquals(prefix + "_2", desc.get(0).title);

            var asc = db.query(Post.class, "title LIKE ? ORDER BY id ASC", prefix + "%");
            assertEquals(prefix + "_0", asc.get(0).title);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryPageReturnsCorrectSlice() {
        var db = new Database(factory.openSession());
        try {
            String prefix = seedPosts(db, 5);

            db.beginTransaction();
            // Page 2 of size 2, ordered ascending: rows 2 and 3 (0-based).
            var page = db.queryPage(Post.class, "title LIKE ? ORDER BY id ASC", 2, 2, prefix + "%");
            assertEquals(2, page.size());
            assertEquals(prefix + "_2", page.get(0).title);
            assertEquals(prefix + "_3", page.get(1).title);

            // Last page is short.
            var last = db.queryPage(Post.class, "title LIKE ? ORDER BY id ASC", 2, 4, prefix + "%");
            assertEquals(1, last.size());
            assertEquals(prefix + "_4", last.get(0).title);

            // Offset past the end is empty, not an error.
            var past = db.queryPage(Post.class, "title LIKE ? ORDER BY id ASC", 2, 10, prefix + "%");
            assertTrue(past.isEmpty());

            // The page-total idiom: db.count with the same where-condition (sans ORDER BY).
            assertEquals(5, db.count(Post.class, "title LIKE ?", prefix + "%"));
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryPageWithOrderByDescSlices() {
        // ORDER BY DESC combined with the slice: page 1 of size 2 = the two newest rows.
        var db = new Database(factory.openSession());
        try {
            String prefix = seedPosts(db, 4);

            db.beginTransaction();
            var page = db.queryPage(Post.class, "title LIKE ? ORDER BY id DESC", 2, 0, prefix + "%");
            assertEquals(2, page.size());
            assertEquals(prefix + "_3", page.get(0).title);
            assertEquals(prefix + "_2", page.get(1).title);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryPageValidatesLimitAndOffset() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            assertThrows(IllegalArgumentException.class,
                    () -> db.queryPage(Post.class, "title = ?", 0, 0, "x"));
            assertThrows(IllegalArgumentException.class,
                    () -> db.queryPage(Post.class, "title = ?", -1, 0, "x"));
            assertThrows(IllegalArgumentException.class,
                    () -> db.queryPage(Post.class, "title = ?", 10, -1, "x"));
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void queryPageInstrumentsQueryCount() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            int before = db.queryCount();
            db.queryPage(Post.class, "title = ? ORDER BY id ASC", 5, 0, "no-such-title");
            assertEquals(before + 1, db.queryCount());
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void hqlAggregateProjection() {
        // The documented aggregate idiom: one SELECT AVG/COUNT round-trip instead of
        // fetching rows and loop-summing in Java.
        var db = new Database(factory.openSession());
        try {
            String prefix = seedPosts(db, 3);

            db.beginTransaction();
            var rows = db.hql(
                "SELECT AVG(p.id), COUNT(p) FROM Post p WHERE p.title LIKE ?", prefix + "%");
            assertEquals(1, rows.size());
            Object[] row = rows.get(0);
            assertTrue(((Number) row[0]).doubleValue() > 0);
            assertEquals(3L, ((Number) row[1]).longValue());
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
    void existsBy() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "ExistsByTest_" + System.nanoTime();
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            assertTrue(db.existsBy(Post.class, "title", post.title));
            assertFalse(db.existsBy(Post.class, "title", "Nonexistent_" + System.nanoTime()));
            assertThrows(IllegalArgumentException.class,
                () -> db.existsBy(Post.class, "title = 'x' OR 1=1", "y"));
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
    void existsTrueAndFalse() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "Exists Check";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            assertTrue(db.exists(Post.class, "title = ?", "Exists Check"));
            assertFalse(db.exists(Post.class, "title = ?", "no-such-title"));
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @Test
    void existsWithMultipleParams() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var post = new Post();
            post.title = "Multi Exists";
            post.body = "Multi Body";
            post.createdAt = Instant.now();
            db.insert(post);
            db.commitTransaction();

            db.beginTransaction();
            assertTrue(db.exists(Post.class, "title = ? AND body = ?", "Multi Exists", "Multi Body"));
            assertFalse(db.exists(Post.class, "title = ? AND body = ?", "Multi Exists", "Wrong Body"));
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
    void convertSimplePlaceholders() {
        var db = new Database(null);
        assertEquals("a = ?1 AND b = ?2", db.convertPositionalParams("a = ? AND b = ?"));
    }

    @Test
    void convertSkipsStringLiterals() {
        // A ? inside a quoted literal is data, not a placeholder, so it must not be renumbered.
        var db = new Database(null);
        assertEquals("title = 'a?b' AND x = ?1", db.convertPositionalParams("title = 'a?b' AND x = ?"));
    }

    @Test
    void convertHandlesEscapedQuoteInLiteral() {
        var db = new Database(null);
        assertEquals("t = 'a''?''b' AND x = ?1", db.convertPositionalParams("t = 'a''?''b' AND x = ?"));
    }

    @Test
    void convertSkipsLineComment() {
        var db = new Database(null);
        assertEquals("x = ?1 -- not a ? placeholder\nAND y = ?2",
            db.convertPositionalParams("x = ? -- not a ? placeholder\nAND y = ?"));
    }

    @Test
    void convertSkipsBlockComment() {
        var db = new Database(null);
        assertEquals("x = ?1 /* keep this ? */ AND y = ?2",
            db.convertPositionalParams("x = ? /* keep this ? */ AND y = ?"));
    }

    @Test
    void convertDoubleQuestionEscapesToLiteral() {
        // ?? escapes to a single literal ? — e.g. so a Postgres JSONB ?| operator survives.
        var db = new Database(null);
        assertEquals("data ?| ?1", db.convertPositionalParams("data ??| ?"));
    }

    // --- L3: dollar-quoted strings, double-quoted identifiers, E-strings ---

    @Test
    void convertSkipsDollarQuoteBody() {
        // A ? inside $$ ... $$ must not be renumbered; the param after it must still be ?1.
        var db = new Database(null);
        assertEquals("$$ hello ? world $$ AND x = ?1",
                db.convertPositionalParams("$$ hello ? world $$ AND x = ?"));
    }

    @Test
    void convertSkipsTaggedDollarQuoteBody() {
        // $body$...$body$ — tag is a non-empty identifier.
        var db = new Database(null);
        assertEquals("$body$ a?b $body$ AND x = ?1",
                db.convertPositionalParams("$body$ a?b $body$ AND x = ?"));
    }

    @Test
    void convertDollarQuoteParamAfterIsNumberedCorrectly() {
        // Two params outside dollar-quote must number sequentially.
        var db = new Database(null);
        assertEquals("x = ?1 AND $$ ? $$ AND y = ?2",
                db.convertPositionalParams("x = ? AND $$ ? $$ AND y = ?"));
    }

    @Test
    void convertSkipsDoubleQuotedIdentifier() {
        // A ? inside a double-quoted identifier must not be renumbered.
        var db = new Database(null);
        assertEquals("\"od?d\" = ?1", db.convertPositionalParams("\"od?d\" = ?"));
    }

    @Test
    void convertDoubleQuotedIdentifierWithEscapedQuote() {
        // "" inside a double-quoted identifier is an escaped " — the identifier doesn't close.
        var db = new Database(null);
        assertEquals("\"a\"\"?\"\"b\" = ?1", db.convertPositionalParams("\"a\"\"?\"\"b\" = ?"));
    }

    @Test
    void convertEStringBackslashEscapeHandled() {
        // E'a\'?b' — the \' does not close the string, so ? is inside the literal.
        var db = new Database(null);
        assertEquals("E'a\\'?b' AND x = ?1",
                db.convertPositionalParams("E'a\\'?b' AND x = ?"));
    }

    @Test
    void convertEStringLowercasePrefix() {
        // e'...' (lowercase) must also be recognised as an E-string.
        var db = new Database(null);
        assertEquals("e'a\\'?b' AND x = ?1",
                db.convertPositionalParams("e'a\\'?b' AND x = ?"));
    }

    @Test
    void convertBareENotConfusedWithEString() {
        // An identifier starting with E that is NOT followed by ' is just a regular identifier.
        var db = new Database(null);
        assertEquals("EMAIL = ?1", db.convertPositionalParams("EMAIL = ?"));
    }

    @Test
    void convertDollarInIdentifierNotMisparsed() {
        // a$b — the $ is inside a word token, not a dollar-quote opener.
        var db = new Database(null);
        assertEquals("a$b = ?1", db.convertPositionalParams("a$b = ?"));
    }

    @Test
    void convertDollarOnePositionalNotMisparsed() {
        // $1 as used in raw Postgres SQL is NOT a dollar-quote opener (closing $ is absent
        // right after the digit). Falls through to verbatim copy.
        var db = new Database(null);
        // The $1 at position 0 — tag scan finds '1' (digit is valid in tag) then hits end-of-string,
        // so no closing $ is found within the tag scan → falls through as verbatim $.
        // After the $, '1' is consumed normally.  Result: "$1" is preserved as-is.
        assertEquals("$1 AND x = ?1", db.convertPositionalParams("$1 AND x = ?"));
    }

    // --- L4: field identifier validation ---

    @Test
    void fieldIdentifierSimpleNameAccepted() {
        // Simple names like "id", "title", "createdAt" must pass without throwing.
        Database.requireValidFieldIdentifier("id");
        Database.requireValidFieldIdentifier("title");
        Database.requireValidFieldIdentifier("createdAt");
        Database.requireValidFieldIdentifier("_count");
        Database.requireValidFieldIdentifier("$value");
    }

    @Test
    void fieldIdentifierDottedPathAccepted() {
        // Embedded paths like "address.city" must pass without throwing.
        Database.requireValidFieldIdentifier("address.city");
        Database.requireValidFieldIdentifier("author.profile.name");
    }

    @Test
    void fieldIdentifierWithSemicolonRejected() {
        // "id; drop" is an injection attempt — must throw.
        assertThrows(IllegalArgumentException.class,
                () -> Database.requireValidFieldIdentifier("id; drop"));
    }

    @Test
    void fieldIdentifierWithParenRejected() {
        // "id) OR (1=1" is an injection attempt — must throw.
        assertThrows(IllegalArgumentException.class,
                () -> Database.requireValidFieldIdentifier("id) OR (1=1"));
    }

    @Test
    void fieldIdentifierSpaceRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Database.requireValidFieldIdentifier("my field"));
    }

    @Test
    void fieldIdentifierNullRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Database.requireValidFieldIdentifier(null));
    }

    @Test
    void findAllByRejectsInjectionField() {
        var db = new Database(factory.openSession());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> db.findAllBy(Post.class, "id) OR (1=1", "x"));
        } finally {
            db.close();
        }
    }

    @Test
    void queryInRejectsInjectionField() {
        var db = new Database(factory.openSession());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> db.queryIn(Post.class, "id; drop", List.of(1L)));
        } finally {
            db.close();
        }
    }

    @Test
    void fieldIdentifierDottedPathValidatesOk() {
        // Dotted paths must pass requireValidFieldIdentifier without throwing — the validator
        // is called before Hibernate sees the HQL, so we test it in isolation.
        Database.requireValidFieldIdentifier("address.city");
        Database.requireValidFieldIdentifier("author.profile.name");
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
