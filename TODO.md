# Brace — Next Steps

This file is the single source of truth for open work. Multi-phase efforts with substantial design get a detailed plan under `docs/YYYY-MM-DD-*.md`; in that case the TODO entry is a one-line pointer to the plan. Don't duplicate plan content into TODO bullets — update the plan instead.

Active plans:
- [`docs/2026-05-25-brace-deploy-plan.md`](docs/2026-05-25-brace-deploy-plan.md) — `brace deploy` CLI + server-side regression detection (Phase 0–5).
- [`docs/2026-05-29-brace-oncall-agent-plan.md`](docs/2026-05-29-brace-oncall-agent-plan.md) — `brace-oncall` autonomous on-call AI developer: event-driven incident triage over the ops surface, tiered autonomy, human-gated production mutation. Gated on scoped read-only ops tokens + audit log + redaction layer + deploy-plan Phase 0.

## Security Hardening — Phase 1 ✅ COMPLETE (409 tests passing)

- [x] Trusted proxy configuration — explicit CIDR-based proxy trust, prevents IP spoofing
- [x] Session encryption — AES-256-GCM encrypted cookies with PBKDF2 key derivation
- [x] Session cookie policy — SessionOptions with secure defaults (Secure, HttpOnly, SameSite)
- [x] CSRF exemption logic — explicit `.csrf(false)` opt-out, required by default for mutating requests
- [x] Security headers middleware — SecurityHeaders.defaults() with nosniff, frame-options, referrer-policy, permissions-policy
- [x] Secret validation — 32-char minimum, rejects weak patterns, startup validation
- [x] Ops auth uses Jackson — replaced manual JSON parsing with typed OpsAuthRequest record

## Bugs

- [x] **Non-multipart request bodies over ~64 KB intermittently hang 30 s, then 500** — `BraceHandler.handle()` eagerly reads the whole request body for every non-multipart request with the *blocking* `Content.Source.asString(jettyRequest)` (`BraceHandler.java:171`). When the entire body arrives in Jetty's first content read it returns in well under a second; when the body spans more than one chunk the read intermittently never completes, the connection goes idle, and Jetty's 30 s idle timeout fires:

  ```
  java.io.IOException: java.util.concurrent.TimeoutException: Idle timeout expired: 30000/30000 ms
    at org.eclipse.jetty.util.Blocker$3.block(Blocker.java:223)
    at org.eclipse.jetty.io.Content$Source.asString(Content.java:448)
    at org.eclipse.jetty.io.Content$Source.asString(Content.java:429)
    at com.larvalabs.brace.BraceHandler.handle(BraceHandler.java:171)
  ```

  Reproduced with a plain `curl` straight at the origin (no proxy/CDN in the path), single instance, server otherwise idle (heap 88 MB, 41 threads, GC negligible):

  | Body | Result |
  |---|---|
  | 9 KB / 63 KB | 200 in ~70–80 ms (10/10) |
  | 70 KB | 500 after 30.07 s |
  | 160 KB | 500 after ~30.1 s — 7 of 8 attempts (one squeaked through in 0.11 s) |
  | 160 KB, forced HTTP/1.1 | 500 after 30.1 s |

  The threshold sits between 63 and 70 KB (≈ one socket-buffer / first-read worth): below it 10/10 succeed, above it ≈ 85 % fail. Not size-deterministic above the threshold and reproduces on both HTTP/2 and HTTP/1.1 — a race in the multi-chunk read path, not a hard limit or a protocol issue. The blocking `asString` overload bridges to the async read via `org.eclipse.jetty.util.Blocker`; blocking a Jetty handler thread on the body read inside `Handler.handle()` — while the network read that would satisfy the next `demand()` needs a thread from the same `AdaptiveExecutionStrategy` pool — is the leading hypothesis for the stall (unverified; for the framework author to confirm).

  Operational impact: **any** POST/PUT with a non-multipart body over ~64 KB — webhooks, large JSON, raw-body uploads — fails ≈ 85 % of the time with a 30 s stall and a 500. Multipart uploads use a separate path (`parseMultipart`, line 167) and are unaffected. The eager read also means handlers that never touch the body still pay the cost and hit the bug.

  Surfaced 2026-05-21 debugging Wendell's inbound-email webhook: forwarded emails carrying a PDF (~120 KB raw → ~160 KB base64 POST body) failed to post to Slack; Cloudflare's webhook retry masked it part of the time, so it presented as flaky end-to-end. Wendell runs `brace-0.1.2-SNAPSHOT`.

  Fix direction: don't block a handler thread on a multi-chunk body read — accumulate chunks asynchronously via `demand()` and dispatch the route only once the body is complete, or run the blocking read off the Jetty thread. Consider also reading the body lazily (on first `req.body()` access) rather than eagerly for every request. Add a regression test that POSTs bodies straddling the chunk boundary (1 KB, 64 KB, 256 KB, 1 MB) and asserts sub-second completion.

  **Resolved 2026-05-21.** Root cause was a one-line server misconfiguration, not the async-read rewrite the fix direction above anticipated. `Brace.start()` passed `Runnable::run` to `QueuedThreadPool.setVirtualThreadsExecutor(...)`. A non-null executor makes `AdaptiveExecutionStrategy.isUseVirtualThreads()` return true, so Jetty assumes blocking a handler is free (a virtual thread would just park) and runs the handler via that executor instead of dispatching a thread to keep producing. But `Runnable::run` runs the handler *inline on the producer thread* — so when `Content.Source.asString` blocks waiting for the next chunk, the only thread that could read that chunk is itself blocked → deadlock until the 30 s idle timeout. Single-chunk bodies never block, hence the ~64 KB threshold. Fix: pass a real virtual-thread executor (`VirtualThreads.getDefaultVirtualThreadsExecutor()`) — the handler then runs on an actual virtual thread that parks and frees its carrier to read the next chunk. The blocking `asString` is fine on a real virtual thread; no async rewrite needed. Handlers now genuinely run on virtual threads, which also benefits the Hibernate/JDBC blocking paths. Regression test: `RequestBodyTest` — POSTs bodies in two halves over a raw socket with a deliberate gap to force the multi-chunk park (a normal loopback client buffers the whole body and never reproduces it).

## Tier 0 — Critical Security & Ops Hardening

- [x] Ops browser login token — CLI requests short-lived/single-use token, opens browser to exchange endpoint that sets httpOnly cookie and redirects to dashboard (eliminates query-param token from ongoing dashboard URLs)
- [ ] Redaction layer — auto-redact sensitive headers/params/keys in logs and ops diagnostics (authorization, cookie, token, secret, password, key patterns). Higher priority because logs are reachable over HTTP via `brace logs` / ops dashboard — a single curl with a stolen ops key pulls the ring buffer, vs. SSH-gated file access on a traditional server. Allowlist-based field-name match; not content-level PII detection.
- [x] Ops endpoint audit log — every authenticated ops request is recorded as a structured `ops.access` log event (`kid` + scope + method + path + granted), attributed to the token's key fingerprint. Rides the normal logging pipeline (durable wherever stdout goes, queryable via `brace logs` / `/ops/logs` filtered on `event=ops.access`) — no separate store or migration, works with or without a database. Records granted access and authenticated-but-scope-denied attempts (the `granted=false` escalation signal); unauthenticated probes surface as 401s in the request log. Implemented in `OpsAudit`, hooked at the `OpsHandler.authorize` choke point. Makes a stolen ops key visible after the fact.
- [ ] Key rotation — support current + previous keys for sessions and ops auth (allow rotation without forced logout)

## Tier 1 — API Clarity & AI Ergonomics (Additive, Non-Breaking)

- [x] Typed route methods — `app.getDb(path, DbHandler)`, `app.postSession(...)`, `app.putFull(...)` etc. (eliminates cast syntax)
- [x] Source-specific request accessors — `req.pathParam()`, `req.queryParam()`, `req.formParam()`, `req.intPathParam()`, `req.queryInt()` etc. (eliminates ambiguity)
- [x] Unified `Result.*` helpers — `Result.json()`, `Result.view()`, `Result.redirect()`, `Result.notFound()` etc. (one namespace for all responses)
- [x] Constrained DB helpers — `db.findBy(Class, field, value)`, `db.findAllBy()`, `db.countBy()`, `db.existsBy()`, `db.deleteBy()` (covers 80% of queries without string generation)
- [x] `Form.hasErrors()` — clearer boolean logic than `!valid()`
- [x] Safer storage helpers — `storage.safeKey()`, `storage.putGenerated()` (makes safe pattern the easy pattern)
- [x] Update all examples to canonical style — ensure docs/examples show one dominant pattern per feature
- [x] Support all logging levels — add `Log.debug()`, `Log.info()`, `Log.error()` (currently has warn() and event())
- [x] Binary response support for `Http` client — `Http.get(url).fetchBytes()` → `byte[]` (currently only `fetchString()` / `fetchJson()`)
- [x] Clean up doc drift — ensure README, AGENTS.md, SECURITY.md are consistent; mark historical design docs with status banners
- [ ] `brace new` should generate a fresh `session.secret` — match Play 1's behavior of writing a randomized secret into `application.conf` at scaffolding time (e.g. `openssl rand -hex 32` equivalent). Today the template ships `CHANGE-ME-to-a-random-string-at-least-32-chars` and every new project owner has to remember to swap it. Footgun for first-deploys: Brace's secret-strength validator catches the literal placeholder, but a developer who only mostly-rotates it (e.g. trims the prefix) can still ship a weak production secret. Generating once at `brace new` time eliminates the manual step entirely.
- [ ] Surface asset fingerprinting in the auto-generated `CLAUDE.md` capability index — today `Assets.url()` lives in `BRACE-AGENTS.md` under "Asset Fingerprinting" but isn't in the per-project CLAUDE.md snapshot that `app.generateClaudeMd()` produces. Agents bootstrapping in a project read CLAUDE.md first and miss the cache-busting helper, then reach for ad-hoc workarounds (e.g. setting short `Cache-Control` via `app.after`, or hand-bumped `?v=N`) when fighting CDN staleness. Add a one-line entry alongside Storage / Cache / Sessions: `Assets — content-hash URL fingerprinting via Assets.url("/path") for cache-busted static asset references in templates`. Discovered while iterating on a JTE template behind Cloudflare and watching a deploy hide behind a 4-hour edge cache.
- [ ] Default `Cache-Control` on `staticFiles` responses — today `app.staticFiles("/public", "public")` returns the file bytes with no `Cache-Control` header, so each CDN/proxy applies its own default (Cloudflare's was `max-age=14400` in our case, leaving deploys stale for 4 hours until manual purge). Right default given fingerprinting already exists: emit `Cache-Control: public, max-age=31536000, immutable` when the request URL has a `?v=` query param (those URLs are content-addressed via `Assets.url()` so unchanged content stays cached and a real change mints a new URL anyway), and `public, max-age=300, must-revalidate` otherwise. Apps that need different policy can still override with `.after("/public/*", ...)`. The `BRACE-AGENTS.md` "Asset Fingerprinting" section already advises pairing fingerprinting with a long `max-age` — making it the default closes the gap that the docs currently expect each app author to bridge.
- [x] Bundle framework-internal migrations in the release jar — framework SQL now ships at `src/main/resources/brace/db/migration/` as `V1__brace_scheduled_jobs.sql`, `V2__brace_ops_tables.sql`, `V3__brace_profiling_tables.sql` and is applied via a separate Flyway instance with its own schema-history table (`flyway_brace_history`). The two history tables make the version spaces independent — apps can keep their own `V1`, `V2`, … without colliding. (Initial attempt used a `B` prefix; that's reserved by Flyway for baseline migrations and silently broke multi-file scans.) `BIGINT AUTO_INCREMENT` (H2-only) replaced with `BIGINT GENERATED ALWAYS AS IDENTITY` for H2/Postgres parity. Surfaced from a first deploy hitting `relation "scheduled_jobs" does not exist`.
- [x] Parse PaaS-style `DATABASE_URL` in `DatabaseFactory` — accept bare `postgresql://` / `postgres://` schemes (Dokploy/Heroku-style platforms inject these; without normalization Flyway logs "No database found to handle &lt;url&gt;") and extract embedded `user:pass@` credentials before handing the URL to JDBC (pgjdbc otherwise treats the whole authority as a hostname → `UnknownHostException`). Explicit user/pass args still win; URL-embedded creds are the fallback. Password URL-decoded for libpq parity.
- [ ] Log every request, including early-return rejections — `BraceHandler.handle()` only calls `Log.request()` and `stats.recordRequest()` in three paths: the normal handler-completed path, the `NotFoundException` catch, and the generic exception catch. Early returns from `before` middleware, the static-file fallback, and (most painfully) the CSRF rejection at `BraceHandler.java:252` write the response and `return true` without ever logging the request. Operational consequence: requests rejected at those points are invisible in `/ops/status`, `brace logs`, and `http.request` event streams — they look as if they never reached the app. Surfaced 2026-05-07 while debugging Wendell's `brace status` returning 403 "Forbidden" against an older 0.1.2-SNAPSHOT jar still running pre-CSRF-fix code: the POSTs reached origin, were CSRF-rejected, and produced no log line — leading to ~30 minutes of misdiagnosis as a Cloudflare zone-level block. Refactor so request-completion logging happens in a single `finally`-style path covering every exit (success, all early returns, exceptions), and emit at minimum `method`, `path`, `status`, `durationMs`. Bonus: include a tag like `rejection: csrf | static | before-middleware` so post-mortems can tell which short-circuit fired without grepping source.
- [x] Make framework migrations idempotent for upgrade-path safety — `V1__brace_scheduled_jobs.sql`, `V2__brace_ops_tables.sql`, `V3__brace_profiling_tables.sql` use bare `CREATE TABLE` / `CREATE INDEX`, which crashes apps upgrading from a pre-bundled-migration brace where the same tables were created from the app's own migration sequence (e.g. wendell's `V3__brace_internal_tables.sql`). When `runFrameworkMigrations` runs against such a schema, `flyway_brace_history` is empty so V1 is on the apply path, and Flyway aborts with `relation "scheduled_jobs" already exists`. The app never finishes `main()` and serves 502 from then on. Switch to `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` (and equivalent guards on FKs) so adopting bundled framework migrations on a populated schema is a no-op rather than a crash. The `baselineVersion(0)` comment in `DatabaseFactory.runFrameworkMigrations` already calls out this scenario as intentional ("apps upgrading from a Brace release that didn't ship migrations") — but the migration scripts themselves don't honor that intent. Surfaced 2026-05-07 by a Wendell prod outage after bumping to brace 0.1.2-SNAPSHOT; recovered by hand-inserting V1/V2/V3 rows into `flyway_brace_history` with computed CRC32 checksums.

## Framework Improvements

- [x] Exception tracking — persistent `ops_errors` table, `GET /ops/errors`, `POST /ops/errors/{id}/resolve`
- [x] Ops endpoint security — `X-Ops-Key` header with query param fallback for dashboard
- [x] Ops token auth — Ed25519 keypair auth, SSH-style authorized keys, short-lived tokens, CLI commands
- [x] Cache implementation (~120 lines — ConcurrentHashMap + TTL + tag-based invalidation + `cache.wrap()`)
- [x] HTTP client wrapper (`Http.get()`, `Http.post()`, `.bearer()`, `.bodyJson()`, `.fetchJson()` — over `java.net.http.HttpClient`)
- [x] HTTP client: multipart form and binary request body support (`Http.post(url).bodyBytes(bytes, "image/png")`, `Http.post(url).multipart().field("file", bytes, "image.png")` — needed for S3/R2 uploads, Slack file uploads, Bluesky blob uploads, Reddit media uploads)
- [x] Binary/stream responses (`Result.bytes()` + `Result.download()` with Content-Disposition)
- [x] Flash messages (`session.flash("key", "value")` — survives one redirect, auto-consumed by BraceHandler)
- [x] `notFoundIfNull()` convenience (`Result.notFoundIfNull(value)` — throws `NotFoundException`, caught as 404)
- [x] URL generation from route patterns (`Url.to("/users/{id}", 42)` → `"/users/42"`)
- [x] Enhanced ops dashboard (error tracking with resolve, cache stats, clear cache button, expandable stack traces)
- [x] Ops control endpoints (`POST /ops/cache/clear`, cache stats in `/ops/status`)
- [x] WebSocket support (`app.ws("/path", Handler::new)` with WsContext, rooms, broadcast, session access)
- [x] Route grouping (`app.group("/admin", admin -> { ... })`)
- [x] Static file serving (`app.staticFiles("/assets", "public")`)
- [x] `brace dev` CLI command with file watcher + fast restart (`./brace dev`, `./brace test`, `./brace run`)
- [ ] **`brace deploy` + server-side regression detection** — full design in [`docs/2026-05-25-brace-deploy-plan.md`](docs/2026-05-25-brace-deploy-plan.md). Phase 0 adds `/ops/regressions` (new error kinds since startup) + webhook/email notifiers. Phase 1–5 add the CLI verb with shell + Dokploy adapters, version tracking, agent skill, and rollback. Subsumes the previous "Dokploy ops skill" and "Deploy hooks (app.error.new)" TODO lines.
- [x] Auto-generated CLAUDE.md (`app.generateClaudeMd(projectName, path)` — capability index with pointers to AGENTS.md for full API reference)
- [x] `brace init` + `.brace` project config file — store dashboard URL, ops key path, etc. so CLI commands don't need `--url` and other repeated flags
- [x] Move ops keypair + dashboard CLI config from global to project-scoped (each project has its own keypair and ops URL)
- [x] Additional `brace` ops commands for humans + AI agents: `brace errors`, `brace logs` (in-memory ring buffer), `brace status`, `brace cache`, `brace resolve`
- [x] `brace check` — single-command production health check with 9 checks (errors, latency, cache, mailer, JVM, etc.), configurable thresholds, transparent re-auth
- [x] App-level custom metrics (`Stats.counter("talks.created")`, `Stats.gauge("queue.depth", supplier)`, `Stats.timer("api.latency", durationMs)` — lock-free LongAdder internals; auto-rendered in ops dashboard with sparklines; exposed in `/ops/status` JSON; in-memory ring buffer)
- [x] S3-compatible storage (`Storage.s3(config)`, `storage.put(key, bytes, contentType)` returns public URL, `storage.delete(key)`, `storage.url(key)` — built-in AWS Sig V4 signing, no SDK; works with S3, R2, MinIO; config: accessKeyId, secretKey, bucket, endpoint, region, publicUrl; integrates with `req.file()` uploads)
- [x] `db.withSession()` for scoped DB access outside request lifecycle
- [x] `INSERT ... RETURNING id` — fixed via JDBC `getGeneratedKeys()` (works with H2 and PostgreSQL)
- [x] `db.queryIn()` for IN clause support (e.g., `db.queryIn(Talk.class, "id", idList)`)
- [x] Custom message from a job run to show on the ops dashboard (e.g., "Retrieved 4 new listings") — `Jobs.message(...)`
- [ ] Ops key UX gaps — `brace ops keypair` generates a fresh keypair, prints the private key once to stdout, and appends the public half to `ops-authorized-keys`, but does **not** write `ops-private.key` (only `brace new` ever does). It also has no idempotency check, so re-running it pollutes the authorized list with orphaned keys whose private half exists nowhere. Missing pieces:
  - `brace ops keypair --save` (or default to saving) — write the new private key to the path in `.brace.local`'s `ops.key` and refuse to overwrite an existing file unless `--force`. Eliminates the manual copy-paste step that's easy to skip and impossible to recover from.
  - `brace ops authorize <pubkey> [--label <name>]` — register an existing public key (e.g. a coworker's). Today this is a hand-edit of the authorized-keys file with no validation.
  - `brace ops whoami` — derive the public key from local `ops-private.key`, check whether it appears in `ops-authorized-keys`, and report the matching label. Diagnoses "do I already have access?" without grep gymnastics. Shape: prints `authorized as <label>` (exit 0), `not authorized` (exit 1), or `no local key` (exit 2).
- [x] **Bug: ops endpoints inherit CSRF default and break for any app that calls `sessions()`.** `Brace.java` registers `POST /ops/auth`, `POST /ops/auth/login-token`, `POST /ops/errors/{id}/resolve`, and `POST /ops/cache/clear` via `router.add(...)` without `.csrf(false)`. `Route` defaults `csrfRequired=true`, and `BraceHandler` enforces CSRF on mutating routes whenever `sessionSecret != null`. The CLI does not (and cannot meaningfully) send a CSRF token on `/ops/auth`, so any host app that configures sessions gets `403 Forbidden` on every CLI command — manifests as "Authentication failed (403): Forbidden" with the ops keys correctly authorized. These endpoints have their own auth (signed payload for `/ops/auth`, bearer token for the others), so CSRF is doubly inappropriate. Fix: mark all four routes `.csrf(false)` at registration. Discovered against wendell where the deployed 0.1.1 server rejected a 0.1.2-SNAPSHOT CLI; the version skew was a red herring — the bug exists identically in 0.1.1 and HEAD.
- [x] `brace init` misdiagnoses 403 as a missing key. On any non-200 from `/ops/auth`, the CLI emits `actions: ["Add to server's ops-authorized-keys: <pubkey>"]` — even when the local `ops-authorized-keys` already lists that exact key (which the local-file check it just printed proves). It should compare the presented public key against the local authorized-keys file and, if already listed, suggest the failure is something other than missing authorization (CSRF-blocked endpoint, version skew, clock skew, server-side authorized-keys drift). Bare minimum: include the server's response body in the diagnostic so an operator can see the actual reason instead of being routed to the wrong fix.
- [x] Ops 403 responses are opaque. `BraceHandler` returns a flat `403 Forbidden` for both CSRF rejection and other framework-level denials, with no machine-readable code or hint. Combined with the fact that `/ops/auth` itself currently fails CSRF (above), CLI consumers cannot tell "your key is wrong" from "framework blocked you before the handler ran." Emit a structured error body — at minimum `{"error": "csrf_required" | "auth_failed" | ...}` — and have `CliAuth` surface the code in its thrown `RuntimeException` so `brace init` and `brace status` can route to the right remediation. As a smaller standalone improvement, include the running brace version in ops responses so the CLI can warn on skew even when the protocol hasn't actually changed.

## Ops Dashboard Polish

- [x] Use a dashboard template/layout (cards, grid, consistent spacing)
- [x] Custom metric graphs — `OpsDashboard.java:229–328` renders sparklines for counters (cyan), gauges (amber), and timers (blue avg ms) using the in-memory minute snapshots from `Stats`.
- [ ] Dedicated latency sparkline — currently latency only shows as a tooltip on the requests-per-minute sparkline (`OpsDashboard.java:181-183`). Add a standalone sparkline for avg/p95 latency over the 60-minute window so latency trends are visible at a glance, not just on hover.
- [x] Improve JFR data formatting (readable method names, better table layout, units)
- [x] Visual polish (typography, color coding, status indicators)

## Documentation

- [ ] Getting started guide — curated walkthrough of routing, forms, auth, database, templates, uploads, ops
- [ ] API reference for all core types
- [ ] Deployment guide (Dokploy + Docker)
- [ ] Security model page — document session encryption, CSRF policy, trusted proxies, upload safety, ops hardening, secret rotation
- [ ] API style guide — canonical patterns for routes, requests, responses, database, storage, controllers
- [ ] Migration guide from Spring Boot
- [ ] Migration guide from Play 1

## Benchmarks (Lower Priority)

- [ ] Re-run runtime benchmarks — `benchmark/RESULTS.md` is invalid for two compounding reasons and should not be cited until re-run on `brace` ≥ 0.1.5 / JDK 25. (1) It was measured on Java 23.0.2, pre-JEP 491. (2) More importantly, it ran with virtual threads *accidentally disabled* — `Brace.start()` passed `Runnable::run` to `setVirtualThreadsExecutor` (fixed in 0.1.5), so every handler ran synchronously on a platform thread. There was no virtual-thread pinning to relieve because there were no virtual threads; the real before/after variable is vthreads off → on, not JEP 491. The re-run is **not guaranteed to look better**:
  - **Plaintext / JSON** (81k req/s, the "1.1–1.2× faster than Spring" headline) may *regress*. `Runnable::run` ran handlers inline on the producer thread — the fastest possible path for non-blocking work. Real virtual threads add per-request allocation/mount overhead for handlers that never block, which can erode that margin.
  - **DB rows** (Single Query, Fortunes, etc.) may improve modestly. `Runnable::run` capped in-flight requests at the `QueuedThreadPool` ceiling (~200 platform threads) even though HikariCP allowed 256 — Little's law on the Single Query row (15,752 × 16.17ms ≈ 254) shows it running at that cap with queueing. Real vthreads lift the ceiling to HikariCP (256) and the DB itself, but the gain is gated by whether Postgres does useful work at 256 vs ~200 connections; won't close the 2–5× Hibernate/batching gaps.
  - The ReadDbHandler before/after table was measured under the same buggy config on both sides, so its *relative* 1.85× is roughly fine but absolute numbers are suspect.
- [ ] Run Spring Boot TFB on same machine for side-by-side comparison
- [ ] Tune Brace connection pool (HikariCP settings) — DB numbers may improve significantly
- [ ] Run Brace TFB with H2 embedded for max-performance baseline
- [ ] Design PetClinic spec for token efficiency benchmark (framework-agnostic prompt)
- [ ] Implement PetClinic in Brace via AI, measuring tokens
- [ ] Implement PetClinic in Spring Boot via AI, measuring tokens
- [ ] Add-feature benchmark: appointment scheduling on both PetClinic implementations
- [ ] Automate token measurement via Claude API script

## Tier 2 — Production Maturity

- [x] Constant-time CSRF token comparison — use `MessageDigest.isEqual()` instead of `String.equals()` in Csrf.validateToken()
- [ ] Upload spooling/streaming — small files in memory, larger files spooled to temp storage, configurable thresholds and limits
- [ ] CSP helpers — `SecurityHeaders.Builder.contentSecurityPolicy(String)` exists as a raw setter (`SecurityHeaders.java:107`), but no structured CSP builder, no per-request nonce generation, no safe defaults preset (`default-src 'self'; script-src 'self' 'nonce-…'`). Adding the builder + a `csp(nonce)` template helper is the missing piece.
- [ ] Static asset caching — `Cache-Control`, `ETag`, `Last-Modified`, optional immutable asset mode
- [x] Asset fingerprinting helper + template tag — `Assets.url("/assets/app.css")` → `/assets/app.css?v=<md5-prefix>`. Hash computed from file contents, cached per `(path, mtime)`, so redeploys with unchanged files don't bust browser/CDN caches. Pairs with static-asset caching above: long `max-age` on the origin, cache invalidation via URL change.
- [ ] WebSocket metrics (active connections, messages sent/received, connections per room)
- [ ] File upload metrics (count, total bytes, by endpoint)

## Tier 3 — Evidence & Adoption

- [ ] Broader benchmark suite — add CRUD app, auth-heavy app, htmx flow to token-efficiency benchmarks
- [x] Migration guides per release — one file per release transition in `docs/migrations/brace-FROM-to-TO.md` (also bundled in dist zip), discoverable via the `Upgrading` section of `BRACE-AGENTS.md`
- [ ] Golden path sample app — one blessed example showing current best practices end-to-end
- [ ] Getting started guide — curated walkthrough of routing, forms, auth, database, templates, uploads, ops (consolidate with docs section below)
- [ ] Publish to Maven Central — JitPack (`com.github.larvalabs:brace:vX.Y.Z`) is the current public dependency path; it requires zero publisher setup but isn't what most Java shops expect. Migrating to Central means claiming `com.larvalabs` namespace on the Central Portal (DNS TXT verify), generating a GPG signing key, publishing sources+javadoc jars, and switching `publish.yml` to deploy to Central instead of (or in addition to) GitHub Packages. Keep GH Packages for SNAPSHOT/Dokploy use. Then dep instructions drop the `<repositories>` block and become just `com.larvalabs:brace:X.Y.Z`.

## Ops Stats Gaps

- [x] JFR-based JVM profiler (heap, CPU, GC pauses, threads, method hot spots, allocation tracking)
- [x] Database query instrumentation (query count/latency per request)
- [x] Cache hit/miss rates and eviction counts
- [x] Mailer failure tracking

## Future Considerations

- [x] Scoped ops tokens — `read` and `control` scopes, enforced per endpoint. Each authorized key has a scope ceiling (`scope:read` marker in `ops-authorized-keys`, default `control`); `/ops/auth` caps the minted token at the ceiling, so a read-only key can never obtain a control token even if it requests one. Read endpoints (`status`/`errors`/`logs`/`routes`/`cache` stats) require `read`; mutating endpoints (`cache/clear`, `errors/{id}/resolve`) require `control`. Tokens carry a `kid` fingerprint for audit attribution. Generate a read-only agent key with `brace ops keypair --read-only`. (Two scopes to start; ordered `OpsScope` enum leaves room for a third `dashboard` tier later.) Unblocks the `brace-oncall` on-call agent plan; see [`docs/2026-05-30-scoped-ops-token-plan.md`](docs/2026-05-30-scoped-ops-token-plan.md). Next: the ops endpoint audit log attaches to the `kid` seam in `OpsHandler.authenticate`.
- [ ] Cron expression support for jobs (currently only `every()` and `daily()`)
- [ ] Precompiled JTE templates for production
- [ ] Multi-database support testing (MySQL, MariaDB)
- [x] Simple async tasks (`Jobs.run(runnable)`, `Jobs.submit(callable)` — non-scheduled, non-durable, virtual thread per task)
- [ ] Make `TestApp` work without `Mailer` dependency (currently always creates a `Mailer`, requiring `jakarta.mail` even for apps that don't use email)
- [ ] SSE (Server-Sent Events) support
- [ ] Consider publishing to Maven Central
