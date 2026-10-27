package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H3 (2026-10 security review): request data concatenated into a where-fragment or raw query is
 * HQL/SQL injection. The Database a handler receives checks each query string against the
 * request's values; a hit throws in dev mode and tests, and logs in production.
 */
class QueryTaintTest {

    static DatabaseFactory factory;
    static long secretId;

    @BeforeAll
    static void setup() {
        factory = new DatabaseFactory("jdbc:h2:mem:query-taint;DB_CLOSE_DELAY=-1", null, null, List.of(Post.class));
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            secretId = insert(db, "secret draft");
            insert(db, "public post");
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @AfterAll
    static void teardown() {
        factory.close();
    }

    // --- The review's payloads ---

    @Test
    void whereFragmentInjectionThrows() {
        String q = "zzz' or 1=1 or title='";
        var req = get(Map.of(), Map.of("q", q));
        // Unchecked, the payload returns every row: the bug the check exists for.
        assertEquals(2, run(null, false, db -> db.query(Post.class, "title = '" + q + "' AND id = ?", 99L)).size());
        var ex = assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "title = '" + q + "' AND id = ?", 99L)));
        assertTrue(ex.getMessage().contains("query parameter \"q\""), ex.getMessage());
        assertTrue(ex.getMessage().contains("db.query"), ex.getMessage());
        assertFalse(ex.getMessage().contains(q), "the message must not echo the value: " + ex.getMessage());
    }

    @Test
    void orderByOracleThrows() {
        String sort = "case when (select substring(x.title,1,1) from Post x where x.id=" + secretId + ")='s' then id else -id end";
        var req = get(Map.of(), Map.of("sort", sort));
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "title IS NOT NULL ORDER BY " + sort)));
    }

    @Test
    void sqlIsCheckedToo() {
        String q = "x' OR '1'='1";
        var req = get(Map.of(), Map.of("q", q));
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.sqlQuery("SELECT id FROM posts WHERE title = '" + q + "'")));
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.hql("SELECT id FROM Post WHERE title = '" + q + "'")));
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.count(Post.class, "title = '" + q + "'")));
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.queryOne(Post.class, "title = '" + q + "'")));
    }

    // --- Each request source ---

    @Test
    void concatenatedSortWordThrows() {
        // A bare word is harmless in a literal but picks the column after ORDER BY.
        var req = get(Map.of(), Map.of("sort", "title"));
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "id > 0 ORDER BY " + "title")));
        var desc = get(Map.of(), Map.of("sort", "-title"));
        assertThrows(IllegalArgumentException.class,
            () -> run(desc, true, db -> db.paginate(Post.class, "ORDER BY title DESC", 1, 10)));
    }

    @Test
    void concatenatedPathParamThrows() {
        String slug = "a' OR 'x'='x";
        var req = new Request("GET", "/posts/x", Map.of("slug", slug), Map.of(), Map.of(), "");
        var ex = assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "title = '" + slug + "'")));
        assertTrue(ex.getMessage().contains("path parameter \"slug\""), ex.getMessage());
    }

    @Test
    void concatenatedFormFieldThrows() {
        var req = new Request("POST", "/search", Map.of(), Map.of(),
            Map.of("Content-Type", "application/x-www-form-urlencoded"), "q=it%27s+here");
        var ex = assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "title = 'it''s here' OR title = '" + "it's here" + "'")));
        assertTrue(ex.getMessage().contains("form field \"q\""), ex.getMessage());
    }

    // --- Review fixes ---

    @Test
    void sortOfRequestTextIsCheckedButSortFromIsNot() {
        var req = get(Map.of(), Map.of("sort", "body DESC"));
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "", Sort.of(req.queryParam("sort")))));
        // Sort.from only lets the request pick a key, so its order is never flagged.
        var byTitle = get(Map.of(), Map.of("sort", "title"));
        var sort = Sort.from(byTitle, "sort", Map.of("title", "title"), "createdAt DESC");
        assertDoesNotThrow(() -> run(byTitle, true, db -> db.query(Post.class, "", sort)));
        assertDoesNotThrow(() -> run(byTitle, true, db -> db.paginate(Post.class, "id > ?", sort, 1, 10, 0L)));
    }

    @Test
    void aliasedOrderByWordThrows() {
        var req = get(Map.of(), Map.of("sort", "title"));
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.hql("SELECT p.id FROM Post p ORDER BY p." + "title")));
    }

    @Test
    void everyValueOfARepeatedFormFieldIsChecked() {
        var req = new Request("POST", "/search", Map.of(), Map.of(),
            Map.of("Content-Type", "Application/X-WWW-Form-Urlencoded; charset=UTF-8"),
            "tag=x%27+OR+%271%27%3D%271&tag=safe");
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "title = '" + "x' OR '1'='1" + "'")));
    }

    @Test
    void malformedFormBodyIsSkippedNotA500() throws Exception {
        var app = Brace.test().entities(Post.class).start(a -> {
            a.defaultAccess(Access.none());
            a.post("/hook", (DbHandler) (r, db) -> Json.of(r.body().length())).csrf(false);
        });
        try {
            var resp = app.request("POST", "/hook")
                .body("{\"x\":\"50% off\"}", "application/x-www-form-urlencoded").send();
            assertEquals(200, resp.status(), resp.body());
        } finally {
            app.stop();
        }
    }

    @Test
    void checkedValuesAreCapped() {
        var params = new HashMap<String, String>();
        for (int i = 0; i < 5000; i++) params.put("p" + i, "value-" + i + " x");
        assertEquals(1000, QueryTaint.of(get(Map.of(), params), true).size());
    }

    // --- No false positives ---

    @Test
    void boundParamsPass() {
        String q = "zzz' or 1=1 or title='";
        var req = get(Map.of("id", "x' or 1=1"), Map.of("q", q, "sort", "title"));
        assertEquals(0, run(req, true, db -> db.query(Post.class, "title = ?", q)).size());
        assertEquals(0L, (long) run(req, true, db -> db.count(Post.class, "title = ? AND id > ?", q, 0L)));
    }

    @Test
    void shortNumericAndCoincidentValuesPass() {
        var params = new HashMap<String, String>();
        params.put("id", "1");             // short
        params.put("page", "12345");       // numeric
        params.put("status", "published"); // a word that a hard-coded literal happens to contain
        params.put("dir", "desc");         // a sort keyword
        params.put("q", "secret");         // a word appearing outside ORDER BY
        var req = get(Map.of(), params);
        assertDoesNotThrow(() -> run(req, true, db ->
            db.query(Post.class, "title <> 'published' AND title <> 'secret' AND id <> 12345 ORDER BY createdAt DESC")));
    }

    @Test
    void productionLogsInsteadOfThrowing() {
        String q = "zzz' or 1=1 or title='";
        var req = get(Map.of(), Map.of("q", q));
        assertEquals(2, run(req, false, db -> db.query(Post.class, "title = '" + q + "' AND id = ?", 99L)).size());
    }

    // --- Wiring: the handler's Database is checked, strictly under Brace.test() ---

    @Test
    void handlerDatabaseIsCheckedUnderTestApp() throws Exception {
        var app = Brace.test().entities(Post.class).start(a -> {
            a.defaultAccess(Access.none());
            a.get("/search", (DbHandler) (r, db) ->
                Json.of(db.query(Post.class, "title = '" + r.queryParam("q") + "'").size()));
            a.get("/safe", (DbHandler) (r, db) ->
                Json.of(db.query(Post.class, "title = ?", r.queryParam("q")).size()));
        });
        try {
            assertEquals(500, app.get("/search?q=x%27+OR+%271%27%3D%271").status());
            assertEquals(200, app.get("/safe?q=x%27+OR+%271%27%3D%271").status());
        } finally {
            app.stop();
        }
    }

    // --- helpers ---

    private static Request get(Map<String, String> pathParams, Map<String, String> query) {
        return new Request("GET", "/posts", pathParams, query, Map.of(), "");
    }

    private static <R> R run(Request req, boolean strict, java.util.function.Function<Database, R> body) {
        var db = new Database(factory.openSession());
        try {
            if (req != null) db.checkQueriesAgainst(req, strict);
            db.beginTransaction();
            R result = body.apply(db);
            db.commitTransaction();
            return result;
        } finally {
            db.close();
        }
    }

    private static long insert(Database db, String title) {
        var post = new Post();
        post.title = title;
        post.body = "b";
        post.createdAt = Instant.now();
        db.insert(post);
        return post.id;
    }
}
