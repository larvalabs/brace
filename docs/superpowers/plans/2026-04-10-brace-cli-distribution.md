# Brace CLI Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Brace as a downloadable zip containing a global `brace` CLI and pre-resolved framework dependencies, so users install once and run `brace new`, `brace dev`, `brace test` from any directory.

**Architecture:** The existing `sample/brace` bash script moves to `bin/brace` in a distribution zip. The script resolves `BRACE_HOME` from its own location and builds classpaths from `$BRACE_HOME/lib/*` + project `./lib/*` + `target/classes`. A Maven assembly plugin produces the zip during `mvn package`. Generated projects keep `pom.xml` as the source-of-truth for dependencies; `brace deps` shells out to Maven once to populate a project-local `lib/`.

**Tech Stack:** Bash (launcher), Maven assembly plugin (packaging), existing Java `Cli.java` (new/ops subcommands), JUnit Platform Console Standalone (test runner), `javac`/`java` from JDK 21.

**Reference spec:** `docs/superpowers/specs/2026-04-10-brace-cli-distribution-design.md`

---

## File Structure

**New files:**
- `src/assembly/distribution.xml` — Maven assembly descriptor that builds the zip
- `bin/brace` — new launcher script (replaces `sample/brace`)
- `tests/cli/test-new-and-build.sh` — end-to-end shell test

**Modified files:**
- `pom.xml` — add junit-platform-console-standalone as runtime dep, add assembly plugin
- `src/main/java/io/brace/ProjectGenerator.java` — stop copying per-project brace script, add `lib/` to generated .gitignore
- `src/main/java/io/brace/Cli.java` — add `--help` for top-level, no behavior changes otherwise

**Deleted files:**
- `sample/brace` — replaced by `bin/brace` (keep symlink or stub for back-compat during transition? No — remove outright, the sample dir stays for the sample app)

---

## Task 1: Add junit-platform-console-standalone as a runtime dependency

The test runner needs to ship in `$BRACE_HOME/lib/` so `brace test` can invoke it without Maven. Currently JUnit is test-scoped, which means Maven won't include it in the distribution.

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the console standalone dependency to pom.xml**

Add this dependency block in the `<dependencies>` section, right after the existing junit-jupiter entry (around line 62):

```xml
<!-- JUnit Platform Console Standalone - shipped in distribution for `brace test` -->
<dependency>
    <groupId>org.junit.platform</groupId>
    <artifactId>junit-platform-console-standalone</artifactId>
    <version>1.11.4</version>
</dependency>
```

Note: this is compile/runtime scope, not test scope — we need it in the distribution.

- [ ] **Step 2: Verify the build still passes**

Run: `mvn compile -q`
Expected: Exits 0 with no errors.

- [ ] **Step 3: Verify existing tests still pass**

Run: `mvn test -q 2>&1 | grep -E "Tests run: 4[0-9]{2}|BUILD"`
Expected: `Tests run: 409, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "Add junit-platform-console-standalone for CLI distribution"
```

---

## Task 2: Add Maven assembly plugin to build the distribution zip

Configure Maven to produce `target/brace-0.1.0-SNAPSHOT.zip` alongside the existing JAR.

**Files:**
- Create: `src/assembly/distribution.xml`
- Modify: `pom.xml`

- [ ] **Step 1: Create the assembly descriptor**

Create `src/assembly/distribution.xml` with this content:

```xml
<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.1.1"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.1.1 http://maven.apache.org/xsd/assembly-2.1.1.xsd">
    <id>dist</id>
    <formats>
        <format>zip</format>
    </formats>
    <includeBaseDirectory>true</includeBaseDirectory>
    <baseDirectory>brace-${project.version}</baseDirectory>

    <fileSets>
        <!-- Launcher script -->
        <fileSet>
            <directory>${project.basedir}/bin</directory>
            <outputDirectory>bin</outputDirectory>
            <fileMode>0755</fileMode>
            <includes>
                <include>brace</include>
            </includes>
        </fileSet>
    </fileSets>

    <dependencySets>
        <!-- All runtime + compile scope deps (excludes test scope like h2) -->
        <dependencySet>
            <outputDirectory>lib</outputDirectory>
            <useProjectArtifact>true</useProjectArtifact>
            <scope>runtime</scope>
            <unpack>false</unpack>
        </dependencySet>
    </dependencySets>
</assembly>
```

- [ ] **Step 2: Add the assembly plugin to pom.xml**

Add this plugin in the `<plugins>` section inside `<build>`, after the surefire plugin (after line 146):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-assembly-plugin</artifactId>
    <version>3.7.1</version>
    <configuration>
        <descriptors>
            <descriptor>src/assembly/distribution.xml</descriptor>
        </descriptors>
        <appendAssemblyId>false</appendAssemblyId>
        <finalName>brace-${project.version}</finalName>
    </configuration>
    <executions>
        <execution>
            <id>make-distribution</id>
            <phase>package</phase>
            <goals>
                <goal>single</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 3: Create a placeholder bin/brace so the assembly works**

The assembly will fail if `bin/brace` doesn't exist. Create a minimal placeholder — Task 3 will replace it:

```bash
mkdir -p bin
cat > bin/brace <<'EOF'
#!/usr/bin/env bash
echo "brace CLI - placeholder - will be implemented in Task 3"
EOF
chmod +x bin/brace
```

- [ ] **Step 4: Run the build and verify the zip is produced**

Run: `mvn package -DskipTests -q 2>&1 | tail -5 && ls -la target/brace-0.1.0-SNAPSHOT.zip`
Expected: File `target/brace-0.1.0-SNAPSHOT.zip` exists, size > 10MB.

- [ ] **Step 5: Inspect the zip contents**

Run: `unzip -l target/brace-0.1.0-SNAPSHOT.zip | head -30`
Expected: Shows `brace-0.1.0-SNAPSHOT/bin/brace`, `brace-0.1.0-SNAPSHOT/lib/brace-0.1.0-SNAPSHOT.jar`, and at least `jetty-server`, `hibernate-core`, `jte`, `junit-platform-console-standalone` JARs under `lib/`.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/assembly/distribution.xml bin/brace
git commit -m "Add Maven assembly plugin for brace CLI distribution zip"
```

---

## Task 3: Write the bin/brace launcher script

Replace the placeholder with the real launcher. It must resolve `BRACE_HOME` from its own location, dispatch Java subcommands to `Cli.java`, and handle project-context commands (compile/run/test/dev/deps) using `javac`/`java` directly.

**Files:**
- Modify: `bin/brace`

- [ ] **Step 1: Write the full launcher script**

Replace `bin/brace` with this content:

```bash
#!/usr/bin/env bash
set -euo pipefail

# Brace CLI — global launcher
# Resolves BRACE_HOME from its own location. Invokes Java subcommands via
# $BRACE_HOME/lib, and compiles/runs projects using javac/java directly.

# --- Resolve BRACE_HOME ---
# Follow symlinks to the real script location, then go up one directory.
SCRIPT_PATH="${BASH_SOURCE[0]}"
while [[ -L "$SCRIPT_PATH" ]]; do
    SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT_PATH")" && pwd)"
    SCRIPT_PATH="$(readlink "$SCRIPT_PATH")"
    [[ "$SCRIPT_PATH" != /* ]] && SCRIPT_PATH="$SCRIPT_DIR/$SCRIPT_PATH"
done
BRACE_HOME="$(cd -P "$(dirname "$SCRIPT_PATH")/.." && pwd)"

if [[ ! -d "$BRACE_HOME/lib" ]]; then
    echo "Error: BRACE_HOME/lib not found at $BRACE_HOME/lib" >&2
    exit 1
fi

# --- Colors ---
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()    { echo -e "${YELLOW}▸${NC} $*"; }
success() { echo -e "${GREEN}✓${NC} $*"; }
error()   { echo -e "${RED}✗${NC} $*" >&2; }

# --- Classpath construction ---
# $BRACE_HOME/lib/* + ./lib/* (if exists) + target/classes (if exists)
project_classpath() {
    local cp="$BRACE_HOME/lib/*"
    [[ -d lib ]] && cp="$cp:lib/*"
    [[ -d target/classes ]] && cp="$cp:target/classes"
    echo "$cp"
}

test_classpath() {
    local cp
    cp="$(project_classpath)"
    [[ -d target/test-classes ]] && cp="$cp:target/test-classes"
    echo "$cp"
}

# --- Ensure we're in a project directory ---
require_project() {
    if [[ ! -d src/main/java ]]; then
        error "No src/main/java directory found. Run this command from a Brace project directory."
        exit 1
    fi
}

# --- Find main class ---
find_main_class() {
    local main_file
    main_file=$(grep -rl 'public static void main' src/main/java/ --include='*.java' 2>/dev/null | head -1)
    if [[ -z "$main_file" ]]; then
        error "No main class found in src/main/java/"
        exit 1
    fi
    local class_name="${main_file#src/main/java/}"
    class_name="${class_name%.java}"
    class_name="${class_name//\//.}"
    echo "$class_name"
}

# --- Compile main sources ---
brace_compile() {
    require_project
    info "Compiling..."
    mkdir -p target/classes
    local sources
    sources=$(find src/main/java -name '*.java' 2>/dev/null)
    if [[ -z "$sources" ]]; then
        error "No Java sources found under src/main/java"
        exit 1
    fi
    if javac -d target/classes -cp "$(project_classpath)" $sources; then
        success "Compiled"
    else
        error "Compilation failed"
        return 1
    fi
}

# --- Compile test sources ---
brace_compile_tests() {
    require_project
    [[ ! -d src/test/java ]] && return 0
    info "Compiling tests..."
    mkdir -p target/test-classes
    local sources
    sources=$(find src/test/java -name '*.java' 2>/dev/null)
    [[ -z "$sources" ]] && return 0
    if javac -d target/test-classes -cp "$(test_classpath)" $sources; then
        success "Tests compiled"
    else
        error "Test compilation failed"
        return 1
    fi
}

# --- Run ---
brace_run() {
    brace_compile
    local main_class
    main_class=$(find_main_class)
    info "Starting ${CYAN}${main_class}${NC}"
    exec java -cp "$(project_classpath)" "$main_class"
}

# --- Test ---
brace_test() {
    brace_compile
    brace_compile_tests
    local junit_jar
    junit_jar=$(ls "$BRACE_HOME"/lib/junit-platform-console-standalone-*.jar 2>/dev/null | head -1)
    if [[ -z "$junit_jar" ]]; then
        error "junit-platform-console-standalone not found in $BRACE_HOME/lib"
        exit 1
    fi
    info "Running tests..."
    if [[ $# -gt 0 ]]; then
        java -jar "$junit_jar" execute -cp "$(test_classpath)" --select-class "$1" --disable-banner
    else
        java -jar "$junit_jar" execute -cp "$(test_classpath)" --scan-classpath target/test-classes --disable-banner
    fi
}

# --- Deps (populate project lib/ from pom.xml via mvn) ---
brace_deps() {
    require_project
    if [[ ! -f pom.xml ]]; then
        error "pom.xml not found — brace deps requires a Maven project"
        exit 1
    fi
    if ! command -v mvn &>/dev/null; then
        error "Maven is not installed. Install it or drop JARs manually into ./lib/"
        exit 1
    fi
    info "Copying dependencies from pom.xml into ./lib/"
    mvn dependency:copy-dependencies \
        -DoutputDirectory=lib \
        -DincludeScope=runtime \
        -DexcludeGroupIds=io.brace \
        -q
    success "Dependencies copied to ./lib/"
}

# --- Dev (compile + run + watch) ---
PID_FILE="target/.brace-pid"

kill_app() {
    if [[ -f "$PID_FILE" ]]; then
        local pid
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            for _ in 1 2 3 4 5; do
                kill -0 "$pid" 2>/dev/null || break
                sleep 0.2
            done
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
        fi
        rm -f "$PID_FILE"
    fi
}

start_app() {
    local main_class="$1"
    java -cp "$(project_classpath)" "$main_class" &
    local pid=$!
    echo "$pid" > "$PID_FILE"
    success "Started (PID ${pid})"
}

brace_dev() {
    require_project
    trap 'echo ""; info "Shutting down..."; kill_app; exit 0' INT TERM

    brace_compile || exit 1
    local main_class
    main_class=$(find_main_class)

    info "Main class: ${CYAN}${main_class}${NC}"
    start_app "$main_class"

    info "Watching ${CYAN}src/${NC} for changes..."
    echo ""

    if [[ "$(uname)" == "Darwin" ]] && command -v fswatch &>/dev/null; then
        fswatch -0 --include='\.java$' --exclude='.*' -r src/ | while IFS= read -r -d '' _; do
            while IFS= read -r -d '' -t 0.5 _ 2>/dev/null; do :; done
            echo ""
            info "Change detected — recompiling..."
            kill_app
            if brace_compile; then
                start_app "$main_class"
            else
                error "Compilation failed — waiting for next change..."
            fi
        done
    else
        if [[ "$(uname)" == "Darwin" ]] && ! command -v fswatch &>/dev/null; then
            info "Install ${BOLD}fswatch${NC} for faster file watching: ${CYAN}brew install fswatch${NC}"
            echo ""
        fi
        local marker="target/.brace-watch-marker"
        mkdir -p target
        touch "$marker"
        while true; do
            sleep 1
            local changed
            changed=$(find src/ -name '*.java' -newer "$marker" 2>/dev/null | head -1)
            if [[ -n "$changed" ]]; then
                touch "$marker"
                echo ""
                info "Change detected — recompiling..."
                kill_app
                if brace_compile; then
                    start_app "$main_class"
                else
                    error "Compilation failed — waiting for next change..."
                fi
            fi
        done
    fi
}

# --- Java CLI subcommands (new, ops) ---
run_java_cli() {
    exec java -cp "$BRACE_HOME/lib/*" io.brace.Cli "$@"
}

# --- Help ---
brace_help() {
    echo ""
    echo -e "${BOLD}Brace${NC} — Java web framework CLI"
    echo ""
    echo "Global commands:"
    echo -e "  ${CYAN}brace new <name>${NC}           Create a new Brace project"
    echo -e "  ${CYAN}brace ops keypair${NC}          Generate an Ed25519 keypair for ops auth"
    echo -e "  ${CYAN}brace ops dashboard${NC}        Authenticate and open the ops dashboard"
    echo ""
    echo "Project commands (run inside a project directory):"
    echo -e "  ${CYAN}brace deps${NC}                 Copy dependencies from pom.xml into ./lib/"
    echo -e "  ${CYAN}brace compile${NC}              Compile the project"
    echo -e "  ${CYAN}brace run${NC}                  Compile and run"
    echo -e "  ${CYAN}brace dev${NC}                  Compile, run, and watch for changes"
    echo -e "  ${CYAN}brace test${NC} [class]         Run tests (all or a specific class)"
    echo -e "  ${CYAN}brace help${NC}                 Show this help"
    echo ""
}

# --- Dispatch ---
COMMAND="${1:-help}"
shift 2>/dev/null || true

case "$COMMAND" in
    # Global (Java CLI)
    new)     run_java_cli new "$@" ;;
    ops)     run_java_cli ops "$@" ;;
    # Project
    deps)    brace_deps ;;
    compile) brace_compile ;;
    run)     brace_run ;;
    test)    brace_test "$@" ;;
    dev)     brace_dev ;;
    help|-h|--help|*) brace_help ;;
esac
```

- [ ] **Step 2: Make it executable**

Run: `chmod +x bin/brace`

- [ ] **Step 3: Smoke test the launcher against the sample app**

The brace repo has a `sample/App.java`. Build the distribution, unzip it, and run `brace help` to verify it starts.

```bash
mvn package -DskipTests -q
rm -rf /tmp/brace-test && mkdir -p /tmp/brace-test
unzip -q target/brace-0.1.0-SNAPSHOT.zip -d /tmp/brace-test/
/tmp/brace-test/brace-0.1.0-SNAPSHOT/bin/brace help
```

Expected: Prints the help text with "Global commands" and "Project commands" sections.

- [ ] **Step 4: Smoke test `brace new`**

```bash
cd /tmp && rm -rf testapp
/tmp/brace-test/brace-0.1.0-SNAPSHOT/bin/brace new testapp
ls testapp/
```

Expected: `Created new Brace project: testapp` message. Directory `testapp` contains `pom.xml`, `src/`, `application.conf`, `migrations/`, `views/`, etc.

- [ ] **Step 5: Smoke test `brace compile` inside the new project**

```bash
cd /tmp/testapp
/tmp/brace-test/brace-0.1.0-SNAPSHOT/bin/brace compile
```

Expected: `Compiled` success message. `target/classes/app/App.class` exists.

- [ ] **Step 6: Smoke test `brace ops keypair`**

```bash
cd /tmp/testapp
rm -f ops-authorized-keys  # clear the one generated by `brace new`
/tmp/brace-test/brace-0.1.0-SNAPSHOT/bin/brace ops keypair
cat ops-authorized-keys
```

Expected: Prints "Public key: ..." and "Private key: ...". `ops-authorized-keys` exists with a `# comment` header and one key line.

- [ ] **Step 7: Return to the brace repo and commit**

```bash
cd /Users/matt/code/brace
git add bin/brace
git commit -m "Write bin/brace launcher with project-context commands"
```

---

## Task 4: Update ProjectGenerator to drop per-project brace script

Generated projects should no longer include their own copy of `brace`. Users run the global `brace` command.

**Files:**
- Modify: `src/main/java/io/brace/ProjectGenerator.java`

- [ ] **Step 1: Check what ProjectGenerator currently does re: the brace script**

Run: `grep -n "brace" /Users/matt/code/brace/src/main/java/io/brace/ProjectGenerator.java | head -20`
Expected: Confirms whether the generator copies a brace script or not. Current code generates files but does not copy `sample/brace` into new projects (verified during exploration) — so this task is primarily about `.gitignore`.

- [ ] **Step 2: Add `lib/` to the generated `.gitignore`**

Open `src/main/java/io/brace/ProjectGenerator.java`. Find the `.gitignore` generation around line 219:

```java
            // .gitignore
            Files.writeString(root.resolve(".gitignore"), """
target/
jte-classes/
*.class
.idea/
*.iml
.DS_Store
*.key
""");
```

Change it to:

```java
            // .gitignore
            Files.writeString(root.resolve(".gitignore"), """
target/
lib/
jte-classes/
*.class
.idea/
*.iml
.DS_Store
*.key
""");
```

- [ ] **Step 3: Compile and run tests**

Run: `mvn test -q 2>&1 | grep -E "Tests run: 4|BUILD"`
Expected: `Tests run: 409, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 4: Rebuild the distribution and verify a new project doesn't ship a brace script**

```bash
mvn package -DskipTests -q
rm -rf /tmp/brace-test && mkdir -p /tmp/brace-test
unzip -q target/brace-0.1.0-SNAPSHOT.zip -d /tmp/brace-test/
cd /tmp && rm -rf testapp
/tmp/brace-test/brace-0.1.0-SNAPSHOT/bin/brace new testapp
ls testapp/ | grep -c brace || echo "no brace script in project (correct)"
cat testapp/.gitignore
```

Expected: No `brace` file in the project root. `.gitignore` contains `lib/`.

- [ ] **Step 5: Commit**

```bash
cd /Users/matt/code/brace
git add src/main/java/io/brace/ProjectGenerator.java
git commit -m "Add lib/ to generated .gitignore for brace CLI dep cache"
```

---

## Task 5: Delete the old sample/brace script

The per-project script is obsolete now that the launcher lives in `bin/brace` and ships in the distribution.

**Files:**
- Delete: `sample/brace`

- [ ] **Step 1: Verify nothing else in the repo references sample/brace for running**

Run: `grep -rn "sample/brace" /Users/matt/code/brace --include="*.md" --include="*.java" --include="*.xml" 2>/dev/null | grep -v "worktrees\|target\|\.git/"`
Expected: May show references in docs. Note them — they'll need updating.

- [ ] **Step 2: Update any doc references from `./sample/brace sample` to the new approach**

For each match from Step 1, replace `./sample/brace sample` with `brace run` (for usage inside the sample dir) or the equivalent. The sample app in `sample/` will need to become a standalone project using the new CLI, or the docs updated to say "the sample app is now built by running `mvn compile && java -cp target/classes:...` directly".

For now, update `CLAUDE.md` line that says `./sample/brace sample` to read:

```
# Build and run the sample app (uses the installed brace CLI)
cd sample && brace run
```

Check `CLAUDE.md`:

Run: `grep -n "sample/brace" /Users/matt/code/brace/CLAUDE.md`

If it matches line 73, edit `CLAUDE.md` to replace that line.

- [ ] **Step 3: Delete the old sample/brace script**

```bash
git rm sample/brace
```

- [ ] **Step 4: Verify the brace test suite still passes**

Run: `mvn test -q 2>&1 | grep -E "Tests run: 4|BUILD"`
Expected: `Tests run: 409, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Remove sample/brace — replaced by bin/brace in distribution"
```

---

## Task 6: Add `brace run` support for sample app directory structure

The sample app lives at `sample/App.java` — a single file, not under `src/main/java/app/`. The launcher's `find_main_class` grep currently looks only at `src/main/java/`. We need to decide: either move the sample app to match the convention, or add a fallback.

**Files:**
- Modify: `sample/` directory structure OR `bin/brace`

- [ ] **Step 1: Move the sample app to standard project layout**

The simplest and most consistent approach is to restructure `sample/` to look like a real Brace project.

```bash
cd /Users/matt/code/brace/sample
mkdir -p src/main/java/sample
git mv App.java src/main/java/sample/App.java
```

Verify it still compiles standalone:

```bash
cd /Users/matt/code/brace
mvn package -DskipTests -q
rm -rf /tmp/brace-test && mkdir -p /tmp/brace-test
unzip -q target/brace-0.1.0-SNAPSHOT.zip -d /tmp/brace-test/
cd /Users/matt/code/brace/sample
/tmp/brace-test/brace-0.1.0-SNAPSHOT/bin/brace compile
```

Expected: `Compiled` success message.

- [ ] **Step 2: Verify `brace run` works against the sample**

```bash
cd /Users/matt/code/brace/sample
timeout 3 /tmp/brace-test/brace-0.1.0-SNAPSHOT/bin/brace run 2>&1 | head -20 || true
```

Expected: Log output showing `Brace started on port 9000` (or similar startup message) before the timeout kills it.

- [ ] **Step 3: Commit**

```bash
cd /Users/matt/code/brace
git add -A
git commit -m "Restructure sample app to standard Brace project layout"
```

---

## Task 7: End-to-end shell test for the distribution

Write a shell script that builds the distribution, unzips it, creates a new project, compiles, and runs tests against a scratch directory. Runnable locally or in CI.

**Files:**
- Create: `tests/cli/test-distribution.sh`

- [ ] **Step 1: Write the end-to-end test script**

Create `tests/cli/test-distribution.sh`:

```bash
#!/usr/bin/env bash
# End-to-end test for the brace CLI distribution.
# Builds the zip, unzips it to a temp dir, runs brace new + compile + test.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

step() { echo -e "${YELLOW}▸${NC} $*"; }
pass() { echo -e "${GREEN}✓${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*" >&2; exit 1; }

step "Building distribution"
cd "$REPO"
mvn package -DskipTests -q
ZIP=$(ls target/brace-*.zip | head -1)
[[ -f "$ZIP" ]] || fail "Distribution zip not built"
pass "Built $(basename "$ZIP")"

step "Unzipping distribution to $WORK"
unzip -q "$ZIP" -d "$WORK"
BRACE_BIN=$(ls "$WORK"/brace-*/bin/brace | head -1)
[[ -x "$BRACE_BIN" ]] || fail "bin/brace not executable"
pass "Launcher found at $BRACE_BIN"

step "Running brace help"
"$BRACE_BIN" help > "$WORK/help.out"
grep -q "Global commands" "$WORK/help.out" || fail "help output missing 'Global commands'"
grep -q "brace new" "$WORK/help.out" || fail "help output missing 'brace new'"
pass "help prints usage"

step "Running brace new testapp"
cd "$WORK"
"$BRACE_BIN" new testapp > "$WORK/new.out" 2>&1 || fail "brace new failed"
[[ -d testapp ]] || fail "testapp directory not created"
[[ -f testapp/pom.xml ]] || fail "testapp/pom.xml not created"
[[ -f testapp/src/main/java/app/App.java ]] || fail "App.java not created"
[[ -f testapp/src/test/java/app/HomeControllerTest.java ]] || fail "test class not created"
pass "brace new created project"

step "Running brace compile"
cd "$WORK/testapp"
"$BRACE_BIN" compile > "$WORK/compile.out" 2>&1 || {
    cat "$WORK/compile.out"
    fail "brace compile failed"
}
[[ -f target/classes/app/App.class ]] || fail "App.class not produced"
pass "brace compile succeeded"

step "Running brace test"
"$BRACE_BIN" test > "$WORK/test.out" 2>&1 || {
    cat "$WORK/test.out"
    fail "brace test failed"
}
grep -qE "Test run finished|Successful tests|\[ +1 tests successful +\]" "$WORK/test.out" || {
    cat "$WORK/test.out"
    fail "test output doesn't look right"
}
pass "brace test ran"

step "Running brace ops keypair"
rm -f ops-authorized-keys  # clear the one from brace new
"$BRACE_BIN" ops keypair > "$WORK/keypair.out" 2>&1 || fail "brace ops keypair failed"
grep -q "Public key:" "$WORK/keypair.out" || fail "keypair output missing 'Public key:'"
grep -q "Private key:" "$WORK/keypair.out" || fail "keypair output missing 'Private key:'"
[[ -f ops-authorized-keys ]] || fail "ops-authorized-keys not created"
pass "brace ops keypair generated and wrote file"

echo ""
echo -e "${GREEN}All distribution tests passed.${NC}"
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x tests/cli/test-distribution.sh
```

- [ ] **Step 3: Run it locally**

```bash
./tests/cli/test-distribution.sh
```

Expected: All steps print `✓`, final line says `All distribution tests passed.`

- [ ] **Step 4: If anything fails, diagnose and fix**

Common issues:
- `brace test` output format might differ from expected grep patterns — check actual output in `/tmp` and adjust the grep.
- If the new project's `HomeControllerTest` fails because the views directory is missing at test time, check whether the test's `@BeforeAll` references templates correctly.
- If compilation fails with missing symbols, verify the assembly is packaging all runtime-scope deps (Task 2).

Iterate until the script exits 0.

- [ ] **Step 5: Commit**

```bash
git add tests/cli/test-distribution.sh
git commit -m "Add end-to-end distribution test"
```

---

## Task 8: Update CLAUDE.md and AGENTS.md with install instructions

Document how agents and developers install and use the brace CLI.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `AGENTS.md`
- Modify: `src/main/java/io/brace/ClaudeMdGenerator.java` (so generated projects also reference the new install flow)

- [ ] **Step 1: Add an Installation section to AGENTS.md**

Open `AGENTS.md`. Find the "Build & Run" section near the top (around line 6-13). Insert a new "Installation" section *before* it:

```markdown
## Installation

Download the latest release zip, unzip it, and add `bin/` to your PATH:

```bash
curl -LO https://github.com/larvalabs/brace/releases/latest/download/brace-0.1.0.zip
unzip brace-0.1.0.zip
export PATH="$PWD/brace-0.1.0/bin:$PATH"
brace help
```

No Maven or per-project scripts needed for the dev loop. Maven is only invoked by `brace deps` to populate a project-local `lib/` folder from `pom.xml`.

```

Then update the "Build & Run" section that follows it. Replace the existing content:

```markdown
## Build & Run

```bash
brace new myapp                                 # create a new project
cd myapp
brace deps                                      # populate lib/ from pom.xml (first time)
brace compile                                   # compile
brace test                                      # run all tests
brace test app.HomeControllerTest               # run one test class
brace dev                                       # run with auto-restart on file changes
brace run                                       # run without watching
```
```

- [ ] **Step 2: Make the same updates to CLAUDE.md**

Open `CLAUDE.md`. Find the "Building and Testing" section (around line 67-74). Replace the commands with the new brace CLI equivalents:

```markdown
## Building and Testing

```bash
mvn compile          # compile brace framework itself
mvn test             # run all 409 tests
mvn package          # build distribution zip (target/brace-0.1.0-SNAPSHOT.zip)
```

For using brace as an end user (e.g., building the sample app):

```bash
cd sample && brace run     # compile and run the sample
```
```

- [ ] **Step 3: Update ClaudeMdGenerator to generate install-friendly content**

Open `src/main/java/io/brace/ClaudeMdGenerator.java`. Find the Build & Run section (around lines 23-29):

```java
sb.append("## Build & Run\n\n");
sb.append("```bash\n");
sb.append("./brace dev       # compile + run + watch for changes\n");
sb.append("./brace test      # run all tests\n");
sb.append("./brace test Name # run specific test class\n");
sb.append("```\n");
```

Replace with (noting the text block is inside a `.formatted()` call, so `%%` escapes percent signs):

```java
## Build & Run

```bash
brace deps        # copy dependencies from pom.xml into ./lib/ (first-time setup)
brace dev         # compile + run + watch for changes
brace run         # compile and run (no watching)
brace test        # run all tests
brace test Name   # run specific test class
```
```

Note: that code lives inside the single `generate` method's text block. Locate the existing `## Build & Run` block inside `ClaudeMdGenerator.generate` and replace it to match the above.

- [ ] **Step 4: Run the full brace test suite**

Run: `mvn test -q 2>&1 | grep -E "Tests run: 4|BUILD"`
Expected: `Tests run: 409, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Regenerate and inspect the CLAUDE.md that would ship with a new project**

```bash
mvn package -DskipTests -q
rm -rf /tmp/brace-test && mkdir -p /tmp/brace-test
unzip -q target/brace-0.1.0-SNAPSHOT.zip -d /tmp/brace-test/
cd /tmp && rm -rf testapp
/tmp/brace-test/brace-0.1.0-SNAPSHOT/bin/brace new testapp
grep -A 6 "Build & Run" testapp/CLAUDE.md
```

Expected: Shows the new `brace deps`/`brace dev`/`brace run`/`brace test` commands.

- [ ] **Step 6: Commit**

```bash
cd /Users/matt/code/brace
git add AGENTS.md CLAUDE.md src/main/java/io/brace/ClaudeMdGenerator.java
git commit -m "Document brace CLI install and usage in AGENTS.md, CLAUDE.md, and generator"
```

---

## Task 9: Add release build instructions to the repo

So future Brace maintainers know how to cut a release.

**Files:**
- Modify: `README.md` (if it exists) or create `RELEASE.md`

- [ ] **Step 1: Check if README.md exists**

Run: `ls README.md RELEASE.md 2>&1`

- [ ] **Step 2: Create or update RELEASE.md**

If `RELEASE.md` doesn't exist, create it with:

```markdown
# Releasing Brace

## Cutting a release

1. Update version in `pom.xml` (remove `-SNAPSHOT` for the release, e.g. `0.1.0`).
2. Commit: `git commit -am "Release 0.1.0"`.
3. Tag: `git tag v0.1.0`.
4. Build: `mvn clean package`.
5. Verify: `./tests/cli/test-distribution.sh`.
6. Publish: `gh release create v0.1.0 target/brace-0.1.0.zip --title "Brace 0.1.0" --notes "..."`.
7. Bump to next snapshot: update `pom.xml` to `0.2.0-SNAPSHOT`, commit, push.

## What's in the distribution zip

- `bin/brace` — bash launcher script
- `lib/brace-<version>.jar` — framework JAR
- `lib/*.jar` — all runtime dependencies (Jetty, Hibernate, JTE, Flyway, Jackson, jBCrypt, Jakarta Mail, JUnit Platform Console Standalone)

The zip is produced by `mvn package` via the assembly plugin configured in `src/assembly/distribution.xml`.

## User install flow

Users download the zip, unzip it, and put `bin/` on their PATH:

```bash
curl -LO https://github.com/larvalabs/brace/releases/download/v0.1.0/brace-0.1.0.zip
unzip brace-0.1.0.zip
export PATH="$PWD/brace-0.1.0/bin:$PATH"
```
```

If `README.md` exists, add an "Install" section near the top pointing to the release URL, and link to `RELEASE.md` for maintainers.

- [ ] **Step 3: Commit**

```bash
git add RELEASE.md
# Also add README.md if modified
git commit -m "Document release process"
```

---

## Final Verification

- [ ] **Run the full brace test suite**

```bash
cd /Users/matt/code/brace
mvn test -q 2>&1 | grep -E "Tests run: 4|BUILD"
```
Expected: `Tests run: 409, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Run the end-to-end distribution test**

```bash
./tests/cli/test-distribution.sh
```
Expected: `All distribution tests passed.`

- [ ] **Manually verify a fresh install works end-to-end**

```bash
rm -rf /tmp/brace-smoke && mkdir /tmp/brace-smoke
cd /tmp/brace-smoke
unzip -q /Users/matt/code/brace/target/brace-0.1.0-SNAPSHOT.zip
export PATH="$PWD/brace-0.1.0-SNAPSHOT/bin:$PATH"
brace help
brace new demo
cd demo
brace compile
brace test
```

Expected: Each command succeeds without errors. `brace test` runs the generated `HomeControllerTest`.
