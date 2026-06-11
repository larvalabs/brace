package com.larvalabs.brace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.jar.JarFile;

/**
 * {@code brace agents-md} — refresh the project's {@code BRACE-AGENTS.md} from the
 * framework version the project is pinned to.
 *
 * <p>{@code BRACE-AGENTS.md} is written once at {@code brace new} time and then goes
 * silently stale when {@code <brace.version>} is bumped. The launcher shim already
 * resolves the pinned toolchain for this command (everything except
 * {@code new|version|help|self-update} runs against the project's pin), so the
 * brace jar in {@link BuildCommands#frameworkLibDir()} is the pinned version's jar;
 * we extract its packaged {@code /brace/BRACE-AGENTS.md} and overwrite the project
 * copy. {@code --stdout} prints the doc instead of writing it.
 */
final class CliAgentsMd {

    /** Resource path BRACE-AGENTS.md is packaged under inside the framework jar. */
    static final String JAR_ENTRY = "brace/BRACE-AGENTS.md";

    private CliAgentsMd() {}

    static int run(Path cwd, String[] args) throws Exception {
        boolean toStdout = CliCommands.hasFlag(args, "--stdout");

        String content = loadBundledAgentsMd();
        if (content == null) {
            CliOutput.printError("This framework version's jar does not contain BRACE-AGENTS.md "
                    + "(pre-0.1.7 toolchain). Bump <brace.version> in pom.xml to 0.1.7 or later "
                    + "and re-run `brace agents-md`.");
            return 1;
        }

        if (toStdout) {
            System.out.print(content);
            return 0;
        }

        if (!Files.isDirectory(cwd.resolve("src/main/java"))) {
            CliOutput.printError("Run inside a Brace project (no src/main/java) — "
                    + "or use `brace agents-md --stdout` to print the doc instead.");
            return 1;
        }

        Files.writeString(cwd.resolve("BRACE-AGENTS.md"), content);
        CliOutput.printSuccess("BRACE-AGENTS.md refreshed from Brace " + BraceVersion.get());
        return 0;
    }

    /**
     * The packaged BRACE-AGENTS.md of the framework version this Cli runs from:
     * extracted from the brace jar in the toolchain lib dir when running installed,
     * or read off the classpath when running from {@code target/classes} during
     * framework development. {@code null} when neither carries the resource.
     */
    static String loadBundledAgentsMd() throws IOException {
        Path lib = BuildCommands.frameworkLibDir();
        Path jar = lib != null ? findBraceJar(lib) : null;
        if (jar != null) return extractAgentsMd(jar);
        try (var in = CliAgentsMd.class.getResourceAsStream("/" + JAR_ENTRY)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The framework jar ({@code brace-<version>.jar}) in a toolchain lib dir, or null. */
    static Path findBraceJar(Path libDir) throws IOException {
        if (!Files.isDirectory(libDir)) return null;
        try (var s = Files.list(libDir)) {
            return s.filter(p -> p.getFileName().toString().matches("brace-\\d.*\\.jar"))
                    .filter(p -> !p.getFileName().toString().contains("-sources")
                            && !p.getFileName().toString().contains("-javadoc"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
    }

    /** The {@value #JAR_ENTRY} entry of a framework jar, or null when absent (pre-0.1.7). */
    static String extractAgentsMd(Path jar) throws IOException {
        try (var jf = new JarFile(jar.toFile())) {
            var entry = jf.getEntry(JAR_ENTRY);
            if (entry == null) return null;
            try (var in = jf.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
