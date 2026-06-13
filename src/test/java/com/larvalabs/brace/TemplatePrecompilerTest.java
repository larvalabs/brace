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
        Path marker = target.resolve(TemplatePrecompiler.SOURCE_MARKER);

        // No marker at all → not usable.
        assertFalse(TemplateEngine.hasPrecompiledClasses(target, "views"));

        // Marker from a different template dir → not usable.
        Files.writeString(marker, "other-views");
        assertFalse(TemplateEngine.hasPrecompiledClasses(target, "views"));

        // Marker matching this template dir → usable. The comparison is over the
        // relative path as given, so classes precompiled on a build host match in a
        // container with a different working directory; "./views" normalizes too.
        Files.writeString(marker, "views");
        assertTrue(TemplateEngine.hasPrecompiledClasses(target, "views"));
        assertTrue(TemplateEngine.hasPrecompiledClasses(target, "./views"));

        // An absolute template path never matches a relative marker (safe fallback).
        assertFalse(TemplateEngine.hasPrecompiledClasses(target,
                dir.resolve("views").toAbsolutePath().toString()));
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
