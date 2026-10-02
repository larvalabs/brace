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

    @Test
    void opsHelpListsSubcommandsAndExitsZero() throws Exception {
        // A project layout, so `ops keypair` would really run if --help were ignored.
        java.nio.file.Files.createDirectories(cwd.resolve("src/main/java"));
        String[][] forms = {{}, {"help"}, {"--help"}, {"-h"}, {"keypair", "--help"}, {"dashboard", "-h"}};
        for (String[] form : forms) {
            var bout = new ByteArrayOutputStream();
            var prev = System.out;
            System.setOut(new PrintStream(bout));
            int code;
            try {
                code = Cli.dispatch(cwd, "ops", form);
            } finally {
                System.setOut(prev);
            }
            String label = "brace ops " + String.join(" ", form);
            assertEquals(0, code, label);
            assertTrue(bout.toString().contains("brace ops keypair"), label + ": " + bout);
            assertTrue(bout.toString().contains("brace ops dashboard"), label + ": " + bout);
        }
        // `keypair --help` must print help, not generate a key.
        assertFalse(java.nio.file.Files.exists(cwd.resolve("ops-private.key")));
    }

    @Test
    void unknownOpsCommandExitsOneWithHint() throws Exception {
        var berr = new ByteArrayOutputStream();
        var prev = System.err;
        System.setErr(new PrintStream(berr));
        int code;
        try {
            code = Cli.dispatch(cwd, "ops", new String[]{"keypiar"});
        } finally {
            System.setErr(prev);
        }
        assertEquals(1, code);
        assertTrue(berr.toString().contains("Unknown ops command: keypiar"), berr.toString());
        assertTrue(berr.toString().contains("brace ops --help"), berr.toString());
    }
}
