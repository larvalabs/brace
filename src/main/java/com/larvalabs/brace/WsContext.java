package com.larvalabs.brace;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.StatusCode;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket context wrapping a Jetty WebSocket session.
 * Provides send, room management, broadcast, and session access.
 *
 * <p>Room membership and broadcast fan-out live in the per-instance {@link WsRegistry}; this
 * context just tracks which rooms it has joined (for cleanup on disconnect) and delegates.
 */
public class WsContext {

    private final org.eclipse.jetty.websocket.api.Session jettySession;
    private final Session session; // may be null
    private final WsRegistry registry;
    private final Set<String> joinedRooms = ConcurrentHashMap.newKeySet();

    WsContext(org.eclipse.jetty.websocket.api.Session jettySession, Session session, WsRegistry registry) {
        this.jettySession = jettySession;
        this.session = session;
        this.registry = registry;
    }

    /**
     * Send a text message to this connection.
     */
    public void send(String message) {
        jettySession.sendText(message, Callback.NOOP);
    }

    /**
     * Join a named room.
     */
    public void join(String room) {
        joinedRooms.add(room);
        registry.join(room, this);
    }

    /**
     * Leave a named room.
     */
    public void leave(String room) {
        joinedRooms.remove(room);
        registry.leave(room, this);
    }

    /**
     * Broadcast a message to all connections in a room — across every instance in the fleet, not
     * just this one (the registry's {@link MessageBus} handles cross-instance fan-out).
     */
    public void broadcast(String room, String message) {
        registry.broadcast(room, message);
    }

    /**
     * Read-only access to the HTTP session from the upgrade request.
     * May be null if no session was configured or no session cookie was present.
     */
    public Session session() {
        return session;
    }

    /**
     * Close this WebSocket connection.
     */
    public void close() {
        jettySession.close(StatusCode.NORMAL, "closed", Callback.NOOP);
    }

    /**
     * Remove this context from all rooms it has joined.
     * Called internally on disconnect.
     */
    void leaveAllRooms() {
        for (var room : joinedRooms) {
            registry.leave(room, this);
        }
        joinedRooms.clear();
    }
}
