package com.larvalabs.brace;

import gg.jte.Content;
import gg.jte.TemplateOutput;
import gg.jte.html.OwaspHtmlTemplateOutput;
import gg.jte.html.escape.Escape;
import gg.jte.output.StringOutput;

import java.util.Locale;
import java.util.Set;

/**
 * The output every Brace template renders through (M4, M5 — 2026-10 security review). JTE's own
 * {@link OwaspHtmlTemplateOutput} escapes per context but guards URLs only on {@code <a href>},
 * only against a literal {@code javascript:} prefix, and treats htmx's code-bearing attributes as
 * plain text. This adds:
 *
 * <ul>
 *   <li><strong>URL attributes</strong> ({@code href}, {@code src}, {@code action},
 *       {@code formaction}, {@code hx-get}, ... on any tag): a value written at the start of the
 *       attribute is normalized the way browsers parse URLs (leading/trailing control characters
 *       and spaces stripped, tabs and newlines removed) and allowed only as a relative URL or an
 *       {@code http}, {@code https}, {@code mailto} or {@code tel} URL. Anything else
 *       ({@code javascript:}, {@code data:}, {@code java\tscript:}) renders as
 *       {@code about:invalid#blocked}. A value after a static prefix ({@code href="/users/${id}"})
 *       can't choose the scheme and is only escaped. {@code src} and {@code poster} also take a
 *       raster {@code data:image/...} URL (png, jpeg, gif, webp, avif, bmp; never SVG).</li>
 *   <li><strong>Script attributes</strong> ({@code on*}, {@code hx-on*}, and {@code hx-vals} /
 *       {@code hx-headers} / {@code hx-trigger}, which htmx can evaluate): JavaScript-escaped and
 *       then HTML-escaped. JTE's JavaScript escaping alone leaves {@code &} as is, so
 *       {@code &#39;} in user data decoded back to a quote in the browser and closed the string.</li>
 *   <li>{@code srcdoc}: escaped twice, so user data becomes text in the frame, not markup.</li>
 * </ul>
 *
 * JTE uses an output it's given as is when it is already an HTML output, so dev (hot-reload) and
 * precompiled templates both go through this class.
 */
public class BraceHtmlOutput extends OwaspHtmlTemplateOutput {

    static final String BLOCKED_URL = "about:invalid#blocked";

    private static final Set<String> URL_ATTRIBUTES = Set.of(
        "href", "src", "action", "formaction", "xlink:href", "poster", "cite", "background", "data",
        "codebase", "manifest", "hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete");
    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto", "tel");

    private final TemplateOutput target;
    private String tagName;
    private String attributeName;
    // Whether the next user content is the first thing in the current attribute's value: only a
    // value there decides the URL's scheme. Set from the static template text written last.
    private boolean atValueStart;

    public BraceHtmlOutput(TemplateOutput target) {
        super(target);
        this.target = target;
    }

    @Override
    public void setContext(String tagName, String attributeName) {
        super.setContext(tagName, attributeName);
        this.tagName = tagName;
        this.attributeName = attributeName == null ? null : attributeName.toLowerCase(Locale.ROOT);
    }

    @Override
    public void writeContent(String value) {
        trackStatic(value, 0, value.length());
        super.writeContent(value);
    }

    @Override
    public void writeContent(String value, int beginIndex, int endIndex) {
        trackStatic(value, beginIndex, endIndex);
        super.writeContent(value, beginIndex, endIndex);
    }

    @Override
    public void writeBinaryContent(byte[] value) {
        // Static template text pre-encoded as UTF-8; the attribute syntax that matters is ASCII.
        int end = value.length;
        while (end > 0 && isUrlWhitespace((char) (value[end - 1] & 0xff))) end--;
        atValueStart = end > 1 && (value[end - 1] == '"' || value[end - 1] == '\'') && value[end - 2] == '=';
        super.writeBinaryContent(value);
    }

    private void trackStatic(String value, int begin, int end) {
        while (end > begin && isUrlWhitespace(value.charAt(end - 1))) end--;
        atValueStart = end - begin > 1 && (value.charAt(end - 1) == '"' || value.charAt(end - 1) == '\'')
            && value.charAt(end - 2) == '=';
    }

    @Override
    public void writeUserContent(String value) {
        if (value == null) return;
        boolean first = atValueStart;
        atValueStart = false;
        if (tagName == null || attributeName == null) {
            super.writeUserContent(value);
            return;
        }
        if (URL_ATTRIBUTES.contains(unprefixed(attributeName))) {
            // Escaped here rather than by JTE, whose own a/href check drops a value starting with
            // "javascript:" even after a static prefix, where it's only a harmless path segment.
            boolean safe = isSafeUrl(value) || (isMediaAttribute(attributeName) && isRasterDataUrl(value));
            Escape.htmlAttribute(first && !safe ? BLOCKED_URL : value, target);
        } else if (isScriptAttribute(attributeName)) {
            var js = new StringOutput();
            Escape.javaScriptAttribute(value, js);
            Escape.htmlAttribute(js.toString(), target);
        } else if ("srcdoc".equals(attributeName)) {
            var once = new StringOutput();
            Escape.htmlAttribute(value, once);
            Escape.htmlAttribute(once.toString(), target);
        } else {
            super.writeUserContent(value);
        }
    }

    @Override
    public void writeUserContent(Content content) {
        if (content == null) return;
        if (tagName != null && attributeName != null) {
            // Rendered to text first, then treated like any other value in this attribute.
            var text = new StringOutput();
            content.writeTo(text);
            writeUserContent(text.toString());
        } else {
            atValueStart = false;
            super.writeUserContent(content);
        }
    }

    private static final java.util.regex.Pattern RASTER_DATA_URL =
        java.util.regex.Pattern.compile("(?i)data:image/(png|jpeg|jpg|gif|webp|avif|bmp)[;,].*", java.util.regex.Pattern.DOTALL);

    /** {@code src} and {@code poster} load media, where a raster {@code data:} image can't run script. */
    private static boolean isMediaAttribute(String attribute) {
        return attribute.equals("src") || attribute.equals("poster");
    }

    /** A {@code data:} URL of a raster image type: never SVG, which can carry script. */
    static boolean isRasterDataUrl(String url) {
        return RASTER_DATA_URL.matcher(normalize(url)).matches();
    }

    /** {@code data-hx-get} is htmx's {@code hx-get}. */
    private static String unprefixed(String attribute) {
        return attribute.startsWith("data-hx-") ? attribute.substring(5) : attribute;
    }

    private static boolean isScriptAttribute(String attribute) {
        String name = unprefixed(attribute);
        return name.startsWith("on") || name.startsWith("hx-on")
            || name.equals("hx-vals") || name.equals("hx-headers") || name.equals("hx-trigger");
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

    private static boolean isUrlWhitespace(char c) {
        return c <= ' ';
    }
}
