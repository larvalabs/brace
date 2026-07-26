package com.larvalabs.brace;

import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.io.Content;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file part from a {@code multipart/form-data} request.
 *
 * <p>An upload is backed either by a heap {@code byte[]} (small parts, and hand-built instances in
 * tests) or by a temp file Jetty spilled it to during parsing — parts larger than
 * {@code app.uploadMemoryThreshold(...)}, 1 MB by default. Which one it is does not change the API;
 * it changes how much heap the request costs.
 *
 * <p>Prefer {@link #stream()}, {@link #saveTo(Path)}, and {@link #transferTo(OutputStream)} — those
 * are bounded-memory whatever the backing store. {@link #bytes()} materializes the whole part in
 * heap and is kept for compatibility and for genuinely small uploads; it is bounded only by
 * {@code app.maxUploadSize(...)}, so an app that raises that cap into the gigabytes should stop
 * calling it.
 *
 * <p>A file-backed upload is only valid for the duration of its request: the framework releases the
 * temp file when the request finishes, whatever the outcome. Handing an {@code UploadedFile} to a
 * background job and reading it later will fail — {@link #saveTo(Path)} it somewhere durable, or
 * push it to {@link Storage}, before the handler returns.
 */
public class UploadedFile {

    private final String filename;
    private final String contentType;

    /** Non-null for a heap-backed upload; null when {@link #part} owns the content. */
    private final byte[] bytes;
    /**
     * Non-null for a parser-backed upload. Kept as the Jetty type rather than wrapped: the part is
     * the thing that knows whether it is chunks or a file, and it already implements the two
     * operations that matter here ({@code newContentSource}, {@code writeTo}) with the right
     * behavior for both. It never appears in this class's public signatures.
     */
    private final MultiPart.Part part;
    private final long size;

    public UploadedFile(String filename, String contentType, byte[] bytes) {
        this.filename = filename;
        this.contentType = contentType;
        this.bytes = bytes;
        this.part = null;
        this.size = bytes.length;
    }

    UploadedFile(MultiPart.Part part, String filename, String contentType, long size) {
        this.filename = filename;
        this.contentType = contentType;
        this.bytes = null;
        this.part = part;
        this.size = size;
    }

    public String filename() { return filename; }
    public String contentType() { return contentType; }
    public long size() { return size; }

    /**
     * The part's content as a byte array — <strong>materializes the whole upload in heap</strong>.
     *
     * <p>For a file-backed part this reads the temp file back. Bounded by
     * {@code app.maxUploadSize(...)}, which is the only thing keeping it from being unbounded, so
     * prefer {@link #stream()} / {@link #saveTo(Path)} / {@link #transferTo(OutputStream)} for
     * anything that might be large.
     */
    public byte[] bytes() {
        if (bytes != null) return bytes;
        if (size > Integer.MAX_VALUE - 8) {
            throw new IllegalStateException(
                "Upload '" + filename + "' is " + size + " bytes and cannot be materialized as a "
                    + "byte[] — use stream(), saveTo(Path), or transferTo(OutputStream)");
        }
        try (var in = stream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file: " + filename, e);
        }
    }

    /**
     * Opens a stream over the part's content. The caller owns the stream and must close it.
     *
     * <p>Repeatable: each call returns a fresh stream positioned at the start, so a caller can read
     * the content more than once (hashing it and then uploading it, say) without buffering.
     */
    public InputStream stream() {
        if (bytes != null) return new java.io.ByteArrayInputStream(bytes);
        // The 3-arg overload, not the no-arg one: Part.newContentSource() is a deprecated stub that
        // returns null unless a subclass overrides it, and neither ChunksPart nor PathPart does —
        // they override this one. A null ByteBufferPool is supported (Content.Source.from(Path)
        // passes null itself); offset 0 / length -1 means "the whole part".
        var source = part.newContentSource(null, 0, -1);
        if (source == null) {
            // Only when the part has already been closed — i.e. the upload outlived its request.
            throw new IllegalStateException(
                "Uploaded file '" + filename + "' is no longer readable: its content was released "
                    + "when the request finished. Save it (saveTo/transferTo/Storage.put) before "
                    + "the handler returns.");
        }
        return Content.Source.asInputStream(source);
    }

    /** Copies the content to {@code out} without materializing it. Returns the bytes written. */
    public long transferTo(OutputStream out) throws IOException {
        if (bytes != null) {
            out.write(bytes);
            return bytes.length;
        }
        try (var in = stream()) {
            return in.transferTo(out);
        }
    }

    /**
     * Saves the upload to {@code path}, creating parent directories as needed.
     *
     * <p>For a file-backed part this is a filesystem move where possible — no copy, no heap — and
     * the moved file is no longer the framework's to clean up. For a heap-backed part it is a
     * streaming write. An existing file at {@code path} is replaced.
     */
    public void saveTo(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (bytes != null) {
            Files.write(path, bytes);
            return;
        }
        // Part.writeTo moves a spilled file and streams a chunked one, and clears the part's
        // "temporary" flag so the end-of-request release does not delete what we just moved.
        part.writeTo(path);
    }
}
