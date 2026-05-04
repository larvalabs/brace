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
        return modeFrom(System.console() != null, jsonFlag, prettyFlag);
    }

    public static String table(List<String> headers, List<List<String>> rows) {
        return table(headers, rows, 200);
    }

    public static String table(List<String> headers, List<List<String>> rows, int maxWidth) {
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

    public static String json(Object value) {
        try {
            return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public static String jsonCompact(Object value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public static void printError(String message) {
        System.err.println("✗ " + message);
    }

    public static void printSuccess(String message) {
        System.out.println("✓ " + message);
    }
}
