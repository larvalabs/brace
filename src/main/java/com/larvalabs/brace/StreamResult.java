package com.larvalabs.brace;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * A response whose body is streamed rather than held in memory.
 *
 * <p>Built through the {@link Result} factories — {@link Result#file}, {@link Result#stream},
 * {@link Result#download(Path, String)} — not directly. The framework writes it with a bounded
 * buffer, so the response costs the same whether it is 4 KB or 4 GB.
 *
 * <h2>What a streaming response cannot do</h2>
 *
 * <ul>
 *   <li><b>It has no {@code body()} / {@code rawBytes()}.</b> Both return null. An after-middleware
 *       that rewrites bodies should check {@link Result#isStreaming()} and pass through.</li>
 *   <li><b>It cannot be page cached.</b> {@code Cache.wrap} throws rather than caching an empty
 *       body — see {@code Cache.RenderedResponse.from}.</li>
 *   <li><b>It cannot read from the request's {@code Database}.</b> The per-request transaction is
 *       committed and its connection returned to the pool <em>before</em> the response is written,
 *       so a source that queries the request's session finds it closed. This is deliberate (it is
 *       what keeps template rendering off a pooled connection). To stream a large export, open a
 *       dedicated session inside a {@link Result#stream(Consumer, String)} writer and accept that a
 *       slow client holds that connection for the duration of the download.</li>
 *   <li><b>It cannot change its status once bytes are on the wire.</b> A source that fails midway
 *       aborts the connection — the client sees a truncated transfer, which is the only honest
 *       signal left after the status line has gone out. It is never a silently short 200.</li>
 * </ul>
 */
public class StreamResult extends Result {

    /** Where the bytes come from. */
    sealed interface Body permits FileBody, StreamBody, WriterBody {}

    /**
     * A byte range of a file. {@code offset}/{@code length} carry {@code Range} support:
     * {@code length < 0} means "to the end".
     */
    record FileBody(Path path, long offset, long length) implements Body {}

    /** A one-shot stream. {@code length < 0} means unknown, which forces chunked encoding. */
    record StreamBody(InputStream stream, long length) implements Body {}

    /** Content generated on demand — CSV, ZIP, NDJSON. Always chunked. */
    record WriterBody(Consumer<OutputStream> writer) implements Body {}

    private final Body streamBody;

    StreamResult(int status, String contentType, Body body, long contentLength) {
        super(status, contentType, (String) null);
        this.streamBody = body;
        if (contentLength >= 0) {
            header("Content-Length", Long.toString(contentLength));
        }
    }

    Body streamBody() {
        return streamBody;
    }

    @Override
    public boolean isStreaming() {
        return true;
    }

    /**
     * The total size of the underlying resource, independent of any range being served — the
     * denominator in a {@code Content-Range}. Negative when unknown.
     */
    long totalLength() {
        if (streamBody instanceof FileBody f) {
            try {
                return Files.size(f.path());
            } catch (Exception e) {
                return -1;
            }
        }
        if (streamBody instanceof StreamBody s) {
            return s.length();
        }
        return -1;
    }
}
