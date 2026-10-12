package com.larvalabs.brace;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Callback;

/**
 * Gzip response compression, on by default ({@link Brace#compression(boolean)}).
 *
 * <p>Brace used to leave compression to the reverse proxy, but the common proxies don't do it out
 * of the box (Dokploy's Traefik has no compress middleware by default), so the leg between the app
 * server and the CDN went uncompressed — an HTML page ~18x its gzipped size, paid in egress. Jetty's
 * {@link GzipHandler} does the work; this class only fixes Brace's choices on top of its defaults:
 *
 * <ul>
 *   <li><b>Only for clients that ask.</b> {@code Accept-Encoding: gzip} or the response goes out
 *       unchanged. Compressible responses carry {@code Vary: Accept-Encoding} either way.</li>
 *   <li><b>Methods:</b> GET and POST (Jetty's default) plus PUT, PATCH and DELETE, since htmx
 *       requests with any of them get HTML back.</li>
 *   <li><b>Small bodies stay plain.</b> Under {@link #MIN_SIZE} bytes, gzip's header and trailer
 *       eat most of the saving.</li>
 *   <li><b>Already-compressed types are skipped:</b> Jetty's list (images other than SVG, audio,
 *       video, zip/gzip/brotli/zstd/... archives) plus web fonts.</li>
 *   <li><b>Event streams are never compressed.</b> {@code text/event-stream} must reach the browser
 *       an event at a time, and a deflater holds small writes back until its buffer fills.</li>
 *   <li><b>Streamed bodies stay streamed.</b> Every write is sync-flushed, so a
 *       {@code Result.stream} writer's output still reaches the client as it is produced. A
 *       one-shot body is a single write, so this costs nothing there.</li>
 *   <li><b>Range requests are passed through untouched.</b> A 206 carries a {@code Content-Range}
 *       in the uncompressed bytes; gzipping it would send bytes that don't match that range. A
 *       resumed download whose {@code If-Range} names a gzipped ETag ({@code "...--gzip"}) does
 *       not match the file's ETag, so it gets the whole file again, which is correct.</li>
 *   <li><b>Request bodies are never inflated.</b> {@code Content-Encoding: gzip} on a request is
 *       left alone, so {@code maxUploadSize} bounds the bytes a handler reads, not the bytes on the
 *       wire. Inflating would let a few KB of gzip expand past the cap (a gzip bomb).</li>
 * </ul>
 *
 * <p>ETags: Jetty appends {@code --gzip} to the ETag of a compressed response and strips it from
 * {@code If-None-Match} before Brace sees the request, so conditional GETs against static files
 * and the bundled htmx still answer 304, with the suffixed ETag the client sent.
 */
final class CompressionHandler extends GzipHandler {

    static final int MIN_SIZE = 1024;

    CompressionHandler(Handler next) {
        super(next);
        setIncludedMethods("GET", "POST", "PUT", "PATCH", "DELETE");
        setMinGzipSize(MIN_SIZE);
        setSyncFlush(true);
        setInflateBufferSize(-1); // Jetty's default today; pinned because it's a security property
        addExcludedMimeTypes("text/event-stream", "font/woff", "font/woff2");
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (request.getHeaders().contains(HttpHeader.RANGE)) {
            Handler next = getHandler();
            return next != null && next.handle(request, response, callback);
        }
        return super.handle(request, response, callback);
    }
}
