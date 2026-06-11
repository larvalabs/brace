package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CliAgentsMdTest {

    private static final String DOC = "# Brace Framework Reference\n\ntest content\n";

    /** Writes a jar with (or without) the brace/BRACE-AGENTS.md entry. */
    private static Path writeJar(Path dir, String name, boolean withAgentsMd) throws IOException {
        Path jar = dir.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("com/larvalabs/brace/placeholder.txt"));
            out.write("x".getBytes());
            out.closeEntry();
            if (withAgentsMd) {
                out.putNextEntry(new JarEntry(CliAgentsMd.JAR_ENTRY));
                out.write(DOC.getBytes());
                out.closeEntry();
            }
        }
        return jar;
    }

    // --- jar extraction (the installed-toolchain path) ---

    @Test
    void extractsAgentsMdFromFrameworkJar(@TempDir Path lib) throws Exception {
        Path jar = writeJar(lib, "brace-9.9.9.jar", true);
        assertEquals(DOC, CliAgentsMd.extractAgentsMd(jar));
    }

    @Test
    void extractReturnsNullWhenJarLacksTheEntry(@TempDir Path lib) throws Exception {
        // A pre-0.1.7 framework jar: no packaged BRACE-AGENTS.md.
        Path jar = writeJar(lib, "brace-0.1.6.jar", false);
        assertNull(CliAgentsMd.extractAgentsMd(jar));
    }

    @Test
    void findBraceJarPicksTheFrameworkJarNotDepsOrSources(@TempDir Path lib) throws Exception {
        writeJar(lib, "jackson-databind-2.18.0.jar", false);
        writeJar(lib, "brace-9.9.9-sources.jar", false);
        Path framework = writeJar(lib, "brace-9.9.9.jar", true);
        assertEquals(framework, CliAgentsMd.findBraceJar(lib));
    }

    @Test
    void findBraceJarReturnsNullInEmptyDir(@TempDir Path lib) throws Exception {
        assertNull(CliAgentsMd.findBraceJar(lib));
    }

    // --- command behavior (classpath fallback: tests run from target/classes,
    //     where Maven copies BRACE-AGENTS.md to brace/BRACE-AGENTS.md) ---

    @Test
    void writesAgentsMdIntoProject(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("BRACE-AGENTS.md"), "stale copy from an old version");

        int code = CliAgentsMd.run(project, new String[]{});

        assertEquals(0, code);
        String written = Files.readString(project.resolve("BRACE-AGENTS.md"));
        assertTrue(written.contains("# Brace Framework Reference"), "expected refreshed doc, got: "
                + written.substring(0, Math.min(80, written.length())));
        assertFalse(written.contains("stale copy"));
    }

    @Test
    void stdoutFlagPrintsInsteadOfWriting(@TempDir Path project) throws Exception {
        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        int code;
        try {
            code = CliAgentsMd.run(project, new String[]{"--stdout"});
        } finally {
            System.setOut(prev);
        }
        assertEquals(0, code);
        assertTrue(bout.toString().contains("# Brace Framework Reference"));
        assertFalse(Files.exists(project.resolve("BRACE-AGENTS.md")), "--stdout must not write the file");
    }

    @Test
    void failsOutsideAProjectWithFriendlyError(@TempDir Path notAProject) throws Exception {
        var berr = new ByteArrayOutputStream();
        var prev = System.err;
        System.setErr(new PrintStream(berr));
        int code;
        try {
            code = CliAgentsMd.run(notAProject, new String[]{});
        } finally {
            System.setErr(prev);
        }
        assertEquals(1, code);
        assertTrue(berr.toString().contains("inside a Brace project"), berr.toString());
        assertFalse(Files.exists(notAProject.resolve("BRACE-AGENTS.md")));
    }
}
