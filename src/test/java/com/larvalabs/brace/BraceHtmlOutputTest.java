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

    // The same templates compiled with static text as Strings (JTE's default), which reaches the
    // output through writeContent, not writeBinaryContent.
    static gg.jte.TemplateEngine stringEngine;
    @TempDir static Path stringClasses;

    @BeforeAll
    static void setup() {
        engine = new TemplateEngine(VIEWS);
        stringEngine = gg.jte.TemplateEngine.create(
            new gg.jte.resolve.DirectoryCodeResolver(Path.of(VIEWS)), stringClasses, gg.jte.ContentType.Html);
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
            assertEquals("go('\\u0027);alert(document.domain)//')", value, id);
        }
        assertEquals("js:{v: \"\\u0027);alert(document.domain)//\"}", decodedAttr(html, "vals"));
    }

    @Test
    void entityEncodedQuotesCantCloseTheString() {
        // JTE's own on* escaping left "&" alone: "&#39;" decoded back to a quote in the browser.
        String html = render("&#39;);alert(1)//");
        for (String id : List.of("onclick", "hxon")) {
            String decoded = decodedAttr(html, id);
            assertEquals("go('\\u0026#39;);alert(1)//')", decoded, id);
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

    // --- Review fixes: the whole value is checked ---

    @Test
    void userDataThatDecidesTheSchemeAnywhereIsChecked() {
        // Every ${...} counts, not only the first: "" + "javascript:..." and "javascript" + "://..."
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(combo("", "javascript:alert(1)", 1), "ab"));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(combo(" ", "javascript:alert(1)", 1), "ab"));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(combo("java", "script:alert(1)", 1), "ab"));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(combo("javascript", "%0aalert(1)", 1), "scheme"));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(combo("", "javascript:alert(1)", 1), "loop"));
        assertEquals("https://example.com", attr(combo("https", "example.com", 1), "scheme"));
        assertEquals("/a/b", attr(combo("/a", "/b", 1), "ab"));
    }

    @Test
    void aSchemeTheTemplateWroteIsKept() {
        assertEquals("mailto:javascript:x", attr(combo("javascript:x", "b", 1), "mailto"));
        assertEquals("sms:+1555", attr(combo("+1555", "b", 1), "sms"));
        // A quote inside the template's value isn't the value's start.
        assertTrue(combo("a", "javascript:x", 1).contains("href='/r?x=\"javascript:x'"));
    }

    @Test
    void primitivesKeepTheirPlaceInTheValue() {
        assertEquals("1/x", attr(combo("a", "/x", 1), "num"));
        // A scheme can't start with a digit, so this is a relative path, as browsers read it.
        assertEquals("1javascript:x", attr(combo("a", "javascript:x", 1), "num"));
    }

    @Test
    void svgAnimationValuesAreUrls() {
        String html = combo("a", "javascript:alert(1)", 1);
        assertTrue(html.contains("id=\"set\" attributeName=\"href\" to=\"" + BraceHtmlOutput.BLOCKED_URL + "\""), html);
        assertTrue(html.contains("values=\"" + BraceHtmlOutput.BLOCKED_URL + "\""), html);
        assertTrue(combo("a", "/y", 1).contains("values=\"/x;/y\""));
    }

    @Test
    void hxVarsAndHxRequestAreScript() {
        String html = combo("a", "'+(window.pwned=1)+'", 1);
        assertEquals("v: '\\u0027+(window.pwned=1)+\\u0027'", named(html, "vars", "hx-vars"));
        assertTrue(named(html, "req", "hx-request").contains("\\u0027+(window.pwned=1)"));
    }

    @Test
    void dataFromATemplateCallInsideAnAttributeIsEscapedForThatAttribute() {
        // JTE gives a @template call's output no attribute context, so it used to get body
        // escaping (quotes left alone) and skip the URL and script rules entirely.
        String html = engine.render("xssCalleeCaller", Map.of("u", "javascript:alert(1)"));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, named(html, "c1", "href"), html);

        html = engine.render("xssCalleeCaller", Map.of("u", "x\" onmouseover=\"alert(1)"));
        assertFalse(Pattern.compile("<[^>]*\\sonmouseover=[\"']").matcher(html).find(), html);
        assertEquals("x\" onmouseover=\"alert(1)", named(html, "c2", "title"));
        html = engine.render("xssCalleeCaller", Map.of("u", "x' onmouseover='alert(1)"));
        assertFalse(Pattern.compile("<[^>]*\\sonmouseover=[\"']").matcher(html).find(), html);
        assertEquals("x' onmouseover='alert(1)", named(html, "c3", "title"));

        html = engine.render("xssCalleeCaller", Map.of("u", "');alert(1)//"));
        assertEquals("go('\\u0027);alert(1)//')", named(html, "c4", "onclick"), html);
        // Outside any attribute it is still body text.
        assertTrue(html.contains("<p id=\"c6\">');alert(1)//</p>"), html);
    }

    @Test
    void wholeValueJsonInHxValsIsKeptValid() throws Exception {
        // hx-vals='${json}': the app built the JSON; \\u-escaping its quotes would break it.
        String json = "{\"id\":42,\"name\":\"O'Brien <b>\"}";
        String html = engine.render("xssCalleeCaller", Map.of("u", json));
        var mapper = Json.mapper();
        assertEquals("O'Brien <b>", mapper.readTree(named(html, "j1", "hx-vals")).get("name").asText(), html);
        assertEquals(42, mapper.readTree(named(html, "j2", "hx-headers")).get("id").asInt());
        assertEquals("O'Brien <b>", mapper.readTree(named(html, "c5", "hx-vals")).get("name").asText());
        assertFalse(html.contains("hx-vals='{\"id\":42,\"name\":\"O'Brien"), "quotes are attribute-escaped: " + html);
        // A js: value would be evaluated by htmx, so a whole value of data never is.
        for (String code : List.of("js:{x: alert(1)}", " JavaScript:alert(1)")) {
            html = engine.render("xssCalleeCaller", Map.of("u", code));
            assertEquals("{}", named(html, "j1", "hx-vals"), code);
            assertEquals("{}", named(html, "j2", "hx-headers"), code);
        }
    }

    @Test
    void jsonInHxValsStaysValid() throws Exception {
        String value = "O'Brien \"Bob\" $5 `x` <b>&</b> \\ \n";
        String html = combo("a", value, 1);
        var mapper = Json.mapper();
        var vals = mapper.readTree(named(html, "json", "hx-vals"));
        assertEquals(value, vals.get("q").asText());
        assertEquals("kept", vals.get("other").asText());
        assertEquals(value, mapper.readTree(named(html, "hdrs", "hx-headers")).get("X-Q").asText());
    }

    @Test
    void contentBlocksRenderInsideAttributes() {
        String html = engine.render("xssContentCaller", Map.of("name", "<Ann>"));
        assertTrue(html.contains("<meta id=\"m\" content=\"Hi &lt;Ann>\">"), html);
        assertTrue(html.contains("href=\"Hi &lt;Ann>\""), html);
    }

    @Test
    void outputAfterAHeldValueIsInOrder() {
        String html = combo("/a", "/b", 7);
        assertTrue(html.indexOf("id=\"ab\"") < html.indexOf("id=\"after\""), html);
        assertTrue(html.contains("<p id=\"after\">/b</p>"), html);
    }

    private static String combo(String a, String b, int n) {
        return engine.render("xssCombos", Map.of("a", a, "b", b, "n", n));
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
                var combos = Map.<String, Object>of("a", "", "b", u, "n", 3);
                assertArrayEquals(engine.renderToBytes("xssCombos", combos), precompiled.renderToBytes("xssCombos", combos), u);
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

    // --- H1 (2026-11 security review): script bodies ---
    //
    // JavaScript such as `var a = "` looks like the start of an attribute value. A value was held
    // for it, and the data then got attribute escaping (no backslash or newline escaping) in the
    // middle of a script.

    static final List<String> SCRIPT_VALUES = List.of("\\", "\"", "'", "a\nb", "a\r\nb",
        "</script><script>alert(1)</script>", "a'b\"c\\d", "<!--", "&#39;", "plain");

    @Test
    void scriptBodiesAreJavaScriptEscaped() {
        for (String x : SCRIPT_VALUES) {
            String html = both("xssScript", Map.of("x", x, "y", "Y"));
            String e = js(x);
            assertTrue(html.contains("<script id=\"s1\">var a = \"" + e + "\";</script>"), html);
            assertTrue(html.contains("<script id=\"s2\">const name = '" + e + "';</script>"), html);
            assertTrue(html.contains("<script id=\"s3\">var a = \"" + e + "\", b = \"Y\";</script>"), html);
            assertTrue(html.contains("<script id=\"s4\">foo(\"" + e + "\");</script>"), html);
            // Variables named like URL attributes: no URL check in a script.
            assertTrue(html.contains("<script id=\"s5\">let src = \"" + e + "\", href = 'Y';</script>"), html);
            assertTrue(html.contains("<script id=\"s6\">var q = '" + e + "', r = 'Y';</script>"), html);
            // The second value on the line, too.
            html = both("xssScript", Map.of("x", "X", "y", x));
            assertTrue(html.contains("<script id=\"s3\">var a = \"X\", b = \"" + e + "\";</script>"), html);
            assertTrue(html.contains("<script id=\"s5\">let src = \"X\", href = '" + e + "';</script>"), html);
            assertTrue(html.contains("<script id=\"s6\">var q = 'X', r = '" + e + "';</script>"), html);
        }
    }

    @Test
    void aBackslashCantSwallowTheClosingQuoteInAScript() {
        // The exploit: "\" ate the first string's closing quote, and the second value was code.
        String html = both("xssScript", Map.of("x", "\\", "y", ";alert(1)//"));
        assertTrue(html.contains("<script id=\"s3\">var a = \"\\\\\", b = \";alert(1)\\/\\/\";</script>"), html);
        assertTrue(html.contains("<p id=\"body\">it's a = \"5</p><script id=\"s6\">var q = '\\\\', r = ';alert(1)\\/\\/';</script>"), html);
        html = both("xssScript", Map.of("x", "javascript:alert(1)", "y", "data:text/html,x"));
        assertFalse(html.contains("<script id=\"s5\">let src = \"" + BraceHtmlOutput.BLOCKED_URL), html);
        assertTrue(html.contains("<script id=\"s5\">let src = \"javascript:alert(1)\", href = 'data:text\\/html,x';</script>"), html);
    }

    @Test
    void bodyTextThatLooksLikeAnAttributeIsBodyText() {
        String html = both("xssScript", Map.of("x", "<b>\"hi\"</b>", "y", "it's"));
        assertTrue(html.contains("<p id=\"text\">a = \"&lt;b&gt;\"hi\"&lt;/b&gt;\" and b = 'it's'</p>"), html);
    }

    @Test
    void attributesAroundScriptsAreUnchanged() {
        String html = both("xssScript", Map.of("x", "javascript:alert(1)", "y", "\"><i>"));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(html, "after"));
        assertTrue(html.contains("<a id=\"after\" href=\"" + BraceHtmlOutput.BLOCKED_URL + "\" title=\"&#34;>&lt;i>\">x</a>"), html);
        html = both("xssScript", Map.of("x", "');alert(1)//", "y", "/ok"));
        assertEquals("go('\\u0027);alert(1)//')", named(html, "click", "onclick"));
        // And the templates the other tests use render the same in both static-content modes.
        for (String u : List.of("javascript:alert(1)", "/ok", "');alert(1)//", "&#39;x", "{\"a\":1}")) {
            both("xssAttrs", Map.of("u", u));
            both("xssCalleeCaller", Map.of("u", u));
            both("xssCombos", Map.of("a", "", "b", u, "n", 3));
            both("xssCombos", Map.of("a", "javascript", "b", u, "n", 3));
        }
        both("xssContentCaller", Map.of("name", "<Ann>"));
    }

    /**
     * Renders with static template text as pre-encoded bytes (how Brace runs, to a String and to
     * bytes) and as Strings (JTE's default), asserts all three agree, and returns the HTML.
     */
    private static String both(String template, Map<String, Object> params) {
        String binary = engine.render(template, params);
        assertEquals(binary, new String(engine.renderToBytes(template, params), StandardCharsets.UTF_8), template);
        var out = new gg.jte.output.StringOutput();
        var html = new BraceHtmlOutput(out);
        stringEngine.render(template + ".jte", params, html);
        html.finish();
        assertEquals(binary, out.toString(), template + ": String and binary static content differ");
        return binary;
    }

    /** JTE's escaping for a script body. */
    private static String js(String value) {
        var out = new gg.jte.output.StringOutput();
        gg.jte.html.escape.Escape.javaScriptBlock(value, out);
        return out.toString();
    }

    // --- helpers ---

    private static String render(String u) {
        return engine.render("xssAttrs", Map.of("u", u));
    }

    /** The raw (still HTML-escaped) value of the URL-ish attribute on the element with this id. */
    private static String attr(String html, String id) {
        var m = Pattern.compile("id=\"" + id + "\"[^>]*?\\s(?:href|src|action|formaction|hx-get|data-hx-post|xlink:href)=[\"']([^\"']*)[\"']")
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

    /** The decoded value of attribute {@code name} on #id. */
    private static String named(String html, String id, String name) {
        var m = Pattern.compile("id=\"" + id + "\"[^>]*?\\s" + Pattern.quote(name) + "=(['\"])(.*?)\\1").matcher(html);
        assertTrue(m.find(), "no " + name + " on #" + id + " in " + html);
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
