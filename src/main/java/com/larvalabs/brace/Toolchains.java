package com.larvalabs.brace;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and unpacks framework toolchains (the distribution zip published per
 * release). Shared by {@code brace self-update} and any other command that needs
 * to materialise a specific framework version locally.
 *
 * <p>A toolchain directory mirrors the dist zip: it contains {@code lib/} (the
 * framework jars), {@code bin/brace} (the launcher) and the docs. The zip unpacks
 * to a top-level {@code brace-<version>/} directory.
 *
 * <p>URLs are resolved against configurable bases so the same code works against
 * GitHub Releases in production and a local {@code file://} tree in tests:
 * <ul>
 *   <li>{@code BRACE_RELEASE_BASE} — base for dist zips; the zip for version
 *       {@code X} is {@code <base>/vX/brace-X.zip}</li>
 *   <li>{@code BRACE_LATEST_URL} — returns release metadata whose {@code tag_name}
 *       names the latest version (the GitHub "latest release" API by default)</li>
 * </ul>
 */
final class Toolchains {

    static final String DEFAULT_RELEASE_BASE =
            "https://github.com/larvalabs/brace/releases/download";
    static final String DEFAULT_LATEST_URL =
            "https://api.github.com/repos/larvalabs/brace/releases/latest";

    private Toolchains() {}

    static String releaseBase() {
        return envOr("BRACE_RELEASE_BASE", DEFAULT_RELEASE_BASE);
    }

    static String latestUrl() {
        return envOr("BRACE_LATEST_URL", DEFAULT_LATEST_URL);
    }

    /** The latest released version (tag_name with any leading "v" stripped). */
    static String latestVersion() throws Exception {
        byte[] body = fetch(latestUrl());
        var node = Json.mapper().readTree(body);
        var tag = node.get("tag_name");
        if (tag == null || tag.asText().isBlank()) {
            throw new IllegalStateException("No tag_name in release metadata from " + latestUrl());
        }
        return stripV(tag.asText());
    }

    /**
     * Download the dist zip for {@code version} and unpack it so that
     * {@code destDir} directly contains {@code lib/}, {@code bin/}, etc.
     * Writes atomically: unpacks to a temp dir, then moves into place.
     */
    static void install(String version, Path destDir) throws Exception {
        String url = releaseBase() + "/v" + version + "/brace-" + version + ".zip";
        byte[] zip = fetch(url);

        Path staging = Files.createTempDirectory("brace-tc-");
        try {
            unzip(zip, staging);
            // The zip carries a single top-level brace-<version>/ directory.
            Path top = staging.resolve("brace-" + version);
            if (!Files.isDirectory(top)) {
                throw new IllegalStateException(
                        "Unexpected archive layout: missing brace-" + version + "/ in " + url);
            }
            Files.createDirectories(destDir.getParent());
            deleteRecursively(destDir);
            try {
                Files.move(top, destDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                // ATOMIC_MOVE can fail across filesystems (temp dir vs home); fall back.
                Files.move(top, destDir);
            }
            // ZipInputStream drops the unix executable bit, so the launcher comes out
            // non-executable. Restore it explicitly (it's the toolchain's only exe).
            makeExecutable(destDir.resolve("bin").resolve("brace"));
        } finally {
            deleteRecursively(staging);
        }
    }

    private static void makeExecutable(Path file) {
        if (!Files.exists(file)) return;
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (Exception notPosix) {
            file.toFile().setExecutable(true, false);
        }
    }

    // --- transport ---

    /** GET the bytes at {@code url}; supports http(s) and file:// (the latter for tests). */
    private static byte[] fetch(String url) throws Exception {
        if (url.startsWith("file:")) {
            return Files.readAllBytes(Path.of(URI.create(url)));
        }
        var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)   // GitHub asset URLs 302 to a CDN
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        var req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "brace-cli")             // GitHub API rejects requests without one
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " fetching " + url);
        }
        return resp.body();
    }

    // --- zip ---

    private static void unzip(byte[] zip, Path target) throws Exception {
        try (var zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) {           // zip-slip guard
                    throw new IllegalStateException("Refusing entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // --- helpers ---

    static String stripV(String v) {
        return v.startsWith("v") ? v.substring(1) : v;
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
        }
    }
}
