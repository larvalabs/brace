package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H5: the session cookie is decrypted at most once per request, and not at all on a route that
 * never touches the session. The behavior tests in {@link CsrfLazyMintTest} pass either way (an
 * extra decrypt changes no response), which is how this was lost in merge b8609b6 unnoticed, so
 * these count decrypts on the handler.
 */
class SessionDecryptCountTest {

    static final String SECRET = "session-decrypt-count-secret-32-chars!!";
    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .sessions(SECRET)
            .templates("src/test/resources/views")
            .start(app -> {
                // CSRF-required route whose handler takes no Session.
                app.post("/submit", req -> Result.text("ok"));
                // CSRF-exempt route that never touches the session.
                app.get("/api/data", req -> Json.of(Map.of("ok", true))).csrf(false);
                // CSRF-exempt route that renders flash (R1).
                app.getSession("/setflash", (req, session) -> {
                    session.flash("notice", "saved!");
                    return Redirect.to("/api/page");
                });
                app.get("/api/page", req -> Result.view("flash")).csrf(false);
            });
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
        // Static engine is process-wide; reset so stub-mode tests (ResultTest) are unaffected.
        View.setEngine(null);
    }

    @BeforeEach
    void clearJar() {
        testApp.evictSessionCookie();
    }

    private static long decrypts() {
        return testApp.app().handler().sessionDecrypts.sum();
    }

    @Test
    void mutatingCsrfRequestDecryptsTheCookieOnce() {
        var session = Session.of("user", "1");
        long before = decrypts();
        var resp = testApp.postWithCsrf("/submit", Map.of(), session);
        assertEquals(200, resp.status(), resp.body());
        assertEquals(before + 1, decrypts(), "the CSRF check and token setup must share one decrypt");
    }

    @Test
    void csrfExemptRouteThatNeverTouchesTheSessionDecryptsNothing() {
        var session = Session.of("user", "1");
        long before = decrypts();
        var resp = testApp.get("/api/data", session);
        assertEquals(200, resp.status());
        assertEquals(before, decrypts(), ".csrf(false) route that reads no session must not decrypt it");
    }

    @Test
    void flashStillRendersOnCsrfExemptRoute() {
        testApp.get("/setflash");
        long before = decrypts();
        var page = testApp.get("/api/page");
        assertEquals(200, page.status());
        assertTrue(page.body().contains("notice=saved!"), "flash must render on a .csrf(false) view: " + page.body());
        assertEquals(before + 1, decrypts(), "rendering flash decrypts the cookie once");
        // Rendering consumed it, and the consumption was written back.
        assertTrue(testApp.get("/api/page").body().contains("notice=none"));
    }
}
