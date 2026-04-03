#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/Users/matt/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SPRING_DIR="/tmp/tfb-spring/frameworks/Java/spring"
JAR="$SPRING_DIR/target/hello-spring-1.0-SNAPSHOT.jar"
PORT=8080
WRK_THREADS=8
WRK_CONNECTIONS=256
WRK_DURATION=15s
WARMUP_DURATION=5s
DB_HOST=localhost
DB_PORT=5433

if [ ! -f "$JAR" ]; then
  echo "Spring TFB jar not found at $JAR"
  echo "Clone and build first:"
  echo "  cd /tmp && git clone --depth 1 --filter=blob:none --sparse https://github.com/TechEmpower/FrameworkBenchmarks.git tfb-spring"
  echo "  cd tfb-spring && git sparse-checkout set frameworks/Java/spring"
  echo "  cd frameworks/Java/spring && mvn package -q -DskipTests"
  exit 1
fi

echo "Starting Spring Boot benchmark app..."
java -XX:+DisableExplicitGC -XX:+UseStringDeduplication \
  -Dlogging.level.root=OFF \
  -Ddatabase.host=$DB_HOST -Ddatabase.port=$DB_PORT \
  -jar "$JAR" --spring.profiles.active=jdbc &
APP_PID=$!
trap "kill $APP_PID 2>/dev/null; wait $APP_PID 2>/dev/null" EXIT

# Wait for app to start
for i in $(seq 1 30); do
  if curl -sf http://localhost:$PORT/plaintext > /dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

echo "Warming up..."
wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WARMUP_DURATION http://localhost:$PORT/plaintext > /dev/null 2>&1
wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WARMUP_DURATION http://localhost:$PORT/json > /dev/null 2>&1
wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WARMUP_DURATION http://localhost:$PORT/db > /dev/null 2>&1
wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WARMUP_DURATION http://localhost:$PORT/fortunes > /dev/null 2>&1

echo ""
echo "=== Spring Boot TFB Benchmark (wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION) ==="
echo ""

TESTS=("plaintext" "json" "db" "queries?queries=20" "fortunes" "updates?queries=20")
LABELS=("Plaintext" "JSON" "Single Query" "Multiple Queries (20)" "Fortunes" "Updates (20)")

for i in "${!TESTS[@]}"; do
  echo "--- ${LABELS[$i]} ---"
  wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION "http://localhost:$PORT/${TESTS[$i]}"
  echo ""
done

echo "Done. Stopping Spring Boot."
