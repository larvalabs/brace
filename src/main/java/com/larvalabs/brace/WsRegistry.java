package com.larvalabs.brace;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance WebSocket room registry and broadcast fan-out. Holds the set of room members
 * connected to <em>this</em> instance and a {@link MessageBus} that carries broadcasts across the
 * fleet.
 *
 * <p>{@link #broadcast} publishes to the bus rather than delivering directly; the bus calls back
 * into {@link #deliverLocal} on every instance (including this one), which sends to locally
 * connected members. With {@link InProcessMessageBus} that callback is synchronous and local-only
 * (single-process behavior is unchanged); with the Postgres bus it fans out via
 * {@code LISTEN}/{@code NOTIFY} so a room split across instances behind a load balancer is reunited.
 *
 * <p>This replaces the former {@code static} room map on {@code WsContext}: registry state is now
 * scoped to a {@code Brace} instance, so multiple apps in one JVM (notably tests) don't share rooms.
 */
final class WsRegistry {

    private final ConcurrentHashMap<String, Set<WsContext>> rooms = new ConcurrentHashMap<>();
    private final MessageBus bus;
    private final long maxQueuedBytes;

    WsRegistry(MessageBus bus, long maxQueuedBytes) {
        this.bus = bus;
        this.maxQueuedBytes = maxQueuedBytes;
        bus.subscribe(this::deliverLocal);
    }

    /** Per-connection cap on bytes queued-but-not-yet-flushed before a slow consumer is force-closed (M18). */
    long maxQueuedBytes() {
        return maxQueuedBytes;
    }

    void join(String room, WsContext ctx) {
        // L15: ConcurrentHashMap.newKeySet() gives O(1) add/remove on join/leave, vs the O(n)
        // array copy CopyOnWriteArraySet paid on every membership change (quadratic for a
        // high-churn room). Its iterator is weakly consistent, which is still safe for the
        // lock-free broadcast fan-out in deliverLocal — a just-joined/just-left member may or may
        // not be seen for an in-flight broadcast, the same tolerance the snapshot iterator had.
        rooms.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet()).add(ctx);
    }

    void leave(String room, WsContext ctx) {
        var members = rooms.get(room);
        if (members != null) {
            members.remove(ctx);
            if (members.isEmpty()) {
                rooms.remove(room, members);
            }
        }
    }

    /** Publish a broadcast to the whole fleet; delivery happens via the bus callback. */
    void broadcast(String room, String message) {
        bus.publish(room, message);
    }

    /**
     * Deliver a message to members connected to THIS instance. Invoked by the {@link MessageBus}.
     *
     * <p>M8: each send is isolated. The loop used to be unguarded, so anything thrown for one
     * member — a session Jetty has already closed, say — aborted delivery to every remaining
     * member, and one bad connection silently dropped the broadcast for the whole room. A failing
     * member is logged and skipped; its own close/error callback handles cleanup.
     */
    private void deliverLocal(String room, String message) {
        var members = rooms.get(room);
        if (members == null) {
            return;
        }
        for (var ctx : members) {
            try {
                ctx.send(message);
            } catch (RuntimeException e) {
                Log.warn("ws-broadcast-send-failed room=" + room + " error=" + e);
            }
        }
    }

    void close() {
        bus.close();
    }
}
