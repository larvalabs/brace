# Brace 0.1.8 release notes

> Source of truth for every item (with before/after examples) is
> `docs/migrations/brace-0.1.7-to-0.1.8.md`.

---

Brace 0.1.8 contains the fixes from a second full-codebase security review, which tighten
several framework defaults. It also fixes two ways a durable job could be lost or stuck,
starts durable jobs as soon as they are enqueued, bounds `Mailer` SMTP timeouts, and adds
named routes.

Most apps upgrade with no code changes. Two cases are breaking; see Upgrading below.

## Security

0.1.8 includes the fix set from a model-driven security review of the codebase (17
findings: 2 High, 8 Medium, 7 Low, all fixed;
[review record](https://github.com/larvalabs/brace/blob/v0.1.8/docs/reviews/2026-07-security-opus-5.md)):

- **Session cookies are `Secure` by default.** The attribute is resolved per request: set
  on every non-loopback request and omitted on `http://localhost`, so local development
  and in-process tests are unaffected. An app served over plain HTTP on a real hostname
  opts out with `.secure(false)`.
- **Static files no longer follow symlinks out of the served directory.** Containment is
  checked against the link-resolved path.
- **Security headers apply to every response**, including static files, 404s, 500s, CSRF
  403s and 413s. Previously after-middleware ran only on the handler path.
- **Request bodies are read after before-middleware**, so rate limiters and auth guards
  can reject a request before its body is buffered. Before-middleware that reads
  `req.body()` still works.
- **WebSocket upgrades are `Origin`-checked.** Cross-host upgrades get a 403; declare
  deliberate cross-origin browser clients with `app.wsAllowedOrigins(...)`; an entry with a
  scheme must match scheme, host and port exactly, and a bare host matches any scheme or port.
- **`/ops/*` responses are `no-store`**, and the ops session cookie is scoped to `/ops`.
- **Ops auth protocol v1 is removed**, ending the deprecation window opened in 0.1.7.
- **Escaping and validation:** `Result.download` escapes the filename, `Http.multipart`
  rejects control characters in part names, `Result.cookie` and `SessionOptions` validate
  cookie names, values, path, domain and `SameSite`, and `Storage` rejects `.`/`..`
  segments in object keys.
- **Proxies that rewrite `Host`.** nginx's default `proxy_pass` rewrites `Host` to
  `127.0.0.1`, which makes production requests look like local development.
  `X-Forwarded-Host` from a trusted proxy is now honored for the `Secure` decision and the
  WebSocket `Origin` check, and a one-time warning names the fix
  (`proxy_set_header Host $host;`). See `docs/SECURITY.md`.

## Durable jobs

- **Jobs survive deploys and crashes.** `Brace.start()` registers a shutdown hook, so
  SIGTERM gives running jobs `jobShutdownTimeout` (default 3s) to finish and returns the
  rest to the queue with the attempt refunded. Each poller heartbeats into a new
  `brace_job_workers` table, and a job is recovered from an instance only after that
  instance misses heartbeats for 2 minutes, so a long-running job on a live instance is
  never run twice. Previously a deploy could strand in-flight jobs permanently, along with
  any jobs that depended on them.
- **Optional per-attempt timeout** via `jobTimeout(...)`, off by default.
- **Jobs start on enqueue.** A job scheduled with no delay wakes the poller when the
  enqueuing transaction commits, instead of waiting up to 10 seconds for the next poll.
  Polling continues at 5 seconds (was 10) for delayed jobs, retries, recovered jobs and
  work enqueued on other instances.
- **`db.afterCommit(Runnable)`** is now public API: it runs an action after the current
  transaction commits, or drops it on rollback.

## Mailer

- **SMTP timeouts are bounded**: 10s connect, 30s per read/write, matching `Http`.
  Previously a wedged relay hung the sending thread indefinitely, and five hung sends
  from durable jobs could stall the job system. Override with `.connectTimeout(...)` /
  `.timeout(...)`.

## Routing

- **Named routes.** Register with `.name(Routes.POST)` and build links with
  `Url.to(Routes.POST, id)` in handlers, redirects and JTE templates, so each path is
  written once. Duplicate names fail at startup. `brace new` scaffolds a `Routes` class,
  and `GET /ops/routes` includes each route's name. Purely additive.

## htmx

- **Bundled htmx 2.0.4 → 2.0.10.** Bug-fix releases only; no framework API change.

## Upgrading

Read `docs/migrations/brace-0.1.7-to-0.1.8.md`; it opens with an index of every change.
Two cases are breaking:

1. **Session cookies carry `Secure` on every non-loopback request.** An app served over
   plain HTTP on a real hostname must add `.secure(false)`. Behind nginx, pass the real
   host through with `proxy_set_header Host $host;`.
2. **Ops auth protocol v1 is rejected.** Upgrade the `brace` CLI to 0.1.7 or later.

The durable-job and mailer changes are new defaults and need no code change unless your
platform has a very short kill timeout, your SMTP relay is unusually slow, or you want to
tune the poll interval.

After bumping `<brace.version>`, finish with `brace agents-md` to refresh your project's
framework docs.
