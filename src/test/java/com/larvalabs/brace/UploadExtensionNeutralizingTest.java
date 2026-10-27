package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10 security review, M7: {@link Storage#safeKey} and {@link UploadedFile#safeFilename()}
 * kept the uploader's extension, so an {@code .html} or {@code .svg} upload stayed one wherever
 * it was stored and served. An executable final extension is now neutralized.
 */
class UploadExtensionNeutralizingTest {

    static final List<String> ACTIVE = List.of(
        "html", "htm", "xhtml", "xht", "shtml", "mht", "mhtml", "svg", "svgz", "xml", "xsl", "xslt",
        "js", "mjs", "cjs", "css", "wasm", "swf", "php", "phtml", "phar", "asp", "aspx", "jsp", "cgi");

    private static String keyExtension(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }

    @Test
    void safeKeyNeutralizesEveryActiveExtensionInAnyCase() {
        for (String ext : ACTIVE) {
            for (String variant : List.of(ext, ext.toUpperCase(), Character.toUpperCase(ext.charAt(0)) + ext.substring(1))) {
                String key = Storage.safeKey("u", "x." + variant);
                assertEquals("bin", keyExtension(key), "x." + variant + " -> " + key);
            }
        }
    }

    @Test
    void safeKeyKeepsPassiveExtensions() {
        for (String ext : List.of("png", "jpg", "gif", "webp", "pdf", "txt", "csv", "zip", "mp4", "docx", "psd")) {
            assertEquals(ext, keyExtension(Storage.safeKey("u", "x." + ext)), ext);
        }
        assertEquals("jpg", keyExtension(Storage.safeKey("u", "Holiday.JPG")));
    }

    @Test
    void onlyTheFinalExtensionCounts() {
        // A static mapping and Storage both type a file by its last extension, so x.svg.png is a
        // PNG to them, and x.png.svg is an SVG.
        assertEquals("png", keyExtension(Storage.safeKey("u", "x.svg.png")));
        assertEquals("bin", keyExtension(Storage.safeKey("u", "x.png.svg")));
        assertEquals("bin", keyExtension(Storage.safeKey("u", "x.png.Html")));
    }

    @Test
    void extensionStillReportsTheRealExtension() {
        assertEquals("html", Storage.extension("x.HTML"));
        assertEquals("svg", Storage.extension("x.svg"));
    }

    @Test
    void safeKeyWithoutAnExtensionHasNone() {
        String key = Storage.safeKey("u", "README");
        assertFalse(key.substring("u/".length()).contains("."), key);
    }

    @Test
    void safeFilenameAppendsBinToActiveExtensions() {
        assertEquals("page.HTML.bin", upload("page.HTML").safeFilename());
        assertEquals("logo.svg.bin", upload("../logo.svg").safeFilename());
        assertEquals("a.png.Svg.bin", upload("a.png.Svg").safeFilename());
        assertEquals("x.svg.png", upload("x.svg.png").safeFilename());
        assertEquals("report.pdf", upload("report.pdf").safeFilename());
        assertEquals("html", upload(".html").safeFilename());
    }

    @Test
    void safeFilenameChecksTheExtensionAfterTruncation() {
        // The 255-byte cap can cut a name so that an executable extension becomes the last one.
        String name = "a".repeat(250) + ".html" + "xxxxxxxx";
        String safe = upload(name).safeFilename();
        assertTrue(safe.endsWith(".bin"), safe);
        assertTrue(safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255, safe);

        String longActive = "b".repeat(300) + ".svg";
        String safe2 = upload(longActive).safeFilename();
        assertTrue(safe2.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255);
        assertFalse(UntrustedContent.isActiveExtension(UntrustedContent.extensionOf(safe2)), safe2);
    }

    private static UploadedFile upload(String name) {
        return new UploadedFile(name, "text/html", new byte[0]);
    }
}
