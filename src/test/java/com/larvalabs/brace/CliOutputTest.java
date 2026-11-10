package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CliOutputTest {

    @Test
    void stdoutIsTtyHonorsLauncherProperty() {
        String prev = System.getProperty("brace.stdout.tty");
        try {
            System.setProperty("brace.stdout.tty", "true");
            assertTrue(CliOutput.stdoutIsTty());
            System.setProperty("brace.stdout.tty", "false");
            assertFalse(CliOutput.stdoutIsTty());
        } finally {
            if (prev == null) System.clearProperty("brace.stdout.tty");
            else System.setProperty("brace.stdout.tty", prev);
        }
    }

    @Test
    void autoModeKeysOnStdoutIsTtyNotSystemConsole() {
        // autoMode must agree with stdoutIsTty() (the shim's -Dbrace.stdout.tty answer),
        // not System.console() — on JLine-backed JDKs console() is non-null even under
        // redirection, which made `brace errors | jq` pick HUMAN while `brace test | cat`
        // in the same pipeline went concise.
        String prev = System.getProperty("brace.stdout.tty");
        try {
            System.setProperty("brace.stdout.tty", "false");
            assertEquals(CliOutput.Mode.JSON, CliOutput.autoMode(false, false));
            System.setProperty("brace.stdout.tty", "true");
            assertEquals(CliOutput.Mode.HUMAN, CliOutput.autoMode(false, false));
        } finally {
            if (prev == null) System.clearProperty("brace.stdout.tty");
            else System.setProperty("brace.stdout.tty", prev);
        }
    }

    @Test
    void tableRendersHeadersAndRows() {
        var out = CliOutput.table(
            List.of("ID", "MESSAGE"),
            List.of(
                List.of("1", "first"),
                List.of("42", "second")));
        assertTrue(out.contains("ID"));
        assertTrue(out.contains("MESSAGE"));
        assertTrue(out.contains("first"));
        assertTrue(out.contains("second"));
    }

    @Test
    void tableAlignsColumnWidths() {
        var out = CliOutput.table(
            List.of("A", "B"),
            List.of(List.of("short", "long-value")));
        var lines = out.split("\n");
        int bHeader = lines[0].indexOf("B");
        int bRow = lines[1].indexOf("long-value");
        assertEquals(bHeader, bRow);
    }

    @Test
    void truncatesOverlongValues() {
        var huge = "x".repeat(500);
        var out = CliOutput.table(List.of("M"), List.of(List.of(huge)), 80);
        for (var line : out.split("\n")) {
            assertTrue(line.length() <= 80, "line too long: " + line.length());
        }
    }

    @Test
    void truncatesMultipleWideColumns() {
        var out = CliOutput.table(
            List.of("A", "B"),
            List.of(List.of("x".repeat(50), "y".repeat(50))),
            40);
        for (var line : out.split("\n")) {
            assertTrue(line.length() <= 40, "line too long: " + line.length());
        }
    }

    @Test
    void formatsJson() throws Exception {
        var out = CliOutput.json(Map.of("ok", true, "count", 5));
        assertTrue(out.contains("\"ok\""));
        assertTrue(out.contains("\"count\""));
    }

    @Test
    void jsonModeIsCompactSingleLine() throws Exception {
        // M12: agent (JSON) mode must not pretty-print — one line, no indentation.
        var out = CliOutput.json(Map.of("ok", true, "nested", Map.of("a", List.of(1, 2, 3))));
        assertFalse(out.contains("\n"), "agent-mode JSON must be one line: " + out);
        assertFalse(out.contains("  "), "agent-mode JSON must not be indented: " + out);
    }

    @Test
    void modeFromEnvForcesJson() {
        assertEquals(CliOutput.Mode.JSON, CliOutput.modeFrom(false, false, false));
        assertEquals(CliOutput.Mode.HUMAN, CliOutput.modeFrom(true, false, false));
        assertEquals(CliOutput.Mode.JSON, CliOutput.modeFrom(true, true, false));
        assertEquals(CliOutput.Mode.HUMAN, CliOutput.modeFrom(false, false, true));
    }

    private static String capture(Runnable r) {
        var bout = new java.io.ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new java.io.PrintStream(bout));
        try { r.run(); } finally { System.setOut(prev); }
        return bout.toString();
    }

    private static final String ESC = "\u001b[2J\u001b[1A";
    private static final String OSC = "\u001b]0;pwned\u0007";

    @Test
    void sanitizeReplacesControlCharactersButKeepsNewlineAndTab() {
        assertEquals("a?[2J?]0;x?b\n\tc", CliOutput.sanitize("a\u001b[2J\u001b]0;x\u0007b\n\tc"));
        assertEquals("a?b?c?d", CliOutput.sanitize("a\u007fb\u0085c\u009bd"));
        assertEquals("cr?lf\n", CliOutput.sanitize("cr\rlf\n"));
        assertEquals("plain ünïcode ✓", CliOutput.sanitize("plain ünïcode ✓"));
        assertNull(CliOutput.sanitize(null));
    }

    @Test
    void tableNeutralizesControlCharactersInCellsAndHeaders() {
        String out = CliOutput.table(java.util.List.of("A" + ESC, "B"),
            java.util.List.of(java.util.List.of("x" + OSC, "y")));
        assertFalse(out.indexOf('\u001b') >= 0 || out.indexOf('\u0007') >= 0, out);
    }

    @Test
    void printHelpersNeutralizeControlCharacters() {
        String out = capture(() -> {
            CliOutput.println("a" + ESC);
            CliOutput.printf("%s|%d%n", OSC, 3);
            CliOutput.print("p" + ESC);
            CliOutput.printSuccess("ok" + OSC);
            CliOutput.printInfo("info" + ESC);
        });
        assertFalse(out.indexOf('\u001b') >= 0 || out.indexOf('\u0007') >= 0, out);
        assertTrue(out.contains("|3\n"));
    }

    @Test
    void errorMessagesAreSanitizedButJsonKeepsDataEscaped() {
        var err = new java.io.ByteArrayOutputStream();
        var prev = System.err;
        System.setErr(new java.io.PrintStream(err));
        try { CliOutput.printError("bad" + ESC + OSC); } finally { System.setErr(prev); }
        assertFalse(err.toString().indexOf('\u001b') >= 0, err.toString());

        String json = CliOutput.json(java.util.Map.of("m", "x" + ESC));
        assertTrue(json.toLowerCase().contains("\\u001b"), json);
        assertFalse(json.indexOf('\u001b') >= 0, json);
    }

    @Test
    void statusRenderingNeutralizesServerStrings() throws Exception {
        var root = Json.mapper().readTree(
            "{\"app\":{\"uptime\":\"1d" + "\\u001b[2K\\u001b]0;pwn\\u0007\",\"javaVersion\":\"21\\u009b\"},\"http\":{}}");
        String out = capture(() -> CliCommands.renderStatus(root));
        assertTrue(out.contains("1d?[2K?]0;pwn?"), out);
        assertFalse(out.indexOf('\u001b') >= 0 || out.indexOf('\u0007') >= 0 || out.indexOf('\u009b') >= 0, out);
    }
}
