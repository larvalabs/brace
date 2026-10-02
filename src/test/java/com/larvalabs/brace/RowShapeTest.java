package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Correctness review M7: {@code db.sqlQuery}/{@code db.hql} really return {@code List<Object[]>},
 * including for single-column selects. (M6, {@code Url.to} encoding, landed on main in 0.1.9 and
 * is covered by {@code UrlTest}.)
 */
class RowShapeTest {

    static TestApp app;

    @BeforeAll
    static void setup() throws Exception {
        app = Brace.test().entities(Post.class).start(a -> {});
        app.withDb(db -> {
            var p = new Post();
            p.title = "Ada";
            p.body = "first";
            db.insert(p);
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    @Test
    void singleColumnSqlQueryReturnsRowsNotBareScalars() {
        var rows = app.db().sqlQuery("SELECT title FROM posts");
        assertFalse(rows.isEmpty());
        // Before the fix this threw ClassCastException in the caller's loop, because Hibernate
        // hands back a List<String> for a one-column select while the signature promises rows.
        for (Object[] row : rows) {
            assertEquals("Ada", row[0]);
        }
    }

    @Test
    void multiColumnSqlQueryIsUnchanged() {
        var rows = app.db().sqlQuery("SELECT title, body FROM posts");
        assertEquals("Ada", rows.get(0)[0]);
        assertEquals("first", rows.get(0)[1]);
    }

    @Test
    void singleColumnHqlReturnsRowsNotBareScalars() {
        var rows = app.db().hql("SELECT p.title FROM Post p");
        assertFalse(rows.isEmpty());
        for (Object[] row : rows) {
            assertEquals("Ada", row[0]);
        }
    }

    @Test
    void multiColumnHqlIsUnchanged() {
        var rows = app.db().hql("SELECT p.title, p.body FROM Post p");
        assertEquals("Ada", rows.get(0)[0]);
        assertEquals("first", rows.get(0)[1]);
    }

    @Test
    void emptyResultIsAnEmptyList() {
        assertEquals(0, app.db().sqlQuery("SELECT title FROM posts WHERE title = ?", "nobody").size());
    }
}
