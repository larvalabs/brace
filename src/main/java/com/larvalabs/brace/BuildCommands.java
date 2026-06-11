package com.larvalabs.brace;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Project build & run commands: {@code compile}, {@code run}, {@code dev},
 * {@code test}, {@code deps}. These used to live in the {@code bin/brace} bash
 * launcher; they were moved into the framework jar so that they run against the
 * project's <em>pinned</em> framework version (the jar this {@code Cli} is loaded
 * from) rather than whatever version the globally-installed launcher ships.
 *
 * <p>The framework jars come from {@code -Dbrace.home/lib} (set by the launcher
 * shim) or, as a fallback for running straight from a jar, the directory the
 * running jar sits in. The app's own dependencies come from the project's
 * {@code lib/} (populated by {@code brace deps}), and compiled output from
 * {@code target/classes}.
 */
final class BuildCommands {

    private BuildCommands() {}

    // --- compile ---------------------------------------------------------

    static int compile(Path cwd) throws Exception {
        Path srcMain = cwd.resolve("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            CliOutput.printError("No src/main/java directory found.");
            return 1;
        }
        List<String> sources = findJavaFiles(srcMain);
        if (sources.isEmpty()) {
            CliOutput.printError("No Java sources found under src/main/java");
            return 1;
        }
        Path out = cwd.resolve("target/classes");
        Files.createDirectories(out);
        CliOutput.printInfo("Compiling...");
        int rc = javac(out, projectClasspath(cwd), sources);
        if (rc == 0) CliOutput.printSuccess("Compiled");
        return rc;
    }

    private static int compileTests(Path cwd) throws Exception {
        Path srcTest = cwd.resolve("src/test/java");
        if (!Files.isDirectory(srcTest)) return 0;
        List<String> sources = findJavaFiles(srcTest);
        if (sources.isEmpty()) return 0;
        Path out = cwd.resolve("target/test-classes");
        Files.createDirectories(out);
        CliOutput.printInfo("Compiling tests...");
        int rc = javac(out, testClasspath(cwd), sources);
        if (rc == 0) CliOutput.printSuccess("Tests compiled");
        return rc;
    }

    private static int javac(Path outDir, String classpath, List<String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            CliOutput.printError("No system Java compiler found — run brace with a JDK, not a JRE.");
            return 1;
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromStrings(sources);
            List<String> options = List.of("-d", outDir.toString(), "-cp", classpath);
            boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            List<Diagnostic<? extends JavaFileObject>> diags = diagnostics.getDiagnostics();
            for (String line : formatDiagnostics(diags)) System.err.println(line);
            if (!ok) {
                long errors = diags.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
                long warnings = diags.stream().filter(BuildCommands::isWarning).count();
                CliOutput.printError("Compilation failed: " + errors + " error" + (errors == 1 ? "" : "s")
                        + ", " + warnings + " warning" + (warnings == 1 ? "" : "s"));
                return 1;
            }
            return 0;
        }
    }

    private static boolean isWarning(Diagnostic<?> d) {
        return d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING;
    }

    /** Max deduped diagnostics printed before the trailing {@code ... and N more} line. */
    private static final int MAX_DIAGNOSTICS = 25;
    /** Max extra locations listed in a {@code (+N more at ...)} suffix. */
    private static final int MAX_EXTRA_LOCATIONS = 5;

    /**
     * Condenses javac diagnostics into one line each — {@code path:line: error: message},
     * no source snippet, no caret. Diagnostics with the same kind and first message line
     * are deduped across files: the first occurrence is printed, the rest become a
     * {@code (+N more at Foo.java:12, ...)} suffix. Errors sort before warnings, and the
     * total is capped at {@link #MAX_DIAGNOSTICS} with a final {@code ... and N more} line.
     */
    static List<String> formatDiagnostics(List<Diagnostic<? extends JavaFileObject>> diags) {
        // Group by (kind, first line of message), preserving first-seen order within each rank.
        record Group(Diagnostic<? extends JavaFileObject> first, List<String> moreLocations) {}
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Diagnostic<? extends JavaFileObject> d : diags) {
            String key = d.getKind() + "|" + firstLine(message(d));
            Group g = groups.get(key);
            if (g == null) groups.put(key, new Group(d, new ArrayList<>()));
            else g.moreLocations().add(shortLocation(d));
        }
        List<Group> ordered = new ArrayList<>(groups.values());
        ordered.sort((a, b) -> Integer.compare(rank(a.first().getKind()), rank(b.first().getKind())));

        List<String> lines = new ArrayList<>();
        int printed = 0;
        int suppressed = 0;
        for (Group g : ordered) {
            if (printed >= MAX_DIAGNOSTICS) {
                suppressed += 1 + g.moreLocations().size();
                continue;
            }
            Diagnostic<? extends JavaFileObject> d = g.first();
            String location = d.getSource() != null && d.getLineNumber() != Diagnostic.NOPOS
                    ? d.getSource().getName() + ":" + d.getLineNumber() + ": "
                    : "";
            lines.add(location + label(d.getKind()) + ": " + firstLine(message(d)));
            printed++;
            if (!g.moreLocations().isEmpty()) {
                List<String> locs = g.moreLocations();
                String shown = String.join(", ", locs.subList(0, Math.min(locs.size(), MAX_EXTRA_LOCATIONS)));
                if (locs.size() > MAX_EXTRA_LOCATIONS) shown += ", ...";
                lines.add("  (+" + locs.size() + " more at " + shown + ")");
            }
        }
        if (suppressed > 0) lines.add("... and " + suppressed + " more");
        return lines;
    }

    private static String message(Diagnostic<?> d) {
        String m = d.getMessage(null);
        return m == null ? "" : m;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).strip();
    }

    private static String shortLocation(Diagnostic<? extends JavaFileObject> d) {
        if (d.getSource() == null) return "<unknown>";
        String name = d.getSource().getName();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(File.separatorChar));
        if (slash >= 0) name = name.substring(slash + 1);
        return d.getLineNumber() != Diagnostic.NOPOS ? name + ":" + d.getLineNumber() : name;
    }

    private static int rank(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> 0;
            case WARNING, MANDATORY_WARNING -> 1;
            default -> 2;   // NOTE, OTHER
        };
    }

    private static String label(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> "error";
            case WARNING, MANDATORY_WARNING -> "warning";
            case NOTE -> "note";
            default -> "info";
        };
    }

    // --- run -------------------------------------------------------------

    static int run(Path cwd) throws Exception {
        if (compile(cwd) != 0) return 1;
        String mainClass = findMainClass(cwd);
        CliOutput.printInfo("Starting " + mainClass);
        Process app = new ProcessBuilder("java", "-cp", projectClasspath(cwd), mainClass)
                .directory(cwd.toFile())
                .inheritIO()
                .start();
        // Unlike the old bash launcher (which exec'd the app, so a signal hit it
        // directly), we run the app as a child and wait. Tear it down if this
        // process is terminated, otherwise SIGTERM here would orphan the app JVM.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopApp(app)));
        return app.waitFor();
    }

    // --- dev (compile + run + watch) -------------------------------------

    static int dev(Path cwd) throws Exception {
        if (compile(cwd) != 0) return 1;
        String mainClass = findMainClass(cwd);
        CliOutput.printInfo("Main class: " + mainClass);

        final Process[] app = { startApp(cwd, mainClass) };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopApp(app[0])));

        CliOutput.printInfo("Watching src/ for changes...");
        Path src = cwd.resolve("src");
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            registerAll(src, ws);
            for (;;) {
                WatchKey key = ws.take();          // block until the first event
                boolean javaChanged = false;
                // Coalesce a burst of events into one rebuild: drain the current
                // key, then keep polling within a short quiet window.
                do {
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        if (ev.kind() == OVERFLOW) { javaChanged = true; continue; }
                        Path dir = (Path) key.watchable();
                        Path changed = dir.resolve((Path) ev.context());
                        if (ev.kind() == ENTRY_CREATE && Files.isDirectory(changed)) {
                            registerAll(changed, ws);   // newly created subdirectory
                        }
                        if (changed.toString().endsWith(".java")) javaChanged = true;
                    }
                    key.reset();
                    key = ws.poll(300, TimeUnit.MILLISECONDS);
                } while (key != null);

                if (javaChanged) {
                    System.out.println();
                    CliOutput.printInfo("Change detected — recompiling...");
                    stopApp(app[0]);
                    if (compile(cwd) == 0) {
                        app[0] = startApp(cwd, mainClass);
                    } else {
                        CliOutput.printError("Compilation failed — waiting for next change...");
                    }
                }
            }
        }
    }

    private static Process startApp(Path cwd, String mainClass) throws IOException {
        Process p = new ProcessBuilder("java", "-cp", projectClasspath(cwd), mainClass)
                .directory(cwd.toFile())
                .inheritIO()
                .start();
        CliOutput.printSuccess("Started (PID " + p.pid() + ")");
        return p;
    }

    private static void stopApp(Process p) {
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void registerAll(Path start, WatchService ws) throws IOException {
        if (!Files.isDirectory(start)) return;
        List<Path> dirs = new ArrayList<>();
        try (var s = Files.walk(start)) {
            s.filter(Files::isDirectory).forEach(dirs::add);
        }
        for (Path dir : dirs) {
            dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        }
    }

    // --- test ------------------------------------------------------------

    static int test(Path cwd, String[] args) throws Exception {
        boolean verbose = false, quiet = false;
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            switch (a) {
                case "--verbose" -> verbose = true;
                case "--quiet" -> quiet = true;
                default -> rest.add(a);
            }
        }
        // Concise mode when stdout isn't a TTY (agents, pipes, CI) or on --quiet:
        // summary-only ConsoleLauncher output, post-processed to one line per
        // failure. --verbose forces today's full passthrough in either mode.
        boolean concise = !verbose && (quiet || !CliOutput.stdoutIsTty());

        if (compile(cwd) != 0) return 1;
        if (compileTests(cwd) != 0) return 1;
        if (findJunitJar() == null) {
            CliOutput.printError("junit-platform-console-standalone not found in " + frameworkLibDir());
            return 1;
        }
        CliOutput.printInfo("Running tests...");
        // Launch the console runner via -cp (not `java -jar`), so the full test
        // classpath lands on java.class.path. The junit-platform-console-standalone
        // jar is already on testClasspath() (it lives in the framework lib dir), so
        // ConsoleLauncher resolves from there. This matters because JTE compiles
        // templates on the fly using java.class.path — under `java -jar` the app and
        // jte classes are hidden, so any view-rendering test fails with a 500.
        List<String> cmd = new ArrayList<>(List.of(
                "java", "-cp", testClasspath(cwd),
                "org.junit.platform.console.ConsoleLauncher", "execute"));
        if (!rest.isEmpty() && !rest.get(0).startsWith("-")) {
            cmd.add("--select-class");
            cmd.add(rest.get(0));
        } else {
            cmd.add("--scan-classpath");
            cmd.add(cwd.resolve("target/test-classes").toString());
        }
        cmd.add("--disable-banner");
        if (!concise) {
            return new ProcessBuilder(cmd).directory(cwd.toFile()).inheritIO().start().waitFor();
        }

        cmd.add("--details=summary");
        cmd.add("--disable-ansi-colors");
        Process p = new ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        List<String> lines = summarizeTestRun(output, projectPackages(cwd), rc);
        if (lines == null) {
            // Parsing failed — never swallow output.
            System.out.print(output);
        } else {
            for (String line : lines) System.out.println(line);
        }
        return rc;
    }

    /**
     * Condenses captured ConsoleLauncher {@code --details=summary} output: one line per
     * failed test — {@code Class.method() — ExceptionType: message (File.java:NN)}, the
     * location being the first stack frame in one of the project's own packages — then a
     * final {@code N passed, M failed in X.Xs} line. Returns {@code null} when the output
     * doesn't parse (caller falls back to printing it verbatim).
     */
    static List<String> summarizeTestRun(String output, java.util.Set<String> projectPackages, int exitCode) {
        var successful = matchLong(output, "\\[\\s*(\\d+) tests successful\\s*\\]");
        var failed = matchLong(output, "\\[\\s*(\\d+) tests failed\\s*\\]");
        var skipped = matchLong(output, "\\[\\s*(\\d+) tests skipped\\s*\\]");
        var millis = matchLong(output, "Test run finished after (\\d+) ms");
        if (successful == null || failed == null) return null;   // summary table missing

        List<String> lines = new ArrayList<>(parseFailures(output, projectPackages));
        // A nonzero exit with no parseable failure block (e.g. a container-level error
        // we don't recognise) must not be condensed into a bare summary line.
        if (exitCode != 0 && lines.isEmpty()) return null;

        StringBuilder summary = new StringBuilder()
                .append(successful).append(" passed, ").append(failed).append(" failed");
        if (skipped != null && skipped > 0) summary.append(", ").append(skipped).append(" skipped");
        if (millis != null) summary.append(" in ")
                .append(String.format(java.util.Locale.ROOT, "%.1fs", millis / 1000.0));
        lines.add(summary.toString());
        return lines;
    }

    private static final java.util.regex.Pattern FAILURE_TEST = java.util.regex.Pattern.compile(
            "className = '([^']+)', methodName = '([^']+)'");
    private static final java.util.regex.Pattern FAILURE_EXCEPTION = java.util.regex.Pattern.compile(
            "^\\s*=> ([\\w.$]+)(?:: (.*))?$");
    private static final java.util.regex.Pattern STACK_FRAME = java.util.regex.Pattern.compile(
            "^\\s*(?:at )?([\\w.$]+)\\.[\\w$<>]+\\(([\\w$]+\\.java):(\\d+)\\)");

    /** One condensed line per entry in the ConsoleLauncher {@code Failures (N):} section. */
    private static List<String> parseFailures(String output, java.util.Set<String> projectPackages) {
        List<String> lines = new ArrayList<>();
        String className = null, methodName = null, exception = null, location = null;
        boolean inFailures = false;
        for (String line : output.split("\n")) {
            if (line.startsWith("Failures (")) { inFailures = true; continue; }
            if (!inFailures) continue;
            if (line.startsWith("Test run finished")) break;
            var test = FAILURE_TEST.matcher(line);
            if (test.find()) {
                flushFailure(lines, className, methodName, exception, location);
                className = test.group(1);
                methodName = test.group(2);
                exception = null;
                location = null;
                continue;
            }
            var ex = FAILURE_EXCEPTION.matcher(line);
            if (exception == null && ex.matches()) {
                String type = ex.group(1);
                String message = ex.group(2);
                exception = type.substring(type.lastIndexOf('.') + 1)
                        + (message == null || message.isBlank() ? "" : ": " + message.strip());
                continue;
            }
            var frame = STACK_FRAME.matcher(line);
            if (exception != null && location == null && frame.find()) {
                String frameClass = frame.group(1);
                int dot = frameClass.lastIndexOf('.');
                String framePackage = dot >= 0 ? frameClass.substring(0, dot) : "";
                if (projectPackages.contains(framePackage)) {
                    location = frame.group(2) + ":" + frame.group(3);
                }
            }
        }
        flushFailure(lines, className, methodName, exception, location);
        return lines;
    }

    private static void flushFailure(List<String> lines, String className, String methodName,
                                     String exception, String location) {
        if (className == null) return;
        String simple = className.substring(className.lastIndexOf('.') + 1);
        lines.add(simple + "." + methodName + "()"
                + (exception != null ? " — " + exception : "")
                + (location != null ? " (" + location + ")" : ""));
    }

    private static Long matchLong(String text, String regex) {
        var m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    /** Package names of the project's own sources (from the dirs under src/{main,test}/java). */
    private static java.util.Set<String> projectPackages(Path cwd) throws IOException {
        var packages = new java.util.HashSet<String>();
        for (String tree : List.of("src/main/java", "src/test/java")) {
            Path root = cwd.resolve(tree);
            for (String file : findJavaFiles(root)) {
                Path rel = root.relativize(Path.of(file)).getParent();
                packages.add(rel == null ? "" : rel.toString().replace(File.separatorChar, '.'));
            }
        }
        return packages;
    }

    // --- deps ------------------------------------------------------------

    static int deps(Path cwd) throws Exception {
        if (!Files.exists(cwd.resolve("pom.xml"))) {
            CliOutput.printError("pom.xml not found — brace deps requires a Maven project");
            return 1;
        }
        if (which("mvn") == null) {
            CliOutput.printError("Maven is not installed. Install it or drop JARs manually into ./lib/");
            return 1;
        }
        CliOutput.printInfo("Copying dependencies from pom.xml into ./lib/");
        int rc = new ProcessBuilder(
                "mvn", "dependency:copy-dependencies",
                "-DoutputDirectory=lib",
                "-DincludeScope=runtime",
                "-DexcludeGroupIds=com.larvalabs,com.github.larvalabs",
                "-q")
                .directory(cwd.toFile())
                .inheritIO()
                .start()
                .waitFor();
        if (rc == 0) CliOutput.printSuccess("Dependencies copied to ./lib/");
        return rc;
    }

    // --- classpath -------------------------------------------------------

    private static String projectClasspath(Path cwd) {
        List<String> cp = new ArrayList<>(jarsIn(frameworkLibDir()));
        Path lib = cwd.resolve("lib");
        if (Files.isDirectory(lib)) cp.addAll(jarsIn(lib));
        Path classes = cwd.resolve("target/classes");
        if (Files.isDirectory(classes)) cp.add(classes.toString());
        return String.join(File.pathSeparator, cp);
    }

    private static String testClasspath(Path cwd) {
        String cp = projectClasspath(cwd);
        Path testClasses = cwd.resolve("target/test-classes");
        if (Files.isDirectory(testClasses)) cp = cp + File.pathSeparator + testClasses;
        return cp;
    }

    /**
     * The directory holding the framework jars. Set by the launcher via
     * {@code -Dbrace.home}; falls back to the directory of the running jar so
     * that {@code java -cp brace.jar Cli run} works without the launcher.
     */
    private static Path frameworkLibDir() {
        String home = System.getProperty("brace.home");
        if (home != null && !home.isBlank()) return Path.of(home, "lib");
        try {
            Path loc = Path.of(BuildCommands.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (loc.toString().endsWith(".jar")) return loc.getParent();
        } catch (Exception ignored) {
            // not running from a jar (e.g. target/classes during framework dev)
        }
        return null;
    }

    private static List<String> jarsIn(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path findJunitJar() {
        Path lib = frameworkLibDir();
        if (lib == null || !Files.isDirectory(lib)) return null;
        try (var s = Files.list(lib)) {
            return s.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("junit-platform-console-standalone-") && n.endsWith(".jar");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // --- helpers ---------------------------------------------------------

    private static List<String> findJavaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
    }

    private static String findMainClass(Path cwd) throws IOException {
        Path srcMain = cwd.resolve("src/main/java");
        try (var s = Files.walk(srcMain)) {
            var mainFile = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(BuildCommands::hasMain)
                    .sorted()
                    .findFirst();
            if (mainFile.isEmpty()) {
                throw new IOException("No main class found in src/main/java/");
            }
            Path rel = srcMain.relativize(mainFile.get());
            return rel.toString()
                    .substring(0, rel.toString().length() - ".java".length())
                    .replace(File.separatorChar, '.');
        }
    }

    private static boolean hasMain(Path p) {
        try {
            return Files.readString(p).contains("public static void main");
        } catch (IOException e) {
            return false;
        }
    }

    private static Path which(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            Path p = Path.of(dir, exe);
            if (Files.isExecutable(p)) return p;
            Path pe = Path.of(dir, exe + ".exe");
            if (Files.isExecutable(pe)) return pe;
        }
        return null;
    }
}
