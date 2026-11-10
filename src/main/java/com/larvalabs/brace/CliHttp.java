package com.larvalabs.brace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLSession;

/**
 * The one HTTP path every ops request of the CLI goes through (L11 final review). The server the
 * CLI talks to is chosen by the committed {@code .brace}, so it may be hostile: it can accept the
 * connection and never answer, send the body a byte at a time, or never stop sending. Every
 * request therefore has a connect timeout, a request timeout, and a response body that is read
 * with a size cap and a total deadline, failing with a short {@link IOException}.
 */
final class CliHttp {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Time to the response headers, and, separately, to the end of the body. Tests shorten it. */
    static volatile Duration requestTimeout = Duration.ofSeconds(30);

    /** The most a response body may hold (16 MB). Tests shorten it. */
    static volatile long maxBodyBytes = 16L * 1024 * 1024;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build();

    private static final ExecutorService READERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "brace-cli-http-reader");
        t.setDaemon(true);
        return t;
    });

    private CliHttp() {}

    /** Sends the request with this class's timeout and reads the body within its cap and deadline. */
    static HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        Duration timeout = requestTimeout;
        long cap = maxBodyBytes;
        HttpResponse<InputStream> response = CLIENT.send(builder.timeout(timeout).build(),
            HttpResponse.BodyHandlers.ofInputStream());
        InputStream in = response.body();
        Future<byte[]> read = READERS.submit(() -> readCapped(in, cap));
        try {
            byte[] bytes = read.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Buffered(response, new String(bytes, StandardCharsets.UTF_8));
        } catch (TimeoutException e) {
            throw new IOException("the response was not complete within " + seconds(timeout) + " seconds");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("could not read the response: " + e.getCause());
        } finally {
            read.cancel(true);
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] readCapped(InputStream in, long cap) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > cap) throw new IOException("the response is larger than " + (cap / 1024) + " KB");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String seconds(Duration d) {
        return d.toMillis() % 1000 == 0 ? String.valueOf(d.toSeconds()) : String.format("%.1f", d.toMillis() / 1000.0);
    }

    /** The response with its body already read. */
    private record Buffered(HttpResponse<InputStream> origin, String body) implements HttpResponse<String> {
        @Override public int statusCode() { return origin.statusCode(); }
        @Override public HttpRequest request() { return origin.request(); }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return origin.headers(); }
        @Override public Optional<SSLSession> sslSession() { return origin.sslSession(); }
        @Override public URI uri() { return origin.uri(); }
        @Override public HttpClient.Version version() { return origin.version(); }
    }
}
