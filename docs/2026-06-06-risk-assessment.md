# Brace Risk Assessment Notes

Assessment date: 2026-06-06

Brace is in good shape overall: the core request lifecycle is readable, the public API supports the explicit-wiring thesis, and the test suite covers a broad surface including Postgres-specific integration behavior. The risks below are the main hardening items I would address before expanding the framework further.

> **Update (2026-06-06): all five items addressed in `0.1.7-SNAPSHOT`.** Each section carries a
> **Status** note below. Per-version upgrade notes are in `docs/migrations/brace-0.1.6-to-0.1.7.md`.

## 1. Multiple response headers are not supported

`Result` stores headers as `Map<String, String>`, so repeated headers overwrite previous values. This is most likely to hurt `Set-Cookie`: a handler may set an app cookie, then the framework may write the session cookie and replace it, or vice versa.

**Status: Fixed.** `Result` now keeps `Set-Cookie` in a dedicated list (other headers stay single-value); `header("Set-Cookie", …)` and `cookie(…)` append; `BraceHandler.writeResult` emits each with `add()` rather than `put()` (the write path had to change too, not just `Result`). Covered by `ResultTest` and `HeadersCookiesIntegrationTest`.

Relevant code:

- `src/main/java/com/larvalabs/brace/Result.java`
- `src/main/java/com/larvalabs/brace/BraceHandler.java`

Suggested fix:

- Change response headers to support multiple values per header name, at least for `Set-Cookie`.
- Preserve ergonomic `result.header(name, value)` behavior for normal single-value headers.
- Add an explicit `result.cookie(...)` path that appends rather than overwrites.

Tests to add:

- A route that sets two cookies returns two `Set-Cookie` headers.
- A session-modifying route that also sets an application cookie returns both cookies.
- Existing single-value headers such as `Location` and `Content-Type` remain straightforward to assert.

## 2. Request header lookup is case-sensitive

Headers are copied into a plain map and later accessed with exact names such as `Content-Type`, `Cookie`, `HX-Request`, and `Authorization`. HTTP header names are case-insensitive, so clients using lowercase or mixed-case variants can break content-type checks, session loading, CSRF behavior, htmx detection, ops auth, and trusted proxy handling.

**Status: Fixed.** Request headers are now stored in a case-insensitive map both at ingestion (`BraceHandler`) and defensively in the `Request` constructor (covers `TestApp`/direct construction); `header`/`hasHeader` are case-insensitive. Covered by `RequestTest`. Note on blast radius: Brace serves **HTTP/1.1 only** (`new ServerConnector(server)`, no TLS), so the catastrophic HTTP/2-lowercases-everything path was latent, not active — but it would have detonated the moment an HTTP/2-terminating proxy normalized header casing. Fixing the map removes that landmine regardless of protocol.

Relevant code:

- `src/main/java/com/larvalabs/brace/BraceHandler.java`
- `src/main/java/com/larvalabs/brace/Request.java`
- `src/main/java/com/larvalabs/brace/OpsHandler.java`

Suggested fix:

- Normalize request header keys on ingestion, or use a case-insensitive map.
- Keep original header names only if needed for diagnostics.
- Make `Request.header(name)` and `Request.hasHeader(name)` case-insensitive.

Tests to add:

- `content-type: application/json` is accepted by `req.isJson()` and `req.requireJson(...)`.
- lowercase `cookie` loads sessions correctly.
- lowercase `authorization` works for ops bearer auth.
- lowercase `hx-request` makes `req.isHtmx()` true and sets the expected `Vary` behavior.

## 3. Request bodies are read before route/static matching

`BraceHandler` reads the body before it matches routes or checks static files. This keeps the lifecycle simple, but it means GET requests, static files, 404s, and middleware-only exits still pay body-read cost. It also makes oversized or slow bodies relevant to requests that may never need a body.

**Status: Fixed (scoped).** Route matching now happens before the body read; the body (and multipart parsing) is read only for matched routes, so static files, 404s, and before-middleware short-circuits no longer pay the cost — and an unmatched POST with a large multipart body is no longer parsed into memory before the 404 (a mild DoS amplifier removed). One deliberate behavior change: before-middleware on an unmatched route now sees an empty body. Full laziness (deferring the read until first `req.body()` access on matched routes too) was *not* done — it would couple `Request` to Jetty or require a body-supplier refactor; deferred as a larger change. Covered by `HeadersCookiesIntegrationTest`.

Relevant code:

- `src/main/java/com/larvalabs/brace/BraceHandler.java`

Suggested fix:

- Match the route and static file mappings before reading the body.
- Delay body parsing until a route or middleware actually needs request body/form/file access.
- If laziness is too large a change, at least avoid body reads for static files and methods that normally do not carry bodies.

Tests to add:

- Static file requests do not attempt multipart/body parsing.
- 404 requests with large bodies are rejected or handled according to a deliberate size policy.
- Before middleware that returns early can do so without forcing multipart parsing.

## 4. Positional parameter conversion is too naive for raw SQL/HQL

`Database.convertPositionalParams` rewrites every `?` character to Hibernate-style numbered parameters. This works for simple Brace-style queries, but raw SQL/HQL can contain question marks inside string literals, comments, JSON operators, or dialect-specific syntax.

**Status: Fixed.** `convertPositionalParams` is now a small scanner that skips `?` inside single-quoted string literals (handling `''` escapes), `--` line comments, and `/* */` block comments. A literal `?` operator (e.g. Postgres JSONB `?`/`?|`/`?&`) is escaped as `??`; `db.jdbc(...)` remains the raw escape hatch. This is a correctness fix, not security — parameters were always bound. Covered by `DatabaseTest` (`convert*` tests). The placeholder-vs-JSONB-operator ambiguity is fundamentally undecidable for a bare `?`, so the `??` escape is the chosen resolution rather than a full SQL parser.

Relevant code:

- `src/main/java/com/larvalabs/brace/Database.java`

Suggested fix:

- Either document the limitation clearly and keep the helper intentionally simple, or replace it with a small parser that skips string literals and comments.
- Consider adding an escape hatch for already-numbered parameters or raw JDBC when query text must contain literal `?`.

Tests to add:

- SQL containing a literal `?` in a string does not get rewritten.
- Existing simple `?` placeholder conversion still works.
- Numbered parameters are not double-rewritten if support is added.

## 5. Version and documentation drift

The project has version references that do not currently line up. The POM is `0.1.7-SNAPSHOT`, while README dependency examples reference `v0.1.5`; some local development context also still mentions `0.1.2-SNAPSHOT`.

**Status: Fixed.** README install examples updated to `v0.1.6` (latest release). The agent docs no longer hardcode the version: `CLAUDE.md` is now a one-line `@AGENTS.md` import and `AGENTS.md` references `pom.xml` as the single source of truth (the `mvn package` zip name and artifact line no longer carry a literal version, so they can't drift again). A release-checklist note in `AGENTS.md` covers the one remaining hardcoded spot (the README install example). `BRACE-AGENTS.md` had no stale version string.

Relevant files:

- `pom.xml`
- `README.md`
- `BRACE-AGENTS.md`
- `AGENTS.md`

Suggested fix:

- Add a release checklist item for version-reference updates.
- Prefer a single generated or clearly documented source of truth for current framework version.
- Update README examples whenever the recommended install version changes.

Tests/checks to add:

- A lightweight docs check that flags stale version strings in primary docs.
- A release script step that prints all version references before tagging.

## Priority order

1. Header multi-value support, because cookie loss can cause real user-visible bugs.
2. Case-insensitive request headers, because it affects common clients and several security-adjacent paths.
3. Request body parsing order, because it is a performance and robustness hardening item.
4. Positional parameter conversion, because it is a sharp edge mostly for advanced raw SQL usage.
5. Version/docs drift, because it affects adoption and agent correctness more than runtime behavior.
