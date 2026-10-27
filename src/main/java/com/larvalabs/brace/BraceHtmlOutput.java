package com.larvalabs.brace;

import gg.jte.Content;
import gg.jte.TemplateOutput;
import gg.jte.html.OwaspHtmlTemplateOutput;
import gg.jte.html.escape.Escape;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The output every Brace template renders through (M4, M5 — 2026-10 security review). JTE's own
 * {@link OwaspHtmlTemplateOutput} escapes per context but guards URLs only on {@code <a href>},
 * only against a literal {@code javascript:} prefix, and treats htmx's code-bearing attributes as
 * plain text. This adds:
 *
 * <ul>
 *   <li><strong>URL attributes</strong> ({@code href}, {@code src}, {@code action},
 *       {@code formaction}, {@code hx-get}, ... on any tag, and {@code to}/{@code from}/
 *       {@code by}/{@code values} on SVG {@code <set>}/{@code <animate>}): the whole value,
 *       template text and every {@code ${...}} together, is held until its closing quote and
 *       checked the way a browser parses a URL (leading/trailing control characters and spaces
 *       stripped, tabs and newlines removed). If the template's own text chose the scheme
 *       ({@code href="mailto:${email}"}) or the URL is relative, it's written as is; if user data
 *       decides the scheme, only {@code http}, {@code https}, {@code mailto} and {@code tel}
 *       pass, plus raster {@code data:image/...} on {@code src}/{@code poster}. Anything else
 *       ({@code javascript:}, {@code data:text/html}, {@code java\tscript:}, or
 *       {@code ${scheme}://${host}} with {@code scheme = "javascript"}) renders as
 *       {@code about:invalid#blocked}.</li>
 *   <li><strong>Script attributes</strong> ({@code on*}, {@code hx-on*}, and {@code hx-vals},
 *       {@code hx-vars}, {@code hx-headers}, {@code hx-request}, {@code hx-trigger}, which htmx
 *       can evaluate): user data is escaped with {@code \}{@code uXXXX} sequences, valid in a
 *       JavaScript string, a template literal and JSON alike, so {@code hx-vals='{"q": "${q}"}'}
 *       stays valid JSON. HTML escaping alone is undone by the browser before the code runs, and
 *       JTE's JavaScript escaping for {@code on*} left {@code &} alone, so {@code &#39;} in user
 *       data closed the string.</li>
 *   <li>{@code srcdoc}: user data is escaped twice, so it is text in the frame, not markup.</li>
 * </ul>
 *
 * JTE uses an output it's given as is when it is already an HTML output, so dev (hot-reload) and
 * precompiled templates both go through this class. {@link TemplateEngine} calls {@link #finish}
 * after each render.
 */
public class BraceHtmlOutput extends OwaspHtmlTemplateOutput {

    static final String BLOCKED_URL = "about:invalid#blocked";

    private static final Set<String> URL_ATTRIBUTES = Set.of(
        "href", "src", "action", "formaction", "xlink:href", "poster", "cite", "background", "data",
        "codebase", "manifest", "hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete");
    // SVG animation can set href: <set attributeName="href" to="javascript:...">.
    private static final Set<String> ANIMATION_TAGS = Set.of("set", "animate");
    private static final Set<String> ANIMATION_VALUES = Set.of("to", "from", "by", "values");
    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto", "tel");

    // The end of a static chunk that opens a quoted attribute value: the attribute name, then
    // whatever of the value the template wrote before the first ${...}.
    private static final Pattern OPENS_VALUE = Pattern.compile(
        "\\s([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)|'([^']*))$");

    private final TemplateOutput target;
    private String tagName;
    private String attributeName;

    // An attribute value that's open: held back, not yet written, because if user data in a URL
    // value turns out to make it unsafe the whole value is replaced.
    private String valueAttribute;      // lower-cased attribute name; null when no value is held
    private char valueQuote;
    private StringBuilder valueOut;     // the value as HTML to emit: template text, escaped user data
    private StringBuilder valueRaw;     // the value as the browser reads it, for the URL check
    private int firstUserIndex;         // where in valueRaw user data first appears; -1 if none
    private boolean urlValue;           // JTE's context confirmed this is a URL attribute
    private boolean jsonValue;          // hx-vals or hx-headers: may be one piece of JSON data
    private int userWrites;             // pieces of user data in the held value
    private String soleUser;            // the last of them (the only one, if userWrites == 1)

    public BraceHtmlOutput(TemplateOutput target) {
        super(target);
        this.target = target;
    }

    @Override
    public void setContext(String tagName, String attributeName) {
        super.setContext(tagName, attributeName);
        this.tagName = tagName;
        this.attributeName = attributeName == null ? null : attributeName.toLowerCase(Locale.ROOT);
        if (valueAttribute == null || this.attributeName == null || urlValue || jsonValue) return;
        if (valueAttribute.equals(this.attributeName) && isUrlAttribute(tagName, this.attributeName)) {
            urlValue = true;
        } else if (valueAttribute.equals(this.attributeName) && isJsonAttribute(this.attributeName)) {
            jsonValue = true;
        } else {
            emitHeldValue(); // not a URL: nothing to decide, so the held start goes out now
        }
    }

    // --- template text ---

    @Override
    public void writeContent(String value) {
        writeStatic(value, 0, value.length());
    }

    @Override
    public void writeContent(String value, int beginIndex, int endIndex) {
        writeStatic(value, beginIndex, endIndex);
    }

    @Override
    public void writeBinaryContent(byte[] value) {
        // Template text pre-encoded as UTF-8: passed through untouched unless it's inside, or
        // opens, an attribute value, which needs it as text.
        if (valueAttribute == null && !mayOpenValue(value)) {
            super.writeBinaryContent(value);
            return;
        }
        String text = new String(value, StandardCharsets.UTF_8);
        writeStatic(text, 0, text.length());
    }

    private void writeStatic(String s, int begin, int end) {
        if (valueAttribute != null) {
            int close = s.indexOf(valueQuote, begin);
            if (close < 0 || close >= end) {
                valueOut.append(s, begin, end);
                valueRaw.append(s, begin, end);
                return;
            }
            valueOut.append(s, begin, close);
            valueRaw.append(s, begin, close);
            emitHeldValue();
            begin = close;
        }
        Matcher m = mayOpenValue(s, begin, end) ? OPENS_VALUE.matcher(s).region(begin, end) : null;
        if (m == null || !m.find()) {
            super.writeContent(s, begin, end);
            return;
        }
        int valueStart = m.start(2) >= 0 ? m.start(2) : m.start(3);
        super.writeContent(s, begin, valueStart);
        valueAttribute = m.group(1).toLowerCase(Locale.ROOT);
        valueQuote = m.start(2) >= 0 ? '"' : '\'';
        valueOut = new StringBuilder(end - valueStart + 32).append(s, valueStart, end);
        valueRaw = new StringBuilder(end - valueStart + 32).append(s, valueStart, end);
        firstUserIndex = -1;
        urlValue = false;
        jsonValue = false;
        userWrites = 0;
        soleUser = null;
    }

    /**
     * A cheap test that template text might end inside an attribute value: a quote after '=',
     * back to the tag's start. Other quotes are passed over, since a value can hold the other
     * kind ({@code onclick="go('}); {@link #OPENS_VALUE} makes the real decision.
     */
    private static boolean mayOpenValue(byte[] b) {
        for (int i = b.length - 1; i > 0; i--) {
            byte c = b[i];
            if (c == '"' || c == '\'') {
                int j = i - 1;
                while (j >= 0 && (b[j] == ' ' || b[j] == '\t' || b[j] == '\n' || b[j] == '\r')) j--;
                if (j >= 0 && b[j] == '=') return true;
            }
            if (c == '>' || c == '<') return false;
        }
        return false;
    }

    private static boolean mayOpenValue(String s, int begin, int end) {
        for (int i = end - 1; i > begin; i--) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i - 1;
                while (j >= begin && Character.isWhitespace(s.charAt(j))) j--;
                if (j >= begin && s.charAt(j) == '=') return true;
            }
            if (c == '>' || c == '<') return false;
        }
        return false;
    }

    /** Write the held value, replaced first if it's a URL that user data made unsafe. */
    private void emitHeldValue() {
        if (urlValue && !isSafeValue()) {
            Escape.htmlAttribute(BLOCKED_URL, target);
        } else if (jsonValue && userWrites == 1 && valueRaw.length() == soleUser.length()) {
            // The whole value is one piece of data: JSON the app built (hx-vals='${json}').
            // escaping its quotes as JavaScript would break the JSON, so it's attribute-escaped instead (the
            // browser decodes that back), unless it's a js: expression htmx would evaluate.
            Escape.htmlAttribute(evaluated(soleUser) ? "{}" : soleUser, target);
        } else {
            target.writeContent(valueOut.toString());
        }
        valueAttribute = null;
        valueOut = null;
        valueRaw = null;
        urlValue = false;
        jsonValue = false;
        userWrites = 0;
        soleUser = null;
    }

    /** Flushes a value still held at the end of a render. Called by {@link TemplateEngine}. */
    void finish() {
        if (valueAttribute != null) emitHeldValue();
    }

    // --- user data ---

    @Override
    public void writeUserContent(String value) {
        if (value == null) return;
        if (valueAttribute != null && (attributeName == null || urlValue || jsonValue)) {
            appendToHeldValue(value);
            return;
        }
        if (tagName == null || attributeName == null) {
            super.writeUserContent(value);
            return;
        }
        if (valueAttribute != null) emitHeldValue();
        if (isUrlAttribute(tagName, attributeName)) {
            // A URL value whose opening this class didn't see: judge the value on its own.
            boolean safe = isSafeUrl(value) || mediaDataUrl(attributeName, value);
            Escape.htmlAttribute(safe ? value : BLOCKED_URL, target);
        } else if (isScriptAttribute(attributeName)) {
            Escape.htmlAttribute(escapeScript(value), target);
        } else if ("srcdoc".equals(attributeName)) {
            var once = new StringBuilder(value.length() + 16);
            Escape.htmlAttribute(value, new Appender(once));
            Escape.htmlAttribute(once.toString(), target);
        } else {
            super.writeUserContent(value);
        }
    }

    /**
     * User data inside the held value. With no attribute context from JTE, it was written by a
     * {@code @template} call (or a content block) inside the value: JTE escapes that as body text,
     * which leaves quotes alone, so it's classified here by the held attribute's name instead,
     * failing safe for names that are URLs only on some tags.
     */
    private void appendToHeldValue(String value) {
        String attribute = valueAttribute;
        if (!urlValue && !jsonValue) {
            if (isUrlAttribute(tagName, attribute) || ANIMATION_VALUES.contains(attribute)) urlValue = true;
            else if (isJsonAttribute(attribute)) jsonValue = true;
        }
        if (firstUserIndex < 0) firstUserIndex = valueRaw.length();
        userWrites++;
        soleUser = value;
        valueRaw.append(value);
        var out = new Appender(valueOut);
        if (urlValue) {
            Escape.htmlAttribute(value, out);
        } else if (jsonValue || isScriptAttribute(attribute)) {
            Escape.htmlAttribute(escapeScript(value), out);
        } else if ("srcdoc".equals(attribute)) {
            var once = new StringBuilder(value.length() + 16);
            Escape.htmlAttribute(value, new Appender(once));
            Escape.htmlAttribute(once.toString(), out);
        } else {
            Escape.htmlAttribute(value, out);
        }
    }

    @Override
    public void writeUserContent(Content content) {
        if (content == null) return;
        if (valueAttribute != null && (attributeName == null || urlValue || jsonValue)) {
            var text = createOutputForAttributeContent();
            content.writeTo(text);
            appendToHeldValue(text.toString());
            return;
        }
        if (tagName != null && attributeName != null) {
            // Rendered to text first, into the HTML-aware output JTE itself uses for this (a
            // content block casts its output to one), then treated like any other value here.
            var text = createOutputForAttributeContent();
            content.writeTo(text);
            writeUserContent(text.toString());
        } else {
            if (valueAttribute != null) emitHeldValue();
            super.writeUserContent(content);
        }
    }

    // JTE writes primitives and enums past the String path; route them through it, so they keep
    // their place in a held value and count as user data.
    @Override public void writeUserContent(boolean value) { writeUserContent(String.valueOf(value)); }
    @Override public void writeUserContent(byte value) { writeUserContent(String.valueOf(value)); }
    @Override public void writeUserContent(short value) { writeUserContent(String.valueOf(value)); }
    @Override public void writeUserContent(int value) { writeUserContent(String.valueOf(value)); }
    @Override public void writeUserContent(long value) { writeUserContent(String.valueOf(value)); }
    @Override public void writeUserContent(float value) { writeUserContent(String.valueOf(value)); }
    @Override public void writeUserContent(double value) { writeUserContent(String.valueOf(value)); }
    @Override public void writeUserContent(char value) { writeUserContent(String.valueOf(value)); }
    @Override public void writeUserContent(Boolean value) { if (value != null) writeUserContent(value.toString()); }
    @Override public void writeUserContent(Number value) { if (value != null) writeUserContent(value.toString()); }
    @Override public void writeUserContent(Character value) { if (value != null) writeUserContent(value.toString()); }
    @Override public void writeUserContent(Enum<?> value) { if (value != null) writeUserContent(value.name()); }

    // --- rules ---

    private boolean isSafeValue() {
        if (firstUserIndex < 0) return true; // all template text
        String raw = valueRaw.toString();
        if ("values".equals(valueAttribute)) {
            for (String item : raw.split(";")) {
                if (!isSafeUrl(item)) return false;
            }
            return true;
        }
        if (schemeWrittenByTemplate(raw, firstUserIndex)) return true;
        return isSafeUrl(raw) || mediaDataUrl(valueAttribute, raw);
    }

    /**
     * Whether the URL's scheme, or the path, query or fragment that rules one out, is settled
     * before the first user data: then the template chose it ({@code href="mailto:${email}"},
     * {@code href="/users/${id}"}).
     */
    private static boolean schemeWrittenByTemplate(String raw, int firstUser) {
        int i = 0;
        while (i < raw.length() && raw.charAt(i) <= ' ') i++;
        for (; i < firstUser; i++) {
            char c = raw.charAt(i);
            if (c == ':' || c == '/' || c == '?' || c == '#') return true;
        }
        return false;
    }

    private static boolean isUrlAttribute(String tag, String attribute) {
        if (URL_ATTRIBUTES.contains(unprefixed(attribute))) return true;
        return tag != null && ANIMATION_TAGS.contains(tag.toLowerCase(Locale.ROOT)) && ANIMATION_VALUES.contains(attribute);
    }

    private static final Pattern RASTER_DATA_URL =
        Pattern.compile("(?i)data:image/(png|jpeg|jpg|gif|webp|avif|bmp)[;,].*", Pattern.DOTALL);

    /** On {@code src} and {@code poster}, a raster {@code data:} image, which can't run script. */
    private static boolean mediaDataUrl(String attribute, String url) {
        return (attribute.equals("src") || attribute.equals("poster")) && isRasterDataUrl(url);
    }

    /** A {@code data:} URL of a raster image type: never SVG, which can carry script. */
    static boolean isRasterDataUrl(String url) {
        return RASTER_DATA_URL.matcher(normalize(url)).matches();
    }

    /** {@code data-hx-get} is htmx's {@code hx-get}. */
    private static String unprefixed(String attribute) {
        return attribute.startsWith("data-hx-") ? attribute.substring(5) : attribute;
    }

    /** htmx attributes that take JSON (or, with a {@code js:} prefix, an expression). */
    private static boolean isJsonAttribute(String attribute) {
        String name = unprefixed(attribute);
        return name.equals("hx-vals") || name.equals("hx-headers");
    }

    /** A {@code js:}/{@code javascript:} value, which htmx evaluates as code. */
    private static boolean evaluated(String value) {
        String v = normalize(value).toLowerCase(Locale.ROOT);
        return v.startsWith("js:") || v.startsWith("javascript:");
    }

    private static boolean isScriptAttribute(String attribute) {
        String name = unprefixed(attribute);
        return name.startsWith("on") || name.startsWith("hx-on")
            || name.equals("hx-vals") || name.equals("hx-vars") || name.equals("hx-headers")
            || name.equals("hx-request") || name.equals("hx-trigger");
    }

    /**
     * Escape for a script attribute with {@code \}{@code uXXXX} for every character that could end
     * a string or start markup or a substitution: valid in JavaScript strings, template literals
     * and JSON. The result has none of {@code & < > " '}, so HTML-escaping it changes nothing.
     */
    static String escapeScript(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean escape = c == '\\' || c < 0x20 || c == '"' || c == '\'' || c == '`' || c == '$'
                || c == '&' || c == '<' || c == '>' || c == '\u2028' || c == '\u2029';
            if (escape) {
                if (out == null) out = new StringBuilder(value.length() + 16).append(value, 0, i);
                if (c == '\\') out.append("\\\\");
                else out.append("\\u").append(String.format("%04x", (int) c));
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? value : out.toString();
    }

    /**
     * Whether {@code url} is relative or uses an allowed scheme, judged the way a browser parses
     * it: leading and trailing C0 controls and spaces dropped, tabs and newlines removed anywhere.
     */
    static boolean isSafeUrl(String url) {
        String normalized = normalize(url);
        int colon = normalized.indexOf(':');
        if (colon < 0) return true;
        // A scheme only if the ':' comes before any path, query or fragment delimiter.
        for (int i = 0; i < colon; i++) {
            char c = normalized.charAt(i);
            if (c == '/' || c == '?' || c == '#') return true;
        }
        String scheme = normalized.substring(0, colon);
        if (!isScheme(scheme)) return true; // not parseable as a scheme: a relative path
        return SAFE_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    }

    private static boolean isScheme(String s) {
        if (s.isEmpty() || !isAsciiLetter(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isAsciiLetter(c) && !(c >= '0' && c <= '9') && c != '+' && c != '-' && c != '.') return false;
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static String normalize(String url) {
        int begin = 0, end = url.length();
        while (begin < end && url.charAt(begin) <= ' ') begin++;
        while (end > begin && url.charAt(end - 1) <= ' ') end--;
        var out = new StringBuilder(end - begin);
        for (int i = begin; i < end; i++) {
            char c = url.charAt(i);
            if (c != '\t' && c != '\n' && c != '\r') out.append(c);
        }
        return out.toString();
    }

    /** A TemplateOutput appending to a StringBuilder sized for its content. */
    private record Appender(StringBuilder sb) implements TemplateOutput {
        @Override public void writeContent(String value) { sb.append(value); }
        @Override public void writeContent(String value, int beginIndex, int endIndex) { sb.append(value, beginIndex, endIndex); }
    }
}
