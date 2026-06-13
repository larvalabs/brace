package com.larvalabs.brace;

import gg.jte.ContentType;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class TemplateEngine {

    private final gg.jte.TemplateEngine engine;

    public TemplateEngine(String templatePath) {
        boolean prod = "prod".equals(System.getProperty("brace.mode"));
        Path precompiledDir = precompiledDir();
        if (prod && hasPrecompiledClasses(precompiledDir, templatePath)) {
            // Loads finished template classes: no javac on first render, no per-render
            // hot-reload checks, none of the compiler's ~20-50MB metaspace footprint.
            this.engine = gg.jte.TemplateEngine.createPrecompiled(precompiledDir, ContentType.Html);
        } else {
            var codeResolver = new DirectoryCodeResolver(Path.of(templatePath));
            this.engine = gg.jte.TemplateEngine.create(codeResolver, ContentType.Html);
            if (prod) {
                // Prod without usable precompiled classes (custom template dir, non-CLI
                // launch): compile everything now, at startup, so no request pays the
                // first-render compile cost. Throws on a broken template — in prod a
                // deploy-time failure beats a 500 on first hit.
                this.engine.precompileAll();
            }
        }
    }

    private static Path precompiledDir() {
        return Path.of(System.getProperty("brace.templates.precompiled", "target/jte-classes"));
    }

    static boolean hasPrecompiledClasses(Path dir, String templatePath) {
        // Explicitly configured location (e.g. a jte-maven-plugin output dir): trust it.
        if (System.getProperty("brace.templates.precompiled") != null) {
            return Files.isDirectory(dir);
        }
        // Default location: only use it if the brace CLI generated it from this same
        // template directory (see TemplatePrecompiler.SOURCE_MARKER) — otherwise fall
        // back to compiling from source rather than serving mismatched templates.
        try {
            String source = Files.readString(dir.resolve(TemplatePrecompiler.SOURCE_MARKER)).trim();
            return Path.of(source).equals(Path.of(templatePath).toAbsolutePath().normalize());
        } catch (IOException e) {
            return false;
        }
    }

    public String render(String template, Map<String, Object> params) {
        var output = new StringOutput();
        engine.render(template + ".jte", params, output);
        return output.toString();
    }
}
