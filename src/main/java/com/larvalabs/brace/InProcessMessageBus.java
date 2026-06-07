package com.larvalabs.brace;

import java.util.function.BiConsumer;

/**
 * Single-process {@link MessageBus}: {@code publish} delivers synchronously to the local
 * subscriber. The default when there's no Postgres to fan out across — behaviorally identical to
 * the original process-local WebSocket broadcast.
 */
final class InProcessMessageBus implements MessageBus {

    private volatile BiConsumer<String, String> onMessage = (room, message) -> {};

    @Override
    public void subscribe(BiConsumer<String, String> onMessage) {
        this.onMessage = onMessage;
    }

    @Override
    public void publish(String room, String message) {
        onMessage.accept(room, message);
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
