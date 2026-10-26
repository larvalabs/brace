package com.larvalabs.brace;

/**
 * HTML escaping for the rare case a response is built by hand with {@link Result#html(String)}.
 * Templates ({@link View}) escape automatically and are the right tool for anything with
 * request or database data in it.
 */
public final class Html {
    private Html() {}

    /**
     * Escapes {@code & < > " '} so the text is safe in element content and in a quoted attribute
     * value. A {@code null} becomes the empty string.
     *
     * <p>Not safe inside {@code <script>}, {@code <style>}, an unquoted attribute, or a URL
     * attribute such as {@code href} (where {@code javascript:} still works) — use a template for
     * those.
     */
    public static String escape(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            String rep = switch (s.charAt(i)) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> null;
            };
            if (rep != null && out == null) out = new StringBuilder(s.length() + 16).append(s, 0, i);
            if (out != null) {
                if (rep != null) out.append(rep);
                else out.append(s.charAt(i));
            }
        }
        return out == null ? s : out.toString();
    }
}
