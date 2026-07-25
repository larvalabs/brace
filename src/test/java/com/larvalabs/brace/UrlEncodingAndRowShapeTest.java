package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Correctness review M6 ({@code Url.to} percent-encodes substituted values) and M7
 * ({@code db.sqlQuery}/{@code db.hql} really return {@code List<Object[]>}, including for
 * single-column selects).
 */
class UrlEncodingAndRowShapeTest {

    static TestApp app;

    @BeforeAll
    static void setup() throws Exception {
        app = Brace.test().entities(Post.class).start(a ->
            a.get("/tags/{name}", req -> Result.text(req.pathParam("name"))));
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

    // --- M6: Url.to ---

    @Test
    void plainValuesAreUnchanged() {
        assertEquals("/users/42", Url.to("/users/{id}", 42));
        assertEquals("/posts/hello-world", Url.to("/posts/{slug}", "hello-world"));
        assertEquals("/files/a.b_c~d", Url.to("/files/{n}", "a.b_c~d"));
    }

    @Test
    void slashInAValueDoesNotCreateAnExtraSegment() {
        assertEquals("/tags/a%2Fb", Url.to("/tags/{name}", "a/b"));
    }

    @Test
    void spacesAndReservedCharactersAreEncoded() {
        assertEquals("/tags/John%20Doe", Url.to("/tags/{name}", "John Doe"));
        assertEquals("/tags/a%26b", Url.to("/tags/{name}", "a&b"));
        assertEquals("/tags/a%3Fb", Url.to("/tags/{name}", "a?b"));
        assertEquals("/tags/a%23b", Url.to("/tags/{name}", "a#b"));
        // "+" must be encoded, not passed through: in a path it is a literal plus, and leaving
        // it bare would be ambiguous with the form-encoded spelling of a space.
        assertEquals("/tags/a%2Bb", Url.to("/tags/{name}", "a+b"));
    }

    @Test
    void nonAsciiIsEncodedAsUtf8() {
        assertEquals("/tags/caf%C3%A9", Url.to("/tags/{name}", "café"));
    }

    @Test
    void generatedUrlRoundTripsBackToTheOriginalValue() {
        // The whole point: Url.to and pathParam are inverses (H3 + M6).
        for (String value : new String[]{"John Doe", "a&b", "a?b", "a#b", "café", "a+b"}) {
            String url = Url.to("/tags/{name}", value);
            assertEquals(value, app.get(url).body(), "round trip failed for: " + value);
        }
    }

    /**
     * Two values encode correctly but cannot make the round trip over HTTP: Jetty's default
     * {@code UriCompliance} rejects {@code %2F} (ambiguous path separator) and {@code %25}
     * (ambiguous encoding) with a 400 before the handler runs. Encoding them as anything else
     * would be wrong — leaving {@code /} bare really would forge a segment — so the assertion is
     * on the encoder, and the transport limit is recorded rather than papered over.
     *
     * <p>Practical consequence for apps: a path parameter cannot carry a value containing
     * {@code /} or {@code %} unless the server relaxes URI compliance. Put those in the query
     * string, which has no such restriction.
     */
    @Test
    void slashAndPercentEncodeCorrectlyEvenThoughJettyRefusesToCarryThem() {
        assertEquals("/tags/a%2Fb", Url.to("/tags/{name}", "a/b"));
        assertEquals(400, app.get("/tags/a%2Fb").status());

        assertEquals("/tags/100%25", Url.to("/tags/{name}", "100%"));
        assertEquals(400, app.get("/tags/100%25").status());
    }

    @Test
    void multipleParametersAreEachEncoded() {
        assertEquals("/a/x%20y/b/z%26w", Url.to("/a/{p}/b/{q}", "x y", "z&w"));
    }

    @Test
    void literalPatternSegmentsAreNotEncoded() {
        // Pattern text is code, not data — it must pass through untouched.
        assertEquals("/a-b/c.d/42", Url.to("/a-b/c.d/{id}", 42));
    }

    // --- M7: row shape ---

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
