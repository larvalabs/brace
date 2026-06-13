#!/usr/bin/env bash
set -euo pipefail

# Session/CSRF benchmark scenario (H5/M2/M3 — see
# docs/2026-06-11-runtime-performance-review-todos.md). Runs benchmark.SessionApp
# (no database needed), primes a session cookie + CSRF token, then drives wrk:
#   1. Session Read   — GET with session cookie, read-only handler
#   2. CSRF Form POST — form POST validated against the session CSRF token
#   3. API POST       — csrf(false) JSON POST, session cookie still attached
#
# Override BENCH_JAR to benchmark a jar built against a different framework
# version (before/after comparisons).

# JDK 25+ recommended (JEP 491: no virtual-thread pinning on synchronized) — see AGENTS.md.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${BENCH_JAR:-$SCRIPT_DIR/target/brace-benchmark-0.2.0-SNAPSHOT.jar}"
export PORT=${PORT:-8081}
WRK_THREADS=${WRK_THREADS:-8}
WRK_CONNECTIONS=${WRK_CONNECTIONS:-256}
WRK_DURATION=${WRK_DURATION:-15s}
WARMUP_DURATION=${WARMUP_DURATION:-5s}
LUA="$SCRIPT_DIR/session-post.lua"

if [ ! -f "$JAR" ]; then
  echo "Building Brace benchmark..."
  cd "$SCRIPT_DIR" && mvn package -q -DskipTests
fi

echo "Starting Brace session benchmark app ($JAR)..."
java --enable-preview -cp "$JAR" benchmark.SessionApp > /dev/null 2>&1 &
APP_PID=$!
trap "kill $APP_PID 2>/dev/null; wait $APP_PID 2>/dev/null" EXIT

# Wait for app to start (prime endpoint also serves as readiness probe)
for i in $(seq 1 30); do
  if curl -sf http://localhost:$PORT/sess/prime > /dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

# Guard against a stale/foreign process answering on the port (a failed bind
# would otherwise let wrk measure the wrong server)
LISTENER=$(lsof -t -iTCP:$PORT -sTCP:LISTEN | head -1)
if [ "$LISTENER" != "$APP_PID" ]; then
  echo "FATAL: port $PORT is served by PID ${LISTENER:-none}, not the benchmark app ($APP_PID)" >&2
  exit 1
fi

# Prime a session: capture the brace_session cookie and the CSRF token (body)
HDRS=$(mktemp)
TOKEN=$(curl -sf -D "$HDRS" http://localhost:$PORT/sess/prime)
COOKIE=$(grep -i '^set-cookie:' "$HDRS" | sed -E 's/^[Ss]et-[Cc]ookie: *//' | cut -d';' -f1 | tr -d '\r')
rm -f "$HDRS"
if [ -z "$TOKEN" ] || [ -z "$COOKIE" ]; then
  echo "FATAL: failed to prime session (token='$TOKEN' cookie='$COOKIE')" >&2
  exit 1
fi

# Sanity-check the scenario before burning wrk time on it
check() {
  local label=$1 expected=$2 actual=$3
  if [ "$actual" != "$expected" ]; then
    echo "FATAL: $label: expected $expected, got $actual" >&2
    exit 1
  fi
}
check "session read" "benchmark-user" \
  "$(curl -sf -H "Cookie: $COOKIE" http://localhost:$PORT/sess/read)"
check "csrf form post" "ok:hello" \
  "$(curl -sf -X POST -H "Cookie: $COOKIE" -H 'Content-Type: application/x-www-form-urlencoded' \
      --data "_csrf=$TOKEN&data=hello" http://localhost:$PORT/sess/form)"
check "csrf form post without token (403)" "403" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Cookie: $COOKIE" \
      -H 'Content-Type: application/x-www-form-urlencoded' --data "data=hello" \
      http://localhost:$PORT/sess/form)"
check "api post" '{"ok":true}' \
  "$(curl -sf -X POST -H "Cookie: $COOKIE" -H 'Content-Type: application/json' \
      --data '{}' http://localhost:$PORT/sess/api)"

export WRK_COOKIE="$COOKIE"

run_get() { # label url
  echo "--- $1 ---"
  wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WARMUP_DURATION -H "Cookie: $COOKIE" "$2" > /dev/null 2>&1
  wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION --latency -H "Cookie: $COOKIE" "$2"
  echo ""
}

run_post() { # label url body content-type
  echo "--- $1 ---"
  WRK_BODY="$3" WRK_CONTENT_TYPE="$4" \
    wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WARMUP_DURATION -s "$LUA" "$2" > /dev/null 2>&1
  WRK_BODY="$3" WRK_CONTENT_TYPE="$4" \
    wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION --latency -s "$LUA" "$2"
  echo ""
}

echo ""
echo "=== Brace Session/CSRF Benchmark (wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION) ==="
echo ""

run_get  "Session Read"   "http://localhost:$PORT/sess/read"
run_post "CSRF Form POST" "http://localhost:$PORT/sess/form" "_csrf=$TOKEN&data=hello" "application/x-www-form-urlencoded"
run_post "API POST csrf(false)" "http://localhost:$PORT/sess/api" '{}' "application/json"

echo "Done. Stopping Brace."
