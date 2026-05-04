# Brace Framework — Development Context

## What This Is

Brace is a full-stack Java web framework. Requires JDK 21+; JDK 25 LTS recommended (JEP 491 removes virtual-thread pinning on `synchronized`, which matters under load with Hibernate/JDBC). Plain Java, no DI container, no bytecode enhancement, no classpath scanning. Batteries included: HTTP, database, templates, sessions, forms, jobs, mailer, ops dashboard.

## Project Structure

```
src/main/java/com/larvalabs/brace/     # Framework source (~4,000 lines)
src/test/java/com/larvalabs/brace/     # Tests (410 tests)
src/test/resources/          # Test templates, migrations
src/assembly/distribution.xml # Assembly descriptor for the brace CLI zip
bin/brace                    # CLI launcher script (shipped in distribution)
tests/cli/                   # Shell-based end-to-end tests for the CLI
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
| `Session` | AES-256-GCM encrypted cookie session |
| `SessionOptions` | Fluent API for session cookie configuration |
| `TrustedProxies` | CIDR-based proxy trust validation |
| `SecurityHeaders` | Security headers middleware with safe defaults |
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
# Framework development (these commands build/test Brace itself)
mvn compile          # compile brace framework
mvn test             # run all 410 tests
mvn package          # build distribution zip (target/brace-0.1.0-SNAPSHOT.zip)

# Using brace as an end user (e.g., building the sample app)
cd sample && brace run
```

## Code Navigation

For symbol-level questions about Java code, prefer the `LSP` tool over `Grep`. The Java LSP (jdtls) is type-aware and returns exact symbols — no false positives from name collisions, comments, or textual matches — which usually means fewer tokens and zero disambiguating reads.

- `LSP goToDefinition` — where a symbol is defined
- `LSP findReferences` — every usage of a symbol
- `LSP goToImplementation` — implementations of an interface or abstract method
- `LSP incomingCalls` / `outgoingCalls` — call hierarchy
- `LSP documentSymbol` — list classes, methods, fields in a file
- `LSP hover` — type info and Javadoc

Use `Grep` for text searches (TODOs, string literals, config values, error messages), non-Java files, and existence checks. Rule of thumb: **symbol-level question → LSP; text-level question → Grep.**

## Key Design Decisions

- **No DI container.** Dependencies passed via constructors (services) or method parameters (request-scoped).
- **Hibernate StatelessSession.** No dirty checking, no persistence context, no lazy loading. Explicit insert/update/delete.
- **HQL queries with `?` positional params.** Framework converts `?` to `?1`, `?2` for Hibernate 7.
- **Per-request transactions.** BraceHandler opens/commits/rollbacks automatically. No `@Transactional`.
- **CSRF required by default** on POST/PUT/DELETE. Explicitly opt out with `.csrf(false)` for bearer-token APIs.
- **Session cookie format:** `base64url(12-byte-nonce || aes-gcm-ciphertext || 16-byte-auth-tag)`. Encrypted and authenticated.
- **Trusted proxies.** IP forwarding headers only respected from configured proxy CIDRs. Prevents IP spoofing.
- **Security headers.** Easy defaults via `app.after(SecurityHeaders.defaults())` for nosniff, frame-options, etc.
- **Secret validation.** Session secrets must be 32+ characters. Warns about weak patterns on startup.
- **Stats use LongAdder/AtomicLong** — lock-free, zero contention on the hot path.
- **htmx for dynamic pages.** Bundled htmx 2.0.4 served from `/__brace/htmx.min.js`. Default pattern: handler returns full page, htmx uses `hx-select` to extract elements client-side. Optimize with `req.isHtmx()` to return partials when needed. `Vary: HX-Request` header set automatically.

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
2. In controller: `var form = req.form(MyForm.class)` then check `form.hasErrors()`
3. Entity convention: add `apply(MyForm form)` method for mapping

### Updating documentation
When changing public API (adding/removing/renaming methods, classes, or handler types), update `BRACE-AGENTS.md` and `README.md` to reflect the change.

### Adding dynamic page updates with htmx
1. Include `<script src="/__brace/htmx.min.js"></script>` in your layout
2. Add `hx-get`, `hx-target`, `hx-select`, `hx-trigger` attributes to HTML elements
3. The handler returns the full page — htmx extracts the element it needs via `hx-select`
4. For optimization: use `req.isHtmx()` to return a `_partial.jte` template directly
5. Partial templates use `_` prefix convention (e.g., `_list.jte`, `_stats.jte`)

## Dependencies

Jetty 12, Hibernate 7, PostgreSQL JDBC (runtime), H2 (test), HikariCP, Flyway, JTE, Jackson, jBCrypt, Jakarta Mail, htmx 2.0.4, JUnit 5.

Single Maven artifact: `com.larvalabs.brace:brace:0.1.0-SNAPSHOT`
