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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the concise {@code brace compile} diagnostics (H8) and the concise
 * {@code brace test} output post-processing (H7) — both pure-formatting methods are
 * exercised without invoking javac or spawning a JUnit ConsoleLauncher, plus one
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

    // --- app launch command ------------------------------------------------

    @Test
    public void devRunsAppInDevModeRunDoesNot(@TempDir Path dir) {
        List<String> dev = BuildCommands.appCommand(dir, "app.App", true);
        List<String> run = BuildCommands.appCommand(dir, "app.App", false);
        // brace dev must activate %dev. config overrides (the scaffold's H2 db)
        // and dev-only behavior; brace run is the production-style launch.
        assertTrue(dev.contains("-Dbrace.mode=dev"), dev.toString());
        assertFalse(run.contains("-Dbrace.mode=dev"), run.toString());
        assertEquals("app.App", dev.get(dev.size() - 1));
    }

    // --- H7: summarizeTestRun ---------------------------------------------

    private static final String FAILURE_OUTPUT = """

            Failures (2):
              JUnit Jupiter:HomeControllerTest:homePage()
                MethodSource [className = 'app.HomeControllerTest', methodName = 'homePage', methodParameterTypes = '']
                => org.opentest4j.AssertionFailedError: expected: <200> but was: <404>
                   org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:55)
                   org.junit.jupiter.api.AssertionUtils.failNotEqual(AssertionUtils.java:62)
                   org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:182)
                   app.HomeControllerTest.homePage(HomeControllerTest.java:18)
                   java.base/java.lang.reflect.Method.invoke(Method.java:565)
                   [...]
              JUnit Jupiter:UserTest:rejectsBlankName()
                MethodSource [className = 'app.UserTest', methodName = 'rejectsBlankName', methodParameterTypes = '']
                => java.lang.IllegalStateException: no database
                   com.larvalabs.brace.Database.query(Database.java:88)
                   app.User.validate(User.java:31)
                   app.UserTest.rejectsBlankName(UserTest.java:44)
                   [...]

            Test run finished after 742 ms
            [         3 containers found      ]
            [         0 containers skipped    ]
            [         3 containers started    ]
            [         0 containers aborted    ]
            [         3 containers successful ]
            [         0 containers failed     ]
            [        12 tests found           ]
            [         0 tests skipped         ]
            [        12 tests started         ]
            [         0 tests aborted         ]
            [        10 tests successful      ]
            [         2 tests failed          ]

            """;

    @Test
    public void summarizesFailuresToOneLineWithFirstProjectFrame() {
        List<String> lines = BuildCommands.summarizeTestRun(FAILURE_OUTPUT, Set.of("app"), 1);
        assertEquals(List.of(
                "HomeControllerTest.homePage() — AssertionFailedError: expected: <200> but was: <404> (HomeControllerTest.java:18)",
                "UserTest.rejectsBlankName() — IllegalStateException: no database (User.java:31)",
                "10 passed, 2 failed in 0.7s"), lines);
    }

    @Test
    public void successPrintsJustTheSummaryLine() {
        String output = """
                Test run finished after 503 ms
                [         3 containers found      ]
                [        12 tests found           ]
                [         0 tests skipped         ]
                [        12 tests successful      ]
                [         0 tests failed          ]
                """;
        assertEquals(List.of("12 passed, 0 failed in 0.5s"),
                BuildCommands.summarizeTestRun(output, Set.of("app"), 0));
    }

    @Test
    public void skippedCountShownOnlyWhenNonzero() {
        String output = """
                Test run finished after 1200 ms
                [         9 tests successful      ]
                [         2 tests skipped         ]
                [         0 tests failed          ]
                """;
        assertEquals(List.of("9 passed, 0 failed, 2 skipped in 1.2s"),
                BuildCommands.summarizeTestRun(output, Set.of("app"), 0));
    }

    @Test
    public void unparseableOutputReturnsNullSoCallerFallsBackToVerbatim() {
        assertNull(BuildCommands.summarizeTestRun("Error: Could not find or load main class", Set.of("app"), 1));
    }

    @Test
    public void nonzeroExitWithNoParsedFailuresFallsBackToVerbatim() {
        // Summary table parses, but the failure block doesn't (e.g. a container-level
        // error) — must not be condensed into a bare summary line on a failing run.
        String output = """
                Test run finished after 100 ms
                [         5 tests successful      ]
                [         0 tests failed          ]
                """;
        assertNull(BuildCommands.summarizeTestRun(output, Set.of("app"), 1));
    }

    @Test
    public void containerFailureShownAlongsideMethodFailure() {
        // A @BeforeAll/constructor failure carries a ClassSource (no methodName); it must
        // not vanish just because a method-level failure parsed successfully.
        String output = """
                Failures (2):
                  JUnit Jupiter:BrokenSetupTest
                    ClassSource [className = 'app.BrokenSetupTest', filePosition = null]
                    => java.lang.IllegalStateException: DB not reachable
                       app.BrokenSetupTest.beforeAll(BrokenSetupTest.java:12)
                  JUnit Jupiter:UserTest:rejectsBlankName()
                    MethodSource [className = 'app.UserTest', methodName = 'rejectsBlankName', methodParameterTypes = '']
                    => org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
                       app.UserTest.rejectsBlankName(UserTest.java:44)

                Test run finished after 320 ms
                [         1 containers failed     ]
                [         8 tests successful      ]
                [         1 tests failed          ]
                """;
        assertEquals(List.of(
                "BrokenSetupTest (class init) — IllegalStateException: DB not reachable (BrokenSetupTest.java:12)",
                "UserTest.rejectsBlankName() — AssertionFailedError: expected: <true> but was: <false> (UserTest.java:44)",
                "8 passed, 1 failed, 1 container failed in 0.3s"),
                BuildCommands.summarizeTestRun(output, Set.of("app"), 1));
    }

    @Test
    public void unrecognizedFailureEntryFallsBackToVerbatim() {
        // The Failures header declares 2 entries but only 1 parses — condensing would
        // silently drop a failure, so the caller must get null and print verbatim.
        String output = """
                Failures (2):
                  JUnit Jupiter:UserTest:rejectsBlankName()
                    MethodSource [className = 'app.UserTest', methodName = 'rejectsBlankName', methodParameterTypes = '']
                    => java.lang.AssertionError
                  JUnit Vintage:WeirdSuite
                    CompositeSource [something we do not parse]
                    => java.lang.Exception: legacy suite exploded

                Test run finished after 100 ms
                [         3 tests successful      ]
                [         1 tests failed          ]
                """;
        assertNull(BuildCommands.summarizeTestRun(output, Set.of("app"), 1));
    }

    @Test
    public void failureWithoutProjectFrameOmitsLocation() {
        String output = """
                Failures (1):
                  JUnit Jupiter:DeepTest:boom()
                    MethodSource [className = 'app.DeepTest', methodName = 'boom', methodParameterTypes = '']
                    => java.lang.OutOfMemoryError
                       java.base/java.util.Arrays.copyOf(Arrays.java:3541)
                       [...]

                Test run finished after 90 ms
                [         1 tests successful      ]
                [         1 tests failed          ]
                """;
        assertEquals(List.of(
                "DeepTest.boom() — OutOfMemoryError",
                "1 passed, 1 failed in 0.1s"),
                BuildCommands.summarizeTestRun(output, Set.of("app"), 1));
    }
}
