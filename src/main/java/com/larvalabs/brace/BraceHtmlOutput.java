package com.larvalabs.brace;

import gg.jte.Content;
import gg.jte.TemplateOutput;
import gg.jte.html.OwaspHtmlTemplateOutput;
import gg.jte.html.escape.Escape;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *   <li><strong>CSS</strong> ({@code <style>} bodies and {@code style=""}): user data is written
 *       with CSS hex escapes for everything but plain value characters, so it can't end its
 *       declaration or rule. See {@link #escapeCss}.</li>
 *   <li><strong>Script bodies</strong>: on top of JTE's JavaScript escaping, {@code < > &} are
 *       written as {@code \}{@code uXXXX}, and in a {@code <script>} inside {@code <svg>} or
 *       {@code <math>}, which a browser tokenizes as markup, quotes are too. See
 *       {@link #writeScriptData}.</li>
 * </ul>
 *
 * Where each {@code ${...}} lands is read from the output itself, by a small HTML tokenizer over
 * the template's text (H1 and L7, 2026-11 review): a value is held only while the output is
 * inside a quoted URL or {@code hx-vals}/{@code hx-headers}/{@code hx-request} value of a start
 * tag, never in a {@code <script>} or {@code <style>} body or in body text, and data a
 * {@code @template} call writes inside an attribute value is escaped for that attribute. The
 * tokenizer follows the script-data escaped states ({@code <!--} in a script) and foreign content
 * ({@code <svg>}, {@code <math>}), where {@code <script/>} is a complete element. JTE's own
 * context is the second opinion, and disagreement between the two always resolves to the stricter:
 * data gets the rules of both readings (see "user data" below).
 *
 * <p>JTE uses an output it's given as is when it is already an HTML output, so dev (hot-reload) and
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

    private final TemplateOutput target;
    // Where JTE says the template source is: the enclosing tag and, inside a value, its attribute.
    private String tagName;
    private String attributeName;

    // A URL or hx-vals/hx-headers/hx-request value that's open: held back, not yet written, because if user
    // data turns out to make it unsafe the whole value is replaced. Which value is open, and on
    // which attribute of which tag, comes from the tokenizer below, so a value is held exactly
    // while the output is inside it (L7: a pattern match on the text before a ${...} could pick
    // an earlier attribute, and the real one was then judged one piece at a time).
    private StringBuilder valueOut;     // the value as HTML to emit: template text, escaped user data; null when none is held
    private String valueAttribute;      // its attribute, lower-cased
    private char valueQuote;
    private boolean urlValue;           // a URL attribute; otherwise hx-vals, hx-headers or hx-request
    private boolean templateText;       // the template wrote some of the value itself
    private int firstUser;              // where in valueOut user data first appears; -1 if none
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
        // Template text pre-encoded as UTF-8: read as bytes and passed through untouched unless
        // it's inside, or opens, a held value, which needs it as text.
        if (valueOut != null) {
            String text = new String(value, StandardCharsets.UTF_8);
            writeStatic(text, 0, text.length());
            return;
        }
        scan(value);
        if (!opensHeldValue()) {
            super.writeBinaryContent(value);
            return;
        }
        // valueStart follows an ASCII quote, so it is a character boundary.
        if (valueStart > 0) super.writeBinaryContent(Arrays.copyOfRange(value, 0, valueStart));
        String rest = new String(value, valueStart, value.length - valueStart, StandardCharsets.UTF_8);
        hold(rest, 0, rest.length());
    }

    private void writeStatic(String s, int begin, int end) {
        scan(s, begin, end);
        if (valueOut != null) {
            // The tokenizer was in this value, so the first quote of its kind is where it left.
            int close = s.indexOf(valueQuote, begin);
            if (close < 0 || close >= end) {
                valueOut.append(s, begin, end);
                templateText |= end > begin;
                return;
            }
            valueOut.append(s, begin, close);
            templateText |= close > begin;
            emitHeldValue();
            begin = close;
        }
        if (!opensHeldValue()) {
            super.writeContent(s, begin, end);
            return;
        }
        super.writeContent(s, begin, valueStart);
        hold(s, valueStart, end);
    }

    /**
     * Whether the text just scanned ends inside a quoted value it opened, on a start tag, of an
     * attribute whose value is judged whole. H1: so never in a script or style body or in body
     * text, whatever the text there looks like ({@code var a = "}).
     */
    private boolean opensHeldValue() {
        if (valueStart < 0 || endTag) return false;
        String attribute = currentAttribute();
        return isUrlAttribute(currentTag(), attribute) || isJsonAttribute(attribute);
    }

    private void hold(String s, int begin, int end) {
        valueAttribute = currentAttribute();
        valueQuote = quote;
        urlValue = isUrlAttribute(currentTag(), valueAttribute);
        valueOut = new StringBuilder(end - begin + 64).append(s, begin, end);
        templateText = end > begin;
        firstUser = -1;
        userWrites = 0;
        soleUser = null;
    }

    // --- where the output is ---
    //
    // A tokenizer over the template's own text (and anything else written as trusted markup:
    // $unsafe{...}, content blocks), following the HTML parsing rules closely enough to know
    // whether the next ${...} lands in body text, inside a tag, inside a quoted attribute value,
    // or in the body of a <script> or <style> element, where markup isn't parsed and nothing is
    // an attribute.
    //
    // H1 (2026-11 security review). A held attribute value NEVER opens inside a raw-text element
    // or in body text: it opens only when template text ends in the state VALUE_QUOTED of a start
    // tag. Before this, the end of each text chunk was matched against a pattern on its own, so
    // `<script>var a = "${x}"` held a "value" named `a` and gave x attribute escaping in the
    // middle of a script. The decision rests on this class's own reading of the output, not on
    // the tag JTE reports with setContext: JTE's context says where the *template source* is,
    // which isn't where the output is when a @template call renders inside an attribute value.
    //
    // User data isn't read: it is escaped so that it can't close a tag, a quoted value or a
    // raw-text element. It does end a match the template text was in the middle of (dataWritten).
    //
    // <textarea> and <title> bodies are text to a browser as well, but are read as markup here:
    // the worst that does is attribute-escape data in text that looks like a tag, whereas reading
    // them as text would be wrong for <title> inside <svg>, whose body is markup.
    //
    // Foreign content (H1 review, F1). Inside <svg> and <math> the browser parses differently:
    // `<script href="a.js"/>` and `<style/>` are complete elements (in HTML content the "/" is
    // ignored and a script body follows), a <script> or <style> with a body is ordinary markup,
    // tags in it included, and <![CDATA[ ... ]]> is text. So the elements open inside the
    // outermost <svg>/<math> are kept in `open`, and the output is in foreign content while the
    // innermost of them isn't an integration point (<foreignObject>, <desc>, <title>,
    // <annotation-xml>, MathML's <mi> <mo> <mn> <ms> <mtext>), whose content is HTML again. What
    // isn't modelled errs towards HTML: a tag that takes a browser out of foreign content (<p>,
    // <div>, ... or an end tag that matches nothing open in it) empties the list down to the
    // nearest integration point, every integration-point name counts under either root, and
    // <annotation-xml> counts whatever its encoding. Believing HTML where the browser is in
    // foreign content means a self-closing <script/> is read as the start of a script body, and
    // data after it is then escaped for a script and for what JTE reports as well (see "user
    // data" below): garbled, never unescaped. The reverse (believing foreign content where the
    // browser reads HTML) needs markup this doesn't follow, such as an <svg> tag in the text of a
    // <textarea> or an element left open across an integration point; the only thing it changes
    // is that a <script/> or <style/> written self-closing is taken as complete.

    private static final int DATA = 0, LT = 1, END_TAG_OPEN = 2, TAG_NAME = 3, BEFORE_ATTR = 4,
        ATTR_NAME = 5, AFTER_ATTR_NAME = 6, BEFORE_VALUE = 7, VALUE_QUOTED = 8, VALUE_UNQUOTED = 9,
        RAW = 10, RAW_LT = 11, RAW_END = 12, MARKUP = 13, MARKUP_DASH = 14, COMMENT = 15,
        COMMENT_DASH = 16, COMMENT_DASH_DASH = 17, COMMENT_BANG = 18, BOGUS = 19,
        CDATA_OPEN = 20, CDATA = 21, CDATA_BRACKET = 22, CDATA_BRACKETS = 23,
        RAW_BANG = 24, RAW_BANG_DASH = 25, RAW_OPEN = 26;
    private static final int MAX_NAME = 64;
    private static final int MAX_OPEN = 256;

    // Elements whose body is text up to their own end tag (in HTML content).
    private static final String[] RAW_TEXT = {"script", "style"};

    private static final Set<String> FOREIGN_ROOTS = Set.of("svg", "math");
    private static final Set<String> INTEGRATION_POINTS = Set.of(
        "foreignobject", "desc", "title", "annotation-xml", "mi", "mo", "mn", "ms", "mtext");
    // Start tags that end foreign content: the HTML parser's list, with <font> whatever its attributes.
    private static final Set<String> BREAKOUT_TAGS = Set.of(
        "b", "big", "blockquote", "body", "br", "center", "code", "dd", "div", "dl", "dt", "em", "embed",
        "h1", "h2", "h3", "h4", "h5", "h6", "head", "hr", "i", "img", "li", "listing", "menu", "meta", "nobr",
        "ol", "p", "pre", "ruby", "s", "small", "span", "strong", "strike", "sub", "sup", "table", "tt", "u",
        "ul", "var", "font");
    private static final String CDATA_START = "CDATA[";

    private int state = DATA;
    private boolean endTag;                                // the tag being read is </name ...>
    private boolean slash;                                 // inside a tag: the character just read was a '/' outside a value
    private final StringBuilder tag = new StringBuilder(); // its name, lower-cased
    private final StringBuilder attr = new StringBuilder(); // the attribute being read, lower-cased
    private String tagString, attrString;                  // those as Strings, made when asked for
    private char quote;                                    // in VALUE_QUOTED: the quote that closes it
    private String rawElement;                             // in RAW*: the element whose body this is
    private int rawMatched;                                // in RAW_END, RAW_OPEN: characters of its name matched; in CDATA_OPEN: of "CDATA["
    private int scriptEscape;                              // in a script body: 0 script data, 1 escaped, 2 double-escaped (see raw)
    private int dashes;                                    // in RAW with scriptEscape > 0: the '-' just read, up to 2
    private int valueStart;                                // see scan
    // Elements open inside the outermost <svg>/<math>, lower-cased, outermost first; empty in
    // plain HTML content. Inside an integration point only what matters is added: a nested
    // <svg>/<math>, and an element named like the integration point (so its end tag is counted).
    private final java.util.ArrayList<String> open = new java.util.ArrayList<>();

    /**
     * Advances the tokenizer over template text. Afterwards {@code valueStart} is the index in
     * this text where the quoted value still open at its end began, or -1 if the text doesn't end
     * in a value it opened.
     */
    private void scan(String s, int begin, int end) {
        valueStart = -1;
        int i = begin;
        while (i < end) {
            if (state == DATA || (state == RAW && scriptEscape == 0)) { // most of a page: nothing happens before a '<'
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
            if (state == DATA || (state == RAW && scriptEscape == 0)) {
                while (i < end && b[i] != '<') i++;
                if (i == end) return;
            }
            step((char) (b[i] & 0xff), i);
            i++;
        }
    }

    /** Whether the output is in the body of a {@code <script>} element, in any letter case. */
    private boolean inScriptBody() {
        return inBody("script");
    }

    /** Whether the output is in the body of a {@code <style>} element, in any letter case. */
    private boolean inStyleBody() {
        return inBody("style");
    }

    /** In HTML content, the raw text of that element; in foreign content, directly inside one. */
    private boolean inBody(String element) {
        if (inRawText()) return element.equals(rawElement);
        return !open.isEmpty() && element.equals(open.get(open.size() - 1));
    }

    private boolean inRawText() {
        return state == RAW || state == RAW_LT || state == RAW_END || state == RAW_BANG || state == RAW_BANG_DASH
            || state == RAW_OPEN;
    }

    /** Whether the output is inside {@code <svg>} or {@code <math>} and not in an integration point. */
    private boolean inForeignContent() {
        return !open.isEmpty() && !INTEGRATION_POINTS.contains(open.get(open.size() - 1));
    }

    /** Whether the output is inside a quoted attribute value of a start tag. */
    private boolean inQuotedValue() {
        return state == VALUE_QUOTED && !endTag;
    }

    /** The tag being read, or the last one read. */
    private String currentTag() {
        if (tagString == null) tagString = tag.toString();
        return tagString;
    }

    /** The attribute being read: in VALUE_QUOTED, the one whose value this is. */
    private String currentAttribute() {
        if (attrString == null) attrString = attr.toString();
        return attrString;
    }

    private void step(char c, int index) {
        // "/>" ends a tag as self-closing only when the '/' is outside a value and nothing
        // follows it: not in <a href=/x/> or <br / >.
        boolean selfClosing = slash;
        slash = false;
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
                if (isSpace(c)) state = BEFORE_ATTR;
                else if (c == '/') tagSlash();
                else if (c == '>') closeTag(false);
                else appendName(tag, c);
                tagString = null;
            }
            case BEFORE_ATTR -> {
                if (c == '>') closeTag(selfClosing);
                else if (c == '/') tagSlash();
                else if (!isSpace(c)) startAttribute(c);
            }
            case ATTR_NAME -> {
                if (isSpace(c)) state = AFTER_ATTR_NAME;
                else if (c == '/') tagSlash();
                else if (c == '=') state = BEFORE_VALUE;
                else if (c == '>') closeTag(false);
                else appendName(attr, c);
                attrString = null;
            }
            case AFTER_ATTR_NAME -> {
                if (c == '=') state = BEFORE_VALUE;
                else if (c == '/') tagSlash();
                else if (c == '>') closeTag(false);
                else if (!isSpace(c)) startAttribute(c);
            }
            case BEFORE_VALUE -> {
                if (c == '"' || c == '\'') {
                    state = VALUE_QUOTED;
                    quote = c;
                    valueStart = index + 1;
                } else if (c == '>') {
                    closeTag(false);
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
                else if (c == '>') closeTag(false);
            }
            case RAW -> raw(c);
            case RAW_LT -> {
                if (c == '/') {
                    state = RAW_END;
                    rawMatched = 0;
                } else if (c == '!' && scriptEscape == 0 && "script".equals(rawElement)) {
                    state = RAW_BANG;
                } else if (scriptEscape == 1 && lower(c) == 's') {
                    state = RAW_OPEN;
                    rawMatched = 1;
                } else {
                    rawAgain(c);
                }
            }
            case RAW_BANG -> { if (c == '-') state = RAW_BANG_DASH; else rawAgain(c); }
            case RAW_BANG_DASH -> {
                if (c == '-') {
                    state = RAW;
                    scriptEscape = 1;
                    dashes = 2; // "<!-->" is over already
                } else {
                    rawAgain(c);
                }
            }
            case RAW_OPEN -> { // "<script" in the escaped state
                if (rawMatched < rawElement.length()) {
                    if (lower(c) == rawElement.charAt(rawMatched)) rawMatched++;
                    else rawAgain(c);
                } else if (isSpace(c) || c == '/' || c == '>') {
                    state = RAW;
                    scriptEscape = 2;
                } else {
                    rawAgain(c); // <scripty
                }
            }
            case RAW_END -> {
                if (rawMatched < rawElement.length()) {
                    if (lower(c) == rawElement.charAt(rawMatched)) rawMatched++;
                    else rawAgain(c);
                } else if (!isSpace(c) && c != '/' && c != '>') {
                    rawAgain(c); // </scripty: still the body
                } else if (scriptEscape == 2) {
                    state = RAW; // the </script> of the inner "<script": not the end of the element
                    scriptEscape = 1;
                } else {
                    scriptEscape = 0;
                    if (c == '>') {
                        state = DATA;
                    } else {
                        endTag = true; // </script ...>: read to its '>' like any other tag
                        state = BEFORE_ATTR;
                    }
                }
            }
            case MARKUP -> {
                if (c == '[' && inForeignContent()) {
                    state = CDATA_OPEN; // in HTML content "<![CDATA[" is a bogus comment, up to the first '>'
                    rawMatched = 0;
                } else {
                    state = c == '-' ? MARKUP_DASH : c == '>' ? DATA : BOGUS;
                }
            }
            case CDATA_OPEN -> {
                if (c != CDATA_START.charAt(rawMatched)) state = c == '>' ? DATA : BOGUS;
                else if (++rawMatched == CDATA_START.length()) state = CDATA;
            }
            case CDATA -> { if (c == ']') state = CDATA_BRACKET; }
            case CDATA_BRACKET -> state = c == ']' ? CDATA_BRACKETS : CDATA;
            case CDATA_BRACKETS -> { if (c != ']') state = c == '>' ? DATA : CDATA; }
            // "<!--" goes straight to COMMENT_DASH_DASH: "<!-->" and "<!--->" are whole comments.
            case MARKUP_DASH -> state = c == '-' ? COMMENT_DASH_DASH : c == '>' ? DATA : BOGUS;
            case COMMENT -> { if (c == '-') state = COMMENT_DASH; }
            case COMMENT_DASH -> state = c == '-' ? COMMENT_DASH_DASH : COMMENT;
            case COMMENT_DASH_DASH -> {
                if (c == '>') state = DATA;
                else if (c == '!') state = COMMENT_BANG;
                else if (c != '-') state = COMMENT;
            }
            case COMMENT_BANG -> state = c == '>' ? DATA : c == '-' ? COMMENT_DASH : COMMENT; // "--!>"
            case BOGUS -> { if (c == '>') state = DATA; }
            default -> throw new IllegalStateException("tokenizer state " + state);
        }
    }

    private void startTagName(char c, boolean end) {
        state = TAG_NAME;
        endTag = end;
        tag.setLength(0);
        tag.append(lower(c));
        tagString = null;
    }

    private void startAttribute(char c) {
        state = ATTR_NAME;
        attr.setLength(0);
        attr.append(lower(c));
        attrString = null;
    }

    // The body of a <script> or <style> in HTML content. A <style> ends at its end tag and that is
    // all. A <script> has the HTML tokenizer's "escaped" states as well (H1 review, F2), kept in
    // scriptEscape: "<!--" enters the escaped state (1), "<script" followed by a space, '/' or
    // '>' in it enters the double-escaped state (2), where "</script" followed by one of those
    // returns to the escaped state and does NOT end the element, and "-->" in either returns to
    // plain script data (0). "</script" then ends the element from 0 or 1. So in
    //   <script><!-- document.write("<script src=x></script>"); var a = "${a}"; //--></script>
    // ${a} is still in the script, though JTE, which ends a script at the first </script>, says
    // it isn't; the two disagree and the data is escaped for a script.

    private void raw(char c) {
        if (c == '<') {
            state = RAW_LT;
            dashes = 0;
        } else if (scriptEscape > 0) {
            if (c == '-') {
                if (dashes < 2) dashes++;
            } else {
                if (c == '>' && dashes == 2) scriptEscape = 0; // "-->"
                dashes = 0;
            }
        }
    }

    /** A character that didn't continue what the body was in the middle of matching. */
    private void rawAgain(char c) {
        state = RAW;
        raw(c);
    }

    private void tagSlash() {
        state = BEFORE_ATTR;
        slash = true;
    }

    /** The '>' of a tag. {@code selfClosing}: it was written {@code />}. */
    private void closeTag(boolean selfClosing) {
        state = DATA;
        if (endTag) {
            if (!open.isEmpty()) closeElement(currentTag());
            return;
        }
        if (inForeignContent()) {
            if (!BREAKOUT_TAGS.contains(currentTag())) {
                // Every element here is markup, <script> and <style> too: no raw text, and
                // a self-closing one is complete.
                if (!selfClosing) openElement(currentTag());
                return;
            }
            leaveForeignContent(); // the browser closes the foreign elements and reads the tag as HTML
        }
        for (String name : RAW_TEXT) {
            if (name.contentEquals(tag)) {
                state = RAW; // "/>" or not: HTML has no self-closing <script/>
                rawElement = name;
                scriptEscape = 0;
                dashes = 0;
                return;
            }
        }
        if (selfClosing) return;
        if (open.isEmpty() ? "svg".contentEquals(tag) || "math".contentEquals(tag)
                : FOREIGN_ROOTS.contains(currentTag()) || open.get(open.size() - 1).equals(currentTag())) {
            openElement(currentTag());
        }
    }

    private void openElement(String name) {
        // Deeper than any real SVG: stop following it, which reads everything after as HTML.
        if (open.size() >= MAX_OPEN) open.clear();
        else open.add(name);
    }

    /** An end tag while something is open inside {@code <svg>} or {@code <math>}. */
    private void closeElement(String name) {
        int top = open.size() - 1;
        if (INTEGRATION_POINTS.contains(open.get(top))) {
            // HTML content: only the integration point's own end tag is of interest.
            if (open.get(top).equals(name)) open.remove(top);
            return;
        }
        int match = name.equals("p") || name.equals("br") ? -1 : open.lastIndexOf(name);
        if (match < 0) leaveForeignContent(); // </p>, </br>, or nothing here by that name: HTML's rules, which may close it all
        else open.subList(match, open.size()).clear();
    }

    /** Closes the foreign elements down to the nearest integration point, or all of them. */
    private void leaveForeignContent() {
        int keep = open.size();
        while (keep > 0 && !INTEGRATION_POINTS.contains(open.get(keep - 1))) keep--;
        open.subList(keep, open.size()).clear();
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

    /**
     * Write the held value: replaced first if it's a URL that user data made unsafe, or an
     * {@code hx-vals}/{@code hx-headers} value that user data made an expression.
     */
    private void emitHeldValue() {
        if (firstUser < 0) {
            target.writeContent(valueOut.toString()); // all template text
        } else {
            // The value as the browser reads it. Decoded from what will be written, not put
            // together from the pieces, so that a reference the template wrote (href="&#32;${x}",
            // L7) or one split between template and data ("&#" + "32;") reads as it will there.
            String raw = decodeReferences(valueOut);
            int userAt = templatePrefixLength();
            if (urlValue) {
                if (isSafeValue(raw, userAt)) target.writeContent(valueOut.toString());
                else Escape.htmlAttribute(BLOCKED_URL, target);
            } else if (userWrites == 1 && !templateText) {
                // The whole value is one piece of data: JSON the app built (hx-vals='${json}').
                // escaping its quotes as JavaScript would break the JSON, so it's attribute-escaped instead (the
                // browser decodes that back), unless it's a js: expression htmx would evaluate.
                if (evaluatedBecauseOfUserData(soleUser, 0)) target.writeContent("{}");
                else Escape.htmlAttribute(soleUser, target);
            } else if (evaluatedBecauseOfUserData(unescapeScript(raw), unescapeScript(raw.substring(0, userAt)).length())) {
                // Data made it a js: expression. Read with the \\uXXXX escapes of the data undone:
                // a tab written as \\u0009 isn't whitespace to htmx, but this doesn't lean on that.
                target.writeContent("{}");
            } else {
                target.writeContent(valueOut.toString());
            }
        }
        valueOut = null;
        valueAttribute = null;
        soleUser = null;
    }

    /**
     * How much of the decoded value is the template's own text before the first user data. A
     * reference left unfinished where the data starts ({@code &#} then {@code ${n};}) doesn't
     * count: the data completes it.
     */
    private int templatePrefixLength() {
        int end = firstUser;
        int amp = valueOut.lastIndexOf("&", end - 1);
        if (end > 0 && amp >= 0) {
            int semicolon = valueOut.indexOf(";", amp);
            if (semicolon < 0 || semicolon >= end) end = amp;
        }
        return decodeReferences(valueOut.subSequence(0, end)).length();
    }

    /** Flushes a value still held at the end of a render. Called by {@link TemplateEngine}. */
    void finish() {
        if (valueOut != null) emitHeldValue();
    }

    // --- user data ---
    //
    // Two readings of where it lands: this class's tokenizer (where the output is) and JTE's
    // context (where the template source is). They differ when a @template call or content block
    // renders inside an attribute value, where JTE reports body text, and inside <Script>, which
    // JTE doesn't know is a script. They also differ whenever the tokenizer has lost its place,
    // which no template should be able to make it do, but has (H1 review, F1: a self-closing
    // <script/> in SVG was read as the start of a script body that ran on down the page).
    //
    // INVARIANT: disagreement between JTE's context and the tokenizer always resolves to the
    // stricter of the two. Each reading names the rules for the place it believes the data
    // lands in, and where the two name different places the data gets the rules of both: the
    // URL scheme check if either says URL attribute, script escaping if either says script
    // attribute or script body, CSS escaping if either says style, attribute escaping if either
    // says attribute. Neither reading can switch off a rule the other calls for, so a tokenizer
    // that has lost its place garbles data (script escapes in body text) and never unescapes it.

    @Override
    public void writeUserContent(String value) {
        if (value == null) return;
        if (!value.isEmpty()) dataWritten(value.charAt(0));
        boolean jteAttribute = tagName != null && attributeName != null;
        boolean jteScript = attributeName == null && "script".equals(tagName);
        boolean jteStyle = attributeName == null && "style".equals(tagName);
        if (valueOut != null) {
            appendToHeldValue(value, jteScript, jteStyle);
        } else if (inQuotedValue()) {
            // In an attribute value that isn't held. Also reached with no attribute context from
            // JTE (a @template call inside the value): JTE would escape that as body text, which
            // leaves quotes alone.
            writeAttributeData(currentTag(), currentAttribute(), jteAttribute ? tagName : null,
                jteAttribute ? attributeName : null, value, jteScript, jteStyle);
        } else if (jteAttribute) {
            // JTE reports an attribute and the output isn't in a quoted value of a start tag
            // (JTE refuses unquoted ones, so the two disagree): JTE's attribute, and whatever
            // body the output is in.
            writeAttributeData(null, null, tagName, attributeName, value, inScriptBody(), inStyleBody());
        } else {
            boolean script = inScriptBody(), style = inStyleBody() || jteStyle;
            if (script != jteScript) {
                // One says script body: \\uXXXX escapes, inert in a script string and as text.
                String escaped = escapeScript(value);
                target.writeContent(style ? escapeCss(escaped) : escaped);
            } else if (style) {
                // L7: JTE HTML-escapes data in a <style> body, which keeps it in the element but not
                // in its declaration. (The result is inert as body text too, if JTE alone says style.)
                target.writeContent(escapeCss(value));
            } else if (script) {
                writeScriptData(value);
            } else {
                super.writeUserContent(value); // JTE's escaping for body text
            }
        }
    }

    /**
     * Data in a script body, where JTE and the tokenizer agree that it is one (H1 review, F3).
     *
     * <p>In HTML content: JTE's script escaping, with {@code < > &} written as
     * {@code \}{@code uXXXX} on top (JTE leaves them alone, and escapes nothing into them). In a
     * JavaScript string, template literal or regex literal and in a JSON string that reads back
     * as the same character; anywhere else in a script it is a syntax error, and data there was
     * code already. It costs nothing in HTML, where a script body is raw text, and it is what
     * keeps the data text if the browser is tokenizing the body as markup after all.
     *
     * <p>In foreign content ({@code <svg><script>}) it is tokenized as markup: {@code <img} in a
     * string is a tag, a character reference is decoded, and after a {@code <} the template wrote
     * ({@code if (a<b)}) a quote ends an attribute value. So everything that is syntax to either
     * parser goes: {@link #escapeScript}, which leaves no {@code < > & " '} at all.
     */
    private void writeScriptData(String value) {
        if (!inRawText()) {
            target.writeContent(escapeScript(value));
            return;
        }
        int i = 0, n = value.length();
        while (i < n && !isMarkup(value.charAt(i))) i++;
        if (i == n) {
            Escape.javaScriptBlock(value, target);
            return;
        }
        var escaped = new StringBuilder(n + 32);
        Escape.javaScriptBlock(value, new Appender(escaped));
        var out = new StringBuilder(escaped.length() + 16);
        for (i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (isMarkup(c)) out.append(c == '<' ? "\\u003c" : c == '>' ? "\\u003e" : "\\u0026");
            else out.append(c);
        }
        target.writeContent(out.toString());
    }

    private static boolean isMarkup(char c) {
        return c == '<' || c == '>' || c == '&';
    }

    /**
     * Data is about to be written (not empty; {@code first} is its first character). The
     * tokenizer doesn't read data, which is escaped so that it can't open or close anything, but
     * data does separate the template text before it from the text after it (H1 review, F5):
     * {@code </scr${x}ipt>} is not an end tag, {@code -${x}->} doesn't end a comment. So a match
     * in progress is dropped. The one place where what the data is matters is straight after a
     * {@code <} in markup, where a browser takes a letter for the start of a tag; reading that as
     * a tag too keeps a quoted value written after it an attribute value. (Its name starts with
     * data, so it is never taken for a script, style or svg tag: '?' stands in for the data.)
     */
    private void dataWritten(char first) {
        slash = false;
        dashes = 0;
        switch (state) {
            case LT -> {
                if (isAsciiLetter(first)) startTagName('?', false);
                else state = first == '/' ? END_TAG_OPEN : first == '!' ? MARKUP : first == '?' ? BOGUS : DATA;
            }
            case END_TAG_OPEN -> {
                if (isAsciiLetter(first)) startTagName('?', true);
                else state = BOGUS;
            }
            case MARKUP, CDATA_OPEN -> state = BOGUS;
            case MARKUP_DASH -> state = first == '-' ? COMMENT : BOGUS;
            case COMMENT_DASH, COMMENT_DASH_DASH, COMMENT_BANG -> state = COMMENT;
            case CDATA_BRACKET, CDATA_BRACKETS -> state = CDATA;
            case RAW_LT, RAW_END, RAW_BANG, RAW_BANG_DASH, RAW_OPEN -> state = RAW;
            default -> { }
        }
    }

    /**
     * Data in an attribute value that isn't held, under the rules of the attribute the output is
     * in ({@code tag}, {@code attribute}; null if it isn't in one) and of the one JTE reports
     * ({@code jteTag}, {@code jteAttribute}; null if none), which are normally the same, plus
     * script or CSS escaping when one of the two readings is a script or style body.
     */
    private void writeAttributeData(String tag, String attribute, String jteTag, String jteAttribute,
                                    String value, boolean script, boolean css) {
        boolean url = attribute != null && isUrlAttribute(tag, attribute);
        boolean jteUrl = jteAttribute != null && isUrlAttribute(jteTag, jteAttribute);
        if (url || jteUrl) {
            // No whole value to judge here, only this piece, and the next piece could finish a
            // scheme this one starts ("javascript" then ":..."): it has to settle its own.
            boolean media = (!url || mediaDataUrl(attribute, value)) && (!jteUrl || mediaDataUrl(jteAttribute, value));
            if (!settlesItsScheme(value) || !(isSafeUrl(value) || media)) value = BLOCKED_URL;
        }
        if ((isJsonAttribute(attribute) || isJsonAttribute(jteAttribute)) && evaluatedBecauseOfUserData(value, 0)) value = "";
        if (script || isScriptAttribute(attribute) || isScriptAttribute(jteAttribute)) value = escapeScript(value);
        if (css || "style".equals(attribute) || "style".equals(jteAttribute)) {
            target.writeContent(escapeCss(value)); // nothing left in it for HTML escaping to do
        } else if ("srcdoc".equals(attribute) || "srcdoc".equals(jteAttribute)) {
            var once = new StringBuilder(value.length() + 16);
            Escape.htmlAttribute(value, new Appender(once));
            Escape.htmlAttribute(once.toString(), target);
        } else {
            Escape.htmlAttribute(value, target);
        }
    }

    /**
     * Whether a piece of a URL written outside a held value decides, by itself, what scheme the
     * URL has or that it has none: it is empty, or has a {@code : / ? #} in it.
     */
    private static boolean settlesItsScheme(String piece) {
        String url = normalize(piece);
        if (url.isEmpty()) return true;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == ':' || c == '/' || c == '?' || c == '#') return true;
        }
        return false;
    }

    /** User data inside the held value: a URL, or hx-vals/hx-headers. */
    private void appendToHeldValue(String value, boolean jteScript, boolean jteStyle) {
        if (firstUser < 0) firstUser = valueOut.length();
        // JTE reads a script, a style or a script attribute here: its escaping as well.
        boolean jteDiffers = jteScript || jteStyle || (urlValue && isScriptAttribute(attributeName));
        userWrites += jteDiffers ? 2 : 1; // never "the whole value is one piece of JSON" then
        soleUser = value;
        if (jteDiffers || !urlValue) value = escapeScript(value);
        if (jteStyle) value = escapeCss(value);
        Escape.htmlAttribute(value, new Appender(valueOut));
    }

    @Override
    public void writeUserContent(Content content) {
        if (content == null) return;
        if (valueOut != null || inQuotedValue() || (tagName != null && attributeName != null)) {
            // Rendered to text first, into the HTML-aware output JTE itself uses for this (a
            // content block casts its output to one), then treated like any other value here.
            var text = createOutputForAttributeContent();
            content.writeTo(text);
            writeUserContent(text.toString());
        } else {
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

    /** {@code raw}: the held URL value as the browser reads it; {@code userAt}: where user data starts in it. */
    private boolean isSafeValue(String raw, int userAt) {
        if ("values".equals(valueAttribute)) {
            for (String item : raw.split(";")) {
                if (!isSafeUrl(item)) return false;
            }
            return true;
        }
        // L7: a script URL the template wrote, with data in it (href="javascript:go('${x}')"), is
        // code with data spliced in. Attribute escaping is undone by the browser before it runs,
        // and so is percent-encoding, so there is no escaping to apply: it's blocked.
        if (schemeWrittenByTemplate(raw, userAt)) return !isScriptUrl(raw);
        return isSafeUrl(raw) || mediaDataUrl(valueAttribute, raw);
    }

    private static boolean isScriptUrl(String url) {
        String scheme = scheme(normalize(url));
        return scheme != null && (scheme.equalsIgnoreCase("javascript") || scheme.equalsIgnoreCase("vbscript"));
    }

    /** The scheme of a normalized URL, or null if it is relative. */
    private static String scheme(String normalized) {
        int colon = normalized.indexOf(':');
        if (colon < 0) return null;
        // A scheme only if the ':' comes before any path, query or fragment delimiter.
        for (int i = 0; i < colon; i++) {
            char c = normalized.charAt(i);
            if (c == '/' || c == '?' || c == '#') return null;
        }
        String scheme = normalized.substring(0, colon);
        return isScheme(scheme) ? scheme : null; // not parseable as a scheme: a relative path
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

    /**
     * htmx attributes that take JSON (or, with a {@code js:} prefix, an expression): the three
     * that htmx 2.0.10 reads through its one evaluating function with "evaluate only on the
     * prefix". {@code hx-request} was missing (L7 review, F4). The fourth caller of that
     * function, {@code hx-vars}, is evaluated whatever it starts with, so it has no prefix to
     * guard and is a script attribute only.
     */
    private static boolean isJsonAttribute(String attribute) {
        if (attribute == null) return false;
        String name = unprefixed(attribute);
        return name.equals("hx-vals") || name.equals("hx-headers") || name.equals("hx-request");
    }

    /**
     * Whether a held {@code hx-vals}/{@code hx-headers}/{@code hx-request} value starts with {@code js:} or
     * {@code javascript:}, which htmx evaluates as code, and user data supplied any of that
     * prefix. Judged on the whole value as the browser reads it ({@code raw}), however many
     * pieces it was written in, after the trimming htmx does: JavaScript's {@code trim()}, which
     * strips Unicode spaces too (L7). U+FFFD stands for a named reference this class doesn't
     * decode, which may be one of those spaces. A prefix that is all template text
     * ({@code hx-vals='js:{v: "${x}"}'}) is the template's choice.
     */
    private static boolean evaluatedBecauseOfUserData(String raw, int userAt) {
        int i = 0, length = raw.length();
        while (i < length && (isJsSpace(raw.charAt(i)) || raw.charAt(i) == '�')) i++;
        for (String prefix : EVALUATED_PREFIXES) {
            if (raw.regionMatches(true, i, prefix, 0, prefix.length())) return userAt < i + prefix.length();
        }
        return false;
    }

    private static final String[] EVALUATED_PREFIXES = {"js:", "javascript:"};

    /** What JavaScript's {@code String.prototype.trim} strips: WhiteSpace and LineTerminator. */
    private static boolean isJsSpace(char c) {
        return (c >= '\t' && c <= '\r') || c == ' ' || c == ' ' || c == ' '
            || (c >= ' ' && c <= ' ') || c == ' ' || c == ' ' || c == ' '
            || c == ' ' || c == '　' || c == '﻿';
    }

    private static boolean isScriptAttribute(String attribute) {
        if (attribute == null) return false;
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
     * Escape for CSS, in a {@code <style>} body or a {@code style=""} attribute (L7): everything
     * but letters, digits, spaces, {@code # % . _ -} and non-ASCII characters becomes a hex
     * escape ({@code ;} is {@code \3b }). An escaped character is never syntax to a CSS parser,
     * so data can't end its declaration or rule, open a function or a comment, or (having no
     * {@code < > & " '} left) leave the style element or attribute. Inside a quoted CSS string
     * the parser reads the original text back; outside one, a value with escaped characters is
     * an odd identifier, and its declaration is dropped.
     */
    static String escapeCss(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean plain = isAsciiAlphanumeric(c) || c == ' ' || c == '#' || c == '%' || c == '.'
                || c == '_' || c == '-' || c >= 0xa0;
            if (plain) {
                if (out != null) out.append(c);
                continue;
            }
            if (out == null) out = new StringBuilder(value.length() + 16).append(value, 0, i);
            out.append('\\').append(Integer.toHexString(c == 0 ? 0xfffd : c)).append(' ');
        }
        return out == null ? value : out.toString();
    }

    /** Undoes {@link #escapeScript}: {@code \}{@code uXXXX} and {@code \\} back to the characters. */
    static String unescapeScript(String value) {
        int i = value.indexOf('\\');
        if (i < 0) return value;
        var out = new StringBuilder(value.length()).append(value, 0, i);
        for (int n = value.length(); i < n; i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < n && value.charAt(i + 1) == '\\') {
                out.append('\\');
                i++;
            } else if (c == '\\' && i + 5 < n && value.charAt(i + 1) == 'u' && isHex4(value, i + 2)) {
                out.append((char) Integer.parseInt(value, i + 2, i + 6, 16));
                i += 5;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex4(String s, int from) {
        for (int i = from; i < from + 4; i++) {
            if (asciiDigit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /**
     * Whether {@code url} is relative or uses an allowed scheme, judged the way a browser parses
     * it: leading and trailing C0 controls and spaces dropped, tabs and newlines removed anywhere.
     */
    static boolean isSafeUrl(String url) {
        String scheme = scheme(normalize(url));
        return scheme == null || SAFE_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
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

    // Named character references that decode to ASCII (every one that does, bar the ligature-free
    // rest of the table, which is all non-ASCII), plus the no-break space.
    private static final Map<String, String> NAMED_REFERENCES = Map.ofEntries(
        Map.entry("Tab", "\t"), Map.entry("NewLine", "\n"), Map.entry("excl", "!"), Map.entry("quot", "\""),
        Map.entry("QUOT", "\""), Map.entry("num", "#"), Map.entry("dollar", "$"), Map.entry("percnt", "%"),
        Map.entry("amp", "&"), Map.entry("AMP", "&"), Map.entry("apos", "'"), Map.entry("lpar", "("),
        Map.entry("rpar", ")"), Map.entry("ast", "*"), Map.entry("midast", "*"), Map.entry("plus", "+"),
        Map.entry("comma", ","), Map.entry("period", "."), Map.entry("sol", "/"), Map.entry("colon", ":"),
        Map.entry("semi", ";"), Map.entry("lt", "<"), Map.entry("LT", "<"), Map.entry("equals", "="),
        Map.entry("gt", ">"), Map.entry("GT", ">"), Map.entry("quest", "?"), Map.entry("commat", "@"),
        Map.entry("lsqb", "["), Map.entry("lbrack", "["), Map.entry("bsol", "\\"), Map.entry("rsqb", "]"),
        Map.entry("rbrack", "]"), Map.entry("Hat", "^"), Map.entry("lowbar", "_"), Map.entry("UnderBar", "_"),
        Map.entry("grave", "`"), Map.entry("DiacriticalGrave", "`"), Map.entry("fjlig", "fj"),
        Map.entry("lcub", "{"), Map.entry("lbrace", "{"), Map.entry("verbar", "|"), Map.entry("vert", "|"),
        Map.entry("VerticalLine", "|"), Map.entry("rcub", "}"), Map.entry("rbrace", "}"),
        Map.entry("nbsp", " "), Map.entry("NonBreakingSpace", " "));
    // Of those, the ones HTML also accepts without the semicolon.
    private static final Set<String> NO_SEMICOLON = Set.of("amp", "AMP", "lt", "LT", "gt", "GT", "quot", "QUOT", "nbsp");
    private static final int MAX_REFERENCE_NAME = 32;

    /**
     * An attribute value as the browser reads it, with character references decoded: numeric
     * ones ({@code &#32;}, {@code &#x3a}, the semicolon optional) and the named ones that produce
     * ASCII ({@code &colon;}, {@code &Tab;}, {@code &amp;}...). Any other {@code &name;} becomes
     * U+FFFD: whatever it names is not ASCII, so it can't be part of a scheme or a delimiter.
     */
    static String decodeReferences(CharSequence s) {
        int n = s.length(), i = 0;
        while (i < n && s.charAt(i) != '&') i++;
        if (i == n) return s.toString();
        var out = new StringBuilder(n).append(s, 0, i);
        while (i < n) {
            char c = s.charAt(i);
            int next = c == '&' && i + 1 < n ? s.charAt(i + 1) : -1;
            if (next == '#') {
                int j = i + 2;
                int radix = j < n && (s.charAt(j) == 'x' || s.charAt(j) == 'X') ? 16 : 10;
                if (radix == 16) j++;
                int digits = j, code = 0;
                for (; j < n; j++) {
                    int d = asciiDigit(s.charAt(j), radix);
                    if (d < 0) break;
                    if (code <= 0x10FFFF) code = code * radix + d;
                }
                if (j > digits) {
                    if (j < n && s.charAt(j) == ';') j++;
                    boolean valid = code != 0 && code <= 0x10FFFF && !(code >= 0xD800 && code <= 0xDFFF);
                    out.appendCodePoint(valid ? code : 0xFFFD);
                    i = j;
                    continue;
                }
            } else if (next >= 0 && isAsciiAlphanumeric((char) next)) {
                int j = i + 1;
                while (j < n && j - i <= MAX_REFERENCE_NAME && isAsciiAlphanumeric(s.charAt(j))) j++;
                String name = s.subSequence(i + 1, j).toString();
                if (j < n && s.charAt(j) == ';') {
                    out.append(NAMED_REFERENCES.getOrDefault(name, "�"));
                    i = j + 1;
                    continue;
                }
                // No semicolon: in an attribute, a reference only if '=' or a letter or digit
                // doesn't follow (so ?a=1&lt=2 keeps its "&lt").
                if (NO_SEMICOLON.contains(name) && (j == n || s.charAt(j) != '=')) {
                    out.append(NAMED_REFERENCES.get(name));
                    i = j;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int asciiDigit(char c, int radix) {
        if (c >= '0' && c <= '9') return c - '0';
        if (radix == 16 && c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (radix == 16 && c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    /** A TemplateOutput appending to a StringBuilder sized for its content. */
    private record Appender(StringBuilder sb) implements TemplateOutput {
        @Override public void writeContent(String value) { sb.append(value); }
        @Override public void writeContent(String value, int beginIndex, int endIndex) { sb.append(value, beginIndex, endIndex); }
    }
}
