# Token Efficiency Review Todos — 2026-06-11

Findings from a full-codebase token-efficiency review at 0.1.7-SNAPSHOT, run with
Fable 5 across five dimensions: API shapes that force boilerplate, patterns agents
actually repeat in generated apps (measured from the ai-benchmark corpus), doc
weight/staleness, CLI/tool output verbosity, and testing/scaffold ergonomics.
Severity = tokens forced per occurrence × how often an agent hits it (generated LOC
compounds: the orchestrator re-reads app code on every later feature — per the
ai-benchmark, docs quality is a ~2.5× cost lever vs ~1.3× for framework design).
Every High finding was verified directly against source (the route-overload
ambiguity by compile test). Each item notes the fix approach and a suggested model
assignment sized by how subtle the fix is, not how severe the finding is:
mechanical → Haiku 4.5, well-specified logic → Sonnet 4.6, design/structure-sensitive
→ Opus 4.8 / Fable 5.

This review formalizes and supersedes the informal 2026-06-10 pass filed straight
into `TODO.md` (Tier 1 token items + Documentation items); those entries now point
here. Benchmark evidence below is from `../ai-benchmark/work/brace-feature5-run1`
(final cumulative state: 13 controllers, 1,292 controller LOC; cumulative cost
$5.62 vs Spring $8.16 vs Hono $5.79 — Brace **lost to Hono on F5, its most
expensive round**, exactly when the re-read corpus is largest).

## High

- [x] **H1: Route registration is ambiguous for bare lambdas; read-only handlers have no cast-free form** — `Brace.java:297-401`, `RouteGroup.java:135-183`
  - Verified by compile test: `app.get("/a", (req, db) -> ...)` fails with "reference
    to get is ambiguous" (SessionHandler vs ReadDbHandler both match 2-arg lambdas;
    FullHandler vs ReadFullHandler both match 3-arg). The raw multi-arg overloads of
    `get/post/put/delete` are dead API for lambdas — only casts or the typed names
    (`getDb`, `getFull`, …) work, and read-only has no typed name. Benchmark app:
    **55 casts** (27 `(ReadDbHandler)` + 28 `(DbHandler)`) on effectively every
    route registration, plus one compile-error round-trip whenever an agent writes
    the natural untyped form first.
  - **Fix:** add typed read variants to `Brace` and `RouteGroup`:
    `getRead/postRead/putRead/deleteRead(String, ReadDbHandler)` and
    `getReadFull/...(String, ReadFullHandler)` (mechanical mirror of `getDb` at
    `Brace.java:405`). Document `get/post/put/delete` as Handler-only and consider
    deprecating the uninvokable raw overloads. Sweep BRACE-AGENTS.md / README /
    scaffold examples to typed methods everywhere (no cast examples remain).
  - **Tests:** compile-level registration of each handler shape via typed names;
    `getRead` produces a read-only invoker.
  - **Model: Sonnet 4.6** — core is mechanical but the deprecation decision and doc
    sweep need consistency.

- [x] **H2: JSON request bodies get no declarative validation, and malformed JSON → 500** — `Request.java:174-180,315-321`, `FormBinder.java:10`
  - `req.form()` binds form-encoded body + query params only; the `@Required`/`@Min`/…
    annotation vocabulary is unreachable from JSON. `req.bodyAs()` wraps parse
    failures in `RuntimeException` → generic 500, so a correct JSON endpoint needs a
    try/catch just to 400. Benchmark: TalkController duplicates a ~73-line validation
    block verbatim between POST and PUT (28% of the file); app-wide 59 hand-rolled
    `Result.error(400, ...)` checks and 19 find/null/`Result.error` FK-existence
    triples (~57 lines).
  - **Fix:** `req.jsonForm(Class<T>) : Form<T>` (TODO.md filed this as `bodyValid`;
    pick one name and alias nothing): parse body as JSON object → coerce scalars →
    delegate to `FormBinder.bind` so annotations + `validate(Errors)` run
    identically; unparseable/non-object body → `Form` with a top-level `_body` error,
    never an exception. Document the reject idiom
    (`if (form.hasErrors()) return Result.json(Map.of("errors", form.errors()), 422)`)
    and the style rule "extract validation shared between create and update into one
    static helper" (which also covers cross-entity rules `jsonForm` can't).
  - **Tests:** valid JSON binds + validates; missing required field → `hasErrors()`;
    malformed JSON → `hasErrors()` not 500; numeric coercion from JSON numbers and
    strings.
  - **Model: Sonnet 4.6** — well-specified; the JSON→String coercion rules need care.

- [ ] **H3: Split BRACE-AGENTS.md into a dev core + ops reference; dedup with agent-ops-guide.md** — `BRACE-AGENTS.md` (1,119 lines ≈ 7.8k tokens), `docs/agent-ops-guide.md` (247 lines)
  - ~300 lines (~27%) is strictly operational (ops runbook block `:739-1010`, scaling
    `:680-689`, cache fleet semantics `:498-508`) — paid every session by agents
    *writing* code. `docs/agent-ops-guide.md` already exists as the destination and
    **already duplicates** the CLI table, storage/retention, post-deploy workflow,
    and keypair sections — two of which have diverged (see H4; the guide also lacks
    `brace check` and leads with `brace status`, the exact anti-pattern
    BRACE-AGENTS.md:855 forbids).
  - **Fix:** move the ops-auth key workflows, CLI/HTTP endpoint tables,
    storage/retention, health-check + runbooks, `/ops/status` JSON shape, scaling,
    and multi-instance observability sections into `docs/agent-ops-guide.md`,
    deduplicating on arrival and bringing the guide current (`brace check` first).
    Keep the dev reference (routing → testing → config) plus a ~6-line pointer
    ("operating in production? read docs/agent-ops-guide.md"). Target ≤750 lines
    directly, ~600 with prose compression. Ship the guide in the jar alongside
    BRACE-AGENTS.md if M10 lands.
  - **Tests:** none (docs); verify no section is lost (diff of section headings).
  - **Model: Fable 5 / Opus 4.8** — structure-sensitive; the dedup-merge needs judgment.

- [x] **H4: BRACE-AGENTS.md ops-keypair section is actively wrong — contradicts the shipped CLI** — `BRACE-AGENTS.md:785-799` vs `CliOps.java:25-90`
  - The doc claims `brace ops keypair` "prints the private key once to stdout (it
    does **not** write `ops-private.key`)", tells users to hand-copy keys, and warns
    it "always generates a fresh pair and silently appends another authorized
    entry." All three claims are false: `CliOps` writes `ops-private.key`
    (owner-only perms, refuses to overwrite) and replaces the same-label
    `ops-authorized-keys` entry in place. `agent-ops-guide.md:15-53` describes
    current behavior correctly. An agent following BRACE-AGENTS hand-constructs key
    files and avoids the safe idempotent command — a guaranteed wrong-procedure loop
    on every key-rotation/new-machine task. Also: `TODO.md` "Ops key UX gaps" still
    requests the now-shipped `--save` behavior (the `whoami`/`authorize` sub-items
    remain open).
  - **Fix:** rewrite the section to match `CliOps` (or fold into H3's move and keep
    only the guide's correct copy); prune the shipped half of the TODO entry.
  - **Model: Sonnet 4.6** — must read source to write truth.

- [ ] **H5: `brace errors` agent default dumps every error's full stack trace, headers, and request detail** — `ErrorStore.java:162-196`, `OpsHandler.java:497-508`, `CliCommands.java:38-45`
  - `/ops/errors` returns all unresolved rows (no LIMIT) each carrying `stackTrace`,
    `requestDetail`, `queriesBefore`, `requestHeaders`; in non-TTY (= agent) mode the
    CLI prints the full payload pretty-printed, while the human TTY mode gets a
    compact table. One trace ≈ 500 tokens; 10 errors ≈ 6–9k tokens per call,
    re-read every fix-loop iteration. Exactly inverted.
  - **Fix:** default JSON = summary per error (`id, errorType, message, route,
    occurrenceCount, firstSeen, lastSeen` + new `at` first-app-frame field); full
    detail via `brace errors <id>` / `--full` backed by `/ops/errors/{id}` (or
    `?full=true`) so the dashboard keeps working. Compact output (see M12).
  - **Tests:** summary shape has no `stackTrace`; detail endpoint returns it;
    dashboard still renders.
  - **Model: Sonnet 4.6.**

- [ ] **H6: `/ops/status` payload bloat + `brace status` always reports "Errors 0" (broken exit-code contract)** — `OpsHandler.java:226-418`, `CliCommands.java:163-200`
  - Status embeds up to 50 recent errors **with full stack traces**, 60 per-minute
    timeseries snapshots, 20+20 JFR hot-method/allocation entries, and a hardcoded
    all-zeros `cpu`/`gc`/`profiling` stub when no profiler — tens of KB per poll,
    pretty-printed. Verified bug: `status()` never emits `errors.count`, but
    `CliCommands.java:172` reads it → `brace status` always prints "Errors 0" and
    exits 0 even with unresolved errors.
  - **Fix:** `errors` → `{count: N, recent: [top 5: type, message, route, count,
    lastSeen]}` with **no stackTrace** (fixes the count bug); move `timeseries` and
    `jvm.profiling` behind `?include=timeseries,profiling` (dashboard requests them
    explicitly); drop the zero stubs when no profiler (`brace check` tolerates
    absence — it reads via `.path()` with defaults). Migration-guide entry: the
    `/ops/status` shape change.
  - **Tests:** status with unresolved errors → CLI exit 1 and correct count; check
    still passes without profiler; dashboard timeseries still renders.
  - **Model: Fable 5 / Opus 4.8** — API-shape change rippling across dashboard,
    `brace check`, and the migration guide.

- [ ] **H7: `brace test` pipes raw JUnit ConsoleLauncher output: per-test tree, ANSI codes, full framework stack traces** — `BuildCommands.java:177-203`
  - Spawns ConsoleLauncher with only `--disable-banner`, `inheritIO()`. Default tree
    prints a line per test (~120+ lines green for 100 tests); piped output keeps
    ANSI escapes (ConsoleLauncher doesn't TTY-detect); failures carry 30–60 frames
    of which 1–3 are app frames. Re-read every fix-loop iteration. (The seed
    TODO framed this as Surefire — wrong: there is no Maven in the loop, we own the
    invocation, which makes this much easier.)
  - **Fix:** step 1 (2 lines): when `System.console() == null` or `--quiet`, add
    `--details=summary --disable-ansi-colors`. Step 2: capture output instead of
    `inheritIO()`; on failure emit one line per failure
    (`Class.method() — AssertionError: expected <a> but was <b> (TestClass.java:42)`)
    keeping only frames in the project's packages (derivable from the source tree
    already walked by `findJavaFiles`); final `N passed, M failed in Xs` line;
    `--verbose` restores passthrough; TTY behavior unchanged. Exit code already
    propagates — keep it.
  - **Tests:** extend `tests/cli/` e2e: failing test produces one-line failure with
    app frame; passing run ≤ ~5 lines; exit codes preserved.
  - **Model: Sonnet 4.6** (step 1 alone is Haiku-mechanical).

- [ ] **H8: `brace compile` passes raw javac diagnostics: snippet+caret per error, no dedupe, repeated across files** — `BuildCommands.java:67-78`
  - `compiler.run(null, null, null, args)` → stock javac stderr: 3–4 lines per
    diagnostic, identical messages repeated per file (a renamed method used in 8
    files = 8 near-identical blocks), up to javac's 100-error cap. 1–3k tokens per
    broken-refactor compile, re-read every iteration — and the dev watch loop
    (`BuildCommands.java:130-139`) replays it on every save.
  - **Fix:** switch to `compiler.getTask(...)` with a `DiagnosticCollector`; emit one
    line per diagnostic (`path:line: error: message` — drop snippet/caret); dedupe by
    (kind, message) with `(+N more at Foo.java:12, …)`; errors before warnings; cap
    ~25 with `… and N more`; final `✗ N errors, M warnings` line. Apply to
    `compile`, `compileTests`, and the dev loop identically.
  - **Tests:** e2e: project with the same error in 3 files → 1 deduped line + count;
    line-number accuracy.
  - **Model: Sonnet 4.6.**

## Medium

- [ ] **M1: No session-aware before-middleware — login guards can't be factored out** — `Middleware.java:7-10`, `BraceHandler.java:217-257`, `BRACE-AGENTS.md:117-118`
  - `Middleware.Before` is `Result handle(Request)`; the session is constructed after
    before-middleware runs, so an auth guard can only live inside each handler
    (2–3 lines × every protected route, and it forces Session/Full handler shapes on
    routes that don't otherwise need them). The documented `isAdmin(req)` hand-wave
    requires manually decrypting the cookie.
  - **Fix:** additive `Middleware.BeforeSession { Result handle(Request, Session) }`
    + `app.before(pattern, BeforeSession)`. BraceHandler builds the session once
    (lazily) when any BeforeSession matches and **passes the same instance** to the
    handler so mutations and cookie write-back stay coherent — that identity
    invariant is the subtle part. Convenience on top:
    `app.requireSession("/admin/*", "userId", "/login")`.
  - **Tests:** anonymous → redirect; authenticated passes; session mutated in
    middleware survives to handler and Set-Cookie; CSRF interplay unchanged.
  - **Model: Fable 5 / Opus 4.8** — touches the session/CSRF/cookie write-back
    lifecycle (the security review's M5/M6 region).

- [x] **M2: `db.findOr404` + make it the canonical lookup in docs** — `Database.java`, `Result.java:61-64`
  - `Result.notFoundIfNull` exists and is documented, but the benchmark agent never
    discovered it: **32×** `find`/null-check/`Result.notFound()` preambles (~96
    lines) in the final app (the earlier TODO estimate of ~10× undercounted 3×).
  - **Fix:** `db.findOr404(Class<T>, Object id)` and
    `db.queryOneOr404(Class<T>, String where, Object...)` throwing
    `NotFoundException`; make it the lookup shown in every BRACE-AGENTS.md /
    scaffold example.
  - **Tests:** missing id → 404 end-to-end.
  - **Model: Haiku 4.5.**

- [x] **M3: Hand-built `LinkedHashMap` response shapes — add `Json.obj(...)` + bless records** — benchmark: 16 `LinkedHashMap` builds ≈ 120 lines
  - Worst ratio: a single-key response costs 3 lines (`new LinkedHashMap` / `.put` /
    `Json.of`). Agents avoid `Map.of` for valid reasons (null values rejected,
    unstable order).
  - **Fix:** `Json.obj("talkId", id, "averageRating", avg, ...)` — ordered,
    null-tolerant, odd-arity throws. Doc rule: prefer a 1-line local record for
    named/reused shapes (self-documents schema, declaration-order serialization);
    `Json.obj` for one-offs.
  - **Tests:** order preserved; null values kept; odd arity throws.
  - **Model: Haiku 4.5** (helper); the doc guidance rides M5.

- [x] **M4: No ordering/pagination story in the query API or docs** — `Database.java:71`, BRACE-AGENTS.md §Database
  - *Implemented as a distinct `db.queryPage(Class, hqlWhere, limit, offset, params...)`
    rather than the `db.query` overload proposed below: with varargs, existing calls like
    `db.query(Post.class, "a = ? AND b = ?", 1, 2)` would silently resolve to the new
    overload and reinterpret the two bind params as limit/offset. ORDER-BY-in-fragment
    pinned with tests; docs + migration-guide entry added.*
  - `"published = true ORDER BY id DESC"` already works (where-fragment is
    concatenated), but nothing documents it — zero ORDER BY mentions anywhere — so
    the benchmark app sorted in memory 5× and loop-summed aggregates (18-line stats
    endpoint vs Hono's 10-line `SELECT AVG/COUNT`). No limit/offset parameters
    exist.
  - **Fix:** pin ORDER-BY-in-where with a test (it currently works by accident of
    concatenation); add `db.query(Class<T>, String hqlWhere, int limit, int offset,
    Object... params)` via `setMaxResults/setFirstResult` (no string surgery); doc
    examples for ORDER BY, limit/offset, and one aggregate-projection via `db.hql`.
  - **Tests:** ORDER BY honored; page 2 slice correct; aggregate example compiles.
  - **Model: Sonnet 4.6.**

- [ ] **M5: "Token-minimizing patterns" doc section — canonical idioms agents copy** — folds the former TODO style-guidance item
  - The benchmark app re-derived verbose forms of things the framework already has:
    15 `db.query(...).isEmpty()` existence checks (where `existsBy`/`count(where)>0`
    is 1 line; 11 of 15 are two-field — document the `count` idiom or add
    `db.exists(Class, hqlWhere, params...)`), N+1 find-per-item loops where
    `db.queryIn` + `Collectors.toMap` is shorter, 4× 12-line notification
    construction blocks (doc convention: all-args convenience constructor / static
    factory for event-shaped entities), 22 manual field-copy lines in PUT handlers
    (the `entity.apply(form)` convention covers it; do NOT add a reflective
    copier — mass-assignment risk, and update semantics differ per entity).
  - **Fix:** one "Common Patterns / token-minimizing style" pass over BRACE-AGENTS.md:
    extract-shared-validation rule, findOr404 canonical, records for responses,
    existsBy/queryIn/ORDER BY/aggregate examples, batch-fetch pattern. Optional tiny
    API: `db.exists(Class, where, params...)`.
  - **Model: Sonnet 4.6** — must choose canonical idioms consistently.

- [ ] **M6: TestApp surface gaps: headers, session variants, CSRF, JSON assertions** — `TestApp.java:41-129`, `TestResponse.java:39-45`
  - No custom headers (bearer-token APIs untestable via the harness → agents
    hand-roll `HttpClient`, ~10 lines/class); `get`/`postJson`/`put`/`delete` lack
    session variants (`post(path, params, session)` exists at `:67`); no CSRF
    helper — first session-enabled POST test 403s, and the framework's own tests
    either regex-scrape the token (`CsrfPlainHandlerTest.java:104-112`) or register
    routes `.csrf(false)`; JSON assertions limited to `bodyAs(Class)` — the
    framework's own tests model fragile `body().contains(...)`.
  - **Fix:** header-accepting overloads or a small builder
    (`app.request("GET", path).header(...).session(s).send()`); session variants for
    the remaining verbs; `postWithCsrf(path, params, session)` using
    `Csrf.ensureToken` (same package — no scraping); `TestResponse.json()` →
    `JsonNode` + `bodyAs(TypeReference<T>)`. Keep raw `post` so CSRF regressions
    stay testable (don't silently auto-inject). Document all of it in the Testing
    section (currently CSRF-silent).
  - **Tests:** postWithCsrf to a CSRF-on route succeeds; same without token → 403;
    bearer header reaches handler; typed list deserialization.
  - **Model: Sonnet 4.6.**

- [ ] **M7: Scaffold: route wiring isn't reusable by tests; no test idiom to copy** — `ProjectGenerator.java:97-126,142-173`
  - Generated `App.java` wires routes inline in `main()` (which reads config and
    starts the real server), so the scaffold test — and the documented idiom
    (BRACE-AGENTS.md §Testing) — re-register routes by hand: O(routes) duplicated
    lines per test class + drift. The benchmark agent independently invented
    `static void register(Brace app)` per controller, with no framework guidance.
  - **Fix:** scaffold `public static void routes(Brace app)` called from `main()`;
    scaffold test calls `App.routes(app)`; show a 5-line `TestData` factory helper
    in the scaffold test so agents copy a pattern instead of inventing one; teach
    both in BRACE-AGENTS.md §Testing.
  - **Tests:** scaffold e2e (`tests/cli/`) still green; generated test passes.
  - **Model: Sonnet 4.6.**

- [ ] **M8: Scaffold pom: `mvn test` silently runs zero tests; Dockerfile can't work as shipped** — `ProjectGenerator.java:50-84,250-260`
  - Generated pom has no `<build>` section → super-POM surefire 2.12.4 → JUnit 5
    tests are ignored: "Tests run: 0 … BUILD SUCCESS" — a false green agents trust
    (they reflexively run `mvn test`). Dockerfile does `COPY target/*.jar` +
    `java -jar`, but the pom builds a thin jar with no manifest/deps → first deploy
    fails and costs a debugging session.
  - **Fix:** pin surefire ≥3.2 in the generated pom; make the packaging story real
    (shade plugin or jar-plugin manifest + dependency copy, or a multi-stage
    Dockerfile that builds what the pom actually produces).
  - **Tests:** e2e: `mvn test` in a fresh scaffold runs the generated test;
    `docker build` (or at least `mvn package` + `java -jar`) works.
  - **Model: Sonnet 4.6.**

- [x] **M9: `app.generateClaudeMd()` omissions and stale facts** — `ClaudeMdGenerator.java`
  - Omits: the entire `Http` client (largest gap — agents reach for raw
    `HttpClient`), `Assets.url()` fingerprinting (seed item), `Url.to()`,
    `Log.debug/info/error` (shows only `Log.event`), `Redirect.toLocal`,
    `brace check` (the mandatory ops entry point), `/ops/logs`/`/ops/cache`/
    `/ops/regressions` rows. Stale: links `github.com/matth/brace` (should be
    `larvalabs`); CSRF line omits PATCH. Bloat: two overlapping ops sections.
    Emitted into every project, read every session.
  - **Fix:** one-line capability entries for the omissions; fix link + PATCH; merge
    the ops sections.
  - **Tests:** `ClaudeMdGeneratorTest` asserting presence of the new entries.
  - **Model: Sonnet 4.6.**

- [ ] **M10: `brace agents-md` — version-matched agent docs refresh** — `pom.xml:205-212`, `ProjectGenerator.java:265-268`, `src/assembly/distribution.xml`
  - More is shipped than the seed assumed: BRACE-AGENTS.md is packaged in the jar at
    `/brace/BRACE-AGENTS.md` and written into every `brace new` project. The gap is
    **post-upgrade refresh**: after bumping `<brace.version>` the project copy goes
    silently stale — the exact drift class (`req.intParam` → `queryInt`) the
    benchmark paid 2.5× for. No `brace agents-md` command exists; the Upgrading
    section and migration guides never say "refresh your copy"; the dist zip ships
    it only as jar-internal bytes.
  - **Fix:** `brace agents-md` extracts `/brace/BRACE-AGENTS.md` from the resolved
    jar of the pinned version (overwrites project copy; `--stdout` option); add the
    refresh step to the Upgrading section and the migration-guide convention in
    AGENTS.md; optionally a dist `<fileSet>`.
  - **Tests:** e2e: command rewrites the file from the jar.
  - **Model: Sonnet 4.6.**

- [x] **M11: Dev-loop 500s carry no location — cheapest next step is H5's 6–9k-token dump** — `BraceHandler.java:408-446`, `Log.java:35-47`
  *(Done: `at` field with first-app-frame heuristic that doesn't filter `org.*` app
  packages. The optional dev-gated 500 body was NOT pursued — deferred.)*
  - HTTP body is `"Internal Server Error"`; the console `http.error` line carries
    only exception class + message — zero frames, not even file:line. Three tool
    reads to learn one location.
  - **Fix:** add an `at` field to the `http.error` log event: first frame outside
    `com.larvalabs.brace`/`java.*`/`jakarta.*`/`org.*` (e.g.
    `app.controllers.PostController.show(PostController.java:42)`). Optionally
    (design-gated, strictly dev-mode): type+message+app-trimmed trace in the 500
    body.
  - **Tests:** thrown handler exception → log event contains app frame.
  - **Model: Haiku 4.5** for the `at` field; Fable/Opus if the dev-mode body is
    pursued.

- [ ] **M12: Agent-mode CLI JSON is pretty-printed everywhere** — `CliOutput.java:67-73`
  - `errors`, `status`, `check`, `init` all print `writerWithDefaultPrettyPrinter()`
    output in JSON (non-TTY) mode — ~15–30% extra tokens for zero agent value. Only
    `logs` does it right (compact per line).
  - **Fix:** JSON mode emits compact (one line, or NDJSON for list-shaped outputs);
    human/`--pretty` unaffected.
  - **Tests:** update `tests/cli/` expectations.
  - **Model: Haiku 4.5.**

- [ ] **M13: Startup/restart log noise from third-party logging is unmanaged** — `pom.xml` (no slf4j provider), `Brace.java:773-794`
  - No slf4j binding → Jetty prints the "No SLF4J providers" warning; Hibernate/
    Flyway fall back to JUL's two-line-per-record console handler (banners, dialect
    info, migration progress) — replayed on every `brace dev` restart the agent
    reads. (Brace's own banner is compact and the route list is genuinely useful —
    keep it.)
  - **Fix:** configure JUL at `Brace.app()`: single-line format, default WARNING for
    `org.hibernate`/`org.flywaydb`/`com.zaxxer.hikari` (overridable via
    `log.level.*` config); ship `slf4j-jdk14` so Jetty routes to the same sink.
    Verify actual output by booting the sample app first.
  - **Tests:** boot test asserting no INFO-level third-party lines on stdout.
  - **Model: Sonnet 4.6.**

- [x] **M14: AGENTS.md / README carry stale size+test counts and an incomplete core-types table** *(completed the table rather than trimming; also de-precisioned README's "~20 core types" — the table now has ~40 rows)* — `AGENTS.md`, `README.md:13,502`
  - "~4,000 lines" vs 15,275 actual; "410 tests" and "409 tests" (mutually
    inconsistent in the same file) vs 822 `@Test` methods. Core-types table omits
    ~17 shipped public types (`Cache`, `Storage`, `Http`, `RateLimiter`, `Assets`,
    `Url`, `WsContext`, `UploadedFile`, notifiers, `RegressionTracker`,
    `ErrorStore`, `OpsAudit`, `OpsKeys`, …). Wrong priors mislead every scoping
    decision an agent makes in this repo.
  - **Fix:** de-precision the counts ("~15k lines incl. CLI"); add missing rows or
    trim to truly-core + a pointer.
  - **Model: Haiku 4.5** with this list as spec.

- [x] **M15: README benchmark numbers are stale and overstate the trend** *(also fixed the ai-benchmark repo link: `mattonfoot` → `larvalabs`)* — `README.md:19,28`
  - Claims $5.43 cumulative / F5 $1.36 / 41% / "the gap widens as the codebase
    grows"; reconciled ai-benchmark data: cumulative **$5.62** vs $8.16 (**31%**),
    F5 **$1.54** vs $2.29 (**33%**), and the saving is "fairly stable around a
    third," not widening. Exact edits per the docs-dimension report; F1–F4 rows
    already match. (Cross-filed in `../ai-benchmark/TODO.md`.)
  - **Model: Haiku 4.5.**

- [x] **M16: FormBinder type coverage stops at primitives** — `FormBinder.java:45-71,116-124`
  - No enum / `LocalDate` / `Instant` / `BigDecimal`: unknown types fall through to
    the raw string and explode reflectively → 500. `<input type="date">` forces
    String fields + ~4 lines of hand-parse/validate per field.
  - **Fix:** extend `convert` with enum (`Enum.valueOf` → "must be one of …" field
    error), `LocalDate.parse`, `Instant.parse`, `BigDecimal` — failures become field
    errors, never exceptions.
  - **Tests:** record with LocalDate + enum binds; garbage → field error, not 500.
  - **Model: Haiku 4.5** (pattern established in the same method).

## Low

- [x] **L1: Docs/javadoc reference a `req.param()` that doesn't exist** — `BRACE-AGENTS.md:675`, `RateLimiter.java:87`, 6 `Database.java` javadocs
  - Copying the documented rate-limit example is a compile error. Fix: correct the 8
    references to `formParam`/`queryParam` (minimum), or add a unified
    `req.param(name)` with pathParam → queryParam → formParam precedence (token-
    efficient option; needs a precedence test). **Model: Haiku 4.5.**
    *Fixed with option (a): the repo deliberately replaced a unified accessor with
    source-specific ones ("eliminates ambiguity", Tier 1), so reintroducing
    `req.param` would undo that decision. Historical docs left as-is.*

- [ ] **L2: Defaulted numeric accessors throw on unparseable input** — `Request.java:61,83-86`
  - `queryInt("page", 1)` with `?page=abc` → NumberFormatException → 500. The
    *defaulted* variants should return the default (documented behavior change;
    migration-guide entry); non-defaulted keep throwing. **Model: Haiku 4.5.**

- [ ] **L3: 404s carry no route context in dev** — `Result.java:57-59`, `BraceHandler.java:232`
  - Dev-mode only: list same-method registered patterns sharing a prefix
    (`Not Found: GET /user/42 — registered: GET /users/{id}, …`, cap 5). Production
    unchanged (route disclosure). **Model: Sonnet 4.6.**

- [ ] **L4: CLI nits** — `Cli.java:84`, `CliCommands.logs`, `ProjectGenerator.java:287`
  - Unknown command prints full usage and **exits 0** → typos look like success; fix
    to `Unknown command: <x>` + exit 1. `brace logs` lacks `--limit` passthrough
    (server supports `?limit=`, default 200). `brace new` next-steps text says
    `mvn compile exec:java -D...` instead of `brace dev`. **Model: Haiku 4.5.**

- [ ] **L5: README API drift** — `README.md:46,178,235,434,457`
  - CSRF described as "POST/PUT/DELETE" (PATCH missing, twice); ":46" points readers
    to AGENTS.md as "the complete framework reference" (wrong file — that's the
    contributor doc; the reference is BRACE-AGENTS.md); two examples still use cast
    style while ":230" advertises typed methods. **Model: Haiku 4.5.**

- [ ] **L6: README duplicates ~290 lines of BRACE-AGENTS.md** — `README.md:193-486`
  - The duplicated copy is where the drift in L5 lives (the canonical copy didn't
    rot). Decide: trim README to pitch + quick start + install, pointing at
    BRACE-AGENTS.md; or keep and add "README API sections" to the release
    checklist. **Model: Fable/Opus for the trim decision; Haiku to execute.**

- [x] **L7: BRACE-AGENTS.md currency sweep (0.1.7 behaviors + undocumented security helpers)** *(also documented `Log.warn`, which exists alongside debug/info/error; SECURITY.md upload-limits framing fixed in place and a new "Open Redirects" section added with a TOC entry)* — various
  - Missing/stale: the 413 cap now applies to *all* non-multipart bodies (docs frame
    it upload-only); server-enforced session expiry absent from §Sessions;
    `Url.to()` absent; Logging section shows only `Log.event` (no
    `debug/info/error`); XFF rightmost-untrusted semantics — one-line pointer to
    SECURITY.md; **`Redirect.toLocal` documented nowhere agents look** (only the
    migration guide) and SECURITY.md has no open-redirect section — agents keep
    writing `Redirect.to(req.queryParam("next"))`, the exact pattern the API was
    built to kill. **Model: Haiku 4.5** with this list as spec.

- [ ] **L8: Migration guides: add a machine-scannable checklist table; compress the 872-line 0.1.7 guide** — `docs/migrations/`
  - Shape is right (breaking-verdict-first, before/after); weight is per-upgrade,
    not per-session. Add a top table (change | breaking? | action | anchor),
    compress historical narration (40-line Flyway 10 background), add the
    "refresh BRACE-AGENTS.md" step once M10 lands. **Model: Sonnet 4.6.**

- [ ] **L9: Sample app under-delivers as "the API demonstration"** — `sample/`
  - 34 lines: no DB/views/forms/sessions/tests; hardcoded `0.1.0` version strings;
    the middleware demo registers a route behind an unconditional 401 (models an
    unreachable route). Decide: expand into a small CRUD exemplar (entity + view +
    form + TestApp test) or relabel in AGENTS.md as a minimal smoke app pointing at
    BRACE-AGENTS.md. **Model: Fable/Opus if expanding; Haiku if relabeling.**

- [ ] **L10: Shared H2 URL across test classes; `resetDatabase()` is H2-only** — `Brace.java:857`, `TestApp.java:153-175`
  - Every TestAppBuilder defaults to `jdbc:h2:mem:test;DB_CLOSE_DELAY=-1` → data
    leaks across classes in one JVM unless each resets; `resetDatabase` uses
    H2-only SQL (fails on the PG/Testcontainers tier). Fix: per-builder unique DB
    name; document/branch the reset. Costs surface as flaky-test debugging tokens.
    **Model: Sonnet 4.6.**

- [ ] **L11: Multi-value form/query params are unrepresentable** — `BraceHandler.java:607-616`, `Request.java:323-337`, `FormBinder.java:10`
  - Last-value-wins maps: `<select multiple>`/checkbox groups force hand-parsing
    `req.body()`. Fix (additive): `req.queryParams(name)`/`req.formParams(name)` →
    `List<String>`; later, `List<String>` components in FormBinder.
    **Model: Sonnet 4.6.**

## Considered and rejected

- **Auto entity↔form mapper / `db.copyFields`** — reintroduces mass-assignment risk
  the explicit `apply(Form)` convention deliberately prevents; benchmark controllers
  even disagree on null-handling semantics within one app. Doc convention only.
- **Stringly-typed DTO field whitelists** (`Json.of(entity, "id", "email")`) —
  silently drifts from schema; records remain the blessed shape (`Json.warnIfEntity`
  already guards the failure mode).
- **Generic test fixture/factory API** — not worth the surface for public-field
  entities; the scaffold's `TestData` helper pattern (M7) covers it.
- **TestApp's unconditional `new Mailer(null)`** (existing TODO) — currently free:
  the constructor touches no jakarta.mail classes (lazy in `sendSmtp`) and
  jakarta.mail is a hard dependency anyway. Fold into a future dependency-slimming
  pass.

## Cross-repo follow-ups (ai-benchmark)

Tracked in `../ai-benchmark/TODO.md`, not here: the stale duplicated CLAUDE.md
context blocks and broken Brace route listing in the harness; the F1–F5 re-run
after those fixes. New from this review: the benchmark's `brace-template/CLAUDE.md`
(207 lines) actively teaches the cast idiom and the 3-line 404 preamble and omits
`notFoundIfNull`, the `*By` helpers, `queryIn`, typed route methods, and ORDER BY —
rewrite it with the canonical idioms after H1/H2/M2/M5 land, before the re-run.

## TODO.md sync notes

- The four Tier 1 token items (`req.bodyValid`, typed read routes, `findOr404`,
  agent-shaped output) → H2, H1, M2, H7+H8 here.
- The four Documentation items (BRACE-AGENTS split, `brace agents-md`, style
  guidance, README numbers) → H3, M10, M5, M15 here.
- "Ops key UX gaps": the `--save` half shipped (CliOps writes + refuses overwrite +
  replaces by label); `whoami`/`authorize` remain open. H4 fixes the doc that still
  describes the old behavior.
- The findOr404 occurrence count is 32×, not ~10× as originally filed.
