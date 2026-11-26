# Brace Benchmarks

Two jobs, one harness:

- **Regression checks** for the runtime performance reviews: the same machine, before and after
  a change (`docs/reviews/`, raw output in `baselines/`).
- **Cross-framework comparison** against Spring Boot on Spring Data JPA (Hibernate), built from
  the official TechEmpower Spring implementation, run on the same machine with the same driver.

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
"Full" ORM. So the Spring Boot app it's compared with uses one too:

| Framework (`run-docker.sh` name) | Reads | Updates write | ORM class |
|---|---|---|---|
| `brace` | Hibernate `StatelessSession`, no transaction (`ReadDbHandler`) | one JDBC batch, in the request transaction | Full |
| `spring-jpa` | Spring Data JPA over Hibernate, each `findById` in Spring Data's default read-only transaction | one JDBC batch | Full |

`spring-jpa` is TechEmpower's Spring app plus a `jpa` profile from `docker/spring-jpa/`
(TechEmpower never shipped one). Both issue the same statements for Updates: N selects plus one
batched update. TechEmpower's own Spring profiles aren't used, because they're `JdbcTemplate`,
Spring Data JDBC and jOOQ. Hand-written SQL against Brace's Hibernate isn't a like-for-like
comparison.

### Database (for local runs)

`run-docker.sh` brings its own. For the local scripts, a dedicated Postgres on port 5434, so it
doesn't collide with a local Postgres on 5432:

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

There are two ways to run the suite:

- **Containers on a dedicated Linux box** (`run-docker.sh`): use this for numbers you publish.
- **Brace as a process on your machine** (`run-brace.sh`): use this for quick before/after
  checks while working. Spring comparisons always use `run-docker.sh`, which also runs fine
  on a laptop for a smoke test.

A laptop that's doing other things is the biggest source of error, so don't publish laptop
numbers.

### On a dedicated box: `run-docker.sh`

Everything runs in Docker (`docker/compose.yml`), each part pinned to its own CPUs so they don't
compete:

- **Postgres:** fresh and freshly seeded on every invocation.
- **The app:** Brace built from this checkout, or the TechEmpower Spring app (archived repo's
  final commit) with the Spring Data JPA profile, both on the same `eclipse-temurin:25-jdk`
  image.
  Brace's templates are precompiled in the image, as `brace compile` does for a deployed app.
  Without that, prod mode falls back to jte's on-demand engine. That engine takes a lock and
  reads template file timestamps on every render, which gave Fortunes a long latency tail
  (p90 33ms instead of 5ms on the render-only variant).
- **wrk:** the load generator, on the same Docker network.

By default the app gets half the CPUs and Postgres and wrk a quarter each. For `ROUNDS` rounds
it launches each framework in turn (a fresh JVM each time). Each launch checks responses, warms
up, measures, and stops. Alternating like this spreads any drift across both frameworks.

**The machine.** Use a cloud VM with *dedicated* vCPUs, 8 or more (16 gives an 8/4/4 split):

- Hetzner: the CCX (dedicated vCPU) line, not the shared CX/CPX ones.
- DigitalOcean: CPU-Optimized droplets, not Basic.

Shared-vCPU instances have noisy neighbours, which is the problem we're trying to escape. The
report prints the hypervisor's **CPU steal** for the run. Above about 1%, distrust the numbers.

**From your machine, on a throwaway DigitalOcean droplet** (`cloud/do-run.sh`). The script:

1. creates a CPU-Optimized droplet with a one-off SSH key;
2. copies this working tree over as-is, so uncommitted changes are measured too;
3. runs `run-docker.sh` and copies the results back into `benchmark/results/`;
4. destroys the droplet and the key.

Teardown runs on any exit: success, failure or Ctrl-C. Before creating anything, the script
prints the size, region and hourly price and asks for confirmation (`BENCH_YES=1` skips the
prompt). Afterwards it reports roughly what the droplet cost.

```bash
./benchmark/cloud/do-run.sh                       # brace and spring-jpa on a c-8 ($0.25/h at the time of writing)
DO_SIZE=c-16 ./benchmark/cloud/do-run.sh brace    # bigger box, Brace only
./benchmark/cloud/do-run.sh --list                # anything left behind?
./benchmark/cloud/do-run.sh --cleanup             # delete leftover brace-bench droplets and keys
```

Setup, once: create a **custom-scoped** API token in the DigitalOcean control panel (API →
Generate New Token → Custom Scopes) with only these scopes:

- droplet: create, read, delete
- ssh_key: create, read, delete
- sizes: read
- regions: read
- tag: create, read

Give it an expiry. Then store it where the script looks, without it passing through your shell
history:

```bash
mkdir -p ~/.config/brace-bench && chmod 700 ~/.config/brace-bench
pbpaste > ~/.config/brace-bench/digitalocean-token && chmod 600 ~/.config/brace-bench/digitalocean-token
```

`DIGITALOCEAN_TOKEN` in the environment works too. The script keeps the token off command lines
and out of its output. A droplet only survives the script if the script itself is killed
outright (e.g. `kill -9`). `--list` shows such leftovers, and they carry the `brace-bench` tag.

**By hand, on any Linux box:**

```bash
# Ubuntu 24.04, as root (or with sudo). docker-buildx: the Dockerfiles use BuildKit cache mounts.
apt-get update && apt-get install -y docker.io docker-compose-v2 docker-buildx git curl
git clone https://github.com/larvalabs/brace.git && cd brace

BENCH_HOST_LABEL="hetzner ccx33 fsn1" ./benchmark/run-docker.sh            # brace and spring-jpa
BENCH_HOST_LABEL="hetzner ccx33 fsn1" ./benchmark/run-docker.sh brace      # Brace only

# Small-box variant: cap the app at 512 MB, Brace-sized pool
APP_MEM=512m DB_POOL=10 JVM_OPTS="-XX:MaxRAMPercentage=60" ./benchmark/run-docker.sh
```

The first run builds the images (a few minutes, mostly Maven downloads). After that,
`SKIP_BUILD=1` reuses them. Results land in `benchmark/results/`: the report (`.txt`), one row
per measurement (`.tsv`) and each app's log. `do-run.sh` copies them back for you. By hand,
copy them off the box before deleting it, for example with
`scp -r root@<ip>:brace/benchmark/results .`.

The report header records everything needed to reproduce or judge the run:

- the host label, CPU model and kernel;
- the Docker version and cpusets;
- the Postgres image digest;
- each app image's ID, Brace version and commit, the TechEmpower commit and `java -version`;
- the wrk settings and the load average.

The summary gives, per framework:

- median startup time (container start to first 200);
- RSS ready and after warmup;
- per test: median req/s, median p99, errors and peak RSS (reset before each measurement);
- Brace against each other framework: req/s (above 1x: Brace is faster), p99 (below 1x: Brace's
  tail is shorter), startup time and memory.

`run-docker.sh` settings, beyond the shared ones below:

| Variable | Default | |
|---|---|---|
| `ROUNDS` / `RUNS` | 3 / 1 | launches per framework / measured passes per launch |
| `CPUS_APP` / `CPUS_DB` / `CPUS_LOAD` | half / quarter / quarter of the Docker host's CPUs | set all three or none |
| `WRK_THREADS` | one per load CPU | |
| `APP_MEM` / `DB_MEM` | none | container memory caps, e.g. `512m`. An OOM kill is reported |
| `PG_MAX_CONNECTIONS` / `PG_SHARED_BUFFERS` | 600 / 256MB | |
| `BENCH_HOST_LABEL` | | free text for the report |
| `KEEP` | 0 | `1` leaves Postgres running afterwards |

### On your machine: `run-brace.sh`

```bash
./benchmark/run-brace.sh     # installs this checkout's framework, rebuilds the app, runs the suite
```

This uses the `tfb-postgres` container from the Database section above. Like `run-docker.sh`,
it uses `lib.sh`, so it:

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
| `DB_POOL` | 256 | the TechEmpower Spring config's size; `run-docker.sh` applies it to both apps (Brace's default is 10) |
| `PORT` | 8080 | |
| `BENCH_JAR` | | run this jar as-is (e.g. one built from another framework commit) |
| `SKIP_BUILD` | 0 | `1` reuses `target/brace-benchmark.jar` |
| `BRACE_LOG_LEVEL` | WARN | per-request logging off, matching Spring's `logging.level.root=OFF` |

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
