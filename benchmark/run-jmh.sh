#!/usr/bin/env bash
set -euo pipefail

# JMH micro-benchmarks (gap #3): allocation-sensitive units measured with gc.alloc.rate.norm,
# which wrk can't see. Unlike the wrk suites these are not throughput tests and don't need a
# quiet machine for the allocation numbers (gc.alloc.rate.norm is deterministic) — though the
# ns/op figures are still steadier on an idle box.
#
# Usage: ./run-jmh.sh [include-regex]    e.g. ./run-jmh.sh RenderAllocBench

# JDK 25+ recommended (JEP 491) — see AGENTS.md.
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"   # JDK selection + ensure_jar
use_jdk

INCLUDE="${1:-benchmark\\.jmh\\.}"

# Install this checkout's framework and `clean package` the benchmark against that exact
# version (lib.sh ensure_jar; without clean, shade silently reuses stale output, the embedded
# class checksum trap noted in the review's benchmark protocol). BENCH_JAR or SKIP_BUILD=1 skip it.
ensure_jar

# Run from the repo root so the repo-root-relative template path resolves (matches App.java).
cd "$REPO_ROOT"
echo ""
java --enable-preview -cp "$JAR" benchmark.jmh.JmhRunner "$INCLUDE"
