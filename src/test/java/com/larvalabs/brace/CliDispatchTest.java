package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliDispatchTest {

    @TempDir Path cwd;

    @Test
    void unknownCommandExitsOneWithStderrHint() throws Exception {
        var berr = new ByteArrayOutputStream();
        var prev = System.err;
        System.setErr(new PrintStream(berr));
        int code;
        try {
            code = Cli.dispatch(cwd, "comple", new String[]{});   // a typo, not a command
        } finally {
            System.setErr(prev);
        }
        assertEquals(1, code, "a typo'd command must not exit 0");
        assertTrue(berr.toString().contains("Unknown command: comple"), berr.toString());
        assertTrue(berr.toString().contains("brace help"), berr.toString());
    }

    @Test
    void helpCommandPrintsUsageAndExitsZero() throws Exception {
        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        int code;
        try {
            code = Cli.dispatch(cwd, "help", new String[]{});
        } finally {
            System.setOut(prev);
        }
        assertEquals(0, code);
        assertTrue(bout.toString().contains("Global commands"), bout.toString());
    }

    @Test
    void helpFlagAliasesAlsoPrintUsage() throws Exception {
        for (String alias : new String[]{"--help", "-h"}) {
            var bout = new ByteArrayOutputStream();
            var prev = System.out;
            System.setOut(new PrintStream(bout));
            int code;
            try {
                code = Cli.dispatch(cwd, alias, new String[]{});
            } finally {
                System.setOut(prev);
            }
            assertEquals(0, code, alias);
            assertTrue(bout.toString().contains("Global commands"), alias);
        }
    }
}
