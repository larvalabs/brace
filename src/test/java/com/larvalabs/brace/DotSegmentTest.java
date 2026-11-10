package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L9: a literal {@code .} or {@code ..} path segment never reaches a handler, as a route match or
 * as a path-parameter value. Raw socket, so no client normalizes the dot segments away first.
 */
class DotSegmentTest {

    static Brace app;
    static final CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setup() throws Exception {
        app = Brace.app().port(0);
        app.get("/items/{a}/{b}", req -> {
            calls.add(req.pathParam("a") + "|" + req.pathParam("b"));
            return Result.text("items");
        });
        app.get("/one", req -> {
            calls.add("one");
            return Result.text("one");
        });
        app.get("/.well-known/{x}", req -> {
            calls.add("wk:" + req.pathParam("x"));
            return Result.text("wk");
        });
        app.start();
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    @BeforeEach
    void clear() {
        calls.clear();
    }

    private static int status(String path) throws Exception {
        try (Socket socket = new Socket("localhost", app.actualPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
            out.flush();
            var buf = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buf);
            return Integer.parseInt(buf.toString(StandardCharsets.UTF_8).substring(9, 12));
        }
    }

    @Test
    void dotSegmentsAreNotFoundAndNeverReachAHandler() throws Exception {
        assertEquals(404, status("/items/../y"));
        assertEquals(404, status("/items/./y"));
        assertEquals(404, status("/items/x/.."));
        assertEquals(404, status("/one/.."));
        assertEquals(404, status("/one/."));
        assertEquals(404, status("/./one"));
        // Climbing above the root: Jetty answers 400 itself, before Brace sees the request.
        assertTrue(Set.of(400, 404).contains(status("/../one")));
        assertEquals(404, status("/one/../one"));
        assertEquals(List.of(), calls, "no handler may run for a dot segment");
    }

    @Test
    void encodedDotSegmentsNeverArriveAsDotParameters() throws Exception {
        // Whatever the status (Jetty may refuse these itself), a handler must not see a ".." value.
        for (String p : new String[]{"/items/%2e%2e/y", "/items/%2E%2E/y", "/items/%2e/y", "/items/.%2e/y"}) {
            assertTrue(Set.of(400, 404).contains(status(p)), p);
        }
        for (String call : calls) {
            assertFalse(call.startsWith("..|") || call.startsWith(".|"), "handler saw a dot segment: " + call);
        }
    }

    @Test
    void ordinaryDottedSegmentsAreUnchanged() throws Exception {
        assertEquals(200, status("/items/..foo/y"));
        assertEquals(200, status("/items/a..b/y"));
        assertEquals(200, status("/items/foo../y"));
        assertEquals(200, status("/.well-known/x"));
        assertEquals(java.util.List.of("..foo|y", "a..b|y", "foo..|y", "wk:x"), calls);
    }
}
