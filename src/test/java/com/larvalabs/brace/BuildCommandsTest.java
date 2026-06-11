package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the concise {@code brace compile} diagnostics (H8) — the pure
 * formatting/dedupe method is exercised without invoking javac, plus one
 * real-javac test through {@link BuildCommands#compile}.
 */
public class BuildCommandsTest {

    // --- H8: formatDiagnostics ------------------------------------------

    private static Diagnostic<JavaFileObject> diag(Diagnostic.Kind kind, String path, long line, String message) {
        JavaFileObject src = path == null ? null
                : new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
                    @Override public String getName() { return path; }
                };
        return new Diagnostic<>() {
            @Override public Kind getKind() { return kind; }
            @Override public JavaFileObject getSource() { return src; }
            @Override public long getPosition() { return NOPOS; }
            @Override public long getStartPosition() { return NOPOS; }
            @Override public long getEndPosition() { return NOPOS; }
            @Override public long getLineNumber() { return line; }
            @Override public long getColumnNumber() { return NOPOS; }
            @Override public String getCode() { return null; }
            @Override public String getMessage(Locale locale) { return message; }
        };
    }

    @Test
    public void oneLinePerDiagnosticNoSnippetNoCaret() {
        List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
        diags.add(diag(Diagnostic.Kind.ERROR, "src/main/java/app/Foo.java", 12,
                "cannot find symbol\n  symbol:   method renamed()\n  location: class Foo"));
        List<String> lines = BuildCommands.formatDiagnostics(diags);
        assertEquals(List.of("src/main/java/app/Foo.java:12: error: cannot find symbol"), lines);
    }

    @Test
    public void dedupesByKindAndFirstMessageLineAcrossFiles() {
        List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
        diags.add(diag(Diagnostic.Kind.ERROR, "src/main/java/app/Foo.java", 12, "cannot find symbol"));
        diags.add(diag(Diagnostic.Kind.ERROR, "src/main/java/app/Bar.java", 7, "cannot find symbol"));
        diags.add(diag(Diagnostic.Kind.ERROR, "src/main/java/app/Baz.java", 9, "cannot find symbol"));
        List<String> lines = BuildCommands.formatDiagnostics(diags);
        assertEquals(List.of(
                "src/main/java/app/Foo.java:12: error: cannot find symbol",
                "  (+2 more at Bar.java:7, Baz.java:9)"), lines);
    }

    @Test
    public void errorsSortBeforeWarnings() {
        List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
        diags.add(diag(Diagnostic.Kind.WARNING, "A.java", 1, "deprecated method"));
        diags.add(diag(Diagnostic.Kind.ERROR, "B.java", 2, "incompatible types"));
        List<String> lines = BuildCommands.formatDiagnostics(diags);
        assertEquals(List.of(
                "B.java:2: error: incompatible types",
                "A.java:1: warning: deprecated method"), lines);
    }

    @Test
    public void extraLocationsCappedAtFive() {
        List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            diags.add(diag(Diagnostic.Kind.ERROR, "F" + i + ".java", i, "cannot find symbol"));
        }
        List<String> lines = BuildCommands.formatDiagnostics(diags);
        assertEquals(2, lines.size());
        assertEquals("  (+7 more at F2.java:2, F3.java:3, F4.java:4, F5.java:5, F6.java:6, ...)", lines.get(1));
    }

    @Test
    public void totalOutputCappedAtTwentyFiveWithAndNMore() {
        List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            diags.add(diag(Diagnostic.Kind.ERROR, "F" + i + ".java", i, "distinct message " + i));
        }
        List<String> lines = BuildCommands.formatDiagnostics(diags);
        assertEquals(26, lines.size());   // 25 diagnostics + trailing "... and N more"
        assertEquals("... and 5 more", lines.get(25));
    }

    @Test
    public void diagnosticWithoutSourceHasNoLocationPrefix() {
        List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
        diags.add(diag(Diagnostic.Kind.ERROR, null, Diagnostic.NOPOS, "invalid flag: -bogus"));
        assertEquals(List.of("error: invalid flag: -bogus"), BuildCommands.formatDiagnostics(diags));
    }

    // --- H8: real javac through compile() --------------------------------

    @Test
    public void realCompileEmitsConciseDiagnostics(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/main/java/app");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Broken.java"),
                "package app;\nclass Broken {\n  void f() { missing(); }\n}\n");

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        PrintStream prevOut = System.out, prevErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
        int rc;
        try {
            rc = BuildCommands.compile(dir);
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        assertEquals(1, rc);
        String stderr = err.toString();
        assertTrue(stderr.contains("Broken.java:3: error: cannot find symbol"), stderr);
        assertFalse(stderr.contains("^"), "no caret expected:\n" + stderr);
        assertFalse(stderr.contains("missing();"), "no source snippet expected:\n" + stderr);
        assertTrue(stderr.contains("✗ Compilation failed: 1 error, 0 warnings"), stderr);
    }
}
