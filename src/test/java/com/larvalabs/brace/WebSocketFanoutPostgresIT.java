package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2 on real Postgres: a WebSocket broadcast reaches room members connected to a <em>different</em>
 * instance, proving cross-instance fan-out via {@link PostgresMessageBus} (LISTEN/NOTIFY). Two
 * separate {@code Brace} instances run against one Postgres; a client on instance A and a client on
 * instance B both join "lobby", then a broadcast originating on A must arrive at B.
 *
 * <p>This is the mechanism the single-process {@code WebSocketTest} cannot exercise: there, every
 * member is local, so the in-process bus would pass even if cross-instance fan-out were broken.
 */
class WebSocketFanoutPostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;
    static Brace instanceA;
    static Brace instanceB;

    /** Joins "lobby" on connect; an inbound message is broadcast to the whole lobby (fleet-wide). */
    public static class LobbySocket {
        private final WsContext ws;
        public LobbySocket(WsContext ws) { this.ws = ws; }
        public void onConnect() { ws.join("lobby"); }
        public void onMessage(String message) { ws.broadcast("lobby", "broadcast:" + message); }
    }

    @BeforeAll
    static void startInstances() throws Exception {
        // Base @BeforeAll started the shared container (skips the class if Docker is absent).
        dbFactory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());

        instanceA = Brace.app().port(0).database(dbFactory).ws("/ws", LobbySocket::new);
        instanceB = Brace.app().port(0).database(dbFactory).ws("/ws", LobbySocket::new);
        instanceA.start();
        instanceB.start();
    }

    @AfterAll
    static void stopInstances() throws Exception {
        if (instanceA != null) instanceA.stop();
        if (instanceB != null) instanceB.stop();
        if (dbFactory != null) dbFactory.close();
    }

    @Test
    void broadcastReachesMemberOnAnotherInstance() throws Exception {
        var clientB = new Collector();
        WebSocket wsB = connect(instanceB.actualPort(), clientB);
        // Also connect a member to A (the broadcaster's own instance) to confirm local delivery
        // still works through the same bus path.
        var clientA = new Collector();
        WebSocket wsA = connect(instanceA.actualPort(), clientA);

        // Give both LISTEN connections a moment to be established before broadcasting.
        Thread.sleep(500);

        // Trigger a broadcast on instance A.
        wsA.sendText("hello", true);

        // The member on instance B must receive it via Postgres LISTEN/NOTIFY fan-out.
        String onB = clientB.messages.poll(10, TimeUnit.SECONDS);
        assertEquals("broadcast:hello", onB, "broadcast from instance A should reach a member on instance B");

        // And the member on A receives it too (delivered via its own NOTIFY, one code path).
        String onA = clientA.messages.poll(10, TimeUnit.SECONDS);
        assertEquals("broadcast:hello", onA, "broadcast should also reach the originating instance's members");

        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void largePayloadSpillsAndStillFansOut() throws Exception {
        var clientB = new Collector();
        WebSocket wsB = connect(instanceB.actualPort(), clientB);
        var wsA = connect(instanceA.actualPort(), new Collector());
        Thread.sleep(500);

        // > 8KB once "broadcast:" is prepended, forcing the spill table path (NOTIFY payload cap).
        String big = "x".repeat(9000);
        wsA.sendText(big, true);

        String onB = clientB.messages.poll(10, TimeUnit.SECONDS);
        assertNotNull(onB, "large broadcast should reach instance B via the spill path");
        assertEquals("broadcast:" + big, onB);

        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    private static WebSocket connect(int port, Collector listener) throws Exception {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
    }

    /** Minimal WebSocket listener that queues complete text messages. */
    static class Collector implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.add(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
