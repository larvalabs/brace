package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Correctness review L1 (trailing slash), L3 ({@code View.of} odd args), L4 (no-op session writes). */
class SmallCorrectnessFixesTest {

    static TestApp app;

    @BeforeAll
    static void setup() throws Exception {
        app = Brace.test().start(a -> {
            a.get("/plain", req -> Result.text("plain"));
            a.get("/users/{id}", req -> Result.text("user " + req.pathParam("id")));
            a.get("/", req -> Result.text("root"));
            a.post("/submit", req -> Result.text("posted")).csrf(false);
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    // --- L1: trailing slash ---

    @Test
    void trailingSlashMatchesTheCanonicalStaticRoute() {
        assertEquals(200, app.get("/plain/").status());
        assertEquals("plain", app.get("/plain/").body());
    }

    @Test
    void trailingSlashMatchesADynamicRoute() {
        assertEquals("user 42", app.get("/users/42/").body());
    }

    @Test
    void trailingSlashWorksForNonGetVerbsWithoutARedirect() {
        // Matching (rather than 301-ing) matters here: a redirect would turn this POST into a GET
        // and silently drop the body.
        var res = app.request("POST", "/submit/").body("a=1", "application/x-www-form-urlencoded").send();
        assertEquals(200, res.status());
        assertEquals("posted", res.body());
    }

    @Test
    void rootIsUnaffected() {
        assertEquals("root", app.get("/").body());
    }

    @Test
    void aGenuinelyUnknownPathStill404s() {
        assertEquals(404, app.get("/nope/").status());
        assertEquals(404, app.get("/nope").status());
    }

    // --- L3: View.of / View.render arg count ---

    @Test
    void viewOfRejectsAnOddArgumentCount() {
        var e = assertThrows(IllegalArgumentException.class,
            () -> View.of("page", "a", 1, "trailing"));
        assertTrue(e.getMessage().contains("trailing"), "the message should name the dangling key");
    }

    @Test
    void viewRenderRejectsAnOddArgumentCount() {
        assertThrows(IllegalArgumentException.class, () -> View.render("page", "a", 1, "trailing"));
    }

    @Test
    void viewOfStillAcceptsWellFormedPairs() {
        var view = View.of("page", "a", 1, "b", 2);
        assertEquals(1, view.params().get("a"));
        assertEquals(2, view.params().get("b"));
    }

    // --- L4: no-op session writes ---

    @Test
    void settingTheSameValueDoesNotMarkTheSessionModified() {
        var session = new Session();
        session.set("user", "42");
        assertTrue(session.isModified());

        var reread = Session.fromCookie(session.toCookie(secret()), secret());
        assertFalse(reread.isModified());
        reread.set("user", "42");
        assertFalse(reread.isModified(), "re-setting an identical value is not a change");
    }

    @Test
    void settingADifferentValueDoesMarkModified() {
        var session = new Session();
        session.set("user", "42");
        var reread = Session.fromCookie(session.toCookie(secret()), secret());
        reread.set("user", "43");
        assertTrue(reread.isModified());
    }

    @Test
    void removingAnAbsentKeyDoesNotMarkModified() {
        var session = new Session();
        session.remove("never-set");
        assertFalse(session.isModified(),
            "an unconditional remove() in a guard must not make every response uncacheable");
    }

    @Test
    void removingAPresentKeyDoesMarkModified() {
        var session = new Session();
        session.set("user", "42");
        var reread = Session.fromCookie(session.toCookie(secret()), secret());
        reread.remove("user");
        assertTrue(reread.isModified());
    }

    @Test
    void clearingAnEmptySessionDoesNotMarkModified() {
        var session = new Session();
        session.clear();
        assertFalse(session.isModified());
    }

    private static String secret() {
        return "small-correctness-fixes-secret-32-chars";
    }
}
