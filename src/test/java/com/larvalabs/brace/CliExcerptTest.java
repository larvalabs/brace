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
import static org.junit.jupiter.api.Assertions.*;

/** Server-derived text in an error message is cut to about 300 characters (L11 final review). */
class CliExcerptTest {

    @Test
    void shortTextIsUnchangedAndNullIsEmpty() {
        assertEquals("not found", CliOutput.excerpt("not found"));
        assertEquals("", CliOutput.excerpt(null));
        assertEquals("x".repeat(300), CliOutput.excerpt("x".repeat(300)));
    }

    @Test
    void longTextIsCutWithAVisibleMarker() {
        String out = CliOutput.excerpt("x".repeat(10_000));
        assertEquals("x".repeat(300) + "… (9700 more characters)", out);
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        // 299 BMP characters then an emoji straddling the limit in UTF-16 units
        String s = "a".repeat(299) + "😀" + "b".repeat(50);
        String out = CliOutput.excerpt(s);
        assertTrue(out.startsWith("a".repeat(299) + "😀…"), out);
        assertFalse(out.contains("\uD83D…"), out);
        assertTrue(out.endsWith("(50 more characters)"), out);
    }

    @Test
    void authFailureMessageIsBounded() {
        var e = new CliAuth.OpsAuthFailure(401, "y".repeat(2_000_000));
        assertTrue(e.getMessage().length() < 500, "" + e.getMessage().length());
        assertTrue(e.getMessage().contains("more characters"), e.getMessage());
    }

    @Test
    void aTenMegabyteNonJsonReplyPrintsABoundedLine(@TempDir Path projectDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Files.writeString(projectDir.resolve("ops-private.key"), kp.privateKey() + "\n" + kp.publicKey() + "\n");
        byte[] big = "z".repeat(10 * 1024 * 1024).getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean auth = exchange.getRequestURI().getPath().equals("/ops/auth");
            byte[] body = auth
                ? ("{\"token\":\"tok\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}").getBytes(StandardCharsets.UTF_8)
                : big;
            exchange.sendResponseHeaders(auth ? 200 : 500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Files.writeString(projectDir.resolve(".brace"), "ops.local.url=http://127.0.0.1:" + server.getAddress().getPort() + "\n");
            Files.writeString(projectDir.resolve(".brace.local"), "ops.key=" + projectDir.resolve("ops-private.key") + "\n");
            for (String[] cmd : new String[][]{{"errors"}, {"cache-clear"}, {"resolve"}, {"errors-detail"}, {"logs"}}) {
                var err = new ByteArrayOutputStream();
                var prev = System.err;
                System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                int code;
                try {
                    code = switch (cmd[0]) {
                        case "errors" -> CliCommands.errors(projectDir, new String[]{"--json"});
                        case "cache-clear" -> CliCommands.cacheClear(projectDir, new String[]{"--json"});
                        case "resolve" -> CliCommands.resolve(projectDir, new String[]{"7", "--json"});
                        case "errors-detail" -> CliCommands.errors(projectDir, new String[]{"7", "--json"});
                        default -> CliCommands.logs(projectDir, new String[]{"--json"});
                    };
                } finally {
                    System.setErr(prev);
                    CliAuth.clearCache(projectDir);
                }
                String text = err.toString(StandardCharsets.UTF_8);
                if (!cmd[0].equals("logs")) assertNotEquals(0, code, cmd[0]);   // `logs` has always exited 0 after printing the error
                assertTrue(text.length() < 1000, cmd[0] + ": " + text.length());
                assertEquals(1, text.strip().lines().count(), cmd[0] + ": " + text);
                assertTrue(text.contains("more characters"), cmd[0] + ": " + text);
            }
        } finally {
            server.stop(0);
        }
    }
}
