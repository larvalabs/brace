# Brace Framework — Development Context

## What This Is

Brace is a full-stack Java web framework. Requires JDK 21+; JDK 25 LTS recommended (JEP 491 removes virtual-thread pinning on `synchronized`, which matters under load with Hibernate/JDBC). Plain Java, no DI container, no bytecode enhancement, no classpath scanning. Batteries included: HTTP, database, templates, sessions, forms, jobs, mailer, ops dashboard.

## Project Structure

```
src/main/java/com/larvalabs/brace/     # Framework source (~15k lines including the CLI)
src/test/java/com/larvalabs/brace/     # Tests (run with `mvn test`)
src/test/resources/          # Test templates, migrations
src/assembly/distribution.xml # Assembly descriptor for the brace CLI zip
bin/brace                    # CLI launcher script (shipped in distribution)
tests/cli/                   # Shell-based end-to-end tests for the CLI
docs/                        # Design spec, decisions, implementation plans
sample/                      # Minimal smoke app (not the canonical API reference — that's BRACE-AGENTS.md)
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
| `Cache` | Cache facade (stats, TTL, serialization, page cache) over a `CacheBackend` |
| `CacheBackend` | Storage SPI behind `Cache` — in-process default or shared Postgres |
| `Storage` | S3-compatible object storage client (AWS Sig V4, no SDK) |
| `Http` | Fluent outbound HTTP client over `java.net.http` |
| `RateLimiter` | Per-IP / per-key rate-limiting middleware |
| `Assets` | Asset URL fingerprinting for cache busting |
| `Url` | URL generation from route patterns (`Url.to("/users/{id}", 42)`) |
| `WsContext` | WebSocket session wrapper (send, rooms, broadcast) |
| `UploadedFile` | Multipart upload (filename, content type, bytes) |
| `Notifier` | Regression notification hook — `LogNotifier`, `WebhookNotifier`, `MailerNotifier` |
| `RegressionTracker` | Tracks new error kinds per deploy, backs `/ops/regressions` |
| `ErrorStore` | Persists exception data to the `ops_errors` table |
| `OpsAudit` | Logs authenticated ops-endpoint access as `ops.access` events |
| `OpsKeys` | Ed25519 keygen, signing, verification, authorized-keys parsing |
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
mvn test             # run all tests (H2, fast)
mvn package          # build distribution zip (target/brace-<version>.zip; version from pom.xml)

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
- **HQL queries with `?` positional params.** Framework converts `?` to `?1`, `?2` for Hibernate 7. The converter skips `?` inside single-quoted string literals and SQL comments; a literal `?` elsewhere (e.g. a Postgres JSONB `?`/`?|`/`?&` operator) is escaped as `??`. For fully hand-written SQL, `db.jdbc(...)` is the raw escape hatch.
- **Per-request transactions.** BraceHandler opens/commits/rollbacks automatically. No `@Transactional`.
- **Framework migrations are immutable.** Files under `src/main/resources/brace/db/migration{,_pg}` ship in the jar and are tracked in their own `flyway_brace_history` table; once released, their bytes must never change. Editing one breaks Flyway checksum validation on every deployment that already applied it. To change behavior, add a new `V*` migration — never edit an old one. `FrameworkMigrationsFrozenTest` enforces this against `src/test/resources/framework-migrations.lock` (add the printed `name=sha256` line when you add a migration). We deliberately do **not** auto-`repair()` the framework history at runtime — preventing the edit is the fix.
- **CSRF required by default** on POST/PUT/DELETE/PATCH. Explicitly opt out with `.csrf(false)` for bearer-token APIs. Content-Type does not affect CSRF enforcement — JSON requests are validated like any other mutating request.
- **Session cookie format:** `base64url(12-byte-nonce || aes-gcm-ciphertext || 16-byte-auth-tag)`. Encrypted and authenticated.
- **Case-insensitive request headers.** Header names are matched case-insensitively (HTTP names are case-insensitive and arrive lowercased over HTTP/2), so `req.header("content-type")` and `req.header("Content-Type")` are equivalent. Brace itself serves HTTP/1.1 only — TLS and HTTP/2 are expected to be terminated by a reverse proxy (see `docs/SECURITY.md`).
- **Multi-value `Set-Cookie`.** `Result` keeps `Set-Cookie` in a separate list (not the single-value header map), so a response can carry several cookies — e.g. an application cookie set by a handler plus the framework session cookie — without one clobbering the other. Use `result.cookie(...)` (repeatable) or `result.header("Set-Cookie", ...)`.
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
3. For JSON responses: return records or DTOs, never entities (see "JPA Entities and JSON Responses" in `docs/SECURITY.md`)

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

### Periodic model reviews
Brace gets a full-codebase review in each of three categories — **Security**, **Token
Efficiency**, **Runtime Performance** — whenever a notably more capable model becomes
available. Process, conventions (findings doc, one commit per finding, merge gates), and
the index of completed reviews live in `docs/reviews/README.md`. If you're asked to run
or resume one of these reviews, read that file first.

### Migration guides (per version step)
`docs/migrations/brace-FROM-to-TO.md` is the upgrade path agents and humans follow when bumping `<brace.version>` (see the "Upgrading" section of `BRACE-AGENTS.md`). One guide per released step, named for the version boundary (e.g. `brace-0.1.6-to-0.1.7.md`).

- **While developing toward the next release:** keep the guide for the in-progress step (the one ending at the current `-SNAPSHOT` version in `pom.xml`) up to date as you go. Any user-visible change — a breaking change *or* a notable new/optional capability — gets an entry there, with before/after examples. Create the file the first time a release needs one; don't wait for tag time.
- **A guide is required even when there are no breaking changes.** State that explicitly ("This release has no breaking changes") so a skipped guide is never ambiguous with a missing one — agents are told to read every guide between two versions in order, so a gap reads as "lost," not "nothing changed."
- **Don't rename or backfill silently.** If you find a missing guide for an already-released step, surface it rather than reconstructing history as part of an unrelated change.
- **The upgrade flow ends with a docs refresh:** after bumping `<brace.version>`, projects run `brace agents-md` to rewrite their `BRACE-AGENTS.md` from the new version's jar — guides can assume this step and don't need to repeat it.

### Release checklist (version references)
`pom.xml` `<version>` is the single source of truth for the framework version. The one place a concrete version must still be hardcoded for users to copy is the install example in `README.md` (Maven/Gradle dependency + the "Replace `vX.Y.Z`" line). When cutting a release, update those `README.md` examples to the new tag. Don't reintroduce hardcoded versions into this file or `BRACE-AGENTS.md` — reference `pom.xml`.

Also sweep `README.md`'s API tour sections (Controllers through Configuration, roughly lines 193–486) against `BRACE-AGENTS.md` for drift. They duplicate deliberately — the README is the GitHub landing page and the tour has adoption value — but the README copy isn't session-loaded by agents, so it rots independently and only this checklist catches it.

### Adding dynamic page updates with htmx
1. Include `<script src="/__brace/htmx.min.js"></script>` in your layout
2. Add `hx-get`, `hx-target`, `hx-select`, `hx-trigger` attributes to HTML elements
3. The handler returns the full page — htmx extracts the element it needs via `hx-select`
4. For optimization: use `req.isHtmx()` to return a `_partial.jte` template directly
5. Partial templates use `_` prefix convention (e.g., `_list.jte`, `_stats.jte`)

## Dependencies

Jetty 12, Hibernate 7, PostgreSQL JDBC (runtime), H2 (test), HikariCP, Flyway, JTE, Jackson, jBCrypt, Jakarta Mail, htmx 2.0.4, JUnit 5.

Single Maven artifact: `com.larvalabs.brace:brace` — the current version lives in `pom.xml` (`<version>`), the single source of truth. Don't hardcode the framework version in this file; reference `pom.xml` instead.
