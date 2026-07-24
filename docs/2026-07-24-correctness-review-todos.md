# Correctness Review: 2026-07-24 (Opus 5)

## Summary

First **Correctness** review — a fourth category alongside Security, Token Efficiency, and
Runtime Performance (see `docs/reviews/README.md`). It looks for plain bugs: wrong results,
silently dropped data, unbounded growth, work that is lost rather than retried, and API
behavior that contradicts its own documentation. It is not a security or performance pass;
where a finding also has a security or perf flavor, that is noted but is not the reason it
is listed.

25 findings: 4 High, 12 Medium, 9 Low. Every High and most Mediums were reproduced against
a running app with a throwaway probe test, not just read — the reproduction is recorded
inline as "Confirmed:".

Branch: `claude/correctness-review-ey31yz`. One commit per finding,
`fix(correctness): <ID> <summary>`, each commit ticks its checkbox here and passes
`mvn test`. User-visible changes get migration-guide entries per AGENTS.md.

Dimensions swept: request lifecycle (`BraceHandler`, `Request`, `Result`, `Router`, `Route`);
sessions/CSRF/forms/views; database wrapper and HQL rewriting; background work (`JobScheduler`,
`JobPoller`, `Counters`); caching and rate limiting; observability (`Stats`, `Log`, `Redactor`,
`ErrorStore`); outbound clients (`Http`, `Storage`, `Mailer`); WebSockets; app wiring and
lifecycle (`Brace`, `TestApp`, `DatabaseFactory`).

**Not covered** (deliberate scope cut, candidates for a follow-up pass): the CLI
(`Cli*`, `BuildCommands`, `ProjectGenerator`, `Toolchains`), `OpsHandler`/`OpsDashboard`
rendering, `JfrProfiler`, and the Flyway migration SQL itself.

---

## High

- [ ] **H1: Per-route stats are keyed by the concrete URL, so the `routes` map grows without bound**
  - Severity: High. Files: `BraceHandler.java:422,437,457`; `Stats.java:53-73` (`recordRequest` vs `recordRequestPattern`).
  - Every request-recording call site uses `stats.recordRequest(method, path, …)` — the **raw path**
    variant. `Stats.recordRequestPattern`, added by the runtime-performance review's H7 fix
    *specifically* to bound this map by the route table, has **no caller in `src/main`**; only
    `StatsTest` uses it. Its own Javadoc says matched requests go through it. They don't.
    `Redactor.redactPath` only collapses high-entropy segments, so ordinary ids and slugs survive:
    one `ConcurrentHashMap` entry (plus two `LongAdder`s) per distinct URL ever requested, for the
    life of the process. `/ops/routes` degrades from a route table into a URL dump.
  - Confirmed: three requests to `/users/{id}` produced
    `routeStats().keySet() == [GET /users/1, GET /users/2, GET /users/3, …]`.
  - Fix: on the matched path record `match.route().pattern()` via `recordRequestPattern`; keep raw-path
    recording only for the unmatched-route 404 (where there is no pattern). The `NotFoundException`
    catch has a match — thread the pattern through, or record before rethrowing.
  - Model: smaller model OK (mechanical), but add a test asserting the map stays at one entry across
    N distinct ids — that is the regression this fix exists to prevent, and it silently reverted once.

- [ ] **H2: Every response that short-circuits before the handler is invisible to stats and the request log**
  - Severity: High. Files: `BraceHandler.java:210,223,245,266,279,283,329` (early `return true`) vs the
    recording sites at `:419-424`, `:431-439`, `:456-461`.
  - Stats and `Log.request` run only on the success path and in the two catch blocks. Every other
    exit — before-middleware short-circuits (**rate-limiter 429s**, auth redirects), session-middleware
    short-circuits, **CSRF 403s**, 413 payload-too-large, static-file serves, and the
    **unmatched-route 404** at `:283` — returns without recording anything. The signals most wanted
    during an incident are exactly the ones missing, and `/ops/status` under-reports total traffic.
  - Confirmed: with a `before("/blocked", …)` returning 429 and a GET to an unregistered path, after
    7 handler requests + 1 blocked + 1 unmatched, `statusCodeCounts()` was `{200=7}` — no 429, no 404.
  - Fix: record once at the write-back choke point (`writeResult(result, response, callback, session,
    csrfOnlySession)`), which already sees every exit, instead of at each return site. Pass the
    matched route pattern (H1) and the db handle through, or capture them in fields on a per-request
    context. Keep the existing behavior that a null `Stats` disables both stats and logging.
  - Model: frontier (touches the choke point every path funnels through; must not double-count the
    success path, and must not start logging static-asset requests without a deliberate decision).

- [ ] **H3: Path parameters are never URL-decoded**
  - Severity: High. Files: `BraceHandler.java:174` (`getHttpURI().getPath()`), `Route.java:70-78`,
    `BraceHandler.java:569-658` (static files).
  - The router matches against Jetty's **raw**, still-percent-encoded path and copies regex groups
    straight into `pathParams`. Query params and form params *are* decoded (`Request.scanPairs`), so
    the same string round-trips differently depending on where it rides. Any route whose parameter is
    not a bare integer — slug, email, filename, tag, title — hands the handler corrupt data, and a
    lookup like `db.findBy(User.class, "email", req.pathParam("email"))` silently returns null instead
    of failing loudly. Static-file serving has the same defect: `/assets/my%20file.css` is looked up as
    a literal `my%20file.css` and 404s.
  - Confirmed: `GET /users/John%20Doe` → `req.pathParam("id")` is `John%20Doe`.
  - Fix: decode **after** matching, per captured segment (decoding before matching would let `%2F`
    forge segment boundaries — that is why the raw path must stay the routing input). Use a
    path-segment decoder, **not** `URLDecoder`: `+` is a literal plus in a path, not a space. Keep the
    raw path for `Stats`/`Log`/`Redactor` keys. Decode the static-file relative path the same way,
    before the `..` and `startsWith(baseDir)` containment checks — and re-verify those checks hold
    against decoded input (`%2e%2e%2f` must not escape the base directory).
  - Model: frontier (traversal-adjacent; the decode-after-match ordering is the whole correctness
    argument, and getting it backwards is a path-traversal hole).

- [ ] **H4: A durable job whose process dies mid-execution is stuck "running" forever**
  - Severity: High. Files: `JobPoller.java:181-191` (PG claim), `:257` (H2 claim), `:281-332`
    (`runJobBody`), `:362-374` (`purgeFinishedJobs`); `Brace.java:988-1011` (`stop`).
  - Both claim paths set `started_at`, and every claim predicate requires `started_at IS NULL`.
    `started_at` is reset in exactly one place: `runJobBody`'s retry branch (`:315`). If the JVM dies
    between the claim and the terminal mark — deploy, OOM, pod eviction, or plain `Brace.stop()`,
    which stops the poller but never waits for in-flight job threads — the row is never reclaimed by
    any instance, never fails, never retries, and is never purged (`purgeFinishedJobs` requires
    `completed_at`/`failed_at`). Silent permanent work loss; the only symptom is the `/ops` "running"
    count creeping up (`getDurableJobStats` counts exactly these rows as running).
  - Fix: a stale-claim reaper. Rows with `started_at < now - visibilityTimeout` and no terminal mark
    get `started_at = NULL` (retry, respecting `attempts < max_attempts`) or `failed_at` set once
    attempts are exhausted. Run it from the poll loop or the existing daily prune. Add a configurable
    `jobVisibilityTimeout` (default generously above the slowest expected job — a too-short timeout
    double-runs a live job, which is the worse failure). Also make `stop()` drain in-flight job
    threads with a bounded wait so clean shutdowns stop creating orphans in the first place.
  - Model: frontier (at-least-once semantics, multi-instance interplay with SKIP LOCKED, and a
    too-aggressive timeout causes concurrent duplicate execution).

---

## Medium

- [ ] **M1: Multipart form fields collapse to a single value per name**
  - Files: `BraceHandler.java:849,875,882-888`.
  - `parseMultipart` accumulates non-file parts into a `LinkedHashMap<String,String>` (last wins) and
    only then re-encodes them into the `&`-joined body that `Request.formParams` re-parses. A
    checkbox group or `<select multiple>` submitted as `multipart/form-data` therefore yields one
    value, while the byte-identical `application/x-www-form-urlencoded` submission yields all of them.
  - Confirmed: two `tag` parts (`a`, `b`) → `formParams("tag") == [b]`.
  - Fix: append each part to the encoded body as it is parsed instead of routing through a map. The
    downstream single-value view already does last-wins, so nothing else changes.
  - Model: smaller model OK.

- [ ] **M2: `boolean` form fields don't bind HTML checkboxes**
  - Files: `FormBinder.java:130`.
  - `Boolean.parseBoolean(raw)` is `false` for everything except `"true"`. A checked HTML checkbox
    submits `name=on`. So an unchecked box binds false (right, by absence) and a **checked** box also
    binds false — the control is inert, and the form silently records the opposite of what the user did.
  - Confirmed: `agree=on` bound to a `boolean agree` component → `false`.
  - Fix: accept `on`, `yes`, `1`, `true`, `checked` (case-insensitive) as true; anything else false.
    Document the accepted set in `BRACE-AGENTS.md` next to the form-binding section.
  - Model: smaller model OK.

- [ ] **M3: The framework's `Vary: HX-Request` clobbers a handler's own `Vary`**
  - Files: `BraceHandler.java:411-413`; `Result.java:170-177`.
  - `result.header("Vary", "HX-Request")` writes into the single-value header map, replacing whatever
    the handler or an after-middleware set. A response that legitimately varies on `Accept-Encoding`
    or `Accept-Language` loses that dimension on every htmx request — a shared cache then serves the
    wrong variant. This is the one header besides `Set-Cookie` that is expected to carry a list.
  - Confirmed: handler set `Vary: Accept-Encoding`; response carried `Vary: HX-Request` only.
  - Fix: append (`existing + ", HX-Request"`) when a `Vary` is already present, skipping if
    `HX-Request` is already listed.
  - Model: smaller model OK.

- [ ] **M4: `Brace.stop()` never closes the `DatabaseFactory`**
  - Files: `Brace.java:988-1011`; `DatabaseFactory.java:117-119` (`close()`), `:182-183`
    (`minimumIdle == maximumPoolSize`); `TestApp.java:286-288`.
  - `DatabaseFactory.close()` exists and is called from nowhere in `src/main`. Because Hikari is
    configured with `minimumIdle == maximumPoolSize`, every stopped app leaves `poolSize` live
    connections plus a Hibernate `SessionFactory` behind. Across the test suite (one `TestApp` per
    class, ~113 classes, Surefire reusing forks) that is a large steady leak; in production a
    graceful shutdown never drains the pool, so the database sees connections vanish on process exit
    rather than close.
  - Fix: close the factory in `stop()`. The factory is app-supplied via `.database(factory)`, so
    ownership is a real decision — either close it and document that `stop()` owns the factory, or
    add `.ownsDatabase(false)` for apps that share one factory across several `Brace` instances.
    State the choice in the migration guide.
  - Model: frontier (lifecycle ownership is user-visible and easy to get wrong for shared factories).

- [ ] **M5: `Brace.stop()` leaves process-global rate-limiter state pointing at the stopped app**
  - Files: `Brace.java:805-808`, `:988-1011`; `RateLimiter.java:22` (`ALL`), `:33` (`sharedCounters`),
    `:156-159` (`disableSharedBackend`).
  - `start()` installs a static `Counters` built from this app's `DatabaseFactory`; `stop()` never
    clears it, and `disableSharedBackend()` is called only from test teardowns. A second app in the
    same JVM counts its rate limits against the previous app's factory (closed, once M4 lands), and
    `RateLimiter.ALL` never drops entries so `allStats()` keeps reporting dead limiters forever.
  - Fix: clear `sharedCounters` in `stop()`; either scope `ALL` per app or deregister a limiter's
    entry when its owning app stops (and stop its cleanup virtual thread, which also runs forever).
  - Model: smaller model OK.

- [ ] **M6: `Url.to` doesn't encode substituted values**
  - Files: `Url.java:11-30`.
  - Values are appended raw. A value containing `/` silently adds a path segment; one containing a
    space, `?`, `#`, or `&` produces an invalid or truncated URL. `Url.to` is the framework's answer
    to "how do I build a link", so this hits exactly the case it exists for.
  - Confirmed: `Url.to("/users/{name}", "a/b")` → `/users/a/b`;
    `Url.to("/users/{name}", "John Doe")` → `/users/John Doe`.
  - Fix: percent-encode each substituted value as a path segment (not form encoding — `+` must stay
    `%2B`, space must be `%20`). Pairs with H3: encode on the way out, decode on the way in.
  - Model: smaller model OK (but keep H3's decoder and this encoder as inverse pairs in one place).

- [ ] **M7: `db.hql(...)` and `db.sqlQuery(...)` lie about their return type for single-column selects**
  - Files: `Database.java:310-319` (`hql`), `:330-339` (`sqlQuery`); contrast `:341-353` (`sqlQueryLong`).
  - Both declare `List<Object[]>` and get there through an unchecked cast. Hibernate returns a list of
    **scalars** when the select has one item, so `for (Object[] row : db.sqlQuery("SELECT id FROM t"))`
    throws `ClassCastException` inside the caller's loop, with a stack trace that points nowhere near
    the query. `sqlQueryLong` already normalizes both shapes; the list variants don't.
  - Fix: normalize inside the accessor — wrap any non-array element as a one-element `Object[]` — so
    the declared type is always true. Add a test for the one-column case on both methods.
  - Model: smaller model OK.

- [ ] **M8: WebSocket broadcast has no per-member error isolation, and a failed send leaks the backpressure counter**
  - Files: `WsRegistry.java:66-73` (`deliverLocal`); `WsContext.java:52-70` (`send`).
  - `deliverLocal` iterates room members calling `ctx.send(message)` with no guard: anything thrown by
    one member aborts delivery to every remaining member — a single bad connection silently drops the
    broadcast for the whole room. Separately, `send` does `queuedBytes.addAndGet(size)` *before*
    `jettySession.sendText(...)`, so a synchronous throw (e.g. sending on a session Jetty has already
    closed) leaves the counter permanently inflated; that connection then trips the M18 cap and is
    force-closed as a "slow consumer" it never was.
  - Fix: try/catch per member in `deliverLocal` (log and continue); in `send`, add to `queuedBytes`
    only after `sendText` returns, or decrement in a catch around it.
  - Model: smaller model OK.

- [ ] **M9: `Cache.getOrSet` hard-casts the backend to the concrete `InMemoryBackend`**
  - Files: `Cache.java:105-127` (`:108`); `CacheBackend.java` (the public SPI).
  - `CacheBackend` is a documented public SPI ("Storage SPI behind `Cache`"), and `getOrSet` branches
    on the SPI method `requiresSerialization()` — then casts to the default implementation. Any
    third-party non-serializing backend throws `ClassCastException` on the cache call the docs
    recommend most.
  - Fix: lift single-flight onto the SPI as a default method (default = plain get/compute/set, which
    is what the serializing path already does), and let `InMemoryBackend` override it.
  - Model: smaller model OK.

- [ ] **M10: SMTP credentials embedded in `smtpUrl` are not percent-decoded**
  - Files: `Mailer.java` (`sendSmtp`, `url.getUserInfo().split(":", 2)`); contrast
    `DatabaseFactory.java:243-246,277-279`.
  - `DatabaseFactory` explicitly decodes URL-embedded credentials ("libpq parity"); `Mailer` does not.
    A password containing `@`, `/`, or `:` *must* be percent-encoded to survive `new URI(...)`, and
    then authenticates with the literal `%40` — an auth failure whose cause is invisible.
  - Fix: `URLDecoder.decode` both halves of the user-info, matching `DatabaseFactory`.
  - Model: smaller model OK.

- [ ] **M11: `daily(...)` drifts an hour across DST and can silently skip a day**
  - Files: `JobScheduler.java:61-80` (`daily`), `:200-241` (`claimRun`), `:308-315` (`computeDelayUntil`).
  - `daily` computes the delay to the next local occurrence, then hands
    `scheduleAtFixedRate` a fixed 24h period. After a DST transition the job runs an hour off its
    configured local time — permanently, until restart. Compounding it, cluster dedupe derives the
    slot from `epochMillis / 86_400_000` (a **UTC** day), so for a job whose local time sits within an
    hour of the UTC-day boundary the shift can land two consecutive runs in one slot; the second is
    deduped away and that day's run is lost with no error anywhere. Affects the framework's own
    `brace-jobs-prune` (03:23) and `ops-metrics-prune` (03:17).
  - Fix: reschedule each run from `computeDelayUntil` after it fires (one-shot chaining) instead of a
    fixed-rate 24h period, and derive the dedupe slot from the intended **local** date rather than
    the UTC-millis quotient.
  - Model: frontier (time-zone arithmetic plus the exactly-once claim invariant).

- [ ] **M12: `SessionOptions.sameSite("None")` doesn't imply `Secure`, so the cookie is rejected**
  - Files: `SessionOptions.java` (`sameSite(String)` vs `sameSiteNone()`).
  - `sameSiteNone()` sets `secure = true`; the string setter doesn't. Every current browser rejects
    `SameSite=None` without `Secure` outright, so `.sameSite("None")` silently disables sessions
    entirely — no cookie is ever stored, and the symptom (users never stay logged in) points nowhere
    near the config line.
  - Fix: force `secure = true` in the string setter when the value is `None` (case-insensitive), and
    validate the value against `Strict`/`Lax`/`None` instead of writing arbitrary text into the header.
  - Model: smaller model OK.

---

## Low

- [ ] **L1: Trailing-slash paths 404**
  - Files: `Route.java:32-54` (empty segments dropped), `Router.java:43-51`.
  - `/plain` compiles to `^/plain$`, so `GET /plain/` matches nothing. Confirmed: 404. Most frameworks
    either redirect to the canonical form or match both. Fix or document deliberately — a silent 404
    for a URL a user typed with a trailing slash is the failure mode either way.

- [ ] **L2: Dead weak-secret check in `Brace.validateSecret`**
  - Files: `Brace.java:218-222`.
  - `lower.contains("CHANGE-ME-to-a-random-string-at-least-32-chars")` tests an uppercase literal
    against an already-lowercased string; it can never match. Harmless today (the `change-me` clause
    above it covers the scaffold value) but it is a dead branch pretending to be a guard.

- [ ] **L3: `View.of` silently drops a trailing odd key**
  - Files: `View.java:48-52`, `:95-99`; contrast `Session.java:326-329`.
  - `for (i = 0; i < keyValues.length - 1; i += 2)` — `View.of("t", "a", 1, "b")` discards `"b"` with
    no error, so a typo'd call renders a template missing a variable. `Session.of` throws on an odd
    count. Make them consistent (throw).

- [ ] **L4: `Session.set`/`remove` mark the session modified unconditionally**
  - Files: `Session.java:117-153`.
  - Removing an absent key, or setting a key to the value it already holds, flips `modified` — which
    costs a full AES-GCM re-mint, a `Set-Cookie`, and a forced `Cache-Control: private` on the
    response (`BraceHandler.attachSessionCookie`). A guard middleware doing an unconditional
    `session.remove("flash")` makes every response uncacheable. Compare before marking.

- [ ] **L5: `Storage.uriEncodePath` uses form encoding, not the SigV4 unreserved set**
  - Files: `Storage.java:323-330`.
  - `URLEncoder` leaves `*` literal and encodes `~` as `%7E`; SigV4's unreserved set is
    `A-Za-z0-9-._~` — exactly inverted for both characters. Keys containing `*` or `~` risk
    `SignatureDoesNotMatch`. The default `safeKey` path (UUID + alnum extension) is unaffected, so
    this only reaches callers passing their own keys to `put(key, …)`.

- [ ] **L6: `Http.Multipart` doesn't escape quotes or CRLF in part names and filenames**
  - Files: `Http.java:172-196`.
  - `Content-Disposition: form-data; name="…"; filename="…"` is built by concatenation. Filenames
    usually come from `UploadedFile.filename()`, i.e. from a remote client, so a quote corrupts the
    body and a CRLF injects part headers. Escape or reject both.

- [ ] **L7: `Http.fetch()`/`fetchJson()` ignore the HTTP status while `fetchBytes()` checks it**
  - Files: `Http.java:103-137`, `:242-249`.
  - `fetchJson` on a 500 tries to parse the error body and surfaces a Jackson failure instead of the
    status. Also the shared `HttpClient` is built with the JDK default `Redirect.NEVER`, so 301/302
    responses come back as the redirect itself. Both are defensible; neither is documented.

- [ ] **L8: `JobScheduler.parseInterval` rejects `d`, but `Cache.parseTtl` accepts it**
  - Files: `JobScheduler.java:293-306`; `Cache.java:293-304`.
  - `every("1d", …)` throws `Unknown time unit: d` while `cache.set(k, v, "1d")` works. Two grammars
    behind identical-looking duration strings. Add `d` to `parseInterval`.

- [ ] **L9: `TrustedProxies` accepts a negative CIDR prefix as trust-everything**
  - Files: `TrustedProxies.java:111-126`, `:141-152`.
  - `createMask(-1, 4)` produces an all-zero mask, so `new TrustedProxies("10.0.0.0/-1")` matches
    every address — silently turning a config typo into "trust all forwarding headers". A prefix
    wider than the address is silently clamped too. Validate `0 <= prefix <= addressLength * 8` and
    throw the same `IllegalArgumentException` the parser already uses.

- [ ] **L10: `Log.error(String, Throwable)` writes the raw exception message**
  - Files: `Log.java:279-288`; contrast `:147-162`.
  - It stores `throwable.getMessage()` under the key `errorMessage`, which `Redactor.isSensitive`
    does not match, while the request-path `Log.error(method, path, Throwable)` runs
    `Redactor.redactMessage`. Same sink (stdout + `/ops/logs`), two redaction levels.

- [ ] **L11: The E-string branch in `convertPositionalParams` fires on any `e` immediately before a quote**
  - Files: `Database.java:487-508`.
  - The scanner treats every `E'`/`e'` as a backslash-escaping E-string opener, including the `E'` in
    `... LIKE'%x%'`. Inside that state a literal backslash before the closing quote swallows the
    terminator, and every subsequent `?` is mis-numbered. Contrived but reachable in native SQL with
    Windows paths or regex literals. Gate on the `E` being a token start (preceding character is not
    an identifier character).

- [ ] **L12: `Redactor.redactMessage` destroys message structure even when it redacts nothing**
  - Files: `Redactor.java:204-225`.
  - Once any token reaches 16 characters, the message is split on the delimiter class and rejoined
    with single spaces — so commas, colons, brackets, quotes, and newlines are replaced wholesale in
    a message where nothing was actually redacted. Hibernate messages
    (`Could not execute statement [n/a]; SQL: …`) are exactly this shape, and this text is what
    `ops_errors.message` and `/ops/errors` show. Rebuild by splicing redacted spans into the original
    string instead of re-joining tokens.
