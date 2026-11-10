package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.larvalabs.brace.StaticMappingReviewFixesTest.send;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Final review fixes for M3 (docs/2026-11-09-security-review-todos.md): the dot-segment 404 under
 * a guarded mapping, WebSocket routes and stricter routes under a mapping's prefix, how
 * {@code Cache-Control} is read, response header names, and a mapping prefix without a slash.
 */
class StaticMappingFinalReviewTest {

    static final String SECRET = "static-mapping-final-review-secret-0123456789";

    static Path root, pubDir, docsDir;
    static Brace app;
    static String userCookie, adminCookie;
    static String startupOutput;

    @BeforeAll
    static void setup() throws Exception {
        root = Files.createTempDirectory("brace-m3-final").toRealPath();
        pubDir = Files.createDirectory(root.resolve("pub"));
        docsDir = Files.createDirectory(root.resolve("docs"));
        Files.writeString(pubDir.resolve("app.css"), "body{}");
        Files.writeString(pubDir.resolve("members.txt"), "members-file-bytes");
        Files.writeString(docsDir.resolve("report.txt"), "the-report");
        Files.writeString(docsDir.resolve("live"), "live-file-bytes");
        Files.writeString(docsDir.resolve("admin-upload"), "admin-upload-file-bytes");

        Access login = Access.session("userId", "/login");
        Access admin = Access.check((req, s) -> "admin".equals(s.get("role")) ? null : Result.forbidden("admins only"));
        app = Brace.app().port(0).banner(false).sessions(SECRET).strictAccessCheck()
            .staticFiles("/pub", pubDir.toString()).publicFiles()
            .staticFiles("/docs", docsDir.toString()).access(login);
        app.get("/login-as", (SessionHandler) (req, session) -> {
            session.set("userId", "42");
            if ("admin".equals(req.queryParam("role"))) session.set("role", "admin");
            return Result.text("ok");
        }).publicRoute();
        app.get("/touch", (SessionHandler) (req, session) -> {
            session.set("seen", String.valueOf(System.nanoTime()));
            return Result.text("ok").header("Cache-Control", req.queryParam("cc"));
        }).publicRoute();
        app.ws("/docs/live", ctx -> new Object()).access(login).access(admin);
        app.post("/docs/admin-upload", req -> Result.text("uploaded")).access(login).access(admin)
            .csrf(false, "raw-socket test client");
        app.get("/pub/members.txt", req -> Result.text("members-via-route")).access(login);
        // An app-wide after(...) that tries to make every response shared-cacheable.
        app.after((req, res) -> res.header("Cache-Control") == null
            ? res.header("Cache-Control", "public, max-age=600") : res);

        var captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            app.start();
        } finally {
            System.setOut(original);
        }
        startupOutput = captured.toString(StandardCharsets.UTF_8);
        userCookie = login("/login-as");
        adminCookie = login("/login-as?role=admin");
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    static String login(String path) throws Exception {
        var res = send(app, "GET", path, null);
        assertEquals(200, res.status(), res.raw());
        String setCookie = res.header("Set-Cookie");
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    // ---- L-I: the dot-segment 404 under a guarded mapping is no-store ----

    @Test
    void dotSegment404UnderAGuardedMappingIsNotCacheable() throws Exception {
        for (String path : new String[] {"/docs/./report.txt", "/docs/../docs/report.txt", "/x/../docs/report.txt",
                "/docs/sub/../report.txt", "/docs/..", "/./docs/report.txt", "/docs/."}) {
            for (String cookie : new String[] {null, userCookie}) {
                var res = send(app, "GET", path, cookie);
                assertEquals(404, res.status(), path + "\n" + res.raw());
                assertFalse(res.raw().contains("the-report"), path);
                assertEquals(List.of("no-store"), res.headers("Cache-Control"), path + "\n" + res.raw());
            }
        }
        // Outside any guarded mapping the app's own caching header stands.
        var elsewhere = send(app, "GET", "/pub/./app.css", null);
        assertEquals(404, elsewhere.status());
        assertEquals("public, max-age=600", elsewhere.header("Cache-Control"));
    }

    // ---- nit: a WebSocket route owns its path under a mapping ----

    @Test
    void plainGetOfAWebSocketPathNeverServesTheMappingsFile() throws Exception {
        var asUser = send(app, "GET", "/docs/live", userCookie);
        assertEquals(403, asUser.status(), "the socket route's own access decides\n" + asUser.raw());
        assertFalse(asUser.raw().contains("live-file-bytes"));

        var anonymous = send(app, "GET", "/docs/live", null);
        assertNotEquals(200, anonymous.status());
        assertFalse(anonymous.raw().contains("live-file-bytes"));

        var asAdmin = send(app, "GET", "/docs/live", adminCookie);
        assertEquals(426, asAdmin.status(), asAdmin.raw());
        assertEquals("websocket", asAdmin.header("Upgrade"));
        assertFalse(asAdmin.raw().contains("live-file-bytes"));
        assertEquals("no-store", asAdmin.header("Cache-Control"));

        var post = send(app, "POST", "/docs/live", adminCookie);
        assertEquals(405, post.status(), post.raw());
        assertEquals("GET", post.header("Allow"));
        assertEquals(403, send(app, "HEAD", "/docs/live", userCookie).status());
    }

    @Test
    void startupWarningListsWebSocketRoutesUnderAMapping() {
        assertTrue(startupOutput.contains("WS /docs/live"), startupOutput);
        assertTrue(startupOutput.contains("POST /docs/admin-upload"), startupOutput);
    }

    // ---- nit: the 405 does not disclose a stricter route's methods ----

    @Test
    void aStricterRoutesMethodsAreNotDisclosedToACallerWhoHasNotPassedItsAccess() throws Exception {
        for (String method : new String[] {"GET", "HEAD", "PUT", "DELETE", "OPTIONS"}) {
            var asUser = send(app, method, "/docs/admin-upload", userCookie);
            assertEquals(403, asUser.status(), method + "\n" + asUser.raw());
            assertNull(asUser.header("Allow"), method + ": no Allow for a caller the route refuses");
            assertFalse(asUser.raw().contains("admin-upload-file-bytes"), method);
            assertEquals("no-store", asUser.header("Cache-Control"), method);

            var asAdmin = send(app, method, "/docs/admin-upload", adminCookie);
            assertEquals(405, asAdmin.status(), method + "\n" + asAdmin.raw());
            assertEquals("POST", asAdmin.header("Allow"), method);
            assertFalse(asAdmin.raw().contains("admin-upload-file-bytes"), method);
        }
        // A login-guarded route under a PUBLIC mapping: an anonymous caller gets the login
        // rejection, not "405 Allow: GET, HEAD".
        var anonymous = send(app, "POST", "/pub/members.txt", null);
        assertNotEquals(405, anonymous.status(), anonymous.raw());
        assertNull(anonymous.header("Allow"), anonymous.raw());
        assertFalse(anonymous.raw().contains("members-file-bytes"));
        var loggedIn = send(app, "POST", "/pub/members.txt", userCookie);
        assertEquals(405, loggedIn.status());
        assertEquals("GET, HEAD", loggedIn.header("Allow"));
    }

    // ---- nit: Cache-Control is parsed, not pattern-matched ----

    @Test
    void sessionCookieResponsesArePrivateHoweverTheDirectivesAreQuoted() throws Exception {
        for (String sent : new String[] {"max-age=60, x=\"no-store\"", "no-cache=\"private\", max-age=60",
                "x=\"private, no-store\", s-maxage=60", "max-age=60, ext=\"a\\\"private\""}) {
            var res = send(app, "GET", "/touch?cc=" + java.net.URLEncoder.encode(sent, StandardCharsets.UTF_8), null);
            assertEquals(200, res.status(), res.raw());
            assertNotNull(res.header("Set-Cookie"), res.raw());
            String cacheControl = res.header("Cache-Control");
            assertTrue(BraceHandler.cacheDirectiveNames(cacheControl).contains("private"),
                sent + " went out with a session cookie as: " + cacheControl);
        }
    }

    @Test
    void privateForSessionCookieRewrite() {
        assertEquals("private", BraceHandler.privateCacheControl(null));
        assertEquals("private", BraceHandler.privateCacheControl(" "));
        assertEquals("private", BraceHandler.privateCacheControl("public"));
        assertEquals("private", BraceHandler.privateCacheControl("Public , PUBLIC"));
        assertEquals("private, max-age=60", BraceHandler.privateCacheControl("public, max-age=60"));
        assertEquals("private, max-age=60", BraceHandler.privateCacheControl("max-age=60"));
        assertEquals("private, s-maxage=600", BraceHandler.privateCacheControl("s-maxage=600"));
        // Already not storable by a shared cache: left exactly as written.
        assertEquals("no-store", BraceHandler.privateCacheControl("no-store"));
        assertEquals("private, max-age=1", BraceHandler.privateCacheControl("private, max-age=1"));
        assertEquals("max-age=1, PRIVATE", BraceHandler.privateCacheControl("max-age=1, PRIVATE"));
        assertEquals("No-Store, max-age=0", BraceHandler.privateCacheControl("No-Store, max-age=0"));
        // The word inside a quoted argument is not the directive.
        assertEquals("private, max-age=60, x=\"no-store\"", BraceHandler.privateCacheControl("max-age=60, x=\"no-store\""));
        assertEquals("private, no-cache=\"private\", max-age=60",
            BraceHandler.privateCacheControl("no-cache=\"private\", max-age=60"));
        assertEquals("private, x=\"a, public, b\", max-age=5", BraceHandler.privateCacheControl("x=\"a, public, b\", max-age=5"));
        // private="field" keeps only that field out of shared caches, not the response.
        assertEquals("private, private=\"set-cookie\", max-age=5",
            BraceHandler.privateCacheControl("private=\"set-cookie\", max-age=5"));
        // An unterminated quoted-string: nothing in it is a directive.
        assertEquals("private, max-age=60, x=\"private, no-store",
            BraceHandler.privateCacheControl("max-age=60, x=\"private, no-store"));
        assertEquals("private, max-age=60", BraceHandler.privateCacheControl("public,,max-age=60,"));
    }

    @Test
    void cacheDirectiveNames() {
        assertEquals(List.of("public", "max-age", "s-maxage"), BraceHandler.cacheDirectiveNames("Public, MAX-AGE=5 , s-maxage = 6"));
        assertEquals(List.of("x", "y"), BraceHandler.cacheDirectiveNames("x=\"public, s-maxage=1\", y"));
        assertEquals(List.of("x"), BraceHandler.cacheDirectiveNames("x=\"a\\\", public\""));
        assertEquals(List.of(), BraceHandler.cacheDirectiveNames(null));
        assertEquals(List.of(), BraceHandler.cacheDirectiveNames(" , "));
    }

    // ---- nit: response header names are tokens, compared as ASCII ----

    @Test
    void headerNamesMustBeTokens() {
        for (String bad : new String[] {"", " ", "X A", "X:A", "X\r\nY", "X\tA", "X-é", "Key", "İd",
                "Content-Type ", "(x)", "a,b", "a;b", "a=b", "\"x\"", "x/y", "x@y", "x[0]", "x{}"}) {
            var ex = assertThrows(IllegalArgumentException.class, () -> Result.text("x").header(bad, "v"),
                "accepted: '" + bad + "'");
            assertTrue(ex.getMessage().contains("header name"), ex.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Result.text("x").header(null, "v"));
        var ok = Result.text("x").header("X-Custom_1.a~!#$%&'*+^`|", "v").header("cache-control", "no-store");
        assertEquals("v", ok.header("x-custom_1.A~!#$%&'*+^`|"));
        assertEquals("no-store", ok.header("Cache-Control"));
    }

    @Test
    void headerNamesCompareAsAsciiOnly() {
        var result = Result.text("x").header("K", "kelvin?").header("I", "dotted?");
        assertEquals("kelvin?", result.header("k"));
        assertNull(result.header("K"), "U+212A KELVIN SIGN is not K");
        assertNull(result.header("ı"), "dotless i is not I");
        // A name put straight into the map (bypassing header(...)) is still a different header.
        int before = result.headers().size();
        result.headers().put("K", "other");
        assertEquals("kelvin?", result.header("K"));
        assertEquals(before + 1, result.headers().size());
    }

    // ---- nit: a mapping prefix must start with "/" ----

    @Test
    void aStaticPrefixWithoutALeadingSlashIsRefusedAtRegistration() {
        for (String bad : new String[] {"d", "", "assets/", "./x", " /x"}) {
            var ex = assertThrows(IllegalArgumentException.class,
                () -> Brace.app().staticFiles(bad, pubDir.toString()), "accepted: '" + bad + "'");
            assertTrue(ex.getMessage().contains("must start with \"/\""), ex.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Brace.app().staticFiles(null, pubDir.toString()));
        assertThrows(IllegalArgumentException.class,
            () -> Brace.app().staticFiles("d", pubDir.toString(), StaticFiles.UNTRUSTED));
        Brace.app().staticFiles("/d", pubDir.toString()).publicFiles();
    }
}
