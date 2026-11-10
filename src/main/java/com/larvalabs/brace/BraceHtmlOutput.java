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
        // L7: lower-cased, because JTE compares the tag to "script" exactly and <Script> is one.
        if (tagName != null) tagName = tagName.toLowerCase(Locale.ROOT);
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
        if (valueAttribute == null) {
            scan(value);
            if (!endsInStartTagValue()) {
                super.writeBinaryContent(value);
                return;
            }
            String text = new String(value, StandardCharsets.UTF_8);
            place(text, 0, text.length());
            return;
        }
        String text = new String(value, StandardCharsets.UTF_8);
        writeStatic(text, 0, text.length());
    }

    private void writeStatic(String s, int begin, int end) {
        scan(s, begin, end);
        place(s, begin, end);
    }

    /** Writes template text {@link #scan} has already read, closing and opening held values. */
    private void place(String s, int begin, int end) {
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
        // H1: a value is held only when the text ends inside a quoted attribute value of a start
        // tag. Text that merely looks like one (`var a = "` in a script, `a = "5` in a paragraph)
        // never opens a hold.
        Matcher m = endsInStartTagValue() ? OPENS_VALUE.matcher(s).region(begin, end) : null;
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

    // --- where the output is ---
    //
    // A tokenizer over the template's own text (and anything else written as trusted markup:
    // $unsafe{...}, content blocks), following the HTML parsing rules closely enough to know
    // whether the next ${...} lands in body text, inside a tag, inside a quoted attribute value,
    // or in the body of a raw-text element (<script>, <style>, <textarea>, <title>), where
    // markup isn't parsed and nothing is an attribute.
    //
    // H1 (2026-11 security review). A held attribute value NEVER opens inside a raw-text element
    // or in body text: it opens only when template text ends in the state VALUE_QUOTED of a start
    // tag. Before this, the end of each text chunk was matched against a pattern on its own, so
    // `<script>var a = "${x}"` held a "value" named `a` and gave x attribute escaping in the
    // middle of a script. The decision rests on this class's own reading of the output, not on
    // the tag JTE reports with setContext: JTE's context says where the *template source* is,
    // which isn't where the output is when a @template call renders inside an attribute value.
    //
    // User data never changes the state: it is escaped so that it can't close a tag, a quoted
    // value or a raw-text element.

    private static final int DATA = 0, LT = 1, END_TAG_OPEN = 2, TAG_NAME = 3, BEFORE_ATTR = 4,
        ATTR_NAME = 5, AFTER_ATTR_NAME = 6, BEFORE_VALUE = 7, VALUE_QUOTED = 8, VALUE_UNQUOTED = 9,
        RAW = 10, RAW_LT = 11, RAW_END = 12, MARKUP = 13, MARKUP_DASH = 14, COMMENT = 15,
        COMMENT_DASH = 16, COMMENT_DASH_DASH = 17, BOGUS = 18;
    private static final int MAX_NAME = 64;

    // Elements whose body is text up to their own end tag.
    private static final String[] RAW_TEXT = {"script", "style", "textarea", "title"};

    private int state = DATA;
    private boolean endTag;                                // the tag being read is </name ...>
    private final StringBuilder tag = new StringBuilder(); // its name, lower-cased
    private final StringBuilder attr = new StringBuilder(); // the attribute being read, lower-cased
    private char quote;                                    // in VALUE_QUOTED: the quote that closes it
    private String rawElement;                             // in RAW*: the element whose body this is
    private int rawMatched;                                // in RAW_END: characters of its name matched
    private int valueStart;                                // see scan

    /**
     * Advances the tokenizer over template text. Afterwards {@code valueStart} is the index in
     * this text where the quoted value still open at its end began, or -1 if the text doesn't end
     * in a value it opened.
     */
    private void scan(String s, int begin, int end) {
        valueStart = -1;
        int i = begin;
        while (i < end) {
            if (state == DATA || state == RAW) { // most of a page: nothing happens before a '<'
                i = s.indexOf('<', i);
                if (i < 0 || i >= end) return;
            }
            step(s.charAt(i), i);
            i++;
        }
    }

    /** The same over UTF-8 bytes: every character that matters is ASCII, so no decoding. */
    private void scan(byte[] b) {
        valueStart = -1;
        int i = 0, end = b.length;
        while (i < end) {
            if (state == DATA || state == RAW) {
                while (i < end && b[i] != '<') i++;
                if (i == end) return;
            }
            step((char) (b[i] & 0xff), i);
            i++;
        }
    }

    /** Whether the output is in the body of a {@code <script>} element, in any letter case. */
    private boolean inScriptBody() {
        return (state == RAW || state == RAW_LT || state == RAW_END) && "script".equals(rawElement);
    }

    /** Whether the text just scanned ends in a quoted attribute value it opened, on a start tag. */
    private boolean endsInStartTagValue() {
        return valueStart >= 0 && !endTag;
    }

    private void step(char c, int index) {
        switch (state) {
            case DATA -> { if (c == '<') state = LT; }
            case LT -> {
                if (isAsciiLetter(c)) startTagName(c, false);
                else if (c == '/') state = END_TAG_OPEN;
                else if (c == '!') state = MARKUP;
                else if (c == '?') state = BOGUS;
                else if (c != '<') state = DATA;
            }
            case END_TAG_OPEN -> {
                if (isAsciiLetter(c)) startTagName(c, true);
                else state = c == '>' ? DATA : BOGUS;
            }
            case TAG_NAME -> {
                if (isSpace(c) || c == '/') state = BEFORE_ATTR;
                else if (c == '>') closeTag();
                else appendName(tag, c);
            }
            case BEFORE_ATTR -> {
                if (c == '>') closeTag();
                else if (!isSpace(c) && c != '/') startAttribute(c);
            }
            case ATTR_NAME -> {
                if (isSpace(c)) state = AFTER_ATTR_NAME;
                else if (c == '/') state = BEFORE_ATTR;
                else if (c == '=') state = BEFORE_VALUE;
                else if (c == '>') closeTag();
                else appendName(attr, c);
            }
            case AFTER_ATTR_NAME -> {
                if (c == '=') state = BEFORE_VALUE;
                else if (c == '/') state = BEFORE_ATTR;
                else if (c == '>') closeTag();
                else if (!isSpace(c)) startAttribute(c);
            }
            case BEFORE_VALUE -> {
                if (c == '"' || c == '\'') {
                    state = VALUE_QUOTED;
                    quote = c;
                    valueStart = index + 1;
                } else if (c == '>') {
                    closeTag();
                } else if (!isSpace(c)) {
                    state = VALUE_UNQUOTED;
                }
            }
            case VALUE_QUOTED -> {
                if (c == quote) {
                    state = BEFORE_ATTR;
                    valueStart = -1;
                }
            }
            case VALUE_UNQUOTED -> {
                if (isSpace(c)) state = BEFORE_ATTR;
                else if (c == '>') closeTag();
            }
            case RAW -> { if (c == '<') state = RAW_LT; }
            case RAW_LT -> {
                if (c == '/') {
                    state = RAW_END;
                    rawMatched = 0;
                } else if (c != '<') {
                    state = RAW;
                }
            }
            case RAW_END -> {
                if (rawMatched < rawElement.length()) {
                    if (lower(c) == rawElement.charAt(rawMatched)) rawMatched++;
                    else state = c == '<' ? RAW_LT : RAW;
                } else if (c == '>') {
                    state = DATA;
                } else if (isSpace(c) || c == '/') {
                    endTag = true; // </script ...>: read to its '>' like any other tag
                    state = BEFORE_ATTR;
                } else {
                    state = c == '<' ? RAW_LT : RAW; // </scripty: still the body
                }
            }
            case MARKUP -> state = c == '-' ? MARKUP_DASH : c == '>' ? DATA : BOGUS;
            case MARKUP_DASH -> state = c == '-' ? COMMENT : c == '>' ? DATA : BOGUS;
            case COMMENT -> { if (c == '-') state = COMMENT_DASH; }
            case COMMENT_DASH -> state = c == '-' ? COMMENT_DASH_DASH : COMMENT;
            case COMMENT_DASH_DASH -> { if (c == '>') state = DATA; else if (c != '-') state = COMMENT; }
            case BOGUS -> { if (c == '>') state = DATA; }
            default -> throw new IllegalStateException("tokenizer state " + state);
        }
    }

    private void startTagName(char c, boolean end) {
        state = TAG_NAME;
        endTag = end;
        tag.setLength(0);
        tag.append(lower(c));
    }

    private void startAttribute(char c) {
        state = ATTR_NAME;
        attr.setLength(0);
        attr.append(lower(c));
    }

    private void closeTag() {
        state = DATA;
        if (endTag) return;
        for (String name : RAW_TEXT) {
            if (name.contentEquals(tag)) {
                state = RAW;
                rawElement = name;
                return;
            }
        }
    }

    private static void appendName(StringBuilder name, char c) {
        if (name.length() < MAX_NAME) name.append(lower(c));
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
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
            if (inScriptBody() == "script".equals(tagName)) {
                super.writeUserContent(value); // JTE's escaping for a script body, or for body text
            } else {
                // JTE and the output disagree about being in a script: a @template call inside
                // <Script>, which JTE reads as an ordinary element. Escaped to be inert in both.
                target.writeContent(escapeScript(value));
            }
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
