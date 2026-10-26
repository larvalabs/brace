package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** M8: upload filenames are attacker-controlled; the safe-name and confined-write/serve helpers. */
class UploadPathSafetyTest {

    @TempDir Path tmp;

    private static UploadedFile upload(String filename) {
        return new UploadedFile(filename, "text/plain", "data".getBytes(StandardCharsets.UTF_8));
    }

    // --- safeFilename ---

    @Test
    void safeFilenameKeepsOrdinaryNames() {
        assertEquals("photo.png", upload("photo.png").safeFilename());
        assertEquals("my report (1).pdf", upload("my report (1).pdf").safeFilename());
        assertEquals("caf\u00e9.txt", upload("caf\u00e9.txt").safeFilename());
    }

    @Test
    void safeFilenameTakesTheBasenameAfterEitherSeparator() {
        assertEquals("x", upload("../../x").safeFilename());
        assertEquals("x", upload("/abs/x").safeFilename());
        assertEquals("x", upload("..\\..\\x").safeFilename());
        assertEquals("x", upload("C:\\Users\\me\\x").safeFilename());
        assertEquals("x", upload("a/b\\c/x").safeFilename());
    }

    @Test
    void safeFilenameStripsControlCharsAndLeadingDots() {
        assertEquals("ab.txt", upload("a\u0000b\r\n.txt").safeFilename());
        assertEquals("htaccess", upload(".htaccess").safeFilename());
        assertEquals("x", upload("...x").safeFilename());
        assertEquals("a.b", upload("a.b").safeFilename());
    }

    @Test
    void safeFilenameNeverReturnsADotfileAfterWhitespace() throws IOException {
        // Trimming dots and then whitespace (or the reverse) once let a dot reach the front.
        assertEquals("env", upload(". .env").safeFilename());
        assertEquals("htaccess", upload(".. .htaccess").safeFilename());
        assertEquals("git", upload(" . . .git").safeFilename());
        assertEquals("env", upload("a/. .env").safeFilename());
        assertEquals("env", upload(".  .env").safeFilename());
        assertEquals("env", upload(".\u00a0.env").safeFilename());
        assertEquals("upload", upload(". ..").safeFilename());
        // The documented idiom therefore can't be made to throw by the client's name.
        var saved = upload(". ..").saveTo(tmp.resolve("idiom"), upload(". ..").safeFilename());
        assertEquals("upload", saved.getFileName().toString());
    }

    @Test
    void safeFilenameStripsInvisibleFormatCharacters() {
        // U+202E (right-to-left override) makes the rest of a name display reversed, so this
        // one would show as "photoexe.png".
        assertEquals("photognp.exe", upload("photo\u202Egnp.exe").safeFilename());
        assertEquals("ab.txt", upload("a\u200Bb.txt").safeFilename());
    }

    @Test
    void safeFilenameIsNeverEmpty() {
        assertEquals("upload", upload(null).safeFilename());
        assertEquals("upload", upload("").safeFilename());
        assertEquals("upload", upload("   ").safeFilename());
        assertEquals("upload", upload("..").safeFilename());
        assertEquals("upload", upload("../").safeFilename());
        assertEquals("upload", upload("\u0000\u0001").safeFilename());
    }

    @Test
    void safeFilenameIsCappedAt255Bytes() {
        assertEquals(255, upload("a".repeat(300)).safeFilename().length());
        // Multi-byte: 3 bytes each, so 85 characters fit and none is split.
        String capped = upload("\u4e2d".repeat(200)).safeFilename();
        assertEquals(85, capped.length());
        assertTrue(capped.getBytes(StandardCharsets.UTF_8).length <= 255);
    }

    // --- saveTo(dir, name) ---

    @Test
    void saveToWritesAnOrdinaryNameUnderTheDirectory() throws IOException {
        var dir = tmp.resolve("uploads");
        var saved = upload("a.txt").saveTo(dir, "a.txt");
        assertEquals(dir.resolve("a.txt").toAbsolutePath().normalize(), saved);
        assertEquals("data", Files.readString(saved));
    }

    @Test
    void saveToAllowsASubdirectoryInsideTheBase() throws IOException {
        var saved = upload("a.txt").saveTo(tmp.resolve("uploads"), "2026/a.txt");
        assertEquals("data", Files.readString(saved));
        assertTrue(saved.startsWith(tmp.resolve("uploads").toAbsolutePath()));
    }

    @Test
    void saveToRejectsTraversal() throws IOException {
        var dir = tmp.resolve("uploads");
        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(dir, "../../x"));
        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(dir, "a/../../x"));
        assertFalse(Files.exists(tmp.resolve("x")));
    }

    @Test
    void saveToRejectsAnAbsoluteName() {
        var dir = tmp.resolve("uploads");
        var abs = tmp.resolve("outside.txt");
        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(dir, abs.toString()));
        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(dir, "/abs/x"));
        assertFalse(Files.exists(abs));
    }

    @Test
    void saveToTreatsABackslashNameAsOneLeafNameOrRejectsIt() throws IOException {
        // On POSIX a backslash is an ordinary character, so "..\\x" is a single (odd) file name
        // inside the directory; on Windows it is a separator and must be rejected. Either way it
        // cannot land outside.
        var dir = tmp.resolve("uploads");
        try {
            var saved = upload("x").saveTo(dir, "..\\x");
            assertEquals(dir.toAbsolutePath().normalize(), saved.getParent());
        } catch (IllegalArgumentException expected) {
            // Windows path semantics
        }
        assertFalse(Files.exists(tmp.resolve("x")));
    }

    @Test
    void saveToRejectsEmptyAndDirectoryNames() {
        var dir = tmp.resolve("uploads");
        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(dir, ""));
        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(dir, null));
        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(dir, "."));
        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(dir, "a/.."));
    }

    @Test
    void saveToRejectsAFileNameWithNul() {
        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(tmp, "a\u0000b"));
    }

    @Test
    void saveToRejectsASymlinkedDirectoryThatEscapesTheBase() throws IOException {
        var base = Files.createDirectories(tmp.resolve("uploads"));
        var outside = Files.createDirectories(tmp.resolve("outside"));
        Files.createSymbolicLink(base.resolve("link"), outside);

        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(base, "link/x.txt"));
        assertFalse(Files.exists(outside.resolve("x.txt")), "nothing was written through the link");
    }

    @Test
    void saveToRejectsASymlinkedFileThatEscapesTheBase() throws IOException {
        var base = Files.createDirectories(tmp.resolve("uploads"));
        var victim = Files.writeString(tmp.resolve("victim.txt"), "original");
        Files.createSymbolicLink(base.resolve("a.txt"), victim);

        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(base, "a.txt"));
        assertEquals("original", Files.readString(victim));
    }

    @Test
    void saveToRejectsADanglingSymlink() throws IOException {
        var base = Files.createDirectories(tmp.resolve("uploads"));
        Files.createSymbolicLink(base.resolve("a.txt"), tmp.resolve("does-not-exist"));

        assertThrows(IllegalArgumentException.class, () -> upload("x").saveTo(base, "a.txt"));
        assertFalse(Files.exists(tmp.resolve("does-not-exist")));
    }

    @Test
    void saveToFollowsASymlinkThatStaysInsideTheBase() throws IOException {
        var base = Files.createDirectories(tmp.resolve("uploads"));
        var real = Files.createDirectories(base.resolve("real"));
        Files.createSymbolicLink(base.resolve("alias"), real);

        upload("x").saveTo(base, "alias/x.txt");
        assertEquals("data", Files.readString(real.resolve("x.txt")));
    }

    // --- Result.fileUnder ---

    @Test
    void fileUnderServesAnOrdinaryFile() throws IOException {
        Files.writeString(tmp.resolve("hello.txt"), "hi");
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub/b.txt"), "b");

        var result = Result.fileUnder(tmp, "hello.txt");
        assertEquals(200, result.status());
        assertEquals("text/plain; charset=utf-8", result.contentType());
        assertTrue(result.isStreaming());
        assertEquals(200, Result.fileUnder(tmp, "sub/b.txt").status());
    }

    @Test
    void fileUnderAnswers404ForTraversalAbsoluteEmptyAndMissing() throws IOException {
        var base = Files.createDirectories(tmp.resolve("pub"));
        Files.writeString(tmp.resolve("secret.txt"), "secret");
        Files.writeString(base.resolve("ok.txt"), "ok");

        assertEquals(404, Result.fileUnder(base, "../secret.txt").status());
        assertEquals(404, Result.fileUnder(base, "ok.txt/../../secret.txt").status());
        assertEquals(404, Result.fileUnder(base, tmp.resolve("secret.txt").toString()).status());
        assertEquals(404, Result.fileUnder(base, "/etc/passwd").status());
        assertEquals(404, Result.fileUnder(base, "").status());
        assertEquals(404, Result.fileUnder(base, null).status());
        assertEquals(404, Result.fileUnder(base, ".").status());
        assertEquals(404, Result.fileUnder(base, "missing.txt").status());
        assertEquals(404, Result.fileUnder(base, "a\u0000b").status());
        assertEquals(404, Result.fileUnder(tmp.resolve("no-such-base"), "ok.txt").status());
    }

    @Test
    void fileUnderAnswers404ForADirectory() throws IOException {
        Files.createDirectories(tmp.resolve("pub/dir"));
        assertEquals(404, Result.fileUnder(tmp.resolve("pub"), "dir").status());
    }

    @Test
    void fileUnderAnswers404ForASymlinkEscapingTheBase() throws IOException {
        var base = Files.createDirectories(tmp.resolve("pub"));
        var secret = Files.writeString(tmp.resolve("secret.txt"), "secret");
        var outsideDir = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outsideDir.resolve("f.txt"), "f");
        Files.createSymbolicLink(base.resolve("leak.txt"), secret);
        Files.createSymbolicLink(base.resolve("dirlink"), outsideDir);

        assertEquals(404, Result.fileUnder(base, "leak.txt").status());
        assertEquals(404, Result.fileUnder(base, "dirlink/f.txt").status());
    }

    @Test
    void fileUnderServesASymlinkThatStaysInsideTheBase() throws IOException {
        var base = Files.createDirectories(tmp.resolve("pub"));
        var real = Files.writeString(base.resolve("real.txt"), "r");
        Files.createSymbolicLink(base.resolve("alias.txt"), real);

        assertEquals(200, Result.fileUnder(base, "alias.txt").status());
    }
}
