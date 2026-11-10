package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seeded fuzz over every {@link Redactor} entry point (second review of M6, finding H-1: a
 * {@code jdbc:<run>://} shape threw {@code StringIndexOutOfBoundsException} out of the redactor,
 * inside the 500 handler). Inputs are short concatenations of atoms picked to hit each scanner:
 * schemes and {@code ://}, the run alphabet and its separators, percent-escapes (valid, double,
 * malformed), {@code name=value} delimiters and brackets, user info, stack-frame prefixes, lone
 * surrogates. The same seed gives the same inputs on every run.
 *
 * <p>The public methods fail closed, so "did not throw" alone would prove nothing: the test also
 * requires that no output carries {@link Redactor#FAILED}, and it calls the unguarded name rule
 * directly.
 */
class RedactorFuzzTest {

    static final String[] ATOMS = {
        "jdbc:", "://", "https", "http", "s3", "ftp", "ldap", "a", "B", "7", "_", "-", "+", "/", ".", "~", "$", "%",
        "%2F", "%3A", "%25", "=", "\"", "'", "<", ">", ";", ",", "&", "?", "#", "@", ":", " ", "\n", "\t", "at ",
        "... ", "3", " more", "[", "]", "{", "}", "(", ")", "|", "\\", "^", "`", "token", "password", "code", "codes",
        "totp", "x", "0x", "g", "deadbeef", "a3f9Bc2d8eF1g4h5", "\uD800", "\uDC00", "é", "%ZZ", "//",
        "user:pw@", "host", "Caused by: ", "\r\n", "[REDACTED]", "[redacted]", ";password=", "h1:27017,", "./", "../",
        ".sql", "V3__", "2024q4", "-12-g3f9a1c2", "com.x.Y.z(Y.java:1)", " ~[a.jar:1.0]", "(Native Method)",
        "one_time_", "2fa", "mfa", "0000000800c8a1b0", "Http2", "Impl"
    };

    /** Inputs per run. About two seconds; raise it locally to dig (the reviewer ran 3,000,000). */
    static final int INPUTS = 200_000;

    @Test
    void noEntryPointThrowsOrFailsInsideOnSeededInputs() {
        Map<String, Function<String, Object>> entryPoints = new LinkedHashMap<>();
        entryPoints.put("redactMessage", Redactor::redactMessage);
        entryPoints.put("redactPath", Redactor::redactPath);
        entryPoints.put("redactQuery", Redactor::redactQuery);
        entryPoints.put("redactUrl", Redactor::redactUrl);
        entryPoints.put("redactStackTrace", Redactor::redactStackTrace);
        entryPoints.put("redactValue", Redactor::redactValue);
        entryPoints.put("redactHeaders", s -> {
            var headers = new LinkedHashMap<String, Object>();
            headers.put("X-Thing", s);
            headers.put("Referer", s);
            headers.put("Forwarded", s);
            headers.put(s, s);
            return Redactor.redactHeaders(headers);
        });
        entryPoints.put("redact", s -> Redactor.redact(Map.of("k", s, s, Map.of(s, List.of(s)))));
        entryPoints.put("isSensitive", s -> Redactor.sensitiveName(s, true));
        entryPoints.put("isSensitiveField", s -> Redactor.sensitiveName(s, false));
        entryPoints.put("isSecretShaped", Redactor::isSecretShaped);

        var rnd = new Random(42);
        var failures = new ArrayList<String>();
        var sb = new StringBuilder();
        for (int n = 0; n < INPUTS && failures.size() < 10; n++) {
            sb.setLength(0);
            int atoms = 1 + rnd.nextInt(10);
            for (int k = 0; k < atoms; k++) sb.append(ATOMS[rnd.nextInt(ATOMS.length)]);
            String in = sb.toString();
            for (var e : entryPoints.entrySet()) {
                try {
                    Object out = e.getValue().apply(in);
                    if (String.valueOf(out).contains(Redactor.FAILED)) {
                        failures.add(e.getKey() + " failed inside on " + printable(in));
                    }
                } catch (Throwable t) {
                    failures.add(e.getKey() + " threw " + t + " on " + printable(in));
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static String printable(String s) {
        var out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c >= ' ' && c < 0x7f && c != '"' && c != '\\') out.append(c);
            else out.append(String.format("\\u%04x", (int) c));
        }
        return out.append('"').toString();
    }
}
