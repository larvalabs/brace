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
    void sortFromWorksWithHqlAndSqlQueries() {
        // Joins and projections: the request picks an allowlisted key, appended after the check.
        var byTitle = get(Map.of(), Map.of("sort", "-title"));
        var hqlSort = Sort.from(byTitle, "sort", Map.of("title", "p.title"), "p.id");
        List<Object[]> rows = run(byTitle, true, db -> db.hql("SELECT p.id, p.title FROM Post p", hqlSort));
        assertEquals("secret draft", rows.get(0)[1]);
        assertEquals("public post", rows.get(1)[1]);
        var sqlSort = Sort.from(byTitle, "sort", Map.of("title", "title"), "id");
        List<Object[]> sqlRows = run(byTitle, true, db -> db.sqlQuery("SELECT id, title FROM posts", sqlSort));
        assertEquals("secret draft", sqlRows.get(0)[1]);
        // The query text itself is still checked, and so is Sort.of text from the request.
        var evil = get(Map.of(), Map.of("q", "title DESC"));
        assertThrows(IllegalArgumentException.class, () -> run(evil, true,
            db -> db.hql("SELECT p.id FROM Post p ORDER BY " + evil.queryParam("q"), (Sort) null)));
        assertThrows(IllegalArgumentException.class, () -> run(evil, true,
            db -> db.hql("SELECT p.id FROM Post p", Sort.of(evil.queryParam("q")))));
        // A query with its own ORDER BY and a Sort is ambiguous.
        assertThrows(IllegalArgumentException.class, () -> run(byTitle, true,
            db -> db.hql("SELECT p.id FROM Post p ORDER BY p.id", hqlSort)));
    }

    @Test
    void sortOfEvasionsThatNormalizationHidesStillThrow() {
        // L15: Sort.of normalizes (trim, collapse spaces, upper-case keywords), so these request
        // values no longer appeared verbatim in the ORDER BY text.
        for (String value : List.of("title ", "title desc", "title,id", "title  desc", " title")) {
            var req = get(Map.of(), Map.of("sort", value));
            assertThrows(IllegalArgumentException.class,
                () -> run(req, true, db -> db.query(Post.class, "", Sort.of(req.queryParam("sort")))),
                "should flag: [" + value + "]");
            assertThrows(IllegalArgumentException.class,
                () -> run(req, true, db -> db.hql("SELECT p.id FROM Post p", Sort.of(req.queryParam("sort")))),
                "hql should flag: [" + value + "]");
        }
        // A fixed order written in code that merely shares nothing with the request is fine.
        var unrelated = get(Map.of(), Map.of("sort", "body desc"));
        assertDoesNotThrow(() -> run(unrelated, true, db -> db.query(Post.class, "", Sort.of("createdAt DESC, id"))));
    }

    @Test
    void paddedQueryStringDoesNotStarveTheFormBody() {
        var query = new HashMap<String, String>();
        for (int i = 0; i < 1500; i++) query.put("pad" + i, "padding-value-" + i);
        String q = "x' OR '1'='1";
        var req = new Request("POST", "/search", Map.of(), query,
            Map.of("Content-Type", "application/x-www-form-urlencoded"),
            "q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "title = '" + q + "'")));
    }

    @Test
    void overCapIsMarkedUnchecked() {
        var small = get(Map.of(), Map.of("q", "hello"));
        assertFalse(QueryTaint.of(small, true).overCap());
        assertTrue(QueryTaint.of(paddedForm(""), false).overCap());
        assertTrue(QueryTaint.of(paddedForm(""), true).overCap());
    }

    // --- L15 final review: padding within one source ---

    @Test
    void paddingTheQueryStringNoLongerHidesASortValue() throws Exception {
        // The reviewer's request: 1,000 padding params then sort=body. The query string is bounded
        // by Jetty's 8 KB request line, so it is recorded whole and the sort value is checked.
        var app = Brace.test().entities(Post.class).start(a -> {
            a.defaultAccess(Access.none());
            a.get("/q", (DbHandler) (r, db) -> Json.of(db.query(Post.class, "", Sort.of(r.queryParam("sort"))).size()));
        });
        try {
            assertEquals(500, app.get("/q?sort=body").status(), "unpadded: flagged");
            String padded = "/q?" + "a=abc&".repeat(1000) + "sort=body";
            assertEquals(500, app.get(padded).status(), "padded: still flagged");
        } finally {
            app.stop();
        }
    }

    @Test
    void queryAndPathValuesHaveNoCountCap() {
        var params = new HashMap<String, String>();
        for (int i = 0; i < 2000; i++) params.put("p" + i, "value-" + i + " x");
        var taint = QueryTaint.of(get(Map.of(), params), true);
        assertEquals(2000, taint.size());
        assertFalse(taint.overCap());
    }

    @Test
    void anOverCapRequestThrowsInStrictModeWhenItReachesAQuery() {
        // A form padded past the cap cannot be checked, so under Brace.test() and dev mode any
        // where-fragment, raw query or Sort.of it reaches fails instead of passing unchecked.
        var req = paddedForm("&q=" + java.net.URLEncoder.encode("x' OR '1'='1", java.nio.charset.StandardCharsets.UTF_8));
        var ex = assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "title = ?", "x")));
        assertTrue(ex.getMessage().contains("over"), ex.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> run(req, true, db -> db.query(Post.class, "", Sort.of("createdAt DESC"))));
        // No query text to check: nothing to refuse.
        assertDoesNotThrow(() -> run(req, true, db -> db.findAll(Post.class)));
        // Production runs it and logs.
        assertDoesNotThrow(() -> run(req, false, db -> db.query(Post.class, "title = ?", "x")));
    }

    @Test
    void productionLogsOverCapOncePerCallSite() {
        var req = paddedForm("");
        run(req, false, db -> db.query(Post.class, "title = ?", "a"));
        run(req, false, db -> db.count(Post.class, "title = ?", "b"));
        long sites = QueryTaint.warnedKeys().stream().filter(k -> k.contains("QueryTaintTest") && k.endsWith("|(over cap)")).count();
        assertTrue(sites >= 2, "each call site logs its own over-cap warning: " + QueryTaint.warnedKeys());
    }

    private static Request paddedForm(String extra) {
        var body = new StringBuilder();
        for (int i = 0; i < 1500; i++) body.append(i == 0 ? "" : "&").append("pad").append(i).append("=padding-value-").append(i);
        body.append(extra);
        return new Request("POST", "/search", Map.of(), Map.of(),
            Map.of("Content-Type", "application/x-www-form-urlencoded"), body.toString());
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
    void checkedFormValuesAreCapped() {
        assertEquals(1000, QueryTaint.of(paddedForm(""), true).size());
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
