package com.larvalabs.brace;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The sending side of a Server-Sent Events response, handed to the producer of
 * {@link Result#sse(Producer)}.
 *
 * <pre>{@code
 * app.get("/ticks", req -> Result.sse(events -> {
 *     for (int i = 0; events.isOpen(); i++) {
 *         events.send("tick", "n=" + i, String.valueOf(i));
 *         Thread.sleep(1000);
 *     }
 * }));
 * }</pre>
 *
 * <p>Each call writes one complete event and flushes it to the client before returning; nothing is
 * buffered. Calls are safe from any thread, so a producer can hand the stream to a listener and
 * block until the client leaves.
 *
 * <h2>When the client goes away</h2>
 *
 * A send to a disconnected client throws {@link UncheckedIOException}, and from then on
 * {@link #isOpen()} is false. A client that leaves while the producer is waiting is noticed by the
 * next heartbeat (every 15 seconds by default, see {@link #heartbeat(Duration)}), and the producer
 * thread is then <em>interrupted</em>, so a {@code Thread.sleep} or {@code queue.take()} returns
 * with {@link InterruptedException}. Either exception ending the producer after a disconnect is the
 * normal end of an event stream: Brace closes the response without logging a failure. An exception
 * while the client is still connected is a real failure and aborts the connection.
 *
 * <h2>Database access</h2>
 *
 * The producer runs after the request transaction has committed and its connection has gone back
 * to the pool, so a stream never pins a connection for its lifetime. The handler's
 * {@code Database} is closed by then: do the per-request reads in the handler, and inside the
 * producer open a short session per unit of work with {@code dbFactory.withSession(db -> ...)}.
 *
 * <h2>Reconnects</h2>
 *
 * A browser {@code EventSource} reconnects on its own, sending the last {@code id} it received as
 * the {@code Last-Event-ID} request header. Read it with {@code req.header("Last-Event-ID")} in the
 * handler to resume from that point; {@link #retry(Duration)} sets how long the browser waits.
 */
public final class EventStream {

    /** Writes events to the stream. Returns, or throws, to end it. */
    @FunctionalInterface
    public interface Producer {
        void produce(EventStream events) throws Exception;
    }

    static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(15);
    private static final long MAX_HEARTBEAT_SLEEP_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final OutputStream out;
    /** Serializes frames from the producer and the heartbeat thread, so events never interleave. */
    private final ReentrantLock writeLock = new ReentrantLock();
    /** Guards {@link #producerThread}, so an interrupt can never land after the producer returns. */
    private final Object producerLock = new Object();
    private Thread producerThread;

    private volatile boolean open = true;
    /** Why the stream closed before the producer finished: a failed write or a Jetty failure. */
    private volatile Throwable disconnect;
    private volatile long heartbeatNanos = DEFAULT_HEARTBEAT.toNanos();
    private volatile long lastWriteNanos = System.nanoTime();

    EventStream(OutputStream out) {
        this.out = out;
    }

    /** Sends an unnamed event (a {@code message} event in the browser). */
    public void send(String data) {
        send(null, data, null);
    }

    /** Sends an event of type {@code event}; a browser receives it via {@code addEventListener(event, ...)}. */
    public void send(String event, String data) {
        send(event, data, null);
    }

    /**
     * Sends an event with an {@code id}, which the browser echoes back as {@code Last-Event-ID} when
     * it reconnects. {@code event} and {@code id} may be null. A multi-line {@code data} is sent as
     * several {@code data:} lines and arrives with its line breaks intact.
     */
    public void send(String event, String data, String id) {
        var frame = new StringBuilder();
        if (event != null) frame.append("event: ").append(field("event", event)).append('\n');
        if (id != null) frame.append("id: ").append(field("id", id)).append('\n');
        for (String line : (data == null ? "" : data).split("\r\n|\r|\n", -1)) {
            frame.append("data: ").append(line).append('\n');
        }
        write(frame.append('\n').toString());
    }

    /** Sends {@code value} serialized as JSON, as an event of type {@code event} (null for unnamed). */
    public void sendJson(String event, Object value) {
        sendJson(event, value, null);
    }

    /** {@link #sendJson(String, Object)} with an {@code id}; see {@link #send(String, String, String)}. */
    public void sendJson(String event, Object value, String id) {
        try {
            send(event, Json.mapper().writeValueAsString(value), id);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize SSE data as JSON: " + e.getMessage(), e);
        }
    }

    /** Sends a comment line, which clients ignore. Useful as a keep-alive or for debugging. */
    public void comment(String text) {
        var frame = new StringBuilder();
        for (String line : (text == null ? "" : text).split("\r\n|\r|\n", -1)) {
            frame.append(':');
            if (!line.isEmpty()) frame.append(' ').append(line);
            frame.append('\n');
        }
        write(frame.append('\n').toString());
    }

    /** Tells the browser how long to wait before reconnecting after the stream drops. */
    public void retry(Duration delay) {
        write("retry: " + delay.toMillis() + "\n\n");
    }

    /**
     * Sets how long the stream may go without a write before Brace sends a comment to keep it alive.
     * The heartbeat also detects a client that has left while the producer is waiting. Defaults to
     * 15 seconds, below Jetty's 30-second idle timeout and common proxy read timeouts.
     * {@link Duration#ZERO} turns it off.
     */
    public void heartbeat(Duration interval) {
        this.heartbeatNanos = interval.toNanos();
    }

    /** False once the client has disconnected or the stream has ended. */
    public boolean isOpen() {
        return open;
    }

    // --- framework side ---

    /**
     * Runs {@code producer} on the calling thread and then ends the response, returning the failure
     * to report, or null for a clean end. Called once, by {@link BraceHandler}.
     */
    Throwable run(Producer producer) {
        Thread heartbeat = Thread.ofVirtual().name("brace-sse-heartbeat").start(this::heartbeatLoop);
        Throwable failure = null;
        try {
            synchronized (producerLock) {
                producerThread = Thread.currentThread();
            }
            // Commit the status line and headers now, so the client's EventSource opens before
            // the first event rather than whenever the producer gets around to sending one.
            flushHeaders();
            producer.produce(this);
        } catch (Throwable t) {
            failure = t;
        } finally {
            synchronized (producerLock) {
                producerThread = null;
                // Clear an interrupt delivered on disconnect: this is the request thread, and the
                // flag must not leak into the rest of the request lifecycle.
                Thread.interrupted();
            }
            heartbeat.interrupt();
        }
        if (disconnect != null) return disconnect;
        if (failure != null) {
            open = false;
            return failure;
        }
        writeLock.lock();
        try {
            open = false;
            out.close(); // the terminal chunk: a clean end of the response
            return null;
        } catch (IOException e) {
            return e;
        } finally {
            writeLock.unlock();
        }
    }

    /** Whether the stream ended because the client (or the server's connection) went away. */
    boolean disconnected() {
        return disconnect != null;
    }

    /**
     * Marks the stream closed from outside a write: Jetty reporting the connection failed (server
     * stopping, idle timeout). Wakes a waiting producer.
     */
    void disconnect(Throwable cause) {
        if (disconnect == null) disconnect = cause;
        open = false;
        synchronized (producerLock) {
            if (producerThread != null) producerThread.interrupt();
        }
    }

    private void flushHeaders() throws IOException {
        writeLock.lock();
        try {
            out.flush();
            lastWriteNanos = System.nanoTime();
        } finally {
            writeLock.unlock();
        }
    }

    private void write(String frame) {
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        writeLock.lock();
        try {
            if (!open) {
                throw new UncheckedIOException(new IOException("SSE client disconnected"));
            }
            // One write per frame. Jetty's sink is unbuffered, so this is the flush.
            out.write(bytes);
            lastWriteNanos = System.nanoTime();
        } catch (IOException e) {
            disconnect(e);
            throw new UncheckedIOException("SSE client disconnected", e);
        } finally {
            writeLock.unlock();
        }
    }

    private void heartbeatLoop() {
        try {
            while (open) {
                // Re-read every pass, and sleep at most a second at a time, so a heartbeat(...)
                // call from the producer takes effect promptly.
                long interval = heartbeatNanos;
                long wait = MAX_HEARTBEAT_SLEEP_NANOS;
                if (interval > 0) {
                    long idle = System.nanoTime() - lastWriteNanos;
                    if (idle >= interval) {
                        write(":\n\n");
                        idle = 0;
                    }
                    wait = Math.min(wait, interval - idle);
                }
                TimeUnit.NANOSECONDS.sleep(wait);
            }
        } catch (InterruptedException | UncheckedIOException e) {
            // Stream ended, or the client left (write() has already recorded the disconnect).
        }
    }

    /** {@code event} and {@code id} are single-line fields: a line break would inject new fields. */
    private static String field(String name, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("SSE " + name + " must not contain line breaks or NUL");
        }
        return value;
    }
}
