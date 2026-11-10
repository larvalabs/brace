package com.larvalabs.brace;

import java.util.*;

public class CliOutput {

    public enum Mode { HUMAN, JSON }

    private CliOutput() {}

    public static Mode modeFrom(boolean isTty, boolean jsonFlag, boolean prettyFlag) {
        if (jsonFlag) return Mode.JSON;
        if (prettyFlag) return Mode.HUMAN;
        return isTty ? Mode.HUMAN : Mode.JSON;
    }

    public static Mode autoMode(boolean jsonFlag, boolean prettyFlag) {
        return modeFrom(stdoutIsTty(), jsonFlag, prettyFlag);
    }

    /**
     * Whether stdout is attached to a terminal. The launcher shim passes the
     * authoritative answer via {@code -Dbrace.stdout.tty} (shell {@code [ -t 1 ]}),
     * because {@code System.console()} on JLine-backed JDKs can be non-null even
     * when output is redirected. Fallbacks, for running {@code Cli} without the
     * shim: {@code Console.isTerminal()} where available (JDK 22+), else a
     * non-null {@code System.console()}.
     */
    public static boolean stdoutIsTty() {
        String prop = System.getProperty("brace.stdout.tty");
        if (prop != null) return Boolean.parseBoolean(prop);
        java.io.Console console = System.console();
        if (console == null) return false;
        try {
            return (boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return true;   // pre-22 JDK: a non-null console is the best signal available
        }
    }

    /**
     * Neutralize terminal control characters in a string that came from a server or a config
     * file before it reaches a human's terminal: C0 controls (except {@code \n} and {@code \t}),
     * DEL and the C1 range U+0080 to U+009F become {@code ?}. Without this a hostile server can
     * emit ESC/CSI/OSC sequences that rewrite earlier output or set the window title. JSON mode
     * does not need it: Jackson escapes control characters.
     */
    public static String sanitize(String s) {
        if (s == null) return null;
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean bad = (c < 0x20 && c != '\n' && c != '\t') || c == 0x7f || (c >= 0x80 && c <= 0x9f);
            if (bad && sb == null) sb = new StringBuilder(s.length()).append(s, 0, i);
            if (sb != null) sb.append(bad ? '?' : c);
        }
        return sb == null ? s : sb.toString();
    }

    /** Human-mode {@code println} that sanitizes. Use for anything derived from a server or config. */
    public static void println(String s) {
        System.out.println(sanitize(s));
    }

    public static void println() {
        System.out.println();
    }

    /** Human-mode {@code print} that sanitizes. */
    public static void print(String s) {
        System.out.print(sanitize(s));
    }

    /** Human-mode {@code printf} that sanitizes the formatted result. */
    public static void printf(String format, Object... args) {
        System.out.print(sanitize(String.format(format, args)));
    }

    public static String table(List<String> headers, List<List<String>> rows) {
        return table(headers, rows, 200);
    }

    public static String table(List<String> headers, List<List<String>> rows, int maxWidth) {
        headers = headers.stream().map(CliOutput::sanitize).toList();
        rows = rows.stream().map(r -> r.stream().map(CliOutput::sanitize).toList()).toList();
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) widths[i] = headers.get(i).length();
        for (var row : rows) {
            for (int i = 0; i < cols && i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i) == null ? 0 : row.get(i).length());
            }
        }

        for (;;) {
            int total = 0;
            for (int w : widths) total += w + 2;
            if (total <= maxWidth) break;
            int widest = 0;
            for (int i = 1; i < cols; i++) if (widths[i] > widths[widest]) widest = i;
            if (widths[widest] <= 8) break;
            int over = total - maxWidth;
            widths[widest] = Math.max(8, widths[widest] - over);
        }

        var sb = new StringBuilder();
        appendRow(sb, headers, widths);
        for (var row : rows) appendRow(sb, row, widths);
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> row, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.size() && row.get(i) != null ? row.get(i) : "";
            if (cell.length() > widths[i]) cell = cell.substring(0, widths[i] - 1) + "…";
            sb.append(pad(cell, widths[i]));
            if (i < widths.length - 1) sb.append("  ");
        }
        sb.append("\n");
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /**
     * Serialize a value for JSON (agent) mode: compact, one line. JSON mode exists for
     * programs — agents, `jq`, scripts — where pretty-printing only costs tokens. The
     * human/`--pretty` mode renders tables and summaries, not pretty-printed JSON.
     */
    public static String json(Object value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public static void printError(String message) {
        System.err.println("✗ " + sanitize(message));
    }

    public static void printSuccess(String message) {
        System.out.println("✓ " + sanitize(message));
    }

    public static void printInfo(String message) {
        System.out.println("▸ " + sanitize(message));
    }
}
