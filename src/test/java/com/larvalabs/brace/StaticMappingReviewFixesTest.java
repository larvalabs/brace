package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 review fixes (2026-11 review): what an independent reviewer reproduced against the first
 * M3 fix. Methods other than GET/HEAD on a mapping, shared-cache headers on guarded responses
 * and on access rejections, and the directory checks at {@code start()}.
 *
 * <p>Requests go over a raw socket: the method, the path and the header spelling are the point.
 */
class StaticMappingReviewFixesTest {

    static final String SECRET = "static-mapping-review-test-secret-0123456789";
    static final String[] OTHER_METHODS = {"POST", "OPTIONS", "QUERY", "PUT", "DELETE", "PATCH"};

    static Path root, pubDir, docsDir;
    /** No after-middleware. */
    static Brace app;
    /** An app-wide after(...) that tries to make every response shared-cacheable. */
    static Brace cachingApp;
    static String cookie, cachingCookie;

    static Brace build(Path pub, Path docs) {
        Access login = Access.session("userId", "/login");
        Access admin = Access.check((req, s) -> "admin".equals(s.get("role")) ? null : Result.forbidden("admins only"));
        Brace a = Brace.app().port(0).banner(false).sessions(SECRET).strictAccessCheck()
            .staticFiles("/pub", pub.toString()).publicFiles()
            .staticFiles("/docs", docs.toString()).access(login);
        a.get("/login-as", (SessionHandler) (req, session) -> {
            session.set("userId", "42");
            return Result.text("ok");
        }).publicRoute();
        a.get("/touch", (SessionHandler) (req, session) -> {
            session.set("seen", "1");
            return Result.text("ok").header("Cache-Control", "max-age=60");
        }).publicRoute();
        // The pattern SECURITY.md recommends for a per-file decision: a route over the mapping.
        a.get("/docs/payroll.pdf", req -> Result.text("payroll-via-route")).access(login).access(admin);
        a.post("/docs/upload", req -> Result.text("uploaded")).access(login).csrf(false, "raw-socket test client");
        a.get("/pub/override.txt", req -> Result.text("override-via-route")).publicRoute();
        a.get("/users/{id}", req -> Result.text("user " + req.pathParam("id"))).access(login);
        return a;
    }

    @BeforeAll
    static void setup() throws Exception {
        root = Files.createTempDirectory("brace-m3-review").toRealPath();
        pubDir = Files.createDirectory(root.resolve("pub"));
        docsDir = Files.createDirectory(root.resolve("docs"));
        Files.writeString(pubDir.resolve("app.css"), "body{}");
        Files.writeString(pubDir.resolve("override.txt"), "override-file-bytes");
        Files.writeString(docsDir.resolve("payroll.pdf"), "payroll-file-bytes");
        Files.writeString(docsDir.resolve("upload"), "upload-file-bytes");
        Files.writeString(docsDir.resolve("report.txt"), "the-report");

        app = build(pubDir, docsDir);
        app.start();
        cookie = login(app);

        cachingApp = build(pubDir, docsDir);
        cachingApp.after((req, res) -> res
            .header("cache-control", "public, max-age=600, s-maxage=600")
            .header("CDN-Cache-Control", "max-age=600")
            .header("cloudflare-cdn-cache-control", "max-age=600")
            .header("Vercel-CDN-Cache-Control", "max-age=600")
            .header("Surrogate-Control", "max-age=600")
            .header("expires", "Thu, 01 Jan 2099 00:00:00 GMT")
            .header("PRAGMA", "cache"));
        cachingApp.start();
        cachingCookie = login(cachingApp);
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
        cachingApp.stop();
    }

    static String login(Brace target) throws Exception {
        var res = send(target, "GET", "/login-as", null);
        assertEquals(200, res.status(), res.raw());
        String setCookie = res.header("Set-Cookie");
        assertNotNull(setCookie, res.raw());
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    record Response(int status, String raw, String body) {
        List<String> headers(String name) {
            String prefix = name.toLowerCase(Locale.ROOT) + ":";
            var values = new ArrayList<String>();
            for (String line : raw.substring(0, Math.max(0, raw.indexOf("\r\n\r\n"))).split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) values.add(line.substring(prefix.length()).strip());
            }
            return values;
        }

        String header(String name) {
            var values = headers(name);
            return values.isEmpty() ? null : values.get(0);
        }
    }

    /** Send {@code method} and {@code path} byte-for-byte. */
    static Response send(Brace target, String method, String path, String cookie, String... extraHeaders) throws Exception {
        try (Socket socket = new Socket("localhost", target.actualPort())) {
            var req = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            if (!method.equals("GET") && !method.equals("HEAD")) req.append("Content-Length: 0\r\n");
            if (cookie != null) req.append("Cookie: ").append(cookie).append("\r\n");
            for (String h : extraHeaders) req.append(h).append("\r\n");
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            var buf = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buf);
            String raw = buf.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(9, 12));
            int bodyStart = raw.indexOf("\r\n\r\n");
            return new Response(status, raw, bodyStart < 0 ? "" : raw.substring(bodyStart + 4));
        }
    }

    // --- F1: only GET and HEAD are served from a mapping; a route over it is never bypassed ---

    @Test
    void theRouteGuardsItsPathForGet() throws Exception {
        var res = send(app, "GET", "/docs/payroll.pdf", cookie);
        assertEquals(403, res.status());
        assertFalse(res.raw().contains("payroll-file-bytes"));
    }

    @Test
    void anotherMethodDoesNotServeTheMappingsFileAroundARoute() throws Exception {
        for (String method : OTHER_METHODS) {
            var res = send(app, method, "/docs/payroll.pdf", cookie);
            assertEquals(405, res.status(), method + "\n" + res.raw());
            assertFalse(res.raw().contains("payroll-file-bytes"), method);
            assertFalse(res.raw().contains("payroll-via-route"), method);
            assertEquals("GET, HEAD", res.header("Allow"), method);
        }
    }

    @Test
    void aGuardedMappingServesOnlyGetAndHead() throws Exception {
        for (String method : OTHER_METHODS) {
            var res = send(app, method, "/docs/report.txt", cookie);
            assertEquals(405, res.status(), method + "\n" + res.raw());
            assertEquals("GET, HEAD", res.header("Allow"), method);
            assertFalse(res.raw().contains("the-report"), method);
            // The same answer whether or not the file exists.
            var missing = send(app, method, "/docs/nope.txt", cookie);
            assertEquals(405, missing.status(), method);
            assertEquals(res.body(), missing.body(), method);
        }
        assertEquals("the-report", send(app, "GET", "/docs/report.txt", cookie).body());
        var head = send(app, "HEAD", "/docs/report.txt", cookie);
        assertEquals(200, head.status());
        assertEquals("", head.body());
    }

    @Test
    void mappingAccessIsEnforcedForEveryMethod() throws Exception {
        var reference = send(app, "GET", "/docs/report.txt", null);
        assertEquals(302, reference.status());
        for (String method : OTHER_METHODS) {
            for (String path : new String[]{"/docs/report.txt", "/docs/nope.txt", "/docs/payroll.pdf"}) {
                var res = send(app, method, path, null);
                assertEquals(302, res.status(), method + " " + path);
                assertEquals("/login", res.header("Location"), method + " " + path);
                assertNull(res.header("Allow"), method + " " + path);
                assertEquals(reference.body(), res.body(), method + " " + path);
                assertEquals(reference.header("Cache-Control"), res.header("Cache-Control"), method + " " + path);
            }
        }
    }

    @Test
    void aPublicMappingServesOnlyGetAndHead() throws Exception {
        for (String method : OTHER_METHODS) {
            var res = send(app, method, "/pub/app.css", null);
            assertEquals(405, res.status(), method + "\n" + res.raw());
            assertEquals("GET, HEAD", res.header("Allow"), method);
            assertFalse(res.raw().contains("body{}"), method);

            var overRoute = send(app, method, "/pub/override.txt", null);
            assertEquals(405, overRoute.status(), method);
            assertEquals("GET, HEAD", overRoute.header("Allow"), method);
            assertFalse(overRoute.raw().contains("override-file-bytes"), method);
        }
        assertEquals("body{}", send(app, "GET", "/pub/app.css", null).body());
        assertEquals("override-via-route", send(app, "GET", "/pub/override.txt", null).body());
    }

    @Test
    void aPathARouteTakesUnderAnotherMethodIsNeverServedFromTheMapping() throws Exception {
        // POST /docs/upload is a route; docs/upload is also a file. GET must not fall through to it.
        var res = send(app, "GET", "/docs/upload", cookie);
        assertEquals(405, res.status(), res.raw());
        assertEquals("POST", res.header("Allow"));
        assertFalse(res.raw().contains("upload-file-bytes"));
        assertEquals(405, send(app, "HEAD", "/docs/upload", cookie).status());
        // The mapping's access still answers first.
        assertEquals(302, send(app, "GET", "/docs/upload", null).status());
        assertEquals("uploaded", send(app, "POST", "/docs/upload", cookie).body());
    }

    @Test
    void startWarnsAboutRoutesUnderAMappingPrefix() throws Exception {
        LogTap.clear();
        var a = build(pubDir, docsDir);
        a.start();
        a.stop();
        var warnings = LogTap.snapshot().stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .map(e -> String.valueOf(e.fields().get("message")))
            .filter(m -> m.contains("under its prefix"))
            .toList();
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(m -> m.contains("/docs/*")
            && m.contains("GET /docs/payroll.pdf") && m.contains("POST /docs/upload")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(m -> m.contains("/pub/*") && m.contains("GET /pub/override.txt")),
            warnings.toString());
    }

    @Test
    void bundledAssetsServeOnlyGetAndHead() throws Exception {
        assertEquals(200, send(app, "GET", "/__brace/htmx.min.js", null).status());
        var res = send(app, "POST", "/__brace/htmx.min.js", null);
        assertEquals(405, res.status());
        assertEquals("GET, HEAD", res.header("Allow"));
    }

    // --- F2: nothing under a guarded mapping is storable by a shared cache ---

    private static void assertNoSharedCacheHeaders(Response res, String expectedCacheControl, String what) {
        assertEquals(List.of(expectedCacheControl), res.headers("Cache-Control"), what + "\n" + res.raw());
        for (String name : new String[]{"Expires", "Pragma", "CDN-Cache-Control", "Surrogate-Control",
                "Cloudflare-CDN-Cache-Control", "Vercel-CDN-Cache-Control"}) {
            assertEquals(List.of(), res.headers(name), what + " " + name);
        }
    }

    @Test
    void afterMiddlewareCannotMakeAGuardedResponseSharedCacheable() throws Exception {
        var ok = send(cachingApp, "GET", "/docs/report.txt", cachingCookie);
        assertEquals(200, ok.status());
        assertNoSharedCacheHeaders(ok, "private, no-cache", "200");

        var partial = send(cachingApp, "GET", "/docs/report.txt", cachingCookie, "Range: bytes=0-2");
        assertEquals(206, partial.status());
        assertNoSharedCacheHeaders(partial, "private, no-cache", "206");

        var notModified = send(cachingApp, "GET", "/docs/report.txt", cachingCookie, "If-None-Match: " + ok.header("ETag"));
        assertEquals(304, notModified.status());
        assertNoSharedCacheHeaders(notModified, "private, no-cache", "304");

        var missing = send(cachingApp, "GET", "/docs/nope.txt", cachingCookie);
        assertEquals(404, missing.status());
        assertNoSharedCacheHeaders(missing, "no-store", "404");

        var unsatisfiable = send(cachingApp, "GET", "/docs/report.txt", cachingCookie, "Range: bytes=9999-");
        assertEquals(416, unsatisfiable.status());
        assertNoSharedCacheHeaders(unsatisfiable, "no-store", "416");

        var wrongMethod = send(cachingApp, "POST", "/docs/report.txt", cachingCookie);
        assertEquals(405, wrongMethod.status());
        assertNoSharedCacheHeaders(wrongMethod, "no-store", "405");

        var rejected = send(cachingApp, "GET", "/docs/report.txt", null);
        assertEquals(302, rejected.status());
        assertNoSharedCacheHeaders(rejected, "no-store", "302");

        // A public mapping is the app's to cache as it likes.
        assertEquals("public, max-age=600, s-maxage=600", send(cachingApp, "GET", "/pub/app.css", null).header("Cache-Control"));
    }

    @Test
    void guardedResponsesCarryCacheControlWithNoAfterMiddlewareToo() throws Exception {
        assertEquals(List.of("private, no-cache"), send(app, "GET", "/docs/report.txt", cookie).headers("Cache-Control"));
        assertEquals(List.of("no-store"), send(app, "GET", "/docs/nope.txt", cookie).headers("Cache-Control"));
        assertEquals(List.of("no-store"),
            send(app, "GET", "/docs/report.txt", cookie, "Range: bytes=9999-").headers("Cache-Control"));
        assertEquals(List.of("no-store"), send(app, "GET", "/docs/report.txt", null).headers("Cache-Control"));
        assertEquals(List.of("no-store"),
            send(app, "GET", "/docs/report.txt", null, "Accept: application/json").headers("Cache-Control"));
    }

    @Test
    void aSessionCookieNeverRidesAResponseMadePublicInLowercase() throws Exception {
        // Both routes write the session; the after-middleware spells the header "cache-control".
        // /touch sets its own "Cache-Control" first, so the two spellings were two map entries
        // and the lowercase "public" one was the one that reached the wire.
        for (String path : new String[]{"/touch", "/login-as"}) {
            var res = send(cachingApp, "GET", path, null);
            assertEquals(200, res.status(), path);
            assertNotNull(res.header("Set-Cookie"), path);
            assertEquals(1, res.headers("Cache-Control").size(), res.raw());
            String cacheControl = res.header("Cache-Control").toLowerCase(Locale.ROOT);
            assertFalse(cacheControl.contains("public"), path + ": " + cacheControl);
            assertTrue(cacheControl.contains("private"), path + ": " + cacheControl);
        }
        // A bare max-age is storable by a shared cache too: the cookie makes it private.
        var plain = send(app, "GET", "/touch", null);
        assertNotNull(plain.header("Set-Cookie"));
        assertEquals(List.of("private, max-age=60"), plain.headers("Cache-Control"));
    }

    // --- F4: an access rejection is never stored, on a route either ---

    @Test
    void aRouteAccessRejectionIsNeverCacheable() throws Exception {
        var page = send(cachingApp, "GET", "/users/5", null);
        assertEquals(302, page.status());
        assertEquals("/login", page.header("Location"));
        assertNoSharedCacheHeaders(page, "no-store", "302");
        assertVaries(page);

        var json = send(cachingApp, "GET", "/users/5", null, "Accept: application/json");
        assertEquals(401, json.status());
        assertNoSharedCacheHeaders(json, "no-store", "401 json");
        assertVaries(json);

        var htmx = send(cachingApp, "GET", "/users/5", null, "HX-Request: true");
        assertEquals(401, htmx.status());
        assertNoSharedCacheHeaders(htmx, "no-store", "401 htmx");
        assertVaries(htmx);

        // With no after-middleware as well.
        var plain = send(app, "GET", "/users/5", null);
        assertEquals(List.of("no-store"), plain.headers("Cache-Control"));
        assertVaries(plain);

        // A successful response on a guarded route keeps what the app set: that is its choice.
        var ok = send(cachingApp, "GET", "/users/5", cachingCookie);
        assertEquals(200, ok.status());
        assertEquals("public, max-age=600, s-maxage=600", ok.header("Cache-Control"));
    }

    private static void assertVaries(Response res) {
        String vary = String.join(", ", res.headers("Vary")).toLowerCase(Locale.ROOT);
        assertTrue(vary.contains("accept") && vary.contains("hx-request"), "Vary: " + vary + "\n" + res.raw());
    }

    @Test
    void devModeWarnsOnceWhenAGuardedRouteIsSentPublic() throws Exception {
        String originalMode = System.getProperty("brace.mode");
        System.setProperty("brace.mode", "dev");
        Brace dev;
        try {
            dev = build(pubDir, docsDir);
            dev.after((req, res) -> res.header("cache-control", "PUBLIC, max-age=600"));
            dev.start();
        } finally {
            if (originalMode != null) System.setProperty("brace.mode", originalMode);
            else System.clearProperty("brace.mode");
        }
        try {
            String devCookie = login(dev);
            LogTap.clear();
            assertEquals(200, send(dev, "GET", "/users/5", devCookie).status());
            assertEquals(200, send(dev, "GET", "/users/6", devCookie).status());
            // Public routes and rejections say nothing.
            assertEquals(200, send(dev, "GET", "/pub/override.txt", null).status());
            assertEquals(302, send(dev, "GET", "/users/7", null).status());
            long warnings = LogTap.snapshot().stream()
                .filter(e -> "WARN".equals(e.fields().get("level")))
                .map(e -> String.valueOf(e.fields().get("message")))
                .filter(m -> m.contains("/users/{id}") && m.contains("Cache-Control"))
                .count();
            assertEquals(1, warnings);
        } finally {
            dev.stop();
        }
    }

    // --- F6: directory checks ---

    @Test
    void twoGuardedMappingsOnOneDirectoryNeedTheSameAccess() {
        Access login = Access.session("userId", "/login");
        Access admin = Access.check((req, s) -> Result.forbidden());
        var e = assertThrows(IllegalStateException.class, () -> Brace.test().sessions(SECRET).start(a -> {
            a.staticFiles("/admin-files", docsDir.toString()).access(login, admin);
            a.staticFiles("/files", docsDir.toString()).access(login);
        }));
        assertTrue(e.getMessage().contains("/admin-files") && e.getMessage().contains("/files"), e.getMessage());

        // Nested, stricter inside: the outer mapping would serve the inner files with less.
        Path inner;
        try {
            inner = Files.createDirectories(docsDir.resolve("admin-only"));
        } catch (java.io.IOException io) {
            throw new java.io.UncheckedIOException(io);
        }
        var nested = assertThrows(IllegalStateException.class, () -> Brace.test().sessions(SECRET).start(a -> {
            a.staticFiles("/files", docsDir.toString()).access(login);
            a.staticFiles("/admin-files", inner.toString()).access(login, admin);
        }));
        assertTrue(nested.getMessage().contains("/admin-files"), nested.getMessage());
    }

    @Test
    void twoMappingsOnOneDirectoryWithTheSameAccessStart() throws Exception {
        Access login = Access.session("userId", "/login");
        Access admin = Access.check((req, s) -> null);
        var guarded = Brace.test().sessions(SECRET).start(a -> {
            a.staticFiles("/a", docsDir.toString()).access(login, admin);
            a.staticFiles("/b", docsDir.toString()).access(admin, login);
        });
        guarded.stop();
        var open = Brace.test().start(a -> {
            a.staticFiles("/a", pubDir.toString()).publicFiles();
            a.staticFiles("/b", pubDir.toString()).publicFiles();
        });
        open.stop();
    }

    @Test
    void aMappingDirectoryMustExistAtStart() throws Exception {
        Path missing = root.resolve("not-created-yet");
        var e = assertThrows(IllegalStateException.class, () -> Brace.test().start(a ->
            a.staticFiles("/later", missing.toString()).publicFiles()));
        assertTrue(e.getMessage().contains("/later") && e.getMessage().contains(missing.toString()), e.getMessage());

        Path file = Files.writeString(root.resolve("a-file"), "x");
        assertThrows(IllegalStateException.class, () -> Brace.test().start(a ->
            a.staticFiles("/file", file.toString()).publicFiles()));
    }

    @Test
    void theSamePrefixCannotBeRegisteredTwice() {
        var a = Brace.app();
        a.staticFiles("/files", pubDir.toString()).publicFiles();
        var e = assertThrows(IllegalStateException.class, () -> a.staticFiles("/files", docsDir.toString()));
        assertTrue(e.getMessage().contains("/files"), e.getMessage());
        // A trailing slash is the same prefix.
        assertThrows(IllegalStateException.class, () -> a.staticFiles("/files/", docsDir.toString()));
        a.staticFiles("/files2", docsDir.toString());
    }

    @Test
    void aDirectoryRelinkedAfterStartIntoAnotherMappingsTreeIsNotServed() throws Exception {
        Path open = Files.createDirectory(root.resolve("relink-open"));
        Path guarded = Files.createDirectory(root.resolve("relink-guarded"));
        Files.writeString(open.resolve("hello.txt"), "hello");
        Files.writeString(guarded.resolve("secret.txt"), "relinked-secret");
        var a = Brace.app().port(0).banner(false).sessions(SECRET).strictAccessCheck()
            .staticFiles("/open", open.toString()).publicFiles()
            .staticFiles("/guarded", guarded.toString()).access(Access.session("userId", "/login"));
        a.start();
        try {
            assertEquals(200, send(a, "GET", "/open/hello.txt", null).status());
            // After start, the public directory becomes a link into the guarded one.
            Files.delete(open.resolve("hello.txt"));
            Files.delete(open);
            try {
                Files.createSymbolicLink(open, guarded);
            } catch (UnsupportedOperationException | java.io.IOException e) {
                Assumptions.abort("symlinks unavailable: " + e);
            }
            var res = send(a, "GET", "/open/secret.txt", null);
            assertEquals(404, res.status(), res.raw());
            assertFalse(res.raw().contains("relinked-secret"));
        } finally {
            a.stop();
        }
    }
}
