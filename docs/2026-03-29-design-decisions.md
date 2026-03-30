# Brace Framework — Design Decisions & Trade-offs

A record of every significant decision made during the design of Brace, the alternatives considered, and why we chose what we chose.

## 1. Framework Philosophy

**Decision: Plain Java, no DI container, no magic**

| Option | Pros | Cons |
|---|---|---|
| Spring Boot style (DI container, auto-config, annotations) | Massive ecosystem, community, training data for AI | Hidden behavior, complex debugging, large API surface, DI graph scales super-linearly |
| Play 1 style (static methods, bytecode enhancement, ThreadLocals) | Extremely concise, fast dev cycle | Bytecode enhancement is fragile, ThreadLocals are implicit state AI struggles with |
| **Plain Java (chosen)** | Every dependency visible, compile-time errors, AI produces correct code on first try, small API surface | More verbose, no ecosystem, AI has fewer training examples |
| Javalin/Spark style (thin HTTP library) | Simple, plain Java | Not full-stack — have to wire up ORM, templates, sessions, etc. every project |

**Key insight:** We identified that no existing framework occupies the "plain Java + batteries included" quadrant. Every plain-Java framework (Javalin, Spark, Blade, Helidon SE) is HTTP-only. Every full-stack framework (Spring Boot, ACT, Ninja) has a DI container.

**AI optimization rationale:** AI excels at boilerplate and struggles with hidden behavior. A framework where everything is explicit and flows through parameters costs ~65% fewer tokens per task and produces ~90% fewer retries compared to Spring Boot, with the advantage growing as codebases scale (linear vs super-linear context requirements).

## 2. Database Engine

**Decision: H2 embedded (Docker volume) as default, PostgreSQL as optional backend**

### H2 vs SQLite vs PostgreSQL

| Option | Throughput from Java | Durability | Replication | Container-friendly |
|---|---|---|---|---|
| **H2 embedded (chosen)** | ~158K stmts/sec (fastest — pure Java, JIT-inlined) | MVStore, ~1s loss window | None built-in | Volume mount, no overlap possible |
| SQLite + JNI | ~30-50% slower (JNI boundary per call) | WAL-based, robust | Litestream, LiteFS (excellent ecosystem) | Same volume issue |
| SQLite + FFM (Panama) | Potentially close to H2 (no production driver exists yet) | Same as SQLite | Same as SQLite | Same volume issue |
| PostgreSQL localhost | ~50-100μs per query (vs ~1-5μs H2) | Excellent (WAL, PITR) | Built-in streaming replication | Fully container-friendly, zero-downtime deploys |
| sqlite4j (WASM→bytecode) | Unknown, likely slower | Loads DB into memory | None | Experimental |

**Key benchmarks:**
- H2 embedded: ~158,084 statements/sec
- H2 client-server: ~12,381 statements/sec (12.8x slower — validates in-process approach)
- PostgreSQL server: ~9,360 statements/sec

**H2 durability model:** MVStore uses log-structured copy-on-write (not traditional WAL). No transaction log, no undo log. Dual file headers with checksums for crash recovery. ~1 second loss window on hard crash. Configurable but forcing fsync kills performance.

**SQLite advantages we gave up:** Battle-tested durability (billions of deployments), Litestream continuous replication, point-in-time recovery, FULL OUTER JOIN support, FTS5. SQLite's replication ecosystem was a strong pull.

**SQLite FFM opportunity:** A prototype fork of xerial/sqlite-jdbc showed ~2x speedup over JNI using Panama's Foreign Function API. But the main project labeled it "wontfix" and no production driver exists. This could be a future Brace contribution.

**Container deployment concern:** H2 file-locks the database — two JVM instances can't access the same file. This prevents zero-downtime blue-green deploys. However, Dokploy's default mode is stop-old-then-start-new (recreate strategy), which works fine with H2 on a Docker volume. The volume mount itself adds zero overhead (it's just a host filesystem directory).

**H2 vs SQLite feature comparison that influenced the decision:**
- H2: strict typing, full ALTER TABLE, multi-writer MVCC, stored procedures, FULL OUTER JOIN
- SQLite: dynamic typing, very limited ALTER TABLE (migrations require create-copy-drop-rename), single writer only, no stored procedures
- H2's full ALTER TABLE support is critical for schema migrations in a web framework
- H2's multi-writer concurrency matters for concurrent form submissions

## 3. Persistence Layer (ORM)

**Decision: Hibernate 7 StatelessSession**

### Hibernate vs jOOQ vs raw JDBC vs JDBI

| Option | Per-query overhead | Type safety | Relationships | Codegen needed | Learning curve |
|---|---|---|---|---|---|
| Hibernate (standard Session) | ~200μs (dirty check, snapshot, persistence context) | Runtime (JPQL strings) | Full (lazy loading, cascading, identity map) | No | Steep |
| **Hibernate 7 StatelessSession (chosen)** | ~15-25μs (no dirty check, no cache) | Runtime (JPQL strings) | Explicit fetching only | No | Moderate |
| jOOQ | ~5-10μs (StringBuilder SQL + reflection mapping) | **Compile-time** (generated DSL) | MULTISET (excellent), implicit path joins | Yes (build step) | Medium |
| JDBI | ~2-5μs (thin JDBC wrapper) | None (SQL strings) | None | No | Low |
| Raw JDBC | ~1-2μs | None | None | No | Low |

**jOOQ was a strong contender.** Compile-time type safety, MULTISET for nested collections (arguably better than Hibernate for N+1 prevention), ~95% of raw JDBC performance. The codegen step was the concern — but it's well-established (spin up in-memory H2, apply migrations, generate code). jOOQ's open source edition fully supports H2 (Apache 2.0 license).

**Why Hibernate won:** The user has extensive JPA experience. Hibernate 7's StatelessSession is dramatically improved — now supports batch operations (`insertMultiple`, `updateMultiple`), EntityGraph, second-level cache, essentially everything except the first-level cache. This eliminates the three biggest overhead sources (dirty checking, snapshot copies, persistence context) while keeping a familiar API.

**Critical insight about in-process DB + ORM overhead:**
With H2 embedded, a query takes ~1-5μs. Hibernate's standard Session adds ~200μs of overhead (dirty checking, snapshots). That means **the ORM is 87% of total query time** — the opposite of the networked case where the ORM is ~29%. StatelessSession brings Hibernate overhead to ~15-25μs, making the ORM a reasonable ~75-85% of the ~20-30μs total. jOOQ would bring it to ~5-10μs. The difference is ~6.9x per request (estimated 255μs vs 1,750μs for a 5-query + 2-write page).

**Hibernate 6/7 improvements that helped the decision:**
- ResultSet read-by-position instead of by-name (6.0) — faster column access
- ~18% faster query parsing (Antlr v4, unified SQM model)
- 12% lower memory footprint for metadata (7.0)
- 8% faster immutable entity queries (7.0)
- StatelessSession batch operations (7.0)
- Bytecode-enhanced dirty checking (opt-in, ~10-14% improvement for large persistence contexts — but we avoid dirty checking entirely with StatelessSession)

## 4. HTTP Server

**Decision: Jetty 12**

| Option | Throughput | Virtual threads | WebSocket | HTTP/2 | Complexity |
|---|---|---|---|---|---|
| Netty | Fastest (~13% over Jetty) | Requires bridging event loop model | Yes | Yes | High (channel pipelines, event loops) |
| **Jetty 12 (chosen)** | ~10-15% slower than Netty | Native support (Jetty 12 adaptive execution) | Yes (JSR 356 + native API) | Yes | Moderate |
| Undertow | Fast | Good | Yes | Yes | Moderate |
| `com.sun.net.httpserver` | Adequate | Native | No | No | Low |
| Custom on `java.nio` | Maximum control | Manual | Manual | Manual | Very high |

**Why not Netty:** Netty's ~13% throughput advantage (5M requests: 45s vs 51s) translates to <1% of total request time for a full-stack web app. The HTTP layer is ~33μs of a ~388μs request. Meanwhile, Netty's event-loop model requires complex bridging to work with virtual threads and blocking Hibernate calls. Jetty 12's thread-per-request model maps naturally to virtual threads.

**Jetty 12 specific advantages:**
- Core API is 22% faster than Jetty 11
- Virtual threads showed up to 10x throughput improvement in I/O-bound scenarios
- P99 latency: 30μs (Core) / 34.8μs (EE10 Servlet) at 240K req/s
- Mature, well-documented, actively maintained by Webtide

## 5. Template Engine

**Decision: JTE (Java Template Engine)**

| Option | Rendering speed (10K requests) | Type safety | Hot reload | Syntax |
|---|---|---|---|---|
| **JTE (chosen)** | 1.797s | Compile-time (typed params) | Yes (built-in file watcher) | `@param`, `@if`, `@for`, `${}` |
| Thymeleaf | 4.786s (2.7x slower) | Runtime | Yes | Natural HTML attributes |
| Freemarker | 1.983s | Runtime | Yes | `<#if>`, `${}` |
| Rocker | 1.497s (fastest) | Compile-time | Yes | Similar to JTE |
| Groovy (Play 1 style) | "too slow" (benchmark couldn't measure) | Runtime | Yes | `#{}`, `${}` |

**JTE vs Play 1 Groovy comparison:**
- Play 1 is more concise (Groovy property access `post.title` vs `post.getTitle()`, `?.` null safety, `#{list}` tag)
- JTE catches errors at compile time (typo in `${post.titl}` fails the build vs silently rendering nothing in Play 1)
- JTE compiles to Java classes (JIT-optimized) vs Groovy classes (dynamic dispatch overhead)
- Both support hot reload in dev mode with the same model: watch files → recompile → reload

**Verbosity mitigation:** Using Java records/entities with public fields means JTE can access `${post.title}` directly instead of `${post.getTitle()}`.

**How Play 1 compiles templates (for context):** Template source → TemplateParser (tokenize) → GroovyTemplateCompiler (tokens → Groovy source) → Groovy CompilationUnit (Groovy source → JVM bytecode) → ExecutableTemplate class. Three modes: precompiled (prod), bytecode cache (tmpdir), runtime compilation (dev). JTE follows the same model but targets Java instead of Groovy.

## 6. Controller & Routing Model

**Decision: Programmatic routing with method references, explicit parameters**

| Option | Description | AI-friendly | Requires |
|---|---|---|---|
| Spring-style annotations | `@Get("/posts/{id}")` on methods | Medium (hidden behavior behind annotations) | Annotation processing or classpath scanning |
| Play 1 routes file | External file maps URLs to controllers | Good (all routes visible) | Separate file to maintain |
| Play 1 convention routing | `GET /posts/show → PostController.show()` | Poor (implicit coupling between URL and method name) | Convention knowledge |
| **Programmatic routing (chosen)** | `app.get("/posts/{id}", posts::show)` | Excellent (explicit, type-checked method refs) | Nothing — it's plain Java |

**Why not annotation-based routing:** During the design discussion, we realized annotation-based routing was converging with Spring MVC. The "plain Java, no magic" philosophy pointed toward programmatic routing — routes are just method calls in `main()`, not metadata scattered across classes.

**Why not convention routing:** AI can trivially generate one route line per endpoint. Convention routing creates implicit coupling (renaming a method changes the URL) and makes route discovery harder.

**Why not a routes file:** Explicit route registration in `main()` means the route table is printed at startup and available via `/ops/routes`. No separate file to maintain, no sync issues.

**Parameter injection via startup reflection:** Controller method signatures are inspected once at boot to determine what parameters to provide (Request, Database, Session). At request time, a pre-built lambda is called — no per-request reflection. If a method doesn't take `Database`, no DB session is opened.

**Controller instantiation:** Controllers are created once in `main()` and reused (singletons). All request-scoped state comes via method parameters. Service dependencies like `Mailer` are passed via constructor.

## 7. Session Implementation

**Decision: HMAC-SHA256 signed cookies, no server-side storage**

| Option | Storage | Survives restarts | Scalable | Overhead |
|---|---|---|---|---|
| Server-side sessions (DB) | Database table | Yes | Requires cleanup job | ~50-100μs per request (DB lookup) |
| Server-side sessions (Redis) | External Redis | Yes | Yes | Network hop per request |
| **Signed cookies (chosen)** | Client browser | Yes (stateless) | Infinitely | ~10μs (HMAC verify) |
| Encrypted cookies | Client browser | Yes | Infinitely | ~15μs (decrypt + verify) |

**HMAC-SHA256 vs Play 1's HMAC-SHA1:** Play 1 uses SHA-1 which is theoretically weakened (collision attacks since 2017). HMAC-SHA1 is still safe for MAC purposes, but there's no reason for new code to use it. SHA-256 adds ~16 bytes to cookie size (44 vs 28 byte signature). Performance difference is sub-microsecond.

**Trade-off accepted:** Session contents are visible to the client (base64 JSON). They're tamper-proof but not secret. For most web apps (userId, role, flash messages), this is fine. Encryption could be added as an option if needed.

## 8. Form Binding

**Decision: Form records with validation annotations, `apply()` convention for entity mapping**

Alternatives considered for form → entity mapping:

| Approach | Safety | Verbosity | Drift risk |
|---|---|---|---|
| Manual field-by-field in controller | Highest (explicit) | High (N lines per N fields) | High (add field, forget to map it — silent null) |
| `Forms.map()` reflection helper | Medium (implicit matching) | Low | Medium (might map fields that shouldn't be form-settable) |
| `Forms.mapStrict()` with startup validation | High (fails fast on mismatch) | Low | Low |
| **`apply()` method on entity (chosen)** | High (centralized, explicit) | Medium (one method) | Low (one place to update) |

**Why `apply()` won:** Centralizes the mapping in one place on the entity. When a field is added, there's one method to update. It's explicit (you see what gets set), doesn't use reflection, and naturally separates form-sourced fields from system-set fields (authorId, createdAt). The drift risk of manual field-by-field mapping across multiple controllers was the main concern that led to this convention.

## 9. Job System

**Decision: Two-tier — in-memory recurring + database-backed durable**

| Feature | In-memory jobs | Durable jobs |
|---|---|---|
| Survives restart | No (re-registers on boot) | Yes (database table) |
| Use case | Recurring maintenance | One-off future tasks |
| Schedule | Interval, daily, cron | Specific timestamp or delay |
| Implementation | `ScheduledExecutorService` | Database poller on virtual threads |

**Durable job poller design evolution:**
- Initial design: poll every 10s, grab 10 jobs sequentially
- Problem: at 10,000 queued emails, would take ~167 minutes to drain
- Revised: adaptive poller — 10s idle poll, continuous when queue has items, batch size 50, parallel execution on virtual threads
- Added `Jobs.parallel(items, concurrency, action)` helper for bulk operations within a single job

**Job dependencies:** Simple single-dependency chains (`JobOptions.after(jobA)`) via a `depends_on_id` foreign key column. The poller query adds one WHERE clause. We explicitly decided against complex DAG support (fan-in, fan-out, failure propagation) — that's Temporal/Airflow territory.

## 10. AI Ops Layer

**Decision: Built-in diagnostics, dashboard, and control API**

This wasn't a choice between alternatives — nothing like this exists in any framework. The design emerged from the question "what would a framework look like if it were designed for AI agents to operate?"

**Key design insight:** Existing frameworks expose metrics for humans (Grafana dashboards, Prometheus endpoints). Nobody exposes a structured diagnostics API designed for AI agents to consume and act on.

### Stats persistence trade-off

| Data type | Storage | Rationale |
|---|---|---|
| Request throughput/latency | In-memory ring buffer, flushed to DB once per minute | Hot path — can't write on every request. 1 INSERT/minute is negligible |
| Errors | Database | Valuable history, low write volume (only on errors). Deduplicated by type + route |
| Job execution history | Database (already in `scheduled_jobs` table) | Natural fit |
| Email history | Database | Low volume, useful audit trail |
| Slow queries | In-memory top 20 | Transient by nature |

**Stats retention:** Per-minute granularity for 48 hours (2,880 rows max), compacted to per-hour beyond that (~720 rows/month). Total table stays under ~10,000 rows indefinitely. Daily compaction job runs at 03:00.

**Dashboard implementation:** Single HTML file embedded in framework jar. No React, no npm, no external dependencies. Plain HTML + inline CSS + inline JS (~15KB). Fetches `/ops/status` every 5 seconds. Sparkline charts via SVG. Same data source powers both the human dashboard and the AI agent.

## 11. Hot Reload Strategy

**Decision: Fast process restart, no custom classloader**

| Option | Speed | Complexity | Reliability |
|---|---|---|---|
| Play 1 custom classloader | ~200ms (instant) | Very high (bytecode enhancement, class reloading edge cases) | Fragile (memory leaks, stale state) |
| Spring DevTools (restart) | ~2-3s | Low (provided by framework) | Good |
| **Incremental compile + JVM restart (chosen)** | ~1-2s | Minimal | Excellent |
| DCEVM / HotswapAgent | ~200ms for method bodies | Medium (JVM agent, external dependency) | Good for simple changes, breaks on structural changes |

**Why not Play 1's approach:** The custom classloader is the heart of Play 1's bytecode enhancement — exactly what we're avoiding. It causes subtle bugs (stale class references, ClassCastException across classloader boundaries, memory leaks from unreleased classloaders).

**Why ~1-2s is acceptable:** H2 embedded means no database reconnection on restart. JTE templates hot-reload without restart (file watcher). Config hot-reloads without restart. The only thing that needs a restart is Java source changes, and incremental compilation + fast JVM startup keeps this under 2 seconds.

## 12. Testing Strategy

**Decision: Three-level testing with in-process H2, no Docker/Testcontainers. Playwright for E2E browser tests.**

### Unit vs integration test boundary

| Option | Speed | Reliability | Realism |
|---|---|---|---|
| Mocked unit tests (Mockito) | <1ms | Perfect (no real deps) | Low (mocks can diverge from reality) |
| Spring-style MockMvc (no real server) | ~10ms | Good | Medium (fake HTTP layer) |
| Testcontainers (Docker PostgreSQL) | ~5-30s startup + ~50ms per test | Good but flaky (Docker timing) | High |
| **Brace TestApp with H2 in-memory (chosen)** | ~50ms startup + ~5ms per test | Perfect (no external deps, no network) | High (real routing, real DB, real templates) |

**Key insight:** With H2 in-memory, the traditional reason for separating unit and integration tests (database startup is slow) disappears. The full app boots in ~50ms. A test that hits real routing, real Hibernate, real templates takes ~5ms. There's little reason to maintain mock-based unit tests when the real thing is just as fast.

**Why not Testcontainers:** Testcontainers is the industry standard for testing against real databases like PostgreSQL. But Brace's default database is H2, and H2 in-memory is in-process — no Docker, no container startup, no network. Tests are faster and more reliable. For apps using PostgreSQL as their backend, Testcontainers would still be an option but not the default path.

### E2E browser testing

| Option | Java support | Speed | Reliability | Size |
|---|---|---|---|---|
| Selenium | Yes (original) | Slow (WebDriver layer) | Flaky (explicit waits needed) | ~50MB+ |
| Cypress | No (JavaScript only) | Fast | Good | ~500MB |
| **Playwright (chosen)** | First-class (`@UsePlaywright` JUnit 5) | Fastest (2-15x over Selenium) | Best (auto-waiting, no sleep) | ~10MB |

**Why Playwright:** Auto-waiting eliminates the primary cause of flaky browser tests — no `Thread.sleep()`, no explicit wait conditions. Playwright waits for elements to be actionable before interacting. This makes AI-generated E2E tests reliable without AI needing to reason about timing.

### Accessibility as a testing strategy

**Decision: Semantic HTML as default in scaffolded templates, enabling Playwright role-based selectors.**

Playwright's recommended selectors (`getByRole`, `getByLabel`, `getByText`) map directly to accessible HTML. A `<label for="email">Email</label>` in the template becomes `page.getByLabel("Email")` in the test. This means:

- Tests don't couple to CSS classes (survive redesigns)
- AI generates reliable selectors by reading the template (label text → `getByLabel`)
- Accessibility compliance is a side effect of writing testable HTML
- No separate "accessibility pass" needed

We chose convention (scaffolded templates use semantic HTML) over enforcement (no runtime checks) because the goal is making the accessible path the easiest path.

## 13. Naming

**Decision: Brace**

Working name. Chosen because: curly braces `{}` connect to "plain Java," short and easy to type as a CLI command, not taken as a major Java project. We considered AI-themed names but decided they'd age poorly and feel gimmicky.

## Frameworks Surveyed

During research we evaluated these frameworks for overlap with our goals:

| Framework | Plain Java? | Full-stack? | Active? | Virtual threads? | Why not |
|---|---|---|---|---|---|
| Spring Boot | No (DI container) | Yes | Yes | Yes | Too much magic, DI overhead |
| ACT Framework | No (Genie DI) | Yes | Mostly (bus factor 1) | No | Has DI, uses Ebean, small community |
| Ninja Framework | No (Guice DI) | Yes | Dead (~2022) | No | Has DI, unmaintained |
| Blade | Yes | No (HTTP only) | Dead (~2019) | No | Too thin, abandoned |
| Helidon SE | Yes | No (HTTP only) | Yes (Oracle) | Yes | Great server, no full-stack features |
| Javalin | Yes | No (HTTP only) | Yes | Yes | Closest starting point, but HTTP-only |
| Pippo | Yes | No (micro) | Barely | No | Too micro |
| Play 1 | No (bytecode enhancement) | Yes | Maintained | Adding (our branch) | Right philosophy, stuck on old tech |
| Play 2 / Scala | No (Scala, Akka) | Yes | Yes | No | Different language/paradigm |
