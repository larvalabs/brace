package com.larvalabs.brace;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketTest {

    static Brace app;
    static int port;

    // Track onClose calls
    static final CopyOnWriteArrayList<String> closeEvents = new CopyOnWriteArrayList<>();

    // --- User handler classes (must be public for reflection) ---

    public static class EchoSocket {
        private final WsContext ws;
        public EchoSocket(WsContext ws) { this.ws = ws; }
        public void onMessage(String message) {
            ws.send("echo:" + message);
        }
        public void onClose(int code, String reason) {
            closeEvents.add(code + ":" + reason);
        }
    }

    public static class RoomSocket {
        // connect() returns when the *client* handshake completes; onConnect (and the join it
        // performs) runs server-side afterwards. Without waiting for the join, a broadcast sent
        // straight after connect can reach an empty room — a race that shows up as a null poll
        // under suite load, not a product bug.
        static final java.util.concurrent.atomic.AtomicInteger joined =
            new java.util.concurrent.atomic.AtomicInteger();
        private final WsContext ws;
        public RoomSocket(WsContext ws) { this.ws = ws; }
        public void onConnect() {
            ws.join("lobby");
            joined.incrementAndGet();
        }
        public void onMessage(String message) {
            ws.broadcast("lobby", "broadcast:" + message);
        }
        public void onClose(int code, String reason) {
            ws.leave("lobby");
        }
    }

    public static class BlastSocket {
        private final WsContext ws;
        public BlastSocket(WsContext ws) { this.ws = ws; }
        public void onConnect() {
            // Fire a large volume of frames at the just-connected client. Against a socket that isn't
            // reading, these back up past the test app's small wsMaxQueuedBytes cap and the connection
            // self-closes; each send short-circuits once that has happened, so this loop stops early.
            var big = "x".repeat(16 * 1024);
            for (int i = 0; i < 8192; i++) {
                ws.send(big);
            }
        }
    }

    public static class SessionSocket {
        private final WsContext ws;
        public SessionSocket(WsContext ws) { this.ws = ws; }
        public void onConnect() {
            var session = ws.session();
            if (session != null && session.has("userId")) {
                ws.send("user:" + session.get("userId"));
            } else {
                ws.send("user:anonymous");
            }
        }
    }

    // --- Java built-in WebSocket listener ---

    static class WsTestClient implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                messages.add(textBuffer.toString());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(null);
            return null;
        }
    }

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0)
            .sessions("test-secret-key-at-least-32-characters-long")
            // Small cap so the slow-consumer test trips backpressure quickly. Other tests send tiny
            // messages that clients read promptly, so their queued backlog never approaches this.
            .wsMaxQueuedBytes(64 * 1024)
            .ws("/echo", EchoSocket::new)
            .ws("/room", RoomSocket::new)
            .ws("/blast", BlastSocket::new)
            .ws("/session", SessionSocket::new);

        // Also add a regular HTTP route to verify coexistence
        app.get("/hello", req -> Result.text("hello"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.stop();
    }

    @BeforeEach
    void resetState() {
        closeEvents.clear();
        // Room membership is now per-instance registry state, cleaned up when connections close;
        // no global map to reset.
    }

    private WebSocket connect(String path, WsTestClient listener) throws Exception {
        return HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + port + path), listener)
            .get(5, TimeUnit.SECONDS);
    }

    private WebSocket connectWithCookie(String path, String cookieHeader, WsTestClient listener) throws Exception {
        return HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .header("Cookie", cookieHeader)
            .buildAsync(URI.create("ws://localhost:" + port + path), listener)
            .get(5, TimeUnit.SECONDS);
    }

    @Test
    void echoMessage() throws Exception {
        var listener = new WsTestClient();
        var ws = connect("/echo", listener);

        ws.sendText("hello", true);
        String response = listener.messages.poll(5, TimeUnit.SECONDS);
        assertEquals("echo:hello", response);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        listener.closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void onCloseIsCalled() throws Exception {
        var listener = new WsTestClient();
        var ws = connect("/echo", listener);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        listener.closed.get(5, TimeUnit.SECONDS);

        // Give a moment for the server-side onClose to fire
        Thread.sleep(200);
        assertFalse(closeEvents.isEmpty(), "onClose should have been called");
    }

    @Test
    void roomBroadcast() throws Exception {
        var listener1 = new WsTestClient();
        var listener2 = new WsTestClient();

        RoomSocket.joined.set(0);
        var ws1 = connect("/room", listener1);
        var ws2 = connect("/room", listener2);
        // Both connections must have completed their server-side join before the broadcast, or
        // it fans out to a room that isn't fully populated yet.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (RoomSocket.joined.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(2, RoomSocket.joined.get(), "both sockets should have joined the room");

        // ws1 sends a message — both should receive the broadcast
        ws1.sendText("hi", true);

        String msg1 = listener1.messages.poll(5, TimeUnit.SECONDS);
        String msg2 = listener2.messages.poll(5, TimeUnit.SECONDS);
        assertEquals("broadcast:hi", msg1);
        assertEquals("broadcast:hi", msg2);

        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        listener1.closed.get(5, TimeUnit.SECONDS);
        listener2.closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void sessionAccessible() throws Exception {
        // Create a session cookie
        var session = new Session();
        session.set("userId", "42");
        String cookie = "brace_session=" + session.toCookie("test-secret-key-at-least-32-characters-long");

        var listener = new WsTestClient();
        var ws = connectWithCookie("/session", cookie, listener);

        String response = listener.messages.poll(5, TimeUnit.SECONDS);
        assertEquals("user:42", response);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        listener.closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void sessionNullWhenNoCookie() throws Exception {
        var listener = new WsTestClient();
        var ws = connect("/session", listener);

        String response = listener.messages.poll(5, TimeUnit.SECONDS);
        assertEquals("user:anonymous", response);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        listener.closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void slowConsumerIsForceClosedByBackpressure() throws Exception {
        // M18: a client that stops reading must not make the server buffer outgoing frames without
        // bound — once its backlog passes wsMaxQueuedBytes the server force-closes it. Driven with a raw
        // socket that completes the WebSocket upgrade then never reads (the JDK HttpClient WebSocket
        // auto-drains the socket internally, so it can't simulate a stalled TCP consumer). The close
        // handshake can't complete against a wedged socket, so we assert on the synchronous backpressure
        // log event rather than the (timeout-delayed) onClose callback.
        long mark = LogTap.snapshot().stream().mapToLong(LogTap.LogEntry::id).max().orElse(0);

        try (var socket = new java.net.Socket("localhost", port)) {
            var req = "GET /blast HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
            socket.getOutputStream().write(req.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            // Read only the HTTP 101 handshake (up to the blank line), then never read again — the
            // server's blast frames pile up unread behind this point.
            String statusLine = readHttpHeaders(socket.getInputStream());
            assertTrue(statusLine.contains("101"), "expected WebSocket upgrade, got: " + statusLine);

            long deadline = System.currentTimeMillis() + 10_000;
            boolean closedForSlowness = false;
            while (System.currentTimeMillis() < deadline) {
                boolean seen = LogTap.since(mark).stream().anyMatch(e ->
                    String.valueOf(e.fields().get("message")).startsWith("ws-slow-consumer-closed"));
                if (seen) { closedForSlowness = true; break; }
                Thread.sleep(50);
            }
            assertTrue(closedForSlowness,
                "a non-reading client should trip per-connection backpressure and be force-closed");
        }
    }

    /** Consumes bytes up to and including the blank line that ends an HTTP response's headers; returns
     *  the status line. */
    private static String readHttpHeaders(java.io.InputStream in) throws Exception {
        var sb = new StringBuilder();
        int matched = 0; // position within the "\r\n\r\n" terminator
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            char expected = "\r\n\r\n".charAt(matched);
            if (b == expected) {
                if (++matched == 4) {
                    int eol = sb.indexOf("\r\n");
                    return eol > 0 ? sb.substring(0, eol) : sb.toString();
                }
            } else {
                matched = (b == '\r') ? 1 : 0;
            }
        }
        throw new java.io.EOFException("connection closed before WebSocket handshake completed");
    }

    @Test
    void httpRoutesStillWork() throws Exception {
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/hello"))
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("hello", response.body());
    }
}
