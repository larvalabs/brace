# Brace 0.1.7 draft release notes

> Draft for the GitHub release at tag time. Source of truth for every item (with
> before/after examples) is `docs/migrations/brace-0.1.6-to-0.1.7.md`.

---

Brace 0.1.7 adds horizontal scaling on Postgres: the cache, rate limiter, WebSocket
broadcast, and durable job queue can all coordinate through the Postgres database an app
already uses, with no added infrastructure. It also includes the results of three
full-codebase model reviews (security, token-efficiency, and runtime performance), a set
of developer-experience additions, and changes to the ops endpoints and CLI.

226 commits since 0.1.6. Most apps upgrade with no code changes. See Upgrading below for
the five breaking cases.

## Horizontal scaling on Postgres

- **Shared cache backend.** `app.cache(CacheBackend.postgres(dbFactory))` makes the
  cache shared across instances: `delete`/`clearTag` invalidate on every instance,
  `incr` is atomic across instances, and a page cached on one server is served by the
  others. The in-process default is unchanged, and both can run side by side.
- **Cross-instance rate limiting.** `RateLimiter` counts across instances on Postgres.
  If the database is unavailable it falls back to per-instance local limiting, so a
  database outage degrades to local limiting rather than blocking all traffic.
- **WebSocket broadcast across instances.** Rooms and `broadcast` reach sockets
  connected to other servers via a Postgres-backed message bus.
- **Durable jobs claim safely under multiple pollers.** Jobs are claimed with Postgres
  `SKIP LOCKED` batch claims, so multiple pollers do not double-run a job or contend on
  locks. The poller backs off adaptively when idle.
- What is shared, what is per-instance, and what to configure is documented in
  `docs/scaling.md` ("Scaling Brace horizontally").

## Ops endpoints and CLI

- **Regression tracking.** `/ops/regressions` reports error kinds not seen before this
  deploy. Notify via structured log (always on), webhook, or email (`LogNotifier` /
  `WebhookNotifier` / `MailerNotifier`).
- **Scoped read-only keys and audit.** Mark an ops key `scope:read` and it can read
  status, errors, and logs but cannot mutate (for example, clear the cache or resolve
  errors). Every authenticated ops request is logged as an `ops.access` event.
- **Auth protocol v2.** Key-bound, nonce'd signatures; v1 is still accepted but
  deprecated. The `?token=` query-param auth is removed; use `Authorization: Bearer`.
- **Smaller ops payloads.** `/ops/status` is now a compact snapshot
  (`?include=timeseries,profiling` adds the larger blocks). `/ops/errors` returns
  summaries with an `at` field naming the first app-code stack frame; full detail per
  error is at `/ops/errors/{id}`. The shapes are the same with or without a database;
  apps with no database serve stack traces and error resolution from in-memory records.
- **More error context.** Each stored error records the redacted request headers and the
  database work done before the throw. Secrets and high-entropy tokens are redacted from
  paths, queries, and messages before anything is stored or served.
- **CLI.** Bug fix: `brace status` now exits non-zero on unresolved errors. It never did
  before, because the server never sent the count the exit code depends on. New:
  `brace errors <id>` and `brace errors --full`. All `--json` output is now compact
  single-line, and `brace compile`/`brace test` print condensed, deduplicated
  diagnostics when piped.

## Developer experience

- **Session auth guards.** `app.requireSession("/admin/*", "userId", "/login")`, or
  custom logic via the session-aware `before(pattern, (req, session) -> ...)` overload.
  The guard sees the same Session instance as the handler. `requireSession` without
  `.sessions(secret)` now fails at startup instead of redirect-looping silently.
- **Flash message bug fixes.** Flash is now consumed when a page actually renders, so a
  redirect-after-POST shows its flash on any handler type, guards can set and read
  pending flash, and a message is no longer consumed before it is shown.
- **Typed read-only routes.** `app.getRead("/posts", (req, db) -> ...)` takes a DB
  handler with no cast and runs query-only GETs without opening a transaction.
- **Database helpers.** `db.findOr404` / `db.queryOneOr404`, `db.queryPage` (real
  LIMIT/OFFSET), `db.exists`, and `ORDER BY` in query fragments is now documented,
  supported behavior.
- **Forms and JSON.** `req.jsonForm(Class)` runs the full validation pipeline on JSON
  bodies; form binding handles enums, `LocalDate`, `Instant`, and `BigDecimal`;
  `Json.obj(...)` builds one-line ad-hoc response shapes; multi-value
  `req.queryParams(name)` / `req.formParams(name)` read checkbox groups.
- **Testing.** Request builder with headers (`app.request("GET", "/api").header(...)`),
  CSRF helpers (`postWithCsrf` and friends), session variants for every verb, and JSON
  assertions via Jackson. Each `Brace.test()` now gets its own isolated H2 database.
- **Generated docs.** `brace new` writes `BRACE-AGENTS.md` (API reference) and
  `BRACE-OPS.md` (ops runbook); `brace agents-md` refreshes both after a version bump.
  `brace dev` runs in dev mode, where a 404 lists near-miss routes (these route
  suggestions are never shown in production).
- **Postgres packaging bug fixed.** Brace now bundles the Postgres JDBC driver and
  `flyway-database-postgresql`, so projects can delete the dependencies they previously
  added by hand to work around the gap.

## Performance

0.1.7 includes the fix set from a model-driven runtime-performance review of the
codebase (record in `docs/reviews/`). The user-visible changes:

- **Templates are precompiled for prod.** `brace compile`/`brace run` precompile JTE
  templates, and in `brace.mode=prod` the engine loads the compiled classes instead of
  compiling on first render. This removes first-render compilation and the runtime
  `jdk.compiler` dependency. `brace dev`/`brace run` also now set `brace.mode`
  (dev/prod) and pass `BRACE_JAVA_OPTS` through to the app JVM.
- **`Mailer.sendAsync()`** sends email on a virtual thread, so the request thread is not
  held during delivery. It is the documented default for sending mail from a request,
  and it never throws; a failed delivery is logged.
- **Static files send caching headers.** Responses carry `Cache-Control`/`ETag`, and a
  conditional `GET` (`If-None-Match`) returns `304`, so clients and proxies skip
  re-fetching unchanged assets.
- **Storage requests time out** (10s connect, 60s request, `s3.timeoutSeconds`).
  Previously a hung S3 call could pin a request thread indefinitely.
- **WebSocket slow-consumer backpressure.** A connection whose send queue grows past a
  cap is force-closed, so one stuck client cannot exhaust memory.
- **Other.** `opsProfiler(false)` opts out of the always-on JFR profiler; stdout logging
  is now asynchronous on a single writer thread; a minimum log level is configurable.
- **Hot-path internals, no API change.** O(1) static-route lookup, `View`/`Json`
  rendered straight to UTF-8 bytes, template render deferred past transaction commit
  (frees the pooled connection sooner), durable-job concurrency bounded to the
  connection pool, `existsBy` probing with `SELECT 1 ... LIMIT 1`, error recording
  coalesced into a ~2s flush so error storms cannot starve the pool, and per-record
  `FormBinder` reflection cached.

One change here is breaking: route-level page caching now ignores query params unless
you declare them with `cache.wrap("10m", ctrl::list).vary("page", "sort")`. This fixes a
cache-keyspace problem (`?utm_source=` or `?x=<random>` previously minted a page-sized
entry each), but a cached route that reads a query param must list that param in
`.vary(...)` or it will serve one value for all of them. See Upgrading. The in-memory
cache backend is also now capped at 10,000 entries (`CacheBackend.inMemory(maxEntries)`).

## Security

0.1.7 includes the fix set from a model-driven security review of the codebase (25
findings; record in `docs/reviews/`):

- **Server-enforced session expiry.** A session cookie stops working after the
  configured `maxAge` (default 14 days), regardless of the client-side hint.
- **Request bodies are bounded** by `maxUploadSize` (default 10 MB; over-size requests
  get a 413), including chunked bodies with no Content-Length.
- **`req.ip()` is spoof-resistant.** It walks forwarding headers rightmost-untrusted,
  and only when the peer is a configured trusted proxy.
- **CSRF token bug fix.** The token now persists when a form is rendered through a plain
  `Handler`, so the first POST after a GET-rendered form no longer 403s. Validation
  applies to JSON the same as to forms.
- **`Redirect.toLocal`** for user-derived redirect targets (open-redirect safe).
  Middleware patterns with interior wildcards are rejected at startup; static files
  carry `nosniff`; responses with a session cookie default to `Cache-Control: private`,
  so a misconfigured shared cache cannot replay one user's cookie to everyone.
- Also: `Passwords.dummyCheck` for enumeration-timing mitigation, percent-encoded
  page-cache keys, and hardened multipart/cookie/header handling (case-insensitive
  header lookup, multiple `Set-Cookie` values, HQL `?` escaping for JSONB operators).

## Quality

- A real-Postgres Testcontainers tier (`mvn verify`) now runs the shipped migrations and
  the concurrency-sensitive paths (upserts, job claims, fan-out) against actual
  Postgres. This is what caught the Flyway packaging bug.
- Three full-codebase model reviews (security, token-efficiency, runtime performance)
  with public records under `docs/reviews/`, each merged through multi-agent code-review
  gates.

## Upgrading

Read `docs/migrations/brace-0.1.6-to-0.1.7.md`; it opens with an index of every change.
Most apps need no code changes. Five cases are breaking:

1. Middleware patterns with an interior wildcard (`/api/*/admin`) now fail at startup.
2. `?token=` query-param ops auth is removed; switch scripts to `Authorization: Bearer`.
3. Scripts parsing `GET /ops/errors` for stack traces need `?include=detail` (CLI: `--full`).
4. Scripts parsing `GET /ops/status` for `timeseries`/`profiling` need `?include=...`,
   and `brace status` now exits non-zero on unresolved errors.
5. Route-level page caching (`cache.wrap(...)`) now ignores query params by default; add
   `.vary("page", "sort", ...)` for any param that changes the rendered page.

After bumping `<brace.version>`, finish with `brace agents-md` to refresh your project's
framework docs.
