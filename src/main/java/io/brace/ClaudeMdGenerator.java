package io.brace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates CLAUDE.md for a Brace project.
 * Compact capability index with pointers to AGENTS.md for full API details.
 */
public class ClaudeMdGenerator {

    public static String generate(String projectName) {
        return """
# %s

Built with [Brace](https://github.com/matth/brace) — a plain Java 21+ web framework. No DI, no classpath scanning. All routes, services, and dependencies are wired explicitly in `main()`.

## Build & Run

```bash
./brace dev       # compile + run + watch for changes
./brace test      # run all tests
./brace test Name # run specific test class
```

## Brace Capabilities

Full API reference: see `AGENTS.md`. Below is what's available — check the reference when you need method signatures or usage details.

- **Routing** — `app.get("/path", handler)` with four handler types: `Handler` (request only), `DbHandler` (+database), `SessionHandler` (+session), `FullHandler` (+both). Read-only variants: `ReadDbHandler`, `ReadFullHandler`. Path params: `/posts/{id}`. Route groups: `app.group("/prefix", g -> ...)`.
- **Request** — `req.queryParam(name)`, `req.pathParam(name)`, `req.formParam(name)`, `req.header(name)`, `req.body()`, `req.bodyAs(Class)`, `req.form(Class)`, `req.file(name)`, `req.ip()`, `req.isHtmx()`.
- **Responses** — `Result.text()`, `.html()`, `.json()`, `.bytes()`, `.view()`, `.redirect()`, `.error()`, `.notFound()`, `.unauthorized()`, `.forbidden()`, `.badRequest()`, `.created()`, `.noContent()`, `.download()`.
- **Config** — Properties file with `%%mode.` prefixes and `${ENV_VAR}` substitution. `config.get(key)`, `.getInt()`, `.getBool()`.
- **Database** — Hibernate StatelessSession wrapper. `db.find()`, `.insert()`, `.update()`, `.delete()`, `.query()`, `.queryOne()`, `.findBy()`, `.count()`, `.sql()`. Per-request transactions. HQL with `?` positional params.
- **Entities** — JPA `@Entity` with public fields. Flyway migrations in `migrations/`. Register in `DatabaseFactory` constructor.
- **Forms** — Java records with `@Required`, `@MinLength`, `@MaxLength`, `@Email`, etc. `req.form(MyForm.class)` returns `Form<T>` with `.hasErrors()`, `.value()`.
- **Templates** — JTE `.jte` files. `Result.view("path", "key", value)`. Partials use `_` prefix.
- **Sessions** — AES-256-GCM encrypted cookies. `session.set()`, `.get()`, `.getInt()`, `.remove()`, `.flash()`. Configure via `SessionOptions`.
- **CSRF** — Required by default on POST/PUT/DELETE. Opt out: `.csrf(false)` for bearer-token APIs.
- **Cache** — In-memory TTL with tags. `cache.set()`, `.get()`, `.getOrSet()`, `.clearTag()`. Route caching: `cache.wrap("5m", handler)`.
- **Jobs** — Recurring: `app.every("5m", name, job)`, `app.daily("02:00", name, job)`. Durable: `Jobs.schedule(db, job, delay)`.
- **Mailer** — `mail.to().subject().html().send()`. Dev mode captures without sending.
- **Storage** — S3-compatible. `storage.put()`, `.delete()`, `.url()`, `.putGenerated()`.
- **WebSocket** — `app.ws("/path", ctx -> handler)`. Rooms, broadcast, session access.
- **Rate Limiting** — `RateLimiter.perIp(count, window)`, `.perKey(fn, count, window)`.
- **Security** — `app.trustedProxies(cidrs)` for IP forwarding. `SecurityHeaders.defaults()` for nosniff, frame-options, etc.
- **Metrics** — `Stats.counter(name)`, `.gauge(name, fn)`, `.timer(name, ms)`. Appear in ops dashboard.
- **Middleware** — `app.before(req -> ...)` returns null to continue or Result to short-circuit. `app.after((req, result) -> ...)`.
- **htmx** — Bundled 2.0.4 at `/__brace/htmx.min.js`. `req.isHtmx()` for partial responses. `Vary: HX-Request` set automatically.
- **Logging** — `Log.event("name", Map.of(...))` structured JSON to stdout.
- **Passwords** — `Passwords.hash(pw)`, `Passwords.check(pw, hash)` (bcrypt).
- **Testing** — `Brace.test().start(app -> ...)` returns `TestApp`. `.get()`, `.post()`, `.postJson()`, `.withDb()`. H2 in-memory.

## Ops — Debugging & Monitoring

When debugging a running Brace app, use `/ops/status` instead of tailing logs. Setup: `app.ops("ops-authorized-keys")`.

| Endpoint | Returns |
|---|---|
| `GET /ops/status` | Full system snapshot — HTTP stats, JVM heap/GC/threads, errors, jobs, cache, metrics, timeseries |
| `GET /ops/errors` | Tracked errors with stack traces, request details, and DB queries that ran before failure |
| `GET /ops/routes` | All registered routes |
| `GET /ops/dashboard` | HTML dashboard |

**Debugging workflow:**
1. **Errors?** → `errors.recent` has stack trace, route, request details, and queries before failure
2. **Slow?** → `http.slowestRoutes` for latency, `jvm.profiling.hotMethods` for CPU
3. **Memory?** → `jvm.heap` for usage, `jvm.gc` for pauses, `jvm.profiling.topAllocations`
4. **Job failing?** → `jobs.scheduled` shows `lastStatus`, `lastError`, `failCount`
5. **Cache miss rate?** → `cache.hits` vs `cache.misses`
""".formatted(projectName);
    }

    public static void write(String projectName, Path path) {
        try {
            Files.writeString(path, generate(projectName));
        } catch (IOException e) {
            System.err.println("Warning: could not write CLAUDE.md: " + e.getMessage());
        }
    }
}
