package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 (2026-11 review): static mappings are inside deny-by-default. A mapping declares
 * {@code .publicFiles()} or {@code .access(...)}; {@code start()} refuses one that declares
 * neither; the requirement is bound to the mapping and runs before the file is looked up.
 *
 * <p>Requests go over a raw socket so no client library normalizes the path or follows the
 * login redirect.
 */
class StaticMappingAccessTest {

    static final String SECRET = "static-mapping-access-test-secret-0123456789";

    static Brace app;
    static Path pubDir, filesDir, uploadsDir;
    static OpsKeys.Keypair keypair;
    static String cookie;

    @BeforeAll
    static void setup() throws Exception {
        Path root = Files.createTempDirectory("brace-m3");
        pubDir = Files.createDirectory(root.resolve("pub"));
        filesDir = Files.createDirectory(root.resolve("files"));
        uploadsDir = Files.createDirectory(root.resolve("uploads"));
        Files.writeString(pubDir.resolve("app.css"), "body{}");
        Files.createDirectory(filesDir.resolve("private"));
        Files.writeString(filesDir.resolve("private/secret.txt"), "top-secret");
        Files.writeString(filesDir.resolve("report.txt"), "the-report");
        Files.writeString(filesDir.resolve(".env"), "dotfile");
        Files.writeString(uploadsDir.resolve("page.html"), "<script>alert(1)</script>");
        Files.writeString(uploadsDir.resolve("pic.png"), "png-bytes");

        keypair = OpsKeys.generateKeypair();
        Path keys = root.resolve("authorized-keys");
        Files.writeString(keys, keypair.publicKey() + " test\n");

        Access login = Access.session("userId", "/login");
        app = Brace.app().port(0).banner(false).sessions(SECRET).ops(keys.toString()).strictAccessCheck()
            .staticFiles("/pub", pubDir.toString()).publicFiles()
            .staticFiles("/files", filesDir.toString()).access(login)
            .staticFiles("/uploads", uploadsDir.toString(), StaticFiles.UNTRUSTED).access(login);
        app.get("/login-as", (SessionHandler) (req, session) -> {
            session.set("userId", "42");
            return Result.text("ok");
        }).publicRoute();
        app.start();

        var res = get("/login-as", null);
        assertEquals(200, res.status());
        String setCookie = res.header("Set-Cookie");
        assertNotNull(setCookie, res.raw());
        cookie = setCookie.substring(0, setCookie.indexOf(';'));
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    record Response(int status, String raw, String body) {
        String header(String name) {
            String prefix = name.toLowerCase(Locale.ROOT) + ":";
            for (String line : raw.substring(0, Math.max(0, raw.indexOf("\r\n\r\n"))).split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) return line.substring(prefix.length()).strip();
            }
            return null;
        }
    }

    private static Response get(String path, String cookie, String... extraHeaders) throws Exception {
        return get(app, path, cookie, extraHeaders);
    }

    /** Send {@code path} byte-for-byte as the request target. */
    private static Response get(Brace target, String path, String cookie, String... extraHeaders) throws Exception {
        try (Socket socket = new Socket("localhost", target.actualPort())) {
            OutputStream out = socket.getOutputStream();
            var req = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            if (cookie != null) req.append("Cookie: ").append(cookie).append("\r\n");
            for (String h : extraHeaders) req.append(h).append("\r\n");
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            var buf = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buf);
            String raw = buf.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(9, 12));
            int bodyStart = raw.indexOf("\r\n\r\n");
            return new Response(status, raw, bodyStart < 0 ? "" : raw.substring(bodyStart + 4));
        }
    }

    // --- Startup check ---

    @Test
    void startRefusesAMappingWithNoDeclaration() {
        var plain = Brace.app().port(0).banner(false);
        plain.staticFiles("/assets", pubDir.toString());
        var e = assertThrows(IllegalStateException.class, plain::start);
        assertTrue(e.getMessage().contains("/assets"), e.getMessage());
        assertTrue(e.getMessage().contains(".publicFiles()"), e.getMessage());
        assertTrue(e.getMessage().contains(".access("), e.getMessage());
    }

    @Test
    void braceTestRefusesItToo() {
        var e = assertThrows(IllegalStateException.class, () -> Brace.test().start(a -> {
            a.staticFiles("/assets", pubDir.toString());
        }));
        assertTrue(e.getMessage().contains("/assets"), e.getMessage());
    }

    @Test
    void defaultAccessDoesNotCoverAMapping() {
        var e = assertThrows(IllegalStateException.class, () -> Brace.test().sessions(SECRET).start(a -> {
            a.defaultAccess(Access.session("userId", "/login"));
            a.staticFiles("/assets", pubDir.toString());
        }));
        assertTrue(e.getMessage().contains("defaultAccess"), e.getMessage());

        assertThrows(IllegalStateException.class, () -> Brace.test().start(a -> {
            a.defaultAccess(Access.none());
            a.staticFiles("/assets", pubDir.toString());
        }));
    }

    @Test
    void everyUndeclaredMappingIsListed() {
        var e = assertThrows(IllegalStateException.class, () -> Brace.test().start(a -> {
            a.staticFiles("/one", pubDir.toString());
            a.staticFiles("/two", pubDir.toString()).publicFiles();
            a.staticFiles("/three", pubDir.toString());
        }));
        assertTrue(e.getMessage().contains("/one") && e.getMessage().contains("/three"), e.getMessage());
        assertFalse(e.getMessage().contains("/two"), e.getMessage());
    }

    @Test
    void contradictoryAndInvalidDeclarationsAreRefused() {
        var a = Brace.app();
        var both = a.staticFiles("/a", pubDir.toString());
        both.publicFiles();
        assertThrows(IllegalStateException.class, () -> both.access(Access.checkRequest(req -> null)));
        var other = a.staticFiles("/b", pubDir.toString());
        other.access(Access.checkRequest(req -> null));
        assertThrows(IllegalStateException.class, other::publicFiles);

        var none = Brace.app().staticFiles("/c", pubDir.toString());
        var e = assertThrows(IllegalArgumentException.class, () -> none.access(Access.none()));
        assertTrue(e.getMessage().contains("publicFiles"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> Brace.app().staticFiles("/d", pubDir.toString()).access(null));
    }

    @Test
    void sessionRequirementNeedsSessions() {
        var e = assertThrows(IllegalStateException.class, () -> Brace.test().start(a ->
            a.staticFiles("/files", filesDir.toString()).access(Access.session("userId", "/login"))));
        assertTrue(e.getMessage().contains("sessions"), e.getMessage());
    }

    @Test
    void aGuardedDirectoryCannotAlsoBePublic() {
        // /assets/private/secret.txt would reach the guarded file around its guard.
        var e = assertThrows(IllegalStateException.class, () -> Brace.test().start(a -> {
            a.staticFiles("/assets", filesDir.toString()).publicFiles();
            a.staticFiles("/private", filesDir.resolve("private").toString())
                .access(Access.checkRequest(req -> Result.forbidden()));
        }));
        assertTrue(e.getMessage().contains("/assets") && e.getMessage().contains("/private"), e.getMessage());
    }

    @Test
    void declarationsAreFrozenAfterStart() throws Exception {
        var a = Brace.app().port(0).banner(false);
        var config = a.staticFiles("/pub", pubDir.toString());
        config.publicFiles();
        a.start();
        try {
            assertThrows(IllegalStateException.class, () -> a.staticFiles("/late", pubDir.toString()));
            assertThrows(IllegalStateException.class, config::publicFiles);
            assertThrows(IllegalStateException.class, () -> config.access(Access.checkRequest(req -> null)));
        } finally {
            a.stop();
        }
    }

    // --- Enforcement ---

    @Test
    void guardedMappingRejectsAnonymousAndServesAuthenticated() throws Exception {
        var anon = get("/files/report.txt", null);
        assertEquals(302, anon.status());
        assertEquals("/login", anon.header("Location"));
        assertFalse(anon.body().contains("the-report"));

        var authed = get("/files/report.txt", cookie);
        assertEquals(200, authed.status());
        assertEquals("the-report", authed.body());

        // A forged cookie is an empty session.
        assertEquals(302, get("/files/report.txt", "brace_session=AAAA").status());
    }

    @Test
    void publicMappingIsServedToEveryone() throws Exception {
        var res = get("/pub/app.css", null);
        assertEquals(200, res.status());
        assertEquals("body{}", res.body());
    }

    @Test
    void everyPathVariantIsGuardedOrRefused() throws Exception {
        // M1: not under the mapping at all.
        assertEquals(404, get("/filesX", null).status());
        assertEquals(404, get("/filesprivate/secret.txt", null).status());
        assertEquals(404, get("/filesX", cookie).status());
        // M2: another spelling of the prefix is no mapping.
        assertEquals(404, get("/%66iles/private/secret.txt", null).status());
        assertEquals(404, get("/FILES/private/secret.txt", null).status());

        // Spellings under the mapping: the guard answers before any lookup...
        for (String path : new String[]{
                "/files/private/secret.txt",
                "/files/%70rivate/secret.txt",
                "/files/PRIVATE/secret.txt",
                "/files/private/secret%2Etxt",
                "/files/private/secret.txt?v=abc",
                "/files/"}) {
            var anon = get(path, null);
            assertEquals(302, anon.status(), path);
            assertFalse(anon.body().contains("top-secret"), path);
        }
        // ...and M2 still refuses the non-canonical ones for a user who passes it.
        assertEquals(200, get("/files/private/secret.txt", cookie).status());
        assertEquals(404, get("/files/%70rivate/secret.txt", cookie).status());
        assertEquals(404, get("/files/PRIVATE/secret.txt", cookie).status());
        assertEquals(404, get("/files/private/secret%2Etxt", cookie).status());

        // Separator tricks: Jetty refuses some with a 400; none is a 200.
        for (String path : new String[]{
                "/files//private/secret.txt",
                "/files/x/../private/secret.txt",
                "/files/private%2Fsecret.txt",
                "/pub/../files/private/secret.txt",
                "/files/private;x=1/secret.txt"}) {
            var anon = get(path, null);
            assertNotEquals(200, anon.status(), path);
            assertFalse(anon.body().contains("top-secret"), path);
        }
    }

    @Test
    void headIsGuardedLikeGet() throws Exception {
        try (Socket socket = new Socket("localhost", app.actualPort())) {
            socket.getOutputStream().write(
                "HEAD /files/report.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            var buf = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buf);
            assertEquals("302", buf.toString(StandardCharsets.UTF_8).substring(9, 12));
        }
    }

    @Test
    void aRejectionDoesNotRevealWhetherTheFileExists() throws Exception {
        var existing = get("/files/report.txt", null);
        for (String path : new String[]{"/files/nope.txt", "/files/.env", "/files/private", "/files/a/b/c"}) {
            var other = get(path, null);
            assertEquals(existing.status(), other.status(), path);
            assertEquals(existing.body(), other.body(), path);
            assertEquals(existing.header("Location"), other.header("Location"), path);
            assertEquals(existing.header("Cache-Control"), other.header("Cache-Control"), path);
            assertEquals(existing.header("Content-Type"), other.header("Content-Type"), path);
            assertEquals(existing.header("Content-Length"), other.header("Content-Length"), path);
            assertNull(other.header("ETag"), path);
            assertNull(other.header("Last-Modified"), path);
        }
        // A conditional request can't probe either: the guard runs before the validators.
        var conditional = get("/files/report.txt", null, "If-None-Match: *");
        assertEquals(302, conditional.status());
        assertNull(conditional.header("ETag"));
        // Once past the guard, a missing file is a plain 404.
        assertEquals(404, get("/files/nope.txt", cookie).status());
        assertEquals(404, get("/files/.env", cookie).status());
    }

    // --- Caching ---

    @Test
    void guardedResponsesAreNeverPublicCacheable() throws Exception {
        var res = get("/files/report.txt", cookie);
        assertEquals(200, res.status());
        assertEquals("private, no-cache", res.header("Cache-Control"));
        assertNotNull(res.header("ETag"));

        // A current fingerprint earns no immutable year on a guarded mapping.
        String fingerprinted = Assets.url("/files/report.txt");
        assertTrue(fingerprinted.contains("?v="), fingerprinted);
        var versioned = get(fingerprinted, cookie);
        assertEquals(200, versioned.status());
        assertEquals("private, no-cache", versioned.header("Cache-Control"));
        assertEquals(302, get(fingerprinted, null).status());

        // Revalidation still works, and still goes through the guard.
        var notModified = get("/files/report.txt", cookie, "If-None-Match: " + res.header("ETag"));
        assertEquals(304, notModified.status());
        assertEquals("private, no-cache", notModified.header("Cache-Control"));

        String rejected = get("/files/report.txt", null).header("Cache-Control");
        assertTrue(rejected == null || !rejected.toLowerCase(Locale.ROOT).contains("public"), rejected);
    }

    @Test
    void publicMappingCachingIsUnchanged() throws Exception {
        assertEquals("public, max-age=0, must-revalidate", get("/pub/app.css", null).header("Cache-Control"));
        assertEquals("public, max-age=31536000, immutable",
            get(Assets.url("/pub/app.css"), null).header("Cache-Control"));
        // Logged in or not, an untouched session sets no cookie, so the response stays public.
        assertEquals("public, max-age=0, must-revalidate", get("/pub/app.css", cookie).header("Cache-Control"));
    }

    @Test
    void afterMiddlewareCannotMakeAGuardedResponsePublic() throws Exception {
        var a = Brace.app().port(0).banner(false).sessions(SECRET).strictAccessCheck()
            .staticFiles("/pub", pubDir.toString()).publicFiles()
            .staticFiles("/files", filesDir.toString()).access(Access.session("userId", "/login"));
        a.after((req, res) -> res.header("Cache-Control", "public, max-age=300"));
        a.start();
        try {
            for (String c : new String[]{cookie, null}) {
                for (String path : new String[]{"/files/report.txt", "/files/nope.txt"}) {
                    String cacheControl = get(a, path, c).header("Cache-Control");
                    assertEquals("private, max-age=300", cacheControl, path + " cookie=" + (c != null));
                }
            }
            assertEquals("public, max-age=300", get(a, "/pub/app.css", null).header("Cache-Control"));
        } finally {
            a.stop();
        }
    }

    // --- UNTRUSTED composes with access ---

    @Test
    void untrustedMappingWithAccess() throws Exception {
        assertEquals(302, get("/uploads/page.html", null).status());
        assertEquals(302, get("/uploads/pic.png", null).status());

        var html = get("/uploads/page.html", cookie);
        assertEquals(200, html.status());
        assertEquals("sandbox", html.header("Content-Security-Policy"));
        assertTrue(html.header("Content-Disposition").startsWith("attachment"), html.raw());
        assertEquals("application/octet-stream", html.header("Content-Type"));
        assertEquals("private, no-cache", html.header("Cache-Control"));

        var png = get("/uploads/pic.png", cookie);
        assertEquals(200, png.status());
        assertEquals("image/png", png.header("Content-Type"));
    }

    // --- A symlink made after startup can't lead around the guard ---

    @Test
    void publicMappingDoesNotServeAFileInsideAGuardedDirectory() throws Exception {
        Path link = pubDir.resolve("leak.txt");
        try {
            Files.createSymbolicLink(link, filesDir.resolve("report.txt"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("symlinks unavailable: " + e);
        }
        try {
            // Already refused: the target is outside /pub's directory. Named here so the
            // guarantee has a test of its own.
            var res = get("/pub/leak.txt", null);
            assertEquals(404, res.status());
            assertFalse(res.body().contains("the-report"));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    // --- Built-in assets ---

    @Test
    void builtInAssetsNeedNoDeclarationEvenUnderAGuardedRootMapping() throws Exception {
        var a = Brace.app().port(0).banner(false).strictAccessCheck()
            .staticFiles("/", filesDir.toString()).access(Access.checkRequest(req -> Result.forbidden("no")));
        a.start();
        try {
            assertEquals(403, get(a, "/report.txt", null).status());
            for (String path : new String[]{"/__brace/htmx.min.js", "/__brace/csrf-htmx.js",
                    "/__brace/ops.js", "/__brace/ops.css", "/__brace/ops-analytics.css"}) {
                var res = get(a, path, null);
                assertEquals(200, res.status(), path);
                assertEquals("public, max-age=0, must-revalidate", res.header("Cache-Control"), path);
            }
            assertEquals(200, get(a, "/__brace/health", null).status());
            // Not a bundled asset: it is a path under the mapping, and guarded.
            assertEquals(403, get(a, "/__brace/other.js", null).status());
        } finally {
            a.stop();
        }

        // And with no mapping at all, nothing needs declaring.
        var bare = Brace.app().port(0).banner(false).strictAccessCheck();
        bare.start();
        try {
            assertEquals(200, get(bare, "/__brace/htmx.min.js", null).status());
        } finally {
            bare.stop();
        }
    }

    // --- Visibility ---

    @Test
    void opsRoutesListsEachMappingWithItsAccess() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        byte[] nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        String n = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String signature = OpsKeys.sign(
            OpsKeys.v3AuthMessage("http://localhost", keypair.publicKey(), timestamp, n), keypair.privateKey());
        String body = "{\"v\":\"3\",\"origin\":\"http://localhost\",\"publicKey\":\"" + keypair.publicKey()
            + "\",\"timestamp\":\"" + timestamp + "\",\"nonce\":\"" + n + "\",\"signature\":\"" + signature + "\"}";
        var client = java.net.http.HttpClient.newHttpClient();
        String base = "http://localhost:" + app.actualPort();
        var auth = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/ops/auth"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, auth.statusCode(), auth.body());
        String token = Json.mapper().readTree(auth.body()).get("token").asText();
        String routes = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/ops/routes"))
            .header("Authorization", "Bearer " + token).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()).body();

        assertTrue(routes.contains("{\"method\":\"STATIC\",\"pattern\":\"/pub/*\",\"access\":\"public\"}"), routes);
        assertTrue(routes.contains("{\"method\":\"STATIC\",\"pattern\":\"/files/*\",\"access\":\"session:userId\"}"), routes);
        assertTrue(routes.contains(
            "{\"method\":\"STATIC\",\"pattern\":\"/uploads/*\",\"access\":\"session:userId\",\"mode\":\"untrusted\"}"), routes);
    }

    @Test
    void bannerListsEachMappingWithItsAccess() throws Exception {
        var a = Brace.app().port(0).sessions(SECRET).strictAccessCheck()
            .staticFiles("/pub", pubDir.toString()).publicFiles()
            .staticFiles("/uploads", uploadsDir.toString(), StaticFiles.UNTRUSTED)
            .access(Access.session("userId", "/login"), Access.check((req, s) -> null));
        var captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            a.start();
        } finally {
            System.setOut(original);
            a.stop();
        }
        String banner = captured.toString(StandardCharsets.UTF_8);
        assertTrue(banner.contains("static (2) {"), banner);
        assertTrue(banner.matches("(?s).*STATIC\\s+/pub/\\*\\s+public\\R.*"), banner);
        assertTrue(banner.matches("(?s).*STATIC\\s+/uploads/\\*\\s+session:userId \\+ check\\s+untrusted\\R.*"), banner);
    }
}
