package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10 security review, M7: a user-uploaded {@code .html} or {@code .svg} under a static
 * directory came back {@code 200 text/html} / {@code image/svg+xml} inline, so its script ran on
 * the app's origin. {@link StaticFiles#UNTRUSTED} and {@link Result#untrusted()} serve uploads so
 * nothing can run: an allowlisted type or octet-stream, an attachment unless it is a raster image,
 * and {@code CSP: sandbox} plus {@code nosniff} always.
 */
class UntrustedUploadServingTest {

    static final String SCRIPT_PAGE = "<html><body><script>document.title='pwned'</script></body></html>";
    static final String SCRIPT_SVG =
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(document.domain)</script></svg>";

    static TestApp app;
    static Path uploads;
    static Path assets;

    @BeforeAll
    static void setup() throws Exception {
        uploads = Files.createTempDirectory("brace-m7-uploads");
        assets = Files.createTempDirectory("brace-m7-assets");
        for (Path dir : new Path[] {uploads, assets}) {
            Files.writeString(dir.resolve("x.html"), SCRIPT_PAGE);
            Files.writeString(dir.resolve("x.svg"), SCRIPT_SVG);
            Files.writeString(dir.resolve("X.SVG"), SCRIPT_SVG);
            Files.writeString(dir.resolve("x.svg.png"), "not really a png");
            Files.writeString(dir.resolve("x.js"), "alert(1)");
            Files.writeString(dir.resolve("x.xml"), "<x/>");
            Files.writeString(dir.resolve("x.png"), "png-bytes");
            Files.writeString(dir.resolve("x.webp"), "webp-bytes");
            Files.writeString(dir.resolve("x.pdf"), "%PDF-1.4");
            Files.writeString(dir.resolve("x.txt"), "0123456789");
            Files.writeString(dir.resolve("noext"), "?");
        }
        // "/assets" first on purpose: the more specific "/assets/uploads" must still serve its
        // own directory (longest prefix wins), not 404 from the trusted one.
        app = Brace.test().start(a -> {
            a.staticFiles("/assets", assets.toString())
             .staticFiles("/assets/uploads", uploads.toString(), StaticFiles.UNTRUSTED);
            a.after(SecurityHeaders.builder().contentSecurityPolicy("default-src 'self'").build());
            a.get("/files/{name}", req -> Result.fileUnder(uploads, req.pathParam("name")).untrusted());
            a.get("/download/{name}", req ->
                Result.download(uploads.resolve(req.pathParam("name")), "report \"final\".html").untrusted());
            a.get("/claimed", req -> Result.bytes(SCRIPT_PAGE.getBytes(), "text/plain, text/html").untrusted());
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    private static void assertSandboxed(TestResponse r, String path) {
        assertTrue(r.headers("Content-Security-Policy").contains("sandbox"),
            path + ": CSP sandbox expected, got " + r.headers("Content-Security-Policy"));
        assertEquals("nosniff", r.header("X-Content-Type-Options"), path);
    }

    private static void assertAttachment(TestResponse r, String path) {
        String cd = r.header("Content-Disposition");
        assertNotNull(cd, path + ": expected an attachment");
        assertTrue(cd.startsWith("attachment"), path + ": " + cd);
    }

    // --- StaticFiles.UNTRUSTED ---

    @Test
    void uploadedHtmlAndSvgAreSandboxedOctetStreamAttachments() {
        for (String name : new String[] {"x.html", "x.svg", "X.SVG", "x.js", "x.xml", "noext"}) {
            String path = "/assets/uploads/" + name;
            var r = app.get(path);
            assertEquals(200, r.status(), path);
            assertEquals("application/octet-stream", r.header("Content-Type"), path);
            assertAttachment(r, path);
            assertSandboxed(r, path);
        }
    }

    @Test
    void rasterImagesStayInlineButSandboxed() {
        for (var c : new String[][] {{"x.png", "image/png"}, {"x.webp", "image/webp"},
                                     {"x.svg.png", "image/png"}}) {
            String path = "/assets/uploads/" + c[0];
            var r = app.get(path);
            assertEquals(200, r.status(), path);
            assertEquals(c[1], r.header("Content-Type"), path);
            assertNull(r.header("Content-Disposition"), path + " should display inline");
            assertSandboxed(r, path);
        }
    }

    @Test
    void passiveDocumentsKeepTheirTypeAsAttachments() {
        var pdf = app.get("/assets/uploads/x.pdf");
        assertEquals("application/pdf", pdf.header("Content-Type"));
        assertAttachment(pdf, "x.pdf");
        assertSandboxed(pdf, "x.pdf");

        var txt = app.get("/assets/uploads/x.txt");
        assertTrue(txt.header("Content-Type").startsWith("text/plain"), txt.header("Content-Type"));
        assertAttachment(txt, "x.txt");
    }

    @Test
    void sandboxSurvivesAnAppCspFromAfterMiddleware() {
        // SecurityHeaders replaced the single-value CSP; the sandbox goes out as a second field.
        var r = app.get("/assets/uploads/x.html");
        var csp = r.headers("Content-Security-Policy");
        assertTrue(csp.contains("default-src 'self'"), csp.toString());
        assertTrue(csp.contains("sandbox"), csp.toString());
    }

    @Test
    void rangeResponsesKeepTheUntrustedHeaders() {
        var r = app.request("GET", "/assets/uploads/x.txt").header("Range", "bytes=0-3").send();
        assertEquals(206, r.status());
        assertEquals("0123", r.body());
        assertAttachment(r, "range");
        assertSandboxed(r, "range");
    }

    @Test
    void notModifiedAndMissingAreUnaffected() {
        assertEquals(404, app.get("/assets/uploads/missing.html").status());
        var first = app.get("/assets/uploads/x.html");
        var again = app.request("GET", "/assets/uploads/x.html")
            .header("If-None-Match", first.header("ETag")).send();
        assertEquals(304, again.status());
    }

    // --- Trusted mapping unchanged ---

    @Test
    void trustedStaticFilesAreServedAsBefore() {
        var html = app.get("/assets/x.html");
        assertEquals(200, html.status());
        assertEquals("text/html; charset=utf-8", html.header("Content-Type"));
        assertEquals(SCRIPT_PAGE, html.body());
        assertNull(html.header("Content-Disposition"));
        assertFalse(html.headers("Content-Security-Policy").contains("sandbox"));

        assertEquals("image/svg+xml", app.get("/assets/x.svg").header("Content-Type"));
        assertEquals("image/svg+xml", app.get("/assets/X.SVG").header("Content-Type"));
        assertEquals("text/javascript; charset=utf-8", app.get("/assets/x.js").header("Content-Type"));
        // Only the final extension decides the type, so a double extension is its last part.
        assertEquals("image/png", app.get("/assets/x.svg.png").header("Content-Type"));
        assertEquals("application/pdf", app.get("/assets/x.pdf").header("Content-Type"));
    }

    // --- Result.untrusted() ---

    @Test
    void handlerServedUploadsAreSandboxedAttachments() {
        var html = app.get("/files/x.html");
        assertEquals(200, html.status());
        assertEquals("application/octet-stream", html.header("Content-Type"));
        assertAttachment(html, "/files/x.html");
        assertSandboxed(html, "/files/x.html");

        var svg = app.get("/files/x.svg");
        assertEquals("application/octet-stream", svg.header("Content-Type"));
        assertAttachment(svg, "/files/x.svg");

        var png = app.get("/files/x.png");
        assertEquals("image/png", png.header("Content-Type"));
        assertNull(png.header("Content-Disposition"));
        assertSandboxed(png, "/files/x.png");
    }

    @Test
    void untrustedKeepsADownloadFilename() {
        var r = app.get("/download/x.html");
        assertEquals("application/octet-stream", r.header("Content-Type"));
        assertTrue(r.header("Content-Disposition").contains("filename="), r.header("Content-Disposition"));
        assertSandboxed(r, "/download");
    }

    @Test
    void aClaimedTypeListIsNotTrusted() {
        // Browsers take the last type of a comma-separated list, so this would render as HTML.
        assertEquals("application/octet-stream", app.get("/claimed").header("Content-Type"));
    }

    @Test
    void untrustedLeavesErrorResponsesAlone() {
        var r = app.get("/files/missing.html");
        assertEquals(404, r.status());
        assertNull(r.header("Content-Disposition"));
        assertEquals(404, Result.notFound().untrusted().status());
        assertNull(Result.notFound().untrusted().header("Content-Disposition"));
    }

    @Test
    void untrustedReplacesAnInlineDisposition() {
        var r = Result.bytes(new byte[0], "text/html").header("Content-Disposition", "inline").untrusted();
        assertEquals("application/octet-stream", r.contentType());
        assertEquals("attachment", r.header("Content-Disposition"));
    }

    // --- Registration ---

    @Test
    void anUntrustedDirectoryInsideATrustedOneIsRefused(@TempDir Path root) throws Exception {
        Path inner = Files.createDirectories(root.resolve("uploads"));
        var e = assertThrows(IllegalArgumentException.class, () -> Brace.app()
            .staticFiles("/assets", root.toString())
            .staticFiles("/uploads", inner.toString(), StaticFiles.UNTRUSTED));
        assertTrue(e.getMessage().contains("overlaps"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Brace.app()
            .staticFiles("/uploads", inner.toString(), StaticFiles.UNTRUSTED)
            .staticFiles("/assets", root.toString()));
        assertDoesNotThrow(() -> Brace.app()
            .staticFiles("/assets", root.resolve("public").toString())
            .staticFiles("/uploads", inner.toString(), StaticFiles.UNTRUSTED));
    }
}
