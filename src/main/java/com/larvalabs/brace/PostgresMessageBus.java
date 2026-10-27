package com.larvalabs.brace;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Postgres-backed {@link MessageBus} for cross-instance WebSocket fan-out (B2). Broadcasts travel
 * via {@code LISTEN}/{@code NOTIFY} on one per-app channel, so a room whose members are spread across
 * instances behind a load balancer receives every broadcast.
 *
 * <p><b>Integrity (L6).</b> Postgres lets any role that can connect {@code NOTIFY} or {@code LISTEN}
 * on any channel, so every payload is signed by {@link MessageBusSigner} with a key derived from the
 * app's shared secret, and the channel is named from that key ({@code brace_ws_<hash>}). A payload
 * without a valid, fresh, unseen signature is dropped and counted, never delivered.
 *
 * <p><b>Publish</b> signs {@code {t, n, r:room, m:message}} and issues {@code pg_notify}. A NOTIFY
 * payload must be shorter than 8000 bytes, so a signed payload over {@link #INLINE_LIMIT_BYTES} is
 * written to {@code brace_ws_messages} and the NOTIFY carries only a signed {@code {t, n, sid:id}};
 * every listener fetches the row by id and verifies the stored payload's own signature too, since
 * the table is no harder to write than the channel. Publish uses the normal pooled connection.
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

    // Measured on the whole signed payload (signature, separator and JSON body, t and n included),
    // so the signing overhead is inside the budget. The headroom under Postgres's 8000-byte cap
    // covers a database encoding other than UTF-8 re-encoding the payload.
    static final int INLINE_LIMIT_BYTES = 7900;
    // Belt and braces for the LISTEN statement, which can't take a bind parameter.
    private static final Pattern CHANNEL_NAME = Pattern.compile("[a-z0-9_]{1,63}");
    private static final int LISTEN_BLOCK_MS = 10_000;
    private static final long RECONNECT_BACKOFF_MS = 1_000;
    // Reap spill rows once every instance has had ample time to read them. Matches the signer's
    // acceptance window: an older row would be rejected anyway.
    private static final String SPILL_RETENTION = "60 seconds";
    // Rejected payloads are logged at most this often, with a count, so a flood of forgeries
    // can't flood the log.
    private static final long REJECT_LOG_INTERVAL_MS = 60_000;

    private final DatabaseFactory dbFactory;
    private final MessageBusSigner signer;
    private final String channel;
    private final AtomicLong rejected = new AtomicLong();
    private long rejectedAtLastLog = 0;
    private long lastRejectLogMs = 0;
    private boolean rejectLogged = false;
    private final Thread listenerThread;
    private volatile boolean closed = false;
    private volatile BiConsumer<String, String> onMessage = (room, message) -> {};

    /**
     * @param secret the app's shared secret (resolved by {@code Brace.start}). It must be identical
     *               on every instance: instances with different secrets land on different channels
     *               and never see each other's broadcasts.
     */
    PostgresMessageBus(DatabaseFactory dbFactory, String secret) {
        this.dbFactory = dbFactory;
        this.signer = new MessageBusSigner(secret);
        this.channel = signer.channel();
        if (!CHANNEL_NAME.matcher(channel).matches()) {
            throw new IllegalStateException("invalid message bus channel name: " + channel);
        }
        this.listenerThread = new Thread(this::listenLoop, "brace-ws-listener");
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
    }

    @Override
    public void subscribe(BiConsumer<String, String> onMessage) {
        this.onMessage = onMessage;
    }

    /** The channel this bus listens and notifies on. */
    String channel() {
        return channel;
    }

    /** Payloads dropped for a malformed, wrong, stale or replayed signature, since start. */
    long rejectedCount() {
        return rejected.get();
    }

    @Override
    public void publish(String room, String message) {
        try {
            String inline = signer.seal(Map.of("r", room, "m", message));
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

    /** Inline path: pg_notify carries the full signed {t,n,r,m} payload. */
    private void notify(String payload) {
        var db = new Database(dbFactory.openSession());
        try {
            db.beginTransaction();
            db.jdbc(conn -> {
                try (var ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                    ps.setString(1, channel);
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

    /**
     * Spill path: store the signed payload, notify with a signed reference to its id. Insert +
     * notify in one txn.
     */
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
                String ref;
                try {
                    // The reference names the row and its content hash, so a row rewritten after
                    // this (another genuine signed payload copied in) isn't accepted for it.
                    ref = signer.seal(Map.of("sid", id, "h", MessageBusSigner.contentHash(payload)));
                } catch (Exception e) {
                    throw new IllegalStateException("could not sign the spill reference", e);
                }
                try (var ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                    ps.setString(1, channel);
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
                    // LISTEN takes no bind parameter. The name is validated to [a-z0-9_] in the
                    // constructor, and quoted so it stays case-exact, matching pg_notify's text arg.
                    st.execute("LISTEN \"" + channel + "\"");
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

    /** Verify one NOTIFY payload and deliver it. Package-private so tests can feed it directly. */
    void deliver(String payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> body = signer.open(payload);
            if (body.containsKey("sid")) {
                if (!(body.get("sid") instanceof Number sid)) {
                    return;
                }
                String full = fetchSpill(sid.longValue());
                if (full == null) {
                    return;
                }
                if (!MessageBusSigner.contentHash(full).equals(body.get("h"))) {
                    reject(MessageBusSigner.Rejection.BAD_SIGNATURE);
                    return;
                }
                // The row is no harder to write than the channel, so it carries its own signature.
                body = signer.open(full);
            }
            if (body.get("r") instanceof String room && body.get("m") instanceof String message) {
                onMessage.accept(room, message);
            }
        } catch (MessageBusSigner.RejectedException e) {
            reject(e.reason);
        } catch (Exception e) {
            Log.event("ws_notification_parse_failed", Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Count a dropped payload, and log at most once per {@link #REJECT_LOG_INTERVAL_MS} with how
     * many were dropped since the last line. A stray one is worth seeing (a forged NOTIFY, or old
     * and new instances mixed in a rolling deploy); a line per forgery would let a forger fill the
     * log.
     */
    private void reject(MessageBusSigner.Rejection reason) {
        long total = rejected.incrementAndGet();
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (rejectLogged && now - lastRejectLogMs < REJECT_LOG_INTERVAL_MS) {
                return;
            }
            Log.event("ws_broadcast_rejected", Map.of(
                "reason", reason.name().toLowerCase(),
                "dropped", total - rejectedAtLastLog,
                "channel", channel));
            rejectedAtLastLog = total;
            lastRejectLogMs = now;
            rejectLogged = true;
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
