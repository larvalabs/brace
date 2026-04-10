# Brace — Next Steps

## Security Hardening — Phase 1 ✅ COMPLETE (409 tests passing)

- [x] Trusted proxy configuration — explicit CIDR-based proxy trust, prevents IP spoofing
- [x] Session encryption — AES-256-GCM encrypted cookies with PBKDF2 key derivation
- [x] Session cookie policy — SessionOptions with secure defaults (Secure, HttpOnly, SameSite)
- [x] CSRF exemption logic — explicit `.csrf(false)` opt-out, required by default for mutating requests
- [x] Security headers middleware — SecurityHeaders.defaults() with nosniff, frame-options, referrer-policy, permissions-policy
- [x] Secret validation — 32-char minimum, rejects weak patterns, startup validation
- [x] Ops auth uses Jackson — replaced manual JSON parsing with typed OpsAuthRequest record

## Tier 0 — Critical Security & Ops Hardening

- [x] Ops browser login token — CLI requests short-lived/single-use token, opens browser to exchange endpoint that sets httpOnly cookie and redirects to dashboard (eliminates query-param token from ongoing dashboard URLs)
- [ ] Redaction layer — auto-redact sensitive headers/params/keys in logs and ops diagnostics (authorization, cookie, token, secret, password, key patterns)
- [ ] Key rotation — support current + previous keys for sessions and ops auth (allow rotation without forced logout)

## Tier 1 — API Clarity & AI Ergonomics (Additive, Non-Breaking)

- [x] Typed route methods — `app.getDb(path, DbHandler)`, `app.postSession(...)`, `app.putFull(...)` etc. (eliminates cast syntax)
- [x] Source-specific request accessors — `req.pathParam()`, `req.queryParam()`, `req.formParam()`, `req.intPathParam()`, `req.queryInt()` etc. (eliminates ambiguity)
- [x] Unified `Result.*` helpers — `Result.json()`, `Result.view()`, `Result.redirect()`, `Result.notFound()` etc. (one namespace for all responses)
- [x] Constrained DB helpers — `db.findBy(Class, field, value)`, `db.findAllBy()`, `db.countBy()`, `db.existsBy()`, `db.deleteBy()` (covers 80% of queries without string generation)
- [x] `Form.hasErrors()` — clearer boolean logic than `!valid()`
- [x] Safer storage helpers — `storage.safeKey()`, `storage.putGenerated()` (makes safe pattern the easy pattern)
- [ ] Update all examples to canonical style — ensure docs/examples show one dominant pattern per feature
- [x] Support all logging levels — add `Log.debug()`, `Log.info()`, `Log.error()` (currently has warn() and event())
- [x] Binary response support for `Http` client — `Http.get(url).fetchBytes()` → `byte[]` (currently only `fetchString()` / `fetchJson()`)
- [ ] Clean up doc drift — ensure README, AGENTS.md, SECURITY.md are consistent; mark historical design docs with status banners

## Framework Improvements

- [x] Exception tracking — persistent `ops_errors` table, `GET /ops/errors`, `POST /ops/errors/{id}/resolve` ([spec](docs/superpowers/specs/2026-04-07-exception-tracking-design.md))
- [x] Ops endpoint security — `X-Ops-Key` header with query param fallback for dashboard
- [x] Ops token auth — Ed25519 keypair auth, SSH-style authorized keys, short-lived tokens, CLI commands ([spec](docs/superpowers/specs/2026-04-08-ops-token-auth-design.md))
- [x] Cache implementation (~120 lines — ConcurrentHashMap + TTL + tag-based invalidation + `cache.wrap()`)
- [x] HTTP client wrapper (`Http.get()`, `Http.post()`, `.bearer()`, `.bodyJson()`, `.fetchJson()` — over `java.net.http.HttpClient`)
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
- [ ] Dokploy ops skill (not Brace-specific — deploy status, monitoring, restart, rollback, env vars via Dokploy API)
- [x] Auto-generated CLAUDE.md (`app.generateClaudeMd(projectName, path)` — capability index with pointers to AGENTS.md for full API reference)
- [x] App-level custom metrics (`Stats.counter("talks.created")`, `Stats.gauge("queue.depth", supplier)`, `Stats.timer("api.latency", durationMs)` — lock-free LongAdder internals; auto-rendered in ops dashboard with sparklines; exposed in `/ops/status` JSON; in-memory ring buffer)
- [x] S3-compatible storage (`Storage.s3(config)`, `storage.put(key, bytes, contentType)` returns public URL, `storage.delete(key)`, `storage.url(key)` — built-in AWS Sig V4 signing, no SDK; works with S3, R2, MinIO; config: accessKeyId, secretKey, bucket, endpoint, region, publicUrl; integrates with `req.file()` uploads)
- [x] `db.withSession()` for scoped DB access outside request lifecycle
- [x] `INSERT ... RETURNING id` — fixed via JDBC `getGeneratedKeys()` (works with H2 and PostgreSQL)
- [x] `db.queryIn()` for IN clause support (e.g., `db.queryIn(Talk.class, "id", idList)`)
- [ ] Deploy hooks (app.started, app.error.new, app.error.spike webhooks)
- [ ] Custom message from a job run to show on the ops dashboard (e.g., "Retrieved 4 new listings")

## Ops Dashboard Polish

- [x] Use a dashboard template/layout (cards, grid, consistent spacing)
- [ ] Add latency sparkline + custom metric graphs (requires app-level custom metrics)
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
- [ ] CSP helpers — builder API for Content-Security-Policy with nonce support and safe defaults
- [ ] Static asset caching — `Cache-Control`, `ETag`, `Last-Modified`, optional immutable asset mode
- [ ] WebSocket metrics (active connections, messages sent/received, connections per room)
- [ ] File upload metrics (count, total bytes, by endpoint)

## Tier 3 — Evidence & Adoption

- [ ] Broader benchmark suite — add CRUD app, auth-heavy app, htmx flow to token-efficiency benchmarks
- [ ] Migration guides per release — one file per release with "what changed / what to replace / before / after" diffs (e.g., brace-0.1.0-0.2.0.md)
- [ ] Golden path sample app — one blessed example showing current best practices end-to-end
- [ ] Getting started guide — curated walkthrough of routing, forms, auth, database, templates, uploads, ops (consolidate with docs section below)

## Ops Stats Gaps

- [x] JFR-based JVM profiler (heap, CPU, GC pauses, threads, method hot spots, allocation tracking — [spec](docs/superpowers/specs/2026-04-08-jfr-profiler-design.md))
- [x] Database query instrumentation (query count/latency per request)
- [x] Cache hit/miss rates and eviction counts
- [x] Mailer failure tracking

## Future Considerations

- [ ] Scoped ops tokens — separate read-only, dashboard, and control/admin token scopes (reduces blast radius of compromised tokens)
- [ ] Cron expression support for jobs (currently only `every()` and `daily()`)
- [ ] Precompiled JTE templates for production
- [ ] Multi-database support testing (MySQL, MariaDB)
- [ ] Simple async tasks (`Jobs.run(runnable)`, `Jobs.submit(callable)` — non-scheduled, non-durable, managed thread pool)
- [ ] Make `TestApp` work without `Mailer` dependency (currently always creates a `Mailer`, requiring `jakarta.mail` even for apps that don't use email)
- [ ] SSE (Server-Sent Events) support
- [ ] Consider publishing to Maven Central
