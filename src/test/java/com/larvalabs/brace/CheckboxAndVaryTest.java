package com.larvalabs.brace;

import com.larvalabs.brace.annotation.Required;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness review M2 (HTML checkboxes bind to {@code boolean}) and M3 (the framework's
 * {@code Vary: HX-Request} appends rather than clobbering).
 */
class CheckboxAndVaryTest {

    record Signup(@Required String email, boolean agree, boolean newsletter) {}

    static TestApp app;

    @BeforeAll
    static void setup() throws Exception {
        app = Brace.test().start(a -> {
            a.post("/signup", req -> {
                var form = req.form(Signup.class);
                return Result.text(form.value().agree() + "/" + form.value().newsletter());
            }).csrf(false);
            a.get("/vary-none", req -> Result.text("x"));
            a.get("/vary-one", req -> Result.text("x").header("Vary", "Accept-Encoding"));
            a.get("/vary-many", req ->
                Result.text("x").header("Vary", "Accept-Encoding, Accept-Language"));
            a.get("/vary-already", req -> Result.text("x").header("Vary", "HX-Request"));
            a.get("/vary-already-cased", req -> Result.text("x").header("Vary", "hx-request"));
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    // --- M2: checkbox binding ---

    @Test
    void checkedCheckboxBindsTrue() {
        // This is what a browser actually submits for <input type="checkbox" name="agree">.
        assertEquals("true/false", post("email=a@b.com&agree=on"));
    }

    @Test
    void absentCheckboxBindsFalse() {
        assertEquals("false/false", post("email=a@b.com"));
    }

    @Test
    void otherTruthySpellingsBind() {
        assertEquals("true/true", post("email=a@b.com&agree=true&newsletter=1"));
        assertEquals("true/true", post("email=a@b.com&agree=yes&newsletter=checked"));
        assertEquals("true/true", post("email=a@b.com&agree=ON&newsletter=True"));
    }

    @Test
    void nonTruthyValuesBindFalse() {
        assertEquals("false/false", post("email=a@b.com&agree=off&newsletter=0"));
        assertEquals("false/false", post("email=a@b.com&agree=no&newsletter=false"));
    }

    private static String post(String body) {
        return app.request("POST", "/signup")
            .body(body, "application/x-www-form-urlencoded").send().body();
    }

    // --- M3: Vary ---

    @Test
    void varyIsSetWhenTheHandlerDeclaredNone() {
        assertEquals("HX-Request", vary("/vary-none"));
    }

    @Test
    void varyPreservesTheHandlersOwnDimension() {
        assertEquals("Accept-Encoding, HX-Request", vary("/vary-one"));
    }

    @Test
    void varyPreservesEveryExistingDimension() {
        assertEquals("Accept-Encoding, Accept-Language, HX-Request", vary("/vary-many"));
    }

    @Test
    void varyIsNotDuplicatedWhenAlreadyDeclared() {
        assertEquals("HX-Request", vary("/vary-already"));
        assertEquals("hx-request", vary("/vary-already-cased"));
    }

    @Test
    void varyIsUntouchedForNonHtmxRequests() {
        var res = app.get("/vary-one");
        assertEquals("Accept-Encoding", res.header("Vary"));
        assertFalse(String.valueOf(res.header("Vary")).contains("HX-Request"));
    }

    @Test
    void varyStillCoversHtmxOnEveryPath() {
        assertTrue(vary("/vary-many").contains("HX-Request"));
    }

    private static String vary(String path) {
        return app.request("GET", path).header("HX-Request", "true").send().header("Vary");
    }
}
