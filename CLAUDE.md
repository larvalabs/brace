# Brace Framework — Development Context

## What This Is

Brace is a full-stack Java 21+ web framework. Plain Java, no DI container, no bytecode enhancement, no classpath scanning. Batteries included: HTTP, database, templates, sessions, forms, jobs, mailer, ops dashboard.

## Project Structure

```
src/main/java/io/brace/     # Framework source (~3,700 lines)
src/test/java/io/brace/     # Tests (138 tests)
src/test/resources/          # Test templates, migrations
docs/                        # Design spec, decisions, implementation plans
sample/                      # Sample app demonstrating the API
```

## Architecture

Entry point is `Brace.app()` in `main()`. No classpath scanning — everything wired explicitly.

Request lifecycle: Jetty receives HTTP → BraceHandler matches route → runs before middleware → opens DB session if needed → invokes handler → commits/rollbacks → runs after middleware → writes response → logs structured JSON.

### Core Types

| Type | Purpose |
|---|---|
| `Brace` | App builder, configures and starts Jetty |
| `BraceHandler` | Jetty handler, orchestrates request lifecycle |
| `Request` | HTTP request wrapper (params, headers, body) |
| `Result` | Base response type (status, contentType, body, headers) |
| `View` | Template result (renders JTE) |
| `Json` | JSON result (Jackson) |
| `Redirect` | 302/301 redirect |
| `Database` | Thin wrapper over Hibernate StatelessSession |
| `DatabaseFactory` | Creates SessionFactory, runs Flyway migrations |
| `Session` | HMAC-SHA256 signed cookie session |
| `Form<T>` | Validated form binding result |
| `FormBinder` | Binds request params to Java records with validation |
| `Router` | Route registration and matching |
| `Middleware` | Before/after handlers with path patterns |
| `Invoker` | Inspects method signatures, builds typed invokers at startup |
| `JobScheduler` | In-memory recurring job scheduler |
| `JobPoller` | Adaptive poller for durable job queue |
| `Jobs` | Static API for scheduling durable jobs |
| `Mailer` | Email sending with dev-mode capture |
| `Stats` | Lock-free request stats collection |
| `OpsHandler` | /ops/status, /ops/routes, /ops/dashboard |
| `Log` | Structured JSON logging to stdout |
| `Config` | File + env var config with mode prefixes |
| `Passwords` | bcrypt hash/check |
| `Csrf` | CSRF token generation and validation |
| `TestApp` | In-process test harness |

### Handler Interfaces

Four functional interfaces for route handlers:
- `Handler`: `Result apply(Request)` — no DB, no session
- `DbHandler`: `Result apply(Request, Database)` — with DB
- `SessionHandler`: `Result apply(Request, Session)` — with session
- `FullHandler`: `Result apply(Request, Database, Session)` — both

Register with: `app.get("/path", handler)` or `app.get("/path", (DbHandler) (req, db) -> ...)`

## Building and Testing

```bash
mvn compile          # compile
mvn test             # run all 138 tests
mvn test -Dtest=IntegrationTest   # run specific test class
```

## Key Design Decisions

- **No DI container.** Dependencies passed via constructors (services) or method parameters (request-scoped).
- **Hibernate StatelessSession.** No dirty checking, no persistence context, no lazy loading. Explicit insert/update/delete.
- **HQL queries with `?` positional params.** Framework converts `?` to `?1`, `?2` for Hibernate 7.
- **Per-request transactions.** BraceHandler opens/commits/rollbacks automatically. No `@Transactional`.
- **CSRF auto-validated** on POST/PUT/DELETE. Skipped for `Content-Type: application/json`.
- **Session cookie format:** `base64url(json).base64url(hmac-sha256)`. Tamper-proof, not encrypted.
- **Stats use LongAdder/AtomicLong** — lock-free, zero contention on the hot path.

## File Conventions

- One class per file
- Controllers are plain classes in `controllers/`
- Models are JPA entities with public fields
- Forms are Java records with validation annotations
- Jobs implement `Job` (recurring) or `DurableJob` (persistent)
- Views are `.jte` files in the configured templates directory

## Common Patterns

### Adding a new endpoint
1. Add handler method to controller class
2. Register route in `main()`: `app.get("/path", ctrl::method)`

### Adding a new entity
1. Create JPA entity class with `@Entity`, public fields
2. Create Flyway migration SQL file
3. Add entity class to `DatabaseFactory` constructor in `main()`

### Adding form validation
1. Create a record with validation annotations (`@Required`, `@MinLength`, etc.)
2. In controller: `var form = req.form(MyForm.class)` then check `form.valid()`
3. Entity convention: add `apply(MyForm form)` method for mapping

## Dependencies

Jetty 12, Hibernate 7, H2 (test), HikariCP, Flyway, JTE, Jackson, jBCrypt, Jakarta Mail, JUnit 5.

Single Maven artifact: `io.brace:brace:0.1.0-SNAPSHOT`
