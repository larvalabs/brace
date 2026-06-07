package com.larvalabs.brace;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Postgres-backed {@link MessageBus} for cross-instance WebSocket fan-out (B2). Broadcasts travel
 * via {@code LISTEN}/{@code NOTIFY} on a single channel, so a room whose members are spread across
 * instances behind a load balancer receives every broadcast.
 *
 * <p><b>Publish</b> serializes {@code {r:room, m:message}} to JSON and issues {@code pg_notify}. A
 * NOTIFY payload is capped at 8000 bytes, so anything larger is written to {@code brace_ws_messages}
 * and the NOTIFY carries only {@code {sid:id}}; every listener fetches the payload by id. Publish
 * uses the normal pooled connection.
 *
 * <p><b>Listen</b> runs on a dedicated thread with its own raw connection (held outside the Hikari
 * pool, since it blocks on {@code LISTEN}). It auto-reconnects with backoff if the connection drops
 * (Postgres restart, network blip). Postgres delivers a NOTIFY to <em>every</em> listener including
 * the publisher's own instance, so local delivery happens through the same callback as remote
 * delivery — one code path.
 *
 * <p>Delivery is at-most-once and only to currently-connected members — correct for ephemeral
 * broadcast, not a missed-message replay log.
 *
 * <p>The pgjdbc notification API ({@code PGConnection#getNotifications}) is reached via reflection
 * so the framework keeps the JDBC driver at {@code runtime} scope; this class is only ever loaded
 * on a Postgres app, where the driver is present.
 */
final class PostgresMessageBus implements MessageBus {

    static final String CHANNEL = "brace_ws";
    // Leave headroom under Postgres's 8000-byte NOTIFY payload cap for the JSON wrapper.
    private static final int INLINE_LIMIT_BYTES = 7900;
    private static final int LISTEN_BLOCK_MS = 10_000;
    private static final long RECONNECT_BACKOFF_MS = 1_000;
    // Reap spill rows once every instance has had ample time to read them.
    private static final String SPILL_RETENTION = "60 seconds";

    private final DatabaseFactory dbFactory;
    private final Thread listenerThread;
    private volatile boolean closed = false;
    private volatile BiConsumer<String, String> onMessage = (room, message) -> {};

    PostgresMessageBus(DatabaseFactory dbFactory) {
        this.dbFactory = dbFactory;
        this.listenerThread = new Thread(this::listenLoop, "brace-ws-listener");
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
    }

    @Override
    public void subscribe(BiConsumer<String, String> onMessage) {
        this.onMessage = onMessage;
    }

    @Override
    public void publish(String room, String message) {
        try {
            String inline = Json.mapper().writeValueAsString(Map.of("r", room, "m", message));
            if (inline.getBytes(StandardCharsets.UTF_8).length <= INLINE_LIMIT_BYTES) {
                notify(inline);
            } else {
                spillAndNotify(inline);
            }
        } catch (Exception e) {
            // A failed broadcast must not break the request handler that triggered it.
            Log.event("ws_broadcast_failed", Map.of(
                "room", room,
                "error", String.valueOf(e.getMessage())));
        }
    }

    /** Inline path: pg_notify carries the full {r,m} payload. */
    private void notify(String payload) {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            db.jdbc(conn -> {
                try (var ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                    ps.setString(1, CHANNEL);
                    ps.setString(2, payload);
                    ps.execute();
                }
                return null;
            });
            db.commitTransaction();
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } finally {
            db.close();
        }
    }

    /** Spill path: store the payload, notify with just its id. Insert + notify in one txn. */
    private void spillAndNotify(String payload) {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            db.jdbc(conn -> {
                long id;
                try (var ps = conn.prepareStatement(
                        "INSERT INTO brace_ws_messages (payload) VALUES (?) RETURNING id")) {
                    ps.setString(1, payload);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
                // id is numeric; build this tiny reference JSON by hand to keep the JDBC lambda
                // free of Jackson's checked JsonProcessingException.
                String ref = "{\"sid\":" + id + "}";
                try (var ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                    ps.setString(1, CHANNEL);
                    ps.setString(2, ref);
                    ps.execute();
                }
                // Opportunistic reap of payloads old enough that every listener has read them.
                try (var ps = conn.prepareStatement(
                        "DELETE FROM brace_ws_messages WHERE created_at < now() - INTERVAL '" + SPILL_RETENTION + "'")) {
                    ps.executeUpdate();
                }
                return null;
            });
            db.commitTransaction();
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } finally {
            db.close();
        }
    }

    @Override
    public void close() {
        closed = true;
        listenerThread.interrupt();
    }

    // --- Listener ---

    private void listenLoop() {
        // Resolve the pgjdbc notification API once, from the interfaces (robust to impl-class
        // visibility). PGConnection#getNotifications(int) blocks up to the timeout; PGNotification
        // #getParameter() is the NOTIFY payload string.
        final Method getNotifications;
        final Method getParameter;
        try {
            getNotifications = Class.forName("org.postgresql.PGConnection")
                .getMethod("getNotifications", int.class);
            getParameter = Class.forName("org.postgresql.PGNotification")
                .getMethod("getParameter");
        } catch (Exception e) {
            // pgjdbc absent or incompatible — without it there's no LISTEN/NOTIFY. The bus degrades
            // to publish-only (local delivery still works via the publisher's own NOTIFY path is
            // lost too, so log loudly). This should never happen on a Postgres app.
            Log.event("ws_listener_unavailable", Map.of("error", String.valueOf(e.getMessage())));
            return;
        }
        while (!closed) {
            try (Connection conn = dbFactory.openRawConnection()) {
                try (var st = conn.createStatement()) {
                    st.execute("LISTEN " + CHANNEL);
                }
                Object pgConn = conn.unwrap(Class.forName("org.postgresql.PGConnection"));
                while (!closed) {
                    Object[] notifications = (Object[]) getNotifications.invoke(pgConn, LISTEN_BLOCK_MS);
                    if (notifications != null) {
                        for (Object n : notifications) {
                            deliver((String) getParameter.invoke(n));
                        }
                    }
                }
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                Log.event("ws_listener_reconnect", Map.of("error", String.valueOf(e.getMessage())));
                sleep(RECONNECT_BACKOFF_MS);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void deliver(String payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> parsed = Json.mapper().readValue(payload, Map.class);
            if (parsed.containsKey("sid")) {
                long id = ((Number) parsed.get("sid")).longValue();
                String full = fetchSpill(id);
                if (full != null) {
                    Map<String, Object> msg = Json.mapper().readValue(full, Map.class);
                    onMessage.accept((String) msg.get("r"), (String) msg.get("m"));
                }
            } else {
                onMessage.accept((String) parsed.get("r"), (String) parsed.get("m"));
            }
        } catch (Exception e) {
            Log.event("ws_notification_parse_failed", Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private String fetchSpill(long id) {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            String payload = db.jdbc(conn -> {
                try (var ps = conn.prepareStatement("SELECT payload FROM brace_ws_messages WHERE id = ?")) {
                    ps.setLong(1, id);
                    try (var rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
            db.commitTransaction();
            return payload;
        } catch (RuntimeException e) {
            db.rollbackTransaction();
            throw e;
        } finally {
            db.close();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
