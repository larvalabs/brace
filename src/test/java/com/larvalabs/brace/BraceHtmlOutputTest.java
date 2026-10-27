package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 + M5 (2026-10 security review): user data in URL attributes could carry a script URL past
 * JTE's narrow {@code javascript:} check, and user data in htmx's script attributes became code.
 */
class BraceHtmlOutputTest {

    static final String VIEWS = "src/test/resources/views";
    static TemplateEngine engine;

    static final List<String> URL_IDS = List.of("a", "img", "iframe", "form", "btn", "hxget", "dhxpost", "xlink");

    @BeforeAll
    static void setup() {
        engine = new TemplateEngine(VIEWS);
    }

    // --- M4: URL attributes ---

    @Test
    void scriptUrlsAreBlockedInEveryUrlAttribute() {
        for (String bad : List.of(
                "javascript:alert(1)", "JaVaScRiPt:alert(1)", "  javascript:alert(1)",
                "java\tscript:alert(1)", "java\nscript:alert(1)", "\u0001javascript:alert(1)",
                "javascript\r:alert(1)", "data:text/html,<script>alert(1)</script>", "vbscript:msgbox(1)",
                "DATA:text/html;base64,PHNjcmlwdD4=")) {
            String html = render(bad);
            for (String id : URL_IDS) {
                assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(html, id), id + " <- " + bad.replace("\t", "\\t"));
            }
        }
    }

    @Test
    void safeUrlsAreUnchanged() {
        for (String ok : List.of("/users/42", "users/42?tab=a:b", "https://example.com/x?y=1#z", "http://example.com",
                "mailto:a@example.com", "tel:+15551234", "#section", "?page=2", "//cdn.example.com/x.js",
                "/search?q=time:10am", "a/b:c")) {
            String html = render(ok);
            for (String id : URL_IDS) {
                assertEquals(Html.escape(ok), attr(html, id), id + " <- " + ok);
            }
        }
    }

    @Test
    void rasterDataImagesAreAllowedInSrcOnly() {
        String png = "data:image/png;base64,iVBORw0KGgo=";
        String html = render(png);
        assertEquals(png, attr(html, "img"));
        assertEquals(png, attr(html, "iframe"));   // iframe src is "src" too: a raster image is inert there
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(html, "a"));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(html, "hxget"));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(render("data:image/svg+xml,<svg onload=alert(1)>"), "img"));
    }

    @Test
    void aValueAfterAStaticPrefixIsOnlyEscaped() {
        // The template fixed the scheme ("/users/"), so the value can't choose one.
        assertEquals("/users/javascript:alert(1)", attr(render("javascript:alert(1)"), "prefixed"));
    }

    @Test
    void urlSafeHelpers() {
        assertTrue(Url.isSafe("/x"));
        assertTrue(Url.isSafe("https://example.com"));
        assertFalse(Url.isSafe("java\tscript:alert(1)"));
        assertFalse(Url.isSafe(null));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, Url.safe("data:text/html,x"));
        assertEquals("mailto:a@b.c", Url.safe("mailto:a@b.c"));
    }

    public record ProfileForm(@com.larvalabs.brace.annotation.SafeUrl String website) {}

    @Test
    void safeUrlAnnotationAcceptsOnlyAbsoluteHttp() {
        for (String ok : List.of("https://example.com", "http://example.com/a?b=c", "HTTPS://EXAMPLE.COM")) {
            assertFalse(FormBinder.bind(ProfileForm.class, Map.of("website", ok)).hasErrors(), ok);
        }
        assertFalse(FormBinder.bind(ProfileForm.class, Map.of("website", "")).hasErrors());
        for (String bad : List.of("javascript:alert(1)", "java\tscript:alert(1)", "data:text/html,x", "/relative",
                "example.com", "https://", "ftp://example.com", "https://exa mple.com")) {
            assertTrue(FormBinder.bind(ProfileForm.class, Map.of("website", bad)).hasErrors(), bad);
        }
    }

    // --- M5: script attributes ---

    @Test
    void htmxScriptAttributesAreJavaScriptEscaped() {
        String payload = "');alert(document.domain)//";
        String html = render(payload);
        for (String id : List.of("onclick", "hxon", "hxondash", "dhxon")) {
            String value = decodedAttr(html, id);
            assertEquals("go('\\x27);alert(document.domain)//')", value, id);
        }
        assertEquals("js:{v: \"\\x27);alert(document.domain)//\"}", decodedAttr(html, "vals"));
    }

    @Test
    void entityEncodedQuotesCantCloseTheString() {
        // JTE's own on* escaping left "&" alone: "&#39;" decoded back to a quote in the browser.
        String html = render("&#39;);alert(1)//");
        for (String id : List.of("onclick", "hxon")) {
            String decoded = decodedAttr(html, id);
            assertEquals("go('&#39;);alert(1)//')", decoded, id);
        }
    }

    @Test
    void srcdocIsText() {
        String decoded = decodedAttr(render("<script>alert(1)</script>"), "srcdoc");
        assertEquals("&lt;script>alert(1)&lt;/script>", decoded);
    }

    @Test
    void plainContextsAreUnchanged() {
        String html = render("<b>\"hi\"</b>");
        assertTrue(html.contains("<p id=\"text\">&lt;b&gt;\"hi\"&lt;/b&gt;</p>"), html);
        assertTrue(html.contains("<input id=\"value\" value=\"&lt;b>&#34;hi&#34;&lt;/b>\">"), html);
    }

    // --- Both render paths ---

    @Test
    void precompiledTemplatesRenderTheSameBytes(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("jte-classes");
        TemplatePrecompiler.precompile(Path.of(VIEWS), target);
        System.setProperty("brace.mode", "prod");
        System.setProperty("brace.templates.precompiled", target.toString());
        try {
            var precompiled = new TemplateEngine(VIEWS);
            for (String u : List.of("javascript:alert(1)", "/ok", "');alert(1)//", "&#39;x")) {
                var params = Map.<String, Object>of("u", u);
                assertArrayEquals(engine.renderToBytes("xssAttrs", params), precompiled.renderToBytes("xssAttrs", params), u);
                assertEquals(engine.render("xssAttrs", params), precompiled.render("xssAttrs", params), u);
            }
            assertTrue(precompiled.render("xssAttrs", Map.of("u", "javascript:x")).contains(BraceHtmlOutput.BLOCKED_URL));
        } finally {
            System.clearProperty("brace.mode");
            System.clearProperty("brace.templates.precompiled");
        }
    }

    @Test
    void bytesAndStringPathsAgree() {
        var params = Map.<String, Object>of("u", "java\tscript:alert(1)");
        assertEquals(engine.render("xssAttrs", params),
            new String(engine.renderToBytes("xssAttrs", params), StandardCharsets.UTF_8));
    }

    // --- helpers ---

    private static String render(String u) {
        return engine.render("xssAttrs", Map.of("u", u));
    }

    /** The raw (still HTML-escaped) value of the URL-ish attribute on the element with this id. */
    private static String attr(String html, String id) {
        var m = Pattern.compile("id=\"" + id + "\"[^>]*?\\s(?:href|src|action|formaction|hx-get|data-hx-post|xlink:href)=\"([^\"]*)\"")
            .matcher(html);
        assertTrue(m.find(), "no URL attribute on #" + id + " in " + html);
        return m.group(1);
    }

    /** The value of the script attribute on #id as the browser sees it: HTML entities decoded. */
    private static String decodedAttr(String html, String id) {
        var m = Pattern.compile("id=\"" + id + "\"\\s[\\w:.-]+=(['\"])(.*?)\\1").matcher(html);
        assertTrue(m.find(), "no attribute on #" + id + " in " + html);
        return decodeEntities(m.group(2));
    }

    private static String decodeEntities(String s) {
        var m = Pattern.compile("&(#(\\d+)|#x([0-9a-fA-F]+)|amp|lt|gt|quot|apos);").matcher(s);
        var out = new StringBuilder();
        while (m.find()) {
            String rep;
            if (m.group(2) != null) rep = String.valueOf((char) Integer.parseInt(m.group(2)));
            else if (m.group(3) != null) rep = String.valueOf((char) Integer.parseInt(m.group(3), 16));
            else rep = switch (m.group(1)) { case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\""; default -> "'"; };
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }
}
