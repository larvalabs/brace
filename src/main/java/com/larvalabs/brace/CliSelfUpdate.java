package com.larvalabs.brace;

import java.nio.file.*;

/**
 * {@code brace self-update [version]} — install a framework toolchain and point
 * the launcher at it. With no argument, updates to the latest released version;
 * with an explicit version, switches to that one (allowing downgrades/pins).
 *
 * <p>Only meaningful for an installed launcher, whose layout is:
 * <pre>
 *   ~/.brace/bin/brace            -> toolchains/&lt;v&gt;/bin/brace   (symlink)
 *   ~/.brace/toolchains/&lt;v&gt;/      (an unpacked dist zip)
 * </pre>
 * The currently-running toolchain is {@code -Dbrace.home} =
 * {@code ~/.brace/toolchains/<current>}; self-update fetches the target into a
 * sibling directory and re-points {@code ~/.brace/bin/brace}.
 */
final class CliSelfUpdate {

    private CliSelfUpdate() {}

    static int run(String[] args) {
        String home = System.getProperty("brace.home");
        if (home == null || home.isBlank()) {
            CliOutput.printError("Cannot self-update: brace.home is unset (run via the installed launcher).");
            return 1;
        }
        Path toolchainDir = Path.of(home);
        Path toolchainsRoot = toolchainDir.getParent();
        if (toolchainsRoot == null || !"toolchains".equals(toolchainsRoot.getFileName().toString())) {
            CliOutput.printError("self-update only works for an installed brace "
                    + "(expected ~/.brace/toolchains/<version>, got " + home + ").");
            CliOutput.printError("Re-run the install script to bootstrap, or use a project-pinned version.");
            return 1;
        }
        Path installRoot = toolchainsRoot.getParent();
        Path binLink = installRoot.resolve("bin").resolve("brace");

        try {
            String current = BraceVersion.get();
            String target = (args.length > 0 && !args[0].isBlank())
                    ? Toolchains.stripV(args[0])
                    : resolveLatest();
            if (target == null) return 1;

            if (target.equals(current) && Files.isDirectory(toolchainsRoot.resolve(target).resolve("lib"))) {
                CliOutput.printSuccess("Already on brace " + current + " (latest).");
                return 0;
            }

            Path dest = toolchainsRoot.resolve(target);
            if (!Files.isDirectory(dest.resolve("lib"))) {
                CliOutput.printInfo("Downloading brace " + target + " ...");
                Toolchains.install(target, dest);
            }

            relink(binLink, dest.resolve("bin").resolve("brace"));
            CliOutput.printSuccess("Updated brace " + current + " → " + target);
            CliOutput.printInfo("Launcher now points at " + dest);
            return 0;
        } catch (Exception e) {
            CliOutput.printError("self-update failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return 1;
        }
    }

    private static String resolveLatest() {
        try {
            return Toolchains.latestVersion();
        } catch (Exception e) {
            CliOutput.printError("Could not determine the latest version: "
                    + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return null;
        }
    }

    private static void relink(Path link, Path target) throws Exception {
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, target);
    }
}
