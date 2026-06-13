package com.larvalabs.brace;

import gg.jte.ContentType;
import gg.jte.resolve.DirectoryCodeResolver;
import gg.jte.runtime.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Compiles every {@code .jte} template in a directory ahead of time, so that in prod
 * mode {@link TemplateEngine} can load finished classes instead of shipping a javac
 * pipeline to production ({@code brace compile}/{@code brace run} invoke this in a
 * child JVM with the project classpath, since templates reference app classes).
 */
public final class TemplatePrecompiler {

    /**
     * Marker file inside the output directory recording which template source directory
     * the classes were generated from. {@link TemplateEngine} refuses to serve precompiled
     * classes whose source doesn't match its configured template path — a stale or
     * foreign output directory silently rendering the wrong templates would be far worse
     * than paying the eager-compile fallback.
     */
    static final String SOURCE_MARKER = ".source-templates";

    private TemplatePrecompiler() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: TemplatePrecompiler <templates-dir> <output-dir>");
            System.exit(2);
        }
        precompile(Path.of(args[0]), Path.of(args[1]));
    }

    static void precompile(Path templates, Path output) throws IOException {
        // Clean the output first: precompileAll regenerates existing templates but never
        // removes classes for deleted ones, and serving those would resurrect dead pages.
        deleteRecursively(output);
        var engine = gg.jte.TemplateEngine.create(
                new DirectoryCodeResolver(templates), output, ContentType.Html,
                null, Constants.PACKAGE_NAME_PRECOMPILED);
        engine.precompileAll();
        // Written last: a marker only exists for a complete, successful run. The path is
        // recorded as given (normalized, NOT absolutized): builds and deploys run from
        // different roots (host project dir vs container WORKDIR, Docker build stage vs
        // runtime stage), but both resolve the same project-relative "views".
        Files.writeString(output.resolve(SOURCE_MARKER), templates.normalize().toString());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
