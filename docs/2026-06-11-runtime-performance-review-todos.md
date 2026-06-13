# Runtime Performance Review — 2026-06-11 (Fable 5)

Full-codebase runtime-performance review per `docs/reviews/README.md`. Branch:
`runtime-performance-review-2026-06`. One commit per finding, `perf: <ID> <summary>`,
each commit ticks its checkbox here and passes `mvn test`.

Dimensions swept: HTTP request hot path; database & query path; sessions/crypto/forms/
rendering; background work & observability; startup & memory footprint; extended
subsystems (cache, storage, http client, rate limiter, websockets). All High findings
verified directly against source before listing.

**Measurement protocol** (see "Benchmarks" section at the bottom): record a baseline
with the existing `benchmark/` wrk suite before the first fix lands; re-run after each
group of related fixes; JMH micro-benchmarks for allocation-sensitive fixes.

---

## High

- [x] **H1 — Per-request logging serializes through the `System.out` lock, flushed per line**
  - Severity: High. Files: `Log.java:118-130` (`println`), call sites `BraceHandler.java:390,405,427`; `LogTap.java:54`.
  - Every request builds a `LinkedHashMap`, calls `Instant.now().toString()`, deep-copies via `Redactor.redact`, copies *again* in `LogTap.append` (the redacted map is already a fresh private instance), Jackson-serializes, then `System.out.println` — a synchronized, autoflushing `PrintStream`: one global monitor + a write syscall per request. On JDK 21–24 a virtual thread blocked in that synchronized write pins its carrier (AGENTS.md JEP 491 note). At a few thousand rps this is plausibly the framework's top contention point. There is also no level filter: `Log.debug` always formats and prints.
  - Fix: single writer thread draining a bounded queue (drop-oldest), or at minimum a dedicated `BufferedOutputStream` (non-autoflush, size/interval flush). Drop the redundant copy in `LogTap.append` for the `Log.println` path. Add a minimum-level check before building the entry map.
  - Measure: wrk plaintext/json before/after; expect this to move the throughput ceiling, not just shave µs.
  - Model: frontier (concurrency + shutdown/flush semantics).

- [x] **H2 — ~72KB allocated per matched request to read the body, including bodyless GETs**
  - Severity: High. Files: `BraceHandler.java:171-204` (body read for every matched route), `:629-646` (`readBoundedBody`).
  - `readBoundedBody` allocates `new ByteArrayOutputStream((int) Math.min(cap, 64 * 1024))`; with the default 10MB `maxUploadSize` that's a 64KB backing array, plus an 8KB read buffer, per matched request — there is no method or Content-Length gate, so every GET pays it. ~700MB/s of pure garbage at 10k rps.
  - Fix: skip the body read entirely for GET/HEAD/OPTIONS and when `Content-Length: 0`/absent with no `Transfer-Encoding`; when Content-Length is present, size the BAOS from it (clamped); default initial size 1–8KB otherwise.
  - Measure: JMH `gc.alloc.rate.norm` on the handler path, or JFR allocation profile under wrk.
  - Model: smaller model OK (mechanical, but add tests for chunked bodies + 413 paths).

- [x] **H3 — `scheduled_jobs` grows forever; every poll walks the dead-row prefix**
  - Severity: High. Files: `JobPoller.java:111-146` (Postgres claim), `:149-170` (H2), `:269-280` (stats counts); `V1__brace_scheduled_jobs.sql:23` (only index: `run_at`).
  - Nothing ever deletes completed/failed rows. The claim query (`run_at <= now AND completed_at IS NULL AND failed_at IS NULL AND started_at IS NULL … ORDER BY run_at LIMIT 50`) walks `idx_scheduled_jobs_run_at` from the oldest rows, skipping an ever-growing prefix of finished jobs — every 10s, forever. The dependency subquery (`depends_on_id IN (SELECT id … WHERE completed_at IS NOT NULL)`) hashes *all* completed jobs per poll. `getDurableJobStats` runs 4 separate `COUNT(*)` scans of the unbounded table per ops-dashboard render.
  - Fix: (a) new framework migration (frozen-migrations rule: new `V*` file + lock entry) adding a Postgres partial index `ON scheduled_jobs(run_at) WHERE completed_at IS NULL AND failed_at IS NULL AND started_at IS NULL` (plain composite fallback for H2); (b) rewrite the dependency check as `NOT EXISTS (SELECT 1 FROM scheduled_jobs d WHERE d.id = depends_on_id AND d.completed_at IS NULL)`; (c) retention: framework-recurring purge of completed/failed rows older than N days (configurable, default ~7); (d) collapse the 4 stats counts into one grouped query.
  - Model: frontier (migration immutability rules, multi-instance semantics, H2/PG divergence).
  - **Resolved as:** (a) `migration_pg/V15__brace_scheduled_jobs_claim_index.sql` (partial index, + lock entry); deviated from the spec's "plain composite fallback for H2" — a base-dir migration would also run on Postgres, paying a redundant always-maintained index there, and H2 is the test tier where the retention purge bounds the table anyway. (b) `NOT EXISTS` in both claim paths (outer select aliased `j` so the correlated `j.depends_on_id` can't mis-bind to `d`); the `depends_on_id` FK guarantees the parent row exists, so this is a pure perf rewrite — no semantics change. (c) `JobPoller.purgeFinishedJobs(db, cutoff)` (public; skips rows still referenced by any child — the FK would reject those deletes — so a finished parent purges one pass after its children) + daily `brace-jobs-prune` job registered before `jobScheduler.start` (daily() has no late-registration path) + `app.jobRetention(days)` knob, default 7, `0` disables; migration-guide + BRACE-AGENTS.md + README entries. (d) one `SUM(CASE …)` scan in `getDurableJobStats`.

- [x] **H4 — Job bursts: up to 50 concurrent jobs vs a 10-connection pool, join-all head-of-line blocking**
  - Severity: High. Files: `JobPoller.java:83-100` (spawn + join-all), `:222-266` (2–3 sessions per job); `DatabaseFactory.java:29` (default pool 10), `:166-167` (`minimumIdle` = max).
  - A full batch spawns 50 virtual threads that each call `factory.openSession()` against the same 10-connection Hikari pool the web handlers use — jobs and requests queue behind each other (pool `connectionTimeout` 30s → latency spikes, then 500s). `pollAndExecute` joins **all** threads before re-polling, so one slow job stalls the whole queue for its duration. Each job also opens a *second* session just to mark completion (third on the failure path).
  - Fix: `Semaphore(maxConcurrent)` sized relative to pool (e.g. `poolSize/2`), poll as permits free instead of join-all; reuse the execution session for the completion/failure mark (new transaction on the same session). Consider claim-batch size proportional to pool.
  - Model: frontier (queue semantics, multi-instance interplay with SKIP LOCKED).
  - **Resolved as:** `Semaphore(max(1, poolSize/2))` in `JobPoller` (new `DatabaseFactory.poolSize()` package accessor). New `dispatch()` blocks until ≥1 permit is free, grabs as many free permits as available (≤ maxConcurrent), and claims `LIMIT <permits>` — rows are only flipped to `started_at` when a slot can actually run them, which keeps over-claimed rows visible to other instances (SKIP LOCKED interplay). The poll loop uses `dispatch()` (non-joining) so a slow job holds one permit, not the queue; `pollAndExecute(factory)` keeps join-the-batch semantics for deterministic tests/ITs and is documented as such. `runJobBody` reuses one session for execute + terminal mark (was 2, 3 on failure), each in its own transaction. Side effect (improvement): a failed *completion mark* no longer falls into the failure-mark path that would `rollbackTransaction()` after a successful commit and mislabel a succeeded job as failed. Behavior change recorded in the migration guide: peak job parallelism drops 50 → poolSize/2.

- [x] **H5 — Session cookie decrypted up to twice per request; CSRF token block runs for every matched route**
  - Severity: High. Files: `BraceHandler.java:264-287` (validation — correctly gated on `csrfRequired()`), `:298-314` (ensure-token — **not** gated), `:365-377` (csrf-only cookie write); `Session.java:200,244` (`new SecureRandom()` / `Cipher.getInstance` per op); `Csrf.java:13`.
  - When `sessionSecret` is set, the ensure-token block at `:299` runs for **every matched route** — including `.csrf(false)` bearer-token APIs and JSON handlers that never render the hidden field: full base64 + AES-GCM decrypt + JSON parse per request, nullifying the `needsSession()` gate. Mutating requests on csrf-required routes whose handler takes no `Session` decrypt the same cookie **twice** (`:275` then `:303`). Cookieless clients (API clients, health checks, bots) never return the cookie, so `ensureToken` mints a fresh token every request → `isModified()` → GCM-encrypt + `Set-Cookie` on every response, forever.
  - Fix: decrypt once — reuse the `:275` `csrfSession` in the ensure block; gate the ensure-token/`View.setCsrfField` work on `match.route().csrfRequired()`, or mint lazily only when a `View` render actually consumes `csrfField`. Decide deliberately what cookieless clients should get (probably: no Set-Cookie unless the route renders a form).
  - Model: frontier (CSRF invariants — M5c regression risk; the comment block at `:289-297` documents the orphaned-token failure mode to preserve).

- [x] **H6 — Mailer retains every sent email forever, in production too**
  - Severity: High. File: `Mailer.java:11,34` (capture before the `smtpUrl != null` branch), `:28` (`sentCount` = `captured.size()`).
  - `send()` unconditionally appends the full email (HTML + text bodies) to an unbounded `synchronizedList` even when really sending via SMTP. 100k emails × 20KB ≈ 2GB leaked. The /ops counter is structurally coupled to the leak.
  - Fix: capture only when `smtpUrl == null` (dev mode), cap dev capture (e.g. 500, drop-oldest); track `sentCount` with a `LongAdder` like `failCount`.
  - Model: smaller model (mechanical; keep `sent()`/`last()`/`clearCaptured()` test API working).
  - **Resolved as:** per spec (dev-only capture, 500 drop-oldest, `LongAdder`). Deliberate semantics change beyond the spec: `sentCount()` now counts *successful* sends only — it previously counted attempts including SMTP failures, which made the ops dashboard's side-by-side Sent/Failed double-count failures. `clearCaptured()` also resets the counter (preserves the dev-mode `sentCount == sent().size()` invariant TestApp relies on). `sentCount()` widened `int` → `long`. Migration-guide entry covers all three; test API verified unchanged.

- [x] **H7 — Per-route stats keyed by concrete URL path: unbounded cardinality, never reset**
  - Severity: High. Files: `Stats.java:37-38,74-75`; fed raw paths from `BraceHandler.java:389,404,424`; `OpsHandler.java` route sort per /ops/status call.
  - `routeKey = method + " " + Redactor.redactPath(path)` — and `redactPath` deliberately keeps numeric IDs and UUIDs visible (Redactor doc, `Redactor.java:36-44`), so `/users/1`, `/users/2`, … each allocate a permanent map entry ("cumulative, not reset"). 404s record arbitrary attacker-chosen paths — internet scanner noise grows the map per unique probe. /ops/status sorts the whole map per call, so observability cost grows with the leak.
  - Fix: key matched requests by `match.route().pattern()` (BraceHandler already has the match in scope — pass it to `recordRequest`); collapse all 404s into a single `404` bucket; this also deletes one of the two per-request `redactPath` calls (see M8).
  - Model: smaller model OK, frontier review of the 404/error-path keying.

- [x] **H8 — Page cache: attacker-controlled key cardinality into an uncapped in-memory store**
  - Severity: High (memory-exhaustion DoS — cross-file to the security review if not fixed here). Files: `Cache.java:327-345` (`pageKey`/`queryKey` include the full sorted query string), `InMemoryBackend.java:27,50` (unbounded `ConcurrentHashMap`, no entry/byte cap), `Cache.java:46-55` (sweep removes only TTL-expired entries).
  - Every distinct query-param combination on a cached page stores a full `RenderedResponse` (entire body bytes) under a new key. `GET /cached-page?x=<random>` materializes one page copy per request and holds it until TTL; with a `10m` TTL an attacker (or just a crawler with tracking params) holds N×page-size of heap. Postgres backend: one `brace_cache` row + serialize + INSERT per distinct query string.
  - Fix: vary the page-cache key only on declared params (`.vary("page","sort")` allowlist on `CachedHandler`, ignore the rest), and add a max-entries bound (with eviction) to `InMemoryBackend`.
  - Model: frontier (cache-key semantics are user-visible API; needs a migration-guide entry).
  - **Resolved as:** per spec, with the default-vary decision made by Matt (2026-06-12): **undeclared routes ignore ALL query params** (key = path only) rather than keeping legacy vary-on-everything — safe-by-default, breaking, loud migration-guide entry ("Breaking: cached routes ignore query params unless declared"). `.vary(params)` declares the allowlist (sorted at declaration for canonical key order; absent param ≠ empty value; values still injectively percent-encoded — the security review's collision tests re-pointed at declared params). `InMemoryBackend` capped at 10,000 entries (ctor + `CacheBackend.inMemory(maxEntries)`): on insert past the cap, drop expired then arbitrary entries; approximate under concurrent insert; capacity evictions fold into the next `evictExpired()` so the ops eviction stat sees them; counters/tags uncapped. `HX-Request` vary unchanged. The PG backend needs no cap (disk-backed, TTL-swept) — the vary allowlist alone kills its per-junk-param row churn.

- [x] **H9 — Error storms: one virtual thread + 1–2 DB transactions per error occurrence**
  - Severity: High. Files: `BraceHandler.java:441-442` (vthread per 500), `ErrorStore.java:47-89` (per record: pool checkout + upsert + `SELECT COUNT(*)` + possible prune DELETE), `PostgresRegressionStore.bump` via `onRepeat` (second transaction per repeat).
  - The scenario that produces error floods (DB degraded/down) is exactly when each error spawns a thread that demands a pool connection; same-`(type,route)` upserts serialize on the row while holding/waiting on checkouts — error recording starves the pool that healthy requests need. Stack-trace string build + redaction also run synchronously per error before the spawn.
  - Fix: coalesce in memory per `(type, route)` and flush every few seconds (one upsert with `count + N`; ride `onRepeat` on the same flush), or at minimum a bounded single-consumer queue that aggregates/drops when full.
  - Model: frontier (loss semantics during storms must be deliberate; first-seen/new-error notification ordering must survive).
  - **Resolved as:** coalesce-and-flush (loss semantics chosen by Matt 2026-06-12 over a bounded drop-queue — the queue drops whole occurrences during exactly the event that matters, and `ops_errors` is already one-row-per-kind so coalescing loses nothing the table keeps). `record()` is a non-blocking in-memory merge; one `brace-error-flush` vthread writes one upsert per kind every 2s (`FLUSH_INTERVAL_MS`), prune check once per flush, all in one transaction on one connection. The per-500 vthread spawn in `BraceHandler` is gone. Deliberate losses: crash ≤2s of data; dashboard/notification lag ≤2s (`/ops/status` `errors.recent` stays immediate via Stats); >1,000 distinct pending kinds → new kinds dropped+counted+warned (known-kind occurrences are never dropped); DB down → buffer accumulates (bounded by kinds) and retries, counts survive the outage; flush failure merges the batch back without clobbering newer payloads. `onNew` ordering preserved (still fires once, post-commit, with the kind's true firstSeen); `onRepeat` gained a count param (listener interface change, migration-guide'd), `RegressionStore.bump(id, n)` on both stores. `Brace.stop()` closes/flushes the store; tests flush deterministically via package-private `Brace.errorStore()`. `ErrorStorePostgresIT`'s race test now runs two stores flushing concurrently — post-H9 the upsert race lives between instances, not threads.

## Medium

- [x] **M1 — Route matching: double linear scan, regex `Matcher` per route even for static paths** — `Router.java:28-42`, `Route.java:63-71`. Index static routes in a `HashMap<String,Route>` keyed `method + ' ' + path` (O(1), no regex); partition dynamic routes by method. **Resolved as proposed:** `Route.staticPath()` exposes the normalized literal path (same empty-segment/trailing-slash normalization as the compiled regex, so index and matcher agree); `putIfAbsent` keeps first-registration-wins; static lookup before the per-method dynamic scan preserves static-over-param precedence regardless of registration order. Model: smaller.
- [x] **M2 — Form body re-parsed on every accessor; CSRF parse duplicates it** — `Request.java:103-113,315-321` (each `formParam`/`form()` re-runs `parseFormBody`), `BraceHandler.java:277,567` (`parseFormParam` splits the whole body for `_csrf`). Parse lazily once, cache the map on `Request`; have the CSRF check use it. Model: smaller.
- [x] **M3 — Headers copied into two case-insensitive `TreeMap`s per request** — `BraceHandler.java:159-162` builds one; `Request.java:43,152-156` copies it again. Package-private adopting constructor (or comparator check) to skip the second copy; keep the defensive copy on public constructors. Model: smaller.
- [x] **M4 — FormBinder reflects from scratch on every bind; throws `NoSuchMethodException` as control flow** — `FormBinder.java:15,74,118-119,129-132`. Per-bind: `getRecordComponents()` (cloned array), `getAnnotations()` per component, linear `getDeclaredConstructor` scan + `setAccessible`, and `getDeclaredMethod("validate", …)` that **constructs+throws** (stack-trace fill) for every form without a custom validator. Fix: `ConcurrentHashMap<Class<?>, FormMeta>` caching components, annotations, constructor handle, nullable `validate` handle (absence marker). ~10–20x faster binds. Model: frontier-lite (reflection→MethodHandle migration; keep error messages identical). **Resolved as:** `ConcurrentHashMap<Class<?>, FormMeta>` keyed by record class, built once via `computeIfAbsent`. `FormMeta` holds a `FieldMeta[]` (name, type, `getAnnotations()` array — all resolved once), the canonical `Constructor` (setAccessible done once), and a **nullable** `Method validate` — null is the absence marker so the `getDeclaredMethod`+`NoSuchMethodException` throw runs once per class, never per bind. **Deviation from the spec's "→MethodHandle":** kept cached *reflective* `Constructor`/`Method` handles, not MethodHandles — the cost M4 removes is the per-bind *lookup* and *throw*, not invoke speed, and reflective invoke on a setAccessible'd handle preserves the exact exception/error semantics (the `InvocationTargetException`→`RuntimeException("Failed to construct…")` wrapping, compact-constructor throws) the finding says to keep identical; MethodHandle would change arg-mismatch and cause propagation. Cache cardinality = app form types (code-defined, not request-controlled) → no eviction. `convert`/`validate` logic byte-identical; all 11 `FormTest` cases (3 no-validate records + 1 custom-validate) green, full suite 829/829. **JMH-measured (FormBindBench, results below): 39–56× faster, 14–21× less allocation — beats the 10–20× estimate.**
- [x] **M5 — `new SecureRandom()` per operation** — `Session.java:200` (every cookie encrypt), `Csrf.java:13` (every token mint). `static final SecureRandom` in both (thread-safe, tiny critical section). Model: smaller (one-liner ×2).
- [x] **M6 — Rendered HTML materialized three times (StringOutput → String → byte[])** — `TemplateEngine.java:19-23`, `View.java:44-50`, `BraceHandler.java:464`; same shape for JSON (`Json.java:30` `writeValueAsString` then `getBytes`). Render JTE into `Utf8ByteOutput` with `binaryStaticContent(true)` and carry via `Result.rawBytes()` (already supported, `BraceHandler.java:461-462`); use `writeValueAsBytes` for JSON. ~3x less per-render allocation. Model: frontier-lite (charset/contract edges: `result.body()` accessors, after-middleware that rewrites bodies). **Resolved as:** built on M12's `materialize()` seam — `View.materialize()` calls a new `TemplateEngine.renderToBytes()` (`Utf8ByteOutput` → `toByteArray()`) and stores via `Result.setRenderedBytes()`; `Json.of()` uses `MAPPER.writeValueAsBytes()` and a package-private `Result(int,String,byte[])` ctor. `binaryStaticContent(true)` set on the on-demand/eager engine path **and** in `TemplatePrecompiler` (it's a codegen flag — precompiled classes must be generated with it, so the prod `createPrecompiled` path emits `writeBinaryContent(byte[])` for static chunks; setting it on the runtime `createPrecompiled` engine would be a no-op). `writeResult`/`RenderedResponse.from` already read `rawBytes()` first, so the wire and page-cache paths now skip the String round-trip and the second encode entirely; JSON caching stores the serialized bytes directly. **Contract bridge:** `Result.body()` lazily decodes `rawBytes` → UTF-8 String (memoized) so a `body()` caller (a body-rewriting after-middleware, or `ResultTest.jsonResult`) still gets the String — that decode only runs for callers that actually want it, never on the hot path. The static `View.render()` String API and stub-mode (no engine) keep the String path. **Charset edge tested:** new byte-accuracy tests (multibyte static + dynamic content) assert the wire byte count equals the UTF-8 length and the content decodes correctly, on both the dev on-demand path (`TemplateTest`) and the prod precompiled+binaryStaticContent path (`TemplatePrecompilerTest.renderToBytes`). Wire-transparent (no API/response change) → no migration-guide entry, like M1/M2/M3. Full suite 829/829 + new asserts.
- [x] **M7 — JTE always runs in dev mode: first-render compile, per-render hot-reload checks, compiler loaded in prod** — `TemplateEngine.java:15-16` (`TemplateEngine.create` + `DirectoryCodeResolver`, the only mode; verified no `createPrecompiled` reference anywhere in src/main). Each template pays a javac compile (tens–hundreds of ms) on its first render after every deploy; `DirectoryCodeResolver` timestamp checks run on subsequent renders in production; `jdk.compiler` + jte compiler infra cost ~20–50MB of metaspace/heap that precompiled mode never pays. Fix: precompiled mode (jte-maven-plugin in `brace compile`/`mvn package`, `createPrecompiled` when running in prod mode — depends on M20 giving the framework a mode signal); at minimum eagerly compile all templates at `start()` off the request path. Also unlocks M6's `binaryStaticContent`. **Resolved as:** `TemplatePrecompiler` (child JVM on the project classpath, generates into jte's *precompiled* package — `precompileAll()` on a default `create()` engine emits `gg.jte.generated.ondemand`, which `createPrecompiled` would never find) invoked by `brace compile`/`brace run` for conventional `views/`/`templates/` dirs → `target/jte-classes` + `.source-templates` marker; in prod `TemplateEngine` loads it only when the marker matches its template dir (override dir via `-Dbrace.templates.precompiled`, trusted as-is), else eager `precompileAll()` at startup. Failure-mode shift (broken template fails boot, not first render) documented in the migration guide. Verified live: scaffolded app served the home page in prod with `views/` renamed away. Follow-up (same day): marker stores the *relative* template path (absolute paths broke host-build → container-run, e.g. Docker build stage `/build` vs runtime `/app`); scaffolded Dockerfile updated to `eclipse-temurin:25-jre` + `COPY target/jte-classes/` + `-Dbrace.mode=prod` (JRE images lack `jdk.compiler`, so the old dev-mode-in-Docker scaffold 500'd on first render — larva2 had worked around exactly this with a full-JDK image and a "defer until brace ships that" comment); guide gained a Docker conversion section. Pre-existing scaffold gap surfaced and fixed in a separate commit (4baabf7, user-approved, outside this finding): the scaffolded pom built a thin jar with no Main-Class/shade, so the Dockerfile's `java -jar app.jar` never worked; scaffold now shades (config mirrored from larva2), `finalName` = project name, Dockerfile copies the exact jar (shade leaves `original-*.jar` in target/, which broke the glob). Verified by building a scaffold against the locally-installed worktree jar and launching it — reached the Postgres handshake. Model: frontier-lite (build/CLI integration).
- [x] **M8 — `Redactor.redactPath` runs twice per request (3× on the 500 path)** — `Stats.java:74` + `Log.java:27` (+ `BraceHandler.java:415`). **Resolved as:** the success-path duplication fell out of H7 (stats keyed by pattern → only the log line redacts; once per request). The remaining multi-pass on the rare 500 path is *deliberately retained*: the security review placed redaction at each sink (Log/Stats/ErrorStore) so no caller can forget — passing pre-redacted strings through would trade that invariant for µs on an exceptional path. Implemented the `isSecretShaped` ordering fix (length check before the UUID regex; safe since UUIDs are 36 chars ≥ MIN_SECRET_LENGTH), so short segments — the overwhelming majority — never touch the regex engine. Model: smaller.
- [x] **M9 — `queryOne`/`findBy` hydrate every matching row to return one** — `Database.java:111-115,148-151`. Build the query and `setMaxResults(1)` instead of `query().get(0)`. Model: smaller (one-liner + test).
- [x] **M10 — `existsBy` does `COUNT(*)` over all matches** — `Database.java:190-193`. `SELECT 1 … setMaxResults(1)`, test non-empty: index probe instead of O(matches) count. **Resolved as proposed** (field-identifier validation retained; covered by new `DatabaseTest.existsBy`). Model: smaller.
- [ ] **M11 — Hibernate/Hikari autocommit dance per transaction** — `DatabaseFactory.java:152-174`: `hibernate.hikari.autoCommit` left true, `hibernate.connection.provider_disables_autocommit` unset. **Caveat:** the read-only handler path (`BraceHandler.java:322`) runs queries with no transaction, relying on autocommit — flipping this requires giving the read-only path an explicit lifecycle first. Model: frontier (correctness coupling).
- [x] **M12 — Views render while the request transaction + pooled connection are held** — `View.java:46` (render in constructor, inside handler), commit at `BraceHandler.java:325-335`. StatelessSession has no lazy loading, so nothing rendered needs the connection; a 20ms render caps a 10-conn pool at ~500 rps. Defer rendering to `writeResult` (after `db.close()`). Model: frontier (lifecycle reorder; ThreadLocal flash/csrf interplay). **Resolved as:** `View.of()` now captures template + params (resolving the csrf/flash ThreadLocals *eagerly* at construction, as before — `getCsrfField()` still mints the token and marks the session modified inside the handler, so the cookie-write decision is unchanged) but defers the `engine.render()` to an overridable `Result.materialize()`. `BraceHandler` calls `result.materialize()` once, right after the db try/finally closes the connection and before after-middleware — a controlled render point fully inside the request try, so a render failure throws cleanly to the 500 catch with **nothing yet written to the response** and the transaction already committed. `Result.body()` also lazily calls `materialize()` (idempotent, guarded), which covers the page-cache snapshot path (`RenderedResponse.from` reads `body()` — cached routes are no-DB Handlers, so no connection is held there anyway) and any body-rewriting after-middleware. **Semantics decision (Matt, 2026-06-12): commit-then-render** — a deferred render failure is a 500 with the write persisted, deliberately, because (a) under StatelessSession the render touches no transactional state, (b) after-middleware/cookie-write/socket-write were already all post-commit, (c) 5xx is "indeterminate" in HTTP, not "nothing happened," and (d) it fails toward availability (a template bug no longer makes a committed action unrepeatable). Known cost recorded in the migration guide: a non-idempotent mutation that renders inline (not PRG) can repeat on retry — remedy is PRG/idempotency key. `Json` stays eager (sub-ms serialize; View-only scope, Matt 2026-06-12). New `RenderAfterCommitTest` (broken template after a DB insert → 500 + row committed; clean render on a DB route → 200) + `brokenRender.jte`; full suite 828/828.
- [x] **M13 — Synchronous SMTP on the calling thread, fresh connection per email** — `Mailer.java:32-44,101`. `Transport.send` = TCP + STARTTLS + auth inline (100ms–2s) while any enclosing transaction stays open; `MailerNotifier.java:24` already shows the offload pattern. Add `sendAsync()` via `Jobs`/virtual thread and document it as the default for request paths. **Resolved as:** `EmailBuilder.sendAsync()` on a virtual thread (same pattern as MailerNotifier); failures logged + counted in `failCount()`, never thrown. Documented as the request-path default (BRACE-AGENTS.md, README, migration guide). The fresh-SMTP-connection-per-email part is *not* addressed — connection reuse/pooling would need a persistent `Transport`, out of scope for this finding. Model: smaller.
- [x] **M14 — Multi-instance recurring-job claim: `SELECT … FOR UPDATE` on every tick on every instance** — `JobScheduler.java:188-232`. Non-locking read of `last_run_slot` first; take the row lock only when the slot looks unclaimed. An `every("1s")` job on 5 instances is constant lock traffic today. Model: frontier-lite (multi-instance race reasoning). **Resolved as proposed:** `claimRun` now does a non-locking snapshot `SELECT last_run_slot` first; if `lastSlot >= currentSlot` (the common case — the slot was already claimed by another instance or by this one on an earlier tick within the slot) it returns false with no row lock. Only when the slot looks unclaimed (or the row is missing, or the read errors) does it fall to the new `claimUnderLock` — the original `SELECT … FOR UPDATE` + re-check + `UPDATE`, **unchanged**, so the exactly-once guarantee is intact: a stale unlocked read can only under-report a claim (READ COMMITTED never exposes an uncommitted newer slot), and the locked re-check catches anything claimed in the gap → never a missed skip, never a duplicate. An `every("1s")` job on N instances drops from N FOR UPDATE write locks/second on one row to N snapshot reads + at most one lock per slot. A freshly-seeded NULL slot reads as "unclaimed" → locked path (correct). Verified: `MultiInstanceSchedulerTest` cluster-dedupe (once per interval across 2 instances) green, and the real-Postgres `MultiInstanceSchedulerPostgresIT` green (the multi-instance race that matters). No schema/migration change.

- [ ] **M15 — Cache `getOrSet` runs the user supplier inside `ConcurrentHashMap.compute`** — `InMemoryBackend.java:62-72` (verified: `store.compute` at `:67` invokes the supplier), via `Cache.java:105-110`. Suppliers are typically DB queries: the CHM bin lock is `synchronized`, so the supplier's blocking I/O pins a carrier thread on JDK 21–24, and unrelated keys in the same bin stall behind it. Keep single-flight but move work outside the map op (per-key in-flight `CompletableFuture` via `putIfAbsent`, or per-key `ReentrantLock`). Model: frontier-lite (single-flight semantics under exceptions).
- [x] **M16 — `Storage` HttpClient has no connect or per-request timeouts** — `Storage.java:49,121-131,207-216` (contrast `Http.java:18-26`, which sets both). A blackholed S3 endpoint hangs `put()`/`delete()` forever — typically inside a request with an open transaction, holding a pooled connection indefinitely. Add `connectTimeout` + per-request `.timeout(...)`, configurable, default ~30–60s. **Resolved as:** 10s connect (fixed) + 60s per-request, configurable via `s3.timeoutSeconds` or the new `Duration` constructor overload; migration-guide entry flags multi-GB-upload apps. Model: smaller.
- [ ] **M17 — Shared-backend rate limiter adds a full extra DB transaction per request through it** — `RateLimiter.java:199-209` → `Counters.java:43-65` (session + tx + upsert + commit per check, on top of the request's own transaction). Intentional design (B4, documented), but a per-instance micro-batch (count locally, flush delta every ~250ms or K hits) keeps fleet accuracy within a flush interval and cuts DB ops 10–100x on hot endpoints. Model: frontier (accuracy/consistency trade-off is a documented posture change).
- [ ] **M18 — WebSocket broadcast has no slow-consumer backpressure** — `WsRegistry.java:51-58`, `WsContext.java:32-34` (`sendText(message, Callback.NOOP)`). A stalled client makes Jetty queue outgoing frames unboundedly (broadcast rate × message size per slow client). Track per-session pending sends via the callback and drop/close past a threshold; also consider Jetty's `setMaxOutgoingFrames`. Model: frontier-lite.
- [x] **M19 — JFR profiler runs continuously whenever ops is enabled; profiling maps never reset without a database** — `Brace.java:502-504` (created unconditionally with ops), `JfrProfiler.java:75` (`jdk.ExecutionSample` at 20ms = 50 stacks/s), `:87` (allocation sampling); the `resetProfiling()` job is only registered in the `databaseFactory != null` branch (`Brace.java:711,730`), so an ops-enabled no-DB app (the shipped sample) accumulates `methodSamples`/`allocationByClass` forever. ~0.5–2% steady CPU + a thread + JFR buffers paid 24/7. **Decision (user, 2026-06-11): always-on JFR is worth the CPU — keep default on, add an opt-out instead of lazy-start.** Fixed: `app.opsProfiler(false)` builder method (default true); no-DB apps now reset the sample maps on the same 5-minute cadence as the DB-backed flush. Model: frontier-lite.
- [x] **M20 — `brace run`/`brace dev` launch the app JVM with no flags: `brace.mode` is never set, `%dev.` config is unreachable, no `JAVA_OPTS` passthrough** — `BuildCommands.java:86,145` (`new ProcessBuilder("java", "-cp", classpath, mainClass)`); `bin/brace` flags only reach the CLI JVM. The framework therefore has no signal to ever select prod behavior (gates M7), `Config.java:42-50` mode prefixes are dead through the CLI (`sample/application.conf` `%dev.port=9000` does nothing), the startup banner mode is always "—", and the app JVM gets default heap sizing. **Resolved as proposed:** `javaCommand()` builds `java -Dbrace.mode=<dev|prod> [BRACE_JAVA_OPTS…] -cp …` — our flag first so a user `-Dbrace.mode` in the env wins (last `-D` wins). Migration-guide entry flags the dormant-`%dev.`-keys-now-apply hazard (scaffolded `%dev.port=9000` starts taking effect). Model: smaller, frontier review (user-visible behavior change → migration-guide entry).
- [ ] **M21 — Cold start is fully serial: framework Flyway → app Flyway → SessionFactory → ops seed → Jetty** — `DatabaseFactory.java:38-40`, `Brace.java:509-524,599`. `buildSessionFactory` has no data dependency on the migrations (`hbm2ddl.auto=none`, `DatabaseFactory.java:163`) yet waits for both Flyway runs (each with its own scanner + unpooled connections). Typical serialized cost: Flyway 200–600ms + SessionFactory 0.5–1.5s. Fix: build the SessionFactory concurrently with the migrations (join before the constructor returns); optionally lazy Hikari fill (`initializationFailTimeout`). Jetty-last ordering is correct — keep it. ~30–50% cold-start cut for DB apps. Model: frontier (failure-ordering semantics: migration failure must still prevent serving).

## Low

- [ ] **L1 — `Invoker.fromFunction` allocated per request for plain `Handler` routes** — `BraceHandler.java:238-243`; `Brace.java:298` and the `/ops/*` registrations pass null invokers. Build the invoker once at registration. Model: smaller.
- [ ] **L2 — `Invoker.build` (the `Method.invoke` path) is dead code** — `Invoker.java:34-64` has zero callers in src/main (verified by grep): every `app.get(...)` overload wraps lambdas in direct-call anonymous Invokers at registration (`Invoker.java:71-132`), so **no reflection runs on the request path** — an earlier draft of this finding claimed otherwise and was wrong. Delete `Invoker.build` (or, if kept for a future controller-scanning feature, add `setAccessible(true)` at build time and a comment). Model: smaller.
- [x] **L3 — `NotFoundException` fills in a stack trace as routine control flow** — `NotFoundException.java:4`, used by `Result.notFoundIfNull`. `super(msg, null, false, false)`; the catch at `BraceHandler.java:394` never reads the trace. Model: smaller.
- [ ] **L4 — `Request.ip()` compiles a regex per call; `TrustedProxies.contains` allocates `BigInteger`s per check** — `Request.java:282`, `TrustedProxies.java:121-130`. Hoist a static `Pattern` (or hand-rolled digit check); cache `network.getAddress()` in `CidrRange`. Model: smaller.
- [x] **L5 — Middleware path match runs a regex whose semantics are equals/startsWith** — `Middleware.java:64-66`. Store prefix/exact strings at registration. Model: smaller.
- [ ] **L6 — `convertPositionalParams` re-scans the HQL string on every execution** — `Database.java:370-511`, called from every query method. Bounded `ConcurrentHashMap<String,String>` memo (call sites are code literals → naturally small cardinality). Model: smaller.
- [ ] **L7 — `queryIn` generates one HQL string per IN-list size** — `Database.java:94-104`. Pad the list to the next power of two (repeat last value) to bound plan-cache/prepared-statement churn. Model: smaller.
- [ ] **L8 — `Session` JSON parser boxes scan indices as `String`** — `Session.java:344,353,369,386` (`String.valueOf(i)` → `Integer.parseInt` per key/value per decrypt). Small index-holder record instead. Model: smaller.
- [ ] **L9 — `Jobs.parallel` retains one `Thread` handle per item regardless of concurrency cap** — `Jobs.java:97-113`. Join in waves or acquire-all-permits at the end; keeps it O(concurrency). Model: smaller.
- [x] **L10 — Cache TTL string regex-parsed on every call** — `Cache.java:105-108,321`. `CachedHandler`'s TTL is fixed at construction — parse once to a `Duration` field; add a `Duration` overload for hot `getOrSet` sites. Model: smaller.
- [ ] **L11 — Page cache thundering herd on expiry; redundant cross-instance Postgres sweeps** — `Cache.java:317-321` (get-then-set, no single-flight for pages), `PostgresBackend.java:182-189` (global DELETE every 30s from every instance). Model: smaller.
- [x] **L12 — `Storage` per-call formatter construction, per-call SigV4 key derivation, `String.format` hex** — `Storage.java:112-115,198-201,258,314-328`. Hoist `static final DateTimeFormatter`s; cache the signing key per `dateStamp` day; use `HexFormat` (as `RateLimiter.java:166` already does — same fix applies to `Assets.java:100-102`). Model: smaller.
- [ ] **L13 — `Storage` has no streaming path: whole object ≥2× in heap per upload** — `Storage.java:109` (`byte[]`), with `UploadedFile.bytes()` already buffered. Acceptable for stated scope; document the limitation, consider a temp-file + `BodyPublishers.ofFile` variant later. Model: n/a (docs) or frontier-lite (streaming variant).
- [ ] **L14 — RateLimiters are never unregistered; one immortal cleanup vthread each; each runs the global sweep** — `RateLimiter.java:20,61,246-273`. Fine for `main()`-time construction; leaks if created dynamically or in tests. Add `close()`, centralize the sweep in one place. Model: smaller.
- [ ] **L15 — WebSocket rooms use `CopyOnWriteArraySet`: O(n) copy per join/leave** — `WsRegistry.java:32`. `ConcurrentHashMap.newKeySet()` keeps broadcast iteration safe with O(1) membership churn. Model: smaller.
- [ ] **L16 — Postgres message bus: session + transaction + `pg_notify` round trip per broadcast** — `PostgresMessageBus.java:77-96`, plus opportunistic DELETE on the spill path (`:121-124`). Fine at chat rates; batch/coalesce if per-tick fan-in becomes a use case. Model: note only.
- [ ] **L17 — `ErrorStore.list(status, since)` fetches all rows/columns then filters in Java** — `ErrorStore.java:198-209`; bounded at 1000 rows but each carries full stack traces + headers; the deploy post-check and `RegressionTracker.seed()` hit it. Push `first_seen >= ?` + column subset into SQL. Model: smaller.
- [ ] **L18 — `Assets.url()` does two `stat` syscalls + path normalization per call even on hash-cache hits** — `Assets.java:54-84` (`Files.isRegularFile` + `Files.getLastModifiedTime`; per-mapping base path re-normalized per call). In prod mode, cache the final URL keyed by path with a short re-stat interval; hoist the base-path normalization to construction. (Content-hash caching by `(path, mtime)` verified present.) Model: smaller.
- [ ] **L19 — JobPoller + JobScheduler always start when a database exists, even with zero jobs** — `Brace.java:627-630`; `JobPoller.java:34-58` (~8,600 idle `scheduled_jobs` queries/day/instance), `JobScheduler.java:70-82` (scheduler pool with no jobs). Start the poller lazily on first `Jobs.schedule(...)`; skip the scheduler pool when nothing is registered. Model: smaller.
- [ ] **L20 — Hikari `minimumIdle == maximumPoolSize` (10), not separately configurable** — `DatabaseFactory.java:29,166-167`. Fixed-size pools are Hikari's recommended posture, but a few instances × 10 + the message-bus raw connection + JobPoller can exhaust small PaaS `max_connections`; each idle PG connection costs ~1–10MB server-side. Expose `minimumIdle` or document sizing in `docs/scaling.md`. Model: smaller (mostly docs).
- [ ] **L21 — Static files re-read from disk and fully buffered per request; no Cache-Control/ETag** — `BraceHandler.java:506` (`Files.readAllBytes` per request), no caching headers despite `Assets` fingerprinting existing for exactly this. Emit `Cache-Control: public, max-age=31536000, immutable` for `?v=` fingerprinted URLs (+ `Last-Modified`/`ETag` otherwise); optionally a small bounded cache keyed by (path, mtime). Model: smaller.
- [ ] **L22 — Jetty `QueuedThreadPool` left at defaults (max 200) alongside virtual threads** — `Brace.java:543-556`. Handlers run on virtual threads, so the platform pool only serves selectors/acceptors; cap it (e.g. 16) to make the footprint intentional. Cosmetic-to-small. Model: smaller.

## Correctness follow-up found during review (not perf — route separately)

- [x] `JobScheduler.daily()` (`JobScheduler.java:61-68`) lacks the `scheduler != null` late-registration branch that `register()` has (`:52-58`): a daily job registered after `start()` is silently never scheduled. File alongside the review or fix opportunistically with M14. **Fixed with M14:** `daily()` now mirrors `register()`'s late branch — seeds the coordination row and `scheduleAtFixedRate`s the job when `scheduler != null` (daily is always cluster-deduped, `local=false`). New `MultiInstanceSchedulerTest.dailyJobRegisteredAfterStartIsScheduled` (register after `start()` targeting ~1-2s out; pre-fix it never ran).

## Verified non-issues (checked against source; do not re-litigate)

- `Json.MAPPER` is a single shared static (`Json.java:12`); no per-request ObjectMapper anywhere.
- PBKDF2 session-key derivation is memoized in a bounded map (`Session.java:83,405-412`).
- `Stats.recordRequest` is genuinely lock-free (LongAdder + CAS max; the synchronized blocks are snapshot/ops-only). `recordError` takes a lock + O(50) scan, but only on 5xx.
- Session cookie is only re-encrypted when `isModified()` (`BraceHandler.java:353`) — the mechanism is right; H5 is about what *sets* modified.
- Route/middleware `Pattern`s are compiled at registration, not per request (M1/L5 are about *running* them needlessly).
- DB session opening is correctly gated on `invoker.needsDatabase()`; read-only handlers skip `beginTransaction`.
- pgjdbc client-side prepared-statement caching is on by default; Hikari delegates correctly (modulo L7's churn caveat).
- SKIP LOCKED batch claim on Postgres is present and correct; claim commits before execution.
- Second-level cache absence is by design (StatelessSession bypasses it).
- bcrypt cost 12 with no hot-path callers.
- `LogTap` ring is bounded (1000) and lock-free.
- `hibernate.jdbc.batch_size` unset is currently moot — no multi-insert API exists.
- `Http` shares one static `HttpClient` with sane timeouts (10s connect / 30s request) — no per-call client construction.
- `PostgresBackend.size()` is memoized (5s); cache expiry is enforced on read in both backends.
- `Counters` Postgres increment is a single-statement upsert (the fast path is right; M17 is about per-request frequency).
- Error recording is already off the request thread and regression listeners fire only post-commit (H9 is about volume, not placement).
- `RateLimiter.checkLocal` is a single cheap CHM `compute`; keys are length-capped (64); window maps are swept.
- `WsRegistry` room removal uses the two-arg `rooms.remove(room, members)` — no leak; Postgres bus listener uses a dedicated non-pool connection with reconnect/backoff.
- `Url.to` uses the single-char `String.split` fast path — no Pattern compile.
- `Assets` content hashing is correctly cached by `(path, mtime)`.
- `Config` is parsed once per `load()`; no file re-reads at runtime (env fallback per miss is off the framework hot path).
- WebSocket/message-bus infrastructure is built only when `wsRoutes` is non-empty (`Brace.java:565`) — properly lazy.
- `htmx.min.js` is loaded once into a field at startup, not per request.
- H6 (Mailer) and H7 (Stats cardinality) were independently re-found by the startup/memory sweep — double-confirmed.

---

# Benchmarks

## Cumulative scoreboard

Baseline (`ea76ffa`, pre-fix) → current checkpoint. Updated after each quiet-window run;
per-checkpoint detail and raw outputs below.

**Current checkpoint: `a62f737` (adds M1, M10, M13, M16, M20, M7, M12, M6 since `33180b8`)**

| Test | Req/sec | Δ | p99 | Δ | Notes |
|---|---|---|---|---|---|
| Plaintext | 67,649 → 76,054 | +12% | 27.9ms → 4.46ms | −84% | CPU-saturated w/ wrk; p99 flattered by clean machine (see caveats) |
| JSON | 68,964 → 76,044 | +10% | 45.8ms → 5.02ms | −89% | M6 byte path |
| Single Query | 25,929 → 29,418 | +13% | 41.4ms → 20.7ms | −50% | |
| Multiple Queries (20) | 1,281 → 1,759 | **+37%** | 464ms → 229ms | −51% | |
| Fortunes | 19,920 → 28,153 | **+41%** | **1.23s → 35.4ms** | **−97%** | template render path (M6/M7); +16% req/s, −38% p99 vs `33180b8` |
| Updates (20) | 1,123 → 1,462 | **+30%** | 633ms → 291ms | −54% | |

Previous checkpoint `33180b8` (adds H5, H7, M2, M3, M8), for reference:

| Test | Req/sec | Δ | p99 | Δ | Notes |
|---|---|---|---|---|---|
| Plaintext | 67,649 → 76,901 | +14% | 27.9ms → 26.8ms | −4% | CPU shared with wrk |
| JSON | 68,964 → 78,200 | +13% | 45.8ms → 10.8ms | **−76%** | |
| Single Query | 25,929 → 27,288 | +5% | 41.4ms → 25.3ms | −39% | |
| Multiple Queries (20) | 1,281 → 1,696 | **+32%** | 464ms → 296ms | −36% | |
| Fortunes | 19,920 → 24,305 | **+22%** | **1.23s → 56.7ms** | **−95%** | 51 socket timeouts → 0; see variance caveat |
| Updates (20) | 1,123 → 1,423 | **+27%** | 633ms → 301ms | −52% | |

Session/CSRF scenario (separate suite, baseline is the `8b495d5` checkpoint — see
"Session/CSRF scenario" below):

| Test | Req/sec | Δ | p99 | Δ |
|---|---|---|---|---|
| Session Read | 74,553 → 75,332 | +1% | 44.0ms → 20.6ms | **−53%** |
| CSRF Form POST | 73,132 → 74,381 | +2% | 60.4ms → 44.5ms | −26% |
| API POST csrf(false) | 70,635 → 75,421 | +7% | 85.2ms → 20.1ms | **−76%** |

## What exists today

| Asset | What it measures | Notes |
|---|---|---|
| `benchmark/` TFB-style suite (`App.java`, `run-brace.sh`, `run-spring.sh`, `RESULTS.md`) | Macro throughput/latency via wrk (8 threads / 256 conns / 15s + warmup): plaintext, json, db, queries, fortunes, updates; Brace vs Spring Boot baseline | The primary before/after harness for this review |
| `benchmark/.../Profile.java` | Per-request lifecycle breakdown (openSession / begin / query / commit / close, µs over 10k iterations) | Directly relevant to M11/M12/H4 |
| `Stats` + `/ops/status` | In-app request counts, avg/max latency, per-route averages, query counts, heap | Avg/max only — no percentiles |
| `JfrProfiler` | CPU %, GC pauses, thread counts, method sampling, allocation by class | Good for verifying H2/M6 allocation claims under load |
| `db.queryDurationUs()` | Per-request query time | Feeds Stats |
| CI (`mvn verify`) | Correctness only | No perf gate |

## Baseline (captured 2026-06-11)

Framework at `ea76ffa` (M19 only — no effect on these paths: the benchmark app doesn't
enable ops, so JFR is off either way). Environment: local macOS (Darwin 24.6.0), JDK 25.0.2
(Homebrew), Postgres 16 in Docker (`tfb-postgres`, port 5433, TFB schema), wrk 8 threads /
256 connections / 15s + 5s warmups, `--latency`. Raw output:
`benchmark/baselines/2026-06-11-wrk-baseline-ea76ffa.txt` (reproduce with
`benchmark/run-brace.sh`).

| Test | Req/sec | p50 | p99 | Notes |
|---|---|---|---|---|
| Plaintext | 67,649 | 3.25ms | 27.9ms | |
| JSON | 68,964 | 2.68ms | 45.8ms | |
| Single Query | 25,929 | 9.19ms | 41.4ms | |
| Multiple Queries (20) | 1,281 | 178ms | 464ms | |
| Fortunes | 19,920 | 10.3ms | **1.23s** | max 1.57s, **51 socket timeouts** |
| Updates (20) | 1,123 | 195ms | 633ms | |

Observations to test against fixes: the Fortunes p99 (1.23s vs 10ms p50) and its socket
timeouts are exactly the tail-stall signature H1 (stdout lock) and M6/M12 (render
allocation, render-inside-transaction) predict — re-check this line after each of those
fixes. Plaintext/JSON p99s (28/46ms vs ~3ms p50) likely carry the H1 + H2 (GC churn)
signal too. Numbers are not comparable to `benchmark/RESULTS.md` (different JDK and
machine conditions); within-review comparisons only.

## Re-measurements

### After H1 (async log writer) — framework `f65fd12`

Same environment and protocol as the baseline; raw output
`benchmark/baselines/2026-06-11-wrk-post-H1-f65fd12.txt`. Caveat: run on a developer
laptop alongside other workloads, so treat single-digit-percent deltas as noise.

| Test | Req/sec | vs baseline | p99 | vs baseline |
|---|---|---|---|---|
| Plaintext | 66,096 | −2% (noise) | 23.3ms | −16% |
| JSON | 65,754 | −5% (noise) | 50.2ms | ~flat |
| Single Query | 27,014 | +4% | 51.1ms | ~flat |
| Multiple Queries (20) | 1,648 | **+29%** | 408ms | −12% |
| Fortunes | 24,378 | **+22%** | **50.8ms** | **−96% (was 1.23s)** |
| Updates (20) | 1,464 | **+30%** | 290ms | −54% |

The baseline's tail-stall signature is gone: Fortunes p99 collapsed from 1.23s to 51ms
and its 51 socket timeouts dropped to **zero**; max latency fell from 1.57s to 152ms.
The DB-backed endpoints (which all log one line per request while holding a pooled
connection) gained 22–30% throughput. Plaintext/JSON moved within noise — they were
already saturating CPU shared with wrk on the same machine, so the freed lock time
mostly went to wrk itself. Conclusion: H1 was the throughput/tail ceiling for DB-backed
routes, as predicted.

### After H2 + batch A (M5, M9, L3, L5, L10, L12) — framework `8b495d5`

Quiet-window run (1-min load 5.8/10 cores, verified before firing; mds_stores spike from
earlier in the day had settled). Raw output:
`benchmark/baselines/2026-06-11-wrk-post-H2-batchA-8b495d5.txt`.

| Test | Req/sec | vs post-H1 | p99 | vs post-H1 |
|---|---|---|---|---|
| Plaintext | 67,292 | +2% (noise) | 21.6ms | −7% |
| JSON | 66,321 | +1% (noise) | 19.8ms | −61% |
| Single Query | 26,915 | flat | 28.9ms | −43% |
| Multiple Queries (20) | 1,761 | +7% | 227ms | **−44%** |
| Fortunes | 27,454 | **+13%** | 38.9ms | −23% |
| Updates (20) | 1,445 | flat | 296ms | flat |

Consistent with H2's mechanism: throughput moves are modest (the 64KB/request was GC
pressure, not lock contention) but p99s tightened across the board — fewer young-gen
pauses landing in the tail. Fortunes (largest responses, most allocation-sensitive)
gained the most throughput. Cumulative vs the pre-fix baseline: Fortunes 19,920 → 27,454
req/s (+38%) with p99 1.23s → 39ms; Queries(20) +37%; Updates(20) +29%.

### After H5 cluster (H5, H7, M2, M3, M8) — framework `33180b8`

Quiet-window run (1-min load 7.3 at fire, verified <7 for the two prior minutes). Raw
output: `benchmark/baselines/2026-06-11-wrk-post-H5cluster-33180b8.txt`. Run on port
8090 — Matt's larva2 dev server (brace 0.1.6) held 8080 and was running, mostly idle,
throughout; a first attempt on 8080 silently measured that server's 404s, which is why
`run-brace.sh`/`run-session.sh` now hard-fail unless the port's listener PID is the app
they just started.

**Variance caveat:** two isolated back-to-back Fortunes re-runs under identical
conditions returned 21,872 and 26,308 req/s (~±10%), noisier than earlier checkpoints —
treat all single-digit deltas below, and the apparent Fortunes drop vs the previous
checkpoint, as noise.

| Test | Req/sec | vs post-H2 | p99 | vs post-H2 |
|---|---|---|---|---|
| Plaintext | 76,901 | +14% | 26.8ms | +24% |
| JSON | 78,200 | +18% | 10.8ms | −45% |
| Single Query | 27,288 | +1% (noise) | 25.3ms | −12% |
| Multiple Queries (20) | 1,696 | −4% (noise) | 296ms | +30% (noise) |
| Fortunes | 24,305 | −11% (noise) | 56.7ms | +46% (noise) |
| Updates (20) | 1,423 | −2% (noise) | 301ms | flat |

The standard suite never touches sessions, so H5/M2 are invisible here by design;
the candidates for the real Plaintext/JSON throughput gain are M3 (drops a per-request
TreeMap copy of all headers — the only hot-path change that fires on every request)
plus possibly H7 (per-route stats key lookup no longer allocates a redacted path
string on the success path). Given the variance caveat, confidence is moderate;
the DB-backed tests are dominated by query time and moved within noise, as expected.

### Job-queue claim latency (H3/H4) — `123714e` (pre) vs `515cdcd` (post)

First run of `run-jobs-claim.sh` (gap #4, claim-latency half): `benchmark.JobsBench`
against tfb-postgres, database `jobs_bench`, seeded with 1M dead rows (95% completed /
5% failed, run_at spread over the prior 30 days — the oldest-prefix shape H3 describes).
Same jar-swap discipline as the session suite (`JobPoller.class` checksums verified
distinct). The post run reused the seeded table **plus** the 1,000 rows the pre drain
completed, so it faced slightly more history, and paid the one-time V15 index build at
startup. Not a quiet-window run — these are single-query latencies, not throughput, and
the deltas are far outside machine noise. Raw outputs:
`benchmark/baselines/2026-06-12-jobs-claim-pre-123714e.txt`, `…-post-515cdcd.txt`.

| Metric | Pre (avg) | Post (avg) | Δ |
|---|---|---|---|
| Empty poll (every 10s/instance, forever) | 65.6ms | 0.7ms | **−99%** |
| `getDurableJobStats` (per /ops/status) | 210.9ms | 38.4ms | **−82%** |
| Drain 1,000 no-op jobs | 29.26 ms/job (20 polls × 50) | 0.52 ms/job (200 polls × 5) | **−98%** |

Mechanism check: empty poll is the V15 partial index + NOT EXISTS rewrite doing exactly
what H3 predicted — claim cost now tracks live work (sub-ms) instead of table history.
Stats is the 4-scans→1-scan collapse; the remaining 38ms is the irreducible single scan
of 1M rows (drops further as retention prunes). The drain line is indicative only
(H4 changed batch size 50 → poolSize/2 = 5), but is the strongest combined signal:
56× more throughput with 10× more claim round-trips, because each pre-H3 claim-with-
pending paid ~1.4s walking dead rows and hashing 950k completed ids per poll.

### Mixed web+jobs (H4) — `123714e` (pre) vs `515cdcd` (post)

`run-jobs-mixed.sh` (gap #4, second half): wrk drives `/ping-db` (256 connections, 15s)
against `JobsApp` (default pool 10), first with no jobs, then while a burst of 300
SlowJobs (3s sleep each) churns through the durable queue. Quiet window verified
(1-min load 4.83 → 5.26 across two minutes). Each run's no-jobs baseline is its own
control. Raw outputs: `benchmark/baselines/2026-06-12-jobs-mixed-{pre-123714e,post-515cdcd}.txt`.

| | Pre `123714e` | Post `515cdcd` |
|---|---|---|
| Baseline /ping-db | 10,029 req/s | 10,063 req/s |
| Contended /ping-db | 1,578 req/s (**−84%**) | 7,260 req/s (**−28%**) |
| Contended socket timeouts | **256** (= every wrk connection) | **0** |
| Burst claim observed | 50 at once | 5 at once (`started=5`, +5 per 3s wave) |

Pre-H4, the burst collapses web traffic: 50 concurrent jobs' session/claim/mark
connection acquisitions swamp the 10-connection pool and every one of wrk's 256
connections hits a timeout. Post-H4 the same burst costs 28% throughput with zero
errors and p99 76ms vs 57ms baseline — jobs degrade web traffic gracefully instead of
500ing it.

**Contamination incident (methodology, recorded like the port-8080 one):** the first
post-H4 mixed run produced `started=177` and 256 Hikari `Connection is not available…
waiting=199` failures on SlowJob rows — impossible under the 5-permit semaphore, and
initially read as an H4 bug. An isolated repro (idle burst, observed +5/3s claim ramp)
cleared the framework; the real cause was the **pre run's JVM outliving its script's
`kill`** long enough for its unbounded 50-batch poller to poach the post run's burst
from the shared table — starving its own pool (the 256 failures) and flattering the
post app's wrk number (9,627 req/s, discarded). `run-jobs-mixed.sh` now refuses to
start while another `JobsApp` is alive and escalates its exit kill to `-9` after a
20s grace. The post numbers above are from a verified-isolated re-run.

### Session/CSRF scenario — `8b495d5` (pre) vs `33180b8` (post)

First run of the new `run-session.sh` suite (see "What exists today" / gap #2):
`benchmark.SessionApp`, no database, sessions enabled. wrk sends a primed session
cookie on every request; the form POST carries a valid `_csrf` token. Before/after
jars differ **only** in the embedded framework (verified by `BraceHandler.class`
checksum — note `mvn package` without `clean` silently reuses stale shade output).
Raw outputs: `benchmark/baselines/2026-06-11-wrk-session-pre-8b495d5.txt`,
`…-session-post-33180b8.txt`. Pre ran in the quiet window (load 4.0); post ran
immediately after and shared its window with larva2's JVM startup, biasing
*against* the improvement — the direction is trustworthy.

| Test | Req/sec (pre → post) | Δ | p99 (pre → post) | Δ |
|---|---|---|---|---|
| Session Read | 74,553 → 75,332 | +1% | 44.0ms → 20.6ms | **−53%** |
| CSRF Form POST | 73,132 → 74,381 | +2% | 60.4ms → 44.5ms | −26% |
| API POST csrf(false) | 70,635 → 75,421 | +7% | 85.2ms → 20.1ms | **−76%** |

Matches H5's mechanism: throughput is CPU-saturated alongside wrk (like
plaintext), so the saved crypto shows up in tail latency rather than req/s.
csrf(false) improves most — post-H5 it performs **zero** session crypto where it
previously decrypted the cookie per request just in case. Session Read halves its
p99 (one decrypt instead of two), and the form POST combines one-decrypt with M2's
single form-body parse.

### Cumulative macro checkpoint (M-batch + M7 + M12 + M6) — framework `a62f737`

Full `run-brace.sh` suite on port 8090 (tfb-postgres on 5433). This is a **cumulative** run, not an
M6 isolation — it folds in everything since the `33180b8` checkpoint: M1 (static route index, fires
on every request), M10, M13, M16, M20, **M7** (templates eager/precompiled — though the benchmark app
runs without `brace.mode=prod`, so it's dev-mode on-demand compile *with* M6's binaryStaticContent),
**M12** (render after commit), **M6** (render to bytes). Single run.

| Test | Req/sec (vs `33180b8`) | p99 (vs `33180b8`) |
|---|---|---|
| Plaintext | 76,054 (−1%, noise) | 4.46ms (was 26.8ms) |
| JSON | 76,044 (−3%, noise) | 5.02ms (was 10.8ms) |
| Single Query | 29,418 (+8%) | 20.7ms (was 25.3ms) |
| Multiple Queries (20) | 1,759 (+4%, noise) | 229ms (was 296ms) |
| Fortunes | 28,153 (**+16%**) | 35.4ms (**−38%**, was 56.7ms) |
| Updates (20) | 1,462 (+3%, noise) | 291ms (was 301ms) |

**Caveats (read before trusting the deltas):**
- **Single sample, not the multi-run protocol.** Treat single-digit % as noise.
- **Machine at the edge of the quiet gate.** 1-min load 5.65 at fire, but 5/15-min ~7 settling from the
  JMH/build work just before. Throughput, especially CPU-saturated plaintext/JSON, is machine-sensitive.
- **The plaintext/JSON p99 collapse (≈27/11ms → 4.5/5ms) is largely a cleaner-machine artifact, not a
  framework win.** Prior checkpoints sometimes shared the box with Matt's larva2 dev server on 8080;
  this run had 8090 to itself. Don't attribute that tail drop to M1/M6 — the *throughput* on those two
  is flat (CPU-bound, shared with wrk), as every prior checkpoint noted.
- **The trustworthy signal is Fortunes:** +16% req/s and −38% p99 vs the last checkpoint, on the one
  endpoint that renders a template — consistent with M6 (render to bytes, JMH-measured −37% render time)
  plus M7/M1, though this run can't separate them. Cumulatively Fortunes is +41% req/s and 1.23s → 35ms
  p99 (−97%) vs the pre-review baseline.
- Not comparable to `benchmark/RESULTS.md` (different machine/JDK); within-review only.

### JMH allocation micro-benchmarks (M6 / gap #3) — framework `a62f737`

First run of the JMH harness (`benchmark/src/main/java/benchmark/jmh/`, run via `run-jmh.sh` →
`benchmark.jmh.JmhRunner`, GC profiler always attached). `RenderAllocBench` renders the Fortunes
page two ways — the exact before/after of M6 isolated to the render unit: a jte engine *without*
`binaryStaticContent` (StringOutput → `toString()` → `getBytes`, the three-pass materialization)
vs *with* it (Utf8ByteOutput → `toByteArray`). JSON pair mirrors `Json.of`: `writeValueAsString()
.getBytes()` vs `writeValueAsBytes()`. `gc.alloc.rate.norm` is deterministic (±0.001 B/op across
iterations); the µs/op figures were not captured on a guaranteed-quiet machine but variance was
±3%. JDK 25.

| Path | rows | Alloc pre → post (B/op) | Δ alloc | Time pre → post (µs/op) | Δ time |
|---|---|---|---|---|---|
| View render | 12 | 34,008 → 14,184 | **−58%** | 5.22 → 4.52 | −13% |
| View render | 100 | 127,187 → 107,432 | −16% | 45.19 → 28.56 | **−37%** |
| JSON serialize | 12 | 8,560 → 1,656 | **−81%** | 2.34 → 1.42 | −39% |
| JSON serialize | 100 | 67,904 → 18,448 | **−73%** | 20.59 → 11.65 | **−43%** |

Mechanism confirmations:
- **The View static-content saving is a near-constant ~19.8 KB/render** (34,008−14,184 = 19,824;
  127,187−107,432 = 19,755) regardless of row count — exactly what `binaryStaticContent` predicts:
  it eliminates the StringBuilder `char[]` (2 bytes/char) + the intermediate `String` for the
  *static* template chunks, a fixed cost. So the *percentage* win shrinks as dynamic content (the
  per-row messages, encoded either way) grows to dominate — biggest on small/static-heavy pages.
- **JSON is the larger allocation win (−73% to −81%):** `writeValueAsString` builds a full `String`
  (plus Jackson's intermediate char buffers) and `getBytes` re-encodes; `writeValueAsBytes` writes
  UTF-8 straight to a byte buffer. The per-render byte body the page cache stores also shrinks.
- **Time dropped more than the review predicted.** The finding (and the M6 discussion) framed M6 as
  "mostly GC pressure, a little CPU." The micro-bench shows a substantial *CPU* cut too — render
  −37% and JSON −43% at 100 rows — from skipping the 2-byte-per-char StringBuilder and the second
  encode pass. At the app level this is diluted by the rest of request handling (routing, DB, socket),
  so it won't move whole-request throughput by 37%, but it's more than the "secondary" CPU effect
  originally claimed. wrk's Fortunes line is where any throughput component of this would show.

`FormBindBench` quantifies **M4** — binding a 4-field validated record, the shipped (cached)
`FormBinder.bind` vs a frozen verbatim copy of the pre-M4 reflect-every-time logic, for a form with
*no* custom validator (the common case — pre-M4 throws `NoSuchMethodException` every bind) and one
*with* a validator (reflection-lookup caching only, no throw):

| Case | pre B/op → post | Δ alloc | pre ns/op → post | Δ time |
|---|---|---|---|---|
| no custom validate | 9,864 → 472 | **−95% (20.9×)** | 9,886 → 178 | **55.6× faster** |
| with custom validate | 6,000 → 424 | −93% (14.2×) | 6,440 → 165 | 39.0× faster |

Beats the finding's 10–20× estimate. The two rows separate the mechanisms cleanly: caching the
reflection lookups alone is ~39× (the with-validate row); eliminating the per-bind
`NoSuchMethodException` construction+stack-trace-fill adds the rest — it costs the pre path an extra
~3,400 ns and ~3,860 B/op (9,864−6,000), visible as the gap between the two pre rows. `gc.alloc.rate
.norm` deterministic to ±0.002 B/op.

The harness covers M6 and M4 today; the other gap-#3 units (route match M1, session decrypt H5, log
line H1, `redactPath` M8, `convertPositionalParams` L6, and H2's body buffer) can be added as sibling
`@Benchmark` classes — the runner discovers them by package.

## Gaps / what to add for this review

1. ~~**Baseline first (required)**~~ — captured above. `--latency` added to `run-brace.sh`; benchmark module repointed at the current framework version (was a stale unresolvable `0.2.0-SNAPSHOT`) and its `req.param` call updated to the current `req.queryParam` API; script default JDK moved to 25 per AGENTS.md recommendation.
2. ~~**Session/CSRF scenario (required for H5)**~~ — done: `benchmark.SessionApp` + `run-session.sh` (session-read GET, CSRF form POST, csrf(false) API POST; primes cookie + token, sanity-checks semantics, verifies the port listener is its own app). Results above.
3. **JMH micro-module (recommended):** ~~harness built~~ — `benchmark/src/main/java/benchmark/jmh/` (`JmhRunner` + GC profiler, `run-jmh.sh`; JMH wired into the existing `brace-benchmark` module via explicit `annotationProcessorPaths`, required on JDK 23+). `RenderAllocBench` verifies **M6** (View −58%/−16% alloc by page size, JSON −73%/−81%) and `FormBindBench` verifies **M4** (39–56× faster, 14–21× less alloc); results above. Still to add as sibling `@Benchmark` classes for the other allocation-sensitive units: route matching (M1), session decrypt (H5), log line (H1), `redactPath` (M8), `convertPositionalParams` (L6), and **H2** (request body buffer — needs a mock-request path, more involved than the pure-unit benches). Use `gc.alloc.rate.norm` — wrk alone can't see allocation.
4. ~~**Job-queue scenario (recommended for H3/H4)**~~ — both halves done. Claim latency (`JobsBench` + `run-jobs-claim.sh`): empty poll −99%, stats −82%, drain −98%/job. Mixed web+jobs (`JobsApp` + `run-jobs-mixed.sh`): contended /ping-db 1,578 → 7,260 req/s, 256 → 0 socket timeouts. Results + a cross-run contamination incident recorded above.
5. **Cold-start timing (for M7/M21):** trivial harness — `time` from JVM launch to first successful HTTP response on the sample app (with a Postgres testcontainer for the DB-app case), 5 runs, before/after. Plus first-render latency per template (M7) via the access log.
6. **Not adding:** CI perf gates (too flaky on shared runners — keep the protocol manual and documented), percentile tracking inside `Stats` (a feature, not a review fix; note as a candidate follow-up for the ops dashboard).
