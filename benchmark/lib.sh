# Shared helpers for the benchmark scripts. Source it, don't run it:
#   source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
#
# Two parts:
#   - setup used by every script: JDK selection, framework version, building the benchmark jar;
#   - the TechEmpower-style suite used by run-brace.sh and run-docker.sh, so every framework
#     gets exactly the same checks, warmups, wrk runs and summary.

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$BENCH_DIR/.." && pwd)"
BENCH_JAR_DEFAULT="$BENCH_DIR/target/brace-benchmark.jar"

# ---- Setup ---------------------------------------------------------------------------------

# JDK 25 LTS (JEP 491: no virtual-thread pinning on synchronized; see AGENTS.md). Deliberately
# ignores an ambient JAVA_HOME (jenv and friends set one globally, which silently put earlier
# runs on the wrong JDK); pick another JDK explicitly with BENCH_JAVA_HOME.
use_jdk() {
  if [ -n "${BENCH_JAVA_HOME:-}" ]; then
    JAVA_HOME="$BENCH_JAVA_HOME"
  else
    local brew=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
    if [ -x "$brew/bin/java" ]; then
      JAVA_HOME="$brew"
    elif [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 25 > /dev/null 2>&1; then
      JAVA_HOME="$(/usr/libexec/java_home -v 25)"
    else
      echo "FATAL: no JDK 25 found; set BENCH_JAVA_HOME" >&2
      exit 1
    fi
  fi
  if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "FATAL: no java at $JAVA_HOME/bin/java" >&2
    exit 1
  fi
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
}

# The framework version in the root pom.xml (the single source of truth).
framework_version() {
  sed -n 's:^    <version>\(.*\)</version>.*:\1:p' "$REPO_ROOT/pom.xml" | head -1
}

# Sets JAR to the benchmark jar to run.
#   BENCH_JAR=path   use that jar as-is (before/after comparisons across framework commits)
#   SKIP_BUILD=1     reuse target/brace-benchmark.jar as-is
#   otherwise        install this checkout's framework, then `clean package` the benchmark
#                    against exactly that version. Always clean: shade silently reuses stale
#                    output otherwise, and an old jar would measure an old framework.
ensure_jar() {
  if [ -n "${BENCH_JAR:-}" ]; then
    JAR="$BENCH_JAR"
  elif [ "${SKIP_BUILD:-0}" = "1" ]; then
    JAR="$BENCH_JAR_DEFAULT"
  else
    local version
    version="$(framework_version)"
    echo "Installing framework $version (tests skipped) and rebuilding the benchmark jar..."
    ( cd "$REPO_ROOT" && mvn install -q -DskipTests ) || { echo "FATAL: framework install failed" >&2; exit 1; }
    ( cd "$BENCH_DIR" && mvn clean package -q -DskipTests -Dbrace.version="$version" ) \
      || { echo "FATAL: benchmark build failed" >&2; exit 1; }
    JAR="$BENCH_JAR_DEFAULT"
  fi
  if [ ! -f "$JAR" ]; then
    echo "FATAL: benchmark jar not found: $JAR" >&2
    exit 1
  fi
}

# Milliseconds since the epoch (GNU date on Linux, perl on macOS, where date has no %N).
now_ms() {
  local t
  t=$(date +%s%3N 2>/dev/null)
  case "$t" in
    *[!0-9]*|"") perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000' ;;
    *) echo "$t" ;;
  esac
}

# ---- TechEmpower-style suite -----------------------------------------------------------------
#
# Used by the local driver (run-brace.sh: app and wrk as processes on this
# machine) and the Docker driver (run-docker.sh: everything in pinned containers). The driver
# owns starting and stopping the app; these functions do the checks, load, parsing and summary.
#
# Hooks a driver may redefine after sourcing this file (defaults are the local ones):
#   load_url <path>   URL the load generator uses for a test path
#   run_wrk <args>    run wrk with these arguments
#   mem_mark          start tracking the app's peak memory
#   mem_peak_mb       peak memory (MB) since mem_mark
#   mem_now_mb        current memory (MB)
#   WRK_REPORT        path of docker/wrk-report.lua as wrk sees it

TFB_TESTS=("plaintext" "json" "db" "queries?queries=20" "fortunes" "updates?queries=20")
TFB_LABELS=("Plaintext" "JSON" "Single Query" "Multiple Queries (20)" "Fortunes" "Updates (20)")

# Defaults, all overridable from the environment.
tfb_init() {
  : "${PORT:=8080}"
  : "${DB_HOST:=localhost}"
  : "${DB_PORT:=5434}"          # tfb-postgres container, see benchmark/README.md
  : "${DB_POOL:=256}"           # what the TechEmpower Spring implementation uses
  : "${WRK_THREADS:=8}"
  : "${WRK_CONNECTIONS:=256}"
  : "${WRK_DURATION:=15s}"
  : "${WARMUP_DURATION:=5s}"
  : "${RUNS:=3}"                # measured passes over the whole suite per app launch
  : "${JVM_OPTS:=}"             # extra JVM flags, e.g. "-Xmx80m -XX:+UseSerialGC"
  : "${RESULTS_DIR:=$BENCH_DIR/results}"   # git-ignored; outside target/, which every build cleans
  export PORT DB_HOST DB_PORT DB_POOL
  mkdir -p "$RESULTS_DIR"
}

# The checkout's commit (with -dirty for uncommitted changes). BRACE_COMMIT overrides it, for
# copies without .git (benchmark/cloud/do-run.sh copies the working tree to the droplet).
commit_desc() {
  if [ -n "${BRACE_COMMIT:-}" ]; then echo "$BRACE_COMMIT"; return; fi
  local sha
  sha="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo nogit)"
  if [ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]; then sha="$sha-dirty"; fi
  echo "$sha"
}

# Tee everything from here on into $RESULTS_DIR/<timestamp>-<name>-<sha>.txt, with one TSV row
# per measurement next to it: framework, round, test, value, p99 ms, errors, peak MB.
# (Rows with a test of _startup / _mem_ready / _mem_warm carry ms or MB in the value column.)
tfb_record_output() { # name
  mkdir -p "$RESULTS_DIR"
  OUT="$RESULTS_DIR/$(date +%Y-%m-%d-%H%M%S)-$1-$(commit_desc).txt"
  TSV="${OUT%.txt}.tsv"
  : > "$TSV"
  exec > >(tee "$OUT") 2>&1
}

# Columns: 1 framework, 2 round, 3 test, 4 value (req/s, or ms/MB for _ rows), 5 p99 ms,
# 6 errors, 7 peak MB, 8 p50 ms, 9 p90 ms, 10 p95 ms, 11 p99.9 ms, 12 max ms.
tsv_row() { local IFS=$'\t'; echo "$*" >> "$TSV"; }

tfb_preflight() {
  if ! nc -z -w 2 "$DB_HOST" "$DB_PORT" 2>/dev/null; then
    echo "FATAL: no Postgres at $DB_HOST:$DB_PORT. Start the TFB database (benchmark/README.md):" >&2
    echo "  docker start tfb-postgres" >&2
    exit 1
  fi
  if port_in_use "$PORT"; then
    echo "FATAL: something is already listening on port $PORT; set PORT or stop it" >&2
    exit 1
  fi
}

port_in_use() {
  if command -v lsof > /dev/null 2>&1; then
    lsof -t -iTCP:"$1" -sTCP:LISTEN > /dev/null 2>&1
  else
    ss -ltn 2>/dev/null | awk '{print $4}' | grep -q ":$1\$"
  fi
}

machine_desc() {
  if [ "$(uname)" = "Darwin" ]; then
    echo "$(sysctl -n hw.model) / $(sysctl -n machdep.cpu.brand_string) / $(sysctl -n hw.ncpu) cpus / $(( $(sysctl -n hw.memsize) / 1073741824 )) GB"
  else
    echo "$(awk -F': ' '/model name/ {print $2; exit}' /proc/cpuinfo) / $(nproc) cpus / $(awk '/MemTotal/ {printf "%d GB", $2/1048576}' /proc/meminfo)"
  fi
}

# Everything needed to judge or reproduce a local run.
tfb_print_environment() { # description
  echo "=== $1 ==="
  echo "date:        $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit:      $(commit_desc)"
  echo "java:        $(java -version 2>&1 | head -1) ($JAVA_HOME)"
  echo "jvm opts:    ${JVM_OPTS:-(none)}"
  echo "machine:     $(machine_desc)"
  echo "os:          $(uname -sr)"
  echo "postgres:    $DB_HOST:$DB_PORT, app pool $DB_POOL"
  echo "load:        wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION --latency, $WARMUP_DURATION warmup per test, $RUNS runs"
  echo "load avg:    $(uptime | sed 's/.*load averages*: //')  (quiet machine: close other apps first)"
  echo ""
}

# Wait until the app answers /plaintext, at most 120s. $1 is a command that succeeds while the
# app is still alive (so a crash fails fast instead of waiting out the timeout).
wait_until_ready() {
  local alive=$1
  for _ in $(seq 1 1200); do
    if ! $alive; then return 2; fi
    if curl -sf "http://localhost:$PORT/plaintext" > /dev/null 2>&1; then return 0; fi
    sleep 0.1
  done
  return 1
}

# Local mode: launch the app as a background process (cwd = repo root), wait until it
# answers, and record the time to first 200. Usage: tfb_start_app <framework> <logfile> java ...
tfb_start_app() {
  local fw=$1 log=$2; shift 2
  local t0 t1 rc=0
  t0=$(now_ms)
  ( cd "$REPO_ROOT" && exec "$@" ) > "$log" 2>&1 &
  APP_PID=$!
  trap tfb_stop_app EXIT
  wait_until_ready app_pid_alive || rc=$?
  t1=$(now_ms)
  if [ "$rc" = 2 ]; then
    echo "FATAL: the app exited during startup; last lines of $log:" >&2
    tail -20 "$log" >&2
    exit 1
  elif [ "$rc" != 0 ]; then
    echo "FATAL: the app did not answer on port $PORT within 120s; see $log" >&2
    exit 1
  fi
  # Guard against a stale/foreign process answering on the port (a failed bind would
  # otherwise let wrk measure the wrong server).
  local listener
  listener=$(lsof -t -iTCP:"$PORT" -sTCP:LISTEN | head -1)
  if [ "$listener" != "$APP_PID" ]; then
    echo "FATAL: port $PORT is served by PID ${listener:-none}, not the benchmark app ($APP_PID)" >&2
    exit 1
  fi
  STARTUP_MS=$((t1 - t0))
  echo "startup:     ${STARTUP_MS} ms to first 200 (JVM launch to /plaintext answering)"
  tsv_row "$fw" 1 _startup "$STARTUP_MS" 0 0 0
  local m; m=$(mem_now_mb)
  echo "rss ready:   $m MB"
  tsv_row "$fw" 1 _mem_ready "$m" 0 0 0
}

app_pid_alive() { kill -0 "$APP_PID" 2>/dev/null; }

tfb_stop_app() {
  [ -n "${APP_PID:-}" ] || return 0
  kill "$APP_PID" 2>/dev/null || true
  for _ in $(seq 1 40); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.5; done
  kill -9 "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=
}

# ---- Local hooks -----------------------------------------------------------------------------

load_url() { echo "http://localhost:$PORT/$1"; }
WRK_REPORT="$BENCH_DIR/docker/wrk-report.lua"
run_wrk() { wrk "$@"; }
mem_now_mb() { ps -o rss= -p "$APP_PID" 2>/dev/null | awk '{printf "%d", $1/1024}'; }
mem_mark() {
  PEAK_FILE=$(mktemp)
  sample_peak_rss "$PEAK_FILE" &
  SAMPLER_PID=$!
}
mem_peak_mb() {
  kill "$SAMPLER_PID" 2>/dev/null || true
  wait "$SAMPLER_PID" 2>/dev/null || true
  echo $(( $(cat "$PEAK_FILE") / 1024 ))
  rm -f "$PEAK_FILE"
}

# Peak RSS of the app process, sampled every 0.5s into $1 (KB).
sample_peak_rss() {
  local peak=0 r
  echo 0 > "$1"
  while kill -0 "$APP_PID" 2>/dev/null; do
    r=$(ps -o rss= -p "$APP_PID" 2>/dev/null | tr -d ' ')
    if [ -n "$r" ] && [ "$r" -gt "$peak" ]; then peak=$r; echo "$peak" > "$1"; fi
    sleep 0.5
  done
}

# ---- Checks, load, summary -------------------------------------------------------------------

# Fail before spending wrk time if an endpoint doesn't do what the TFB spec says. The same
# checks run against every framework.
tfb_check_responses() {
  local base="http://localhost:$PORT" body n
  fail() { echo "FATAL: $1" >&2; exit 1; }
  body=$(curl -sf "$base/plaintext") || fail "/plaintext did not return 200"
  [ "$body" = "Hello, World!" ] || fail "/plaintext body: $body"
  body=$(curl -sf "$base/json") || fail "/json did not return 200"
  # Case-insensitive, like TechEmpower's validator: the TFB Spring app sends "Hello, world!".
  grep -qi '"message" *: *"Hello, World!"' <<< "$body" || fail "/json body: $body"
  body=$(curl -sf "$base/db") || fail "/db did not return 200"
  grep -q '"randomNumber"' <<< "$body" || fail "/db body: $body"
  body=$(curl -sf "$base/queries?queries=20") || fail "/queries did not return 200"
  n=$(echo "$body" | grep -o '"randomNumber"' | wc -l | tr -d ' ')
  [ "$n" = 20 ] || fail "/queries?queries=20 returned $n rows"
  body=$(curl -sf "$base/fortunes") || fail "/fortunes did not return 200"
  grep -q 'Additional fortune added at request time.' <<< "$body" || fail "/fortunes is missing the added fortune"
  grep -q '&lt;script&gt;' <<< "$body" || fail "/fortunes does not HTML-escape the <script> fortune"
  body=$(curl -sf "$base/updates?queries=20") || fail "/updates did not return 200"
  n=$(echo "$body" | grep -o '"randomNumber"' | wc -l | tr -d ' ')
  [ "$n" = 20 ] || fail "/updates?queries=20 returned $n rows"
  echo "checks:      all six endpoints return TFB-shaped responses"
}

tfb_warmup() { # framework round
  local t m
  for t in "${TFB_TESTS[@]}"; do
    run_wrk -t"$WRK_THREADS" -c"$WRK_CONNECTIONS" -d"$WARMUP_DURATION" "$(load_url "$t")" > /dev/null 2>&1
  done
  m=$(mem_now_mb)
  echo "mem warm:    $m MB (after warmup)"
  tsv_row "$1" "$2" _mem_warm "$m" 0 0 0
}

# One measured wrk run of test $3 (an index into TFB_TESTS). Prints the raw output and
# appends a TSV row. Percentiles come from docker/wrk-report.lua (microsecond precision, and
# p95 / p99.9, which wrk's own --latency table doesn't print).
tfb_measure() { # framework round index [tag]
  local fw=$1 round=$2 i=$3 tag=${4:-} raw rps errs non2xx peak lat
  echo "--- ${TFB_LABELS[$i]} ($fw${tag:+, $tag}) ---"
  mem_mark
  raw=$(run_wrk -t"$WRK_THREADS" -c"$WRK_CONNECTIONS" -d"$WRK_DURATION" --latency -s "$WRK_REPORT" \
    "$(load_url "${TFB_TESTS[$i]}")")
  peak=$(mem_peak_mb)
  echo "$raw"
  echo ""
  rps=$(echo "$raw" | awk '/^Requests\/sec/ {print $2}')
  errs=$(echo "$raw" | awk '/Socket errors/ {gsub(/,/, ""); print $4 + $6 + $8 + $10}')
  non2xx=$(echo "$raw" | awk '/Non-2xx/ {print $NF}')
  # "p50 p90 p95 p99 p999 max" in ms
  lat=$(echo "$raw" | awk '/^TFB_LATENCY_US/ {
    for (i = 2; i <= NF; i++) { split($i, kv, "="); v[kv[1]] = kv[2] / 1000 }
    printf "%.2f %.2f %.2f %.2f %.2f %.2f", v["p50"], v["p90"], v["p95"], v["p99"], v["p999"], v["max"] }')
  [ -n "$lat" ] || lat="0 0 0 0 0 0"
  set -- $lat
  tsv_row "$fw" "$round" "${TFB_LABELS[$i]}" "${rps:-0}" "$4" "$(( ${errs:-0} + ${non2xx:-0} ))" "${peak:-0}" \
    "$1" "$2" "$3" "$5" "$6"
}

# RUNS passes, interleaved (run 1 of every test, then run 2, ...) so slow drift on the machine
# spreads across tests instead of landing on one.
tfb_run_suite() { # framework [round]
  local run i
  for run in $(seq 1 "$RUNS"); do
    for i in "${!TFB_TESTS[@]}"; do
      tfb_measure "$1" "${2:-$run}" "$i" "run $run/$RUNS"
    done
  done
}

median() { sort -n | awk '{ v[NR] = $1 } END { if (NR) print v[int((NR + 1) / 2)] }'; }

# Column $2 of the rows for framework $1 and test $3, one value per line.
tsv_col() { awk -F'\t' -v f="$1" -v t="$3" -v c="$2" '$1 == f && $3 == t {print $c}' "$TSV"; }

ratio_of() { awk -v a="$1" -v b="$2" 'BEGIN { if (a != "" && b > 0) printf "%.2fx", a / b; else print "n/a" }'; }

tfb_summary() {
  local fw label rps p50 p95 p99 errs peak runs n subject gc
  for fw in $(awk -F'\t' '!seen[$1]++ {print $1}' "$TSV"); do
    n=$(tsv_col "$fw" 4 "${TFB_LABELS[0]}" | wc -l | tr -d ' ')
    echo "=== Summary: $fw (median of $n measurements) ==="
    echo "startup:     $(tsv_col "$fw" 4 _startup | median) ms to first 200 (median; all: $(tsv_col "$fw" 4 _startup | tr '\n' ' '))"
    echo "memory:      $(tsv_col "$fw" 4 _mem_ready | median) MB ready, $(tsv_col "$fw" 4 _mem_warm | median) MB after warmup (medians)"
    gc=$(tsv_col "$fw" 4 _gc_name | head -1)
    if [ -n "$gc" ]; then
      echo "gc:          $gc, per launch (medians): $(tsv_col "$fw" 4 _gc_pauses | median) pauses, $(tsv_col "$fw" 4 _gc_total_ms | median) ms total, longest $(tsv_col "$fw" 4 _gc_max_ms | median) ms"
    fi
    printf '%-24s %11s %8s %8s %8s %8s %7s %9s   %s\n' "Test" "Req/sec" "p50 ms" "p95 ms" "p99 ms" "max ms" "Errors" "Peak mem" "Req/sec per measurement"
    for label in "${TFB_LABELS[@]}"; do
      printf '%-24s %11s %8s %8s %8s %8s %7s %6s MB   %s\n' "$label" \
        "$(tsv_col "$fw" 4 "$label" | median)" \
        "$(tsv_col "$fw" 8 "$label" | median)" \
        "$(tsv_col "$fw" 10 "$label" | median)" \
        "$(tsv_col "$fw" 5 "$label" | median)" \
        "$(tsv_col "$fw" 12 "$label" | median)" \
        "$(tsv_col "$fw" 6 "$label" | awk '{s += $1} END {print s + 0}')" \
        "$(tsv_col "$fw" 7 "$label" | awk '$1 > m {m = $1} END {print m + 0}')" \
        "$(tsv_col "$fw" 4 "$label" | tr '\n' ' ')"
    done
    echo ""
  done

  # With several frameworks in one file: Brace against each of the others (or, without a Brace
  # row, the first framework against the rest).
  subject=$(awk -F'\t' '$1 ~ /^brace/ {print $1; exit}' "$TSV")
  [ -n "$subject" ] || subject=$(awk -F'\t' 'NR == 1 {print $1}' "$TSV")
  for fw in $(awk -F'\t' -v f="$subject" '!seen[$1]++ && $1 != f {print $1}' "$TSV"); do
    echo "=== $subject vs $fw (medians; req/s >1x: $subject is faster; latency <1x: $subject is quicker) ==="
    printf '%-24s %9s %9s %9s %9s\n' "Test" "req/s" "p50" "p95" "p99"
    for label in "${TFB_LABELS[@]}"; do
      printf '%-24s %9s %9s %9s %9s\n' "$label" \
        "$(ratio_of "$(tsv_col "$subject" 4 "$label" | median)" "$(tsv_col "$fw" 4 "$label" | median)")" \
        "$(ratio_of "$(tsv_col "$subject" 8 "$label" | median)" "$(tsv_col "$fw" 8 "$label" | median)")" \
        "$(ratio_of "$(tsv_col "$subject" 10 "$label" | median)" "$(tsv_col "$fw" 10 "$label" | median)")" \
        "$(ratio_of "$(tsv_col "$subject" 5 "$label" | median)" "$(tsv_col "$fw" 5 "$label" | median)")"
    done
    printf '%-24s %9s\n' "Startup time" \
      "$(ratio_of "$(tsv_col "$subject" 4 _startup | median)" "$(tsv_col "$fw" 4 _startup | median)")"
    printf '%-24s %9s\n' "Memory after warmup" \
      "$(ratio_of "$(tsv_col "$subject" 4 _mem_warm | median)" "$(tsv_col "$fw" 4 _mem_warm | median)")"
    echo ""
  done

  echo "Errors = wrk socket errors + non-2xx responses, summed over all measurements. A nonzero"
  echo "count means that row's throughput is not comparable."
  echo "Raw output: $OUT"
  echo "Per-measurement rows: $TSV"
}
