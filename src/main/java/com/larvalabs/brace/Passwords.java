package com.larvalabs.brace;

import org.mindrot.jbcrypt.BCrypt;

public class Passwords {
    public static String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(12));
    }

    public static boolean check(String password, String hash) {
        return BCrypt.checkpw(password, hash);
    }
}
