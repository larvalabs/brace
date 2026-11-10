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
        String code = "\\u003balert\\u00281\\u0029\\u002f\\u002f";
        assertTrue(html.contains("<script id=\"s3\">var a = \"\\\\\", b = \"" + code + "\";</script>"), html);
        assertTrue(html.contains("<p id=\"body\">it's a = \"5</p><script id=\"s6\">var q = '\\\\', r = '" + code + "';</script>"), html);
        html = both("xssScript", Map.of("x", "javascript:alert(1)", "y", "data:text/html,x"));
        assertFalse(html.contains("<script id=\"s5\">let src = \"" + BraceHtmlOutput.BLOCKED_URL), html);
        assertTrue(html.contains("<script id=\"s5\">let src = \"javascript\\u003aalert\\u00281\\u0029\", href = 'data\\u003atext\\u002fhtml,x';</script>"), html);
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

    // --- L7 (2026-11): <Script> ---

    @Test
    void aScriptTagInAnyCaseIsAScript() {
        // JTE compares the tag name to "script" exactly, so <Script> got body escaping, which
        // leaves quotes and backslashes alone.
        for (String x : SCRIPT_VALUES) {
            String html = both("xssScriptCase", Map.of("x", x));
            String e = js(x);
            assertTrue(html.contains("<Script id=\"m1\">foo('" + e + "')</Script>"), html);
            assertTrue(html.contains("<sCRIPT id=\"m2\">var a = \"" + e + "\";</sCRIPT>"), html);
            assertTrue(html.contains("<script id=\"m4\">baz('" + e + "')</script>"), html);
            // After the end tag, in any case, it is body text again.
            assertTrue(html.contains("<p id=\"after\">" + Html.escape(x).replace("&quot;", "\"").replace("&#39;", "'") + "</p>"), html);
        }
        String html = both("xssScriptCase", Map.of("x", "');alert(1);//"));
        String escaped = "\\u0027\\u0029\\u003balert\\u00281\\u0029\\u003b\\u002f\\u002f";
        assertTrue(html.contains("<Script id=\"m1\">foo('" + escaped + "')</Script>"), html);
        // A @template call inside <Script> (JTE allows it there, and reports its output as body
        // text): escaped the same way.
        assertTrue(html.contains("<Script id=\"m3\">bar('" + escaped + "')</Script>"), html);
        html = both("xssScriptCase", Map.of("x", "\\"));
        assertTrue(html.contains("<Script id=\"m3\">bar('\\\\')</Script>"), html);
    }

    // --- L7 (2026-11): a javascript: URL the template wrote ---

    @Test
    void dataInAScriptUrlTheTemplateWroteIsBlocked() {
        // href="javascript:go('${x}')": the template chose the scheme, so the value used to be
        // only attribute-escaped, and the browser decodes that (and %27) before running the code.
        for (String x : List.of("');alert(1);//", "%27);alert(1);//", "alert(1)", "5", "")) {
            String html = both("xssJsUrl", Map.of("x", x));
            for (String id : List.of("j1", "j2", "j3", "j4", "j6", "j7", "j8")) {
                assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(html, id), id + " <- " + x);
            }
            // Template text alone is the template's business; other schemes and paths are kept.
            assertEquals("javascript:void(0)", attr(html, "k1"));
            assertEquals("mailto:" + x, named(html, "k2", "href"));
            assertEquals("/javascript:" + x, named(html, "k3", "href"));
            assertEquals("?next=javascript:" + x, named(html, "k4", "href"));
        }
    }

    // --- L7 (2026-11): the js: guard on hx-vals / hx-headers ---

    @Test
    void dataCantMakeAnHxValsValueAnExpression() {
        // htmx trims with JavaScript's trim(), which strips U+00A0, U+FEFF and the other Unicode
        // spaces, then evaluates a value starting js: or javascript:.
        for (String code : List.of("js:{x: alert(1)}", " js:{x: alert(1)}", "﻿javascript:alert(1)",
                "   \tjs:alert(1)", " JS:alert(1)", "　js:alert(1)")) {
            String html = both("xssHxVals", Map.of("a", code, "b", ""));
            for (String id : List.of("v1", "v2", "v3", "v6")) assertEquals("{}", named(html, id, "hx-vals"), id + " <- " + code);
            assertEquals("{}", named(html, "v4", "hx-headers"), code);
            assertEquals("{}", named(html, "v5", "data-hx-vals"), code);
        }
        // The prefix assembled from more than one piece of data, or from template text and data.
        for (var parts : List.of(List.of("js", ":alert(1)"), List.of("", "js:alert(1)"), List.of(" ", "js:alert(1)"),
                List.of("j", "s:alert(1)"), List.of("javascript", ":alert(1)"))) {
            String html = both("xssHxVals", Map.of("a", parts.get(0), "b", parts.get(1)));
            assertEquals("{}", named(html, "v3", "hx-vals"), parts.toString());
            assertEquals("{}", named(html, "v4", "hx-headers"), parts.toString());
            assertEquals("{}", named(html, "v6", "hx-vals"), parts.toString());
        }
        assertEquals("{}", named(both("xssHxVals", Map.of("a", "", "b", "s:alert(1)")), "v7", "hx-headers"));
    }

    // L7 review (F4): htmx reads hx-request through the same function as hx-vals and hx-headers,
    // and evaluates it on the same js: / javascript: prefix.
    @Test
    void dataCantMakeAnHxRequestValueAnExpression() throws Exception {
        for (String code : List.of("js:x:alert(1)", " javascript:{x:alert(1)}", " js:{timeout: alert(1)}", "﻿JS:alert(1)")) {
            String html = both("xssHxRequest", Map.of("a", code, "b", ""));
            for (String id : List.of("r1", "r3", "r4", "r5")) assertEquals("{}", named(html, id, "hx-request"), id + " <- " + code);
            assertEquals("{}", named(html, "r2", "data-hx-request"), code);
            assertFalse(html.contains("alert(1)\"></div>\n<div id=\"r3\""), html);
        }
        for (var parts : List.of(List.of("js", ":alert(1)"), List.of("j", "s:alert(1)"), List.of(" ", "javascript:alert(1)"))) {
            String html = both("xssHxRequest", Map.of("a", parts.get(0), "b", parts.get(1)));
            assertEquals("{}", named(html, "r4", "hx-request"), parts.toString());
        }
        // What the template wrote is kept, and JSON stays JSON.
        var mapper = Json.mapper();
        String html = both("xssHxRequest", Map.of("a", "js:alert(1)", "b", ""));
        assertEquals("js:alert(1)", mapper.readTree(named(html, "k3", "hx-request")).get("credentials").asText());
        assertEquals("js:{timeout: \"js:alert(1)\"}", named(html, "k2", "hx-request"));
        html = both("xssHxRequest", Map.of("a", "3000", "b", ""));
        assertEquals(3000, mapper.readTree(named(html, "k1", "hx-request")).get("timeout").asInt());
        html = both("xssHxRequest", Map.of("a", "{\"timeout\":100,\"credentials\":true}", "b", ""));
        assertEquals(100, mapper.readTree(named(html, "r1", "hx-request")).get("timeout").asInt());
        assertEquals(100, mapper.readTree(named(html, "r2", "data-hx-request")).get("timeout").asInt());
        html = both("xssHxRequest", Map.of("a", "\"+alert(1)+\"", "b", ""));
        assertEquals("js:{timeout: \"\\u0022+alert(1)+\\u0022\"}", named(html, "k2", "hx-request"));
    }

    @Test
    void scriptEscapingRoundTrips() {
        for (String s : List.of("", "plain", "\\", "\\u0041", "a\tb\n\"'`$&<>  \\\\u0022", "\\u00zz", "end\\")) {
            assertEquals(s, BraceHtmlOutput.unescapeScript(BraceHtmlOutput.escapeScript(s)), s);
        }
    }

    @Test
    void hxValsTheTemplateWroteStillWork() throws Exception {
        var mapper = Json.mapper();
        String html = both("xssHxVals", Map.of("a", "js:alert(1)", "b", " js:x"));
        // Data inside a JSON string is data, whatever it starts with.
        assertEquals("js:alert(1)", mapper.readTree(named(html, "k2", "hx-vals")).get("q").asText());
        assertEquals(" js:x", mapper.readTree(named(html, "k3", "hx-vals")).get("r").asText());
        // A js: expression the template wrote keeps its (script-escaped) data.
        html = both("xssHxVals", Map.of("a", "\"+alert(1)+\"", "b", ""));
        assertEquals("js:{v: \"\\u0022+alert(1)+\\u0022\"}", named(html, "k1", "hx-vals"));
        // Whole-value JSON the app built is kept intact, also with space around it in the data.
        html = both("xssHxVals", Map.of("a", " {\"id\":42,\"n\":\"O'Brien\"} ", "b", ""));
        assertEquals("O'Brien", mapper.readTree(named(html, "v1", "hx-vals")).get("n").asText());
        assertEquals("not js", named(both("xssHxVals", Map.of("a", "not js", "b", ":x")), "v1", "hx-vals"));
    }

    // --- L7 (2026-11): which value is held, and what the template's prefix really is ---

    @Test
    void theHeldValueIsTheOneTheTagIsReallyIn() {
        // An earlier attribute whose text looks like `name = quote` used to be taken for the open
        // value, and the real href was then judged one ${...} at a time: "javascript" and
        // "%0aalert(1)" are each harmless alone.
        String html = both("xssSplit", Map.of("x", "javascript", "y", "%0aalert(1)"));
        String blocked = BraceHtmlOutput.BLOCKED_URL;
        assertTrue(html.contains("<a id=\"s1\" title=\"x a = 'b\" href=\"" + blocked + "\">"), html);
        assertTrue(html.contains("<a id=\"s2\" data-note='say \"href=' href=\"" + blocked + "\">"), html);
        assertTrue(html.contains("<a id=\"s3\" title=\"1 > 0\" href=\"" + blocked + "\">"), html);
        assertTrue(html.contains("<a id=\"s4\" class=\"c\"href=\"" + blocked + "\">"), html);
        assertTrue(html.contains("<a id=\"s7\" href = \"" + blocked + "\">"), html);
        assertTrue(html.contains("<a id=\"s8\" title=\"a\" data-x=\"b = '\" href='" + blocked + "'>"), html);
        assertTrue(html.contains("<p id=\"s9\">a href=\"javascript://%0aalert(1)\"</p>"), html); // body text
        html = both("xssSplit", Map.of("x", "javascript", "y", "alert(1)"));
        assertTrue(html.contains("<img id=\"s5\" alt=\"it's a = \" src=\"" + blocked + "\">"), html);

        html = both("xssSplit", Map.of("x", "https", "y", "example.com/a?b=1"));
        assertTrue(html.contains("<a id=\"s1\" title=\"x a = 'b\" href=\"https://example.com/a?b=1\">"), html);
        assertTrue(html.contains("<a id=\"s2\" data-note='say \"href=' href=\"https://example.com/a?b=1\">"), html);
        assertTrue(html.contains("<a id=\"s3\" title=\"1 > 0\" href=\"https://example.com/a?b=1\">"), html);
        assertTrue(html.contains("<a id=\"s4\" class=\"c\"href=\"https://example.com/a?b=1\">"), html);
        assertTrue(html.contains("<img id=\"s5\" alt=\"it's a = \" src=\"https:example.com/a?b=1\">"), html);
        assertTrue(html.contains("<a id=\"s7\" href = \"https://example.com/a?b=1\">"), html);
        assertTrue(html.contains("<a id=\"s8\" title=\"a\" data-x=\"b = '\" href='https://example.com/a?b=1'>"), html);
    }

    @Test
    void aTemplateCallAfterDataInTheSameAttributeIsStillInTheAttribute() {
        // title="${y} @template...": once ${y} was written the value was no longer tracked, and
        // the call's output got body escaping, which leaves quotes alone.
        for (String quote : List.of("\"", "'")) {
            String x = "x" + quote + " onmouseover=" + quote + "alert(1)";
            String html = both("xssSplit", Map.of("x", x, "y", "t"));
            assertFalse(Pattern.compile("<[^>]*\\sonmouseover=[\"']").matcher(html).find(), html);
            assertEquals("t " + x, named(html, "s6", "title"));
        }
    }

    @Test
    void characterReferencesInTheTemplatesPrefixAreRead() {
        // href="&#32;${x}": the browser sees a space, not a '#', so x still chooses the scheme.
        String blocked = BraceHtmlOutput.BLOCKED_URL;
        for (String x : List.of("javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html,x")) {
            String html = both("xssSplit", Map.of("x", x, "y", "32"));
            for (String id : List.of("e1", "e2", "e3", "e5", "e6")) assertEquals(blocked, attr(html, id), id + " <- " + x);
            assertEquals(blocked, attr(html, "e4"), "a script URL spelled with references is one");
            // Kept: the template's text, references decoded, settles the scheme or the path.
            assertEquals("/search?a=1&b=" + x, named(html, "k1", "href"));
            assertEquals("/search?a=1&b=" + x, named(html, "k2", "href"));
            assertEquals("/users/" + x, named(html, "k3", "href"));
            assertTrue(html.contains("<a id=\"k3\" href=\"&#47;users/"), html);
            assertTrue(html.contains("<a id=\"k4\" href=\"mailto&colon;"), html);
            // An unknown reference is some non-ASCII character: no scheme can follow it.
            assertTrue(html.contains("<a id=\"k5\" href=\"&copy;" + x.replace("<", "&lt;") + "\">"), html);
        }
        String html = both("xssSplit", Map.of("x", "/ok?a=b", "y", "32"));
        for (String id : List.of("e1", "e2", "e3", "e5", "e6")) assertTrue(attr(html, id).endsWith("/ok?a=b"), id);
        // The data completes a reference the template started: &# + "106" + ; + "avascript:..."
        html = both("xssSplit", Map.of("x", "avascript:alert(1)", "y", "106"));
        assertEquals(blocked, attr(html, "e5"));

        // And for the hx-vals js: prefix.
        assertEquals("{}", named(both("xssSplit", Map.of("x", "s:alert(1)", "y", "")), "h1", "hx-vals"));
        html = both("xssSplit", Map.of("x", "js:alert(1)", "y", ""));
        assertEquals("{}", named(html, "h2", "hx-vals"));
        assertEquals("{}", named(html, "h3", "hx-vals"));
    }

    @Test
    void characterReferenceDecoding() {
        assertEquals(" javascript:x", BraceHtmlOutput.decodeReferences("&#32;javascript&colon;x"));
        assertEquals("j\tA\n", BraceHtmlOutput.decodeReferences("&#x6A;&Tab;&#65&NewLine;"));
        assertEquals("a&b=1&c", BraceHtmlOutput.decodeReferences("a&b=1&amp;c"));
        assertEquals("<>\"'&", BraceHtmlOutput.decodeReferences("&lt;&gt;&quot;&#39;&amp;"));
        // Without a semicolon a named reference isn't one before '=' or a letter or digit.
        assertEquals("?a&lt=1&ltx&<", BraceHtmlOutput.decodeReferences("?a&lt=1&ltx&&lt"));
        assertEquals("���", BraceHtmlOutput.decodeReferences("&#0;&#xD800;&#x110000;"));
        assertEquals("&;&#;&#x;& &", BraceHtmlOutput.decodeReferences("&;&#;&#x;& &"));
        assertEquals("�x", BraceHtmlOutput.decodeReferences("&copy;x"));
        assertEquals("plain", BraceHtmlOutput.decodeReferences("plain"));
    }

    // --- L7 (2026-11): CSS ---

    @Test
    void plainValuesInCssAreUnchanged() {
        for (String x : List.of("red", "#ff0000", "50%", "1.5em", "-3px solid", "a_b-c", "café", "")) {
            String html = both("xssStyle", Map.of("x", x));
            assertTrue(html.contains("<style id=\"st1\">.a { color: " + x + "; }</style>"), html);
            assertTrue(html.contains("<style id=\"st2\">.b::after { content: \"" + x + "\"; }</style>"), html);
            assertTrue(html.contains("<Style id=\"st3\">.c-" + x + " { top: 0 }</Style>"), html);
            assertTrue(html.contains("<div id=\"st4\" style=\"color: " + x + "\">"), html);
            assertTrue(html.contains("<div id=\"st6\" style=\"" + x + "\">"), html);
            assertTrue(html.contains("<div id=\"st7\" style=\"width: " + x + "\">"), html);
        }
    }

    @Test
    void dataInCssCantLeaveItsValue() {
        // Only HTML-escaped before: a value could close the rule and add its own, such as an
        // attribute selector that leaks a hidden field one character at a time.
        for (String x : List.of(
                "red; } input[name=_csrf][value^=a] { background: url(//evil.example/a) } .x {",
                "red;background:url(//evil.example/x)", "x\" onmouseover=\"alert(1)", "x' onmouseover='alert(1)",
                "</style><script>alert(1)</script>", "</Style><script>alert(1)</script>", "expression(alert(1))",
                "\\3b color\\3a red", "a\nb", "/* */ @import 'x';", "<!-- -->", "a,b", "\u0000")) {
            String html = both("xssStyle", Map.of("x", x));
            String e = BraceHtmlOutput.escapeCss(x);
            assertTrue(e.matches("[A-Za-z0-9 #%._\\\\-]*"), "escaped to plain characters and hex escapes: " + e);
            if (!x.equals("\u0000")) assertEquals(x, cssUnescape(e), "a CSS parser reads the data back: " + e);
            assertTrue(html.contains("<style id=\"st1\">.a { color: " + e + "; }</style>"), html);
            assertTrue(html.contains("<style id=\"st2\">.b::after { content: \"" + e + "\"; }</style>"), html);
            assertTrue(html.contains("<Style id=\"st3\">.c-" + e + " { top: 0 }</Style>"), html);
            assertTrue(html.contains("<div id=\"st4\" style=\"color: " + e + "\">"), html);
            assertTrue(html.contains("<div id=\"st5\" style='background-image: url(\"" + e + "\")'>"), html);
            assertTrue(html.contains("<div id=\"st6\" style=\"" + e + "\">"), html);
            assertTrue(html.contains("<div id=\"st7\" style=\"width: " + e + "\">"), html);
            assertFalse(html.contains("<script>"), html);
            assertFalse(Pattern.compile("<[^>]*\\sonmouseover=[\"']").matcher(html).find(), html);
            // Everything else on the page is as before.
            assertTrue(html.contains("<p id=\"st8\" class=\"" + attrEscaped(x) + "\">"), html);
        }
        assertEquals("a\\3b b\\7d ", BraceHtmlOutput.escapeCss("a;b}"));
        assertEquals("\\fffd ", BraceHtmlOutput.escapeCss("\u0000"));
        // A URL in a quoted url("...") still works: the CSS parser undoes the escapes in a string.
        String html = both("xssStyle", Map.of("x", "https://example.com/a.png?b=1"));
        assertTrue(html.contains("url(\"https\\3a \\2f \\2f example.com\\2f a.png\\3f b\\3d 1\")"), html);
    }

    private static String cssUnescape(String css) {
        var m = Pattern.compile("\\\\([0-9a-f]{1,6}) ").matcher(css);
        var out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
            Character.toString(Integer.parseInt(m.group(1), 16))));
        m.appendTail(out);
        return out.toString();
    }

    /** JTE's attribute escaping. */
    private static String attrEscaped(String value) {
        var out = new gg.jte.output.StringOutput();
        gg.jte.html.escape.Escape.htmlAttribute(value, out);
        return out.toString();
    }

    // --- the tokenizer, driven directly ---

    /** Template text, or (with a tag) a ${...} and the context JTE reports for it. */
    record Part(String text, String tag, String attribute) {
        static Part text(String text) { return new Part(text, null, null); }
        static Part data(String tag, String attribute, String value) { return new Part(value, tag, attribute); }
    }

    /** Writes the parts as JTE would, template text in chunks of {@code chunk} characters (0: whole). */
    private static String drive(List<Part> parts, int chunk, boolean binary) {
        var out = new gg.jte.output.StringOutput();
        var html = new BraceHtmlOutput(out);
        for (Part part : parts) {
            if (part.tag() != null) {
                html.setContext(part.tag(), part.attribute());
                html.writeUserContent(part.text());
                if (part.attribute() != null) html.setContext(part.tag(), null);
                continue;
            }
            int step = chunk == 0 ? Math.max(1, part.text().length()) : chunk;
            for (int i = 0; i < part.text().length(); i += step) {
                String piece = part.text().substring(i, Math.min(part.text().length(), i + step));
                if (binary) html.writeBinaryContent(piece.getBytes(StandardCharsets.UTF_8));
                else html.writeContent(piece);
            }
        }
        html.finish();
        return out.toString();
    }

    /** The output, the same however the template text is cut up and whichever way it is written. */
    private static String driven(Part... parts) {
        String whole = drive(List.of(parts), 0, false);
        for (int chunk : new int[] {0, 1, 2, 3, 7}) {
            assertEquals(whole, drive(List.of(parts), chunk, false), "String chunks of " + chunk);
            assertEquals(whole, drive(List.of(parts), chunk, true), "byte chunks of " + chunk);
        }
        return whole;
    }

    @Test
    void theOutputDoesntDependOnWhereTemplateTextIsCut() {
        String blocked = BraceHtmlOutput.BLOCKED_URL;
        assertEquals("<a class=\"x\" href=\"" + blocked + "\">x</a><script>var a = \"\\\\\";</script>"
                + "<p title='it&#39;s'>&lt;i&gt;</p>",
            driven(Part.text("<a class=\"x\" href=\""), Part.data("a", "href", "javascript:alert(1)"),
                Part.text("\">x</a><script>var a = \""), Part.data("script", null, "\\"),
                Part.text("\";</script><p title='"), Part.data("p", "title", "it's"), Part.text("'>"),
                Part.data("p", null, "<i>"), Part.text("</p>")));

        // '<' and quotes in a script, an end tag with a space, then a real attribute.
        assertEquals("<SCRIPT>if (a < b && c > d) x = '\\u0027';</SCRIPT ><a href='/ok'>",
            driven(Part.text("<SCRIPT>if (a < b && c > d) x = '"), Part.data("SCRIPT", null, "'"),
                Part.text("';</SCRIPT ><a href='"), Part.data("a", "href", "/ok"), Part.text("'>")));

        // "</scripty" and "</style" don't end a script; a comment's text isn't markup.
        assertEquals("<script>a = '</scripty>' + \"</style>\\\\\";</script><!-- <a href=\"&lt;\"> --><a href=\"" + blocked + "\">",
            driven(Part.text("<script>a = '</scripty>' + \"</style>"), Part.data("script", null, "\\"),
                Part.text("\";</script><!-- <a href=\""), Part.data("html", null, "<"),
                Part.text("\"> --><a href=\""), Part.data("a", "href", "javascript:x"), Part.text("\">")));

        // "<!-->" is a whole comment; '>' inside a quoted value doesn't end the tag.
        assertEquals("<!--><i title=\"a > b\" data-x='\"' href=\"" + blocked + "\">",
            driven(Part.text("<!--><i title=\"a > b\" data-x='\"' href=\""), Part.data("i", "href", "JaVaScRiPt:x"), Part.text("\">")));

        // Unquoted values and attributes without one; then a held one. A tag's own end tag has no values.
        assertEquals("<input disabled value=a/b title = \"&#34;\" src=\"" + blocked + "\"></p title=\"&#34;\">",
            driven(Part.text("<input disabled value=a/b title = \""), Part.data("input", "title", "\""),
                Part.text("\" src=\""), Part.data("input", "src", "data:text/html,x"),
                Part.text("\"></p title=\""), Part.data("p", "title", "\""), Part.text("\">")));

        // Body text that looks like an attribute, and "1 < 2".
        assertEquals("<p>1 < 2, href=\"&lt;x&gt;\"</p>",
            driven(Part.text("<p>1 < 2, href=\""), Part.data("p", null, "<x>"), Part.text("\"</p>")));
    }

    @Test
    void randomDocumentsRenderTheSameHoweverTheyAreCut() {
        // Every state the tokenizer has, in orders no template would write: the output must not
        // depend on where the template text is cut, or on whether it arrives as Strings or bytes.
        String[] fragments = {"<a href=\"", "\"", "'", ">", "<script>", "</script>", "<style>", "</style >", "<!--", "-->", " title='",
            " hx-vals='", " hx-request=\"", "&#", "106;", "javascript:", "é", " ", "=", "/", "<", "x", " onclick=\"", " style=\"", "<svg>",
            "</svg>", "<set to=\"", "&colon;", "\n", "<div", "</div>", " src=\"", "</a>", "<p>", "js:", "</Script\t>", " srcdoc='", "<!",
            "?", "<a", " href='", "\f", "<textarea>", "</", "<img", "<script", "<script href=\"/a.js\"/>", "<style/>", "/>", "<math>",
            "</math>", "<foreignObject>", "</foreignObject>", "<title>", "</title>", "<desc>", "<mi>", "</p>", "<![CDATA[", "]]>", "]",
            "-", "--", "!", "<!--<script>", "</script ", "<g>", "</g>", "<annotation-xml>", "<SCRIPT/>", "<br/>", "scr", "ipt"};
        String[] values = {"javascript:alert(1)", "\"", "'", ">", "<", "</script>", "-->", " x=y ", "&#58;", "js:alert(1)", "\\", "a", "",
            "é", ";", ":", "script:alert(1)", "java", "/ok", "</style>", "`${1}`", "\t", " js:1", "58;", "<!--", "<script>", "]]>", "/", "!", "-"};
        String[][] contexts = {{null, null}, {"script", null}, {"a", "href"}, {"div", "title"}, {"style", null}, {"div", "onclick"},
            {"p", null}, {"div", "hx-request"}, {"div", "style"}};
        var random = new java.util.Random(42);
        for (int n = 0; n < 3000; n++) {
            var parts = new java.util.ArrayList<Part>();
            var text = new StringBuilder();
            String[] context = contexts[random.nextInt(contexts.length)];
            for (int i = 3 + random.nextInt(22); i > 0; i--) {
                if (random.nextInt(8) > 0) {
                    text.append(fragments[random.nextInt(fragments.length)]);
                    continue;
                }
                if (text.length() > 0) parts.add(Part.text(text.toString()));
                text.setLength(0);
                parts.add(new Part(values[random.nextInt(values.length)], context[0] == null ? "html" : context[0], context[1]));
            }
            if (text.length() > 0) parts.add(Part.text(text.toString()));
            String whole = drive(parts, 0, false);
            for (int chunk : new int[] {0, 1, 2, 5}) {
                assertEquals(whole, drive(parts, chunk, false), () -> "String chunks: " + parts);
                assertEquals(whole, drive(parts, chunk, true), () -> "byte chunks: " + parts);
            }
        }
    }

    @Test
    void whereTheOutputIsDecidesNotWhereJteSaysItIs() {
        // JTE reports body text for a @template call's output, wherever the call was written.
        assertEquals("<a title=\"x&#34; onmouseover=&#34;alert(1)\">",
            driven(Part.text("<a title=\""), Part.data("html", null, "x\" onmouseover=\"alert(1)"), Part.text("\">")));
        assertEquals("<a title=\"t x&#34;y\">",
            driven(Part.text("<a title=\""), Part.data("a", "title", "t"), Part.text(" "),
                Part.data("html", null, "x\"y"), Part.text("\">")));
        assertEquals("<set to=\"" + BraceHtmlOutput.BLOCKED_URL + "\">",
            driven(Part.text("<set to=\""), Part.data("html", null, "javascript:x"), Part.text("\">")));
        assertEquals("<p to=\"javascript:x\">",   // "to" is a URL only on <set> and <animate>
            driven(Part.text("<p to=\""), Part.data("html", null, "javascript:x"), Part.text("\">")));
        assertEquals("<b onclick=\"go('\\u0027')\">",
            driven(Part.text("<b onclick=\"go('"), Part.data("html", null, "'"), Part.text("')\">")));
        // JTE and the output disagree about a script: inert as script, attribute and body text.
        assertEquals("<script>a('\\u0027\\u003c\\\\')</script>",
            driven(Part.text("<script>a('"), Part.data("html", null, "'<\\"), Part.text("')</script>")));
        assertEquals("<script>a('<b title=\"\\u0027\\u0022\">')</script>",
            driven(Part.text("<script>a('<b title=\""), Part.data("b", "title", "'\""), Part.text("\">')</script>")));
        assertEquals("<p>\\u0027\\u003c</p><a title=\"\\u0022\" href=\"\\u0022\">",
            driven(Part.text("<p>"), Part.data("script", null, "'<"), Part.text("</p><a title=\""),
                Part.data("script", null, "\""), Part.text("\" href=\""), Part.data("script", null, "\""), Part.text("\">")));
    }

    // --- H1 review (F1): a self-closing <script/> in SVG is a whole element ---
    //
    // The tokenizer took `<svg><script href="/a.js"/>` for the start of a script body that ran to
    // the next </script> anywhere on the page: no value was held, and data in a URL attribute got
    // script escaping with no scheme check.

    @Test
    void aSelfClosingScriptInSvgDoesntSwitchOffTheRules() {
        String blocked = BraceHtmlOutput.BLOCKED_URL;
        for (String x : List.of("javascript:alert(1)", "data:text/html,<script>alert(1)</script>", "JaVaScRiPt:alert(1)")) {
            String html = both("xssSvg", Map.of("x", x, "y", "alert(1)"));
            for (String id : List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7")) assertEquals(blocked, attr(html, id), id + " <- " + x);
            // Body text is body text again, not script-escaped.
            assertTrue(html.contains("<p id=\"p1\">" + text(x) + "</p>"), html);
            assertTrue(html.contains("</svg><p id=\"p2\">" + text(x) + "</p>"), html);
            assertTrue(html.contains("<text id=\"t3\">" + text(x) + "</text>"), html);
            assertTrue(html.contains("<p id=\"p4\">" + text(x) + "</p>"), html);
            assertTrue(html.contains("<p id=\"p6\">" + text(x) + "</p>"), html);
            assertTrue(html.contains(" title=\"" + attrEscaped(x) + "\">l</a>"), html);

            html = both("xssSvgLayout", Map.of("x", x, "y", "alert(1)"));
            assertEquals(blocked, attr(html, "a1"), x);
            assertEquals(blocked, attr(html, "a2"), x);
            assertTrue(html.contains("<p id=\"p1\">" + text(x) + "</p>"), html);
        }
        // The scheme in one piece and the code in the next: the value is held and judged whole.
        String html = both("xssSvg", Map.of("x", "javascript", "y", "alert(1)"));
        assertEquals(blocked, attr(html, "a2"));
        assertEquals(blocked, attr(html, "a5"));
        assertEquals(blocked, attr(both("xssSvgLayout", Map.of("x", "javascript", "y", "alert(1)")), "a2"));
        html = both("xssSvg", Map.of("x", "https", "y", "//example.com/a"));
        assertEquals("https://example.com/a", attr(html, "a2"));
        assertEquals("https://example.com/a", attr(html, "a5"));
        html = both("xssSvg", Map.of("x", "');alert(1)//", "y", ""));
        assertEquals("go('\\u0027);alert(1)//')", named(html, "a7", "onclick"));
        assertTrue(html.contains("<p id=\"p1\">');alert(1)//</p>"), html);
    }

    @Test
    void foreignContentIsFollowedAsTheBrowserReadsIt() {
        String blocked = BraceHtmlOutput.BLOCKED_URL;
        Part link = Part.data("a", "href", "javascript:alert(1)"), text = Part.data("p", null, "<i>'");
        // In SVG and MathML a self-closing <script/> or <style/> is complete: what follows is markup.
        for (String element : List.of("<svg><script href=\"/a.js\"/>", "<svg><script/>", "<svg><style/>", "<SVG><SCRIPT/>",
                "<math><script />", "<svg><g><script\n/></g>", "<svg><svg><script/></svg><script/>",
                "<svg><title>t</title><script/>", "<svg><foreignObject><b>x</b></foreignObject><script/>",
                "<svg><desc><svg><script/></svg></desc><script/>", "<svg><path d=\"M0 0\"><script/>",
                "<svg><![CDATA[ <script> \" ]] ]]><script/>", "<svg><script href=/a.js />")) {
            assertEquals(element + "</svg><p>&lt;i&gt;'</p><a href=\"" + blocked + "\">", driven(Part.text(element + "</svg><p>"),
                text, Part.text("</p><a href=\""), link, Part.text("\">")), element);
            assertEquals(element + "<a href=\"" + blocked + "\">&lt;i&gt;'", driven(Part.text(element + "<a href=\""), link,
                Part.text("\">"), text), element);
        }
        // In HTML content <script/> is not self-closing: the browser reads a script body up to
        // </script>, and so does this, inside SVG too once the content is HTML again.
        for (String element : List.of("<script src=\"/a.js\"/>", "<script/>", "<svg/><script/>", "<svg></svg><script/>",
                "<svg><foreignObject><script/>", "<svg><desc><script/>", "<svg><title><script/>", "<math><mi><script/>",
                "<math><annotation-xml encoding=\"text/html\"><script/>", "<svg><p><script/>", "<svg><g><div><script/>",
                "<svg><g></p><script/>", "<div><svg><g></div><script/>", "<svg><font color=\"red\"><script/>",
                "<svg><foreignObject><svg></svg><script/>", "<![CDATA[ x ]]><script/>", "<svg><script href=/a.js/>x</script></svg><script/>",
                // Not modelled, and read as HTML, the side that keeps every rule on. To a browser
                // these are still foreign content and the <script/> is complete: an end tag that
                // matches nothing open (it would close the SVG if it matched an element around
                // it), <font> without color/face/size, a MathML integration point in SVG or an
                // SVG one in MathML, <annotation-xml> without an HTML encoding.
                "<svg><g></div><script/>", "<svg><font><script/>", "<svg><mi><script/>", "<math><desc><script/>",
                "<math><annotation-xml><script/>")) {
            assertEquals(element + "<p>\\u003ci\\u003e\\u0027</p><a href=\"" + blocked + "\"></script><a href=\"" + blocked + "\">",
                driven(Part.text(element + "<p>"), text, Part.text("</p><a href=\""), link, Part.text("\"></script><a href=\""),
                    link, Part.text("\">")), element);
        }
        // A <script> with a body in SVG is markup to a browser: tags in it are tags.
        assertEquals("<svg><script>var t = '<a href=\"" + blocked + "\">';</script><a href=\"" + blocked + "\">",
            driven(Part.text("<svg><script>var t = '<a href=\""), link, Part.text("\">';</script><a href=\""), link, Part.text("\">")));
    }

    // --- H1 review (F2): scripts wrapped in <!-- --> ---
    //
    // After "<!--" and then "<script" in a script body, the browser is in the double-escaped
    // state, where the first "</script>" doesn't end the element. JTE ends the script there, and
    // so did the tokenizer: the two agreed on "body text" for what is still script.

    @Test
    void aCommentWrappedScriptEndsWhereTheBrowserEndsIt() {
        String html = both("xssScriptComment", Map.of("x", "');alert(1);//", "y", "\";alert(1);//"));
        String code = "\\u003balert\\u00281\\u0029\\u003b\\u002f\\u002f";
        assertTrue(html.contains("var b = \"\\u0022" + code + "\"; foo('\\u0027\\u0029" + code + "');\n//-->\n</script>"), html);
        assertTrue(html.contains("<p id=\"after1\">');alert(1);//</p><a id=\"l1\" href=\"&#39;);alert(1);//\">"), html);
        assertTrue(html.contains("<script id=\"w2\"><!--<script>var a=\"" + js("');alert(1);//")
            + "\";</script>var b=\"\\u0022" + code + "\";--></script><a id=\"l2\" href=\"&#39;);alert(1);//\">"), html);
        assertTrue(html.contains("<script id=\"w3\"><!-- var c = \"" + js("');alert(1);//") + "\"; --></script>"
            + "<p id=\"after3\">');alert(1);//</p>"), html);
        assertTrue(html.contains("<script id=\"w4\">var s = \"<!--\", d = \"" + js("');alert(1);//") + "\";</script>"
            + "<p id=\"after4\">');alert(1);//</p>"), html);

        String x = "</script><img src=x onerror=alert(1)>";
        html = both("xssScriptComment", Map.of("x", x, "y", "\\"));
        assertTrue(html.contains("var b = \"\\\\\"; foo('" + js(x) + "');"), html);
        assertEquals("\\u003c\\u002fscript\\u003e\\u003cimg\\u0020src\\u003dx\\u0020onerror\\u003dalert\\u00281\\u0029\\u003e", js(x));
        assertTrue(html.contains("<p id=\"after1\">" + text(x) + "</p>"), html);
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(both("xssScriptComment", Map.of("x", "javascript:alert(1)", "y", "")), "l1"));
        assertEquals(BraceHtmlOutput.BLOCKED_URL, attr(both("xssScriptComment", Map.of("x", "javascript:alert(1)", "y", "")), "l2"));
    }

    @Test
    void scriptDataEscapedStates() {
        String blocked = BraceHtmlOutput.BLOCKED_URL;
        Part link = Part.data("a", "href", "javascript:alert(1)"), text = Part.data("p", null, "<'");
        // Still in the script after this: body text from JTE's side is escaped as script.
        for (String script : List.of("<script><!--<script></script>", "<script><!--<SCRIPT\n></script >", "<script><!-- <script/></Script/",
                "<script><!--<script>x</script><script ></script\t>", "<script>\n<!--\ndocument.write(\"<script src='/x.js'></script>\");\n",
                "<script><!-- --!><script></script>", "<script><!--<script></script>-- >", "<script><!--<script><!--</script>",
                "<script><!--<script></script><script><!-- ", "<script><!--<script>-></script>")) {
            assertEquals(script + "\\u003c\\u0027--></script><a href=\"" + blocked + "\">", driven(Part.text(script), text,
                Part.text("--></script><a href=\""), link, Part.text("\">")), script);
        }
        // And out of it after this.
        for (String script : List.of("<script><!--<script></script></script>", "<script><!--<script>--></script>",
                "<script><!--></script>", "<script><!---></script>", "<script><!-- <scriptx></script>", "<script><!-- <scrip></script>",
                "<script>a = \"<script>\";</script>", "<script><!- -<script></script>", "<script><!--<script></scriptx></script></script>",
                "<script><!--<script></script>--></script>", "<script><!--<script>---></script>", "<script><!-- </script>",
                "<script><!--<script>--><!--</script>", "<style><!--<script></style>", "<script><!--<<script></script></script >",
                "<script><!--<script<script></script></script>", "<script><!--<script></script x=\"</script>\">")) {
            assertEquals(script + "&lt;'<a href=\"" + blocked + "\">", driven(Part.text(script), text,
                Part.text("<a href=\""), link, Part.text("\">")), script);
        }
    }

    // --- H1 review (F3): markup characters in script bodies ---
    //
    // A <script> inside <svg> is tokenized as markup, so `<img onerror=...>` in a JavaScript
    // string there is a tag, and <img> ends the SVG. JTE's script escaping leaves '<' alone.

    @Test
    void dataInAScriptBodyCantStartMarkup() throws Exception {
        for (String x : List.of("<img src=x onerror=alert(1)>", "</script><script>alert(1)</script>", "' onmouseover='alert(1) x='",
                "\" onmouseover=\"alert(1)", "\\", "a<b>&c", "]]><img src=x onerror=alert(1)>", "&lt;img src=x onerror=alert(1)&gt;",
                "&#39;;alert(1)//", "<!--<script>", "plain 1.5 (ok)")) {
            String html = both("xssSvgScript", Map.of("x", x));
            String body = js(x);
            // Nothing that is syntax to an HTML parser is left, in SVG (where the script is
            // markup) and in HTML (raw text) alike: no < > & or quotes, no space, "=" or "/".
            assertTrue(html.contains("<svg><script id=\"g1\">var a=\"" + body + "\";</script></svg><p id=\"p1\">" + text(x) + "</p>"), html);
            assertTrue(html.contains("<![CDATA[ if (a < b) { var c = '" + body + "'; } ]]></script><text id=\"t2\">" + text(x) + "</text>"), html);
            assertTrue(html.contains("<style id=\"g3\">a{color:" + BraceHtmlOutput.escapeCss(x) + "}</style>"), html);
            assertTrue(html.contains("<script id=\"h1\">var a = \"" + body + "\", t = `" + body + "`, r = /" + body + "/;</script>"), html);
            assertTrue(html.contains("<script id=\"j1\" type=\"application/json\">{\"a\":\"" + body + "\"}</script>"), html);
            assertFalse(body.matches("(?s).*[<>&\"' =/`$-].*"), body);
            assertTrue(html.contains("<p id=\"p2\">" + text(x) + "</p>"), html);
            assertFalse(html.contains("<img"), html);
            assertEquals(4, html.split("<script", -1).length - 1, html);
        }
        assertEquals("\\u003c\\u002fscript\\u003e\\u0026amp\\u003b", js("</script>&amp;"));
        // Valid JSON stays valid, and reads back as the data.
        String x = "a<b>&c \"q\" \\ / \n é";
        var m = Pattern.compile("type=\"application/json\">(.*?)</script>").matcher(both("xssSvgScript", Map.of("x", x)));
        assertTrue(m.find());
        assertEquals(x, Json.mapper().readTree(m.group(1)).get("a").asText());
    }

    @Test
    void scriptsInForeignContentAreMarkup() {
        // With a body (the '/' is part of the unquoted value): a script, escaped for markup too.
        assertEquals("<svg><script href=/a.js/>a('\\u0027\\u003c\\u0026')</script><p>'&lt;&amp;</p>",
            driven(Part.text("<svg><script href=/a.js/>a('"), Part.data("script", null, "'<&"), Part.text("')</script><p>"),
                Part.data("p", null, "'<&"), Part.text("</p>")));
        // "a<b" in it opens a tag, as it does in the browser: data in what is then an attribute
        // value can't close it.
        assertEquals("<svg><script>if (a<b) x = '\\u0027\\u0020onload\\u003d\\u0027alert\\u00281\\u0029'></script></svg>",
            driven(Part.text("<svg><script>if (a<b) x = '"), Part.data("script", null, "' onload='alert(1)"), Part.text("'></script></svg>")));
        // CDATA is text: tags in it aren't tags, and "]]>" is all that ends it.
        assertEquals("<svg><script><![CDATA[ a < b; s = '</script>'; t = \"\\u0022\\u003e\" ]]></script></svg><a href=\"" + BraceHtmlOutput.BLOCKED_URL + "\">",
            driven(Part.text("<svg><script><![CDATA[ a < b; s = '</script>'; t = \""), Part.data("script", null, "\">"),
                Part.text("\" ]]></script></svg><a href=\""), Part.data("a", "href", "javascript:alert(1)"), Part.text("\">")));
        // In HTML the same body is raw text, and the data is written the same way.
        assertEquals("<script>a('\\u0027\\u003c\\u0026\\u003e')</script>",
            driven(Part.text("<script>a('"), Part.data("script", null, "'<&>"), Part.text("')</script>")));
    }

    // --- H1 review (F5): data written while the tokenizer is in the middle of a match ---
    //
    // `</scr${x}ipt>` isn't an end tag unless x is empty, but the tokenizer, which doesn't read
    // data, carried "</scr" across it and matched "ipt>".

    @Test
    void dataInTheMiddleOfAMatchBreaksIt() {
        String y = "\";alert(1)//";
        String html = both("xssScriptSplit", Map.of("x", "z", "y", y));
        assertTrue(html.contains("<script id=\"s12\">var a = \"</scrzipt>\"; var b = \"" + js(y) + "\";</script><p id=\"p\">" + text(y) + "</p>"), html);
        // With nothing written the template's text is one end tag, to the browser as well: the
        // rest is body text that JTE takes for script, and is escaped for both.
        html = both("xssScriptSplit", Map.of("x", "", "y", y));
        assertTrue(html.contains("<script id=\"s12\">var a = \"</script>\"; var b = \"\\u0022\\u003balert\\u00281\\u0029\\u002f\\u002f\";</script><p id=\"p\">"
            + text(y) + "</p>"), html);

        Part quote = Part.data("script", null, "\""), text = Part.data("p", null, "'"), z = Part.data("script", null, "z");
        Part scheme = Part.data("a", "href", "javascript");
        String blocked = BraceHtmlOutput.BLOCKED_URL;
        // In a script body: "<", "</scr", "<!-", and the dashes of "-->".
        assertEquals("<script>a = \"<z/script>\"; b = \"\\u0022\";</script>",
            driven(Part.text("<script>a = \"<"), z, Part.text("/script>\"; b = \""), quote, Part.text("\";</script>")));
        assertEquals("<script>a = \"</scriptz>\"; b = \"\\u0022\";</script>",
            driven(Part.text("<script>a = \"</script"), z, Part.text(">\"; b = \""), quote, Part.text("\";</script>")));
        assertEquals("<script><!z--<script></script>'",
            driven(Part.text("<script><!"), z, Part.text("--<script></script>"), text));
        assertEquals("<script><!-- -z-><script></script>\\u0027--></script>'",
            driven(Part.text("<script><!-- -"), z, Part.text("-><script></script>"), text, Part.text("--></script>"), text));
        assertEquals("<script><!-- <scrzipt></script>'",
            driven(Part.text("<script><!-- <scr"), z, Part.text("ipt></script>"), text));
        // In a comment and in CDATA: what follows the data is still inside, so no value is held
        // and JTE's attribute is judged a piece at a time.
        assertEquals("<!-- a -'-> <a href=\"" + blocked + ":alert(1)\"> -->",
            driven(Part.text("<!-- a -"), text, Part.text("-> <a href=\""), scheme, Part.text(":alert(1)\"> -->")));
        assertEquals("<svg><![CDATA[ a ]']><a href=\"" + blocked + ":alert(1)\"> ]]><a href=\"" + blocked + "\">",
            driven(Part.text("<svg><![CDATA[ a ]"), text, Part.text("]><a href=\""), scheme, Part.text(":alert(1)\"> ]]><a href=\""),
                scheme, Part.text(":alert(1)\">")));
        // After a "<" in markup a letter would start a tag. Data there is written as escapes, all
        // of it (final review, M1), so it never does: to the browser and to the tokenizer the
        // "<" is text, and so is what follows it.
        Part breakout = Part.data("p", null, "\" onmouseover=\"alert(1)");
        assertEquals("<p><\\u0078a title=\"\" onmouseover=\"alert(1)\">",
            driven(Part.text("<p><"), Part.data("p", null, "x"), Part.text("a title=\""), breakout, Part.text("\">")));
        assertEquals("<p><\\u0031a title=\"\" onmouseover=\"alert(1)\">",
            driven(Part.text("<p><"), Part.data("p", null, "1"), Part.text("a title=\""), breakout, Part.text("\">")));
        assertEquals("<p><\\u002f\\u0078a title=\"\" onmouseover=\"alert(1)\"><a href=\"" + blocked + "\">",
            driven(Part.text("<p><"), Part.data("p", null, "/x"), Part.text("a title=\""), breakout, Part.text("\"><a href=\""),
                scheme, Part.text(":alert(1)\">")));
    }

    @Test
    void disagreementWithJteResolvesToTheStricterReading() {
        String blocked = BraceHtmlOutput.BLOCKED_URL;
        // JTE reports a URL attribute while the output's own reading is a script or style body,
        // a comment or body text: the URL rule applies anyway, to each piece on its own.
        for (String url : List.of("javascript:alert(1)", " JavaScript:alert(1)", "data:text/html,x", "javascript", "j", "42")) {
            for (String open : List.of("<script>", "<!-- ", "</a ")) {
                String html = driven(Part.text(open + "<a href=\""), Part.data("a", "href", url), Part.text(":alert(1)\">"));
                assertEquals(open + "<a href=\"" + blocked + ":alert(1)\">", html, open + " <- " + url);
            }
            assertEquals("<style><a href=\"about\\3a invalid#blocked:alert(1)\">",
                driven(Part.text("<style><a href=\""), Part.data("a", "href", url), Part.text(":alert(1)\">")), url);
        }
        // A piece that settles its own scheme, or is a path, is kept.
        assertEquals("<script><a href=\"/ok\"><a href=\"https://example.com/a?b=\\u0026c\"><img src=\"data:image/png;base64,AA==\">",
            driven(Part.text("<script><a href=\""), Part.data("a", "href", "/ok"), Part.text("\"><a href=\""),
                Part.data("a", "href", "https://example.com/a?b=&c"), Part.text("\"><img src=\""),
                Part.data("img", "src", "data:image/png;base64,AA=="), Part.text("\">")));
        assertEquals("<p><a href=\"/ok?a=&amp;b\">",
            driven(Part.text("<p><a href=\""), Part.data("a", "href", "/ok?a=&b"), Part.text("\">")));
        // The other attribute rules, with script escaping on top where a script is involved.
        assertEquals("<script><a onclick=\"go('\\u0027')\" title=\"\\u0022\\u003c\" style=\"a\\3b b\">",
            driven(Part.text("<script><a onclick=\"go('"), Part.data("a", "onclick", "'"), Part.text("')\" title=\""),
                Part.data("a", "title", "\"<"), Part.text("\" style=\""), Part.data("a", "style", "a;b"), Part.text("\">")));
        assertEquals("<style><a title=\"\\22 \\3c \" hx-vals=''>",
            driven(Part.text("<style><a title=\""), Part.data("a", "title", "\"<"), Part.text("\" hx-vals='"),
                Part.data("a", "hx-vals", " js:alert(1)"), Part.text("'>")));
        // The output is in one attribute's value and JTE reports another: both rules.
        assertEquals("<a title=\"" + blocked + "\" data-x=\"\\u0027\">",
            driven(Part.text("<a title=\""), Part.data("a", "href", "javascript:alert(1)"), Part.text("\" data-x=\""),
                Part.data("a", "onclick", "'"), Part.text("\">")));
        // In a held value too: JTE says style, or a script attribute, where the output is in href.
        assertEquals("<a href=\"/a\\3b b\"><a href=\"/x?\\u0027\"><a href=\"javascript\\3a alert\\28 1\\29 \">",
            driven(Part.text("<a href=\"/"), Part.data("style", null, "a;b"), Part.text("\"><a href=\"/x?"),
                Part.data("a", "onclick", "'"), Part.text("\"><a href=\""), Part.data("style", null, "javascript:alert(1)"), Part.text("\">")));
        // JTE says script or style where the output is in an attribute value or a style body.
        assertEquals("<a title=\"\\u0027\\u003c\"><i title='a\\3b b'><style>a{color:\\5c u0027\\5c u003b}",
            driven(Part.text("<a title=\""), Part.data("script", null, "'<"), Part.text("\"><i title='"),
                Part.data("style", null, "a;b"), Part.text("'><style>a{color:"), Part.data("script", null, "';"), Part.text("}")));
    }

    // --- Final review (M1): data inside a tag, outside a quoted value ---
    //
    // `<svg><script>var n = 0<i ? "${x}" : "";` : to a browser "<i" opens a tag and the string,
    // which doesn't follow "=", is in attribute-name position. The tokenizer knew, and the data
    // still got script escaping only, which leaves space, "=" and "/" alone.

    @Test
    void dataInsideATagOutsideAQuotedValueIsEscapedWhole() {
        String x = "x on=1/>";
        String all = "\\u0078\\u0020\\u006f\\u006e\\u003d\\u0031\\u002f\\u003e";
        // Every state inside a tag, and straight after "<" and "</", whatever JTE reports.
        for (String open : List.of("<svg><script>a<b", "<svg><script>a<b ", "<svg><script>a<b c", "<svg><script>a<b c ",
                "<svg><script>a<b c=", "<svg><script>a<b c=d", "<svg><script>a<b c='d' ", "<svg><script>a<b c='d'", "<svg><script>a<",
                "<svg><script>a</", "<svg><script>a</b ", "<p><b ", "<b/", "<b c = ", "<svg><script>var s = \"", "<svg><script>if (a<b && ok) { log(\"")) {
            boolean inTag = !open.endsWith("s = \"");
            for (String[] context : new String[][] {{"script", null}, {"html", null}, {"b", "title"}, {"b", "onclick"}}) {
                if (!inTag && context[1] != null) continue;
                String expected = inTag ? all : "x\\u0020on\\u003d1\\u002f\\u003e";
                assertEquals(open + expected + " y>", driven(Part.text(open), Part.data(context[0], context[1], x), Part.text(" y>")),
                    open + " in " + String.join(" ", context[0], String.valueOf(context[1])));
            }
            if (!inTag) continue;
            // JTE's attribute keeps its own rule first: a URL piece that doesn't settle its scheme is blocked.
            assertEquals(open + BraceHtmlOutput.escapeAll(BraceHtmlOutput.BLOCKED_URL) + ">",
                driven(Part.text(open), Part.data("a", "href", "javascript"), Part.text(">")), open);
            // CSS reads a hex escape of every character back as the value (where a script is
            // the other reading, it gets both escapings).
            String css = open.contains("<script>") ? BraceHtmlOutput.escapeCss(all.substring(0, 6)) + BraceHtmlOutput.escapeCss("\\u003b")
                : "\\78 \\3b ";
            assertEquals(open + css + ">", driven(Part.text(open), Part.data("style", null, "x;"), Part.text(">")), open);
        }
        // Data can't start a tag, name one, or end one.
        assertEquals("<svg><script>var t = \"<\\u0069\\u006d\\u0067 src=x>\";</script></svg><p>&lt;</p>",
            driven(Part.text("<svg><script>var t = \"<"), Part.data("script", null, "img"), Part.text(" src=x>\";</script></svg><p>"),
                Part.data("p", null, "<"), Part.text("</p>")));
        // In a quoted value it is a value, escaped as one: nothing changes there.
        assertEquals("<svg><script>if (a<b) t = \"x\\u0020on\\u003d1\\u002f\\u003e\";</script></svg>",
            driven(Part.text("<svg><script>if (a<b) t = \""), Part.data("script", null, x), Part.text("\";</script></svg>")));
        assertEquals("<a title=\"x on=1/>\" class='x on=1/>'>",
            driven(Part.text("<a title=\""), Part.data("a", "title", x), Part.text("\" class='"), Part.data("a", "class", x), Part.text("'>")));
        // And HTML's own script is raw text: no tag in it.
        assertEquals("<script>var n = 0<i ? \"" + js(x) + "\" : \"\";</script>",
            driven(Part.text("<script>var n = 0<i ? \""), Part.data("script", null, x), Part.text("\" : \"\";</script>")));
        assertEquals("\\u0061\\u005c\\u00e9\\ud83d\\ude00", BraceHtmlOutput.escapeAll("a\\é😀"));
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

    /** JTE's escaping for body text. */
    private static String text(String value) {
        var out = new gg.jte.output.StringOutput();
        gg.jte.html.escape.Escape.htmlContent(value, out);
        return out.toString();
    }

    /** The escaping for data in a script body (final review: no longer JTE's, with its {@code \'} and {@code \"}). */
    private static String js(String value) {
        return BraceHtmlOutput.escapeScriptData(value);
    }

    // --- Final review (L1, L3): script-body data is escaped whole ---
    //
    // JTE's script escaping writes \' \- \` \$ (not JSON: a JSON data block with "O'Brien" in it
    // failed to parse) and \" (whose quote still ends an attribute value if the browser is
    // reading the script as markup and the tokenizer is not).

    static final List<String> NASTY = List.of("O'Brien", "a-b `c` $d", "${alert(1)}", "`${x}`", "</script><script>alert(1)</script>",
        "<!--<script>", "-->", "]]>", "\" style=x onload=alert(1) x=\"", "' onload='alert(1)", "x onload=alert(1) y", "a/b", "/;alert(1);/",
        "\\", "\\\\", "\\u0041", "\\\"", "a\\", "tab\tx\ny\r\n", "\u0000\u001f\u007f\u0085", "line" + (char) 0x2028 + "sep" + (char) 0x2029, "é ñ 日本語 😀", "a_b,c",
        "\ud83d", "\ude00x", "1.5", "-3", "&#39;&lt;&amp;", "[a-z]+(b|c)*?{1,2}^$.", "{\"k\": [1, 2]}", "", "plain", "a = b", "a=b");

    @Test
    void scriptDataIsWrittenInASafeAlphabet() throws Exception {
        var safe = Pattern.compile("(?:[A-Za-z0-9_,]|[^\\x00-\\x9f\\u2028\\u2029]|\\\\\\\\|\\\\u[0-9a-f]{4})*");
        var mapper = Json.mapper();
        for (String x : NASTY) {
            String e = BraceHtmlOutput.escapeScriptData(x);
            assertTrue(safe.matcher(e).matches(), x + " -> " + e);
            assertEquals(x, BraceHtmlOutput.unescapeScript(e), e);
            // A JSON string reads it back (and so does a JavaScript string: the escapes are the same).
            assertEquals(x, mapper.readTree("{\"v\":\"" + e + "\"}").get("v").asText(), e);
            if (!x.isEmpty() && x.length() < 3 && Character.isSurrogate(x.charAt(0))) continue; // half a pair: the page's body text can't carry it as UTF-8
            // The same through a template, in HTML and in SVG, in both static-content modes.
            String html = both("xssSvgScript", Map.of("x", x));
            assertTrue(html.contains("<svg><script id=\"g1\">var a=\"" + e + "\";</script></svg>"), html);
            assertTrue(html.contains("<script id=\"h1\">var a = \"" + e + "\", t = `" + e + "`, r = /" + e + "/;</script>"), html);
            var m = Pattern.compile("type=\"application/json\">(.*?)</script>").matcher(html);
            assertTrue(m.find(), html);
            assertEquals(x, mapper.readTree(m.group(1)).get("a").asText(), html);
        }
        assertEquals("O\\u0027Brien", BraceHtmlOutput.escapeScriptData("O'Brien"));
        assertEquals("a\\u002db\\u0020\\u0060c\\u0060\\u0020\\u0024d", BraceHtmlOutput.escapeScriptData("a-b `c` $d"));
        assertEquals("\\\\\\u0022", BraceHtmlOutput.escapeScriptData("\\\""));
        assertEquals("é_,日本語😀", BraceHtmlOutput.escapeScriptData("é_,日本語😀"));
        assertEquals("\\ud83d\\u0020\\ude00", BraceHtmlOutput.escapeScriptData("\ud83d \ude00"));
        // Script attributes keep their own, lighter escaping: hx-trigger="${spec}" still reads "click delay:1s".
        assertEquals("click delay:1s", BraceHtmlOutput.escapeScript("click delay:1s"));
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
