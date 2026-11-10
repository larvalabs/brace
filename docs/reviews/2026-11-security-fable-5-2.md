# Security Review: Fable 5.2 (November 2026)

## Summary

Full-codebase security review of Brace at `1e28c0f` (`v0.1.11-rc.6` plus one `TODO.md`-only
commit), the fourth under the [periodic model review process](README.md); the earlier ones are
[Fable 5, June 2026](2026-06-security-fable-5.md), [Opus 5, July 2026](2026-07-security-opus-5.md)
and [Opus 5.5, October 2026](2026-10-security-opus-5-5.md).
Findings: [`2026-11-09-security-review-todos.md`](../2026-11-09-security-review-todos.md). Plan,
tracks and decisions: [`2026-11-09-security-review-fix-plan.md`](../2026-11-09-security-review-fix-plan.md).

**27 findings (2 High, 9 Medium, 16 Low). 26 fixed; H2's code has landed and its last step (one
constant) belongs to the 0.1.11 release commit.** The fixes ship in 0.1.11. Upgrade path:
[`brace-0.1.10-to-0.1.11.md`](../migrations/brace-0.1.10-to-0.1.11.md), section "Security review,
November 2026".

The October review made most protections structural, so this one looked for what was still wrong
around that core: bypasses of those fixes, regressions they introduced, and surfaces earlier
reviews had not pushed on. Route access, SQL, LISTEN/NOTIFY, command execution, the SSRF address
guard, CSRF and session crypto held. The findings were elsewhere.

The substantive changes:

- **Template output** (H1, L7, L8). H1 was a regression from October's attribute work: data in
  `<script>var a = "${x}"</script>` got HTML escaping. The regex that guessed "inside an attribute
  value" is replaced by an HTML tokenizer that follows scripts, styles, SVG and MathML as a
  browser does. Script-body data is written with `\uXXXX` escapes; data inside a tag but outside a
  quoted value is escaped whole; when JTE's context and the tokenizer disagree the stricter rule
  applies. `javascript:` URLs with data are blocked, CSS data is CSS-escaped, `hx-vals`,
  `hx-headers` and `hx-request` are judged whole.
- **Static mappings join the access model** (M1, M2, M3). A mapping declares `.publicFiles()` or
  `.access(...)` and `start()` refuses one that does neither. Files are served only at their
  canonical spelling, to GET and HEAD, and a route under a mapping's prefix owns its path.
  Guarded responses and every access rejection are never shared-cacheable.
- **Rate limiter** (M4, L5, L6). A missing key is limited (per client address); `req.form()`
  ignores the query string on mutating methods; distinct keys are capped with eviction; `perIp`
  groups IPv6 by /64; control characters and oversized keys no longer break the Postgres path.
- **Error store and redaction** (M5, M6, L13). One hostile request can no longer stall error
  persistence on Postgres; headers, query strings, paths and exception messages are redacted by
  value shape as well as by name.
- **Launcher and CLI on an untrusted checkout** (H2, L11). Prereleases order before their
  release, one `<brace.version>` per pom, env names and key labels validated, no writes through
  symlinks, terminal control characters escaped, every ops request bounded in time and size.
- **Ops** (L1, L2, L16). Every authorized-keys line states its scope; ops origins are required
  outside dev; an accepted sign-in cannot be replayed after a restart when there is a database.
- **Outbound and mail** (M8, M9, L12, L14). Buffered `Http` calls time out on the body; `smtp://`
  requires STARTTLS; mail addresses are single; webhook text is inert in Slack and Mattermost.
- **Also:** previous session secrets validated and lifetime-bounded (M7); `req.ip()` reads one
  chosen forwarding header and is canonical (L3); page cache stores only 2xx, keys on the route
  and detects undeclared inputs in dev and tests (L4); dot segments are 404 (L9); unresolved
  route access fails closed (L10); QueryTaint and shared-cache class checks (L15).

The branch is 125 commits (merges excluded), 195 files, 26,278 insertions and 1,594 deletions
against `ef21c1a`.

## Process

The review itself was read-only: five parallel reviewers, one per area, with every High and
Medium confirmed a second time against source. The fix phase then ran in four tracks on disjoint
files, each in its own worktree, with a model per fix sized to how subtle it was (Fable 5.2 for
the template class and the static-mapping access design, Opus 5.5 for multi-file changes with
judgment, Sonnet 5.5 and Haiku 5.5 for mechanical ones). Every fix started from a failing test
that reproduced the finding.

| Stage | What | Tip |
|---|---|---|
| Review | Findings doc and fix plan | `ef21c1a` |
| Fix tracks A to D | One commit per finding, per track | merged by `b8847b6` |
| Per-track independent review | A fresh reviewer per track; confirmed findings fixed as "review fixes" commits before the track merged | same |
| M6 and post-merge cleanups | The one fix that had to wait for the merge | `bc98b10` |
| Whole-branch review | Three areas (request path and ops; data and output; CLI, scaffold and docs samples); fixed as "final review fixes" | `79d9017` |
| Verification pass | Two reviewers, scoped strictly to the final round's diff; fixed in a last pass | `d8aa624` |
| Merge gate | `mvn clean verify` with the Postgres tier, `tests/cli` | `d8aa624` |

Two deviations from the conventions in [`README.md`](README.md): the integration branch is
`claude/framework-security-review-7c9e30`, and migration-guide entries were written at closeout,
not per fix commit, because parallel tracks appending to one guide conflicted on every merge.

## Commits

"Review" is the per-track independent review, "final" the whole-branch review, "verification"
the last pass.

| ID | Finding | Fix | Review fixes | Final review fixes | Verification fixes |
|---|---|---|---|---|---|
| H1 | Script-body data got HTML escaping | `9bda2fc` | `4912bcb`, `a6d6864`, `2da49bf`, `5b2d638`, `911ba6a`, `84c8570` | `1991622`, `0f8bdc0`, `b74e20e`, `c2b68c4`, `ca4e948` | |
| H2 | Launcher floor admits old CLIs | `cc76d22` | `227a8ac` | | |
| M1 | Static prefix has no segment boundary | `72ce66f` | | | |
| M2 | Guards see the raw path, static serving decodes | `d3685b2` | `861f15f` | | |
| M3 | Static mappings outside deny-by-default | `f13f26b`, `7d75559` | `54f8a8a`, `236e92e` | `13b94e6` | |
| M4 | `perKey` skipped on a missing key | `54cdf62` | `8631f0a` | | |
| M5 | `%00` stalls the error store on Postgres | `61aff14` | `0869991` | `9b4810a` | `f382736` |
| M6 | Redaction of headers and queries by name only | `f3659a3` | | `a278a43`, `6b22db1`, `c136520` | `ff969bc`, `4d4c768`, `3b7d2a0`, `d7c438b` |
| M7 | `previousSecrets` accepts the placeholder | `8a61a2f` | `129bb65`, `deb793b` | | |
| M8 | Buffered `Http` has no body timeout | `dbacf8c` | `c272697` | | |
| M9 | STARTTLS is opportunistic | `657c383` | `484b18c` | `9b9966f` | |
| L1 | Unknown `scope:` becomes CONTROL | `1f3cad0` | `01588c7`, `6e1514c` | `d37f522`, `0e9a37a`, `8f5cb9a` | `d08c8f0`, `c8a72c0` |
| L2 | Ops origin pinning only in `prod` | `39e7651` | | | |
| L3 | `req.ip()` header precedence and last-wins lines | `65bdac2` | `a293938` | `4664a1d` | `41bc535` |
| L4 | Page cache stores any status, raw-path keys | `625b1cd` | `673f56c` | `2628631`, `7fb2f76` | `ecce52f`, `682e31f`, `ad3a816` |
| L5 | NUL or oversized keys break Postgres | `1e262da` | `b94bb76` | | |
| L6 | No key cap; `perIp` on full IPv6 | `5acbe4e` | `cbd5780` | `9dbfb1c` | `0d38dbf`, `f9ccc69`, `23a3263` |
| L7 | Smaller template-output gaps | `ed3ace4`, `0f489a2`, `2f9d6c6`, `f5eb0e6`, `f0acc56` | `afabcb0` | `9febd05` | |
| L8 | `csrf-htmx.js` has no origin check | `179b37e` | | `33fd77d` | `07d5f5f` |
| L9 | Dot segments reach handlers | `1d1a3b5` | `20439db` | | |
| L10 | Unresolved route access is open | `ade8d0b` | `f90852a` | | |
| L11 | The CLI on a hostile checkout | `90cac8c`, `525438f`, `44f3402` | `4ebea81` | `15f1021`, `bdbd125`, `b359ee2`, `fe75199`, `acd6a12` | `eb1233f` |
| L12 | Webhook posts exception text as markup | `37485d8` | `647dce8` | | |
| L13 | Raw exception messages reach `/ops` | `ff3bd05` | `5a3a2e2` | `3137bf1`, `f1ff715` | |
| L14 | `@Email` and `Mailer.to` accept lists | `481d26e` | `0b187e2` | `5f4e708` | |
| L15 | QueryTaint evasions; cache class check | `59e479d`, `dcd35d2` | | `39165fd`, `1afcfa2` | `5a9bf77` |
| L16 | Analytics growth; ops nonce cache | `9e1b48b`, `939c39c` | | `654e559`, `ac62004` | `1eb4cd9` |

Not tied to one finding: lifecycle (`bc98b10`, `174f3b2`, `141c54a`); a cross-track test fix
after the merge (`030ea84`); docs and samples (`5e779da`, `3915ae2`, `f0e098a`, `79d9017`); test
hygiene (`73f45b9`, `b280eee`).

## Review rounds

Every round found real defects in the previous round's fixes, regressions included. That is the
main lesson of this review, so it is recorded in full.

**Per-track independent reviews** (after the first fix pass):

- **Template output:** a self-closing `<script .../>` inside SVG made the new tokenizer believe
  it was in a script body for the rest of the page, which **switched off the URL scheme check**
  after it (regression; confirmed executing in Chromium). Also a comment-wrapped script that
  ended early, data breaking out of an SVG script, and an unguarded `hx-request`.
- **Rate limiter and proxy headers:** the new strict address parser rejected the brackets Jetty
  puts around an IPv6 peer, so **no IPv6 proxy was ever trusted** (High, regression). The global
  missing-key bucket locked a login page for everyone after five page views. A page-cache flood
  evicted the app's own cache entries.
- **Stores, ops, outbound:** making `Mailer.to` single-address made a **multi-recipient
  `MailerNotifier` fail silently** on every regression email (regression). The scope parser was
  both too loose and too strict.
- **Static files and access:** a non-GET method reached a mapping's file around a route's
  access; a lowercase `cache-control` header defeated the cache rewrite on guarded responses.
- **Launcher and CLI:** the single-pin check counted on one normalisation of the pom and
  extracted from another; a server value with newlines could forge CLI output lines.

**Whole-branch review** (three areas, after all tracks merged):

- **Request path:** the new quoted-string `Forwarded` parser let a client's **unbalanced quote
  swallow the proxy's entry**, so `req.ip()` returned the proxy's own address (regression).
  Shared-mode key eviction let an instance with a stale view **delete the fleet row of a blocked
  key** (regression), grew one map without bound, and still locked out new keys when the table
  was full of blocked ones.
- **Data and output:** data in an SVG or MathML script body could land in attribute-name
  position and add an event handler (confirmed executing; the earlier fix was incomplete).
  **`cache.get(key, List.class)` missed on any instance that had not itself stored that class**,
  so security state read through an interface failed open after a deploy (regression). Four
  redaction gaps. The analytics caps were weak against one address.
- **CLI and docs:** no request timeout or body cap in the ops CLI; `--json` passed some control
  characters raw; key rotation could leave the old key trusted; three stale docs samples.

**Verification pass** (scoped to the final round's diff only):

- No High. Two Mediums, both availability regressions: **ops sign-in blocked about 30 s when the
  database was down** (the durable nonce write had no timeout), and the **in-memory page cache
  could wedge** when a clear or tag delete raced page stores, leaving pages that could not be
  evicted.
- Lows: redaction ate whole file paths and suppressed-exception stack frames; `csrf-htmx.js`
  dropped a token the page set itself; `brace check` kept one unbounded request; rotating a
  read-only key without `--read-only` made it a control key; the new raw-path check broke the
  documented `Paged` example; the new migration held the cache table locked while it built an
  index.

**The last pass's fixes were not independently reviewed.** They are covered by tests written
first, by the merge gate, and by a read of the two Medium fixes (`1eb4cd9`, `ecce52f`).
Rounds got smaller each time (High and regressions in the first, no High in the second, no High
and narrower Mediums in the third), but the pattern says an unreviewed round is likelier than
not to contain something.

## Decisions

Settled by Matt on 2026-11-09, the recommended option in each case:

| # | Decision |
|---|---|
| D1 | A static mapping takes an `Access` or `.publicFiles()`; `start()` refuses one with neither. |
| D2 | A missing rate-limit key is limited by default; the exemption is opt-in. |
| D3 | `req.form()` no longer fills body fields from the query string on mutating methods. |
| D4 | `smtp://` requires STARTTLS, with an explicit plain opt-out (`smtp+plain://`). |
| D5 | The launcher floor becomes `0.1.11` at release; every `0.1.11` prerelease is refused. |
| D6 | An unknown `scope:` fails startup; `opsOrigins` is required everywhere except dev. |

Applied during the review rounds, on reviewers' recommendations or as the fail-closed choice,
and **confirmed by Matt on 2026-11-10**.

| Decision | Why |
|---|---|
| A missing rate-limit key is limited per client address, not in one global bucket (refines D2) | The global bucket locked a login page for everyone under ordinary traffic |
| At the key cap, an under-limit key is evicted; blocked keys stay pinned; overflow is per address | A flood of fresh keys otherwise locked out every new key |
| Rate-limiter saturation fails closed by default, with `.onSaturation(FAIL_OPEN)` | The limiter exists to bound attempts; the docs state the attack cost of each choice |
| Every authorized-keys line must state `scope:read` or `scope:control` (refines D6) | Each narrower rule left some spelling that silently meant control |
| Rotating an ops key keeps its scope unless `--control` or `--read-only` is given | A read-only agent key was silently replaced by a control key |
| `req.ip()` is `unknown` when a trusted proxy's rightmost element names no client, and is canonical (RFC 5952, IPv4-mapped as IPv4) | Never return the proxy's own address or client-supplied text as the client |
| Without a database, ops sign-ins are refused for 30 s after a start | The in-memory nonce cache is empty after a restart; with a database nonces are recorded durably and nothing is refused |
| All script-body data is `\uXXXX`-escaped | Closes the foreign-content cases and makes JSON script blocks valid |
| Postgres migration `V19__brace_cache_used_at.sql` adds `brace_cache.used_at`, with no index | Page rows are trimmed by recency; the index did not help the trim and held a table lock |
| Redaction redacts runs of 16 or more secret-alphabet characters, with a name-versus-secret heuristic | A token next to punctuation was kept; names made of words must survive. Measured residual: a base62 token of 16 characters reads as a name about 1 in 10,000 times, none at 32 or more |
| An app started with a database cannot be restarted; a second `start()` throws | Rebinding the pool, framework jobs and cache backend is a larger change than a clear refusal |

## Validation

- **Merge gate at `d8aa624`:** `mvn clean verify` green. Unit tier 2,421 tests, memory tier 2,
  Postgres Testcontainers tier 84 (47 at the end of the October review). Run with
  `TESTCONTAINERS_REUSE_ENABLE=false TESTCONTAINERS_RYUK_DISABLED=true`: the shared reusable
  local container still had the pre-edit `V19` applied (the migration was edited before any
  release), and Ryuk cannot start under Colima.
- **`tests/cli` at `d8aa624`:** distribution, pinned-version and self-update pass. The Docker
  image test was not run locally.
- **One flaky failure:** an earlier gate run on identical code failed a single timing-sensitive
  test, `DurableJobTest.claimsSizedToCapacityAndSlowJobsDontStallNewBatches`. It passed alone and
  in the next full run. This branch's only changes to `JobPoller` are redaction wrappers.
- **Postgres-only bugs proven on Postgres:** M5 (the review itself had not run it), L5, the
  shared-limiter defects, the ops nonce race and the cache migration each have integration tests
  that failed before their fix.
- **Browser checks:** the template fixes were confirmed in Chromium by two reviewers (the old
  class executed script on control pages; the new one on none of 902 corpus pages).
  `csrf-htmx.js` was run against the bundled htmx 2.0.10 in Chromium at `33fd77d`; its last
  follow-up, `07d5f5f`, was only replayed against a model of htmx in Node.
- **Test-suite port collisions explained:** several full-suite runs each had one test receive a
  response from another local process. A wildcard-bound app on an OS-assigned port can share
  that port with a process bound to `127.0.0.1`. `Brace.test()` now binds loopback (`b280eee`).

## Tool reruns

**Not run.** None of the three was available offline on the review machine, and nothing was
downloaded.

| Tool | Status |
|---|---|
| SpotBugs 4.8.6 + find-sec-bugs 1.13.0 | not in the local Maven cache |
| Semgrep (`p/java`, `p/security-audit`, `p/secrets`) | not installed |
| OSV gate (`.github/scripts/osv-scan.sh`) | downloads its scanner and queries the OSV API; runs in CI on push |

The dependency set is identical at base and tip (66 artifacts; `pom.xml` unchanged), so the last
CI OSV result describes the tip apart from advisories published since. To run them, in a base
and a tip checkout (the SpotBugs plugin version and flags are unchecked):

```bash
semgrep scan --metrics=off --config p/java --config p/security-audit --config p/secrets src/main
```

```bash
.github/scripts/osv-scan.sh
```

```bash
mvn -DskipTests compile com.github.spotbugs:spotbugs-maven-plugin:spotbugs -Dspotbugs.effort=Max -Dspotbugs.threshold=Low -Dspotbugs.pluginList=<findsecbugs-plugin-1.13.0.jar>
```

## Follow-ups

- **H2's last step.** Set `MIN_TOOLCHAIN="0.1.11"` in `bin/brace` in the 0.1.11 release commit
  (the release checklist in `AGENTS.md` now says so), then tick H2. Not earlier: until 0.1.11
  exists, that floor refuses every `0.1.11` prerelease that is not the bundled one.
- **Wildcard bind (Low, new, not fixed).** On macOS an unprivileged local process can bind
  `127.0.0.1:<app port>` and take the loopback traffic of a wildcard-bound app; `start()` does
  not detect an existing conflict. Linux normally refuses the bind. `.host("127.0.0.1")` is the
  existing mitigation. About 125 tests that call `Brace.app().port(0)` directly still bind the
  wildcard.
- **JTE 3.2.1** drops all template text before a template's first comment in one common shape.
  Brace now escapes the resulting stray tag's data whole, so it is a broken page, not an
  injection. Not reported upstream yet.
- **Template residuals.** MathML `annotation-xml` and a few SVG shapes are not modelled; there a
  letters-only value can become a boolean attribute such as `hidden` (no script execution).
- **Rate limiter.** In per-process mode an evicted under-limit key restarts from zero; the
  shared-mode guarantee for a returning fleet-blocked key has documented limits (the read budget
  and the filter window); the flush still runs on the request thread.
- **Ops.** A signed sign-in request with a future timestamp that no server has accepted can be
  presented once when it becomes valid.
- **Not addressed:** third-party JUL output (Jetty, Hibernate, Flyway) is unredacted on stderr;
  `brace compile` and `brace test` write through a committed `target` symlink; `brace logs` exits
  0 after printing an HTTP error; `DurableJobTest`'s timing sensitivity.
- **Carried from the findings doc's "Not confirmed":** `%00` in a path segment; an over-long
  HTTP method; no cap on SSE or WebSocket connections; no statement or pool timeouts; the
  Cloudflare preset; `ops.key` read from the committed `.brace`.
- **Process.** Budget for more than one review round on any fix that adds state to a
  security-critical class; the tool reruns should be set up before the next review starts.
