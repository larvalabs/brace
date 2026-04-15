# Brace CLI: Project Config and Agent-Facing Ops Commands

**Date:** 2026-04-14
**Status:** Design

## Goal

Turn the `brace` CLI into a production telemetry surface that humans and AI agents can use to inspect a running Brace app. Three threads:

1. A project-local config file (`.brace`) so commands don't repeat `--url` and `--key` flags.
2. Cleanup: ops commands today are labelled "global" but are project-scoped. Fix the labelling and add guards.
3. New read commands (`errors`, `logs`, `status`, `cache`, `resolve`) backed by existing and new `/ops/*` endpoints, designed for both interactive use and scheduled agent checks.

The end state: an agent with shell access to a project can run `brace status --env prod`, get JSON, and decide whether to investigate further. A scheduled cron agent can run `brace errors --env prod --since 15m` every fifteen minutes and detect new exceptions via exit code.

## Non-goals

- A pre-built scheduled-agent skill or cron template wrapping these commands. That depends on first having the commands stable in real use.
- Persistent log storage across process restarts. The in-memory ring buffer is acceptable.
- A general-purpose `brace doctor` separate from `brace init`. `init` does both scaffold and diagnose.
- New ops endpoints beyond what these commands need (`/ops/jobs`, `/ops/metrics`, `/ops/jfr` stay folded inside `/ops/status`).
- Changes to how the running server emits log output (still structured JSON to stdout). The new `LogTap` is purely additive.

## Architecture

The current CLI is split between a bash launcher (`bin/brace`) and a Java entry point (`io.brace.Cli`). The launcher handles compile/run/test/dev locally; it delegates `new` and `ops *` to `java io.brace.Cli`. This split is preserved.

All new subcommands (`init`, `errors`, `logs`, `status`, `cache`, `resolve`, plus the existing `ops keypair` and `ops dashboard`) live in `Cli.java` because they need JSON parsing, HTTP, Ed25519 signing, and TTY detection — easier in Java than bash. The bash launcher dispatches each with one line.

`Cli.java` today is one ~190-line file. After this work it would grow past 800 lines, so it gets split:

| File | Purpose |
|---|---|
| `Cli.java` | Top-level dispatch, `main()`, shared error-print helpers |
| `CliConfig.java` | Loads `.brace` and `.brace.local`, resolves URL + key path + env |
| `CliInit.java` | `brace init` — scaffold and diagnostic checklist |
| `CliOps.java` | `brace ops keypair`, `brace ops dashboard` (existing logic, refactored to use `CliConfig` and `Json.mapper()`) |
| `CliCommands.java` | The new read commands: `errors`, `logs`, `status`, `cache`, `resolve` |
| `CliAuth.java` | Shared auth helper: load private key, sign timestamp, POST `/ops/auth`, return + cache bearer token |
| `CliOutput.java` | TTY detection, table renderer, JSON renderer, exit-code helpers |

All in `io.brace`, no new package. Reuses existing types: `Config` (key=value parser), `OpsKeys` (Ed25519), `Http` (HTTP client), `Json` (Jackson wrapper), and the existing server-side `OpsHandler.OpsAuthRequest` record (the CLI sends instances of it serialized via `Json.mapper()`).

**Existing `Cli.opsDashboard()` cleanup.** Today it hand-builds JSON with string concatenation (`"{\"publicKey\":\"" + ... + "\"}"`). When refactoring it into `CliOps`, replace string concat with `Json.mapper().writeValueAsString(new OpsHandler.OpsAuthRequest(pub, ts, sig, 3600))`. Same change for parsing the auth response — use `Json.mapper().readTree()` instead of substring scanning. The browser login-token + exchange flow (`POST /ops/auth/login-token` → `GET /ops/auth/exchange?token=...`) is preserved as-is — read commands don't need it; only `brace ops dashboard` does.

## `.brace` and `.brace.local` config files

**Format:** the existing `Config` parser. Same key=value syntax as `application.conf`, no new dependency.

**`.brace`** is committed to git. Project-wide CLI defaults — URLs for each environment, the path to the authorized-keys file. Example:

```
ops.local.url=http://localhost:8080
ops.prod.url=https://app.example.com
ops.authorized_keys=ops-authorized-keys
```

**`.brace.local`** is gitignored. Per-developer overrides — the path to *your* private key, *your* default environment, any per-machine URL adjustment.

```
ops.key=ops-private.key
ops.env=local
```

**Resolution order**, highest priority first:
1. CLI flags (`--url`, `--key`, `--env`)
2. `.brace.local`
3. `.brace`
4. Built-in defaults (`ops.local.url=http://localhost:8080`, `ops.key=ops-private.key`, `ops.env=local`, `ops.authorized_keys=ops-authorized-keys`)

**Why a separate file instead of `application.conf`:** `application.conf` is the runtime config consumed by the running JVM (port, db.url, mailer). `.brace` is tooling config consumed by the CLI process, which may run on a machine that never starts the server (an agent box scheduled to check production). The two files have different consumers, different lifecycles, and different deployment directions. A typo in `ops.local.url` should not crash server startup.

**`CliConfig.load()`** is the single API every subcommand calls. It returns a small record:

```java
record CliConfig(String url, String keyPath, String authorizedKeysPath, String env) { }
```

`url` is resolved by looking up `ops.<env>.url` after `env` is determined. No file reads anywhere else in the CLI code.

## `brace init` — scaffold and diagnose

Idempotent, non-interactive. One pass through a checklist. Exit 0 if everything is ✓, non-zero if any ✗. Safe to re-run; only creates what's missing, never overwrites.

**Local checks (always run):**

| Check | If missing |
|---|---|
| `.brace` exists | Create with `ops.local.url=http://localhost:8080` |
| `.brace.local` exists | Create with `ops.key=ops-private.key` |
| `.gitignore` contains `.brace.local` and `ops-private.key` | Append the missing entries |
| `ops-authorized-keys` exists and non-empty | ✗ — print: `run \`brace ops keypair\` to generate one` |
| `ops-private.key` exists | ✗ — same suggestion |

**Remote checks (only if `ops.prod.url` is set in `.brace`):**

| Check | On failure |
|---|---|
| TCP reach `ops.prod.url` | ✗ "not reachable" |
| `POST /ops/auth` with the local private key | ✗ "your public key is not authorized on production. Add this line to the server's `ops-authorized-keys`: `<pubkey>  <label>`" |

**Output** is a checklist:

```
Local setup
  ✓ .brace                    present
  ✓ .brace.local              present, gitignored
  ✓ ops-authorized-keys       1 key
  ✓ ops-private.key           present, gitignored
  ✓ .gitignore                entries OK

Production (https://app.example.com)
  ✓ reachable
  ✗ not authorized            add to server's ops-authorized-keys:
                              ed25519:AAAA...  matt-laptop

Run `brace init` again after fixing.
```

**Agent mode:** `brace init --json` returns the same checklist as a structured payload:

```json
{
  "ok": false,
  "local": { "ok": true, "checks": [ ... ] },
  "remote": { "ok": false, "url": "https://app.example.com", "checks": [ ... ] },
  "actions": [
    "Add ed25519:AAAA... matt-laptop to the server's ops-authorized-keys"
  ]
}
```

**Implementation notes:**

- `brace init` does *not* auto-run `brace ops keypair`. Generating a keypair writes a secret to disk; init should never produce secrets as a side effect of "diagnose" output. Init prints the command instead.
- The remote auth check reuses the existing `/ops/auth` request shape from `Cli.opsDashboard()` — no new server endpoint needed.
- No `--force` flag, no `--fix` flag. The behaviour is already idempotent and safe.

## Project-scoping the ops commands (cleanup)

Today, `bin/brace` lists `ops keypair` and `ops dashboard` under "Global commands". They are not global — both read and write files relative to the current working directory (`./ops-authorized-keys`, `./ops-private.key`). Running them outside a project directory silently scribbles files in the wrong place.

**Changes:**

1. **Help-text reorganization** in `bin/brace`. Only `brace new` stays under "Global commands". Everything else (`init`, `ops keypair`, `ops dashboard`, `errors`, `logs`, `status`, `cache`, `resolve`, plus the existing `compile`, `run`, `dev`, `test`, `deps`) moves to "Project commands".

2. **`require_project` guard** in `Cli.java`. Each project subcommand starts with one check. For commands that *consume* project state (`errors`, `logs`, `status`, `cache`, `resolve`, `ops dashboard`): require `.brace` to exist. For commands that *create* it (`init`, `ops keypair`): require `src/main/java` to exist. On failure:

   ```
   This command must be run inside a Brace project.
   Run `brace init` first, or `brace new <name>` to create a project.
   ```

3. **`brace ops dashboard` flag removal.** Drop `--url` and `--key`. The command reads from `.brace`/`.brace.local` via `CliConfig`. Override which environment via `--env prod`.

## Server-side: `LogTap` and new endpoints

Two server-side pieces enable the new commands.

### `LogTap` — in-memory log ring buffer

A new ~60-line class. Captures structured log entries as `Log` emits them, retaining the last N (default 1000). Each entry:

```java
record LogEntry(long id, Map<String, Object> fields) { }
```

- The `fields` map is the exact same `LinkedHashMap` that `Log` already builds for stdout output (with `ts`, `level`, `event` or `message`, plus call-site fields). No transformation needed.
- Implementation: `ConcurrentLinkedDeque<LogEntry>` plus an `AtomicLong` for monotonic IDs. Lock-free on the hot path, same pattern as `Stats`.
- **Wire point:** `Log.println(Map<String, Object>)` at `Log.java:114` is the single chokepoint every existing logging method already funnels through (`event`, `request`, `error`, `info`, `debug`, `warn`). Add one line: `LogTap.append(map)`. No changes to the public `Log` API, no changes to call sites, and `request`/`http.error` lines are captured for free.
- Default capacity 1000 entries, configurable via `application.conf` (`ops.log_tap.size=2000`).
- Lost on process restart. Confirmed acceptable.

### New endpoints in `OpsHandler`

| Method | Path | Returns |
|---|---|---|
| GET | `/ops/logs?since=<id>&since_ts=<iso8601>&level=<info\|warn\|error>&limit=<200>` | JSON array of log entries newer than `since` (ID) *or* `since_ts` (timestamp), level >= filter, capped by limit |
| GET | `/ops/cache` | `{ size, hits, misses, hit_rate, evictions }` — broken out of `/ops/status` |

Registered the same way existing ops routes are, in `Brace.java` next to the existing `router.add("GET", "/ops/...", (Handler) opsHandler::...)` lines (around `Brace.java:418`-`426`). Both go through the existing `authorize(req)` check (Bearer token *or* `__brace_ops_session` cookie), no new auth path.

`since` defaults to 0 (return everything in the buffer). `level` defaults to `info`. `limit` defaults to 200, max 1000.

**Why a separate `/ops/cache` instead of letting `brace cache` parse `/ops/status`:** `/ops/status` returns a large payload (errors, JFR, routes, jobs, stats). `brace cache` only needs four numbers. Splitting it keeps each command's bandwidth proportional to what it shows.

### Modification: `since` filter on `/ops/errors`

Today, `/ops/errors` returns everything. Add `?since=<iso8601>` to filter to errors first seen after the timestamp. Required for `brace errors --since 1h` to work without client-side filtering on the full set.

## CLI commands — the agent surface

**Shared conventions across all read commands:**

- **Output format:** auto-detect. Human-readable when `stdout` is a TTY, JSON when piped or redirected. `--json` and `--pretty` are explicit overrides.
- **Exit codes:** zero = healthy, non-zero = needs attention. The exact contract is per-command.
- **`--since` flag:** accepts durations (`10m`, `1h`, `24h`) on commands where it makes sense. The CLI converts to whatever the endpoint expects (ID for logs, ISO8601 for errors).
- **`--env` flag:** overrides `ops.env` from `.brace.local` for one invocation.
- **Authentication:** every command goes through `CliAuth.bearer()`, which:
  1. Loads the private key from `CliConfig.keyPath` (or `OPS_PRIVATE_KEY` env var).
  2. If a non-expired cached token exists at `target/.brace-token`, returns it.
  3. Otherwise builds an `OpsHandler.OpsAuthRequest(publicKey, timestamp, signature, ttlSeconds=3600)` via `Json.mapper().writeValueAsString(...)`, POSTs to `/ops/auth`, parses the response with `Json.mapper().readTree()`, caches the returned `token` + `expiresAt`, and returns it.
  4. Includes the bearer as `Authorization: Bearer <token>` on the actual request.
- **Token caching:** the issued bearer token is cached at `target/.brace-token` along with its `expiresAt` (1-hour TTL by default — chosen so an interactive session of `brace errors`, `brace status`, `brace logs -f` re-uses one token, but a stolen cache file expires fast). Invalidated automatically on expiry or on a 401 response (which triggers one retry with a fresh auth).
- **The browser login-token / exchange flow is irrelevant to read commands.** Only `brace ops dashboard` uses `POST /ops/auth/login-token` and `GET /ops/auth/exchange?token=...` — that path stays in `CliOps` unchanged. Read commands always go through plain bearer.

**`brace errors [--since 1h] [--env prod]`**

- Hits `GET /ops/errors?since=<iso8601>`.
- Human: table with columns `id`, `count`, `last seen`, `route`, `message` (truncated to terminal width).
- JSON: pass-through array.
- Exit non-zero if any unresolved errors exist in the window.

**`brace logs [-f] [--since 10m] [--level info] [--env prod]`**

- Hits `GET /ops/logs?since_ts=<iso8601>&level=<level>` for the initial fetch (CLI converts `--since 10m` to an absolute timestamp). For `-f` polling, switches to `?since=<id>` after the first response by tracking the highest ID seen.
- Human: one line per entry — `[ts] LEVEL message key=val key=val`.
- JSON: NDJSON (one JSON object per line) when `-f`, plain JSON array otherwise.
- `-f` polls every 1 second, tracks the last-seen ID, prints only new lines. Ctrl-C exits cleanly.
- Exit 0 always (read-only).

**`brace status [--env prod]`**

- Hits `GET /ops/status`.
- Human: cards/sections — request rate, error count, slowest routes, cache hit rate, job queue depth, JFR summary.
- JSON: pass-through of the full `/ops/status` payload.
- Exit non-zero if the app is unreachable, error count > 0, or the payload reports a degraded state (e.g. job queue stuck, mailer failing).

**`brace cache [--env prod]` and `brace cache clear [--env prod]`**

- `brace cache` → `GET /ops/cache`, prints `size`, `hits`, `misses`, `hit_rate`, `evictions`.
- `brace cache clear` → `POST /ops/cache/clear`, prints confirmation.
- Exit 0 on success, non-zero on HTTP failure.

**`brace resolve <error-id> [--env prod]`**

- Hits `POST /ops/errors/{id}/resolve`.
- Prints `resolved error 42` or the server's error response.
- Exit 0 on 200, non-zero on 404 or auth failure.

## Agent documentation

Two artifacts ship with this work, because the surface is only useful if agents discover and use it correctly.

**1. `docs/agent-ops-guide.md`** — committed in the brace repo. A short guide written for AI agents (and humans) explaining:

- What each command does, with example output for both human and JSON modes.
- The exit-code contract (zero = healthy, non-zero = needs attention).
- Recommended workflows: checking on production after a deploy, investigating a user-reported error, scheduled health check every N minutes.
- The `--env` switch and how `.brace` resolves URLs.
- How to interpret common failure modes (auth failed → re-run `brace init`; app unreachable → check deployment status).

**2. `app.generateClaudeMd()` extension** — the existing `ClaudeMdGenerator` produces a project CLAUDE.md stub. Add a section to the generated stub that points at `agent-ops-guide.md` and lists the available commands with one-line descriptions. When an agent enters a brace project for the first time, the generated CLAUDE.md tells it: "this project uses brace; for production status use `brace errors`, `brace status`, `brace logs`; see [link] for the full guide."

## Testing

- **`CliConfig`**: parsing precedence (flag > local > committed > default), `ops.env` resolution, missing files, missing keys.
- **`CliInit`**: each local check independently (file present, file missing, partial state), `.gitignore` append idempotency, JSON output shape, exit codes. Remote checks via `TestApp` to a stub `/ops/auth` returning 200/401/connection-refused.
- **`CliAuth`**: bearer fetch happy path, cache hit path, expired-cache refresh, 401 → one retry → fail, missing key file, missing authorized-keys file.
- **`CliOps` / `CliCommands`**: integration tests via `TestApp` that boot a real `OpsHandler`, sign a real token through `CliAuth`, hit each endpoint, and assert the rendered output (both TTY and JSON modes). Use `System.setOut` to a `ByteArrayOutputStream` and a forced TTY/non-TTY indicator since auto-detection isn't testable directly.
- **`LogTap`**: ring buffer eviction, `since` (ID) filtering, `since_ts` filtering, level filtering, concurrent appends from multiple threads. Plus a wiring test that calls `Log.info(...)` / `Log.warn(...)` / `Log.error(...)` and asserts each entry shows up in `LogTap.snapshot()` with the same `Map` content that was printed to stdout.
- **New `OpsHandler` endpoints**: `/ops/logs` (since, since_ts, level, limit), `/ops/cache`, `/ops/errors?since=`. Added to `OpsIntegrationTest`.
- **`brace init` end-to-end**: scaffold a temp directory, run init, assert files created, run init again, assert no overwrites. Remote-check path uses `TestApp` to a stub server returning 200/401/connection-refused.

## Open questions

None at this point. Everything in this document was confirmed in the brainstorming session and re-validated against current `main` after a rebase pulled in the security-hardening, browser-login-token, and typed-route work that landed since the spec was first drafted.
