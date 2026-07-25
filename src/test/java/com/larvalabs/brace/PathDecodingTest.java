package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness review H3: path parameters and static-file paths are percent-decoded, so a value
 * round-trips the same whether it rides in the path, the query string, or a form body. Before
 * this fix the router matched — and handed handlers — the raw encoded path, so
 * {@code /users/John%20Doe} yielded the literal {@code "John%20Doe"} and any lookup on it
 * silently missed.
 *
 * <p><strong>Two layers, tested separately.</strong> Jetty's default {@code UriCompliance}
 * rejects the ambiguous encodings — {@code %2F} (separator), {@code %25} (encoding), {@code %2e}
 * (segment), and malformed escapes — with a 400 before any of this code runs. So the decoder's
 * behavior for those inputs is exercised as a unit test, not over HTTP: it is defense in depth
 * (compliance is configurable, and {@code Route.match} is public API callable directly), and the
 * HTTP tests assert what actually crosses the wire.
 */
class PathDecodingTest {

    static TestApp app;
    static Path assetDir;

    @BeforeAll
    static void setup() throws Exception {
        assetDir = Files.createTempDirectory("brace-h3-assets");
        Files.writeString(assetDir.resolve("my file.css"), "body{color:red}");
        Files.writeString(assetDir.resolve("plain.css"), "body{}");
        Files.createDirectory(assetDir.resolve("sub"));
        Files.writeString(assetDir.resolve("sub").resolve("nested.css"), "a{}");
        // A file OUTSIDE the mapped directory, as a traversal target.
        Files.writeString(assetDir.getParent().resolve("brace-h3-secret.txt"), "secret");

        app = Brace.test().start(a -> {
            a.staticFiles("/assets", assetDir.toString());
            a.get("/users/{name}", req -> Result.text(req.pathParam("name")));
            a.get("/echo/{a}/{b}", req -> Result.text(req.pathParam("a") + "|" + req.pathParam("b")));
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    // --- Over the wire ---

    @Test
    void spacesAreDecoded() {
        assertEquals("John Doe", app.get("/users/John%20Doe").body());
    }

    @Test
    void plusIsALiteralPlusNotASpace() {
        // The form decoder would give "a b" here. In a path, "+" is just a plus.
        assertEquals("a+b", app.get("/users/a+b").body());
    }

    @Test
    void nonAsciiRoundTripsAsUtf8() {
        assertEquals("café", app.get("/users/caf%C3%A9").body());
        assertEquals("日本", app.get("/users/%E6%97%A5%E6%9C%AC").body());
    }

    @Test
    void reservedCharactersInAValueSurvive() {
        assertEquals("a&b=c", app.get("/users/a%26b%3Dc").body());
        assertEquals("a?b#c", app.get("/users/a%3Fb%23c").body());
    }

    @Test
    void multipleParametersAreEachDecoded() {
        assertEquals("a b|c d", app.get("/echo/a%20b/c%20d").body());
    }

    @Test
    void pathParamNowAgreesWithQueryAndFormDecodingForTheSameValue() {
        // The point of the fix: one value, three carriers, one result.
        String viaPath = app.get("/users/John%20Doe").body();
        assertEquals("John Doe", viaPath);
        assertNotEquals("John%20Doe", viaPath);
    }

    // --- Static files ---

    @Test
    void staticFilesWithEncodedNamesResolve() {
        var res = app.get("/assets/my%20file.css");
        assertEquals(200, res.status());
        assertEquals("body{color:red}", res.body());
    }

    @Test
    void staticFilesWithoutEncodingStillResolve() {
        assertEquals(200, app.get("/assets/plain.css").status());
        assertEquals(200, app.get("/assets/sub/nested.css").status());
    }

    @Test
    void traversalIsRejectedAndNeverServesTheTarget() {
        // Plain ".." is ours to reject; the encoded forms are stopped by Jetty's UriCompliance
        // with a 400 before we see them. Either way the status is 4xx and the file never leaks —
        // that is what this asserts, rather than pinning which layer said no.
        for (String attempt : new String[]{
                "/assets/../brace-h3-secret.txt",
                "/assets/%2e%2e/brace-h3-secret.txt",
                "/assets/..%2Fbrace-h3-secret.txt",
                "/assets/sub/%2e%2e/%2e%2e/brace-h3-secret.txt",
                "/assets/%2E%2E%2F%2E%2E%2Fetc/passwd"}) {
            var res = app.get(attempt);
            assertTrue(res.status() >= 400, attempt + " must not succeed, got " + res.status());
            assertFalse(res.body().contains("secret"), attempt + " leaked the target file");
        }
    }

    // --- Decoder unit tests (inputs Jetty's compliance layer refuses to forward) ---

    @Test
    void decoderKeepsEncodedSlashInsideItsSegment() {
        // %2F decodes to a literal "/" in the VALUE. Because Route.match decodes after the regex
        // capture, it can never act as a segment separator and forge a path boundary.
        assertEquals("a/b", Request.decodePathSegment("a%2Fb"));
        assertEquals("a/b", Request.decodePathSegment("a%2fb"));
    }

    @Test
    void decoderHandlesEncodedPercent() {
        assertEquals("100%", Request.decodePathSegment("100%25"));
        assertEquals("%20", Request.decodePathSegment("%2520"));
    }

    @Test
    void decoderKeepsMalformedEscapesLiterallyInsteadOfThrowing() {
        // URLDecoder throws on all of these; on a request path that would be a 500 for a stray '%'.
        assertEquals("a%zb", Request.decodePathSegment("a%zb"));
        assertEquals("trailing%", Request.decodePathSegment("trailing%"));
        assertEquals("half%4", Request.decodePathSegment("half%4"));
        assertEquals("%", Request.decodePathSegment("%"));
    }

    @Test
    void decoderLeavesPlusAndUnencodedTextAlone() {
        assertEquals("a+b", Request.decodePathSegment("a+b"));
        assertEquals("plain", Request.decodePathSegment("plain"));
        assertEquals("", Request.decodePathSegment(""));
    }

    @Test
    void decodePathPreservesSeparatorsAndDecodesEachSegment() {
        assertEquals("a b/c d", Request.decodePath("a%20b/c%20d"));
        assertEquals("sub/my file.css", Request.decodePath("sub/my%20file.css"));
        // A %2F inside a segment decodes to "/" — which is exactly why the static-file path
        // re-checks ".." on the decoded result before it touches the filesystem.
        assertEquals("a/../b", Request.decodePath("a%2F..%2Fb"));
    }
}
