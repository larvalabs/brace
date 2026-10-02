package com.larvalabs.brace;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String method;
    private final String url;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private HttpRequest.BodyPublisher bodyPublisher;
    private Duration timeout = Duration.ofSeconds(30);
    private boolean timeoutSet;
    private Duration idleTimeout = Duration.ofSeconds(30);

    private Http(String method, String url) {
        this.method = method;
        this.url = url;
    }

    public static Http get(String url) { return new Http("GET", url); }
    public static Http post(String url) { return new Http("POST", url); }
    public static Http put(String url) { return new Http("PUT", url); }
    public static Http delete(String url) { return new Http("DELETE", url); }

    public Http header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public Http bearer(String token) {
        return header("Authorization", "Bearer " + token);
    }

    /**
     * For {@code fetch*} calls, how long to wait for the response headers (default 30s). For
     * {@link #stream()} and {@link #fetchEvents}, the deadline for the whole call, headers and
     * body together (default none): the connection is closed and {@link StreamTimeoutException}
     * thrown when it passes.
     */
    public Http timeout(Duration timeout) {
        this.timeout = timeout;
        this.timeoutSet = true;
        return this;
    }

    /**
     * For {@link #stream()} and {@link #fetchEvents}: close the connection and throw
     * {@link StreamTimeoutException} when the response headers, or the next bytes of the body,
     * take this long to arrive (default 30s; {@code null} or {@link Duration#ZERO} turns it off).
     * Time the caller spends between reads doesn't count. Ignored by {@code fetch*} calls.
     */
    public Http idleTimeout(Duration idleTimeout) {
        if (idleTimeout != null && idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must not be negative: " + idleTimeout);
        }
        this.idleTimeout = idleTimeout == null || idleTimeout.isZero() ? null : idleTimeout;
        return this;
    }

    public Http bodyJson(Object value) {
        try {
            var json = Json.mapper().writeValueAsString(value);
            this.bodyPublisher = HttpRequest.BodyPublishers.ofString(json);
            headers.putIfAbsent("Content-Type", "application/json");
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    public Http bodyForm(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        this.bodyPublisher = HttpRequest.BodyPublishers.ofString(sb.toString());
        headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
        return this;
    }

    public Http bodyString(String body) {
        this.bodyPublisher = HttpRequest.BodyPublishers.ofString(body);
        return this;
    }

    public Http bodyBytes(byte[] bytes, String contentType) {
        this.bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
        headers.putIfAbsent("Content-Type", contentType);
        return this;
    }

    public Multipart multipart() {
        return new Multipart(this);
    }

    // --- Execute ---

    private HttpRequest buildRequest() {
        return buildRequest(timeout);
    }

    private HttpRequest buildRequest(Duration headerTimeout) {
        var builder = HttpRequest.newBuilder().uri(URI.create(url));
        if (headerTimeout != null) builder.timeout(headerTimeout);
        for (var entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        var pub = bodyPublisher != null ? bodyPublisher : HttpRequest.BodyPublishers.noBody();
        builder.method(method, pub);
        return builder.build();
    }

    public Response fetch() {
        try {
            var httpResponse = CLIENT.send(buildRequest(), HttpResponse.BodyHandlers.ofString());
            return new Response(httpResponse);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted: " + method + " " + url, e);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + method + " " + url, e);
        }
    }

    public <T> T fetchJson(Class<T> type) {
        var response = fetch();
        return response.as(type);
    }

    public String fetchString() {
        return fetch().body();
    }

    public byte[] fetchBytes() {
        try {
            var httpResponse = CLIENT.send(buildRequest(), HttpResponse.BodyHandlers.ofByteArray());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new RuntimeException("HTTP request failed: " + method + " " + url + " (status " + httpResponse.statusCode() + ")");
            }
            return httpResponse.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted: " + method + " " + url, e);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + method + " " + url, e);
        }
    }

    /**
     * Send the request and return as soon as the response headers arrive, with the body left
     * to read as it streams in. Close the result (try-with-resources) to release the
     * connection. {@link #idleTimeout} (default 30s) bounds each wait for data and an explicit
     * {@link #timeout} the whole call; either closes the connection and throws
     * {@link StreamTimeoutException}. A non-2xx status is returned, not thrown: check
     * {@link StreamResponse#ok()}.
     */
    public StreamResponse stream() {
        var watchdog = new Watchdog(this);
        try {
            var raw = CLIENT.send(buildRequest(watchdog.headerTimeout()), HttpResponse.BodyHandlers.ofInputStream());
            return new StreamResponse(raw, watchdog.attach(raw.body()));
        } catch (HttpTimeoutException e) {
            watchdog.close();
            if (e instanceof HttpConnectTimeoutException) {
                throw new RuntimeException("HTTP request failed: " + method + " " + url, e);
            }
            throw watchdog.headerTimeoutException();
        } catch (InterruptedException e) {
            watchdog.close();
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted: " + method + " " + url, e);
        } catch (StreamTimeoutException e) {
            throw e;
        } catch (Exception e) {
            watchdog.close();
            throw new RuntimeException("HTTP request failed: " + method + " " + url, e);
        }
    }

    /**
     * Read a {@code text/event-stream} response, passing each Server-Sent Event to
     * {@code onEvent} as it arrives, and return once the server ends the stream. The returned
     * {@link Response} carries the status and headers; on a non-2xx status no events are
     * parsed and its {@code body()} is the error body. Sends {@code Accept: text/event-stream}
     * unless an Accept header is set. Timeouts are as for {@link #stream()}.
     */
    public Response fetchEvents(Consumer<Event> onEvent) {
        if (headers.keySet().stream().noneMatch("Accept"::equalsIgnoreCase)) {
            headers.put("Accept", "text/event-stream");
        }
        try (var response = stream()) {
            if (!response.ok()) {
                return new Response(response.status(), response.raw.headers(), response.readString());
            }
            response.events(onEvent);
            return new Response(response.status(), response.raw.headers(), "");
        }
    }

    // --- Multipart builder ---

    public static class Multipart {

        private final Http http;
        private final String boundary = "----BraceBoundary" + Long.toHexString(System.nanoTime());
        private final List<Part> parts = new ArrayList<>();

        Multipart(Http http) { this.http = http; }

        public Multipart field(String name, String value) {
            parts.add(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        public Multipart field(String name, byte[] bytes, String filename) {
            return field(name, bytes, filename, guessContentType(filename));
        }

        public Multipart field(String name, byte[] bytes, String filename, String contentType) {
            parts.add(new Part(name, filename, contentType, bytes));
            return this;
        }

        public Multipart header(String name, String value) { http.header(name, value); return this; }
        public Multipart bearer(String token) { http.bearer(token); return this; }
        public Multipart timeout(Duration timeout) { http.timeout(timeout); return this; }
        public Multipart idleTimeout(Duration idleTimeout) { http.idleTimeout(idleTimeout); return this; }

        public Response fetch() { finalizeBody(); return http.fetch(); }
        public String fetchString() { finalizeBody(); return http.fetchString(); }
        public byte[] fetchBytes() { finalizeBody(); return http.fetchBytes(); }
        public <T> T fetchJson(Class<T> type) { finalizeBody(); return http.fetchJson(type); }
        public StreamResponse stream() { finalizeBody(); return http.stream(); }
        public Response fetchEvents(Consumer<Event> onEvent) { finalizeBody(); return http.fetchEvents(onEvent); }

        private void finalizeBody() {
            var out = new ByteArrayOutputStream();
            try {
                for (var part : parts) {
                    writeAscii(out, "--" + boundary + "\r\n");
                    if (part.filename != null) {
                        writeAscii(out, "Content-Disposition: form-data; name=\"" + escapePartValue(part.name)
                            + "\"; filename=\"" + escapePartValue(part.filename) + "\"\r\n");
                        writeAscii(out, "Content-Type: "
                            + escapePartValue(part.contentType != null ? part.contentType : "application/octet-stream")
                            + "\r\n");
                    } else {
                        writeAscii(out, "Content-Disposition: form-data; name=\"" + escapePartValue(part.name) + "\"\r\n");
                    }
                    writeAscii(out, "\r\n");
                    out.write(part.bytes);
                    writeAscii(out, "\r\n");
                }
                writeAscii(out, "--" + boundary + "--\r\n");
            } catch (IOException e) {
                throw new RuntimeException("Failed to build multipart body", e);
            }
            http.bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(out.toByteArray());
            http.headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        }

        private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
        }

        /**
         * Make a value safe to place inside a quoted-string in a multipart part header
         * (2026-07 review, M7).
         *
         * <p>Part names and filenames were concatenated into
         * {@code Content-Disposition: form-data; name="…"; filename="…"} raw and written
         * verbatim, so CR/LF in either value terminated the part headers and let the caller
         * forge <em>additional parts</em> in the outbound request — parameter smuggling into
         * whatever third-party API the app is calling. A user-supplied upload filename is the
         * obvious way that value becomes attacker-controlled.
         *
         * <p>Control characters are a hard failure rather than a silent strip: for an outbound
         * API call, a request that quietly differs from what the caller asked for is worse than
         * one that doesn't happen. Quotes and backslashes are escaped per RFC 7578 §4.2.
         */
        static String escapePartValue(String value) {
            if (value == null) return "";
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    throw new IllegalArgumentException(
                        "Multipart part name/filename must not contain control characters "
                            + "(found 0x" + Integer.toHexString(c) + "): " + value);
                }
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String guessContentType(String filename) {
            if (filename == null) return "application/octet-stream";
            var lower = filename.toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".pdf")) return "application/pdf";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".txt")) return "text/plain";
            if (lower.endsWith(".html")) return "text/html";
            if (lower.endsWith(".css")) return "text/css";
            if (lower.endsWith(".js")) return "application/javascript";
            if (lower.endsWith(".zip")) return "application/zip";
            return "application/octet-stream";
        }

        private record Part(String name, String filename, String contentType, byte[] bytes) {}
    }

    // --- Response ---

    public static class Response {

        private final int status;
        private final HttpHeaders headers;
        private final String body;

        Response(HttpResponse<String> raw) {
            this(raw.statusCode(), raw.headers(), raw.body());
        }

        Response(int status, HttpHeaders headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public int status() { return status; }
        public String body() { return body; }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public boolean ok() { return status() >= 200 && status() < 300; }

        public <T> T as(Class<T> type) {
            try {
                return Json.mapper().readValue(body(), type);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse response as " + type.getSimpleName()
                    + " (status " + status() + "): " + body(), e);
            }
        }
    }

    // --- Streaming ---

    /**
     * A response whose body is read as it arrives, from {@link #stream()}. Holds the connection
     * open until closed; use try-with-resources.
     */
    public static class StreamResponse implements AutoCloseable {

        private final HttpResponse<InputStream> raw;
        private final InputStream body;
        private volatile boolean closed;

        StreamResponse(HttpResponse<InputStream> raw, InputStream body) {
            this.raw = raw;
            this.body = body;
        }

        public int status() { return raw.statusCode(); }
        public boolean ok() { return status() >= 200 && status() < 300; }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        /** The body. Reads block until bytes arrive, bounded by the call's timeouts. */
        public InputStream body() { return body; }

        /** The body as UTF-8 lines, each available as soon as it arrives. Call once. */
        public Stream<String> lines() { return reader().lines(); }

        /** The rest of the body as a UTF-8 string, such as the error body of a non-2xx response. */
        public String readString() {
            try {
                return new String(body.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Parse the body as Server-Sent Events, passing each to {@code onEvent} as it arrives,
         * and return when the stream ends or this response is closed (from the consumer or
         * another thread), which is how to stop early. Check {@link #ok()} first: an error body
         * isn't an event stream. Call once.
         */
        public void events(Consumer<Event> onEvent) {
            var parser = new EventParser(onEvent);
            try {
                var reader = reader();
                for (String line; !closed && (line = reader.readLine()) != null; ) parser.line(line);
            } catch (IOException e) {
                if (closed) return;
                throw new UncheckedIOException(e);
            }
        }

        BufferedReader reader() {
            return new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            closed = true;
            try {
                body.close();
            } catch (IOException ignored) {
                // Closing only cancels the exchange; there's nothing to report.
            }
        }
    }

    /**
     * One Server-Sent Event. {@code type} is the {@code event:} field, {@code "message"} when
     * absent. {@code id} and {@code retry} are the last values the stream set (they carry over
     * between events, as in a browser's EventSource), or null if it never set them.
     */
    public record Event(String type, String data, String id, Duration retry) {

        public <T> T as(Class<T> type) {
            try {
                return Json.mapper().readValue(data, type);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse event data as " + type.getSimpleName()
                    + " (event " + this.type + "): " + data, e);
            }
        }
    }

    /**
     * A streamed call ran past its {@link #timeout} deadline or its {@link #idleTimeout}. The
     * connection has been closed. An {@link UncheckedIOException}, so it also surfaces through
     * {@link StreamResponse#body()} reads and {@link StreamResponse#lines()}.
     */
    public static class StreamTimeoutException extends UncheckedIOException {

        private final boolean idle;
        private final Duration limit;

        StreamTimeoutException(String method, String url, boolean idle, Duration limit) {
            this(message(method, url, idle, limit), idle, limit);
        }

        private StreamTimeoutException(String message, boolean idle, Duration limit) {
            super(message, new HttpTimeoutException(message));
            this.idle = idle;
            this.limit = limit;
        }

        /** True for the idle timeout, false for the total deadline. */
        public boolean idle() { return idle; }

        /** The timeout that was exceeded. */
        public Duration limit() { return limit; }

        private static String message(String method, String url, boolean idle, Duration limit) {
            var millis = limit.toMillis();
            var span = millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
            return "HTTP stream timed out: " + method + " " + url
                + (idle ? " (no data for " + span + ")" : " (exceeded " + span + " deadline)");
        }
    }

    /**
     * The text/event-stream format, per the WHATWG HTML spec ("Parsing an event stream"), fed
     * one line at a time with the line ending already stripped. As the spec requires, an event
     * still pending when the stream ends (no closing blank line) is dropped: the stream was
     * most likely cut off mid-event, and half a payload is worse than none.
     */
    static final class EventParser {

        private final Consumer<Event> onEvent;
        private final StringBuilder data = new StringBuilder();
        private String type = "";
        private String id;
        private Duration retry;
        private boolean first = true;

        EventParser(Consumer<Event> onEvent) { this.onEvent = onEvent; }

        void line(String line) {
            if (first) {
                first = false;
                if (line.startsWith("﻿")) line = line.substring(1);
            }
            if (line.isEmpty()) {
                dispatch();
                return;
            }
            if (line.charAt(0) == ':') return;
            int colon = line.indexOf(':');
            var field = colon < 0 ? line : line.substring(0, colon);
            var value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            switch (field) {
                case "event" -> type = value;
                case "data" -> data.append(value).append('\n');
                case "id" -> { if (value.indexOf('\0') < 0) id = value; }
                case "retry" -> {
                    if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                        try {
                            retry = Duration.ofMillis(Long.parseLong(value));
                        } catch (NumberFormatException ignored) {
                            // Too large for a long; ignored like any other invalid retry.
                        }
                    }
                }
                default -> { }
            }
        }

        private void dispatch() {
            if (!data.isEmpty()) {
                data.setLength(data.length() - 1);
                onEvent.accept(new Event(type.isEmpty() ? "message" : type, data.toString(), id, retry));
            }
            data.setLength(0);
            type = "";
        }
    }

    /**
     * Enforces a streamed call's timeouts. {@code HttpRequest.timeout} only covers the wait for
     * response headers, so a body that stalls would otherwise block forever: the watchdog closes
     * the body stream from a shared timer thread, which unblocks a pending read, and the
     * wrapped stream turns that into a {@link StreamTimeoutException}.
     *
     * <p>The deadline, when set, is one task scheduled when the call starts. The idle timeout is one task
     * at a time that re-arms itself: it fires only when a read has been blocked for the whole
     * limit, so a caller that is slow to read never trips it.
     */
    private static final class Watchdog {

        private static final ScheduledThreadPoolExecutor TIMER = timer();
        private static final long NOT_READING = Long.MIN_VALUE;

        private final String method;
        private final String url;
        private final Duration timeout;
        private final Duration idleTimeout;
        private final ScheduledFuture<?> deadline;
        private volatile ScheduledFuture<?> idleCheck;
        private volatile long readingSince = NOT_READING;
        private volatile boolean fired;
        private volatile boolean firedIdle;
        private boolean done;
        private InputStream stream;

        Watchdog(Http http) {
            this.method = http.method;
            this.url = http.url;
            this.timeout = http.timeoutSet ? http.timeout : null;
            this.idleTimeout = http.idleTimeout;
            this.deadline = timeout == null ? null
                : TIMER.schedule(() -> fire(false), timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        /** The wait for response headers counts against the idle timeout too, when it's shorter. */
        private boolean headerWaitIsIdle() {
            return idleTimeout != null && (timeout == null || idleTimeout.compareTo(timeout) < 0);
        }

        Duration headerTimeout() {
            return headerWaitIsIdle() ? idleTimeout : timeout;
        }

        StreamTimeoutException headerTimeoutException() {
            var idle = headerWaitIsIdle();
            return new StreamTimeoutException(method, url, idle, idle ? idleTimeout : timeout);
        }

        private static ScheduledThreadPoolExecutor timer() {
            var timer = new ScheduledThreadPoolExecutor(1, r -> {
                var t = new Thread(r, "brace-http-stream-timer");
                t.setDaemon(true);
                return t;
            });
            // Most deadlines are cancelled long before they're due; don't keep them queued.
            timer.setRemoveOnCancelPolicy(true);
            return timer;
        }

        synchronized InputStream attach(InputStream raw) {
            if (fired) {
                closeQuietly(raw);
                throw timeoutException();
            }
            stream = raw;
            if (idleTimeout != null) {
                idleCheck = TIMER.schedule(this::checkIdle, idleTimeout.toNanos(), TimeUnit.NANOSECONDS);
            }
            return new WatchedInputStream(raw, this);
        }

        private void checkIdle() {
            if (done || fired) return;
            long since = readingSince;
            long limit = idleTimeout.toNanos();
            long waited = since == NOT_READING ? 0 : System.nanoTime() - since;
            if (waited >= limit) {
                fire(true);
            } else {
                idleCheck = TIMER.schedule(this::checkIdle, limit - waited, TimeUnit.NANOSECONDS);
            }
        }

        private synchronized void fire(boolean idle) {
            if (done || fired) return;
            firedIdle = idle;
            fired = true;
            if (stream != null) closeQuietly(stream);
            cancelTimers();
        }

        void beginRead() {
            check();
            readingSince = System.nanoTime();
        }

        void endRead() { readingSince = NOT_READING; }

        void check() {
            if (fired) throw timeoutException();
        }

        synchronized void close() {
            done = true;
            cancelTimers();
        }

        private void cancelTimers() {
            if (deadline != null) deadline.cancel(false);
            var idle = idleCheck;
            if (idle != null) idle.cancel(false);
        }

        private StreamTimeoutException timeoutException() {
            return new StreamTimeoutException(method, url, firedIdle, firedIdle ? idleTimeout : timeout);
        }

        private static void closeQuietly(InputStream in) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing is how the read is cancelled; a failure here changes nothing.
            }
        }
    }

    /** Routes every read through the {@link Watchdog} so a timeout surfaces as itself. */
    private static final class WatchedInputStream extends FilterInputStream {

        private interface Read { long run() throws IOException; }

        private final Watchdog watchdog;

        WatchedInputStream(InputStream in, Watchdog watchdog) {
            super(in);
            this.watchdog = watchdog;
        }

        @Override public int read() throws IOException { return (int) watched(in::read); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return (int) watched(() -> in.read(b, off, len)); }
        @Override public long skip(long n) throws IOException { return watched(() -> in.skip(n)); }

        private long watched(Read read) throws IOException {
            watchdog.beginRead();
            try {
                long result = read.run();
                watchdog.check();
                return result;
            } catch (IOException e) {
                watchdog.check();
                throw e;
            } finally {
                watchdog.endRead();
            }
        }

        @Override
        public void close() throws IOException {
            watchdog.close();
            in.close();
        }
    }
}
