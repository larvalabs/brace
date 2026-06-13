package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplatePrecompilerTest {

    private static final String VIEWS = "src/test/resources/views";

    @Test
    void precompiledClassesRenderWithoutTemplateSources(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("jte-classes");
        TemplatePrecompiler.precompile(Path.of(VIEWS), target);
        assertTrue(Files.exists(target.resolve(TemplatePrecompiler.SOURCE_MARKER)));

        // Point the engine at an EMPTY template dir: if rendering still works, the
        // output can only have come from the precompiled classes.
        Path emptyViews = Files.createDirectory(dir.resolve("empty-views"));
        System.setProperty("brace.mode", "prod");
        System.setProperty("brace.templates.precompiled", target.toString());
        try {
            var engine = new TemplateEngine(emptyViews.toString());
            assertTrue(engine.render("hello", Map.of()).contains("Hello from JTE!"));
            assertTrue(engine.render("params", Map.of("name", "Alice", "count", 42))
                    .contains("Hello Alice"));
        } finally {
            System.clearProperty("brace.mode");
            System.clearProperty("brace.templates.precompiled");
        }
    }

    @Test
    void prodWithoutPrecompiledClassesEagerCompilesAndRenders() {
        System.setProperty("brace.mode", "prod");
        try {
            var engine = new TemplateEngine(VIEWS);
            assertTrue(engine.render("hello", Map.of()).contains("Hello from JTE!"));
        } finally {
            System.clearProperty("brace.mode");
        }
    }

    @Test
    void markerMustMatchTemplateDirAtDefaultLocation(@TempDir Path dir) throws Exception {
        assertNull(System.getProperty("brace.templates.precompiled"), "test assumes default location");
        Path target = Files.createDirectory(dir.resolve("jte-classes"));

        // No marker at all → not usable.
        assertFalse(TemplateEngine.hasPrecompiledClasses(target, VIEWS));

        // Marker from a different template dir → not usable.
        Files.writeString(target.resolve(TemplatePrecompiler.SOURCE_MARKER),
                dir.resolve("other-views").toAbsolutePath().normalize().toString());
        assertFalse(TemplateEngine.hasPrecompiledClasses(target, VIEWS));

        // Marker matching this template dir → usable.
        Files.writeString(target.resolve(TemplatePrecompiler.SOURCE_MARKER),
                Path.of(VIEWS).toAbsolutePath().normalize().toString());
        assertTrue(TemplateEngine.hasPrecompiledClasses(target, VIEWS));
    }

    @Test
    void precompileCleansStaleClasses(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("jte-classes");
        Files.createDirectories(target);
        Path stale = target.resolve("stale.class");
        Files.writeString(stale, "old");
        TemplatePrecompiler.precompile(Path.of(VIEWS), target);
        assertFalse(Files.exists(stale), "previous output is removed, not merged");
    }
}
