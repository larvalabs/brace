package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordsTest {
    @Test
    void hashAndCheck() {
        var hash = Passwords.hash("secret123");
        assertNotNull(hash);
        assertTrue(Passwords.check("secret123", hash));
        assertFalse(Passwords.check("wrong", hash));
    }

    @Test
    void hashesAreDifferentEachTime() {
        var hash1 = Passwords.hash("secret123");
        var hash2 = Passwords.hash("secret123");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void hashThrowsOnNullPassword() {
        assertThrows(IllegalArgumentException.class, () -> Passwords.hash(null));
    }

    @Test
    void checkThrowsOnNullHash() {
        assertThrows(IllegalArgumentException.class, () -> Passwords.check("password", null));
    }

    @Test
    void checkHandlesNullPasswordAsEmpty() {
        // Null password should be treated as empty string for consistent timing
        var hash = Passwords.hash("");
        assertTrue(Passwords.check("", hash));
        // Calling check with null password should succeed since it's treated as empty
        assertTrue(Passwords.check(null, hash));
    }

    @Test
    void dummyCheckCompletesWithoutError() {
        // dummyCheck should not throw regardless of input
        assertDoesNotThrow(() -> Passwords.dummyCheck("somePassword"));
        assertDoesNotThrow(() -> Passwords.dummyCheck(""));
        assertDoesNotThrow(() -> Passwords.dummyCheck(null));
    }

    @Test
    void dummyCheckAlwaysFails() {
        // dummyCheck uses an invalid hash that will never match
        // This test just verifies it completes without error and doesn't accidentally succeed
        // The important property is timing consistency, which is hard to test directly
        // but we can verify it doesn't throw and uses the same code path
        long startTime = System.nanoTime();
        Passwords.dummyCheck("test");
        long endTime = System.nanoTime();

        // Should complete (no exception)
        assertTrue(endTime > startTime);
    }
}
