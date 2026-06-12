package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Render-time flash semantics (R1 of the token-efficiency fix round 2):
 *
 * <ul>
 *   <li>Flash is consumed when a View renders, whatever the handler signature — a
 *       redirect-after-POST landing on a plain-Handler page displays flash too.</li>
 *   <li>Only cookie-borne entries are consumable: a guard's {@code flash(k, v)} set
 *       during THIS request survives a same-request render and displays next request.</li>
 *   <li>Guards can read pending flash via {@code session.flash(key)} (read-once for
 *       cookie-borne entries, peek for in-flight ones).</li>
 * </ul>
 */
class FlashLifecycleTest {

    static final String SECRET = "flash-lifecycle-test-secret-32-chars-ok!";
    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .sessions(SECRET)
            .templates("src/test/resources/views")
            .start(app -> {
                app.getSession("/setflash", (req, session) -> {
                    session.flash("notice", "saved!");
                    return Redirect.to("/page");
                });

                // Plain Handler rendering a View — before render-time consumption, flash
                // never displayed here (consumption was tied to needsSession()).
                app.get("/page", req -> Result.view("flash"));

                // Reads (and consumes) pending flash without rendering.
                app.getSession("/peek", (req, session) ->
                    Result.text("peek:" + session.flash("notice")));

                // Guard that sets flash and passes through to a same-request View render.
                app.before("/guardset/*", (req, session) -> {
                    session.flash("notice", "from guard");
                    return null;
                });
                app.get("/guardset/page", req -> Result.view("flash"));

                // Guard that reads pending flash and short-circuits on it.
                app.before("/guardread/*", (req, session) ->
                    Result.text("guard saw:" + session.flash("notice")));
                app.get("/guardread/x", req -> Result.text("unreached"));
            });
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
    }

    @BeforeEach
    void clearJar() {
        testApp.evictSessionCookie();
    }

    @Test
    void flashRendersOnPlainHandlerView() {
        testApp.get("/setflash");
        var page = testApp.get("/page");
        assertEquals(200, page.status());
        assertTrue(page.body().contains("notice=saved!"),
            "flash must render on a plain-Handler view: " + page.body());
        // Rendering consumed it: gone on the next request.
        assertEquals("peek:null", testApp.get("/peek").body());
    }

    @Test
    void guardSetFlashSurvivesSameRequestRender() {
        var page = testApp.get("/guardset/page");
        assertEquals(200, page.status());
        assertTrue(page.body().contains("notice=none"),
            "in-flight flash must not render in the request that set it: " + page.body());
        // Pending for the next request.
        assertEquals("peek:from guard", testApp.get("/peek").body());
    }

    @Test
    void guardCanReadPendingFlash() {
        testApp.get("/setflash");
        var guarded = testApp.get("/guardread/x");
        assertEquals("guard saw:saved!", guarded.body());
        // The guard's read was read-once: consumed and written back.
        assertEquals("peek:null", testApp.get("/peek").body());
    }
}
