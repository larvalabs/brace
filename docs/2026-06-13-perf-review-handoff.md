# Runtime Performance Review — Session Handoff (2026-06-13)

Pick-up note for the next session continuing the Fable 5 runtime-performance review.
Canonical tracker (read it first): `docs/2026-06-11-runtime-performance-review-todos.md`.
Process/conventions: `docs/reviews/README.md`.

## Where things are

- **Worktree:** `.claude/worktrees/perf-review` — **always `cd` here and confirm `pwd` + branch
  before running `mvn`.** (A `cd /tmp` earlier in a session silently reset the shell cwd to the
  *main* repo on `main`; builds there falsely "passed" against code without the changes.)
- **Branch:** `runtime-performance-review-2026-06`. **HEAD: `605a8d1`.** Working tree clean.
- **Built on** local `main` (includes the unpushed security-review + token-efficiency merges). Nothing
  on this branch is pushed.
- **Suite:** `mvn test` → **834/834** green (H2). `mvn verify` adds the real-Postgres `*IT` tier
  (needs Docker; `tfb-postgres` container on port 5433).

## Done — 31 findings + 1 correctness bug

All **9 Highs**, **all unheld Mediums**, and 4 Lows. This session's commits (newest first):

| Commit | Finding |
|---|---|
| `605a8d1` | M18 — WebSocket per-connection slow-consumer backpressure |
| `15a07a5` | M15 — cache `getOrSet` supplier runs outside the CHM lock |
| `101e7c4` | M14 — unlocked slot read before row lock; **+ `daily()` late-registration bug** |
| `89e9895` | M4 — cache per-record FormBinder reflection |
| `986f6c2` | docs — cumulative wrk checkpoint at `a62f737` |
| `fde59d8` | bench — JMH allocation harness (gap #3) |
| `a62f737` | M6 — render View/Json straight to UTF-8 bytes |
| `9512c46` | M12 — defer View render past commit |

Earlier in the review (before this session): H1–H9, M1, M2, M3, M5, M7, M8, M9, M10, M13, M16, M19,
M20, L3, L5, L10, L12, plus the H3/H4 + job-queue benchmark work.

## What's left

### 1. Three Mediums HELD for a Matt decision (do not implement without one)
- **M11** — Hibernate/Hikari autocommit dance per transaction. **Coupled:** the read-only handler path
  runs queries with no transaction relying on autocommit; flipping `provider_disables_autocommit`
  needs that path given an explicit lifecycle first. Decision needed: take it on (correctness-coupled)
  or defer to 0.1.8.
- **M17** — Shared-backend rate limiter does a full extra DB transaction per request. A per-instance
  micro-batch (flush delta every ~250ms / K hits) cuts DB ops 10–100× but is a **documented
  accuracy/consistency posture change** (fleet count lags by a flush interval). Decision needed:
  accept the looser-by-a-flush-interval semantics?
- **M21** — Cold start is fully serial (framework Flyway → app Flyway → SessionFactory). Build the
  SessionFactory concurrently with migrations; ~30–50% cold-start cut. Decision needed: confirm the
  failure-ordering requirement (a migration failure must still prevent serving) — that's the only
  subtlety.

### 2. Lows — 18 remaining (L3, L5, L10, L12 done)
Pending: **L1, L2, L4, L6, L7, L8, L9, L11, L13–L22.** Mostly mechanical, "smaller model OK":
L1 (per-request Invoker alloc), L2 (delete dead `Invoker.build`), L4 (`Request.ip()` regex hoist +
CidrRange BigInteger cache), L6 (memoize `convertPositionalParams`), L7 (pad `queryIn` list to pow2),
L8 (Session JSON index boxing), L9 (`Jobs.parallel` thread handles), L11 (page-cache thundering herd),
L13 (Storage streaming — docs/defer), L14 (RateLimiter close/leak), L15 (ws rooms `newKeySet`),
L16 (PG message-bus note only), L17 (`ErrorStore.list` push filter to SQL), L18 (Assets stat cache),
L19 (lazy poller/scheduler start), L20 (`minimumIdle` config/docs), L21 (static-file Cache-Control/ETag
+ per-request `readAllBytes`), L22 (cap Jetty `QueuedThreadPool`). L6 is also a JMH-harness candidate.

### 3. Merge gate (per `docs/reviews/README.md` steps 4–5)
- Full `mvn verify` (H2 + Postgres ITs, Docker up).
- `/code-review` pass over the branch diff.
- Write the review **record doc** in `docs/reviews/` (mirror the security + token-efficiency records),
  then merge to local `main`. Expected only conflict historically: the index table in
  `docs/reviews/README.md`.
- Decide the fate of this handoff doc and the benchmark `baselines/` raw outputs at merge time.

## Key context / gotchas

- **Benchmarks need JDK 25 explicitly.** Shell env doesn't persist between tool calls, so a bare `java`
  resolves to JDK 21 → `UnsupportedClassVersionError` on the preview-compiled benchmark classes. Use
  `benchmark/run-jmh.sh` (sets `JAVA_HOME`) or the explicit JDK 25 binary.
- **JMH harness** (gap #3): `benchmark/src/main/java/benchmark/jmh/` — `JmhRunner` (GC profiler always
  on), `RenderAllocBench` (M6), `FormBindBench` (M4). Run: `./benchmark/run-jmh.sh [IncludeRegex]`.
  Add new units as sibling `@Benchmark` classes (runner finds them by package). `gc.alloc.rate.norm`
  is deterministic; the wired-in trick was explicit `annotationProcessorPaths` (JDK 23+ disables
  implicit annotation processing).
- **wrk protocol:** checkpoint events on a QUIET machine only (1-min load < 7 for ~2 min). `mvn install
  -DskipTests` at root first; benchmark rebuilds **must** be `mvn clean package` (shade reuses stale
  output otherwise — verify embedded class checksums on jar swaps). Scripts hard-fail if the port's
  listener PID isn't their own app. Latest cumulative wrk checkpoint is `a62f737` in the tracker
  (Fortunes +41% req/s, 1.23s→35ms p99 vs baseline; plaintext/JSON p99 collapse there is a
  cleaner-machine artifact, **not** a framework win — noted in the doc).
- **Measured wins this session:** M6 — View alloc −58%/−16% (constant ~19.8 KB/render static saving),
  JSON −81%/−73%, render time −37–43%. M4 — 39–56× faster binds, 14–21× less alloc (beats the 10–20×
  estimate). M14 — `every("1s")` × N instances goes from N FOR UPDATE locks/sec to N snapshot reads +
  ≤1 lock/slot.
- **WebSocket test gotchas (M18):** the JDK `HttpClient` WebSocket **auto-drains** the socket, so it
  can't simulate a stalled consumer — use a raw `java.net.Socket` that does the upgrade handshake then
  never reads. The server-side `onClose` is timeout-delayed against a wedged socket, so assert on the
  synchronous `ws-slow-consumer-closed` LogTap event (`LogTap.since(mark)`), not the close callback.
- **Semantics decisions already made (don't relitigate):** M12 commit-then-render (a render failure is
  a 500 with the write committed); M6 is wire-transparent (no guide entry); M4 kept reflective
  Constructor/Method handles (not MethodHandle) to preserve exact exception semantics; M14 preserves
  exactly-once via a re-check under the lock; M15 shares the supplier exception to concurrent waiters
  (unwrapped) and caches nothing on failure; M18 force-closes with `TRY_AGAIN_LATER`, cap default 4 MB
  configurable via `Brace.wsMaxQueuedBytes`.

## Suggested next move

Get Matt's call on the three held Mediums (M11/M17/M21) — M21 is the lowest-risk and highest-visible
(cold start). While waiting, the Lows are safe to batch (start with the trivial/dead-code ones: L2,
L1, L4, L5-style). Then the merge gate.
