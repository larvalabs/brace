package io.brace;

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
        private final WsContext ws;
        public RoomSocket(WsContext ws) { this.ws = ws; }
        public void onConnect() {
            ws.join("lobby");
        }
        public void onMessage(String message) {
            ws.broadcast("lobby", "broadcast:" + message);
        }
        public void onClose(int code, String reason) {
            ws.leave("lobby");
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
            .ws("/echo", EchoSocket::new)
            .ws("/room", RoomSocket::new)
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
        WsContext.rooms.clear();
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

        var ws1 = connect("/room", listener1);
        var ws2 = connect("/room", listener2);

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
