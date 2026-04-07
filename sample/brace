#!/usr/bin/env bash
set -euo pipefail

# Brace CLI — project-local development commands
# Copy this file to your project root as ./brace and chmod +x it.

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

PID_FILE="target/.brace-pid"
CP_CACHE="target/.brace-classpath"

info()    { echo -e "${YELLOW}▸${NC} $*"; }
success() { echo -e "${GREEN}✓${NC} $*"; }
error()   { echo -e "${RED}✗${NC} $*" >&2; }

# --- Compile ---

brace_compile() {
    info "Compiling..."
    if mvn compile -q; then
        success "Compiled"
    else
        error "Compilation failed"
        return 1
    fi
}

# --- Test ---

brace_test() {
    if [[ $# -gt 0 ]]; then
        info "Running test: $1"
        mvn test -Dtest="$1" -q
    else
        info "Running all tests..."
        mvn test -q
    fi
}

# --- Find main class ---

find_main_class() {
    local main_file
    main_file=$(grep -rl 'public static void main' src/main/java/ --include='*.java' | head -1)
    if [[ -z "$main_file" ]]; then
        error "No main class found in src/main/java/"
        exit 1
    fi
    # Convert file path to fully qualified class name
    # e.g. src/main/java/com/example/App.java -> com.example.App
    local class_name
    class_name="${main_file#src/main/java/}"
    class_name="${class_name%.java}"
    class_name="${class_name//\//.}"
    echo "$class_name"
}

# --- Build classpath ---

build_classpath() {
    # Use cached classpath if pom.xml hasn't changed
    if [[ -f "$CP_CACHE" && -f pom.xml && "$CP_CACHE" -nt pom.xml ]]; then
        cat "$CP_CACHE"
        return
    fi
    info "Resolving dependencies..."
    local deps
    deps=$(mvn dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)
    local cp="target/classes:${deps}"
    mkdir -p target
    echo "$cp" > "$CP_CACHE"
    echo "$cp"
}

# --- Run (compile + start, no watch) ---

brace_run() {
    brace_compile
    local main_class
    main_class=$(find_main_class)
    local cp
    cp=$(build_classpath)
    info "Starting ${CYAN}${main_class}${NC}"
    exec java -cp "$cp" "$main_class"
}

# --- Kill background app ---

kill_app() {
    if [[ -f "$PID_FILE" ]]; then
        local pid
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            # Wait briefly for graceful shutdown
            for _ in 1 2 3 4 5; do
                kill -0 "$pid" 2>/dev/null || break
                sleep 0.2
            done
            # Force kill if still alive
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
        fi
        rm -f "$PID_FILE"
    fi
}

# --- Start app in background ---

start_app() {
    local main_class="$1"
    local cp="$2"
    java -cp "$cp" "$main_class" &
    local pid=$!
    echo "$pid" > "$PID_FILE"
    success "Started (PID ${pid})"
}

# --- Dev (compile + run + watch) ---

brace_dev() {
    # Ensure cleanup on exit
    trap 'echo ""; info "Shutting down..."; kill_app; exit 0' INT TERM

    # Initial compile
    brace_compile || exit 1

    local main_class
    main_class=$(find_main_class)
    local cp
    cp=$(build_classpath)

    info "Main class: ${CYAN}${main_class}${NC}"
    start_app "$main_class" "$cp"

    info "Watching ${CYAN}src/${NC} for changes..."
    echo ""

    if [[ "$(uname)" == "Darwin" ]] && command -v fswatch &>/dev/null; then
        # macOS with fswatch — efficient event-based watching
        fswatch -0 --include='\.java$' --exclude='.*' -r src/ | while IFS= read -r -d '' _; do
            # Drain any queued events (batch changes together)
            while IFS= read -r -d '' -t 0.5 _ 2>/dev/null; do :; done

            echo ""
            info "Change detected — recompiling..."
            kill_app

            if mvn compile -q; then
                # Rebuild classpath if pom.xml changed
                cp=$(build_classpath)
                start_app "$main_class" "$cp"
            else
                error "Compilation failed — waiting for next change..."
            fi
        done
    else
        # Fallback: polling with find -newer
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

                if mvn compile -q; then
                    cp=$(build_classpath)
                    start_app "$main_class" "$cp"
                else
                    error "Compilation failed — waiting for next change..."
                fi
            fi
        done
    fi
}

# --- Help ---

brace_help() {
    echo ""
    echo -e "${BOLD}Brace${NC} — development CLI"
    echo ""
    echo -e "  ${CYAN}./brace dev${NC}            Compile, run, and auto-restart on changes"
    echo -e "  ${CYAN}./brace run${NC}            Compile and run"
    echo -e "  ${CYAN}./brace compile${NC}        Compile the project"
    echo -e "  ${CYAN}./brace test${NC}           Run all tests"
    echo -e "  ${CYAN}./brace test ${YELLOW}Name${NC}      Run a specific test class"
    echo -e "  ${CYAN}./brace help${NC}           Show this help"
    echo ""
}

# --- Main ---

COMMAND="${1:-help}"
shift 2>/dev/null || true

case "$COMMAND" in
    dev)     brace_dev ;;
    test)    brace_test "$@" ;;
    run)     brace_run ;;
    compile) brace_compile ;;
    help|*)  brace_help ;;
esac
