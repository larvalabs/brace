package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2 (2026-10 security review): WebSocket routes take part in the access model. Before, the
 * upgrade ran no guard at all, so a socket under a guarded prefix connected anonymously.
 */
class WebSocketAccessTest {

    static final String SECRET = "ws-access-test-secret-at-least-32-characters";
    static Brace app;
    static int port;

    public static class WhoAmI {
        private final WsContext ws;
        public WhoAmI(WsContext ws) { this.ws = ws; }
        public void onConnect() {
            ws.send("user:" + ws.session().get("userId") + ":" + ws.session().get("touched"));
        }
    }

    @BeforeAll
    static void start() throws Exception {
        app = Brace.app().port(0).banner(false).sessions(SECRET).strictAccessCheck()
            .defaultAccess(Access.session("userId", "/login"));
        app.before(req -> "1".equals(req.queryParam("shed")) ? Result.error(429, "slow down") : null);
        app.get("/login", req -> Result.text("login")).publicRoute();
        app.ws("/live", WhoAmI::new);
        app.ws("/status", WhoAmI::new).publicRoute();
        app.ws("/admin/live", WhoAmI::new)
            .access(Access.check((req, s) -> {
                s.set("touched", "yes");
                return "admin".equals(s.get("role")) ? null : Result.forbidden();
            }));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        app.stop();
    }

    @Test
    void anonymousUpgradeToAGuardedSocketIsDeclined() {
        assertEquals(401, handshakeStatus("/live", null));
    }

    @Test
    void authenticatedUpgradeConnectsWithTheSession() throws Exception {
        assertEquals("user:42:null", firstMessage("/live", Session.of("userId", "42")));
    }

    @Test
    void publicSocketConnectsAnonymously() throws Exception {
        assertEquals("user:null:null", firstMessage("/status", null));
    }

    @Test
    void routeRequirementStacksOnTheDefaultAndSharesTheSession() throws Exception {
        assertEquals(401, handshakeStatus("/admin/live", null), "default rejects first");
        assertEquals(403, handshakeStatus("/admin/live", Session.of("userId", "1")));
        // The socket gets the very session the requirement read (and touched).
        assertEquals("user:1:yes", firstMessage("/admin/live", Session.of("userId", "1", "role", "admin")));
    }

    @Test
    void plainBeforeMiddlewareRunsOnTheUpgrade() {
        assertEquals(429, handshakeStatus("/status?shed=1", null));
    }

    @Test
    void crossOriginUpgradeIsDeclinedPromptly() {
        // The M3 Origin check only set a status on the declined upgrade, so the client waited for
        // its own timeout; it now gets the 403.
        var b = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", "https://evil.example");
        try {
            b.buildAsync(URI.create("ws://localhost:" + port + "/status"), new WebSocket.Listener() {})
                .get(5, TimeUnit.SECONDS);
            fail("cross-origin upgrade must be declined");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof WebSocketHandshakeException, String.valueOf(e.getCause()));
            assertEquals(403, ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void unmarkedSocketFailsStart() {
        var ex = assertThrows(IllegalStateException.class, () -> {
            var a = Brace.app().port(0).banner(false).strictAccessCheck();
            a.ws("/open", WhoAmI::new);
            a.start();
        });
        assertTrue(ex.getMessage().contains("WS     /open"), ex.getMessage());
    }

    // --- helpers ---

    private static WebSocket.Builder builder(Session session) {
        var b = HttpClient.newHttpClient().newWebSocketBuilder();
        if (session != null) b.header("Cookie", "brace_session=" + session.toCookie(SECRET));
        return b;
    }

    private static int handshakeStatus(String path, Session session) {
        try {
            builder(session).buildAsync(URI.create("ws://localhost:" + port + path), new WebSocket.Listener() {})
                .get(5, TimeUnit.SECONDS);
            return 101;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException h) return h.getResponse().statusCode();
            throw new AssertionError("unexpected handshake failure", e);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String firstMessage(String path, Session session) throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        var ws = builder(session).buildAsync(URI.create("ws://localhost:" + port + path), new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                messages.add(data.toString());
                w.request(1);
                return null;
            }
        }).get(5, TimeUnit.SECONDS);
        try {
            return messages.poll(5, TimeUnit.SECONDS);
        } finally {
            ws.abort();
        }
    }
}
