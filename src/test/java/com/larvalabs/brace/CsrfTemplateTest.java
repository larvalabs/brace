package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 (2026-10 security review): {@code ${csrfField}} — exactly as the docs write it — renders a
 * working hidden input through a real JTE template, and {@code ${csrfToken}} renders the masked
 * token for a meta tag. As a plain String the field was HTML-escaped into visible text, the form
 * always 403'd, and the only workaround was {@code $unsafe}.
 */
class CsrfTemplateTest {

    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .sessions("csrf-template-test-secret-at-least-32-chars")
            .templates("src/test/resources/views")
            .start(app -> {
                app.get("/csrf-form", req -> Result.view("csrfForm"));
                app.post("/csrf-form", req -> Result.text("saved"));
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

    @Test
    void csrfFieldRendersAsMarkupNotEscapedText() {
        String body = testApp.get("/csrf-form").body();
        assertTrue(body.contains("<input type=\"hidden\" name=\"_csrf\" value=\""), body);
        assertFalse(body.contains("&lt;input"), body);
    }

    @Test
    void renderedFieldValidatesOnPost() {
        String body = testApp.get("/csrf-form").body();
        String token = extract(body, "name=\"_csrf\" value=\"([^\"]+)\"");
        var res = testApp.post("/csrf-form", Map.of("_csrf", token));
        assertEquals(200, res.status());
        assertEquals("saved", res.body());
    }

    @Test
    void csrfTokenRendersInMetaTagAndValidates() {
        String body = testApp.get("/csrf-form").body();
        String token = extract(body, "<meta name=\"csrf-token\" content=\"([^\"]+)\">");
        assertEquals(200, testApp.post("/csrf-form", Map.of("_csrf", token)).status());
    }

    @Test
    void postWithoutTokenIsStillRejected() {
        testApp.get("/csrf-form");
        assertEquals(403, testApp.post("/csrf-form", Map.of()).status());
    }

    private static String extract(String body, String regex) {
        var m = Pattern.compile(regex).matcher(body);
        assertTrue(m.find(), "no match for " + regex + " in " + body);
        return m.group(1);
    }
}
