package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code --json} output is read by programs and by people with a terminal (L11 final review):
 * Jackson escapes only U+0000 to U+001F, so C1 controls (U+009B is an 8-bit CSI), bidi overrides,
 * line separators and zero-width characters used to come out raw, in values and in key names.
 */
class CliJsonEscapingTest {

    /** Control, format, separator and surrogate characters, plus ordinary text that must stay readable. */
    private static final String READABLE = "héllo 日本語 😀";
    private static final String NASTY = READABLE
        + " csi\u009b nel\u0085 del\u007f rlo‮ ls  ps  zwsp​ bom﻿ shy­"
        + " tag󠀁 lone\uD800 end";

    @TempDir static Path projectDir;
    static HttpServer server;
    static final Map<String, String> bodies = new ConcurrentHashMap<>();

    /** The nasty string and key as JSON text (lone surrogate and all, as escapes). */
    private static String jsonText(String s) {
        var sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c == '"' || c == '\\' || Character.isSurrogate(c) && !isHalfOfPair(s, c)) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static boolean isHalfOfPair(String s, char c) {
        int i = s.indexOf(c);
        return Character.isHighSurrogate(c)
            ? i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))
            : i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    private static final String VAL = jsonText(NASTY);
    private static final String KEY = jsonText("key" + NASTY);

    @BeforeAll
    static void start() throws Exception {
        var kp = OpsKeys.generateKeypair();
        Files.writeString(projectDir.resolve("ops-private.key"), kp.privateKey() + "\n" + kp.publicKey() + "\n");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.equals("/ops/auth")
                ? "{\"token\":\"tok\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}"
                : bodies.getOrDefault(path, "{}");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Files.writeString(projectDir.resolve(".brace"),
            "ops.local.url=http://127.0.0.1:" + server.getAddress().getPort() + "\n");
        Files.writeString(projectDir.resolve(".brace.local"),
            "ops.key=" + projectDir.resolve("ops-private.key") + "\n");
    }

    @AfterAll
    static void stop() throws Exception {
        CliAuth.clearCache(projectDir);
        server.stop(0);
    }

    @BeforeEach
    void reset() { bodies.clear(); }

    // ---------- the serializer ----------

    private static void assertSafe(String out) {
        for (int i = 0; i < out.length(); ) {
            int cp = out.codePointAt(i);
            int type = Character.getType(cp);
            boolean bad = (cp < 0x20 && cp != '\n') || (cp >= 0x7f && cp <= 0x9f)
                || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE;
            assertFalse(bad, "raw U+" + Integer.toHexString(cp) + " at " + i + " in: " + out);
            i += Character.charCount(cp);
        }
    }

    @Test
    void jsonEscapesControlFormatSeparatorAndSurrogateCharactersInValuesAndKeys() throws Exception {
        String[] table = {
            "\u009b", "\u0085", "\u007f", "\u0080", "\u009f", "‮", "‪", "⁦", " ", " ",
            "​", "‍", "‏", "﻿", "­", "؀", "󠀁", "󠁿",
            "\uD800", "\uDC00", "a\uD800b", NASTY, "\u0000", "\u001b]0;x\u0007",
        };
        for (String s : table) {
            var obj = Json.mapper().createObjectNode();
            obj.put(s + "k", s);
            obj.putArray("list").add(s);
            String out = CliOutput.json(obj);
            assertSafe(out);
            assertEquals(1, out.lines().count(), out);
            assertEquals(obj, Json.mapper().readTree(out), "round trip of U+" + Integer.toHexString(s.codePointAt(0)));
        }
    }

    @Test
    void supplementaryFormatCharactersBecomeSurrogatePairEscapes() {
        String out = CliOutput.json(Map.of("t", "󠀁"));
        assertEquals("{\"t\":\"\\udb40\\udc01\"}", out);
    }

    @Test
    void ordinaryTextStaysReadable() throws Exception {
        String out = CliOutput.json(Map.of("t", READABLE));
        assertTrue(out.contains(READABLE), out);
    }

    // ---------- every --json command ----------

    private record Run(int code, String out, String err) {}

    @FunctionalInterface
    private interface CliCall { int call() throws Exception; }

    private static Run run(CliCall cmd) throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = cmd.call();
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String object() {
        return "{" + KEY + ":" + VAL + ",\"id\":7,\"message\":" + VAL + ",\"uptime\":" + VAL + "}";
    }

    private void assertPassThrough(String path, String body, CliCall cmd) throws Exception {
        bodies.put(path, body);
        var r = run(cmd);
        assertSafe(r.out());
        assertSafe(r.err());
        JsonNode expected = Json.mapper().readTree(body);
        String text = r.out().strip();
        JsonNode actual = Json.mapper().readTree(text);
        assertEquals(expected, actual, "round trip through " + path + "\n" + text);
        assertTrue(text.contains(READABLE), "ordinary text readable: " + text);
    }

    @Test
    void statusJson() throws Exception {
        assertPassThrough("/ops/status", "{\"app\":" + object() + ",\"errors\":{\"count\":0}}",
            () -> CliCommands.status(projectDir, new String[]{"--json"}));
    }

    @Test
    void errorsListJson() throws Exception {
        assertPassThrough("/ops/errors", "[" + object() + "]",
            () -> CliCommands.errors(projectDir, new String[]{"--json"}));
    }

    @Test
    void errorDetailJson() throws Exception {
        assertPassThrough("/ops/errors/7", object(),
            () -> CliCommands.errors(projectDir, new String[]{"7", "--json"}));
    }

    @Test
    void logsJson() throws Exception {
        bodies.put("/ops/logs", "[" + object() + "," + object() + "]");
        var r = run(() -> CliCommands.logs(projectDir, new String[]{"--json"}));
        assertSafe(r.out());
        var lines = r.out().strip().lines().toList();
        assertEquals(2, lines.size(), r.out());
        for (var l : lines) assertEquals(Json.mapper().readTree(object()), Json.mapper().readTree(l));
    }

    @Test
    void cacheJson() throws Exception {
        assertPassThrough("/ops/cache", object(), () -> CliCommands.cache(projectDir, new String[]{"--json"}));
    }

    @Test
    void cacheClearJson() throws Exception {
        assertPassThrough("/ops/cache/clear", object(), () -> CliCommands.cacheClear(projectDir, new String[]{"--json"}));
    }

    @Test
    void resolveJson() throws Exception {
        assertPassThrough("/ops/errors/7/resolve", object(),
            () -> CliCommands.resolve(projectDir, new String[]{"7", "--json"}));
    }

    @Test
    void analyticsJson() throws Exception {
        assertPassThrough("/ops/analytics/data", object(),
            () -> CliCommands.analytics(projectDir, new String[]{"--json"}));
    }

    @Test
    void checkJson() throws Exception {
        bodies.put("/ops/status", "{\"app\":{\"uptime\":" + VAL + ",\"javaVersion\":" + VAL + "},\"http\":{}}");
        bodies.put("/ops/errors", "[{\"errorType\":" + VAL + ",\"route\":" + VAL + ",\"occurrenceCount\":1}]");
        bodies.put("/ops/logs", "[]");
        var r = run(() -> CliCheck.run(projectDir, new String[]{"--json"}));
        assertSafe(r.out());
        JsonNode parsed = Json.mapper().readTree(r.out().strip());
        assertTrue(parsed.toString().contains("csi"), parsed.toString());
    }
}
