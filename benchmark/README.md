# Brace Benchmarks

Two jobs, one harness:

- **Regression checks** for the runtime performance reviews: the same machine, before and after
  a change (`docs/reviews/`, raw output in `baselines/`).
- **Cross-framework comparison** against the official TechEmpower Spring Boot implementation,
  run on the same machine with the same driver.

`RESULTS.md` is an old, invalid run (Java 23, virtual threads accidentally off). Don't cite it.
See `TODO.md` ("Benchmarks") and `docs/2026-11-25-benchmark-review.md`.

## TechEmpower-style suite

`src/main/java/benchmark/App.java` implements the six TechEmpower (TFB) tests:

1. **Plaintext** (`/plaintext`): raw HTTP throughput
2. **JSON** (`/json`): JSON serialization
3. **Single Query** (`/db`): one row by id, read-only handler (no transaction)
4. **Multiple Queries** (`/queries?queries=20`): 20 rows, one query each
5. **Fortunes** (`/fortunes`): query, add a row, sort, render a JTE template with HTML escaping
6. **Updates** (`/updates?queries=20`): read 20 rows, then write them back in one JDBC batch

Reads go through Hibernate (`StatelessSession.find`), which TechEmpower would class as a
"Full" ORM. The TechEmpower Spring entry's default profile (`jdbc`, `JdbcTemplate`) is classed
"Micro".

### Database

A dedicated Postgres on port 5434, so it doesn't collide with a local Postgres on 5432:

```bash
docker run -d --name tfb-postgres -p 5434:5432 \
  -e POSTGRES_USER=benchmarkdbuser \
  -e POSTGRES_PASSWORD=benchmarkdbpass \
  -e POSTGRES_DB=hello_world \
  postgres:16

# Seed the TFB schema (world: 10,000 rows, fortune: 12 rows)
docker exec -i tfb-postgres psql -U benchmarkdbuser -d hello_world < benchmark/sql/create.sql
```

After that, `docker start tfb-postgres` is all a later run needs.

### Running

```bash
./benchmark/run-brace.sh     # installs this checkout's framework, rebuilds the app, runs the suite
./benchmark/run-spring.sh    # same suite against the TechEmpower Spring app (build steps in the script)
```

Both scripts use `lib.sh`, so they:

1. refuse to start if Postgres is unreachable or the port is already taken;
2. launch the app and record the **time to first 200** and RSS;
3. check that every endpoint returns a TFB-shaped response (row counts, the added fortune,
   HTML escaping) before any load;
4. warm up every test, then run `RUNS` interleaved passes of
   `wrk -t8 -c256 -d15s --latency` per test, sampling peak RSS during each;
5. print a summary of the median req/s and p99, the error count (socket errors and
   non-2xx responses) and peak RSS;
6. save the raw output and per-run TSV under `benchmark/results/`. Copy a run into
   `baselines/` when it's worth keeping.

Settings, all from the environment:

| Variable | Default | |
|---|---|---|
| `BENCH_JAVA_HOME` | Homebrew `openjdk@25` | JDK 25 LTS is the target. An ambient `JAVA_HOME` is ignored on purpose |
| `JVM_OPTS` | (none) | e.g. `-Xmx80m -XX:+UseSerialGC` for small-box runs |
| `RUNS` | 3 | passes over the whole suite; the summary reports medians |
| `WRK_THREADS` / `WRK_CONNECTIONS` / `WRK_DURATION` / `WARMUP_DURATION` | 8 / 256 / 15s / 5s | |
| `DB_HOST` / `DB_PORT` | localhost / 5434 | |
| `DB_POOL` | 256 | applied to both apps (the TFB Spring config uses 256; Brace's default is 10) |
| `PORT` | 8080 | |
| `BENCH_JAR` | | run this jar as-is (e.g. one built from another framework commit) |
| `SKIP_BUILD` | 0 | `1` reuses `target/brace-benchmark.jar` |
| `BRACE_LOG_LEVEL` | WARN | per-request logging off, matching Spring's `logging.level.root=OFF` |
| `SPRING_DIR` / `SPRING_PROFILE` | `/tmp/tfb-spring/...` / `jdbc` | `run-spring.sh` only |

The Brace app runs with `-Dbrace.mode=prod`, as a deployed app does.

For a number you intend to publish: close other apps, check the load average the script
prints, run both frameworks back to back, and keep both raw files.

## Other scenarios

| Script | What it measures |
|---|---|
| `run-session.sh` | Session-cookie GET, CSRF-checked form POST, `csrf(false)` JSON POST (no DB) |
| `run-jobs-claim.sh` | Durable-job claim latency over about 1M dead rows (`JobsBench`) |
| `run-jobs-mixed.sh` | `/ping-db` latency while a burst of slow durable jobs churns (`JobsApp`) |
| `src/main/java/benchmark/Profile.java` | Per-phase timing of a single-query request (open, begin, find, commit, close) |

They share `lib.sh`'s JDK selection and build (`BENCH_JAR`, `SKIP_BUILD`).

## Allocation micro-benchmarks (JMH)

wrk measures throughput and latency but can't see per-operation allocation. The JMH harness in
`src/main/java/benchmark/jmh/` fills that gap (the `gc.alloc.rate.norm` profiler reports bytes
allocated per op), for the allocation-sensitive units the runtime-performance review targets.

```bash
# Installs the framework, rebuilds the benchmark jar, and runs every benchmark.jmh.* benchmark
# with the GC profiler attached.
./benchmark/run-jmh.sh

# Or a subset, by include-regex:
./benchmark/run-jmh.sh RenderAllocBench
```

`RenderAllocBench` quantifies M6 (render straight to UTF-8 bytes vs the old String→getBytes path)
for both template and JSON rendering. Add new `@Benchmark` classes under `benchmark.jmh` and the
runner discovers them by package.
