# Brace 0.1.7 — draft release notes

> Draft for the GitHub release at tag time. Source of truth for every item (with
> before/after examples) is `docs/migrations/brace-0.1.6-to-0.1.7.md`.

---

Brace 0.1.7 is the **multi-server release**: a Brace app can now scale horizontally
with no new infrastructure — the cache, rate limiter, WebSocket broadcast, and durable
job queue all coordinate through the Postgres database you already have. It also ships
the results of two full-codebase model reviews (security and token-efficiency), a big
developer-experience round, and an ops surface rebuilt for agents and scripts.

151 commits since 0.1.6. Most apps upgrade with **no code changes** — see Upgrading
below for the four narrow breaking cases.

## Scale out on Postgres — no Redis, no message broker

- **Shared cache backend.** `app.cache(CacheBackend.postgres(dbFactory))` makes the
  cache shared, durable, and cross-server-consistent: `delete`/`clearTag` invalidate
  fleet-wide, `incr` is atomic across instances, a page cached on one server is served
  by all. The in-process default is unchanged, and you can run both.
- **Cluster-wide rate limiting.** `RateLimiter` counts across instances on Postgres
  (with automatic fallback to local limiting if the database is unavailable — fail-open
  on infrastructure, not on attackers).
- **WebSocket broadcast across instances.** Rooms and `broadcast` reach sockets
  connected to *other* servers via a Postgres-backed message bus.
- **Durable jobs claim safely under N pollers.** Postgres `SKIP LOCKED` batch claims —
  no double-runs, no lock contention; the poller backs off adaptively when idle.
- The scaling contract — what is shared, what is per-instance, what to configure — is
  documented in one place: "Scaling Brace horizontally" (`docs/scaling.md`).

## Ops, built for agents and scripts

- **Regression tracking.** `/ops/regressions` reports error kinds never seen before
  this deploy; notify via structured log (always on), webhook, or email
  (`LogNotifier` / `WebhookNotifier` / `MailerNotifier`).
- **Scoped read-only keys + audit.** Mark an ops key `scope:read` and it can observe
  production but never mutate it — built for handing to autonomous agents. Every
  authenticated ops request is logged as an `ops.access` event.
- **Auth protocol v2.** Key-bound, nonce'd signatures (v1 still accepted, deprecated);
  `?token=` query-param auth is gone — use `Authorization: Bearer`.
- **Token-efficient payloads.** `/ops/status` is a compact snapshot (`?include=
  timeseries,profiling` for the bulky blocks); `/ops/errors` returns summaries with an
  `at` app-frame pinpoint, full detail per error at `/ops/errors/{id}`. Identical
  shapes with or without a database — no-DB apps get remote stack traces and error
  resolution too.
- **Richer error capture.** Each error records the redacted request headers and the
  DB work done before the throw; secrets and high-entropy tokens are redacted from
  paths, queries, and messages before anything is stored or served.
- **`brace status` exit code now works** (it never fired on unresolved errors before);
  `brace errors <id>` and `brace errors --full` round out the CLI; all `--json` output
  is compact single-line; `brace compile`/`brace test` print condensed, deduplicated
  diagnostics when piped.

## Developer experience

- **Auth guards in one line:** `app.requireSession("/admin/*", "userId", "/login")`,
  or custom logic via session-aware `before(pattern, (req, session) -> ...)` — the
  guard sees the same Session instance as the handler. `requireSession` without
  `.sessions(secret)` fails at startup instead of redirect-looping silently.
- **Flash messages that just work.** Consumed when a page actually renders — a
  redirect-after-POST displays its flash on any handler type, guards can set and read
  pending flash, and nothing eats a message before it's shown.
- **Typed read-only routes:** `app.getRead("/posts", (req, db) -> ...)` — no cast, no
  transaction for query-only GETs.
- **Database helpers:** `db.findOr404` / `db.queryOneOr404`, `db.queryPage` (real
  LIMIT/OFFSET), `db.exists`, and `ORDER BY` in query fragments is now documented,
  pinned behavior.
- **Forms and JSON:** `req.jsonForm(Class)` runs the full validation pipeline on JSON
  bodies; form binding handles enums, `LocalDate`, `Instant`, `BigDecimal`;
  `Json.obj(...)` for one-line ad-hoc response shapes; multi-value
  `req.queryParams(name)` / `req.formParams(name)` for checkbox groups.
- **Testing:** request builder with headers (`app.request("GET", "/api").header(...)`),
  one-line CSRF helpers (`postWithCsrf` et al.), session variants for every verb,
  Jackson-powered assertions — and each `Brace.test()` gets its own isolated H2
  database.
- **Docs that track your pin:** `brace new` writes `BRACE-AGENTS.md` (API reference)
  and `BRACE-OPS.md` (ops runbook); `brace agents-md` refreshes both after a version
  bump. `brace dev` runs with dev-mode niceties (helpful 404s listing near-miss
  routes; route suggestions never leak in production).
- **Postgres packaging fixed:** Brace now bundles the Postgres JDBC driver and
  `flyway-database-postgresql` — delete the manual workaround dependencies.

## Security

0.1.7 includes the full fix set from a model-driven security review of the codebase
(25 findings; record in `docs/reviews/`):

- **Server-enforced session expiry** — a stolen cookie stops working after the
  configured `maxAge` (default 14 days), regardless of the client-side hint.
- **Request bodies are bounded** (`maxUploadSize`, default 10 MB → 413), including
  chunked bodies with no Content-Length.
- **`req.ip()` is spoof-resistant** — rightmost-untrusted walk over forwarding headers,
  only when the peer is a configured trusted proxy.
- **CSRF tokens persist on plain handlers** (no more 403 on the first POST after a
  GET-rendered form), and validation applies to JSON exactly like forms.
- **`Redirect.toLocal`** for user-derived redirect targets (open-redirect safe);
  middleware patterns with interior wildcards are rejected at startup; static files
  carry `nosniff`; responses with a session cookie default to `Cache-Control: private`
  so a misconfigured shared cache can't replay one user's cookie to everyone.
- Plus: `Passwords.dummyCheck` for enumeration-timing mitigation, percent-encoded
  page-cache keys, hardened multipart/cookie/header handling (case-insensitive header
  lookup, multiple `Set-Cookie` values, HQL `?` escaping for JSONB operators).

## Quality

- A real-Postgres **Testcontainers tier** (`mvn verify`) now proves the shipped
  migrations and the concurrency-sensitive paths (upserts, job claims, fan-out)
  against actual Postgres — this is what caught the Flyway packaging gap.
- Two full-codebase model reviews (security, token-efficiency) with public records
  under `docs/reviews/`, each merged through multi-agent code-review gates.

## Upgrading

Read `docs/migrations/brace-0.1.6-to-0.1.7.md` — it opens with a scannable index of
every change. Most apps need no code changes. Four narrow cases are breaking:

1. Middleware patterns with an interior wildcard (`/api/*/admin`) now fail at startup.
2. `?token=` query-param ops auth is removed — switch scripts to `Authorization: Bearer`.
3. Scripts parsing `GET /ops/errors` for stack traces need `?include=detail` (CLI: `--full`).
4. Scripts parsing `GET /ops/status` for `timeseries`/`profiling` need `?include=...`,
   and `brace status` now genuinely exits non-zero on unresolved errors.

After bumping `<brace.version>`, finish with `brace agents-md` to refresh your
project's framework docs.
