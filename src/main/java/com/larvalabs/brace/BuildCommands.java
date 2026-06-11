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
        if (args.length > 0 && !args[0].startsWith("-")) {
            cmd.add("--select-class");
            cmd.add(args[0]);
        } else {
            cmd.add("--scan-classpath");
            cmd.add(cwd.resolve("target/test-classes").toString());
        }
        cmd.add("--disable-banner");
        return new ProcessBuilder(cmd).directory(cwd.toFile()).inheritIO().start().waitFor();
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
