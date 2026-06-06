package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the "released framework migrations are immutable" rule.
 *
 * <p>The framework ships its own Flyway migrations inside the jar and tracks them in a separate
 * history table ({@code flyway_brace_history}). Flyway records a checksum of each migration when it
 * applies it and re-validates on every startup. So editing an already-shipped {@code V*.sql} file —
 * even a cosmetic change — desyncs that recorded checksum from the file and aborts the boot of every
 * deployment that already applied it. (This is exactly what happened across 0.1.3 -> 0.1.4, when
 * V1-V3 gained {@code IF NOT EXISTS}.) End users can't fix it: they neither wrote nor can edit these
 * files. The right place to catch the mistake is here, in Brace's own build, not at a downstream boot.
 *
 * <p>So: released migrations are append-only. To change behavior, add a new {@code V*} migration;
 * never touch an old one. Each released file's hash is frozen in
 * {@code src/test/resources/framework-migrations.lock}; this test fails if a locked file's bytes
 * change, if a locked file disappears, or if a new migration hasn't been added to the lock.
 *
 * <p>We deliberately do NOT auto-{@code repair()} the framework history at runtime: that would
 * silence this whole class of mismatch (and mask version skew) in exchange for nothing, since
 * preventing the edit in the first place is the actual fix.
 */
class FrameworkMigrationsFrozenTest {

    private static final List<Path> MIGRATION_DIRS = List.of(
            Path.of("src/main/resources/brace/db/migration"),
            Path.of("src/main/resources/brace/db/migration_pg"));

    private static final Path LOCK_FILE = Path.of("src/test/resources/framework-migrations.lock");

    @Test
    void releasedMigrationsAreFrozen() {
        Map<String, String> locked = readLock();
        Map<String, String> actual = hashMigrations();

        var errors = new ArrayList<String>();

        // 1. Every locked migration must still exist with its original bytes.
        locked.forEach((name, expected) -> {
            String found = actual.get(name);
            if (found == null) {
                errors.add("Released migration deleted: " + name
                        + " is in the lock but no longer on disk. Released migrations are immutable; "
                        + "restore it. To remove behavior, add a new migration that undoes it.");
            } else if (!found.equals(expected)) {
                errors.add("Released migration edited: " + name
                        + " changed since release. Revert it and add a NEW V* migration instead — "
                        + "editing a shipped migration breaks Flyway checksum validation on every "
                        + "deployment that already applied it.\n    locked:  " + expected
                        + "\n    on disk: " + found);
            }
        });

        // 2. Any new migration must be recorded in the lock (a deliberate, reviewable addition).
        actual.forEach((name, hash) -> {
            if (!locked.containsKey(name)) {
                errors.add("New migration not in lock: add this line to " + LOCK_FILE + "\n    "
                        + name + "=" + hash);
            }
        });

        if (!errors.isEmpty()) {
            fail("Framework migration immutability check failed:\n\n" + String.join("\n\n", errors));
        }
    }

    private Map<String, String> hashMigrations() {
        var hashes = new TreeMap<String, String>();
        for (Path dir : MIGRATION_DIRS) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                        .forEach(p -> hashes.put(p.getFileName().toString(), sha256(p)));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to list " + dir, e);
            }
        }
        return hashes;
    }

    private Map<String, String> readLock() {
        var locked = new TreeMap<String, String>();
        List<String> lines;
        try {
            lines = Files.readAllLines(LOCK_FILE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + LOCK_FILE, e);
        }
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                throw new IllegalStateException("Malformed lock line (expected name=hash): " + line);
            }
            locked.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
        }
        return locked;
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            var sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash " + file, e);
        }
    }
}
