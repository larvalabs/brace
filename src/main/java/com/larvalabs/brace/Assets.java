package com.larvalabs.brace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asset URL fingerprinting for cache busting.
 *
 * Resolves a static-file URL like {@code /assets/app.css} to a hashed variant like
 * {@code /assets/app.css?v=a1b2c3d4}. The hash is derived from file contents and cached
 * by (path, mtime), so unchanged files keep the same URL across restarts and CDNs don't
 * revalidate unnecessarily.
 *
 * Initialized automatically by {@link Brace#start()} from the registered
 * {@code app.staticFiles(...)} mappings.
 */
public class Assets {

    private static volatile Assets instance;

    private final List<BraceHandler.StaticFileMapping> mappings;
    private final ConcurrentHashMap<String, CachedHash> cache = new ConcurrentHashMap<>();

    private record CachedHash(long mtime, String hash) {}

    Assets(List<BraceHandler.StaticFileMapping> mappings) {
        this.mappings = mappings;
    }

    static void init(List<BraceHandler.StaticFileMapping> mappings) {
        instance = new Assets(mappings);
    }

    /**
     * Returns the URL with a content-hash query parameter appended for cache busting.
     * If the URL doesn't resolve to a managed static file, returns it unchanged.
     */
    public static String url(String urlPath) {
        if (instance == null) return urlPath;
        return instance.fingerprint(urlPath);
    }

    /** Clears the fingerprint cache (mainly for tests and dev reloading). */
    public static void clearCache() {
        if (instance != null) instance.cache.clear();
    }

    String fingerprint(String urlPath) {
        if (urlPath == null || urlPath.isEmpty()) return urlPath;
        var queryIdx = urlPath.indexOf('?');
        var clean = queryIdx >= 0 ? urlPath.substring(0, queryIdx) : urlPath;
        var file = resolve(clean);
        if (file == null) return urlPath;
        try {
            var mtime = Files.getLastModifiedTime(file).toMillis();
            var cached = cache.get(clean);
            if (cached != null && cached.mtime == mtime) {
                return clean + "?v=" + cached.hash;
            }
            var hash = hashFile(file);
            cache.put(clean, new CachedHash(mtime, hash));
            return clean + "?v=" + hash;
        } catch (IOException e) {
            return urlPath;
        }
    }

    private Path resolve(String urlPath) {
        for (var mapping : mappings) {
            var prefix = mapping.urlPrefix();
            if (!urlPath.startsWith(prefix)) continue;
            var relative = urlPath.substring(prefix.length());
            if (relative.startsWith("/")) relative = relative.substring(1);
            if (relative.isEmpty() || relative.contains("..")) return null;
            var base = Path.of(mapping.directory()).toAbsolutePath().normalize();
            var file = base.resolve(relative).normalize();
            if (!file.startsWith(base)) return null;
            if (!Files.isRegularFile(file)) return null;
            return file;
        }
        return null;
    }

    private static String hashFile(Path file) throws IOException {
        try {
            var md = MessageDigest.getInstance("MD5");
            try (InputStream in = Files.newInputStream(file)) {
                var buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            var digest = md.digest();
            var sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
