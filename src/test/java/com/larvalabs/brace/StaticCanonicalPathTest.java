package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M2: path guards match the raw request path, so a static file is served only at its canonical
 * spelling. Another spelling that reaches the same file (an encoded unreserved character, a case
 * variant on a case-insensitive volume) would skip a guard written for the canonical one.
 *
 * <p>Requests go over a raw socket so no client library normalizes the path before sending it.
 */
class StaticCanonicalPathTest {

    static Brace app;
    static Path dir;
    static boolean caseInsensitiveVolume;

    @BeforeAll
    static void setup() throws Exception {
        dir = Files.createTempDirectory("brace-m2");
        Files.createDirectory(dir.resolve("private"));
        Files.writeString(dir.resolve("private/secret.txt"), "top-secret");
        Files.writeString(dir.resolve("my file.css"), "spaced");
        Files.writeString(dir.resolve("café.txt"), "accented");
        Files.writeString(dir.resolve("a&b=c.txt"), "reserved");
        Files.writeString(dir.resolve("logo@2x.png"), "png");
        Files.writeString(dir.resolve("a~b.txt"), "tilde");
        Files.createDirectory(dir.resolve("v2"));
        Files.writeString(dir.resolve("v2/app.js"), "js");
        Files.createSymbolicLink(dir.resolve("latest"), Path.of("v2"));
        caseInsensitiveVolume = Files.exists(dir.resolve("PRIVATE"));

        app = Brace.app().port(0).staticFiles("/files", dir.toString()).publicFiles();
        app.before("/files/private/*", req -> Result.forbidden("forbidden"));
        // An exact-path guard, for the trailing-slash spelling (M2 review).
        Files.writeString(dir.resolve("exact.txt"), "exact-secret");
        app.before("/files/exact.txt", req -> Result.forbidden("forbidden"));
        app.start();
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    record Response(int status, String body) {}

    /** Send {@code path} byte-for-byte as the request target. */
    private static Response get(String path) throws Exception {
        try (Socket socket = new Socket("localhost", app.actualPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
            out.flush();
            var buf = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buf);
            String raw = buf.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(9, 12));
            int bodyStart = raw.indexOf("\r\n\r\n");
            return new Response(status, bodyStart < 0 ? "" : raw.substring(bodyStart + 4));
        }
    }

    @Test
    void guardCoversTheCanonicalSpelling() throws Exception {
        var res = get("/files/private/secret.txt");
        assertEquals(403, res.status());
        assertTrue(res.body().contains("forbidden"));
        assertFalse(res.body().contains("top-secret"));
    }

    @Test
    void encodedUnreservedCharacterIsRefused() throws Exception {
        // %70 is 'p'. A canonical URL never encodes an unreserved character, so this spelling
        // exists only to dodge a guard on the raw path. It was a 200 with the file.
        assertEquals(404, get("/files/%70rivate/secret.txt").status());
        assertEquals(404, get("/files/%50rivate/secret.txt").status());
        assertEquals(404, get("/files/v%32/app.js").status());
        assertEquals(404, get("/files/v2/app%2Ejs").status());
        // Hex case doesn't matter: %7e and %7E are both '~', which is unreserved.
        assertEquals(404, get("/files/a%7eb.txt").status());
        assertEquals(404, get("/files/a%7Eb.txt").status());
        assertEquals(404, get("/files/my%20fil%65.css").status());
        assertEquals(404, get("/files/%6Catest/app.js").status());
    }

    @Test
    void caseVariantIsRefused() throws Exception {
        // Refused on any volume: on a case-sensitive one the file simply isn't there.
        assertEquals(404, get("/files/PRIVATE/secret.txt").status());
        assertEquals(404, get("/files/Private/Secret.txt").status());
        assertEquals(404, get("/files/MY%20FILE.CSS").status());
        // Decomposed (NFD) spelling of "café": APFS opens the NFC file for it.
        assertEquals(404, get("/files/cafe%CC%81.txt").status());
    }

    @Test
    void trailingSlashIsNotASecondSpellingOfAFile() throws Exception {
        // M2 review: Path.normalize() drops a trailing separator, so "/files/exact.txt/" opened
        // exact.txt while skipping a guard on exactly "/files/exact.txt". It was a 200.
        assertEquals(403, get("/files/exact.txt").status());
        for (String path : new String[]{"/files/exact.txt/", "/files/exact.txt//", "/files/v2/app.js/",
                "/files/my%20file.css/", "/files/latest/app.js/"}) {
            var res = get(path);
            assertTrue(res.status() == 404 || res.status() == 400, path + " got " + res.status());
            assertFalse(res.body().contains("exact-secret"), path);
        }
        assertEquals(404, get("/files/exact.txt/").status());
        assertEquals(404, get("/files/v2/app.js/").status());
        // The plain spellings still work.
        assertEquals(200, get("/files/v2/app.js").status());
        assertEquals(200, get("/files/my%20file.css").status());
    }

    @Test
    void aDirectoryIsNotServedWithOrWithoutATrailingSlash() throws Exception {
        assertEquals(404, get("/files/v2").status());
        assertEquals(404, get("/files/v2/").status());
        assertEquals(404, get("/files").status());
        assertEquals(404, get("/files/").status());
    }

    @Test
    void emptySegmentsAreNotASecondSpelling() throws Exception {
        // Jetty's default URI compliance answers "//" with a 400 before Brace sees it; the
        // static handler's exact-spelling comparison refuses it as well.
        for (String path : new String[]{"/files/v2//app.js", "/files//v2/app.js", "/files//exact.txt"}) {
            var res = get(path);
            assertTrue(res.status() == 404 || res.status() == 400, path + " got " + res.status());
            assertFalse(res.body().contains("exact-secret"), path);
        }
    }

    @Test
    void escapeClassification() {
        // A fullwidth "hex digit" is refused whatever it would decode to: "%２０" reads as a
        // space to Character.digit, which is not an unreserved character, so only the non-ASCII
        // check catches it (the mutation the M2 review found surviving).
        assertTrue(BraceHandler.hasNonCanonicalEscape("a%２０b"));
        assertTrue(BraceHandler.hasNonCanonicalEscape("a%2０b"));
        assertTrue(BraceHandler.hasNonCanonicalEscape("a%２0b"));
        assertTrue(BraceHandler.hasNonCanonicalEscape("%70rivate/x"));
        assertTrue(BraceHandler.hasNonCanonicalEscape("a%2db"));
        assertTrue(BraceHandler.hasNonCanonicalEscape("a%2Fb"));
        assertTrue(BraceHandler.hasNonCanonicalEscape("a%5cb"));
        assertTrue(BraceHandler.hasNonCanonicalEscape("a\\b"));
        // Fullwidth digits: Character.digit accepts them, so decodeSegment would read "%７０" as 'p'.
        assertTrue(BraceHandler.hasNonCanonicalEscape("%７０rivate"));
        assertFalse(BraceHandler.hasNonCanonicalEscape("my%20file.css"));
        assertFalse(BraceHandler.hasNonCanonicalEscape("caf%C3%A9/caf%c3%a9"));
        assertFalse(BraceHandler.hasNonCanonicalEscape("a%26b%3Dc%40d%2B%21%3F%23%5B%5D%3B"));
        assertFalse(BraceHandler.hasNonCanonicalEscape("plain/path-with_unreserved.~"));
        assertFalse(BraceHandler.hasNonCanonicalEscape("trailing%7"));
    }

    @Test
    void caseVariantIsRefusedOnACaseInsensitiveVolume() throws Exception {
        // The volume really would hand back the file for these spellings: this is the bypass.
        assumeTrue(caseInsensitiveVolume, "volume is case-sensitive");
        assertTrue(Files.exists(dir.resolve("PRIVATE/secret.txt")));
        assertEquals(404, get("/files/PRIVATE/secret.txt").status());
        assertEquals(404, get("/files/V2/app.js").status());
        assertEquals(404, get("/files/v2/APP.JS").status());
        assertEquals(404, get("/files/MY%20FILE.CSS").status());
        // A link inside the tree, reached by a case variant of the link's own name.
        assertEquals(404, get("/files/LATEST/app.js").status());
        assertEquals(404, get("/files/latest/APP.js").status());
    }

    @Test
    void legitimateEncodingsAreStillServed() throws Exception {
        var spaced = get("/files/my%20file.css");
        assertEquals(200, spaced.status());
        assertEquals("spaced", spaced.body());

        var accented = get("/files/caf%C3%A9.txt");
        assertEquals(200, accented.status());
        assertEquals("accented", accented.body());
        // Lowercase hex is still a valid escape for a character that has to be encoded.
        assertEquals(200, get("/files/caf%c3%a9.txt").status());

        // Sub-delims and '@' may be written literally or encoded (encodeURIComponent encodes them).
        assertEquals(200, get("/files/a&b=c.txt").status());
        assertEquals(200, get("/files/a%26b%3Dc.txt").status());
        assertEquals(200, get("/files/logo@2x.png").status());
        assertEquals(200, get("/files/logo%402x.png").status());
        assertEquals(200, get("/files/a~b.txt").status());
    }

    @Test
    void symlinkInsideTheServedTreeStillWorks() throws Exception {
        var res = get("/files/latest/app.js");
        assertEquals(200, res.status());
        assertEquals("js", res.body());
        assertEquals(200, get("/files/v2/app.js").status());
    }

    @Test
    void encodedPrefixIsNotTheMapping() throws Exception {
        // The mapping prefix is matched raw, so another spelling of it is no mapping at all.
        assertEquals(404, get("/%66iles/private/secret.txt").status());
        assertEquals(404, get("/FILES/private/secret.txt").status());
    }

    @Test
    void separatorSpellingsNeverReachTheGuardedFile() throws Exception {
        // Jetty's UriCompliance answers some of these with a 400 before Brace sees them; the
        // static handler refuses the rest. Either way the guarded file is never served.
        for (String path : new String[]{
                "/files/private%2Fsecret.txt",
                "/files/private%2fsecret.txt",
                "/files/private%5Csecret.txt",
                "/files/private%5csecret.txt",
                "/files/private\\secret.txt",
                "/files//private/secret.txt",
                "/files/./private/secret.txt",
                "/files/private/./secret.txt",
                "/files/x/../private/secret.txt",
                "/files/private;x=1/secret.txt"}) {
            var res = get(path);
            assertTrue(res.status() >= 400, path + " must be guarded or refused, got " + res.status());
            assertFalse(res.body().contains("top-secret"), path + " leaked the guarded file");
        }
    }
}
