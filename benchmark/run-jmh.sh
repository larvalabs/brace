#!/usr/bin/env bash
set -euo pipefail

# JMH micro-benchmarks (gap #3): allocation-sensitive units measured with gc.alloc.rate.norm,
# which wrk can't see. Unlike the wrk suites these are not throughput tests and don't need a
# quiet machine for the allocation numbers (gc.alloc.rate.norm is deterministic) — though the
# ns/op figures are still steadier on an idle box.
#
# Usage: ./run-jmh.sh [include-regex]    e.g. ./run-jmh.sh RenderAllocBench

# JDK 25+ recommended (JEP 491) — see AGENTS.md.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
JAR="$SCRIPT_DIR/target/brace-benchmark-0.2.0-SNAPSHOT.jar"
INCLUDE="${1:-benchmark\\.jmh\\.}"

# Install the framework so the benchmark resolves the current worktree's brace (M6 etc.), then
# rebuild the benchmark jar. MUST be `clean package`: without clean, shade silently reuses stale
# output (the embedded class checksum trap noted in the review's benchmark protocol).
echo "Installing framework (skip tests) and rebuilding benchmark jar..."
( cd "$REPO_ROOT" && mvn install -q -DskipTests )
( cd "$SCRIPT_DIR" && mvn clean package -q -DskipTests )

# Run from the repo root so the repo-root-relative template path resolves (matches App.java).
cd "$REPO_ROOT"
echo ""
java --enable-preview -cp "$JAR" benchmark.jmh.JmhRunner "$INCLUDE"
