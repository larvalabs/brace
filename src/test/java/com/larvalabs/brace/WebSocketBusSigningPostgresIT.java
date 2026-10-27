package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L6 on real Postgres: the cross-instance WebSocket bus delivers only payloads signed with the
 * app's key, on the app's own channel.
 *
 * <p>Any role that can connect can {@code NOTIFY} any channel, so the attacker here is a plain
 * JDBC connection issuing {@code pg_notify}. Three instances share one database: two of app A
 * (same secret) and one of app B (a different secret), every one with a client in room "lobby".
 *
 * <p>The forgery tests send their forgeries first and a genuine broadcast last, then assert the
 * genuine one is the first frame the client sees. NOTIFYs from separate transactions arrive in
 * commit order, so a forgery that got through would be ahead of it in the queue.
 */
class WebSocketBusSigningPostgresIT extends PostgresTestBase {

    static final String SECRET_A = "app-a-signing-secret-0123456789abcdefghij";
    static final String SECRET_B = "app-b-signing-secret-0123456789abcdefghij";

    static DatabaseFactory dbFactory;
    static Brace appA1;
    static Brace appA2;
    static Brace appB;

    /** Joins "lobby" on connect; an inbound message is broadcast to the whole lobby (fleet-wide). */
    public static class LobbySocket {
        private final WsContext ws;
        public LobbySocket(WsContext ws) { this.ws = ws; }
        public void onConnect() { ws.join("lobby"); }
        public void onMessage(String message) { ws.broadcast("lobby", message); }
    }

    @BeforeAll
    static void startInstances() throws Exception {
        dbFactory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
        appA1 = lobbyApp().sessions(SECRET_A);
        appA2 = lobbyApp().opsSecret(SECRET_A); // no sessions: the ops secret is the fallback
        appB = lobbyApp().sessions(SECRET_B);
        appA1.start();
        appA2.start();
        appB.start();
    }

    private static Brace lobbyApp() {
        Brace app = Brace.app().port(0).database(dbFactory).ownsDatabase(false);
        app.ws("/ws", LobbySocket::new);
        return app;
    }

    @AfterAll
    static void stopInstances() throws Exception {
        if (appA1 != null) appA1.stop();
        if (appA2 != null) appA2.stop();
        if (appB != null) appB.stop();
        if (dbFactory != null) dbFactory.close();
    }

    @Test
    void unsignedNotifyIsNotDelivered() throws Exception {
        var client = new WebSocketFanoutPostgresIT.Collector();
        WebSocket ws = connect(appA1, client);
        Thread.sleep(500);

        String channel = new MessageBusSigner(SECRET_A).channel();
        String forged = "{\"r\":\"lobby\",\"m\":\"<img src=x onerror=alert(1)>\"}";
        notifyRaw(channel, forged);
        notifyRaw("brace_ws", forged); // the pre-0.1.11 shared channel
        ws.sendText("genuine-1", true);

        assertEquals("genuine-1", client.messages.poll(10, TimeUnit.SECONDS));
        assertNull(client.messages.poll(500, TimeUnit.MILLISECONDS));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void wronglySignedNotifyIsNotDelivered() throws Exception {
        var client = new WebSocketFanoutPostgresIT.Collector();
        WebSocket ws = connect(appA1, client);
        Thread.sleep(500);

        String channel = new MessageBusSigner(SECRET_A).channel();
        // Signed, but with another app's key, sent to this app's channel.
        notifyRaw(channel, new MessageBusSigner(SECRET_B).seal(Map.of("r", "lobby", "m", "wrong-key")));
        // Signed correctly, then the body edited.
        String tampered = new MessageBusSigner(SECRET_A).seal(Map.of("r", "lobby", "m", "original"))
                .replace("\"original\"", "\"tampered\"");
        notifyRaw(channel, tampered);
        ws.sendText("genuine-2", true);

        assertEquals("genuine-2", client.messages.poll(10, TimeUnit.SECONDS));
        assertNull(client.messages.poll(500, TimeUnit.MILLISECONDS));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void validlySignedNotifyIsDeliveredOnceAndNotReplayed() throws Exception {
        var client = new WebSocketFanoutPostgresIT.Collector();
        WebSocket ws = connect(appA2, client);
        Thread.sleep(500);

        String channel = new MessageBusSigner(SECRET_A).channel();
        String signed = new MessageBusSigner(SECRET_A).seal(Map.of("r", "lobby", "m", "signed-direct"));
        notifyRaw(channel, signed);
        assertEquals("signed-direct", client.messages.poll(10, TimeUnit.SECONDS));

        // A captured message (LISTEN needs no privilege) re-sent within the window is dropped.
        notifyRaw(channel, signed);
        ws.sendText("genuine-3", true);
        assertEquals("genuine-3", client.messages.poll(10, TimeUnit.SECONDS));
        assertNull(client.messages.poll(500, TimeUnit.MILLISECONDS));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void forgedSpillRowIsNotDelivered() throws Exception {
        var client = new WebSocketFanoutPostgresIT.Collector();
        WebSocket ws = connect(appA1, client);
        Thread.sleep(500);

        // The spill table is no harder to write than the channel. Even with a correctly signed
        // reference (the strongest case), an unsigned row must not be delivered.
        long id;
        try (Connection conn = raw();
             var ps = conn.prepareStatement(
                     "INSERT INTO brace_ws_messages (payload) VALUES (?) RETURNING id")) {
            ps.setString(1, "{\"r\":\"lobby\",\"m\":\"forged-spill\"}");
            try (var rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        String channel = new MessageBusSigner(SECRET_A).channel();
        notifyRaw(channel, new MessageBusSigner(SECRET_A).seal(Map.of("sid", id)));
        ws.sendText("genuine-4", true);

        assertEquals("genuine-4", client.messages.poll(10, TimeUnit.SECONDS));
        assertNull(client.messages.poll(500, TimeUnit.MILLISECONDS));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void instancesOfOneAppShareBroadcastsAndOtherAppsDoNot() throws Exception {
        var onA1 = new WebSocketFanoutPostgresIT.Collector();
        var onA2 = new WebSocketFanoutPostgresIT.Collector();
        var onB = new WebSocketFanoutPostgresIT.Collector();
        WebSocket wsA1 = connect(appA1, onA1);
        WebSocket wsA2 = connect(appA2, onA2);
        WebSocket wsB = connect(appB, onB);
        Thread.sleep(500);

        // App B broadcasts to its "lobby" first; app A's "lobby" members must not see it.
        wsB.sendText("from-b", true);
        assertEquals("from-b", onB.messages.poll(10, TimeUnit.SECONDS));

        // App A's broadcast crosses A's instances (session-secret one to ops-secret one).
        wsA1.sendText("from-a", true);
        assertEquals("from-a", onA2.messages.poll(10, TimeUnit.SECONDS));
        assertEquals("from-a", onA1.messages.poll(10, TimeUnit.SECONDS));
        assertNull(onB.messages.poll(500, TimeUnit.MILLISECONDS), "app B must not see app A's lobby");
        assertNull(onA1.messages.poll(100, TimeUnit.MILLISECONDS));
        assertNull(onA2.messages.poll(100, TimeUnit.MILLISECONDS));

        wsA1.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        wsA2.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void largeBroadcastStillSpillsAndFansOut() throws Exception {
        var onA2 = new WebSocketFanoutPostgresIT.Collector();
        WebSocket wsA2 = connect(appA2, onA2);
        WebSocket wsA1 = connect(appA1, new WebSocketFanoutPostgresIT.Collector());
        Thread.sleep(500);

        // Just under the inline limit before signing, over it after: exercises the overhead.
        long spilledBefore = maxSpillId();
        String big = "y".repeat(PostgresMessageBus.INLINE_LIMIT_BYTES - 40);
        wsA1.sendText(big, true);
        assertEquals(big, onA2.messages.poll(10, TimeUnit.SECONDS));
        // It went through the spill table, not inline: the signature counted toward the limit.
        assertTrue(maxSpillId() > spilledBefore, "the signed payload must have spilled");

        wsA1.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        wsA2.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void appWithoutASharedSecretFailsToStart() {
        Brace app = lobbyApp();
        var e = assertThrows(IllegalStateException.class, app::start);
        assertTrue(e.getMessage().contains(".sessions(secret)"), e.getMessage());
    }

    private static WebSocket connect(Brace app, WebSocket.Listener listener) throws Exception {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + app.actualPort() + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
    }

    private static long maxSpillId() throws Exception {
        try (Connection conn = raw(); var rs = conn.createStatement().executeQuery(
                "SELECT COALESCE(MAX(id), 0) FROM brace_ws_messages")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection raw() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** What an attacker with any database login can do: NOTIFY a channel with any payload. */
    private static void notifyRaw(String channel, String payload) throws Exception {
        try (Connection conn = raw(); var ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
            ps.setString(1, channel);
            ps.setString(2, payload);
            ps.execute();
        }
    }
}
