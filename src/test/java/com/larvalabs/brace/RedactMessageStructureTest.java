package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness review L12: {@code redactMessage} must preserve the message's structure. It used to
 * split on a delimiter class and rejoin with single spaces, so commas, colons, brackets, quotes and
 * newlines were all replaced with a space — even when nothing was redacted. This is the text stored
 * in {@code ops_errors.message} and shown on {@code /ops/errors}.
 */
class RedactMessageStructureTest {

    @Test
    void punctuationSurvivesWhenNothingIsRedacted() {
        // A realistic Hibernate message: long enough to pass the old fast path, nothing secret.
        String message = "could not execute statement [n/a]; SQL: select p1_0.id from posts p1_0";
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void newlinesAndIndentationSurvive() {
        String message = "Constraint violation:\n  table = posts\n  column = title_unique_index";
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void punctuationSurvivesAroundARedactedToken() {
        String message = "auth failed (token=A1b2C3d4E5f6G7h8J9k0, retrying)";
        String redacted = Redactor.redactMessage(message);
        assertEquals("auth failed (token=[redacted], retrying)", redacted);
    }

    @Test
    void theSecretIsStillRemoved() {
        String message = "bearer A1b2C3d4E5f6G7h8J9k0L1m2 rejected";
        String redacted = Redactor.redactMessage(message);
        assertFalse(redacted.contains("A1b2C3d4E5f6G7h8J9k0L1m2"), redacted);
        assertTrue(redacted.contains("[redacted]"), redacted);
        assertEquals("bearer [redacted] rejected", redacted);
    }

    @Test
    void multipleSecretsAreEachRedactedInPlace() {
        String message = "a=A1b2C3d4E5f6G7h8J9k0; b=Z9y8X7w6V5u4T3s2R1q0";
        assertEquals("a=[redacted]; b=[redacted]", Redactor.redactMessage(message));
    }

    @Test
    void shortTokensAndOrdinaryWordsAreUntouched() {
        assertEquals("user 42 not found", Redactor.redactMessage("user 42 not found"));
    }

    @Test
    void nullAndEmptyAreUnchanged() {
        assertEquals(null, Redactor.redactMessage(null));
        assertEquals("", Redactor.redactMessage(""));
    }

    @Test
    void aBareJwtIsStillFullyReplaced() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals(Redactor.PLACEHOLDER, Redactor.redactMessage(jwt));
    }

    @Test
    void leadingAndTrailingDelimitersAreNotLost() {
        assertEquals("  spaced  ", Redactor.redactMessage("  spaced  "));
        assertEquals("(A1b2C3d4E5f6G7h8J9k0)".replace("A1b2C3d4E5f6G7h8J9k0", "[redacted]"),
            Redactor.redactMessage("(A1b2C3d4E5f6G7h8J9k0)"));
    }
}
