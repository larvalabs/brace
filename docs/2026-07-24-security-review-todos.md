# Security Review Todos: 2026-07-24

Findings from a full-codebase security review of Brace at **0.1.8-SNAPSHOT**, run with
**Opus 5** across the five standing dimensions (crypto/sessions, HTTP lifecycle, ops
surface, database/injection, files/CLI). This is the second review under the
[periodic model review process](reviews/README.md); the first was
[Fable 5, June 2026](reviews/2026-06-security-fable-5.md).

Every High and Medium finding below was **verified directly against a running server**
(raw-socket probes and integration probes), not inferred from reading. Where a suspected
issue turned out to be non-exploitable, it is recorded in "Checked and cleared" at the
end so a future reviewer doesn't re-derive it.

Each item notes the fix approach and a suggested model assignment, sized by how subtle
the fix is, not how severe the bug is.

## High

- [x] **H1: Static-file serving follows symlinks out of the served directory**, `BraceHandler.java:603-620`
  - `serveStaticFile` normalizes lexically (`Path.normalize()`) and then checks
    `filePath.startsWith(baseDir)`. `normalize()` is a pure string operation — it does not
    resolve symlinks — and `Files.readAttributes` / `Files.readAllBytes` follow them by
    default. A symlink anywhere under a `staticFiles(...)` directory therefore serves
    whatever it points at, including files outside the web root.
  - **Verified:** with `app.staticFiles("/assets", dir)` and a symlink `dir/link.txt` →
    a file in the system temp dir, `GET /assets/link.txt` returned `200` with the target
    file's contents. The `..`-substring check and the `startsWith` containment check both
    pass, because the traversal happens in the filesystem, not in the path string.
  - **Why it matters:** symlinks inside a served tree are normal in real deployments
    (`current` → release-dir layouts, build tools that link `node_modules`, an uploads
    directory linked into `public/`). The containment check is presented as *the* control
    bounding what is servable, and it does not hold.
  - **Fix:** resolve the candidate with `toRealPath()` and re-check containment against the
    base directory's own `toRealPath()`. Fall back to 404 on `IOException` (missing file,
    broken link). Keep the existing lexical check as a cheap pre-filter.
  - **Tests:** symlink to a file outside the base dir → 404; symlink *within* the base dir →
    still served (don't break legitimate intra-root links); broken symlink → 404; regular
    file unaffected.
  - **Model: Sonnet 4.6**, well-specified, fully testable.

- [ ] **H2: Session cookies are not `Secure` by default**, `SessionOptions.java:13,31-33`
  - `SessionOptions.of(secret)` sets `secure = false`, and `Brace.sessions(String)` — the
    documented one-liner used by every sample, scaffold, and doc example — builds its
    options through exactly that factory. So an app written the documented way emits
    `Set-Cookie: brace_session=…; Path=/; HttpOnly; SameSite=Lax` with **no `Secure`
    attribute**. Opting in requires knowing about `SessionOptions.secure(secret)`.
  - **Why it matters more here than in a typical framework:** Brace serves HTTP/1.1
    cleartext by design and expects TLS to be terminated by a reverse proxy
    (`docs/SECURITY.md`). The app therefore *cannot* infer the scheme, so "default off" is
    not a safe fallback — it is the permanent state for every deployment that doesn't
    explicitly change it. Any cleartext request to the domain (a typed `http://` URL, a
    stale bookmark, an HSTS-less first visit) discloses the session cookie to a network
    attacker, and the cookie is the whole session.
  - There is also no startup warning: nothing tells the operator the cookie is
    non-`Secure`, unlike the weak-secret path which does warn.
  - **Fix:** default `secure` to `true` when `brace.mode` is not `dev` (the same signal
    `BraceHandler` already reads for dev-mode 404 hints and `TemplateEngine` reads for
    precompiled templates), keep `false` in dev so local `http://localhost` keeps working.
    Leave `SessionOptions.secure(boolean)` as the explicit override in both directions, and
    log a startup warning when sessions are enabled in prod mode with `secure=false`.
  - **Breaking:** a prod-mode app served over plain HTTP will stop receiving its session
    cookie back. Needs a migration-guide entry with the explicit
    `.sessions(SessionOptions.of(secret).secure(false))` opt-out.
  - **Tests:** prod mode → `Secure` present; dev mode → absent; explicit `.secure(false)` in
    prod → absent (override wins); explicit `.secure(true)` in dev → present.
  - **Model: Fable 5 / Opus 5**, changes a security default and is user-visible; the
    mode-gating and the override precedence are where this goes wrong.

## Medium

- [ ] **M1: `SecurityHeaders` never reaches static files, 404s, 500s, CSRF 403s, or 413s**, `BraceHandler.java:406-408`
  - After-middleware runs only on the normal handler path. Every other exit from `handle`
    goes straight to `writeResult`: the before-middleware short-circuit, the static-file
    branch, the no-route 404, the thrown-`NotFoundException` 404, the CSRF 403, the 500
    catch, and both 413 paths.
  - **Verified** on an app with `app.after(SecurityHeaders.defaults())`:
    `/ok` → `X-Frame-Options: DENY`, `Referrer-Policy`, `nosniff`. `/assets/ok.txt` →
    `nosniff` only. `/nope-404`, `/boom` (500), and an 11 MB POST (413) → **no security
    headers at all**.
  - **Why it matters:** an operator who follows the documented hardening step believes the
    headers are global. Static files are the sharpest gap — an app serving user-supplied
    content from a static directory gets no `X-Frame-Options` and no CSP on exactly the
    responses that need them most.
  - **Fix:** run the after-middleware chain (or, more narrowly, re-apply the collected
    response headers) inside the `writeResult` choke point so every response leaving
    `handle` is covered, rather than only the handler path. Note the ordering constraint:
    a header explicitly set by the framework on that path (e.g. `nosniff` on static,
    `Cache-Control: no-store` on the ops exchange) must not be clobbered.
  - **Tests:** assert the defaults appear on static, 404, 500, CSRF-403, and 413 responses;
    assert a path-scoped `after("/admin/*", …)` still does not fire on `/public`.
  - **Model: Fable 5 / Opus 5**, touches the response choke point that the 2026-06 review's
    M6/CR fixes already made subtle; re-entrancy and header precedence are the risks.
  - **Prior art:** listed as a deferred follow-up in the 2026-06 review record and never
    fixed; now verified end to end.

- [ ] **M2: Request bodies are fully buffered before before-middleware runs**, `BraceHandler.java:194-248`
  - Order in `handle` is: match route → **read/parse the entire body** (up to
    `maxUploadSize`, 10 MB default; multipart is parsed with `setMaxMemoryFileSize(-1)`,
    i.e. entirely in memory) → *then* run before-middleware.
  - Consequence: `RateLimiter.perIp(...)`, auth guards, and any other before-middleware
    cannot shed load before the memory is committed. An unauthenticated client can force
    a 10 MB heap allocation per in-flight request against any matched POST/PUT route, and
    request handlers run on virtual threads, so in-flight concurrency is not bounded by a
    thread pool.
  - The comment above the block explains why the body read was moved *after* route matching
    (so 404s and static files don't pay for it) — correct as far as it goes, but the
    rate-limiting layer sits one step further down and is now behind the cost it exists to
    prevent.
  - **Fix:** move the body read to just after the before-middleware loops and before the
    handler invoke. `Request` is constructed before the read today, so this needs the body
    to be supplied lazily (a supplier the `Request` pulls on first `body()`/`form()`
    access) or the `Request` to be built in two stages. Before-middleware that legitimately
    needs the body (rare — rate limiters and auth guards read IP/headers) then triggers the
    read itself on access, which is the correct cost attribution.
  - **Tests:** a `perIp(1, "1m")` limiter returns 429 for the second oversized POST without
    the body ever being buffered (assert via a body-supplier spy); a before-middleware that
    *does* read `req.body()` still sees the full body; the existing 413 behavior is
    unchanged for requests that reach a handler.
  - **Model: Opus 5**, the laziness has to thread through `Request`'s constructor, the CSRF
    `_csrf` extraction, and the 413 paths without changing observable behavior.

- [ ] **M3: WebSocket upgrades are not `Origin`-checked**, `Brace.java:729-747`
  - `container.addMapping(wsPath, (upgradeRequest, upgradeResponse, callback) -> …)` accepts
    every upgrade and immediately decrypts the `brace_session` cookie into the handler's
    `WsContext`, with no check on the request's `Origin`. Jetty does not enforce one by
    default. There is no API to configure allowed origins.
  - **Impact:** classic cross-site WebSocket hijacking — an attacker page opens a socket to
    the app, the browser attaches the victim's session cookie, and the socket is
    authenticated as the victim for its whole lifetime, with the same-origin policy
    providing no protection on the WebSocket response stream.
  - **Currently mitigated by accident:** the default `SameSite=Lax` means modern browsers
    don't attach the cookie to a cross-site WS handshake. That mitigation evaporates the
    moment an app calls `SessionOptions.sameSiteNone()` (documented and supported), and it
    is a browser-side control the server should not be relying on silently.
  - **Fix:** validate `Origin` on upgrade by default — accept same-host, reject a
    cross-origin `Origin`, and allow a missing `Origin` (non-browser clients). Add
    `Brace.wsAllowedOrigins(String...)` for deliberate cross-origin use, and document the
    default.
  - **Tests:** upgrade with a foreign `Origin` → rejected; same-host `Origin` → accepted;
    absent `Origin` → accepted; configured allowlist entry → accepted.
  - **Model: Sonnet 4.6**, the rule is small; the care is in not breaking non-browser
    clients that send no `Origin`.

- [ ] **M4: Ops responses carry no `Cache-Control: no-store`**, `OpsHandler.java:430-438` (and every other ops endpoint bar `exchange`)
  - `dashboard()` returns `Result.html(...)` with no cache headers, and the HTML embeds a
    live 2-hour bearer token (in `hx-headers` on the polling div and on each action button).
    `status()`, `errors()`, `logs()`, `routes()`, `cache()`, and `regressions()` likewise
    set no cache headers.
  - `exchange()` is the only endpoint that sets `no-store` — precisely because the token is
    in its URL — which shows the risk was recognized but applied one endpoint too narrowly.
  - **Why the cookie channel makes this real:** RFC 9111 forbids shared caches from storing
    a response to a request carrying `Authorization`, so the CLI's bearer path is covered by
    the spec. The browser path authenticates with the `__brace_ops_session` **cookie**, which
    carries no such prohibition — a response with no `Cache-Control` and no `Vary: Cookie`
    is heuristically cacheable, so an intermediary can store one operator's dashboard,
    embedded token and all, and serve it to the next requester.
  - **Fix:** set `Cache-Control: no-store` (and `Pragma: no-cache` for old intermediaries)
    on every `/ops/*` response. Cheapest correct place is a single wrapper applied where the
    ops routes are registered, so a future endpoint can't forget.
  - **Tests:** every registered `/ops/*` route asserts `no-store` on its response — write it
    as a loop over `router.routes()` filtered to `/ops/`, so new endpoints are covered
    automatically.
  - **Model: Haiku 4.5**, mechanical once the wrapper location is chosen.

- [ ] **M5: Ops auth protocol v1 is still accepted**, `OpsHandler.java:126-134`
  - v1 signs the timestamp alone: the signature is not bound to the public key and carries
    no nonce, so a captured `/ops/auth` body can be replayed verbatim by anyone within the
    ±30 s acceptance window to mint a fresh token at the key's full scope ceiling.
  - v1 was kept for exactly one release as a compatibility bridge. It shipped in 0.1.6, was
    deprecated in 0.1.7 with "a future release will reject v1"
    (`docs/migrations/brace-0.1.6-to-0.1.7.md:1786-1794`), and the 2026-06 review record
    lists removal as follow-up #2, "next release". **We are now on 0.1.8-SNAPSHOT and it is
    still accepted** — the deprecation window has elapsed.
  - **Fix:** reject a v1 body (no `v`, or `v:"1"`) with 401 and a message naming the
    required CLI version. Remove the CLI's v1 fallback (`CliAuth.bearer`'s retry) and its
    test `CliAuthTest.fallsBackToV1AgainstPre017Server`. Migrate the tests that still
    authenticate v1-style and double as v1 coverage — `OpsScopeIntegrationTest`,
    `ErrorStoreTest`, `OpsCsrfTest`, `RegressionIntegrationTest`, `OpsSharedSecretTest` — to
    v2. Flip `OpsIntegrationTest.authV1StillAcceptedThisRelease` to assert 401 (it carries a
    comment saying to do exactly this).
  - **Breaking:** a pre-0.1.7 CLI can no longer authenticate. Migration-guide entry required.
  - **Model: Sonnet 4.6**, mechanical removal but it touches six test classes; the risk is
    silently losing coverage rather than migrating it.

- [ ] **M6: `Result.download` does not quote the filename into `Content-Disposition`**, `Result.java:38-41`
  - `"attachment; filename=\"" + filename + "\""` with no escaping. A filename containing a
    double quote closes the quoted-string early and everything after it is parsed by the
    client as further `Content-Disposition` parameters.
  - **Verified:** `Result.download(bytes, "text/plain", "x\"; name=\"y")` produced
    `Content-Disposition: attachment; filename="x"; name="y"` on the wire.
  - CR/LF specifically is neutralized by Jetty's generator (it rewrites them to spaces —
    see "Checked and cleared"), so this is parameter injection and download-name spoofing,
    not response splitting. Serving a user-uploaded file under its original name is the
    obvious trigger, and that is the method's primary use case.
  - **Fix:** sanitize in `download()` — strip control characters, and either reject or
    percent-escape `"` and `\`. Emit the RFC 6266 form for non-ASCII: an ASCII-safe
    `filename="…"` fallback plus `filename*=UTF-8''<pct-encoded>`.
  - **Tests:** quote in filename doesn't escape the parameter; CR/LF stripped;
    non-ASCII filename round-trips via `filename*`; plain ASCII name unchanged.
  - **Model: Sonnet 4.6**, RFC 6266 encoding has edge cases worth getting right.

- [ ] **M7: `Http.Multipart` interpolates part name/filename into part headers unescaped**, `Http.java:176-189`
  - `finalizeBody()` writes
    `Content-Disposition: form-data; name="<name>"; filename="<filename>"` with raw string
    concatenation, then `writeAscii` emits it verbatim. CR/LF in either value is written
    straight into the multipart stream, so a caller passing a user-supplied filename to
    `Http.post(url).multipart().field(name, bytes, filename)` lets that user terminate the
    part headers, inject a body, and forge **additional parts** in the outbound request.
  - Unlike the response side, nothing downstream sanitizes this: `writeAscii` is a direct
    `getBytes(US_ASCII)` into the body the JDK client sends.
  - **Impact:** parameter smuggling into whatever third-party API the app is calling —
    e.g. adding a `visibility=public` or `role=admin` part to an upload the app believed it
    fully controlled.
  - **Fix:** reject (or strip) CR, LF, and NUL in `name` and `filename`, and escape `"` and
    `\` per RFC 7578 §4.2. Fail fast with `IllegalArgumentException` on control characters —
    for an outbound API call a hard failure is better than a silently-altered request.
  - **Tests:** CRLF in filename throws; quote in filename is escaped, not part-splitting;
    normal filenames unchanged.
  - **Model: Sonnet 4.6**.

- [ ] **M8: `brace ops dashboard` passes a server-supplied token through `cmd /c start`**, `CliOps.java:167-172,180-187`
  - `loginToken` is read from the server's `/ops/auth/login-token` JSON response and
    concatenated into `dashboardUrl`, which `openBrowser` hands to
    `new ProcessBuilder("cmd", "/c", "start", url)` on Windows. `cmd.exe` re-parses its
    command line, so `&` in the URL terminates the `start` command and begins another —
    a token of `x&calc.exe` executes `calc.exe` on the operator's workstation.
  - **Threat model:** the operator trusts their server to serve a dashboard, not to run code
    on their laptop. A compromised app server, a hostile server an operator is asked to
    inspect, or a MITM on a plain-`http://` `cfg.url()` all reach this. macOS/Linux
    (`open` / `xdg-open`) take the URL as a single `argv` entry and are unaffected.
  - **Fix:** validate the token server-response-side before use — a login token is base64url,
    so reject anything outside `[A-Za-z0-9_-]` — and on Windows invoke via
    `rundll32 url.dll,FileProtocolHandler <url>` (or `explorer.exe <url>`), neither of which
    goes through the `cmd` parser. Validate the assembled URL is `http(s)://` too.
  - **Tests:** a token containing `&`, `"`, `|`, or whitespace is rejected before any process
    is spawned (assert on the validator, not the spawn); a normal base64url token passes.
  - **Model: Sonnet 4.6**, small but the Windows launch-path substitution needs care.

## Low

- [ ] **L1: `Result.cookie` does not validate the cookie name or value**, `Result.java:196-207`
  - The value is appended raw before the framework's own attributes, so a `;` in it injects
    cookie attributes. **Verified:** value `1; Path=/; Domain=evil` produced
    `Set-Cookie: c=1; Path=/; Domain=evil; Max-Age=60; Path=/; HttpOnly; SameSite=Lax`.
  - CR/LF is neutralized by Jetty, so this is attribute injection, not header injection —
    but a handler setting a cookie from user input (a `theme`, a `locale`, a `returnTo`)
    can have its cookie re-scoped by that input.
  - **Fix:** reject control characters, `;`, and `,` in the value (or percent-encode it), and
    validate the name against the RFC 6265 token grammar.
  - **Model: Haiku 4.5.**

- [ ] **L2: The ops session cookie is scoped to `Path=/`**, `OpsHandler.java:218` via `Result.java:201`
  - `result.cookie(OPS_COOKIE_NAME, …)` inherits `Result.cookie`'s hardcoded `Path=/`, so the
    ops session token is attached to **every** request to the app, not just `/ops/*` — it
    reaches application handlers, which can read it via `req.cookie(...)`, and any
    request-logging an app does.
  - **Fix:** add a path parameter to the cookie builder (or set the ops cookie via a
    `Set-Cookie` string directly) and scope it to `/ops`.
  - **Model: Haiku 4.5.**

- [ ] **L3: Dead branch in the weak-secret check**, `Brace.java:220`
  - `lower.contains("CHANGE-ME-to-a-random-string-at-least-32-chars")` tests a mixed-case
    literal against a string that was just lowercased, so it can never match. Harmless — the
    adjacent `changeme` / `change-me` / `change_me` checks already cover the scaffold value —
    but it is misleading dead code in a security check.
  - **Fix:** delete the clause (redundant) or lowercase the literal.
  - **Model: Haiku 4.5.** (Carried over from the 2026-06 review's cleanup list, still open.)

- [ ] **L4: `Csrf.validateToken` encodes with the platform default charset**, `Csrf.java:33`
  - `expected.getBytes()` / `submittedToken.getBytes()` use the JVM default charset. Tokens
    are base64url so today the bytes are identical under every realistic default, but a
    charset-dependent comparison in a CSRF check is a latent correctness bug.
  - **Fix:** `getBytes(StandardCharsets.UTF_8)` on both sides.
  - **Model: Haiku 4.5.**

- [ ] **L5: `Storage.put`/`delete` accept keys containing `..` segments**, `Storage.java:323-330`
  - `uriEncodePath` splits on `/` and percent-encodes each segment, but `.` is unreserved, so
    a `..` segment survives encoding intact. `buildUploadUrl` and `canonicalUri` build the
    same unnormalized path, so the request is **validly signed** for the traversed key and an
    S3 endpoint that normalizes the path will act on it. `putGenerated` is safe (UUID keys);
    the exposure is `put(key, …)` with an app-assembled key that includes user input.
  - **Fix:** reject keys with `..` segments, a leading `/`, or empty segments in `put`,
    `delete`, and `url`, with a clear `IllegalArgumentException` pointing at `safeKey`.
  - **Model: Haiku 4.5.**

- [ ] **L6: `Csrf.hiddenField` does not HTML-escape the token**, `Csrf.java:36-38`
  - The token is framework-generated base64url, so this is not currently exploitable — but
    `session.set("_csrf", …)` is reachable from application code (the reserved-key guard in
    `Session.set` covers only `_exp`), and the field is injected into templates as raw HTML.
  - **Fix:** escape the value, and/or reserve `_csrf` in `Session.set` the way `_exp` is.
  - **Model: Haiku 4.5.**

- [ ] **L7: `OpsToken.create` accepts `ttlSeconds <= 0`**, `OpsToken.java:70-71` and `OpsHandler.java:141-142`
  - `Math.min(requestedTtl, 86400)` caps the top but not the bottom, so a client requesting
    `ttlSeconds: -1` gets a token that is already expired. Fail-closed (verification rejects
    it immediately), so the impact is a confusing 401 rather than a vulnerability.
  - **Fix:** floor the requested TTL at 1 (or 400 with a clear error).
  - **Model: Haiku 4.5.** (Carried over from the 2026-06 review's follow-up list.)

## Checked and cleared

Recorded so a future reviewer doesn't spend the same effort. All were probed against a
running server unless noted.

- **Response splitting via header/`Location`/cookie values.** Jetty 12.0.33's generator
  rewrites CR and LF in field values to spaces. A handler setting
  `header("X-Thing", "a\r\nX-Injected: yes")`, a `Redirect.to("/next\r\n…")`, and a cookie
  value with CRLF all reached the wire as single folded-to-space header lines. This is why
  M6 and L1 are parameter/attribute injection rather than header injection.
- **Session cookie confidentiality/integrity.** AES-256-GCM with a random 12-byte nonce per
  write and a 128-bit tag; any decrypt/auth failure returns an empty session. `_exp` is
  inside the sealed payload, stripped on read, and unsettable through `Session.set`.
- **CSRF fail-closed behavior.** A mutating request with a non-form, non-JSON content type
  (`text/plain` — a "simple request" that a cross-origin form can send without preflight)
  finds no `_csrf` in the body and no `X-CSRF-Token` header, and
  `Csrf.validateToken(session, null)` returns `false` → 403. Route default is
  `csrfRequired = true` (`Route.java:20-22`). Method matching is exact, so a lowercase
  `post` fails to match the route and 404s rather than skipping the check.
- **HQL/SQL injection.** All `Database` helpers that interpolate a field name route through
  `requireValidFieldIdentifier`; every value goes through `setParameter`. `ErrorStore`'s
  only concatenations are a fixed `resolved_at IS [NOT] NULL` clause and a static column
  list. The `?`→`?1` converter's quoting states (E-strings, dollar-quotes, quoted
  identifiers, comments) look correct.
- **Ops dashboard XSS.** `esc()` escapes `&<>"'`, and every interpolation of
  attacker-influenceable data (error type, message, route, stack trace, job name/message,
  metric names, JFR method and class names) goes through it. The embedded token is escaped
  too.
- **Path traversal in static files via the URL.** `..` in the request path is rejected before
  resolution, and `getHttpURI().getPath()` is not percent-decoded, so `%2e%2e` resolves to a
  literal directory name rather than a traversal. (The symlink case is H1 — a different
  mechanism.)
- **`TrustedProxies` / `X-Forwarded-For`.** The 2026-06 rightmost-untrusted walk, the
  IP-literal gate (no DNS on the request path), and the blank-segment handling all still
  hold; `stripPort` handles IPv4, bracketed IPv6, and bare IPv6.
- **Ops CONTROL endpoints have no CSRF token**, by design (`setCsrfRequired(false)`), but the
  browser credential is a `SameSite=Strict` cookie, so a cross-site POST does not carry it.
  Noted rather than filed; an `Origin` check would be reasonable defense-in-depth if the ops
  cookie's `SameSite` is ever loosened.
- **`Passwords.dummyCheck`'s hard-coded hash** parses cleanly under jBCrypt (`$2a$12$` + 22
  chars from bcrypt's base64 alphabet) and returns `false`, so the enumeration-timing
  mitigation does the intended bcrypt work rather than throwing.
- **Form/query parsing hash-collision DoS.** Parameters land in `LinkedHashMap`, which
  treeifies colliding bins, so crafted-collision input is O(n log n), not O(n²).
- **CLI process execution.** Everything except the Windows browser launch (M8) uses
  `ProcessBuilder` with an argument array and no shell. Ops private keys and CLI tokens are
  written through `SecretFiles.writeStringWithOwnerOnlyPermissions`.
