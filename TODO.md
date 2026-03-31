# Brace — Next Steps

## Benchmarks (Priority)

- [ ] Run Spring Boot TFB on same machine for side-by-side comparison
- [ ] Tune Brace connection pool (HikariCP settings) — DB numbers may improve significantly
- [ ] Run Brace TFB with H2 embedded for max-performance baseline
- [ ] Design PetClinic spec for token efficiency benchmark (framework-agnostic prompt)
- [ ] Implement PetClinic in Brace via AI, measuring tokens
- [ ] Implement PetClinic in Spring Boot via AI, measuring tokens
- [ ] Add-feature benchmark: appointment scheduling on both PetClinic implementations
- [ ] Automate token measurement via Claude API script

## Framework Improvements

- [ ] Ops endpoint security — switch from query param to header-based auth (`X-Ops-Key`), research stronger options
- [ ] Cache implementation (~120 lines — ConcurrentHashMap + TTL + tag-based invalidation + `cache.wrap()`)
- [ ] WebSocket support (design is done, not yet implemented)
- [ ] Route grouping (`app.group("/admin", admin -> { ... })`)
- [ ] Static file serving (`app.staticFiles("/assets", "public")`)
- [ ] `brace dev` CLI command with file watcher + fast restart
- [ ] `brace deploy` CLI command with Dokploy API integration
- [ ] Auto-generated CLAUDE.md on build
- [ ] Deploy hooks (app.started, app.error.new, app.error.spike webhooks)
- [ ] Ops control endpoints (POST /ops/config, /ops/cache/clear, /ops/job/run)
- [ ] `db.withSession()` for scoped DB access outside request lifecycle
- [ ] `INSERT ... RETURNING id` for PostgreSQL (currently uses `SELECT MAX(id)` for durable job IDs)

## Documentation

- [ ] Getting started guide
- [ ] API reference for all 15 core types
- [ ] Deployment guide (Dokploy + Docker)
- [ ] Migration guide from Spring Boot
- [ ] Migration guide from Play 1

## Future Considerations

- [ ] Cron expression support for jobs (currently only `every()` and `daily()`)
- [ ] Precompiled JTE templates for production
- [ ] Multi-database support testing (MySQL, MariaDB)
- [ ] Rate limiting middleware
- [ ] File upload handling
- [ ] SSE (Server-Sent Events) support
- [ ] Consider publishing to Maven Central
