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
}
