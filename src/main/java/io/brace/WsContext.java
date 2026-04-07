package io.brace;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.StatusCode;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket context wrapping a Jetty WebSocket session.
 * Provides send, room management, broadcast, and session access.
 */
public class WsContext {

    // Global room registry: room name -> set of connected contexts
    static final ConcurrentHashMap<String, Set<WsContext>> rooms = new ConcurrentHashMap<>();

    private final org.eclipse.jetty.websocket.api.Session jettySession;
    private final Session session; // may be null
    private final Set<String> joinedRooms = ConcurrentHashMap.newKeySet();

    WsContext(org.eclipse.jetty.websocket.api.Session jettySession, Session session) {
        this.jettySession = jettySession;
        this.session = session;
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
        rooms.computeIfAbsent(room, k -> new CopyOnWriteArraySet<>()).add(this);
    }

    /**
     * Leave a named room.
     */
    public void leave(String room) {
        joinedRooms.remove(room);
        var members = rooms.get(room);
        if (members != null) {
            members.remove(this);
            if (members.isEmpty()) {
                rooms.remove(room, members);
            }
        }
    }

    /**
     * Broadcast a message to all connections in a room.
     */
    public void broadcast(String room, String message) {
        var members = rooms.get(room);
        if (members != null) {
            for (var ctx : members) {
                ctx.send(message);
            }
        }
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
            var members = rooms.get(room);
            if (members != null) {
                members.remove(this);
                if (members.isEmpty()) {
                    rooms.remove(room, members);
                }
            }
        }
        joinedRooms.clear();
    }
}
