package com.larvalabs.brace;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Password hashing and verification using bcrypt.
 *
 * <p><strong>Enumeration-timing mitigation:</strong> Always use {@link #dummyCheck(String)}
 * in the "user not found" path to prevent attackers from distinguishing valid usernames
 * by observing response time differences. Example:
 *
 * <pre>{@code
 * var user = db.findByEmail(email);
 * if (user == null) {
 *     Passwords.dummyCheck(password);  // Constant-time no-op for timing consistency
 *     return unauthorized("Invalid credentials");
 * }
 *
 *  if (!Passwords.check(password, user.passwordHash)) {
 *     return unauthorized("Invalid credentials");
 * }
 * }</pre>
 *
 * <p>Without the dummy check, the "user not found" path returns immediately (fast),
 * while a valid user with wrong password takes time for bcrypt verification. This
 * timing leak reveals whether a given email is registered — attackers can enumerate
 * valid usernames. The dummy check performs a harmless bcrypt operation for consistency.
 */
public class Passwords {
    /**
     * Hash a password using bcrypt with work factor 12.
     *
     * @param password the plaintext password
     * @return the bcrypt hash (can be stored in the database)
     * @throws IllegalArgumentException if password is null
     */
    public static String hash(String password) {
        if (password == null) {
            throw new IllegalArgumentException("password must not be null");
        }
        return BCrypt.hashpw(password, BCrypt.gensalt(12));
    }

    /**
     * Verify a plaintext password against a bcrypt hash.
     *
     * @param password the plaintext password to check
     * @param hash the bcrypt hash (from the database)
     * @return true if the password matches the hash, false otherwise
     * @throws IllegalArgumentException if hash is null
     */
    public static boolean check(String password, String hash) {
        if (hash == null) {
            throw new IllegalArgumentException("hash must not be null");
        }
        if (password == null) {
            password = "";  // Treat null password as empty for consistent timing
        }
        return BCrypt.checkpw(password, hash);
    }

    /**
     * Perform a constant-time dummy bcrypt check to mitigate enumeration-timing attacks.
     *
     * <p>Call this in the "user not found" path (or any other path where you don't
     * have a real hash) to make the timing indistinguishable from a failed password check
     * on a valid user. Without this, the response time difference between "user not found"
     * (fast, no bcrypt) and "wrong password" (slow, bcrypt verification) leaks whether
     * a given username is registered.
     *
     * <p>The dummy hash is a valid bcrypt hash that will always fail. The check is
     * constant-time regardless of the password.
     *
     * @param password the plaintext password to check (typically from user input)
     */
    public static void dummyCheck(String password) {
        if (password == null) {
            password = "";
        }
        // Use a known valid bcrypt hash that will always fail verification.
        // This performs the same work as a real check, preserving timing.
        BCrypt.checkpw(password, "$2a$12$invalidhashxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
    }
}
