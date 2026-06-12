#!/usr/bin/env bash
set -euo pipefail

# Mixed web+jobs benchmark, half 2 of gap #4 (H4 — see
# docs/2026-06-11-runtime-performance-review-todos.md). Starts benchmark.JobsApp,
# enqueues a burst of slow durable jobs (each holds a pooled connection while it
# sleeps), waits for the poller to claim them, then drives wrk at /ping-db while
# the burst churns. Compare /ping-db p99 + error count pre vs post H4.
#
# Quiet-window protocol applies (this is a throughput/latency run).
# BENCH_JAR override as in run-session.sh; verify JobPoller.class differs.

# JDK 25+ recommended (JEP 491: no virtual-thread pinning on synchronized) — see AGENTS.md.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${BENCH_JAR:-$SCRIPT_DIR/target/brace-benchmark-0.2.0-SNAPSHOT.jar}"
export PORT=${PORT:-8091}
WRK_THREADS=${WRK_THREADS:-8}
WRK_CONNECTIONS=${WRK_CONNECTIONS:-256}
WRK_DURATION=${WRK_DURATION:-15s}
WARMUP_DURATION=${WARMUP_DURATION:-5s}
# Burst sized so slow jobs outlast the wrk run on both sides of the comparison:
# pre-H4 drains 50×3s waves, post-H4 5×3s waves.
BURST_N=${BURST_N:-300}
BURST_MS=${BURST_MS:-3000}

if [ ! -f "$JAR" ]; then
  echo "Building Brace benchmark..."
  cd "$SCRIPT_DIR" && mvn package -q -DskipTests
fi

docker exec tfb-postgres psql -U benchmarkdbuser -d hello_world -tc \
  "SELECT 1 FROM pg_database WHERE datname = 'jobs_bench'" | grep -q 1 || \
  docker exec tfb-postgres psql -U benchmarkdbuser -d hello_world -c "CREATE DATABASE jobs_bench"

# Clear leftover SlowJob rows from previous runs so /jobs-done counts this run only.
docker exec tfb-postgres psql -U benchmarkdbuser -d jobs_bench -c \
  "DELETE FROM scheduled_jobs WHERE name = 'SlowJob'" > /dev/null 2>&1 || true

# Refuse to start while another JobsApp is alive: a previous run's poller polls the same
# scheduled_jobs table and would poach this run's burst, corrupting BOTH apps' numbers.
# (The first post-H4 run was contaminated exactly this way — see the findings doc.)
if pgrep -f benchmark.JobsApp > /dev/null; then
  echo "FATAL: a benchmark.JobsApp JVM is already running; kill it first" >&2
  exit 1
fi

echo "Starting JobsApp ($JAR)..."
java --enable-preview -cp "$JAR" benchmark.JobsApp > /dev/null 2>&1 &
APP_PID=$!
# On exit, wait until the app is REALLY dead (escalating to -9) — its poller must not
# survive into a subsequent run.
trap 'kill $APP_PID 2>/dev/null
      for i in $(seq 1 40); do kill -0 $APP_PID 2>/dev/null || break; sleep 0.5; done
      kill -9 $APP_PID 2>/dev/null; wait $APP_PID 2>/dev/null' EXIT

for i in $(seq 1 30); do
  if curl -sf http://localhost:$PORT/ping-db > /dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

LISTENER=$(lsof -t -iTCP:$PORT -sTCP:LISTEN | head -1)
if [ "$LISTENER" != "$APP_PID" ]; then
  echo "FATAL: port $PORT is served by PID ${LISTENER:-none}, not the benchmark app ($APP_PID)" >&2
  exit 1
fi

echo "--- Baseline: /ping-db, no jobs running ---"
wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WARMUP_DURATION "http://localhost:$PORT/ping-db" > /dev/null 2>&1
wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION --latency "http://localhost:$PORT/ping-db"
echo ""

echo "Enqueueing burst: ${BURST_N} jobs x ${BURST_MS}ms..."
curl -sf "http://localhost:$PORT/burst?n=$BURST_N&ms=$BURST_MS" > /dev/null

# Wait for the poller to claim the burst (idle poll interval is 10s).
for i in $(seq 1 30); do
  STARTED=$(curl -sf "http://localhost:$PORT/jobs-done" | sed -E 's/.*"started":([0-9]+).*/\1/')
  if [ "${STARTED:-0}" -gt 0 ]; then
    break
  fi
  sleep 1
done
if [ "${STARTED:-0}" -eq 0 ]; then
  echo "FATAL: burst never started executing" >&2
  exit 1
fi
echo "Burst executing (started=$STARTED). Running wrk under contention..."
echo ""

echo "--- Contended: /ping-db while slow jobs hold pool connections ---"
wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION --latency "http://localhost:$PORT/ping-db"
echo ""

curl -sf "http://localhost:$PORT/jobs-done" || true
echo ""
echo "Done. Stopping JobsApp (remaining burst jobs are abandoned with the app)."
