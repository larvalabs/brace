package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** H3: {@code ?sort=} picks from an allowlist and never reaches the query text. */
class SortTest {

    static final Map<String, String> ALLOWED = Map.of("title", "title", "date", "createdAt");
    static DatabaseFactory factory;

    @BeforeAll
    static void setup() {
        factory = new DatabaseFactory("jdbc:h2:mem:sort-test;DB_CLOSE_DELAY=-1", null, null, List.of(Post.class));
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            insert(db, "banana", 2);
            insert(db, "apple", 3);
            insert(db, "cherry", 1);
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    @AfterAll
    static void teardown() {
        factory.close();
    }

    @Test
    void listedKeySortsAscendingOrDescending() {
        var asc = Sort.from(req("title"), "sort", ALLOWED, "createdAt DESC");
        assertEquals("ORDER BY title ASC", asc.toString());
        assertEquals("title", asc.key());
        assertFalse(asc.descending());
        var desc = Sort.from(req("-date"), "sort", ALLOWED, "createdAt DESC");
        assertEquals("ORDER BY createdAt DESC", desc.toString());
        assertTrue(desc.descending());
        assertTrue(desc.is("date"));
    }

    @Test
    void unlistedOrMissingKeyFallsBackToTheDefault() {
        for (String requested : new String[]{null, "", "passwordHash", "title; drop table posts", "-", "--title"}) {
            var sort = Sort.from(req(requested), "sort", ALLOWED, "createdAt DESC, id");
            assertEquals("ORDER BY createdAt DESC, id", sort.toString(), String.valueOf(requested));
            assertNull(sort.key());
        }
    }

    @Test
    void invalidAllowlistOrDefaultThrows() {
        assertThrows(IllegalArgumentException.class, () -> Sort.from(req("x"), "sort", Map.of("x", "title desc"), "id"));
        assertThrows(IllegalArgumentException.class, () -> Sort.from(req("x"), "sort", Map.of("x", "(select 1)"), "id"));
        assertThrows(IllegalArgumentException.class, () -> Sort.from(req("x"), "sort", Map.of(), "id"));
        assertThrows(IllegalArgumentException.class, () -> Sort.of("length(title)"));
        assertThrows(IllegalArgumentException.class, () -> Sort.of("title; drop"));
        assertThrows(IllegalArgumentException.class, () -> Sort.of(" "));
        assertEquals("ORDER BY title DESC NULLS LAST, id", Sort.of("title  desc nulls last ,id").toString());
    }

    @Test
    void multiFieldValueSortsEachField() {
        var sort = Sort.from(req("-both"), "sort", Map.of("both", "title, createdAt"), "id");
        assertEquals("ORDER BY title DESC, createdAt DESC", sort.toString());
    }

    @Test
    void toggleFlipsTheActiveColumn() {
        var byTitle = Sort.from(req("title"), "sort", ALLOWED, "id");
        assertEquals("-title", byTitle.toggle("title"));
        assertEquals("date", byTitle.toggle("date"));
        assertEquals("title", Sort.from(req("-title"), "sort", ALLOWED, "id").toggle("title"));
    }

    @Test
    void databaseOverloadsApplyTheSort() {
        var db = new Database(factory.openSession());
        try {
            db.beginTransaction();
            var byTitle = Sort.from(req("title"), "sort", ALLOWED, "id");
            assertEquals(List.of("apple", "banana", "cherry"), titles(db.query(Post.class, "", byTitle)));
            var byDateDesc = Sort.from(req("-date"), "sort", ALLOWED, "id");
            assertEquals(List.of("cherry", "banana", "apple"), titles(db.query(Post.class, "title IS NOT NULL", byDateDesc)));
            assertEquals(List.of("banana"), titles(db.queryPage(Post.class, null, byTitle, 1, 1)));
            var page = db.paginate(Post.class, "title <> ?", Sort.of("title DESC"), 1, 2, "zzz");
            assertEquals(List.of("cherry", "banana"), titles(page.items()));
            assertEquals(3, page.totalCount());
            assertThrows(IllegalArgumentException.class,
                () -> db.query(Post.class, "id > 0 ORDER BY id", byTitle));
            db.commitTransaction();
        } finally {
            db.close();
        }
    }

    private static List<String> titles(List<Post> posts) {
        return posts.stream().map(p -> p.title).toList();
    }

    private static Request req(String sort) {
        var query = new java.util.HashMap<String, String>();
        if (sort != null) query.put("sort", sort);
        return new Request("GET", "/posts", Map.of(), query, Map.of(), "");
    }

    private static void insert(Database db, String title, int daysAgo) {
        var post = new Post();
        post.title = title;
        post.body = "b";
        post.createdAt = Instant.now().minusSeconds(86400L * daysAgo);
        db.insert(post);
    }
}
