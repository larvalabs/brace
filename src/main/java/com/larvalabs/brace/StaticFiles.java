package com.larvalabs.brace;

/**
 * How {@link Brace#staticFiles(String, String, StaticFiles)} serves a directory.
 *
 * <pre>{@code
 * app.staticFiles("/assets", "public")                                         // your own files
 *    .staticFiles("/uploads", "/var/app/uploads", StaticFiles.UNTRUSTED);       // users' files
 * }</pre>
 */
public enum StaticFiles {

    /**
     * Files you shipped: served inline under the type their extension implies, so {@code .html},
     * {@code .js} and {@code .svg} work as pages, scripts and images. The default. Never point it
     * at a directory users can write to.
     */
    TRUSTED,

    /**
     * Files users uploaded. Nothing can run on the app's origin:
     * <ul>
     *   <li>the type comes from an allowlist (raster images, PDF, plain text, CSV, JSON, office
     *       documents, archives, audio, video); every other extension, {@code .html}, {@code .svg}
     *       and {@code .js} included, is served as {@code application/octet-stream};</li>
     *   <li>everything except a raster image (png, jpeg, gif, webp, avif, bmp, ico) is sent with
     *       {@code Content-Disposition: attachment}, so navigating to it downloads it;</li>
     *   <li>every response carries {@code Content-Security-Policy: sandbox} and
     *       {@code X-Content-Type-Options: nosniff}.</li>
     * </ul>
     * Images still display inline and in {@code <img>}; audio and video still play in
     * {@code <audio>}/{@code <video>}, which ignore {@code Content-Disposition}. The same rules
     * apply to a handler's response through {@link Result#untrusted()}.
     *
     * <p>Keep the directory outside every {@link #TRUSTED} mapping: a file reachable through both
     * would be served trusted by the other one. {@code staticFiles} refuses that registration.
     */
    UNTRUSTED
}
