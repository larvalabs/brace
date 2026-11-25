# Brace 0.1.11 release notes

> Source of truth for every item (with before/after examples) is
> `docs/migrations/brace-0.1.10-to-0.1.11.md`. Start with its "How to upgrade" section: it gives
> the order to work in and lists the changes that nothing will report.

---

Brace 0.1.11 carries the fixes from two full security reviews (October and November 2026), so it
has more breaking changes than any release so far. Most show up as a compile error, a test
failure or a refusal to start that names what to change; the guide lists the ones that don't.
It also adds server-side analytics, gzip, and database load and health figures on the ops
dashboard and in `brace check`.

## Security

- **Every route declares who may reach it.** Access requirements attach to routes (and groups,
  static mappings and WebSocket upgrades), and `start()` refuses to run while a route has none
  and isn't marked `.publicRoute()`. `requireSession(...)` is gone.
- **Ops hardening.** Ops auth protocol v3 signs the server's origin, so a changed `.brace` URL
  can't collect a token for another server; `opsOrigins(...)` (or `BRACE_OPS_ORIGINS`) is
  required outside dev mode; every `ops-authorized-keys` line states `scope:read` or
  `scope:control`; removing a key revokes its tokens; the dashboard is cookie-only and runs
  under its own strict Content-Security-Policy.
- **Sessions:** the production cookie is `__Host-brace_session` (everyone signs in again once),
  sessions have an absolute lifetime, and previous secrets are validated.
- **CSRF:** the token rendered into pages is masked per render; opt-outs are checked and
  cross-site mutating requests are refused; `csrf-htmx.js` never sends the token to another
  origin.
- **Outbound and inbound data:** `Http` refuses non-public destinations unless `.trusted()`, and
  its timeouts cover the whole buffered exchange; `req.json` refuses `@Entity` classes;
  `req.form()` ignores the query string on mutating methods; `@Min`/`@Max` compare exactly.
- **Templates and responses:** data in `javascript:` URLs, CSS and script bodies is neutralized
  or escaped for its context; HTML and text declare `charset=utf-8`; there is no `Server`
  header; access rejections are never shared-cacheable; the page cache stores fewer, safer pages
  and bypasses requests it can't key.
- **Files:** uploads are served and stored as untrusted (`StaticFiles.UNTRUSTED`), static files
  are served only at their canonical URL, dotfiles return 404, and new
  `UploadedFile.safeFilename()` / `saveTo(dir, name)` / `Result.fileUnder(...)` confine paths.
- **Mail and proxies:** `smtp://` requires STARTTLS (`smtp+plain://` opts out) and addresses
  are single; `req.ip()` reads one chosen forwarding header and is canonical.
- **Redaction:** logs, error records and stack traces in `/ops` are redacted by value shape and
  by a wider set of sensitive names, and the redactor fails closed.
- **CLI and launcher:** the ops token cache moved out of `target/`, secret files are never
  written through a link, and the launcher refuses project pins older than 0.1.11 (set
  `BRACE_ALLOW_OLD_TOOLCHAIN=1` to override) or not shaped like a version.
- **New projects** start with `SecurityHeaders.strict()` (a CSP with no inline script), htmx
  set up with CSRF and no `eval`, and a hardened distroless container running as non-root with
  a health check at `/__brace/health`.
- **Owned lookups:** `db.findOwnedOr404(...)` and a predicate form of `findOr404` put the owner
  check in the query; review every `findOr404(type, id)` on user-owned data.

## New

- **Server-side analytics** (`app.analytics()`): visitors, pageviews, top pages, sources,
  devices and browsers, counted from requests the app already serves. No tracking script, no
  cookie, no IP or user agent stored. Viewed at `/ops/analytics` and with `brace analytics`.
- **Gzip response compression**, on by default (`.compression(false)` to opt out).
- **Database panel on the ops dashboard** and a `db` block in `/ops/status` and `brace status`:
  connection pool use and waits, request query time, and on Postgres the database's load
  (average active sessions by CPU, I/O, lock and other waits), connections, cache hit ratio,
  commit and temp-file rates, I/O operations (16+) and the most sequentially scanned tables.
  It reads only Postgres's core statistics views as the app's own role, so it works on any
  install. `brace check` gains a `db` check that warns on pool waits, lock waits, connections
  near `max_connections` and, once you set `check.db_max_load`, high load.
- `Html.escape`, `SecurityHeaders.strict()`, and `HEAD` requests reaching `GET` routes.

## Ops

- The dashboard and `brace status` show request rates per second on busy apps, and the Brace
  version.
- Failed job runs are logged (`job.error`) and recorded in `/ops/errors` like failed requests;
  JVM profiling snapshots no longer fail on long generated class names.

## Upgrading

Read `docs/migrations/brace-0.1.10-to-0.1.11.md` and follow its "How to upgrade" order:

1. Fix the ops files first: add `scope:control` to key lines with no scope, and set
   `BRACE_OPS_ORIGINS` in each deployment.
2. Raise `<brace.version>` and run `brace self-update` together: 0.1.11 and 0.1.10 CLIs and
   servers can't sign in to each other.
3. Run `brace agents-md`, then `brace compile`, then `brace test`: compile errors, startup
   checks and test failures name most of what changed.
4. Start once the way production starts, then go through the guide's list of changes that are
   silent at runtime (rate limiter keys, form query strings, mail, outbound HTTP, `req.ip()`,
   static file URLs, templates, the page cache, sessions).

Coming from a 0.1.11 release candidate, start at the guide's "Security review, November 2026"
section; the database panel and the `db` check are new since rc.6.
