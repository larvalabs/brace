package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H1 (2026-07 security review): static-file containment must hold in the filesystem, not
 * just in the path string. {@code Path.normalize()} collapses ".." lexically and does not
 * resolve symlinks, while {@code Files.readAllBytes} follows them — so a symlink under a
 * served directory used to serve whatever it pointed at, outside the web root included.
 */
class StaticFileSymlinkTest {

    static Brace app;
    static int port;
    @TempDir static Path assets;
    @TempDir static Path outside;

    @BeforeAll
    static void startApp() throws Exception {
        Files.writeString(assets.resolve("public.txt"), "public-content");
        Files.createDirectory(assets.resolve("sub"));
        Files.writeString(assets.resolve("sub/inner.txt"), "inner-content");

        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "SECRET-OUTSIDE-WEBROOT");

        Files.createSymbolicLink(assets.resolve("escape.txt"), secret);
        // A link that stays inside the served tree must keep working.
        Files.createSymbolicLink(assets.resolve("inside-link.txt"), assets.resolve("sub/inner.txt"));
        Files.createSymbolicLink(assets.resolve("broken.txt"), assets.resolve("nope.txt"));
        // A directory symlink pointing out of the tree — traversal through the link, not to it.
        Files.createSymbolicLink(assets.resolve("outdir"), outside);

        app = Brace.app().port(0).staticFiles("/assets", assets.toString());
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.stop();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void symlinkOutOfRootIsNotServed() throws Exception {
        var resp = get("/assets/escape.txt");
        assertEquals(404, resp.statusCode(), "symlink escaping the served directory must 404");
        assertFalse(resp.body().contains("SECRET-OUTSIDE-WEBROOT"),
            "target file contents leaked: " + resp.body());
    }

    @Test
    void directorySymlinkOutOfRootIsNotTraversed() throws Exception {
        var resp = get("/assets/outdir/secret.txt");
        assertEquals(404, resp.statusCode(), "traversal through a directory symlink must 404");
        assertFalse(resp.body().contains("SECRET-OUTSIDE-WEBROOT"));
    }

    @Test
    void symlinkWithinRootStillWorks() throws Exception {
        var resp = get("/assets/inside-link.txt");
        assertEquals(200, resp.statusCode(), "a link that stays inside the root must still serve");
        assertEquals("inner-content", resp.body());
    }

    @Test
    void brokenSymlinkIsNotFound() throws Exception {
        assertEquals(404, get("/assets/broken.txt").statusCode());
    }

    @Test
    void ordinaryFileUnaffected() throws Exception {
        var resp = get("/assets/public.txt");
        assertEquals(200, resp.statusCode());
        assertEquals("public-content", resp.body());
    }
}
