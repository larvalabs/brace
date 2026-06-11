package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for session secret validation on startup.
 */
class SecretValidationTest {

    @Test
    void shortSecretThrowsException() {
        var app = Brace.app();
        var ex = assertThrows(IllegalArgumentException.class, () ->
            app.sessions("short"));
        assertTrue(ex.getMessage().contains("at least 32 characters"));
    }

    @Test
    void emptySecretThrowsException() {
        var app = Brace.app();
        var ex = assertThrows(IllegalArgumentException.class, () ->
            app.sessions(""));
        assertTrue(ex.getMessage().contains("cannot be null or empty"));
    }

    @Test
    void nullSecretThrowsException() {
        var app = Brace.app();
        var ex = assertThrows(IllegalArgumentException.class, () ->
            app.sessions((String) null));
        assertTrue(ex.getMessage().contains("cannot be null or empty"));
    }

    @Test
    void validSecretIsAccepted() {
        var app = Brace.app();
        // 32+ character random-looking string
        assertDoesNotThrow(() ->
            app.sessions("a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"));
    }

    @Test
    void weakSecretLogsWarning() {
        var app = Brace.app();
        // These should warn but not throw
        assertDoesNotThrow(() ->
            app.sessions("this-is-a-test-secret-changeme-32chars"));
        assertDoesNotThrow(() ->
            app.sessions("my-secret-password-placeholder-value"));
    }

    @Test
    void sessionOptionsValidation() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
            Brace.app().sessions(SessionOptions.of("short")));
        assertTrue(ex.getMessage().contains("at least 32 characters"));
    }

    @Test
    void validSessionOptionsAccepted() {
        assertDoesNotThrow(() ->
            Brace.app().sessions(SessionOptions.of("a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6")));
    }

    @Test
    void oldPlaceholderIsWeak() {
        // The old placeholder from brace new scaffolds should warn
        var app = Brace.app();
        assertDoesNotThrow(() ->
            app.sessions("CHANGE-ME-to-a-random-string-at-least-32-chars"));
    }

    @Test
    void changeHyphenVariantsAreWeak() {
        var app1 = Brace.app();
        var app2 = Brace.app();
        var app3 = Brace.app();

        // change-me variant
        assertDoesNotThrow(() ->
            app1.sessions("this-is-my-change-me-secret-at-least-32-chars"));
        // change_me variant
        assertDoesNotThrow(() ->
            app2.sessions("this-is-my-change_me-secret-at-least-32-chars"));
        // changeme variant (already tested but being explicit)
        assertDoesNotThrow(() ->
            app3.sessions("this-is-my-changeme-secret-at-least-32-characters"));
    }
}
