package com.larvalabs.brace;

import java.nio.file.*;
import java.util.Arrays;

public class Cli {

    /**
     * Bootstrap contract version this toolchain expects of the launcher shim. The
     * shim passes its own version via {@code -Dbrace.launcher}; if an older shim
     * runs this (newer) toolchain we nudge the user to self-update. Bumped only
     * when the launcher<->toolchain contract changes (see bin/brace SHIM_VERSION).
     */
    private static final int BOOTSTRAP_CONTRACT = 2;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { printUsage(); return; }
        warnIfStaleLauncher();
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        try {
            int code = dispatch(cwd, cmd, rest);
            System.exit(code);
        } catch (Exception e) {
            CliOutput.printError(e.getMessage() != null ? e.getMessage() : e.toString());
            System.exit(1);
        }
    }

    /** Nudge if a launcher older than this toolchain's bootstrap contract ran us. */
    private static void warnIfStaleLauncher() {
        String launcher = System.getProperty("brace.launcher");
        if (launcher == null) return;   // run directly / by a pre-contract launcher — stay quiet
        try {
            if (Integer.parseInt(launcher.trim()) < BOOTSTRAP_CONTRACT) {
                CliOutput.printError("Your brace launcher is older than this framework version. "
                        + "Run `brace self-update` to refresh it.");
            }
        } catch (NumberFormatException ignored) {
            // unrecognised launcher stamp — nothing actionable
        }
    }

    private static int dispatch(Path cwd, String cmd, String[] args) throws Exception {
        return switch (cmd) {
            case "new" -> {
                if (args.length < 1) {
                    CliOutput.printError("Usage: brace new <project-name>");
                    yield 1;
                }
                ProjectGenerator.generate(args[0]);
                yield 0;
            }
            case "version", "--version", "-v" -> {
                System.out.println(BraceVersion.get());
                yield 0;
            }
            case "self-update" -> CliSelfUpdate.run(args);
            case "compile" -> requireSrc(cwd, () -> BuildCommands.compile(cwd));
            case "run"     -> requireSrc(cwd, () -> BuildCommands.run(cwd));
            case "dev"     -> requireSrc(cwd, () -> BuildCommands.dev(cwd));
            case "test"    -> requireSrc(cwd, () -> BuildCommands.test(cwd, args));
            case "deps"    -> requireSrc(cwd, () -> BuildCommands.deps(cwd));
            case "init" -> initCommand(cwd, args);
            case "ops" -> opsCommand(cwd, args);
            case "errors"  -> requireProject(cwd, () -> CliCommands.errors(cwd, args));
            case "logs"    -> requireProject(cwd, () -> CliCommands.logs(cwd, args));
            case "status"  -> requireProject(cwd, () -> CliCommands.status(cwd, args));
            case "check"   -> requireProject(cwd, () -> CliCheck.run(cwd, args));
            case "cache"   -> cacheCommand(cwd, args);
            case "resolve" -> requireProject(cwd, () -> CliCommands.resolve(cwd, args));
            default -> { printUsage(); yield 0; }
        };
    }

    private static int cacheCommand(Path cwd, String[] args) throws Exception {
        if (args.length > 0 && "clear".equals(args[0])) {
            return requireProject(cwd, () ->
                CliCommands.cacheClear(cwd, Arrays.copyOfRange(args, 1, args.length)));
        }
        return requireProject(cwd, () -> CliCommands.cache(cwd, args));
    }

    private static int opsCommand(Path cwd, String[] args) throws Exception {
        if (args.length < 1) {
            CliOutput.printError("Usage: brace ops <keypair|dashboard>");
            return 1;
        }
        return switch (args[0]) {
            case "keypair"   -> requireSrc(cwd, () -> CliOps.keypair(cwd, sub(args)));
            case "dashboard" -> requireProject(cwd, () -> CliOps.dashboard(cwd, sub(args)));
            default -> {
                CliOutput.printError("Unknown ops command: " + args[0]);
                yield 1;
            }
        };
    }

    private static int initCommand(Path cwd, String[] args) throws Exception {
        if (!Files.exists(cwd.resolve("src/main/java"))) {
            CliOutput.printError("Run inside a Brace project (no src/main/java). Use `brace new <name>` to create one.");
            return 1;
        }
        var result = CliInit.runWithRemote(cwd);
        var mode = CliOutput.autoMode(
            Arrays.asList(args).contains("--json"),
            Arrays.asList(args).contains("--pretty"));
        CliInit.print(result, mode);
        return result.ok() ? 0 : 1;
    }

    @FunctionalInterface
    private interface CliFn { int run() throws Exception; }

    private static int requireProject(Path cwd, CliFn fn) throws Exception {
        if (!Files.exists(cwd.resolve(".brace"))) {
            CliOutput.printError("This command must be run inside a Brace project. Run `brace init` first.");
            return 1;
        }
        return fn.run();
    }

    private static int requireSrc(Path cwd, CliFn fn) throws Exception {
        if (!Files.exists(cwd.resolve("src/main/java"))) {
            CliOutput.printError("This command must be run inside a Brace project (no src/main/java).");
            return 1;
        }
        return fn.run();
    }

    private static String[] sub(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static void printUsage() {
        System.out.println("Brace CLI v" + BraceVersion.get());
        System.out.println();
        System.out.println("Global commands:");
        System.out.println("  brace new <name>            Create a new Brace project");
        System.out.println("  brace version               Print the brace version");
        System.out.println("  brace self-update [version]  Update the installed launcher to the latest (or given) version");
        System.out.println();
        System.out.println("Build & run (run inside a project):");
        System.out.println("  brace compile               Compile the project");
        System.out.println("  brace run                   Compile and run");
        System.out.println("  brace dev                   Compile, run, and watch for changes");
        System.out.println("  brace test [class]          Run tests");
        System.out.println("  brace deps                  Copy dependencies from pom.xml into ./lib/");
        System.out.println();
        System.out.println("Project commands (run inside a project):");
        System.out.println("  brace init                  Scaffold .brace + .brace.local and run readiness checks");
        System.out.println("  brace ops keypair           Generate an Ed25519 keypair for ops auth");
        System.out.println("  brace ops dashboard         Open the ops dashboard in a browser");
        System.out.println("  brace errors [--since 1h]   List unresolved errors");
        System.out.println("  brace logs [-f] [--since]   Tail recent log lines");
        System.out.println("  brace status                Show app health snapshot");
        System.out.println("  brace check                 Run all health checks");
        System.out.println("  brace cache                 Show cache stats");
        System.out.println("  brace cache clear           Clear the cache");
        System.out.println("  brace resolve <id>          Mark an error as resolved");
        System.out.println();
        System.out.println("All project commands accept --env <name>, --json, --pretty.");
        System.out.println("Default env: prod when ops.prod.url is configured, else local.");
    }
}
