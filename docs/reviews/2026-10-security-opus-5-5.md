# Security Review: Opus 5.5 (October 2026)

## Summary

Full-codebase security review of Brace at `e11da4f` (0.1.10, with 0.1.11 in progress), the third
under the [periodic model review process](README.md); the earlier ones are
[Fable 5, June 2026](2026-06-security-fable-5.md) and [Opus 5, July 2026](2026-07-security-opus-5.md).
Findings: [`2026-10-26-security-review-todos.md`](../2026-10-26-security-review-todos.md). Plan,
phases and decisions: [`2026-10-26-security-review-fix-plan.md`](../2026-10-26-security-review-fix-plan.md).

**31 findings (6 High, 14 Medium, 11 Low). All fixed**, every one enforced in 0.1.11 (Brace's only
users are its maintainers' apps, so nothing was staged behind a warning). Upgrade path:
[`brace-0.1.10-to-0.1.11.md`](../migrations/brace-0.1.10-to-0.1.11.md).

The framing differed from earlier reviews. Each category was judged by whether its protection is
**structural** (the framework enforces it, so code an app or an agent writes can't easily get it
wrong), **per-handler** (each route has to remember it), or **missing**. Per-handler protection
counted as a finding even where every current call site was correct, because Brace's users are
largely agents that follow the docs literally.

The substantive changes:

- **Routes are deny-by-default** (H1, H2). Every route and WebSocket endpoint needs an access
  requirement (`defaultAccess`, group or route `Access.*`) or `.publicRoute()`, and `start()`
  refuses one that has neither. Requirements are bound to the `Route`, so path variants can't skip
  them; `/ops` uses the same model.
- **Query safety** (H3, H4). `Sort.from(...)` replaces concatenated `ORDER BY`; in dev and tests a
  query string containing a request value throws (`QueryTaint`); `db.findOwnedOr404` makes the
  owned lookup the easy one.
- **Outbound HTTP refuses non-public destinations** (H5), redirects included, with `.trusted()`
  for internal services and a 10 MB default response cap.
- **The launcher validates a project's pinned version** (H6) before using it as a path, and (final
  review) refuses toolchains older than 0.1.8.
- **Template output** (M4, M5). User data in URL attributes can't carry a script URL; in `on*`,
  `hx-on*`, `hx-vals` and other htmx script attributes it's JSON-safe escaped; `srcdoc` is text.
- **CSRF and sessions** (M1, M2, M3, M10). Opt-outs on cookie-session routes fail startup without
  a reason; cross-site writes are refused with or without sessions; htmx carries the token via
  `${csrfMeta}` + `csrf-htmx.js`; the page cache never stores a render carrying a token or flash;
  `session.regenerate()`, an absolute lifetime, a validator hook, secret rotation and a `__Host-`
  cookie.
- **Strict CSP and headers** (M6, L3): `SecurityHeaders.strict()`; `/ops` under its own CSP,
  cookie-only, with typed tokens.
- **Uploads are untrusted content** (M7, M8): served as attachments with a sandbox CSP and nosniff
  (raster images inline), active extensions neutralized, `safeFilename()`/`saveTo(dir, name)`.
- **Ops protocol v3** (M14) binds the CLI's signature to the server's origin; the token cache is
  per server. **The WebSocket bus is signed** per app (L6). **Downloads are https-only** (L8).
- **The scaffold is hardened** (M13, L5): distroless non-root digest-pinned image, read-only
  compose reference, `/__brace/health`, `TRUSTED_PROXIES`, and the security defaults above.
- **CI fails on HIGH/CRITICAL dependency advisories** (M12), after bumping Jetty, Jackson and pgjdbc.

## Process

Six phases, each merged to `main` only after its gate: the full suite, `mvn verify` with the
Postgres tier in CI, `tests/cli` where the CLI or scaffold changed, and an independent review of
each commit whose confirmed findings were fixed before merging ("review fixes" commits).

| Phase | Scope | Merge |
|---|---|---|
| 0 | Quick wins: M12 bumps, M3, M9, M11, M8, L1, L2, L4, L7, L9, L10, L11, H6 | `94a2351` |
| 1 | H1, H2, M2, M10, M1 | `f3ec0c6` |
| 2 | H3, H4, H5 | `1c4ec93` |
| 3 | M4, M5, M6, L3, M7 | `1139557` |
| 4 | M14, L6, L8 | `260396c` |
| 5 | M13, L5, M12 (CI gate), scaffold integration | `df46d10` |
| 6 | README sweep, tool reruns, whole-branch review and its fixes, this record | — |

The plan's external migration gate (`ai-benchmark/run-migrate.sh`) was dropped: it is no longer
part of the release process.

## Commits

One or more commits per finding (`fix(security): <ID> ...`); "review fixes" commits came from each
phase's independent review.

| ID | Finding | Commits |
|---|---|---|
| H1 | Routes allow-by-default; auth a parallel list | `b2d37ae`, `e0e91a8`, `af889b2`, `f49c9f4`, `efbea81` |
| H2 | WebSocket upgrades bypassed before-middleware | `47c8bb4` |
| H3 | HQL injection through where-fragments and ORDER BY | `873ed6c`, `a2523ba`, `146f9fc` (docs) |
| H4 | No object-level authorization helper | `5ace96c`, `146f9fc` (docs) |
| H5 | `Http` had no SSRF protection | `4793bf9`, `34b4f08` |
| H6 | Launcher ran a classpath chosen by a cloned repo | `e8268a7` |
| M1 | Page cache served one user's CSRF token and flash | `641853d`, `9b533fe` |
| M2 | CSRF silently disabled on cookie routes | `59e10ac`, `f98eda4` |
| M3 | `${csrfField}` rendered escaped | `a6f03dc` |
| M4 + M5 | Script URLs in attributes; user data in `hx-on*` | `4a911ef`, `c287eee` |
| M6 + L3 | No CSP by default; ops page embedded a bearer token | `f430c21`, `9c71ed9` |
| M7 | Uploads served inline on the app origin | `a2954fb`, `8a5fd6c` |
| M8 | Raw upload filenames, no confined save | `20c4215`, `ed8c018` |
| M9 | Request JSON bound into `@Entity` classes | `ec0cd1e`, `2475021` |
| M10 | Sessions couldn't be revoked or rotated | `1b73dfc`, `fed9f9c`, `f0cfc73`, `579959a` |
| M11 | `@Min`/`@Max` truncated to long | `ec92bcb` |
| M12 | Dependency CVEs; no CI gate | `285961c`, `5194e22`, `e833c42` |
| M13 | Root, unpinned, shell-bearing container | `bded957`, `1e6d4f9` |
| M14 | Token cache not keyed by server; signatures not bound | `3915eb4`, `8aabf3f` |
| L1 | Version disclosure; Jetty error pages | `ae369d6` |
| L2 | Stack traces stored unredacted | `e386793` |
| L4 | Ops tokens survived key revocation | `e6e303b`, `1141db2` |
| L5 | No `trustedProxies` in the scaffold; broad CIDRs in docs | `6a8c5cc`, `1e6d4f9` |
| L6 | Unauthenticated NOTIFY; shared channel | `c7bb6db`, `e493363` |
| L7 | Shared-cache deserializer trusted the stored class | `b82580e` |
| L8 | Downloads verified by TLS only | `2238805` |
| L9 | HTML responses carried no charset | `667d1e9` |
| L10 | `Result.html` undocumented raw sink | `4e95921` |
| L11 | Mutating GETs; static dotfiles served | `34787fe` |
| — | Scaffold security defaults | `c3b8383` |
| — | Docs sweep (README, BRACE-AGENTS.md) | `df7e590`, `f6c3be4` |
| — | Whole-branch review fixes | `a1f65fc`, `c743e23`, `f5d5753` |

## Final whole-branch review

Per-phase reviews can't see how the phases interact, so Phase 6 reviewed everything since
`e11da4f` in three areas (request path and ops; data and output; CLI, scaffold, bus and CI), each
claim proven with a probe. None of what it found was a regression introduced by the fixes; all of it
was fixed in `a1f65fc`, `c743e23` and `f5d5753`:

- **Medium:** data a `@template` call writes inside an attribute value got body escaping (JTE gives
  the callee no attribute context), so it could break out of the attribute and skip M4/M5.
- **Medium:** the CLI wrote its token cache through a symlink a cloned repo could commit in
  `target/`; the cache moved to `~/.brace/tokens/`, and secret files are written by atomic replace.
- **Medium:** the launcher would run any older released toolchain a pin named, bringing back CLI
  bugs fixed since; now a floor (0.1.8) with an explicit override.
- **Medium:** a bad percent-escape was an anonymous 500 that filed a new error kind and regression
  notification per path; now a 400, and errors key by route pattern.
- **Low:** duplicate route registrations silently shadowed one copy (and its access); ops
  responses could be made `public` by app after-middleware; a session cookie could ride a `public`
  static response; `/ops/routes` omitted WebSocket routes; `hx-vals='${json}'` produced invalid
  JSON; `db.hql`/`sqlQuery` had no `Sort` overload; `PackageOverrides` and far-future
  `ignoreUntil` bypassed the OSV gate's ignore rules; `application.conf` was world-readable; CI
  had no `permissions:` block.

## Decisions

| # | Decision |
|---|---|
| D1 | H1 hard-fails at `start()`; the public marker is `.publicRoute()`; an app with no login says `defaultAccess(Access.none())`. |
| D2 | `Http` defaults to public destinations only; internal calls use `.trusted()`. |
| D3 | `.csrf(false)` on a cookie-session route fails startup without a reason; the cross-site check is on by default. |
| D4 | Binding request JSON into an `@Entity` throws. |
| D5 | Release signing deferred: downloads are https-only (redirects included) with an explicit insecure override. Revisit before outside adoption. |
| D6 | Only the new `SecurityHeaders.strict()` carries a CSP; `defaults()` is unchanged. |
| D7 | Scaffold runtime: distroless Java 25 nonroot, digest-pinned, refreshed by Dependabot (major bumps ignored). |
| H5 pinning | A spike showed pinning the connection to the checked IP isn't robust with `java.net.http` (the `Host` override is read once per JVM; SNI needs a client per host). Shipped instead: check-then-connect through the JVM address cache plus a re-check when headers arrive; the remaining DNS-rebinding window is documented in `docs/SECURITY.md`. |

## Validation

- `mvn verify` green in CI on every merged phase and on the Phase 6 head, including the Postgres
  Testcontainers tier (47 ITs at Phase 4, with the new `WebSocketBusSigningPostgresIT`).
- `tests/cli`: distribution, pinned-version and self-update pass; `test-docker-image.sh` builds a
  freshly scaffolded app's image and runs its compose stack with Postgres (healthy, uid 65532,
  read-only root, no capabilities, Postgres unpublished, strict headers), locally and in CI.
- Browser check: an `hx-post` in a freshly scaffolded app sends the CSRF token and swaps under the
  strict CSP with no console errors.

## Tool reruns (base `e11da4f` → tip)

| Tool | Base | Tip | Notes |
|---|---|---|---|
| SpotBugs 4.8.6 + find-sec-bugs 1.13.0 (SECURITY, Max/Low) | 95 | 122 | All 27 new hits triaged as false positives: case folding at security checks uses `Locale.ROOT` or `equalsIgnoreCase`; the new regexes are linear; `LISTEN` takes a validated channel name; the health probe only connects to loopback; the new dashboard XML hits are `esc()`-ed output from the M6 restructure. (The review counted 98 at base; plugin build difference.) |
| Semgrep (`p/java`, `p/security-audit`, `p/secrets`) | 2 | 2 | The same MD5 (asset fingerprint) and file-permission hits. |
| OSV gate (`.github/scripts/osv-scan.sh`) | FAIL: 23 advisory groups, 10 ≥ HIGH (Jackson, Jetty, pgjdbc) | PASS: 2 MODERATE (commons-compress, test scope) | Also confirms the gate catches a real vulnerable tree. |

## Follow-ups

- WebSocket upgrade rejections (401/403/429 handshakes) don't pass through `BraceHandler`, so they
  are missing from stats and the request log and get no after-middleware headers.
- Release signing (D5) before outside adoption.
- `Http`'s remaining DNS-rebinding window (documented).
- Found in passing, not security: `brace dev` fails on a fresh scaffold (H2 isn't on its
  classpath), and `brace check` reports never-run jobs as failing right after a restart.
- The README and BRACE-AGENTS.md sweep (`df7e590`, `f6c3be4`) found samples that no longer
  started under the access model; the release checklist sweep should stay part of every release.
