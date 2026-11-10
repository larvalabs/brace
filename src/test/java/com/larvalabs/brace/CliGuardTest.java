package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;

/** {@code Cli.main} reports an Error as a short message and exit code 1, not a stack trace. */
class CliGuardTest {

    private record Run(int code, String err) {}

    private static Run run(Callable<Integer> c) {
        var err = new ByteArrayOutputStream();
        var prev = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return new Run(Cli.guarded(c), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(prev);
        }
    }

    @Test
    void passesTheExitCodeThrough() {
        assertEquals(2, run(() -> 2).code());
    }

    @Test
    void anExceptionIsOneSanitizedLine() {
        var r = run(() -> { throw new IllegalStateException("bad\u001b[2J\nstate"); });
        assertEquals(1, r.code());
        assertEquals(1, r.err().strip().lines().count(), r.err());
        assertFalse(r.err().contains("\u001b"), r.err());
    }

    @Test
    void anErrorExitsNonZeroWithAShortMessageAndNoStackTrace() {
        for (Error e : new Error[]{new OutOfMemoryError("Java heap space"), new StackOverflowError(),
                new NoClassDefFoundError("x"), new AssertionError("boom")}) {
            var r = run(() -> { throw e; });
            assertEquals(1, r.code(), e.toString());
            assertEquals(1, r.err().strip().lines().count(), r.err());
            assertFalse(r.err().contains("\tat "), r.err());
            assertTrue(r.err().contains(e.getClass().getSimpleName()), r.err());
        }
    }
}
