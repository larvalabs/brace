# Shared helpers for the benchmark scripts. Source it, don't run it:
#   source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
#
# Two parts:
#   - setup used by every script: JDK selection, framework version, building the benchmark jar;
#   - the TechEmpower-style suite driver used by run-brace.sh and run-spring.sh, so both
#     frameworks get exactly the same checks, warmups, wrk runs and summary.

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

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }

# ---- TechEmpower-style suite -----------------------------------------------------------------

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
  : "${RUNS:=3}"                # measured passes over the whole suite; the summary reports medians
  : "${JVM_OPTS:=}"             # extra JVM flags, e.g. "-Xmx80m -XX:+UseSerialGC"
  : "${RESULTS_DIR:=$BENCH_DIR/results}"   # git-ignored; outside target/, which every build cleans
  export PORT DB_HOST DB_PORT DB_POOL
  mkdir -p "$RESULTS_DIR"
}

# Tee everything from here on into $RESULTS_DIR/<timestamp>-<name>-<sha>.txt.
tfb_record_output() { # name
  local sha
  sha="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo nogit)"
  if [ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]; then sha="$sha-dirty"; fi
  mkdir -p "$RESULTS_DIR"
  OUT="$RESULTS_DIR/$(date +%Y-%m-%d-%H%M%S)-$1-$sha.txt"
  TSV="${OUT%.txt}.tsv"
  : > "$TSV"
  exec > >(tee "$OUT") 2>&1
}

tfb_preflight() {
  if ! nc -z -w 2 "$DB_HOST" "$DB_PORT" 2>/dev/null; then
    echo "FATAL: no Postgres at $DB_HOST:$DB_PORT. Start the TFB database (benchmark/README.md):" >&2
    echo "  docker start tfb-postgres" >&2
    exit 1
  fi
  if lsof -t -iTCP:"$PORT" -sTCP:LISTEN > /dev/null 2>&1; then
    echo "FATAL: something is already listening on port $PORT (PID $(lsof -t -iTCP:"$PORT" -sTCP:LISTEN | head -1)); set PORT or stop it" >&2
    exit 1
  fi
}

# Everything needed to judge or reproduce the run.
tfb_print_environment() { # framework description
  echo "=== $1 ==="
  echo "date:        $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit:      $(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null)$( [ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ] && echo ' (dirty)')"
  echo "java:        $(java -version 2>&1 | head -1) ($JAVA_HOME)"
  echo "jvm opts:    ${JVM_OPTS:-(none)}"
  if [ "$(uname)" = "Darwin" ]; then
    echo "machine:     $(sysctl -n hw.model) / $(sysctl -n machdep.cpu.brand_string) / $(sysctl -n hw.ncpu) cpus / $(( $(sysctl -n hw.memsize) / 1073741824 )) GB"
  else
    echo "machine:     $(uname -m) / $(nproc) cpus / $(awk '/MemTotal/ {printf "%d GB", $2/1048576}' /proc/meminfo)"
  fi
  echo "os:          $(uname -sr)"
  echo "postgres:    $DB_HOST:$DB_PORT, app pool $DB_POOL"
  echo "load:        wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION --latency, $WARMUP_DURATION warmup per test, $RUNS runs"
  echo "load avg:    $(uptime | sed 's/.*load averages*: //')  (quiet machine: close other apps first)"
  echo ""
}

# Launch the app in the background (cwd = repo root), wait until it answers, and record the
# time to first 200. Usage: tfb_start_app <logfile> java ...args
tfb_start_app() {
  local log=$1; shift
  local t0 t1
  t0=$(now_ms)
  ( cd "$REPO_ROOT" && exec "$@" ) > "$log" 2>&1 &
  APP_PID=$!
  trap tfb_stop_app EXIT
  local ok=0
  for _ in $(seq 1 1200); do   # up to 120s
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      echo "FATAL: the app exited during startup; last lines of $log:" >&2
      tail -20 "$log" >&2
      exit 1
    fi
    if curl -sf "http://localhost:$PORT/plaintext" > /dev/null 2>&1; then ok=1; break; fi
    sleep 0.1
  done
  t1=$(now_ms)
  if [ "$ok" != 1 ]; then
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
  echo "rss ready:   $(rss_mb) MB"
}

tfb_stop_app() {
  [ -n "${APP_PID:-}" ] || return 0
  kill "$APP_PID" 2>/dev/null || true
  for _ in $(seq 1 40); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.5; done
  kill -9 "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=
}

rss_mb() { ps -o rss= -p "$APP_PID" 2>/dev/null | awk '{printf "%d", $1/1024}'; }

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

tfb_warmup() {
  local t
  for t in "${TFB_TESTS[@]}"; do
    wrk -t"$WRK_THREADS" -c"$WRK_CONNECTIONS" -d"$WARMUP_DURATION" "http://localhost:$PORT/$t" > /dev/null 2>&1
  done
  echo "rss warm:    $(rss_mb) MB (after warmup)"
}

# Peak RSS of the app while a wrk run is in progress, sampled every 0.5s into $1.
sample_peak_rss() {
  local peak=0 r
  echo 0 > "$1"
  while kill -0 "$APP_PID" 2>/dev/null; do
    r=$(ps -o rss= -p "$APP_PID" 2>/dev/null | tr -d ' ')
    if [ -n "$r" ] && [ "$r" -gt "$peak" ]; then peak=$r; echo "$peak" > "$1"; fi
    sleep 0.5
  done
}

# wrk latency value ("850.12us", "4.71ms", "1.05s") -> milliseconds
to_ms() {
  awk -v v="$1" 'BEGIN {
    if (v ~ /us$/) { sub(/us$/, "", v); printf "%.2f", v / 1000 }
    else if (v ~ /ms$/) { sub(/ms$/, "", v); printf "%.2f", v }
    else if (v ~ /s$/)  { sub(/s$/, "", v);  printf "%.2f", v * 1000 }
    else printf "%s", v }'
}

# Run passes interleaved (run 1 of every test, then run 2, ...) so slow drift on the machine
# spreads across tests instead of landing on one. Raw wrk output is printed; one TSV row per
# test per run goes to $TSV.
tfb_run_suite() {
  local run i t label raw rps p99 errs non2xx peakfile sampler
  peakfile=$(mktemp)
  for run in $(seq 1 "$RUNS"); do
    for i in "${!TFB_TESTS[@]}"; do
      t=${TFB_TESTS[$i]}; label=${TFB_LABELS[$i]}
      echo "--- $label (run $run/$RUNS) ---"
      sample_peak_rss "$peakfile" &
      sampler=$!
      raw=$(wrk -t"$WRK_THREADS" -c"$WRK_CONNECTIONS" -d"$WRK_DURATION" --latency "http://localhost:$PORT/$t")
      kill "$sampler" 2>/dev/null || true; wait "$sampler" 2>/dev/null || true
      echo "$raw"
      echo ""
      rps=$(echo "$raw" | awk '/^Requests\/sec/ {print $2}')
      p99=$(to_ms "$(echo "$raw" | awk '$1 == "99%" {print $2}')")
      errs=$(echo "$raw" | awk '/Socket errors/ {gsub(/,/, ""); print $4 + $6 + $8 + $10}')
      non2xx=$(echo "$raw" | awk '/Non-2xx/ {print $NF}')
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$run" "${rps:-0}" "${p99:-0}" \
        "$(( ${errs:-0} + ${non2xx:-0} ))" "$(( $(cat "$peakfile") / 1024 ))" >> "$TSV"
    done
  done
  rm -f "$peakfile"
}

median() { sort -n | awk '{ v[NR] = $1 } END { if (NR) print v[int((NR + 1) / 2)] }'; }

tfb_summary() { # framework name
  echo "=== Summary: $1 (median of $RUNS runs; startup ${STARTUP_MS} ms) ==="
  printf '%-24s %12s %10s %8s %10s   %s\n' "Test" "Req/sec" "p99 ms" "Errors" "Peak RSS" "Req/sec per run"
  local label rps p99 errs rss runs
  for label in "${TFB_LABELS[@]}"; do
    rps=$(awk -F'\t' -v l="$label" '$1 == l {print $3}' "$TSV" | median)
    p99=$(awk -F'\t' -v l="$label" '$1 == l {print $4}' "$TSV" | median)
    errs=$(awk -F'\t' -v l="$label" '$1 == l {s += $5} END {print s + 0}' "$TSV")
    rss=$(awk -F'\t' -v l="$label" '$1 == l && $6 > m {m = $6} END {print m + 0}' "$TSV")
    runs=$(awk -F'\t' -v l="$label" '$1 == l {printf "%s ", $3}' "$TSV")
    printf '%-24s %12s %10s %8s %7s MB   %s\n' "$label" "$rps" "$p99" "$errs" "$rss" "$runs"
  done
  echo ""
  echo "Errors = wrk socket errors + non-2xx responses, summed over all runs. A nonzero count"
  echo "means that row's throughput is not comparable."
  echo "Raw output: $OUT"
  echo "Per-run rows: $TSV"
}
