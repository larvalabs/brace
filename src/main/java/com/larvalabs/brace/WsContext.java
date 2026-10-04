package com.larvalabs.brace;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.StatusCode;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    // M18 backpressure: bytes handed to Jetty's sendText but not yet flushed to the socket. A slow
    // client that stops reading makes its frames pile up in Jetty's outgoing queue unboundedly; this
    // counter bounds that per connection. closed guards both threshold/error closes and close() so we
    // only close once and stop sending afterward.
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    WsContext(org.eclipse.jetty.websocket.api.Session jettySession, Session session, WsRegistry registry) {
        this.jettySession = jettySession;
        this.session = session;
        this.registry = registry;
    }

    /**
     * Send a text message to this connection. Applies per-connection backpressure (M18): each send's
     * size is tracked until Jetty reports the frame flushed (via the callback), and if a connection's
     * unflushed backlog exceeds the configured cap — i.e. the client is too slow to drain — it is
     * force-closed with {@code TRY_AGAIN_LATER} rather than buffering without bound. A failed send
     * (broken connection) likewise stops further sends; Jetty's close/error callback cleans up rooms.
     * Backpressure is per connection, so a slow client never blocks healthy members of the same room.
     */
    public void send(String message) {
        if (closed.get()) return;
        // Approximate the queued payload by char count — a cheap proxy for UTF-8 bytes (exact for
        // ASCII, under-counts multibyte by up to ~3x). This is a safety threshold, not exact accounting,
        // so the approximation only loosens the effective cap; it never lets the backlog grow unbounded.
        long size = message.length();
        if (queuedBytes.get() + size > registry.maxQueuedBytes()) {
            if (closed.compareAndSet(false, true)) {
                Log.warn("ws-slow-consumer-closed queuedBytes=" + queuedBytes.get()
                    + " capBytes=" + registry.maxQueuedBytes());
                jettySession.close(StatusCode.TRY_AGAIN_LATER, "slow consumer", Callback.NOOP);
            }
            return;
        }
        queuedBytes.addAndGet(size);
        try {
            jettySession.sendText(message, Callback.from(
                () -> queuedBytes.addAndGet(-size),
                failure -> {
                    queuedBytes.addAndGet(-size);
                    closed.set(true); // broken connection — stop sending; Jetty fires onClose/onError → cleanup
                }));
        } catch (RuntimeException e) {
            // M8: a synchronous throw (e.g. sending on a session Jetty already closed) never
            // reaches the callback, so without this the reservation above is never released. The
            // connection would then carry a permanent phantom backlog and eventually be
            // force-closed as a "slow consumer" it never was.
            queuedBytes.addAndGet(-size);
            closed.set(true);
            throw e;
        }
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
     * Close this WebSocket connection. Idempotent — also marks the context closed so any in-flight
     * broadcast sends stop targeting it.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            jettySession.close(StatusCode.NORMAL, "closed", Callback.NOOP);
        }
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
