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
     * Make one value safe to print on a single line of a human's terminal. The value came from a
     * server or a config file (the URL is in a committed {@code .brace}, so a pull request picks
     * the server), and without this it could:
     * <ul>
     *   <li>emit ESC/CSI/OSC sequences that rewrite earlier output or set the window title
     *       (C0 controls, DEL, the C1 range U+0080 to U+009F);</li>
     *   <li>forge whole lines, e.g. a passing check and a {@code →} follow-up command, or scroll
     *       a warning away ({@code \n}, {@code \r}, U+2028, U+2029; {@code \t} too);</li>
     *   <li>make the line read differently from what it says (Unicode FORMAT characters: bidi
     *       embeddings, overrides and isolates, zero-width characters, the BOM, the soft
     *       hyphen).</li>
     * </ul>
     * Each such character is shown as a visible escape, as text: {@code \n}, {@code \x1b}, or a
     * backslash, {@code u} and the hex code point (four digits, or in braces above U+FFFF), so
     * the operator can see what was there. Other text,
     * accents, CJK, emoji and NBSP included, is unchanged; a zero width joiner is a FORMAT
     * character, so a joined emoji sequence shows its parts with the joiner escaped. JSON mode
     * doesn't need this: Jackson escapes control characters.
     */
    public static String sanitizeLine(String s) {
        return escape(s, false);
    }

    /**
     * {@link #sanitizeLine} for multi-line text whose line breaks this CLI laid out itself, a
     * rendered table or report: {@code \n}, CRLF and {@code \t} are kept, everything else
     * {@code sanitizeLine} escapes is escaped (a lone {@code \r} too: it returns to the start of
     * the line and overwrites it). A server value going into such text must still be passed
     * through {@code sanitizeLine} first, or its own newlines would forge lines.
     */
    public static String sanitize(String s) {
        return escape(s, true);
    }

    private static String escape(String s, boolean keepLineBreaks) {
        if (s == null) return null;
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            boolean keep = keepLineBreaks
                && (cp == '\n' || cp == '\t' || (cp == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n'));
            boolean bad = !keep && unsafe(cp);
            if (bad && sb == null) sb = new StringBuilder(s.length() + 16).append(s, 0, i);
            if (sb != null) {
                if (bad) appendEscape(sb, cp);
                else sb.appendCodePoint(cp);
            }
            i += n;
        }
        return sb == null ? s : sb.toString();
    }

    private static boolean unsafe(int cp) {
        if (cp < 0x20 || cp == 0x7f || (cp >= 0x80 && cp <= 0x9f)) return true;
        int type = Character.getType(cp);
        return type == Character.FORMAT
            || type == Character.LINE_SEPARATOR        // U+2028
            || type == Character.PARAGRAPH_SEPARATOR   // U+2029
            || type == Character.SURROGATE;            // an unpaired half: not text, prints as '?'
    }

    private static void appendEscape(StringBuilder sb, int cp) {
        switch (cp) {
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> {
                if (cp < 0x100) sb.append(String.format("\\x%02x", cp));
                else if (cp <= 0xFFFF) sb.append(String.format("\\u%04x", cp));
                else sb.append(String.format("\\u{%x}", cp));
            }
        }
    }

    /**
     * Human-mode {@code println} of ONE line: the whole string goes through
     * {@link #sanitizeLine}, so a value's newline can't start a line of its own. For text with
     * line breaks of this CLI's own (a table), use {@link #print}.
     */
    public static void println(String s) {
        System.out.println(sanitizeLine(s));
    }

    public static void println() {
        System.out.println();
    }

    /**
     * Human-mode {@code print} of multi-line text this CLI laid out (see {@link #sanitize}):
     * server values in it must already have gone through {@link #sanitizeLine} or
     * {@link #table}.
     */
    public static void print(String s) {
        System.out.print(sanitize(s));
    }

    /**
     * Human-mode {@code printf}: every argument that isn't a number or a boolean is printed
     * through {@link #sanitizeLine}, so line breaks come only from the format string.
     */
    public static void printf(String format, Object... args) {
        Object[] clean = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            clean[i] = a == null || a instanceof Number || a instanceof Boolean ? a : sanitizeLine(String.valueOf(a));
        }
        System.out.print(sanitize(String.format(format, clean)));
    }

    public static String table(List<String> headers, List<List<String>> rows) {
        return table(headers, rows, 200);
    }

    public static String table(List<String> headers, List<List<String>> rows, int maxWidth) {
        // Each cell on one line: a newline in a value must not start a row of its own.
        headers = headers.stream().map(CliOutput::sanitizeLine).toList();
        rows = rows.stream().map(r -> r.stream().map(CliOutput::sanitizeLine).toList()).toList();
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
            if (cell.length() > widths[i]) {
                int cut = widths[i] - 1;
                if (cut > 0 && Character.isHighSurrogate(cell.charAt(cut - 1))) cut--;   // keep pairs whole
                cell = cell.substring(0, cut) + "…";
            }
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

    /** One line on stderr; the message goes through {@link #sanitizeLine}. */
    public static void printError(String message) {
        System.err.println("✗ " + sanitizeLine(message));
    }

    /** One line; the message goes through {@link #sanitizeLine}. */
    public static void printSuccess(String message) {
        System.out.println("✓ " + sanitizeLine(message));
    }

    /** One line; the message goes through {@link #sanitizeLine}. */
    public static void printInfo(String message) {
        System.out.println("▸ " + sanitizeLine(message));
    }
}
