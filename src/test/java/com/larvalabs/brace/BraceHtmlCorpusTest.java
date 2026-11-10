package com.larvalabs.brace;

import gg.jte.ContentType;
import gg.jte.output.StringOutput;
import gg.jte.output.Utf8ByteOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renders the templates in {@code src/test/resources/xss-corpus.txt} through {@link BraceHtmlOutput}
 * with each of their values, and checks the output against the expected text or the forbidden
 * patterns given there. The corpus is the probes of the independent review of the H1/L7 fixes
 * (2026-11 security review): its findings, and the cases it cleared. The file's header describes
 * the format.
 */
class BraceHtmlCorpusTest {

    /** Used for a template that lists no values of its own. */
    static final List<String> DEFAULT_VALUES = List.of(
        "javascript:alert(1)", "\"><img src=x onerror=alert(1)>", "' onmouseover='alert(1)", "x onmouseover=alert(1) y", "\\",
        "</script><script>alert(1)</script>", "');alert(1);//", "red;}*{background:url(//evil)}</style><script>alert(1)</script>",
        "&#106;avascript:alert(1)", "js:alert(1)", "https://example.com/a?b=1&c=2", "/ok", " java\tscript:alert(1)",
        "data:text/html,<script>alert(1)</script>", "café     😀");

    record Probe(String name, String template, List<String[]> values, List<String> expect, List<String> forbid, String error) {}

    @TempDir static Path work;
    static List<Probe> probes;
    static gg.jte.TemplateEngine strings, bytes;

    @BeforeAll
    static void compile() throws Exception {
        probes = parse(Files.readAllLines(Path.of("src/test/resources/xss-corpus.txt")));
        Path views = Files.createDirectories(work.resolve("views"));
        for (Probe p : probes) {
            String source = p.name().startsWith("_") ? p.template() : "@param String x\n@param String y\n" + p.template();
            Files.writeString(views.resolve(p.name() + ".jte"), source);
        }
        strings = gg.jte.TemplateEngine.create(new DirectoryCodeResolver(views), work.resolve("strings"), ContentType.Html);
        bytes = gg.jte.TemplateEngine.create(new DirectoryCodeResolver(views), work.resolve("bytes"), ContentType.Html);
        bytes.setBinaryStaticContent(true);
    }

    @TestFactory
    List<DynamicTest> corpus() {
        var tests = new ArrayList<DynamicTest>();
        for (Probe p : probes) {
            if (!p.name().startsWith("_")) tests.add(DynamicTest.dynamicTest(p.name(), () -> check(p)));
        }
        assertTrue(tests.size() >= 20, "the corpus was read: " + tests.size());
        return tests;
    }

    private static void check(Probe p) {
        if (p.error() != null) {
            var e = assertThrows(gg.jte.TemplateException.class, () -> render(strings, p, "a", "b", false));
            assertTrue(e.getMessage().contains(p.error()), e.getMessage());
            return;
        }
        assertTrue(p.expect().isEmpty() || p.expect().size() == p.values().size(), "one expected output per value");
        for (int i = 0; i < p.values().size(); i++) {
            String x = p.values().get(i)[0], y = p.values().get(i)[1];
            String label = p.name() + " <- " + x + " || " + y;
            String html = render(strings, p, x, y, false);
            assertEquals(html, render(bytes, p, x, y, true), label + ": String and binary static content differ");
            if (!p.expect().isEmpty() && !p.expect().get(i).equals("*")) assertEquals(p.expect().get(i), html, label);
            for (String pattern : p.forbid()) {
                assertFalse(Pattern.compile(pattern).matcher(html).find(), label + ": found /" + pattern + "/ in " + html);
            }
        }
    }

    private static String render(gg.jte.TemplateEngine engine, Probe p, String x, String y, boolean binary) {
        var params = new HashMap<String, Object>(Map.of("x", x, "y", y));
        if (binary) {
            var out = new Utf8ByteOutput();
            var html = new BraceHtmlOutput(out);
            engine.render(p.name() + ".jte", params, html);
            html.finish();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        var out = new StringOutput();
        var html = new BraceHtmlOutput(out);
        engine.render(p.name() + ".jte", params, html);
        html.finish();
        return out.toString();
    }

    // --- the file ---

    private static List<Probe> parse(List<String> lines) {
        var probes = new ArrayList<Probe>();
        String name = null, section = null, error = null;
        var template = new ArrayList<String>();
        var values = new ArrayList<String[]>();
        var expect = new ArrayList<String>();
        var forbid = new ArrayList<String>();
        for (String line : lines) {
            if (line.startsWith("#!")) continue;
            if (line.startsWith("### ")) {
                if (name != null) probes.add(probe(name, template, values, expect, forbid, error));
                name = line.substring(4).trim();
                section = "template";
                error = null;
                template = new ArrayList<>();
                values = new ArrayList<>();
                expect = new ArrayList<>();
                forbid = new ArrayList<>();
            } else if (name == null) {
                continue;
            } else if (line.startsWith("--- ")) {
                section = line.substring(4).trim();
                assertTrue(List.of("values", "expect", "forbid", "error").contains(section), line);
            } else if (section.equals("template")) {
                template.add(line);
            } else if (line.isEmpty()) {
                continue;
            } else if (section.equals("values")) {
                String[] parts = line.split("\\|\\|", -1);
                values.add(new String[] {unescape(parts[0]), parts.length > 1 ? unescape(parts[1]) : "Y"});
            } else if (section.equals("expect")) {
                expect.add(line.replace("{{NL}}", "\n"));
            } else if (section.equals("forbid")) {
                forbid.add(line);
            } else {
                error = line;
            }
        }
        if (name != null) probes.add(probe(name, template, values, expect, forbid, error));
        return probes;
    }

    private static Probe probe(String name, List<String> template, List<String[]> values, List<String> expect,
                               List<String> forbid, String error) {
        while (!template.isEmpty() && template.get(template.size() - 1).isEmpty()) template.remove(template.size() - 1);
        if (values.isEmpty()) {
            for (String value : DEFAULT_VALUES) values.add(new String[] {value, value});
        }
        return new Probe(name, String.join("\n", template), values, expect, forbid, error);
    }

    private static String unescape(String s) {
        var out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                out.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '\\' -> out.append('\\');
                case 'u' -> {
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }
}
