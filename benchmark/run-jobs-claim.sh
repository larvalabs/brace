#!/usr/bin/env bash
set -euo pipefail

# Job-queue claim-latency benchmark, half 1 of gap #4 (H3 — see
# docs/2026-06-11-runtime-performance-review-todos.md). Runs benchmark.JobsBench
# against the tfb-postgres container: seeds ~1M dead scheduled_jobs rows, then
# measures empty-poll latency, getDurableJobStats latency, and a no-op drain.
#
# No wrk, no quiet-window requirement — single-query latency, not throughput.
#
# Compare framework versions by building two shaded jars (same procedure as
# run-session.sh: `mvn clean install -DskipTests` at the repo root per framework
# commit, then `mvn clean package -DskipTests` here; verify JobPoller.class
# differs) and pointing BENCH_JAR at each. Run the PRE jar first on a fresh
# jobs_bench database — the POST jar's startup migration then builds the V15
# partial index over the already-seeded rows.

# JDK 25+ recommended (JEP 491: no virtual-thread pinning on synchronized) — see AGENTS.md.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${BENCH_JAR:-$SCRIPT_DIR/target/brace-benchmark-0.2.0-SNAPSHOT.jar}"

if [ ! -f "$JAR" ]; then
  echo "Building Brace benchmark..."
  cd "$SCRIPT_DIR" && mvn package -q -DskipTests
fi

# Ensure the jobs_bench database exists in the tfb-postgres container.
docker exec tfb-postgres psql -U benchmarkdbuser -d hello_world -tc \
  "SELECT 1 FROM pg_database WHERE datname = 'jobs_bench'" | grep -q 1 || \
  docker exec tfb-postgres psql -U benchmarkdbuser -d hello_world -c "CREATE DATABASE jobs_bench"

echo "JobsBench jar: $JAR"
java --enable-preview -cp "$JAR" benchmark.JobsBench
