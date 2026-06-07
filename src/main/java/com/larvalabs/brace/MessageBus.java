package com.larvalabs.brace;

import java.util.function.BiConsumer;

/**
 * Carries WebSocket room broadcasts across a deployment. A {@link WsRegistry} publishes every
 * {@code broadcast(room, message)} to the bus; the bus invokes the registry's delivery callback
 * on <em>every</em> instance (including the publisher), which then sends to its locally connected
 * members.
 *
 * <p>Two implementations ship:
 * <ul>
 *   <li>{@link InProcessMessageBus} — the default for single-process / non-Postgres apps. Publish
 *       delivers synchronously to the local subscriber; behaviorally identical to the original
 *       process-local broadcast.</li>
 *   <li>{@code PostgresMessageBus} — selected automatically when the app runs on Postgres. Fans
 *       out via {@code LISTEN}/{@code NOTIFY} so a room whose members are spread across instances
 *       behind a load balancer still receives every broadcast.</li>
 * </ul>
 *
 * <p>Behind an interface so a Redis-backed bus can drop in later without touching {@code WsContext}
 * or {@code WsRegistry}. Delivery is at-most-once and reaches only currently-connected members —
 * correct for ephemeral broadcast, not a missed-message replay log.
 */
interface MessageBus {

    /** Register the delivery callback {@code (room, message)} invoked for each received broadcast. */
    void subscribe(BiConsumer<String, String> onMessage);

    /** Publish a broadcast to all instances (including this one, via the subscribed callback). */
    void publish(String room, String message);

    /** Stop listening and release resources (e.g. the dedicated listener connection). */
    void close();
}
