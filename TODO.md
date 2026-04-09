# Brace — Next Steps

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
- [x] Auto-generated CLAUDE.md stub (`app.generateClaudeMd(path)` — minimal, since `main()` is self-documenting)
- [x] App-level custom metrics (`Stats.counter("talks.created")`, `Stats.gauge("queue.depth", supplier)`, `Stats.timer("api.latency", durationMs)` — lock-free LongAdder internals; auto-rendered in ops dashboard with sparklines; exposed in `/ops/status` JSON; in-memory ring buffer)
- [x] S3-compatible storage (`Storage.s3(config)`, `storage.put(key, bytes, contentType)` returns public URL, `storage.delete(key)`, `storage.url(key)` — built-in AWS Sig V4 signing, no SDK; works with S3, R2, MinIO; config: accessKeyId, secretKey, bucket, endpoint, region, publicUrl; integrates with `req.file()` uploads)
- [ ] Deploy hooks (app.started, app.error.new, app.error.spike webhooks)
- [x] `db.withSession()` for scoped DB access outside request lifecycle
- [x] `INSERT ... RETURNING id` — fixed via JDBC `getGeneratedKeys()` (works with H2 and PostgreSQL)
- [x] `db.queryIn()` for IN clause support (e.g., `db.queryIn(Talk.class, "id", idList)`)

## Ops Dashboard Polish

- [x] Use a dashboard template/layout (cards, grid, consistent spacing)
- [ ] Add latency sparkline + custom metric graphs (requires app-level custom metrics)
- [x] Improve JFR data formatting (readable method names, better table layout, units)
- [x] Visual polish (typography, color coding, status indicators)

## Documentation

- [ ] Getting started guide
- [ ] API reference for all 15 core types
- [ ] Deployment guide (Dokploy + Docker)
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

## Ops Stats Gaps

- [x] JFR-based JVM profiler (heap, CPU, GC pauses, threads, method hot spots, allocation tracking — [spec](docs/superpowers/specs/2026-04-08-jfr-profiler-design.md))
- [ ] WebSocket metrics (active connections, messages sent/received, connections per room)
- [x] Database query instrumentation (query count/latency per request)
- [x] Cache hit/miss rates and eviction counts
- [ ] File upload metrics (count, total bytes, by endpoint)
- [x] Mailer failure tracking

## Future Considerations

- [ ] Cron expression support for jobs (currently only `every()` and `daily()`)
- [ ] Precompiled JTE templates for production
- [ ] Multi-database support testing (MySQL, MariaDB)
- [x] Rate limiting middleware (`RateLimiter.perIp(100, "1m")`, `RateLimiter.perKey(fn, limit, duration)`)
- [x] File upload handling (`req.file("name")`, `req.files("name")`, multipart parsing, `app.maxUploadSize("50m")`)
- [ ] Simple async tasks (`Jobs.run(runnable)`, `Jobs.submit(callable)` — non-scheduled, non-durable, managed thread pool)
- [ ] SSE (Server-Sent Events) support
- [ ] Consider publishing to Maven Central
