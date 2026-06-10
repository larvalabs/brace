# Security Review Todos — 2026-06-09

Findings from a full-codebase security review (five-dimension audit: crypto/sessions,
HTTP lifecycle, ops surface, database/injection, files/CLI). Every High item was
verified directly against source before being listed. Each item notes the fix approach
and a suggested model assignment — sized by how subtle the fix is, not how severe the
bug is. Mechanical fixes go to Haiku 4.5, well-specified logic to Sonnet 4.6,
protocol/lifecycle changes with security invariants to Opus 4.8 / Fable 5.

## High

- [x] **H1: Ops dashboard mints CONTROL tokens for READ callers** — `OpsHandler.java:353-356`, `OpsToken.java:50-51`
  - `dashboard()` gates at READ but embeds a 2h token from the 2-arg `OpsToken.create()`,
    which defaults to CONTROL with `kid=null`. A read-only key holder can scrape it from
    the page HTML and call `/ops/cache/clear` etc. `loginToken()` (`OpsHandler.java:123`)
    has the same default — no escalation there (caller must already be CONTROL) but it
    drops scope + key attribution, so every browser session is unattributed CONTROL for 24h.
  - **Design intent confirmed (not an intentional exception):** the scoped-token plan
    (`docs/2026-05-30-scoped-ops-token-plan.md` §2) says dashboard GET is READ
    ("harmless to view") and the action buttons were expected to 401 at the CONTROL
    endpoints for read tokens. The CONTROL-default mint is an unmigrated pre-scope call
    site. Read keys clearing cache was explicitly rejected elsewhere: the sanctioned
    pattern for agents needing a control action is a separate narrow CONTROL key, never
    an upgraded read key (`docs/2026-05-30-brace-oncall-monitor-project.md:176-179`,
    `docs/2026-05-29-brace-oncall-agent-plan.md:197-201`).
  - **Fix:** thread the authenticated caller's `claims.scope()` and `claims.kid()` through
    both `dashboard()` and `loginToken()` (and from there `exchange()` already preserves
    them). In `OpsDashboard.html()`, take the scope and omit/disable the mutating buttons
    (clear cache, resolve error, acknowledge regression) at READ. Consider deprecating the
    2-arg `OpsToken.create()` entirely — a CONTROL default is the wrong default.
  - **Tests:** READ-authenticated dashboard fetch → extracted token rejected by a CONTROL
    endpoint; exchange preserves READ scope end to end.
  - **Model: Fable 5** — touches the scope-ceiling invariant across token mint, login
    exchange, and dashboard rendering; getting the default wrong reopens the hole.

- [ ] **H2: X-Forwarded-For trusts the leftmost (spoofable) entry** — `Request.java:194-231`
  - `forwarded.split(",")[0]` returns the client-supplied entry; real proxies append the
    true client on the right. Defeats `RateLimiter.perIp`, IP allowlists, audit logs.
    The RFC 7239 `Forwarded` parser has the same leftmost bug and mangles multi-element
    headers (`for=a, for=b` parses wrong).
  - **Fix:** walk the XFF chain right-to-left, skipping entries inside trusted CIDRs,
    return the first untrusted address (rightmost-untrusted). Strip `:port` suffixes and
    `[...]` brackets for IPv6. Rewrite `extractForwardedFor` to split elements on `,`
    first, then params on `;`, and apply the same right-to-left walk. Also document in
    SECURITY.md that operators should list both IPv4 and IPv6 forms of proxy addresses
    (`TrustedProxies.contains` fails closed on representation mismatch — see L10).
  - **Tests:** spoofed-leftmost case, multi-hop chains, port suffixes, multi-element
    Forwarded header, untrusted peer ignores headers entirely (existing behavior).
  - **Model: Sonnet 4.6** — well-specified algorithm, success is fully testable.

- [ ] **H3: Unbounded request-body read (OOM DoS)** — `BraceHandler.java:171-183`
  - `maxUploadSize` only caps the multipart branch; the plain branch does
    `Content.Source.asString()` with no limit and no `setMaxRequestContentLength`
    anywhere. A chunked multi-hundred-MB POST buffers into one String.
  - **Fix:** enforce `maxUploadSize` on the plain-body branch — read via a bounded
    accumulator (check `Content-Length` first when present, but also cap the actual
    chunked read; Content-Length alone is bypassable). Return 413 on overflow.
  - **Tests:** oversized Content-Length → 413; oversized chunked body → 413 without
    full buffering; body at exactly the limit succeeds.
  - **Model: Sonnet 4.6** — needs care with Jetty's Content.Source API and chunked reads.

- [ ] **H4: `brace new` ships a placeholder session secret that evades the weak-secret check** — `ProjectGenerator.java:160`, `Brace.java:199-213`
  - `session.secret=CHANGE-ME-to-a-random-string-at-least-32-chars` is a public constant
    (it's in this repo), 46 chars so it passes the length gate, and the hyphen in
    "change-me" defeats every pattern in `validateSecret`. `application.conf` is not in
    the generated `.gitignore` and is COPYd into the generated Dockerfile image.
  - **Fix (agreed):** generate a real secret at scaffold time —
    `Base64.getUrlEncoder().withoutPadding().encodeToString(SecureRandom 32 bytes)` —
    instead of a placeholder. Belt-and-braces: add `change-me` / `change_me` / the exact
    placeholder to the `validateSecret` blocklist anyway (old scaffolds exist), and add
    `application.conf` to the generated `.gitignore` with a generated
    `application.conf.example` carrying the placeholder for documentation. Update the
    generated README/Dockerfile notes to pass the secret via env in production.
  - **Tests:** two `brace new` runs produce different secrets; generated secret passes
    `validateSecret` silently; placeholder now triggers the warning.
  - **Model: Haiku 4.5** — mechanical; the design decision is made.

## Medium

- [ ] **M1: PBKDF2 runs 100k iterations on every request** — `Session.java:141,188,323-337`
  - `deriveKey()` is called per cookie read/write with no cache; secret is fixed for
    process lifetime. CPU amplification under request flood + hot-path latency.
  - **Fix:** memoize the derived `SecretKeySpec` (static `ConcurrentHashMap<String,SecretKeySpec>`
    or single cached entry keyed by secret). Pure caching, no KDF change.
  - **Model: Haiku 4.5**

- [ ] **M2: No server-side session expiry** — `Session.java` (whole class), `SessionOptions`
  - Encrypted payload has no issued-at/expiry; `Max-Age` is client-enforced only, so a
    stolen cookie is valid until secret rotation.
  - **Fix:** write `_exp` (epoch seconds, derived from `SessionOptions.maxAge`, with a
    sane default for session-lifetime cookies, e.g. 14 days) into the payload in
    `toCookie`; reject in `fromCookie` when expired. Backward compat: cookies without
    `_exp` should be treated as expired after a grace window — or accepted for one
    release and rejected the next; pick one and put it in the migration guide
    (`docs/migrations/`, per AGENTS.md this is a user-visible change).
  - **Model: Opus 4.8** — small code change but a compat/rollout decision with a
    security invariant; the migration-guide story has to be right.

- [ ] **M3: `/ops/auth` signature replayable within ±30s** — `OpsHandler.java:77-92`, `OpsKeys.java:69`
  - Client signs only the ISO timestamp; no nonce, no challenge, signature doesn't bind
    the public key. Anyone observing one auth request can replay it for a fresh token at
    any scope up to the key ceiling.
  - **Fix:** sign over `publicKey || timestamp || clientNonce` at minimum (kills
    cross-key replay and makes captured tuples single-purpose); ideally server-issued
    challenge. Note the B5 constraint: ops must work without shared state, so a
    server-side seen-nonce store can be per-instance best-effort — document the residual
    window. Touches the CLI client (`CliAuth`/`CliOps`) and any agent docs
    (`docs/agent-ops-guide.md`) — protocol version the auth body so old CLIs get a clear
    error.
  - **Model: Fable 5** — auth protocol change across server + CLI with a fleet/state
    constraint; design judgment required.

- [ ] **M4: Ops tokens travel in URLs** — `OpsHandler.java:126-130,140-157,549-553`
  - `?token=` accepted for full auth; `exchangeUrl` carries the login token in a GET
    query string (proxy logs, history, Referer); 60s exchange token is replayable into a
    24h session.
  - **Fix:** restrict `?token=` to the exchange endpoint only (drop the general
    `authenticate()` query-param fallback); shorten the exchanged session TTL or make it
    scope-bound (depends on H1 landing first); keep the documented stateless-replay
    trade-off but move it from a code comment into SECURITY.md.
  - **Model: Sonnet 4.6**

- [ ] **M5: CSRF gaps (three related)** — `BraceHandler.java:245,267-281`, `docs/SECURITY.md:96-102`
  - (a) `isMutating` omits PATCH — unroutable today (no `patch()` registration exists)
    but a landmine; (b) SECURITY.md documents a JSON content-type exemption that does
    not exist in code (code is stricter — fix the doc, don't add the exemption);
    (c) a CSRF token rendered via a handler that doesn't take a `Session` is never
    persisted to a cookie, so the subsequent POST always 403s — pushes users to `.csrf(false)`.
  - **Fix:** (a) add `"PATCH"` to `isMutating` now; (b) rewrite the SECURITY.md CSRF
    section to match the implementation; (c) in `BraceHandler`, when `Csrf.ensureToken`
    mints a token into the local `csrfSession`, write that session cookie on the
    response even though the handler didn't request a session.
  - **Tests for (c):** GET via plain `Handler` renders form → POST with rendered token
    succeeds.
  - **Model: Sonnet 4.6** — (c) touches the request lifecycle; (a)+(b) are trivial riders.

- [ ] **M6: After-middleware can drop the session cookie** — `BraceHandler.java:313-327`
  - Session `Set-Cookie` is attached before after-middleware runs; middleware returning
    a *new* `Result` discards it (logins silently don't stick).
  - **Fix:** run after-middleware first, then attach the session cookie to the surviving
    `Result` (or copy `setCookies` forward when the instance changes). Mind ordering
    interactions with M5(c) — same code region, do these together.
  - **Model: Sonnet 4.6**

- [ ] **M7: Rate limiter — unbounded keys + fail-closed 500** — `RateLimiter.java:74-118`, `Counters.java:43-65`
  - User-controlled `perKey` extractors (and spoofed IPs, until H2 lands) create
    unbounded `brace_counters` rows / local map entries with arbitrarily long keys; and
    any Postgres error propagates out of the before-middleware as a 500, so a DB blip
    breaks every guarded route.
  - **Fix:** hash keys longer than ~64 chars (sha256-hex) before use; cap raw key length;
    catch DB errors in `checkShared` and fall back to `checkLocal` (per-instance
    approximation beats both fail-open and 500s), logging loudly. Document the posture
    in SECURITY.md and `docs/scaling.md`.
  - **Model: Sonnet 4.6**

- [ ] **M8: `/prefix/*` middleware doesn't match `/prefix` itself** — `Middleware.java:44-46`
  - `before("/admin/*", auth)` leaves `/admin` unauthenticated; interior wildcards
    (`/api/*/edit`) silently never match.
  - **Fix:** make trailing `/*` also match the bare prefix (`^\Q/admin\E(/.*)?$`).
    This is a behavior change — note it in the 0.1.7→0.1.8 migration guide. Reject or
    warn on interior `*` at registration time instead of failing open silently.
  - **Model: Haiku 4.5** — small, but write the matcher tests first.

- [ ] **M9: `Json.of(entity)` serializes every public field** — `Json.java:9-27`
  - With the public-fields entity convention, `Json.of(user)` leaks `passwordHash`.
  - **Fix:** document prominently (BRACE-AGENTS.md + SECURITY.md): respond with records/
    DTOs, never entities. Consider a framework nudge: warn (dev mode) when `Json.of`
    receives an `@Entity`-annotated object, or honor `@JsonIgnore` guidance in docs.
    Don't change global mapper visibility — too breaking.
  - **Model: Haiku 4.5** (docs) — escalate to Sonnet 4.6 if adding the dev-mode warning.

- [ ] **M10: Error store keeps URL paths + exception messages unredacted** — `BraceHandler.java:367,546`, `OpsDashboard.java:594`
  - Redaction covers query params and headers, but path segments (password-reset
    tokens) and `e.getMessage()` (frequently contains credentials/PII) are stored and
    rendered in the dashboard.
  - **Fix:** at minimum document the gap in `docs/agent-ops-guide.md` + SECURITY.md;
    better, add value-shaped redaction (long high-entropy path segments → `[redacted]`)
    to `Redactor` and run messages through `Redactor.redact`-style scrubbing before
    storage.
  - **Model: Sonnet 4.6**

- [ ] **M11: Secrets written world-readable** — `CliOps.java:37`, `ProjectGenerator.java:70`, `CliAuth.java:156`
  - `ops-private.key` (Ed25519, authenticates to prod control plane) and
    `target/.brace-token` (live bearer token) written via `Files.writeString` with
    umask-default perms.
  - **Fix:** create with `rw-------` (POSIX perms with non-POSIX fallback — copy the
    pattern from `Toolchains.makeExecutable`, `Toolchains.java:94`). Chmod-then-write
    order matters on pre-existing files: create exclusive or set perms before writing
    the secret bytes.
  - **Model: Haiku 4.5**

## Low

- [ ] **L1: No safe-redirect helper** — `Redirect.java:5-16`. Add `Redirect.toLocal(path)`
  (rejects absolute URLs and `//` protocol-relative) and document the
  `Redirect.to(req.queryParam("next"))` hazard. **Haiku 4.5**
- [ ] **L2: `OpsDashboard.esc()` doesn't escape single quotes** — `OpsDashboard.java:621-624`,
  used inside single-quoted `hx-headers` attributes. Add `'` → `&#39;`. **Haiku 4.5**
- [ ] **L3: HQL `?`-converter miscounts in dollar-quoted strings / quoted identifiers /
  E-strings** — `Database.java:265-319`. Developer-authored SQL only (correctness, not
  injection). Either teach the scanner `$tag$...$tag$` and `"..."`, or document as
  unsupported → use `db.jdbc(...)`. **Sonnet 4.6**
- [ ] **L4: `findBy`/`queryIn` concatenate the `field` identifier into HQL** —
  `Database.java:79-153`. Latent injection if an app passes `req.param("sort")`.
  Validate `field` against the entity's attribute names at call time (reflect once,
  cache), or at minimum add loud Javadoc. **Sonnet 4.6**
- [ ] **L5: `Class.forName` on stored class names** — `JobPoller.java:225`, `Cache.java:255`.
  Not request-reachable, but static initializers run before the `DurableJob` cast.
  Use `Class.forName(name, false, loader)` + `isAssignableFrom` check before
  instantiation; document the trust boundary on `scheduled_jobs`/`brace_cache`. **Haiku 4.5**
- [ ] **L6: Page-cache keys collision-prone** — `Cache.java:326-344`. Param values inserted
  unescaped (`&`/`=` injection collides keys); no identity vary. Percent-encode key
  parts; document that `wrap()` is for identity-independent responses only. **Haiku 4.5**
- [ ] **L7: Static responses lack `nosniff`; SVG served inline** — `BraceHandler.java:459-479`.
  Set `X-Content-Type-Options: nosniff` on the static path unconditionally. **Haiku 4.5**
- [ ] **L8: `brace new` project name unvalidated** — `ProjectGenerator.java:8-34`. Path
  traversal / pom injection (self-inflicted only). Validate `[A-Za-z0-9_-]+`. **Haiku 4.5**
- [ ] **L9: bcrypt helper gaps** — `Passwords.java:6-12`. Null hash throws; no dummy-check
  helper for constant-time user-not-found. Add `Passwords.dummyCheck()` + doc the
  enumeration-timing guidance. **Haiku 4.5**
- [ ] **L10: TrustedProxies IPv4/IPv6 representation mismatch fails closed silently** —
  `TrustedProxies.java:76`. Dual-stack `::1` vs `127.0.0.1` etc. Document (fold into
  H2's SECURITY.md update). **Haiku 4.5**

## Doc-only riders (fold into the items above)

- SECURITY.md CSRF section describes an exemption that doesn't exist (M5b).
- SECURITY.md "Nonce: ... (prevents replay attacks)" is wrong — the nonce prevents
  keystream reuse, not replay; replay protection is M2's `_exp`. Fix wording with M2.
- Ops token URL/replay trade-offs live only in code comments — surface in SECURITY.md (M4).

## Suggested order

1. H1 (breaks a documented guarantee), H4 (one-line-ish, kills a whole class of deployments going wrong), H2, H3.
2. M5 + M6 together (same lifecycle code), M1, M11 (quick wins).
3. M2, M3, M4 (session/ops auth protocol batch — coordinate, they interact).
4. M7–M10, then Lows opportunistically.

All of this lands in 0.1.7 or 0.1.8 — anything user-visible (M2, M5c, M8) needs an
entry in the in-progress migration guide per AGENTS.md.
