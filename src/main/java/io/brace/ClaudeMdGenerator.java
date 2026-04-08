package io.brace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a minimal CLAUDE.md stub for a Brace project.
 * Kept lean because Brace's explicit main() is already self-documenting.
 */
public class ClaudeMdGenerator {

    public static String generate(Brace app) {
        var sb = new StringBuilder();

        sb.append("# Project\n\n");
        sb.append("Built with Brace — a plain Java web framework. ");
        sb.append("All routes, services, and dependencies are wired explicitly in `main()`.\n\n");

        sb.append("## Build & Run\n\n");
        sb.append("```bash\n");
        sb.append("./brace dev       # compile + run + watch for changes\n");
        sb.append("./brace test      # run all tests\n");
        sb.append("./brace test Name # run specific test class\n");
        sb.append("```\n");

        return sb.toString();
    }

    public static void write(Brace app, Path path) {
        try {
            Files.writeString(path, generate(app));
        } catch (IOException e) {
            System.err.println("Warning: could not write CLAUDE.md: " + e.getMessage());
        }
    }
}
