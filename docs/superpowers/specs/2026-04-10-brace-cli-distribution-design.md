# Brace CLI Distribution — Design

## Summary

Package Brace as a downloadable distribution containing a global `brace` CLI and all pre-resolved framework dependencies, so users can install once and run `brace new`, `brace dev`, `brace test`, etc. from any directory — similar to how Play 1 distributed itself.

Today, Brace has two disjoint pieces: a Java `Cli` class (handles `new` and `ops` commands, invoked via `java -cp brace.jar com.larvalabs.brace.Cli`) and a per-project `sample/brace` bash script (handles `dev`/`test`/`run`, copied into each project). Users have no global `brace` command. Agents and developers cannot discover or run Brace commands without first cloning the framework repo or copying scripts manually.

## Goals

- A single `brace` command installable via zip download → unzip → add to PATH.
- No Maven required to run dev-loop commands (`brace dev`, `brace run`, `brace test`) after an initial dependency fetch.
- Project structure stays familiar — `pom.xml` remains the source of truth for dependencies, usable by IDEs and production Docker builds unchanged.
- The launcher is a single script shipped in the distribution, not copied into each project.

## Non-Goals

- Building a custom dependency resolver. Maven is invoked once via `brace deps` to populate a project-local `lib/` folder from `pom.xml`.
- Homebrew tap, native binary, or auto-updater. These are follow-ups.
- Windows support at the launcher script level. Bash launcher is POSIX-only; Windows users use WSL.

## Distribution Layout

```
brace-0.1.0/
├── bin/
│   └── brace                          # bash launcher
└── lib/
    ├── brace-0.1.0.jar                # framework
    ├── jetty-server-12.0.33.jar       # transitive deps
    ├── jetty-websocket-jetty-server-12.0.33.jar
    ├── hibernate-core-7.0.10.Final.jar
    ├── HikariCP-6.3.0.jar
    ├── flyway-core-10.22.0.jar
    ├── jte-3.2.1.jar
    ├── jte-runtime-3.2.1.jar
    ├── jackson-databind-2.18.6.jar
    ├── jakarta.mail-2.0.5.jar
    ├── jbcrypt-0.4.jar
    ├── junit-platform-console-standalone-1.11.4.jar
    └── ...                            # all brace transitive deps
```

`BRACE_HOME` is resolved by the launcher as the parent of its own directory — no `BRACE_HOME` env var required.

Install:

```bash
curl -LO https://github.com/larvalabs/brace/releases/download/v0.1.0/brace-0.1.0.zip
unzip brace-0.1.0.zip
export PATH="$PWD/brace-0.1.0/bin:$PATH"
```

## Project Layout (after `brace new myapp`)

```
myapp/
├── pom.xml                # source of truth for deps (Maven + IDE)
├── lib/                   # populated by `brace deps` (gitignored)
│   └── telegrambots-6.8.0.jar
├── application.conf
├── ops-authorized-keys
├── migrations/
├── views/
├── public/
└── src/
    ├── main/java/app/
    │   ├── App.java
    │   └── controllers/
    └── test/java/app/
```

`pom.xml` declares Brace as a dependency for IDE/Maven use; `brace` CLI ignores it for dev commands and uses its own bundled classpath instead. The `brace deps` command uses Maven to copy non-Brace dependencies into `lib/` for the CLI to pick up.

## Classpath Construction

The brace CLI builds classpaths from three sources, in this order:

1. `$BRACE_HOME/lib/*` — framework + standard deps
2. `./lib/*` — project extras (created by `brace deps`)
3. `target/classes` — compiled project code
4. `target/test-classes` — compiled test code (for `brace test` only)

The same classpath pattern applies to compilation, running, and testing.

## Commands

| Command | Behavior |
|---|---|
| `brace new <name>` | Runs `java -cp $BRACE_HOME/lib/* com.larvalabs.brace.Cli new <name>` — unchanged generator logic. |
| `brace ops keypair [--label]` | Runs `java -cp $BRACE_HOME/lib/* com.larvalabs.brace.Cli ops keypair`. |
| `brace ops dashboard [--key] [--url]` | Runs `java -cp $BRACE_HOME/lib/* com.larvalabs.brace.Cli ops dashboard`. |
| `brace deps` | Runs `mvn dependency:copy-dependencies -DoutputDirectory=lib -DincludeScope=runtime -DexcludeGroupIds=com.larvalabs -q`. Excludes Brace itself since that's already in `$BRACE_HOME/lib`. |
| `brace compile` | Runs `javac -d target/classes -cp "$(classpath)" $(find src/main/java -name '*.java')`. |
| `brace run` | Compiles, then runs `java -cp "target/classes:$(classpath)" <main-class>`. Main class discovered by grepping for `public static void main` under `src/main/java/`. |
| `brace test [name]` | Compiles main + test sources, runs `java -jar $BRACE_HOME/lib/junit-platform-console-standalone-*.jar -cp "target/classes:target/test-classes:$(classpath)" --scan-classpath` (with `--select-class` filter if name provided). |
| `brace dev` | Compile + run in background + watch `src/` for changes (fswatch on macOS, polling fallback). Same logic as current `sample/brace dev`. |
| `brace help` | Prints usage. |

`brace deps` is the only command that invokes Maven, and only when the user runs it explicitly (after changing `pom.xml`). Users can also run `mvn package` unchanged for production builds — Brace CLI and Maven coexist.

## Changes to Existing Code

1. **`sample/brace`** → moves to `bin/brace` in the distribution. Updated:
   - Removes Maven classpath building (`build_classpath()`) — replaced with `$BRACE_HOME/lib/*:./lib/*`.
   - Adds dispatch for `new`, `ops`, `deps` subcommands.
   - Adds `test` command that invokes JUnit standalone runner.
   - `BRACE_HOME` resolved from launcher script location via `dirname`.

2. **`ProjectGenerator.java`**:
   - Stops copying `brace` script into generated projects (no per-project script needed).
   - Adds `lib/` to generated `.gitignore`.
   - Generated `pom.xml` remains for production builds and IDE support, unchanged.

3. **`pom.xml`** (Brace's own):
   - Adds `maven-assembly-plugin` configuration that produces `brace-0.1.0.zip` containing `bin/brace` + all runtime-scoped transitive dependencies copied into `lib/`.
   - Adds JUnit Platform Console Standalone as a `runtime` dependency so it ships in the distribution.

4. **`Cli.java`**: no changes. Its existing subcommands (`new`, `ops keypair`, `ops dashboard`) are invoked from the launcher via `java -cp $BRACE_HOME/lib/*`.

## Build and Release

The assembly plugin runs during `mvn package` and produces `target/brace-0.1.0.zip` alongside the existing JAR. Release process:

1. `mvn clean package` — builds JAR and distribution zip.
2. `gh release create v0.1.0 target/brace-0.1.0.zip` — publishes.

No new build infrastructure required.

## Error Handling

- **Launcher script**: fails loudly with clear messages when `BRACE_HOME/lib` is missing, `java` is not on PATH, or commands that need project context (`dev`, `run`, `test`, `compile`, `deps`) are run outside a project directory.
- **`brace deps`**: if Maven is not installed, prints an error telling the user to install Maven or download JARs manually into `lib/`.
- **`brace run` / `brace dev`**: if no main class is found under `src/main/java`, prints an error listing what was searched.

## Testing

- **Launcher script**: a few shell-based tests in `tests/` that run `brace new`, `brace compile`, `brace test` against a scratch project and assert exit codes + expected output.
- **Distribution assembly**: a CI job that unzips the generated distribution, adds `bin/` to PATH, runs `brace new testapp && cd testapp && brace compile && brace test`, and verifies it all works end-to-end.
- **No changes to existing Java tests** — `Cli.java` behavior is unchanged.

## Open Questions Resolved

- **pom.xml vs no pom.xml**: Keep pom.xml. It's the source of truth for deps, works with IDEs and Maven builds unchanged. Brace CLI sidesteps it for dev commands by using its bundled classpath.
- **Extra deps**: Declared in pom.xml, copied into project `lib/` once via `brace deps`. Not auto-synced — user re-runs `brace deps` after editing pom.xml.
- **Maven dependency**: Only required when populating `lib/`, not for day-to-day dev. Users who don't add extras never need Maven.
