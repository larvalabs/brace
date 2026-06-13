# Brace Benchmarks

## Runtime Performance (TechEmpower Framework Benchmark suite)

Implements the standard TFB test suite to compare Brace against Spring Boot on the same hardware.

### Tests
1. **Plaintext** (`/plaintext`) — raw HTTP throughput
2. **JSON** (`/json`) — JSON serialization
3. **Single Query** (`/db`) — one DB lookup
4. **Multiple Queries** (`/queries?queries=20`) — 20 random DB lookups
5. **Fortunes** (`/fortunes`) — DB query + sort + template render + XSS escape
6. **Updates** (`/updates?queries=20`) — 20 read-modify-write cycles

### Setup

Requires PostgreSQL with TFB schema:

```bash
docker run -d --name tfb-postgres -p 5432:5432 \
  -e POSTGRES_USER=benchmarkdbuser \
  -e POSTGRES_PASSWORD=benchmarkdbpass \
  -e POSTGRES_DB=hello_world \
  postgres:16

# Seed the database
psql -h localhost -U benchmarkdbuser -d hello_world -f benchmark/sql/create.sql
```

### Running

```bash
# Build
cd benchmark
mvn package -DskipTests

# Run Brace benchmark app
java -jar target/brace-benchmark.jar

# In another terminal, run wrk
wrk -t8 -c256 -d15s http://localhost:8080/plaintext
wrk -t8 -c256 -d15s http://localhost:8080/json
wrk -t8 -c256 -d15s http://localhost:8080/db
wrk -t8 -c256 -d15s http://localhost:8080/queries?queries=20
wrk -t8 -c256 -d15s http://localhost:8080/fortunes
wrk -t8 -c256 -d15s http://localhost:8080/updates?queries=20
```

### Comparing with Spring Boot

Use the Spring Boot TFB implementation from the TechEmpower repo:
https://github.com/TechEmpower/FrameworkBenchmarks/tree/master/frameworks/Java/spring

## Allocation micro-benchmarks (JMH)

wrk measures throughput and latency but cannot see per-operation allocation. The JMH harness in
`src/main/java/benchmark/jmh/` fills that gap (the `gc.alloc.rate.norm` profiler reports bytes
allocated per op), for the allocation-sensitive units the runtime-performance review targets.

```bash
# From the repo root (the template path is repo-root-relative). Installs the framework, rebuilds
# the benchmark jar, and runs every benchmark.jmh.* benchmark with the GC profiler attached.
./benchmark/run-jmh.sh

# Or a subset, by include-regex:
./benchmark/run-jmh.sh RenderAllocBench
```

`RenderAllocBench` quantifies M6 (render straight to UTF-8 bytes vs the old String→getBytes path)
for both template and JSON rendering. Add new `@Benchmark` classes under `benchmark.jmh` and the
runner discovers them by package.
