#!/usr/bin/env bash
set -euo pipefail

# TechEmpower-style suite against the Brace benchmark app (benchmark/src/main/java/benchmark/App.java).
# Same driver as run-spring.sh (lib.sh): response checks, warmup, RUNS interleaved wrk passes,
# medians, startup time and RSS. Output is also saved under benchmark/results/.
#
# Environment (defaults in lib.sh): PORT, DB_HOST, DB_PORT, DB_POOL, WRK_THREADS,
# WRK_CONNECTIONS, WRK_DURATION, WARMUP_DURATION, RUNS, JVM_OPTS, BENCH_JAVA_HOME,
# BENCH_JAR / SKIP_BUILD (see ensure_jar), BRACE_LOG_LEVEL.

source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
use_jdk
tfb_init
tfb_preflight
ensure_jar

# Per-request logging off, as TechEmpower requires and as run-spring.sh does for Spring
# (-Dlogging.level.root=OFF). Brace's default (DEBUG) logs a JSON line per request.
export BRACE_LOG_LEVEL="${BRACE_LOG_LEVEL:-WARN}"

tfb_record_output brace
tfb_print_environment "Brace $(framework_version) TFB benchmark ($JAR)"
echo "brace:       BRACE_LOG_LEVEL=$BRACE_LOG_LEVEL, -Dbrace.mode=prod"

# brace.mode=prod: production template loading (precompiled or compiled once at startup, no
# per-render staleness checks), which is how a deployed app runs.
# shellcheck disable=SC2086
tfb_start_app brace "$RESULTS_DIR/brace-app.log" \
  java --enable-preview -Dbrace.mode=prod $JVM_OPTS -jar "$JAR"
tfb_check_responses
tfb_warmup brace 1
echo ""
tfb_run_suite brace
tfb_summary
