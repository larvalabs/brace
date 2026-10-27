package com.larvalabs.brace;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which file types are safe to serve when the bytes came from a user: the shared rules behind
 * {@link StaticFiles#UNTRUSTED}, {@link Result#untrusted()}, {@link Storage#put(String, UploadedFile)},
 * {@link Storage#safeKey} and {@link UploadedFile#safeFilename()}.
 *
 * <p>Two lists, deliberately of different kinds. What may be <em>served</em> under its own type is
 * an allowlist ({@link #typeForExtension}, {@link #safeType}): anything not on it goes out as
 * {@code application/octet-stream}, so a type nobody thought of fails closed. What gets
 * <em>renamed</em> is a denylist ({@link #isActiveExtension}): renaming every unknown extension
 * would turn ordinary uploads ({@code .psd}, {@code .dwg}, {@code .epub}) into {@code .bin}, and
 * the extension only matters to servers that don't apply the allowlist, which the rename is
 * defense in depth for.
 */
final class UntrustedContent {

    private UntrustedContent() {}

    static final String OCTET_STREAM = "application/octet-stream";

    /**
     * Extensions a browser, or a server in front of the files, can execute when they are served
     * from the app's origin. Markup and anything that can carry markup or script (SVG and plain
     * XML can embed XHTML {@code <script>}; XSL runs as a stylesheet transform), script and style
     * (an uploaded {@code .js} or {@code .css} on the app's origin satisfies a
     * {@code script-src 'self'} or {@code style-src 'self'} CSP), WebAssembly, Flash, and the
     * server-side script types a PHP/ASP/JSP/SSI-enabled server in front of an upload directory
     * would run. PDF is not here: it keeps its extension and is served as an attachment by the
     * untrusted paths instead.
     */
    private static final Set<String> ACTIVE_EXTENSIONS = Set.of(
        "html", "htm", "xhtml", "xht", "shtml", "shtm", "stm", "mht", "mhtml",
        "svg", "svgz", "xml", "xsl", "xslt", "rdf",
        "js", "mjs", "cjs", "css", "wasm", "swf",
        "php", "php3", "php4", "php5", "php7", "phtml", "phar", "pht",
        "asp", "aspx", "jsp", "jspx", "cgi"
    );

    /**
     * The types an untrusted file is served (and stored in S3) under, by extension. Raster images
     * first: they are the only ones shown inline. SVG is not a raster image.
     */
    private static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("avif", "image/avif"),
        Map.entry("bmp", "image/bmp"),
        Map.entry("ico", "image/x-icon"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("txt", "text/plain; charset=utf-8"),
        Map.entry("csv", "text/csv; charset=utf-8"),
        Map.entry("json", "application/json"),
        Map.entry("zip", "application/zip"),
        Map.entry("gz", "application/gzip"),
        Map.entry("tar", "application/x-tar"),
        Map.entry("doc", "application/msword"),
        Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Map.entry("xls", "application/vnd.ms-excel"),
        Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        Map.entry("ppt", "application/vnd.ms-powerpoint"),
        Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        Map.entry("odt", "application/vnd.oasis.opendocument.text"),
        Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
        Map.entry("odp", "application/vnd.oasis.opendocument.presentation"),
        Map.entry("rtf", "application/rtf"),
        Map.entry("mp3", "audio/mpeg"),
        Map.entry("m4a", "audio/mp4"),
        Map.entry("wav", "audio/wav"),
        Map.entry("ogg", "audio/ogg"),
        Map.entry("mp4", "video/mp4"),
        Map.entry("webm", "video/webm"),
        Map.entry("mov", "video/quicktime")
    );

    private static final Set<String> RASTER_IMAGE_TYPES = Set.of(
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp",
        "image/x-icon", "image/vnd.microsoft.icon"
    );

    /**
     * Media types (without parameters) an untrusted response may keep, besides raster images,
     * {@code audio/*}, {@code video/*} and the OOXML/ODF families. Every TYPES value is covered.
     */
    private static final Set<String> PASSIVE_TYPES = Set.of(
        "application/pdf", "text/plain", "text/csv", "application/json", "application/zip",
        "application/gzip", "application/x-tar", "application/msword", "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint", "application/rtf", OCTET_STREAM
    );

    /** True when a file with this extension (no dot, any case) can execute where it is served. */
    static boolean isActiveExtension(String ext) {
        return ext != null && ACTIVE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }

    /** The final extension of a file name or path, lowercased, or "" when there is none. */
    static String extensionOf(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The allowlisted type for a file name or key's final extension; octet-stream otherwise. */
    static String typeForName(String name) {
        return TYPES.getOrDefault(extensionOf(name), OCTET_STREAM);
    }

    /**
     * {@code contentType} if it is on the allowlist, else octet-stream. The comparison is on the
     * bare media type, and a value with a comma is refused outright: browsers take the last of
     * a comma-separated list, so {@code "text/plain, text/html"} would render as HTML.
     */
    static String safeType(String contentType) {
        if (contentType == null || contentType.indexOf(',') >= 0) return OCTET_STREAM;
        String base = baseType(contentType);
        boolean allowed = RASTER_IMAGE_TYPES.contains(base) || PASSIVE_TYPES.contains(base)
            || base.startsWith("audio/") || base.startsWith("video/")
            || base.startsWith("application/vnd.openxmlformats-officedocument.")
            || base.startsWith("application/vnd.oasis.opendocument.");
        return allowed ? contentType : OCTET_STREAM;
    }

    /** True for a raster image type: the only kind an untrusted response shows inline. */
    static boolean isRasterImage(String contentType) {
        return contentType != null && RASTER_IMAGE_TYPES.contains(baseType(contentType));
    }

    private static String baseType(String contentType) {
        int semi = contentType.indexOf(';');
        String base = semi < 0 ? contentType : contentType.substring(0, semi);
        return base.strip().toLowerCase(Locale.ROOT);
    }
}
