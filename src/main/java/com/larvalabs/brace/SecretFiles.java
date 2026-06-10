package com.larvalabs.brace;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Utility for writing secret files (keys, tokens) with owner-only permissions.
 * Uses POSIX file permissions where available; falls back gracefully on non-POSIX
 * filesystems (Windows).
 */
public class SecretFiles {

    private SecretFiles() {}

    /**
     * Write a string to a file with owner-only permissions (rw-------).
     * Uses PosixFilePermissions on POSIX filesystems; falls back to setReadable/setWritable
     * on Windows and other non-POSIX systems.
     */
    public static void writeStringWithOwnerOnlyPermissions(Path path, String content)
            throws IOException {
        // Write the file first, then set permissions.
        // Files.writeString creates the file with default umask permissions.
        Files.writeString(path, content);
        setOwnerOnlyPermissions(path);
    }

    /**
     * Set a file to owner-only permissions (rw-------).
     * Uses PosixFilePermissions on POSIX filesystems; falls back gracefully on Windows.
     */
    private static void setOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // Non-POSIX filesystem (Windows): use setReadable/setWritable
            // setReadable(false, false) removes read for everyone else (only owner retains)
            // setWritable(false, false) removes write for everyone else (only owner retains)
            try {
                path.toFile().setReadable(false, false);
                path.toFile().setReadable(true, true);   // owner can read
                path.toFile().setWritable(false, false);
                path.toFile().setWritable(true, true);   // owner can write
            } catch (Exception ignored) {
                // Fallback failed, but we tried; on non-POSIX systems, owner-only is
                // best-effort without guaranteed semantics. The file is written at least.
            }
        } catch (IOException e) {
            // If setOwnerOnlyPermissions fails, the file is already written, but with
            // potentially wrong permissions. This is a security issue but not a fatal error
            // for scaffold/CLI operations. Let it propagate so callers can handle it if needed.
            throw new RuntimeException("Failed to set owner-only permissions on " + path, e);
        }
    }
}
