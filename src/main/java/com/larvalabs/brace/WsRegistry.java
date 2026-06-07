package com.larvalabs.brace;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

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

    WsRegistry(MessageBus bus) {
        this.bus = bus;
        bus.subscribe(this::deliverLocal);
    }

    void join(String room, WsContext ctx) {
        rooms.computeIfAbsent(room, k -> new CopyOnWriteArraySet<>()).add(ctx);
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

    /** Deliver a message to members connected to THIS instance. Invoked by the {@link MessageBus}. */
    private void deliverLocal(String room, String message) {
        var members = rooms.get(room);
        if (members != null) {
            for (var ctx : members) {
                ctx.send(message);
            }
        }
    }

    void close() {
        bus.close();
    }
}
