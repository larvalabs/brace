package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1/L2 (2026-07 security review): {@link Result#cookie} appended the value raw ahead of the
 * framework's own attributes, so a {@code ;} in it injected cookie attributes — a handler
 * setting a cookie from user input could have that cookie re-scoped by the input. The ops
 * session cookie was also pinned to {@code Path=/}, attaching an operator credential to every
 * application request.
 */
class CookieValidationTest {

    @Test
    void semicolonInValueCannotInjectAttributes() {
        // Verified on the wire before the fix: this produced
        //   Set-Cookie: c=1; Path=/; Domain=evil; Max-Age=60; Path=/; HttpOnly; SameSite=Lax
        var ex = assertThrows(IllegalArgumentException.class, () ->
            Result.text("ok").cookie("c", "1; Path=/; Domain=evil", 60, true, false, "Lax"));
        assertTrue(ex.getMessage().contains("inject cookie attributes"), ex.getMessage());
    }

    @Test
    void otherUnsafeValueCharactersAreRejected() {
        String[] bad = {"a,b", "a b", "a\"b", "a\\b", "a\r\nb", "a\tb"};
        for (String value : bad) {
            assertThrows(IllegalArgumentException.class,
                () -> Result.text("ok").cookie("c", value, 60, true, false, "Lax"),
                "should have rejected value: " + value);
        }
    }

    @Test
    void invalidCookieNamesAreRejected() {
        String[] bad = {"", "a b", "a;b", "a=b", "a,b", "a\tb", "a(b)"};
        for (String name : bad) {
            assertThrows(IllegalArgumentException.class,
                () -> Result.text("ok").cookie(name, "v", 60, true, false, "Lax"),
                "should have rejected name: " + name);
        }
    }

    @Test
    void ordinaryCookiesStillWork() {
        var result = Result.text("ok").cookie("theme", "dark", 3600, true, false, "Lax");
        assertEquals("theme=dark; Max-Age=3600; Path=/; HttpOnly; SameSite=Lax",
            result.setCookies().get(0));
    }

    @Test
    void pathCanBeNarrowed() {
        var result = Result.text("ok").cookie("t", "v", 60, true, true, "Strict", "/ops");
        assertEquals("t=v; Max-Age=60; Path=/ops; HttpOnly; Secure; SameSite=Strict",
            result.setCookies().get(0));
    }

    @Test
    void base64UrlAndBase64ValuesArePermitted() {
        // Real session/ops tokens must not trip the validator.
        String token = OpsToken.create("a-signing-secret-at-least-32-chars-x", 60, OpsScope.READ, "kid");
        assertDoesNotThrow(() -> Result.text("ok").cookie("t", token, 60, true, true, "Strict"));
        assertDoesNotThrow(() -> Result.text("ok").cookie("t", "YWJjZA==", 60, true, true, "Strict"));
    }
}
