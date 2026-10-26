package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Security review L11: a mapped static directory serves ordinary files, but never a dotfile or a
 * file under a dot-directory ({@code .env}, {@code .git/config}), except under a leading
 * {@code .well-known}.
 */
class StaticDotfileTest {

    private static final String SECRET = "brace-l11-secret-content";

    static TestApp app;
    static Path dir;

    @BeforeAll
    static void setup() throws Exception {
        dir = Files.createTempDirectory("brace-l11-assets");
        Files.writeString(dir.resolve("plain.css"), "body{}");
        Files.writeString(dir.resolve("app.min.js"), "var a=1;");
        Files.writeString(dir.resolve(".env"), SECRET);
        Files.writeString(dir.resolve(".htaccess"), SECRET);
        Files.createDirectory(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git").resolve("config"), SECRET);
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub").resolve("nested.css"), "a{}");
        Files.writeString(dir.resolve("sub").resolve(".secret"), SECRET);
        Files.createDirectory(dir.resolve("sub").resolve(".hidden"));
        Files.writeString(dir.resolve("sub").resolve(".hidden").resolve("x.txt"), SECRET);
        Files.createDirectory(dir.resolve(".well-known"));
        Files.writeString(dir.resolve(".well-known").resolve("x"), "well-known content");
        Files.writeString(dir.resolve(".well-known").resolve(".hidden"), SECRET);
        Files.createDirectory(dir.resolve("sub").resolve(".well-known"));
        Files.writeString(dir.resolve("sub").resolve(".well-known").resolve("x"), SECRET);

        app = Brace.test().start(a -> a.staticFiles("/assets", dir.toString()));
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    @Test
    void normalFilesAreServed() {
        assertEquals(200, app.get("/assets/plain.css").status());
        assertEquals("body{}", app.get("/assets/plain.css").body());
        assertEquals(200, app.get("/assets/app.min.js").status());
        assertEquals(200, app.get("/assets/sub/nested.css").status());
    }

    @Test
    void dotfileAtTheRootIs404() {
        for (String path : new String[] {"/assets/.env", "/assets/.htaccess"}) {
            var r = app.get(path);
            assertEquals(404, r.status(), path);
            assertFalse(r.body().contains(SECRET), path);
        }
    }

    @Test
    void filesUnderADotDirectoryAre404() {
        var r = app.get("/assets/.git/config");
        assertEquals(404, r.status());
        assertFalse(r.body().contains(SECRET));
        assertEquals(404, app.get("/assets/sub/.secret").status());
        assertEquals(404, app.get("/assets/sub/.hidden/x.txt").status());
    }

    @Test
    void percentEncodedDotIsDecodedBeforeTheCheck() {
        var r = app.get("/assets/%2eenv");
        assertEquals(404, r.status());
        assertFalse(r.body().contains(SECRET));
    }

    @Test
    void wellKnownFirstSegmentIsServed() {
        var r = app.get("/assets/.well-known/x");
        assertEquals(200, r.status());
        assertEquals("well-known content", r.body());
    }

    @Test
    void wellKnownExemptionIsOnlyForTheFirstSegmentAndNotForDotfilesInsideIt() {
        assertEquals(404, app.get("/assets/.well-known/.hidden").status());
        assertEquals(404, app.get("/assets/sub/.well-known/x").status());
    }
}
