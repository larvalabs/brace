# Benchmark review (2026-11-25)

Framework at `0.1.12-SNAPSHOT` (just after the 0.1.11 release). Scope: what Brace uses as a
benchmark today, how that compares with public multi-framework benchmarks (the PVRLabs 512MB
suite from Reddit, TechEmpower, the-benchmarker), and what to do next so the README can make a
concrete, defensible performance claim. No benchmarks were run and no code changed for this
review.

## Summary

- **Our benchmark today is a homemade TechEmpower-style (TFB) suite**, in `benchmark/`. It runs
  six TFB tests on a MacBook with `wrk -t8 -c256 -d15s`, against a Postgres in Docker. We use it
  as a **regression harness** for the runtime performance reviews (before/after on the same
  machine), not as a public comparison. The one cross-framework result, `RESULTS.md`
  (Brace vs Spring Boot), is invalid, as `TODO.md` says.
- **The better numbers already exist but are Brace-only.** The June 2026 perf review's final
  checkpoint (`benchmark/baselines/2026-06-13-wrk-final-d89cf90.txt`, JDK 25.0.2, virtual
  threads on) has Brace at about 76k req/s plaintext/JSON, **31k single query, 30k fortunes**,
  1.9k queries(20) and 1.5k updates(20). The DB rows are **2x the `RESULTS.md` figures** and at
  or above the Spring numbers in that old table. But Spring was never re-run on the same box
  and day, so we can't claim a ratio yet.
- **TechEmpower, the widely cited benchmark, is gone as a live venue.** The GitHub repo was
  archived on 2026-03-24, and Round 23 (early 2025) was the last round. Submitting Brace there is
  no longer possible. Its *test definitions* are still the lingua franca, though, and we already
  implement them.
- **PVRLabs/framework-benchmarks is not worth targeting as a venue.** It was created
  2026-11-18, has 1 star and a single author, and its "512MB VPS" is a 1-vCPU QEMU guest on a
  laptop. Only four JVM stacks run in it, with Postgres in the same 512 MiB, at a fixed
  40 RPS. In practice it's a **startup and memory** benchmark, and a reasonable one. That
  idea is worth borrowing, because it measures what Brace users actually deploy on (small
  VPS/PaaS boxes).
- **the-benchmarker/web-frameworks** (about 7k stars, active, ~440 implementations, 25 Java
  ones) is the most-watched *live* multi-framework comparison. It tests routing, JSON and
  multipart only. There is no database, which is where Brace's design choices actually matter.
  A cheap submission for visibility, not a source of a meaningful claim.
- **Recommendation:** (1) fix the harness and re-run Brace vs Spring Boot TFB on JDK 25 on one
  machine, in containers. That gives the README claim. (2) Add a 512MB-capped profile of the same
  suite (Brace vs Spring Boot, optionally Javalin/Quarkus), reporting RSS and startup alongside
  throughput. This fits Brace's pitch best. (3) Optionally submit a v2 implementation to
  the-benchmarker. Skip PVRLabs unless it gains traction. Effort and order are at the end.

## Part 1: what our benchmark is today

### `benchmark/` inventory

| Asset | What it is | State |
|---|---|---|
| `src/main/java/benchmark/App.java`, `model/World.java`, `model/Fortune.java`, `views/fortunes.jte` | TFB app with six tests: `/plaintext`, `/json`, `/db`, `/queries?queries=N`, `/fortunes`, `/updates?queries=N`. Reads use `ReadDbHandler` (no transaction), `/updates` uses a `DbHandler` plus a raw-JDBC `executeBatch` for the writes. Pool 256. | Works, but see the harness problems below |
| `run-brace.sh` | Builds the jar if missing, starts `App`, checks the port's listener PID is ours, 5s warmups, then `wrk -t8 -c256 -d15s --latency` per test | JDK 25 default |
| `run-spring.sh` | Same driver against the official TechEmpower `frameworks/Java/spring` app (Spring Boot 3.3.5, Undertow, `JdbcTemplate`, `jdbc` profile), cloned into `/tmp/tfb-spring` | **Still defaults to JDK 23**, no `--latency`, no PID guard |
| `RESULTS.md` | The only Brace vs Spring table: Brace 1.1–1.2x on plaintext/JSON, 0.2–0.6x on the DB tests | **Invalid** (see below) |
| `run-jmh.sh`, `jmh/RenderAllocBench`, `jmh/FormBindBench` | JMH micro-benchmarks with the GC profiler. `gc.alloc.rate.norm` (bytes/op) is deterministic, so allocation claims don't depend on a quiet machine | Used to verify the June review's M4/M6 allocation fixes |
| `run-session.sh`, `SessionApp`, `session-post.lua` | Session-read GET, CSRF form POST, `csrf(false)` JSON POST under wrk. No DB | Regression harness for H5/M2/M3 |
| `run-jobs-claim.sh`, `JobsBench` | Seeds about 1M dead `scheduled_jobs` rows, times empty-poll, `getDurableJobStats` and a no-op drain | Single-query latencies, not throughput |
| `run-jobs-mixed.sh`, `JobsApp` | wrk on `/ping-db` while a burst of 300 slow durable jobs churns, comparing p99 and errors | Regression harness for H4 (jobs starving the pool) |
| `Profile.java` | Splits a single-query request into openSession/begin/find/commit/close timings over 10k iterations | Meant for the deferred M11/M21 decisions |
| `baselines/` | Raw wrk/JobsBench output, one file per checkpoint of the June review (`ea76ffa` baseline through `d89cf90` final) | Brace-only, JDK 25.0.2, same MacBook |
| `sql/create.sql` | TFB schema plus seed (`world` 10k rows, `fortune` 12 rows) | |

### How the runtime performance reviews measure

The protocol is in `docs/2026-06-11-runtime-performance-review-todos.md` (Benchmarks section) and
summarized in `docs/reviews/2026-06-runtime-performance-fable-5.md`:

- Throughput/latency: `run-brace.sh` (and the session/jobs scripts) on a "quiet window"
  (1-min load average checked before firing), baseline at the review's start commit, then a
  checkpoint after each batch of fixes, all on the same laptop. Raw output goes into
  `benchmark/baselines/`.
- The review states the variance plainly: single runs, about ±10% on a developer laptop, so
  macro numbers are **checkpoints, not gates**. Allocation claims are proven with JMH
  `gc.alloc.rate.norm` instead.
- Result of the June review, baseline to final: req/s +11% to +53% across the six tests, p99
  down 54% to 97% (Fortunes p99 1.23s to 33ms, from the async log writer, H1).
- There is no perf gate in CI. `mvn verify` is correctness only.

So the honest answer to "what is our benchmark" is: **a same-machine regression harness for
Brace against itself, borrowed from TFB's test definitions.** It is good at that job. It has
never produced a credible cross-framework number.

### Why `RESULTS.md` is invalid, and what's newer

`TODO.md` ("Benchmarks (Lower Priority)") lists the reasons. It was measured on Java 23.0.2,
and, more importantly, `Brace.start()` passed `Runnable::run` as the virtual-thread executor
(fixed in 0.1.5), so handlers ran inline on platform threads, capped at about 200 in flight.
It also predates:

- the June perf review (async logging, render-after-commit, allocation fixes);
- the `/updates` change to one JDBC batch (`RESULTS.md` still explains the 5x Updates gap as
  "Brace issues them sequentially").

The June final checkpoint is the best current data. It ran on JDK 25.0.2, with virtual threads,
on the same kind of machine:

| Test | `RESULTS.md` Brace (JDK 23, vthreads off) | June final `d89cf90` (JDK 25) | `RESULTS.md` Spring (JDK 23) |
|---|---|---|---|
| Plaintext | 81,533 | 76,417 | 67,640 |
| JSON | 81,952 | 76,630 | 74,769 |
| Single Query | 15,752 | **31,356** | 26,278 |
| Multiple Queries (20) | 890 | **1,867** | 1,747 |
| Fortunes | 14,840 | **30,438** | 29,972 |
| Updates (20) | 351 | **1,515** | 1,695 |

This matches the `TODO.md` prediction. Plaintext/JSON slipped a little once real virtual threads
were on (there's per-request mount/allocation overhead for handlers that never block). The DB
rows roughly doubled. **Do not put the right-hand column next to the middle one in public.**
They are different days, JDKs and machine states. They are a strong hint that a same-day re-run
will show Brace near parity with Spring's `JdbcTemplate` implementation on DB reads, not at
0.5x.

### Harness problems to fix before any re-run

1. **`benchmark/pom.xml` pins `brace` `0.1.7-SNAPSHOT`.** The framework is at
   `0.1.12-SNAPSHOT`, so `mvn install` at the root plus a benchmark build either fails or
   silently links an old snapshot from `~/.m2`. `run-jmh.sh` has the same trap. Use a
   property kept in sync with the root pom, or `${project.parent.version}`-style wiring.
2. **Request logging is on in Brace and off in Spring.** `App` doesn't set a log level, and
   `Log` defaults to `DEBUG`, so every request enqueues a JSON log line. `run-spring.sh`
   passes `-Dlogging.level.root=OFF`. Run Brace with `BRACE_LOG_LEVEL=WARN` (TFB and
   the-benchmarker both require logging off).
3. **`run-spring.sh` defaults to JDK 23** and lacks `--latency` and the listener-PID guard
   that `run-brace.sh` has.
4. **The README in `benchmark/` is stale**: it says Postgres on 5432 and
   `target/brace-benchmark.jar`, but `App.java` hardcodes `localhost:5433` and the jar is
   `brace-benchmark-0.2.0-SNAPSHOT.jar`. The DB URL should come from an env var, so the same
   app works in a container.
5. **wrk, the app and Postgres share one laptop.** That's fine for regression checkpoints,
   but for a published number the load generator should be pinned to separate cores (or a
   separate box), and each test should be run 3+ times with the median reported.
6. **Pool 256 is a TFB-tuning number, not a Brace default.** The default is 10. For a
   realistic comparison, report both, or pick one size per box for all frameworks.
7. **No JIT/startup or memory capture.** Neither script records RSS, heap, startup time or
   image size. Those are cheap to add (`docker stats`, `/ops/status` heap, time-to-first-200)
   and they matter for the 512MB story.

**Status (2026-11-25, step 1 of the plan): fixed in the working tree, not yet committed.**
`run-brace.sh` and `run-spring.sh` now share one driver, `benchmark/lib.sh`. It:

- uses JDK 25, ignoring an ambient `JAVA_HOME`;
- installs this checkout's framework and builds against that exact version;
- refuses an unreachable DB or a busy port;
- checks every endpoint's response before load;
- runs interleaved warmup plus `RUNS` (default 3) passes and reports median req/s and p99,
  errors, peak RSS and time to first 200;
- saves raw output and a TSV under `benchmark/results/` (git-ignored).

Problems 1–4 and 6–7 are fixed (the pool size is now `DB_POOL`, applied to both apps). Item 5
remains a run-time discipline, now documented in `benchmark/README.md`.

The smoke test (2s runs) found two more things:

- jenv's global `JAVA_HOME` had silently put *both* apps on JDK 21. This is the same class
  of mistake that invalidated `RESULTS.md`.
- The TechEmpower Spring app returns `Hello, world!` (lowercase w), so the JSON check is
  case-insensitive, as TFB's validator is.

The short Brace runs also showed a few dozen wrk *read* socket errors on JSON and Fortunes
(none on Spring). Check whether that persists in full-length runs before publishing anything.

## Part 2: public benchmarks

### PVRLabs/framework-benchmarks (the Reddit 512MB one)

Read from the repo with `gh api` on 2026-11-25. It's quite different from what the Reddit
framing suggests.

- **Status.** Created 2026-11-18, 17 commits from one author, 1 star, 0 issues/PRs, no
  LICENSE or CONTRIBUTING file. The README discloses that the maintainer also wrote CoilBase,
  the data layer two of the four stacks use, and a CoilBase stack wins.
- **The "512MB VPS" is a Lima QEMU/KVM guest on a laptop** (Ryzen 7 4700U host): 1 vCPU,
  512 MiB, swap off. **PostgreSQL 18 runs inside the same guest**, tuned down hard
  (`max_connections=10`, `shared_buffers=16MB`). There are no containers and no cgroup
  limits. The apps run as plain `java -jar` on the guest.
- **One realistic workload, not TFB tests.** "Market Replay Portfolio" has 1,000 seeded users
  and a background job that polls a quote CSV every 60s into Postgres. Request mix: 40% `GET
  portfolio`, 25% `PUT holding` (204), 25% `GET valuation` (one SQL snapshot), 8% latest
  quotes, 2% quote history, plus a DB-backed `/readyz`. Each app must pass a correctness
  checker (`verify-postgres.py`, HTTP plus SQL assertions) before it's measured. That
  contract is a good idea.
- **Load: a custom Python `urllib` driver on the host**, through SSH port forwarding.
  Open-loop **fixed 40 RPS** (5 RPS light, 125s warm-up, 185s measured), a new TCP
  connection per request, two rounds in a fixed order.
- **Same JVM settings for every stack**: HotSpot JDK 27 build, `-Xms16m -Xmx80m
  -XX:+UseG1GC`, no virtual threads, no AOT/CDS/native. **DB pool capped at 4** everywhere
  (Spring Tomcat threads 16).
- **Reports**: readiness time, idle and peak app RSS/PSS, Postgres PSS, min guest
  MemAvailable, achieved RPS, p95/p99 with queue/dispatch breakdown, errors/timeouts, OOM
  counters, GC logs. Evidence is retained in full and pinned by hash. A regenerated report must
  match the published HTML byte for byte.
- **Stacks**: Spring Boot 4.1 (Tomcat, Spring JDBC), Quarkus REST plus Jdbi, Avaje Jex plus
  Jdbi, Micronaut Data JDBC. All JVM; no Hibernate (the README puts its cost at about 55 MiB
  RSS). Posted results: ready in 4.6s (Jex) to 10.6s (Spring), sustained peak RSS 148 MiB
  (Jex) to 233 MiB (Spring). Every stack held 40 RPS with zero errors and p99 within about
  0.5ms of each other.

**Assessment.** At 40 RPS, throughput and latency don't separate anything, so this is really
a **startup-time and memory-footprint benchmark** for JVM stacks on a tiny box. That's
legitimate and relevant to Brace's users. But the venue is a one-week-old, one-person
project with a disclosed conflict of interest and no community yet. Some comparisons are
uneven (the Spring reference ships Actuator plus Prometheus and extra JDBC drivers; Jex runs
no monitoring). It isn't something to target for a README claim.

**Adding Brace there** would mean a `framework-benchmarks/brace/` app implementing the five
endpoints plus `/readyz` against their schema and fixture, passing `verify-postgres.py`, and
wiring the jar and launch line into `colocated-suite.py`/`colocated-guest.py`. The maintainer's
`branch-strategy.md` puts new frameworks on `experiment/*` branches, not `main`. The quote
poller maps naturally onto `app.every(...)`, the `PUT` needs `.csrf(false)`, and the app
should run with `BRACE_LOG_LEVEL=WARN` and ops/JFR off to match the others. About 1–1.5 days
including their runner. Their GitHub Actions workflow can run a fork's config in about 49
minutes, so we could get numbers **without** submitting anything.

**How Brace would likely fare** under their exact settings (`-Xmx80m`, pool 4, Postgres in
the same 512 MiB):

- *Heap*: Hibernate's `SessionFactory`/metamodel plus Flyway plus Jackson plus JTE in an
  80 MB heap is tight but plausible for a 3-entity app. It needs a check with GC logging
  before anything else.
- *RSS*: likely between Micronaut and Spring (about 190–230 MiB), because Hibernate is the
  one heavy dependency the leaner stacks avoid. Running ops would add JFR buffers.
- *Readiness*: Brace runs two Flyway passes (framework, then app) then builds the Hibernate
  `SessionFactory` serially (M21). On 1 vCPU, expect something like 6–9s: better than Spring's
  10.6s, worse than Jex/Quarkus. These are estimates, not measurements.
- *Latency/throughput*: a wash, like everyone else at 40 RPS.

This is the scenario where M21 (parallel/lazier boot) and a "no ops, no JFR" posture would
show up. It's a reason to run the methodology ourselves, not to join their leaderboard.

### TechEmpower Framework Benchmarks

- The de facto standard for a decade: about 330 implementations in Round 23, dedicated
  hardware (Intel Xeon Gold 6330, 40Gbps), and the six tests our `App.java` already
  implements, with strict rules (e.g. no response caching, real per-request queries).
- **Archived.** The GitHub repo is read-only since 2026-03-24, and Round 23 (published early
  2025) is the last round. There is no way to submit Brace. Some people still run the
  toolset themselves; the published results are frozen.
- What's still valuable: the test definitions and requirements (everyone recognizes
  "fortunes" and "20 queries"), and the existing Java implementations (Spring, Javalin,
  Helidon, Vert.x, Quarkus...) in the archived repo, which we can run locally as the
  comparison set. A claim like "on the TFB fortunes test, same machine, Brace does X vs
  Spring Boot's official TFB implementation" is legible to anyone.

### the-benchmarker/web-frameworks

- About 7k stars, active (pushed the day of this review), about 440 implementations, 25 under
  `java/` (Spring, Quarkus, Micronaut, Helidon, Javalin, Vert.x, Jooby...), results site at
  web-frameworks-benchmark.netlify.app.
- **No database.** The v2 contract (required for new submissions) is six routes on port 3000:
  static/dynamic routing, `/serialization` (100-object JSON array), `/deserialization`
  (parse 100 items, strict media-type rules), and multipart `/upload`. Load is `zrk --closed`,
  15s per route. Logging must be off, and there is no compression or TLS.
- Java implementations share a templated Dockerfile: `maven:3-eclipse-temurin-<ver>` build
  stage, `eclipse-temurin:<ver>-jre` runtime, `java -jar` with no special flags, and an
  optional GraalVM engine. Adding Brace means `java/brace/` with `config.yaml`, `pom.xml`
  and an app.
- For Brace: Jetty 12 plus Jackson should be competitive with the Spring MVC/Javalin tier and
  behind the Vert.x/ActiveJ/Undertow-raw tier. Brace-specific setup the app must do:
  `.csrf(false)` on the POST routes, `defaultAccess(Access.none())`,
  `BRACE_LOG_LEVEL=WARN`, and returning 415/400 exactly as the contract says. None of Brace's
  real strengths (DB lifecycle, sessions, templates, jobs) show up here.

## Part 3: where Brace should do well or badly

From the code, for a DB-backed TFB-style suite, especially under a memory cap:

**Likely strengths**

- **Lean request path.** Jetty 12 straight to `BraceHandler`. There's no servlet filter chain,
  no DI proxies, no reflection per request (`Invoker` builds typed invokers at startup). The
  June numbers already put plaintext/JSON at Spring-plus levels.
- **Read-only DB handlers skip BEGIN/COMMIT** (`ReadDbHandler`), which saves two round trips
  per request against frameworks that wrap every request in a transaction (Spring with
  `@Transactional`, or Spring Data JPA defaults).
- **Render after commit** (M12). The pooled connection is released before the JTE render,
  so the pool isn't held during template work. That matters a lot with a small pool on a
  small box.
- **JTE is precompiled, binary-content templates.** Fortunes rendering is cheap in both CPU
  and allocation (M6: View alloc -58%).
- **Virtual threads on JDK 25.** Blocking JDBC parks cheaply, and JEP 491 removes the
  `synchronized` pinning in Hibernate/pgjdbc. Concurrency is bounded by the pool, not threads.
  That's a good shape for 256 connections from wrk against a 10–20 connection pool.
- **Startup has no classpath scanning or DI container**, so it should start faster than Spring
  Boot JPA, though not as fast as Javalin or native images.

**Likely weaknesses**

- **Hibernate is the heaviest thing in the box.** Even with `StatelessSession`, the
  `SessionFactory` build, metamodel and HQL machinery cost tens of MB of heap/metaspace and
  0.5–1.5s at startup (M21). That's against `JdbcTemplate`/raw-JDBC implementations that
  pay none of that. This is the main risk under a 512MB cap, and it matters for startup-time
  comparisons.
- **Per-request `find()` through Hibernate vs a raw prepared statement.** Entity
  instantiation, `StatelessSession` open/close and the query translation per call. The June
  numbers suggest the gap is small now, but 20-query and updates tests multiply it.
- **No automatic JDBC batching of entity writes.** `App.java`'s `/updates` hand-writes a JDBC
  batch. Idiomatic Brace (`db.update(entity)` in a loop) would be slower, and a strict
  reviewer may call the hand-batched version non-idiomatic. Keep both variants and say which
  is which.
- **Always-on framework services.** With ops enabled, the JFR profiler (about 0.5–2% CPU plus
  buffers, opt out with `opsProfiler(false)`), the durable `JobPoller` and `JobScheduler` start
  whenever a database exists (L19, won't fix), and framework Flyway migrations run at boot.
  These are small but nonzero on a 1-vCPU/512MB box. The benchmark app should state which
  are on.
- **Hikari pool is fixed-size** (`minimumIdle == maximumPoolSize`). On a box where Postgres
  shares the 512MB, a 256 pool is wrong. Each PG backend is several MB. Expect to tune down
  to something like 8–16.
- **The JVM itself.** Any JVM framework starts at about 60–120MB RSS before the heap. In a
  512MB-total comparison against Go/Rust/Node entries, Brace will look heavy on memory and
  startup no matter what, unless a GraalVM native image is in scope (it isn't for Brace:
  Hibernate plus JTE plus reflection make that a project of its own).

### Hibernate vs raw-JDBC entries: is the DB comparison fair?

TechEmpower already answers this. Every entry carries an ORM class: **Raw**, **Micro** or
**Full**. Results are read with that label, and the site filters by it.

- The TFB Spring default profile (`jdbc`, `JdbcTemplate`) is classed **Micro**.
- Brace's idiomatic path (Hibernate `StatelessSession`) would be **Full**.

Comparing the two is legitimate as long as the table says which is which. It is *not* a
foregone loss. In a TFB read test the time goes to the Postgres round trip and the HTTP path,
not entity mapping. The June checkpoint had Brace's Hibernate reads at 31k req/s, against
Spring `JdbcTemplate`'s old 26k (different days, so only a hint).

To make it airtight, measure both sides of the Hibernate question rather than avoid it:

- **Full-ORM peers:** the archived TechEmpower repo has a blocking Hibernate ORM Quarkus
  entry (`frameworks/Java/quarkus/resteasy-reactive-hibernate`). It ships no Spring Data JPA
  variant; writing one (a `jpa` profile next to TFB's `jdbc`/`data-jdbc`/`jooq`) is about
  half a day. That's the like-for-like "Spring with Hibernate" comparison.
- **Brace's Hibernate cost:** a benchmark-only Brace variant that does the reads through
  `db.jdbc(...)` puts a number on what Hibernate costs Brace. If it's small, that's a claim
  in itself. If it's large, it's a perf-review finding.
- Report Brace against Spring `JdbcTemplate` (Micro), Spring Data JPA (Full) and Quarkus
  Hibernate (Full), each labelled.

**Decision (2026-11-25): the comparison is Brace vs Spring Data JPA only.** `JdbcTemplate` is
dropped: hand-written SQL against Brace's Hibernate isn't a fair fight, even with a label.
`run-spring.sh` (JdbcTemplate only) is removed. `run-docker.sh` compares `brace` with
`spring-jpa`: TechEmpower's Spring app plus a Spring Data JPA profile, in
`benchmark/docker/spring-jpa/`. A short cloud run before the switch had Brace at about
0.6–0.9x `JdbcTemplate` on the database tests and 1.5–2.2x on plaintext/JSON (single 5s samples).

## Recommended plan

| # | Step | What | Effort |
|---|---|---|---|
| 1 | Fix the harness | Bump/wire the `brace` version in `benchmark/pom.xml`; DB URL from env; `BRACE_LOG_LEVEL=WARN` in `run-brace.sh`; `run-spring.sh` on JDK 25 with `--latency` and the PID guard; fix `benchmark/README.md`; median-of-3 runs; capture RSS and startup | 0.5 day |
| 2 | Re-run TFB, Brace vs Spring Boot, JDK 25, same machine, same day | Replaces `RESULTS.md` and closes the `TODO.md` item. **The harness is ready:** `benchmark/run-docker.sh` runs Postgres, the app and wrk in containers pinned to separate CPUs, alternates frameworks round by round, and reports medians, ratios, RSS, startup and CPU steal. `benchmark/cloud/do-run.sh` runs it on a throwaway DigitalOcean CPU-Optimized droplet and destroys it afterwards (a short test run cost about $0.02). The comparison peer is `spring-jpa`: TechEmpower's Spring app plus a Spring Data JPA profile. `JdbcTemplate` was dropped as unfair, see the Hibernate section. What's left is the full-length run | About $0.20 and under an hour per full run |
| 3 | Small-box profile (PVRLabs-style, run ourselves) | A `docker compose` with app plus Postgres in one 512 MiB / 1 CPU budget (app `mem_limit` around 400m, Postgres around 100m with PVRLabs' tiny-PG settings), or a 512 MiB Lima VM to copy them exactly. JVM: `-Xmx80m` to match PVRLabs, plus a second run at `-XX:MaxRAMPercentage=50` (Brace's scaffold Dockerfile default); `-XX:+UseSerialGC` as a variant (often the smallest footprint on 1 CPU); `-Dbrace.mode=prod`, `BRACE_LOG_LEVEL=WARN`, Hikari pool 4–10, ops/JFR off. Workload: the TFB DB tests at a fixed rate (wrk2 or oha `-q`) plus a short saturation run. Brace vs Spring Boot JDBC and JPA. Report time to first 200, idle and peak RSS, p99 at fixed rate, max req/s, OOM/errors | 1 day after step 2 |
| 4 | the-benchmarker submission (optional) | `java/brace/` with the v2 six-route app plus `config.yaml`. Gives Brace a row in the most-watched live list, but says nothing about DB/templates | 0.5–1 day plus PR review time |
| 5 | Skip PVRLabs for now | Too new, single-maintainer, conflict disclosed. Revisit if it gains users. If we want their exact numbers, implementing their Market Replay app and running their GitHub Actions workflow on a fork is about 1–1.5 days and needs no PR. Step 3 already covers the methodology on our own terms | 0 (or 1–1.5 days) |
| 6 | Later: CI perf smoke | A short JMH allocation check (deterministic) in CI catches regressions without the macro-benchmark noise | 0.5 day |

Steps 1–3 are about 2.5 days and produce what the README needs. That's a same-machine,
reproducible, JDK 25 comparison against the most-used Java framework, plus a small-box number
that fits Brace's "useful, efficient" pitch. For the README wording, put the claim on the
same-machine ratio and link the scripts and raw output, rather than quoting absolute req/s from
a laptop.

Nothing here has been run. Each run step needs a go-ahead, because a full step 2 plus step 3
is a couple of hours of a quiet machine.

## Sources

- TechEmpower archive: GitHub `TechEmpower/FrameworkBenchmarks` (archived, last push
  2026-03-24); [Round 23 announcement](https://www.techempower.com/blog/2025/03/17/framework-benchmarks-round-23/);
  [DEV post on the archive](https://dev.to/kaliumhexacyanoferrat/techempower-framework-benchmarks-are-now-archived-whats-next-3l0a).
- [the-benchmarker/web-frameworks](https://github.com/the-benchmarker/web-frameworks):
  `README.md`, `SCENARIO.md`, `java/Dockerfile`, `java/config.yaml`.
- [PVRLabs/framework-benchmarks](https://github.com/PVRLabs/framework-benchmarks): README,
  `framework-benchmarks/protocol-v1.md`, `config-v1.json`, `vm-linux.yaml`,
  `scripts/prepare-postgres.sh`, `custom-runs.md`, `branch-strategy.md`,
  `evidence/debian-v1/`.
