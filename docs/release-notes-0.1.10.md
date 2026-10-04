# Brace 0.1.10 release notes

> Source of truth for every item (with before/after examples) is
> `docs/migrations/brace-0.1.9-to-0.1.10.md`.

---

Brace 0.1.10 adds streaming throughout, from uploads to responses to the outbound HTTP client,
and Server-Sent Events. It also lands a correctness review, makes the ops dashboard and GC
figures accurate, and restores several 0.1.7 performance fixes that a merge had silently dropped
before 0.1.7 shipped. Two production apps were upgraded against it before release, one from
0.1.9 and one from 0.1.7 across three guides, and both test suites passed.

## Streaming

- **Server-Sent Events.** `Result.sse(events -> ...)` streams events with `send`, `sendJson`,
  `comment`, `retry` and a default 15s heartbeat. The producer runs after the request
  transaction commits, so a long stream never holds a database connection; disconnects are
  detected and interrupt the producer. Read `Last-Event-ID` with `req.header(...)`.
- **Streaming responses.** `Result.file(path)` (with `Range` support) and `Result.stream(...)`
  stream bodies instead of loading them into memory.
- **Large uploads spill to disk** instead of the heap, and `Storage.put` streams. An
  `UploadedFile` is now only valid during its request.
- **`Http.stream()` and `fetchEvents(...)`** read a response as it arrives and parse SSE, for
  LLM APIs and other streaming endpoints. Streams get an idle timeout (default 30s) and an
  optional total deadline, both raising `Http.StreamTimeoutException`.

## Correctness

- **Route stats are keyed by route pattern, and every response is counted**, including CSRF
  rejections, rate-limited requests, static files and unmatched 404s.
- Static files with percent-encoded names are served; HTML checkboxes bind to `boolean`
  fields; repeated multipart fields keep every value; a trailing slash reaches its route
  (without bypassing a guard registered for the canonical path); the htmx `Vary` header is
  appended; `daily(...)` jobs keep their wall-clock time across DST.
- `app.stop()` releases the `DatabaseFactory` and other resources, and a failed `start()` no
  longer leaves the JVM running.

## Ops

- **Dashboard:** request rate per minute instead of a lifetime total, a Top Routes table, a
  per-minute avg/p95 latency sparkline, and tables that no longer wrap values or long names.
- **GC pause figures count only stop-the-world time.** G1's concurrent cycles used to be
  reported as pauses, overstating pause time several-fold. New fields: `maxPauseMs`,
  `fullCount`, and per-collection `cycleMs` and `full`.
- `brace status` shows the request rate and busiest routes, and takes
  `--include profiling,timeseries`. `brace ops --help` lists the subcommands.
- `TrustedProxies.cloudflare()` ships Cloudflare's egress ranges with optional auto-refresh,
  and Brace warns at startup when `RateLimiter.perIp` runs without trusted proxies.

## New projects and metrics

- **Scaffolded `Dockerfile`:** a JRE 25 image with templates precompiled during
  `mvn package`, a `JAVA_OPTS` entrypoint with a heap cap, and `ops-authorized-keys` copied
  in. Container config reads secrets from the environment.
- **The placeholder session secret older scaffolds shipped is refused outside dev mode.**
- **`Metrics.counter/gauge/timer`** record custom metrics statically, without threading
  `app.stats()` through services.

## Fixes

- `Storage.put` and `Storage.delete` threw on every call on a standard JVM (restricted `Host`
  header). They work now.
- Restored from the 0.1.7 performance review: requests without a body allocate no buffer, the
  session cookie is decrypted at most once per request, request headers aren't copied twice,
  a template that fails to render returns a clean 500, the unfiltered error list is capped,
  and resolving an error returns the full record.
- Oversized multipart uploads return 413, not 500. `Http` transport failures name their cause.

## Upgrading

Read `docs/migrations/brace-0.1.9-to-0.1.10.md`. Six changes are breaking:

1. **Single-column `db.sqlQuery` / `db.hql` return `Object[]` rows.** Read `row[0]`.
2. **`app.stop()` closes the `DatabaseFactory`.** Apps sharing one factory between several
   `Brace` instances (often tests) add `.ownsDatabase(false)`.
3. **`View.of` / `View.render` throw on an odd number of arguments.**
4. **Out-of-range CIDR prefixes in `trustedProxies` throw at startup.**
5. **An `UploadedFile` is only valid during its request.** Copy or save it first.
6. **The scaffold's placeholder session secret is refused outside dev mode.** Set a real
   `SESSION_SECRET` before upgrading.

Coming from 0.1.7, also read the 0.1.7 → 0.1.8 guide's note on tests that enqueue durable jobs.

After bumping `<brace.version>`, finish with `brace agents-md` to refresh your project's
framework docs.
