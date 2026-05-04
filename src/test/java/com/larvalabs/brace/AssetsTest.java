package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class AssetsTest {

    static Brace app;
    static Path tempDir;

    @BeforeAll
    static void startApp() throws Exception {
        tempDir = Files.createTempDirectory("brace-assets-test");
        Files.writeString(tempDir.resolve("app.css"), "body { color: red; }");
        Files.writeString(tempDir.resolve("app.js"), "console.log('v1');");

        var subDir = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(subDir.resolve("nested.css"), ".x { }");

        app = Brace.app().port(0).staticFiles("/assets", tempDir.toString());
        app.start();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @BeforeEach
    void clearCache() {
        Assets.clearCache();
    }

    @Test
    void appendsHashToManagedAsset() {
        var url = Assets.url("/assets/app.css");
        assertTrue(url.startsWith("/assets/app.css?v="), "got: " + url);
        assertEquals(8, url.substring("/assets/app.css?v=".length()).length(),
            "hash should be 8 hex chars");
    }

    @Test
    void hashIsStableForSameContent() {
        var first = Assets.url("/assets/app.css");
        var second = Assets.url("/assets/app.css");
        assertEquals(first, second);
    }

    @Test
    void differentFilesGetDifferentHashes() {
        var css = Assets.url("/assets/app.css");
        var js = Assets.url("/assets/app.js");
        var cssHash = css.substring(css.indexOf("?v=") + 3);
        var jsHash = js.substring(js.indexOf("?v=") + 3);
        assertNotEquals(cssHash, jsHash);
    }

    @Test
    void hashChangesWhenFileContentChanges() throws Exception {
        var path = tempDir.resolve("changing.css");
        Files.writeString(path, "v1");
        Files.setLastModifiedTime(path, FileTime.fromMillis(1_000_000_000_000L));
        var first = Assets.url("/assets/changing.css");

        Files.writeString(path, "completely different content");
        Files.setLastModifiedTime(path, FileTime.fromMillis(2_000_000_000_000L));
        var second = Assets.url("/assets/changing.css");

        assertNotEquals(first, second);
    }

    @Test
    void nestedPathsResolve() {
        var url = Assets.url("/assets/sub/nested.css");
        assertTrue(url.startsWith("/assets/sub/nested.css?v="), "got: " + url);
    }

    @Test
    void unknownAssetReturnsUrlUnchanged() {
        var url = Assets.url("/assets/does-not-exist.css");
        assertEquals("/assets/does-not-exist.css", url);
    }

    @Test
    void urlOutsideStaticPrefixReturnsUnchanged() {
        var url = Assets.url("/some/other/path.css");
        assertEquals("/some/other/path.css", url);
    }

    @Test
    void pathTraversalReturnsUnchanged() {
        var url = Assets.url("/assets/../etc/passwd");
        assertEquals("/assets/../etc/passwd", url);
    }

    @Test
    void existingQueryStringIsReplaced() {
        var url = Assets.url("/assets/app.css?v=stale");
        assertTrue(url.startsWith("/assets/app.css?v="));
        assertFalse(url.endsWith("?v=stale"));
    }

    @Test
    void idempotent() {
        var first = Assets.url("/assets/app.css");
        var second = Assets.url(first);
        assertEquals(first, second);
    }
}
