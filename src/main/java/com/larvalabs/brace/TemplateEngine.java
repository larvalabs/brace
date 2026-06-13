package com.larvalabs.brace;

import gg.jte.ContentType;
import gg.jte.output.StringOutput;
import gg.jte.output.Utf8ByteOutput;
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
            // These classes already carry byte[] static content — TemplatePrecompiler set
            // binaryStaticContent at generation time (M6), so renderToBytes writes them to
            // a Utf8ByteOutput without re-encoding.
            this.engine = gg.jte.TemplateEngine.createPrecompiled(precompiledDir, ContentType.Html);
        } else {
            var codeResolver = new DirectoryCodeResolver(Path.of(templatePath));
            this.engine = gg.jte.TemplateEngine.create(codeResolver, ContentType.Html);
            // M6: emit static template content as pre-encoded UTF-8 byte[] instead of String, so
            // renderToBytes() writes it straight through with no per-render charset encoding. This
            // is a code-generation flag, so it must be set before precompileAll()/first render.
            this.engine.setBinaryStaticContent(true);
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
        // Default location: only use it if it was generated from this same template
        // directory (see TemplatePrecompiler.SOURCE_MARKER) — otherwise fall back to
        // compiling from source rather than serving mismatched templates. Compared as
        // given (not absolutized) so classes precompiled on a build host load in a
        // container with a different working directory.
        try {
            String source = Files.readString(dir.resolve(TemplatePrecompiler.SOURCE_MARKER)).trim();
            return Path.of(source).equals(Path.of(templatePath).normalize());
        } catch (IOException e) {
            return false;
        }
    }

    public String render(String template, Map<String, Object> params) {
        var output = new StringOutput();
        engine.render(template + ".jte", params, output);
        return output.toString();
    }

    /**
     * Renders straight to UTF-8 bytes (M6). With binaryStaticContent the template's static chunks are
     * written as pre-encoded byte[] and only the dynamic values are encoded, so the result is the
     * response body ready for the wire — no intermediate {@code String} and no second encode in
     * {@code writeResult}. Used for {@link View} results; {@link #render} stays for the String API.
     */
    public byte[] renderToBytes(String template, Map<String, Object> params) {
        var output = new Utf8ByteOutput();
        engine.render(template + ".jte", params, output);
        return output.toByteArray();
    }
}
