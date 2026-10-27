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
 * <p>A parser-backed upload is only valid for the duration of its request: the framework releases
 * its content (the temp file, or the in-memory chunks) when the request finishes, whatever the
 * outcome. Handing an {@code UploadedFile} to a
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

    /**
     * The filename the client sent, <strong>unmodified and attacker-controlled</strong>: it may
     * contain {@code ../}, a leading {@code /}, backslashes, control characters, or be empty. Never
     * pass it to {@code Path.resolve} or {@code new File(dir, ...)}. Use {@link #safeFilename()}
     * for a storage name and {@link #saveTo(Path, String)} to write it under a directory.
     */
    public String filename() { return filename; }
    public String contentType() { return contentType; }

    /**
     * A filename that is safe to use as a single path component: the part after the last
     * {@code /} or {@code \}, with control characters removed and leading dots trimmed (so no
     * dotfiles, {@code ..}, or hidden names), capped at 255 UTF-8 bytes (the usual filesystem
     * limit). Never empty: a missing or fully stripped name becomes {@code "upload"}.
     *
     * <p>Every extension a browser or server could execute ({@code html}, {@code svg}, {@code xml},
     * {@code js}, {@code css}, {@code php}, …, any case, anywhere in the name) is made inert: its
     * dot becomes {@code _}, and {@code .bin} is appended when it was the last one:
     * {@code "page.HTML"} becomes {@code "page_HTML.bin"}, {@code "a.php.png"} becomes
     * {@code "a_php.png"}. Serve the directory as {@link StaticFiles#UNTRUSTED} regardless; the
     * rename is for whatever else serves it.
     *
     * <p>This is a sanitiser, not a uniqueness guarantee — two uploads can share a safe name.
     */
    public String safeFilename() {
        if (filename == null) return "upload";
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        // Controls and invisible format characters (bidi overrides such as U+202E, zero-width
        // spaces) go: the latter would let a name display as something it isn't.
        var sb = new StringBuilder(name.length());
        name.codePoints()
            .filter(cp -> !Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT)
            .forEach(sb::appendCodePoint);
        name = sb.toString();
        // Drop the whole leading run of dots AND whitespace, not dots then whitespace: trimming
        // them one after the other turned ". .env" into ".env" and ". .." into "..".
        int start = 0;
        while (start < name.length()
                && (name.charAt(start) == '.' || Character.isWhitespace(name.charAt(start))
                    || Character.isSpaceChar(name.charAt(start)))) {
            start++;
        }
        name = capBytes(name.substring(start).strip(), 255);
        if (name.isEmpty()) return "upload";
        // M7: executable extensions are made inert, so a trusted static mapping or a server in front
        // of the directory can't serve "x.html" as a page or run "x.php.png". After the cap, which
        // can itself expose a new final extension by cutting the name short; the result is capped
        // again ahead of a ".bin" so the limit holds.
        String neutral = UntrustedContent.neutralizeName(name);
        if (!neutral.equals(name)) {
            if (neutral.endsWith(".bin") && !name.endsWith(".bin")) {
                name = capBytes(neutral.substring(0, neutral.length() - 4), 255 - 4) + ".bin";
            } else {
                name = capBytes(neutral, 255);
            }
            if (name.isEmpty()) return "upload";
        }
        return name;
    }

    /** {@code name} cut to at most {@code max} UTF-8 bytes, never splitting a code point, stripped. */
    private static String capBytes(String name, int max) {
        int bytes = 0, end = 0;
        while (end < name.length()) {
            int cp = name.codePointAt(end);
            int n = Character.toString(cp).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + n > max) break;
            bytes += n;
            end += Character.charCount(cp);
        }
        return name.substring(0, end).strip();
    }
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
        var source = isReleased() ? null : part.newContentSource(null, 0, -1);
        if (source == null) {
            // Only when the part has already been closed — i.e. the upload outlived its request.
            throw released();
        }
        return Content.Source.asInputStream(source);
    }

    /**
     * Whether the end-of-request release has already run. A closed chunked part returns a null
     * source on its own, but a closed spilled part nulls its path and then throws a bare
     * NullPointerException from inside Jetty, so that shape is checked up front.
     */
    private boolean isReleased() {
        return part instanceof MultiPart.PathPart pathPart && pathPart.getPath() == null;
    }

    private IllegalStateException released() {
        return new IllegalStateException(
            "Uploaded file '" + filename + "' is no longer readable: its content was released "
                + "when the request finished. Save it (saveTo/transferTo/Storage.put) before "
                + "the handler returns.");
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
        if (isReleased()) throw released();
        // Part.writeTo moves a spilled file and streams a chunked one, and clears the part's
        // "temporary" flag so the end-of-request release does not delete what we just moved.
        part.writeTo(path);
    }

    /**
     * Saves the upload as {@code name} under {@code dir}, refusing any name that would land outside
     * it. Use this instead of {@code dir.resolve(name)} whenever {@code name} comes from a client
     * ({@code saveTo(dir, file.safeFilename())} is the usual call).
     *
     * <p>The check is lexical (the normalized path must stay under {@code dir}) and on real paths
     * (a symlink under {@code dir} that points elsewhere is rejected), the way static files are
     * served. {@code dir} is created if missing; a {@code name} with subdirectories such as
     * {@code "2026/a.png"} is allowed as long as it stays inside. Replaces an existing file.
     *
     * @return the path written
     * @throws IllegalArgumentException if {@code name} is empty, absolute, or escapes {@code dir}
     */
    public Path saveTo(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path target = confine(dir, name);
        saveTo(target);
        return target;
    }

    /**
     * Resolves {@code name} under {@code base} and verifies it cannot escape: lexically, then on
     * real paths. The target need not exist — the deepest existing ancestor (or the target itself,
     * which catches an existing symlink) is what gets resolved. Not atomic with the later use, like
     * any check-then-open, but it closes the symlinks that are already on disk.
     *
     * @throws IllegalArgumentException when the name is empty, malformed, or escapes {@code base}
     */
    static Path confine(Path base, String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Filename must not be empty");
        Path baseDir = base.toAbsolutePath().normalize();
        Path target;
        try {
            target = baseDir.resolve(name).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("Invalid filename", e);
        }
        if (target.equals(baseDir) || !target.startsWith(baseDir)) {
            throw new IllegalArgumentException("Filename escapes the target directory");
        }
        try {
            Path existing = target;
            while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (!existing.toRealPath().startsWith(baseDir.toRealPath())) {
                throw new IllegalArgumentException("Filename escapes the target directory");
            }
        } catch (IOException e) {
            // A dangling symlink or an unreadable base: cannot prove containment, so refuse.
            throw new IllegalArgumentException("Filename cannot be resolved inside the target directory", e);
        }
        return target;
    }
}
