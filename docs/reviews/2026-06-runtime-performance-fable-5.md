# Runtime Performance Review: Fable 5 (June 2026)

## Summary

Of 52 findings (9 High, 21 Medium, 22 Low), all 9 Highs and 19 of 21 Mediums were fixed,
plus 12 of 22 Lows fixed, 4 documented, and 1 won't-fix. The rest (M11, M21, and
L7/L8/L9/L11/L14) are deferred to 0.1.8 with the rationale recorded below. Work was on the
`runtime-performance-review-2026-06` branch, one commit per finding. The substantive
changes:

- Request hot path: the stdout log lock that serialized every request was replaced with an
  async batched writer (H1), the 64 KB per-request body buffer is skipped for bodyless
  reads and sized from Content-Length otherwise (H2), View and Json render straight to
  UTF-8 bytes (M6), the form body is parsed once (M2), FormBinder reflection is cached and
  no longer throws as control flow (M4), and static routing is an O(1) hash lookup (M1).
- Sessions decrypt at most once per request, with lazy CSRF minting and a crypto skip when
  CSRF is off (H5). Session and CSRF share one static `SecureRandom` (M5).
- Durable jobs: bounded `scheduled_jobs` growth with a claim index and retention purge
  (H3), job concurrency capped to the pool (H4), and an unlocked slot read before the row
  lock (M14, which also fixed a `daily()` late-registration bug).
- Ops, cache, and limits under load: per-route stats keyed by route pattern instead of raw
  path (H7), a page-cache key allowlist and capped store (H8), error-storm recording
  coalesced into one flush every 2s (H9), Storage request timeouts (M16), batched rate
  limiting (M17), and WebSocket slow-consumer backpressure (M18).
- Static files gained Cache-Control/ETag and conditional GET (L21), and `docs/scaling.md`
  now documents the connection-budget, Storage-buffering, message-bus, and thread-pool
  limits that were left as design notes (L13/L16/L20/L22).

A merge-gate code-review pass refuted two scary-looking correctness candidates (an
ErrorStore timezone shift and a WsRegistry race, both shown to be non-issues) and fixed
five smaller items (CR#1 through CR#4, CR#6, CR#8), including static-file caching-header
correctness and removing a stray NUL byte from `ErrorStore.java`.

Macro `wrk` benchmarks at the branch tip showed throughput up 11 to 53% across the test
suite and large p99 tail reductions (for example, Fortunes p99 fell from 1.23s to about
33ms, driven mostly by H1 removing a log-lock stall). These are single-run
developer-laptop numbers with about plus or minus 10% variance, treated as checkpoints;
the allocation claims were verified separately with deterministic JMH runs.

Third review under the [periodic model review process](README.md). Full-codebase
runtime-performance review of Brace at 0.1.7-SNAPSHOT, run with Fable 5 across the hot
paths: request lifecycle, logging, sessions/CSRF, templates/rendering, the durable job
queue, the cache, rate limiting, WebSockets, database access, startup, and footprint
under load.

- **Findings doc (canonical tracker):** [`docs/2026-06-11-runtime-performance-review-todos.md`](../2026-06-11-runtime-performance-review-todos.md)
- **Fix branch:** `runtime-performance-review-2026-06` (off local `main`, which already carries the unpushed security + token-efficiency merges)
- **Result:** 52 findings (9 High, 21 Medium, 22 Low). **All 9 Highs and 19 of 21 Mediums fixed; 12 of 22 Lows fixed + 4 documented + 1 won't-fix.** The remaining 2 Mediums (M11, M21) and 5 Lows (L7/L8/L9/L11/L14) are deferred to 0.1.8 with rationale captured below. One commit per finding; full `mvn test` green after each.

Each fix commit is `perf: <ID> …`; documentation-only resolutions are `docs(perf): …`.
Severity, affected files/lines, the fix spec, and a resolution note for every finding live
in the findings doc; this record is the commit map and the disposition summary.

## High: all fixed

| ID | Finding | Commit |
|---|---|---|
| H1 | stdout log lock serialized the request path; async batched log writer | `f65fd12` |
| H2 | 64 KB body buffer per request; skip bodyless reads, size from Content-Length | `d004de2` |
| H3 | `scheduled_jobs` growth unbounded; claim index, NOT EXISTS deps, retention purge, one-scan stats | `40e2b19` |
| H4 | Job concurrency unbounded vs the pool; claim as slots free, one session per job | `515cdcd` |
| H5 | Session decrypted per request; decrypt at most once, lazy CSRF mint, `csrf(false)` skips crypto | `99b2ab7` |
| H6 | Mailer dev-capture leak; dev-only + bounded; `sentCount` = real sends | `53b44cc` |
| H7 | Per-route stats keyed by raw path (unbounded cardinality); key by route pattern | `485071c` |
| H8 | Page-cache key DoS; `vary` allowlist + capped in-memory store | `052b602` |
| H9 | Error-storm amplification; coalesce recording, one flush tx per 2s | `17996df` |

## Medium: 19 fixed, 2 deferred

| ID | Finding | Commit |
|---|---|---|
| M1 | O(1) static-route lookup via hash index; dynamic routes partitioned by method | `107ed2d` |
| M2 | Parse the form body once per request | `9856a0e` |
| M3 | Adopt the handler's case-insensitive header map instead of recopying | `c19bdbf` |
| M4 | Cache per-record FormBinder reflection; stop throwing as control flow | `89e9895` |
| M5 | Share a static `SecureRandom` in Session and Csrf | `a3677a8` |
| M6 | Render View and Json straight to UTF-8 bytes, no String round-trip | `a62f737` |
| M7 | Precompiled JTE in prod; `brace compile/run` build them; eager-compile fallback at startup | `89f0dbf` (+ `213ac8c` container-deploy follow-up) |
| M8 | Length-gate `isSecretShaped` before the UUID regex | `33180b8` |
| M9 | `queryOne`/`findBy` cap the result set with `setMaxResults(1)` | `adeccc5` |
| M10 | `existsBy` probes with `SELECT 1 LIMIT 1` instead of `COUNT(*)` | `b23fc83` |
| **M11** | **Autocommit dance, DEFERRED to 0.1.8** (see below) | (none) |
| M12 | Defer View render past commit, freeing the pooled connection | `9512c46` |
| M13 | `Mailer.sendAsync()` delivers on a virtual thread | `08784ad` |
| M14 | Unlocked slot read before the row lock; also fixed `daily()` late-registration | `101e7c4` |
| M15 | Run cache `getOrSet` supplier outside the ConcurrentHashMap lock | `15a07a5` |
| M16 | Storage requests time out (10s/60s, `s3.timeoutSeconds`) instead of hanging | `b83e107` |
| M17 | Batched best-effort rate limiting + negative cache | `7263714` |
| M18 | Per-connection WebSocket slow-consumer backpressure | `605a8d1` |
| M19 | `opsProfiler(boolean)` opt-out for the JFR profiler; reset sample maps on no-DB apps | `ea76ffa` |
| M20 | `brace dev/run` set `brace.mode`; `BRACE_JAVA_OPTS` passthrough to the app JVM | `ee399e3` |
| **M21** | **Serial cold start, DEFERRED to 0.1.8** (see below) | (none) |

## Low: 12 fixed, 4 documented, 5 deferred, 1 won't-fix

| ID | Finding | Commit / disposition |
|---|---|---|
| L1 | Build the plain-`Handler` invoker once at registration | `f9ee89a` |
| L2 | `Invoker.build` documented off-hot-path, hardened with `setAccessible` | `d23e468` |
| L3 | `NotFoundException` skips stack-trace capture | `3a6839b` |
| L4 | Regex-free `stripPort`; `BigInteger`-free byte-wise CIDR match | `bfbc9a2` |
| L5 | Middleware path match via string compare instead of regex | `6151f68` |
| L6 | Memoize `convertPositionalParams` rewrite (bounded) | `de1550f` |
| L7 | Pad `queryIn` lists to a power of two; **PUNTED to 0.1.8** | (none) |
| L8 | `Session` JSON parser index boxing; **PUNTED to 0.1.8** | (none) |
| L9 | `Jobs.parallel` O(items) thread handles; **PUNTED to 0.1.8** | (none) |
| L10 | Memoize cache TTL parsing | `7510ba2` |
| L11 | Page-cache thundering herd + redundant PG sweeps; **DEFERRED to 0.1.8** (SWR design captured) | (none) |
| L12 | Hoist Storage formatters, cache SigV4 day key, `HexFormat` hex | `8b495d5` |
| L13 | Storage buffers whole objects in heap; **documented** (`docs/scaling.md`) | `405273d` |
| L14 | RateLimiters never unregistered / immortal cleanup vthread; **PUNTED to 0.1.8** | (none) |
| L15 | WebSocket rooms use `ConcurrentHashMap.newKeySet()`, not `CopyOnWriteArraySet` | `98753f8` |
| L16 | PG message bus tx+`pg_notify` per broadcast; **documented** (note only) | `405273d` |
| L17 | Push `ErrorStore` `since`-filter into SQL | `3f9e069` |
| L18 | Hoist Assets base-path normalization to construction | `e38b6b2` |
| L19 | Lazy JobPoller/JobScheduler start; **WON'T FIX** (static-bridge + late-registration risk ≫ a no-op poll every 10s) | (none) |
| L20 | Hikari pool sizing / connection budget; **documented** (`docs/scaling.md`) | `405273d` |
| L21 | Cache-Control/ETag + conditional GET for static files | `c1ec325` |
| L22 | Jetty `QueuedThreadPool` posture under virtual threads; **documented** | `405273d` |

### Correctness follow-up found during the review (not perf)

`JobScheduler.daily()` lacked the late-registration branch `register()` has: a daily job
added after `start()` was tracked but never scheduled. **Fixed with M14** (`101e7c4`),
with a timing regression test.

## Deferred to 0.1.8 (with rationale)

Three items are genuine trade-offs that want real-Postgres measurement (`Profile.java`
vs the `*IT` tier) rather than H2's ~free in-process round-trips, so they were held
rather than guessed at:

- **M11: autocommit dance.** Not a free win: the pgjdbc `setAutoCommit` toggle is mostly
  local flag-flips, and the read-only path's no-begin/commit is a *deliberate* optimization
  (saves a BEGIN/COMMIT round-trip per read). Forcing an explicit read-only tx onto that path
  to flip autocommit could net-regress read-heavy loads. Measure first, then give the read-only
  path an explicit lifecycle before flipping `provider_disables_autocommit=true` + Hikari
  `autoCommit=false`.
- **M21: serial cold start.** Building the SessionFactory concurrently with the Flyway runs
  is a ~30–50% cold-start cut, but instances are long-lived so it amortizes to ~zero, and it
  adds a fail-closed/no-leak concurrency hazard to the one currently dead-simple wiring point.
  Hikari `initializationFailTimeout` lazy-fill is the safer lever to weigh first.
- **L11: page-cache thundering herd.** Bigger than a Low: M15's single-flight lives in
  `InMemoryBackend.getOrCompute` (reached only via `getOrSet`, which the page handler never
  calls *and* which drops tags), so the page path has no single-flight. The 0.1.8 design is
  **stale-while-revalidate**: on TTL expiry the first request fires one async regen and is
  served the still-present stale value with the rest of the herd; the regen atomically swaps in, **plus** single-flight on cold miss (SWR doesn't cover a never-populated key). Crucially,
  explicit invalidation (`clearTag`/`delete`) must bypass grace and hard-remove, so event-driven
  "importantly incorrect" cases never serve stale. Needs a grace state in both cache backends
  and a migration-guide entry. Full design is in the findings doc.

**Punted to 0.1.8 (small, low-value):** L7, L8, L9, L14: micro-optimizations on cold or
rarely-contended paths, not worth the change surface this cycle.

## Code-review pass over the branch (2026-06-13)

A high-effort `/code-review` of the Low-batch diff (7 finder angles + per-candidate
adversarial verification) ran as the merge gate. Two scary correctness candidates were
**verified and refuted**:

- **ErrorStore `Timestamp` vs `timestamptz` timezone shift**: REFUTED. The write path binds
  `Timestamp.from(p.firstSeen)` and the L17 read filter binds `Timestamp.from(since)` through
  the identical Hibernate `setParameter` path with no `Calendar` override, against a
  `TIMESTAMP WITH TIME ZONE` column. The binding is symmetric, so the comparison is correct on
  any JVM zone.
- **WsRegistry `leave()` TOCTOU race**: REFUTED as a finding against this diff. Real but
  pre-existing and identical before/after; L15 changed only `join()`'s set type, and the
  two-arg `remove(room, members)` succeeds by reflexive identity regardless of `Set` impl.

Fixes applied on the branch (2 commits):

| ID | Finding | Commit |
|---|---|---|
| CR#6 | A raw NUL byte in `ErrorStore.java` (a `'\u0000'` char literal written as an actual NUL) made git and grep treat the file as binary; replaced with the `'\u0000'` escape (identical char value, zero behavior change) | `4d474a2` |
| CR#1 | `serveStaticFile` trusted any `?v=` param's *presence* for 1-year `immutable` caching; now verified against `Assets.currentVersion(path)`; a stale/hand-rolled `?v=` falls back to revalidate-always | `190e016` |
| CR#2 | Bundled `htmx.min.js` got no caching headers; now ETag + revalidate Cache-Control + 304 (not `immutable`; a brace upgrade changes the bytes at that fixed URL) | `190e016` |
| CR#3 | `serveStaticFile` did 4 `File` stat syscalls; folded into one `Files.readAttributes` | `190e016` |
| CR#4 | Dead null-invoker fallback in the request path + unused null-producing `Route` constructor removed; L1's "every Route has a non-null invoker" now holds by construction | `190e016` |
| CR#8 | `isNotModified` unused trimmed-copy var | `190e016` |

Confirmed but **not fixed** (low severity, acknowledged):

- **CR#5**: `serveStaticFile` re-implements Assets's base-path normalization + traversal
  containment per request; a future shared-helper cleanup (the two copies of security-sensitive
  traversal logic must be hardened in lockstep).
- **CR#7**: the `convertPositionalParams` param-cache bound (`size() < MAX` then `putIfAbsent`)
  is weakly consistent and the cache is unobservable (private static, no eviction, invisible to
  ops). Acceptable best-effort for a bounded literal set.
- **CR#9**: 304 responses carry `Content-Type: text/plain` via `writeResult`; Jetty suppresses
  the body, so a compliance nit not a break.
- **CR#10**: the mtime+length ETag yields a false 304 on a same-second, same-byte-length edit.
  Matches nginx's default ETag behavior; extremely narrow.

## Validation

- `mvn test` (H2 suite) run before every commit; **846 tests green** at branch tip (post
  code-review fixes), up from the opening suite as fixes added targeted coverage.
- `mvn verify` (H2 suite + real-Postgres Testcontainers ITs: `ErrorStorePostgresIT`,
  `RateLimiterPostgresIT`, `CountersPostgresIT`, `MultiInstanceSchedulerPostgresIT`,
  `WebSocketFanoutPostgresIT`, `PostgresCacheBackendIT`, `DurableJobConcurrencyPostgresIT`,
  and others, **32 IT tests**): **BUILD SUCCESS**, run twice (before and after the
  code-review fixes).

## Benchmarks

Macro throughput/latency via `wrk` (8t/256c/15s + warmups) on a quiet machine against a
TFB-schema Postgres, plus JMH allocation micro-benchmarks for the allocation fixes. Final
merge-gate checkpoint at branch tip (`d89cf90`) vs the pre-review baseline (`ea76ffa`):

| Test | Req/sec | p99 |
|---|---|---|
| Plaintext | +13% | 27.9ms → 4.7ms (−83%) |
| JSON | +11% | 45.8ms → 4.8ms (−89%) |
| Single Query | +21% | 41.4ms → 17.0ms (−59%) |
| Multiple Queries (20) | +46% | 464ms → 214ms (−54%) |
| Fortunes | +53% | 1.23s → 32.9ms (−97%) |
| Updates (20) | +35% | 633ms → 286ms (−55%) |

H1 alone removed the Fortunes tail-stall (1.23s p99 → ~51ms, 51 socket timeouts → 0). JMH
confirmed the allocation fixes: M6 cut View alloc −58% and JSON alloc −81% (and render *time*
−37%/−43%, more than predicted); M4 cut FormBinder bind time 39–56× and alloc −93–95%. Raw
outputs and per-checkpoint detail are in the findings doc's Benchmarks section and
`benchmark/baselines/`. Single-run macro numbers on a developer laptop carry ±10% variance,
treated as checkpoints, not gates, with allocation claims verified via deterministic JMH
`gc.alloc.rate.norm` instead.

## User-visible changes

User-visible changes are documented with before/after examples in
[`docs/migrations/brace-0.1.6-to-0.1.7.md`](../migrations/brace-0.1.6-to-0.1.7.md): H6
(`sentCount` now counts successful sends only), H8 (`.vary()` allowlist; ignore-all-params
default), H9 (error-record coalescing semantics), M7 (precompiled templates / `brace.mode`),
M13 (`sendAsync` virtual-thread delivery), M17 (rate-limiter accuracy posture +
`rateLimitBatchDivisor`/`sharedRateLimiting` knobs), M18 (`wsMaxQueuedBytes`), and M20
(dormant `%dev.` config keys now apply under `brace dev`). `docs/scaling.md` gained the
connection-budget / Storage-buffering / message-bus / thread-pool limits (L13/L16/L20/L22).

## Follow-ups surfaced during the work (not in the original findings)

1. **M11 / M21 / L11 deferred to 0.1.8**: see the rationale above; each needs real-PG
   measurement before committing to the trade-off. L11 carries a full stale-while-revalidate
   design spec in the findings doc.
2. **L7/L8/L9/L14 punted to 0.1.8**: small, low-value micro-optimizations.
3. **CR#5 (traversal-logic duplication)**: `serveStaticFile` and `Assets.resolve` should
   share one base-path-resolution + containment helper; today they're two copies of
   security-sensitive logic.
4. **CR#7 (param-cache observability)**: if a standard bounded-cache abstraction is ever
   introduced, fold `Database`'s param cache, `FormBinder`'s reflection cache, and
   `Cache`'s TTL cache onto it so eviction/metrics live in one place.
5. **Benchmark harness hardening done mid-review**: `run-brace.sh`/`run-session.sh`/
   `run-jobs-mixed.sh` now hard-fail unless the port's listener PID is their own app, after a
   stray dev server on 8080 silently measured 404s; the JMH module needs explicit
   `annotationProcessorPaths` (JDK 23+ disables implicit annotation processing) and JDK 25 on
   PATH (preview class version).
