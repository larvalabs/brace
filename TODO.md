# Brace — Next Steps

## Framework Improvements

- [x] Exception tracking — persistent `ops_errors` table, `GET /ops/errors`, `POST /ops/errors/{id}/resolve` ([spec](docs/superpowers/specs/2026-04-07-exception-tracking-design.md))
- [x] Ops endpoint security — `X-Ops-Key` header with query param fallback for dashboard
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
- [ ] Deploy hooks (app.started, app.error.new, app.error.spike webhooks)
- [x] `db.withSession()` for scoped DB access outside request lifecycle
- [x] `INSERT ... RETURNING id` — fixed via JDBC `getGeneratedKeys()` (works with H2 and PostgreSQL)
- [x] `db.queryIn()` for IN clause support (e.g., `db.queryIn(Talk.class, "id", idList)`)

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

## Future Considerations

- [ ] Cron expression support for jobs (currently only `every()` and `daily()`)
- [ ] Precompiled JTE templates for production
- [ ] Multi-database support testing (MySQL, MariaDB)
- [ ] Rate limiting middleware
- [ ] File upload handling
- [ ] SSE (Server-Sent Events) support
- [ ] Consider publishing to Maven Central
