# Plan: `brace deploy` — CLI and agent ownership

Status: draft
Date: 2026-05-25

## Goal

Make deployment a first-class, agent-callable verb in Brace. Today the framework owns runtime ops (`brace status`, `brace errors`, `brace logs`, `brace check`) but punts deploys to whatever each project happens to use (Dokploy, manual `git push`, etc.). An agent asked to "ship this fix" has no Brace-native way to do that — it has to learn each project's bespoke deploy story.

After this plan, the loop is:

```bash
brace deploy --env prod        # run the deploy
brace check  --env prod --wait # confirm it came up healthy
```

…and an agent can do the same thing, read structured output, and triage failures using the same JSON surface it already uses for `/ops/*`.

## Non-goals

- **Not building a deploy platform.** Brace doesn't run the deploy; it dispatches to one.
- **Not supporting every provider in v1.** First-class Dokploy + a generic shell escape hatch covers ~all current Brace projects. Fly/Render/Kamal/etc. can come later or live in the skill.
- **Not replacing CI/CD.** A `brace deploy` invocation from CI is fine, but the framework doesn't grow a workflow engine.
- **Not handling secrets management at deploy time.** Provider owns env vars; `brace deploy` just triggers. (Phase 6 `brace provision` does bootstrap initial secrets, since *something* has to generate them once.)

## Design

### Layers

```
┌─────────────────────────────────────────┐
│ brace-deploy skill                      │  ← Claude-side, optional
│ (provider quirks, troubleshooting)      │
└──────────────────┬──────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────┐
│ brace deploy CLI verb                   │  ← framework, shipped
│  - dispatches to adapter                │
│  - runs pre/post hooks                  │
│  - structured JSON output + exit codes  │
└──────────────────┬──────────────────────┘
                   │
        ┌──────────┼──────────┐
        ▼          ▼          ▼
   dokploy     shell      (future:
   adapter    adapter      fly, kamal…)
```

- **CLI owns the verb and the safety net.** Pre/post checks, exit codes, JSON output, agent ergonomics.
- **Adapter owns the dispatch.** "Provider X, do a deploy with this config."
- **Skill owns provider knowledge.** First-time wiring, error interpretation, edge cases.

### Config (`.brace`)

```
deploy.provider = dokploy            # or "shell"
deploy.timeout_seconds = 600         # how long to wait for adapter to finish
deploy.health_wait_seconds = 120     # how long to wait for /ops/status to come back green

# Dokploy-specific (in .brace for shared config)
deploy.dokploy.api_url     = https://app.dokploy.com
deploy.dokploy.application = gifmsgbot

# Token (in .brace.local — gitignored, per-developer)
deploy.dokploy.token       = dok_xxx...
# …or fall back to env var if not set in .brace.local:
# deploy.dokploy.token_env = DOKPLOY_TOKEN

# Shell-specific
deploy.shell.command       = git push dokploy main
```

Per-env override possible via `deploy.prod.provider = ...` mirroring how `ops.prod.url` works today.

### Pre/post hooks (provider-agnostic)

`brace deploy` always runs:

1. **Pre-deploy baseline capture.** Snapshot current state from `/ops/status`: version, uptime, unresolved error count, slow-route stats. This is **never** a blocking gate — deploys are often *the fix* for a broken prod. We only fail the pre-phase if the target is unreachable or the config is wrong (i.e., the deploy can't possibly succeed). Application-level problems are recorded as context, not blockers.
2. **Dispatch to adapter.** Adapter is responsible for "deploy is done" — for Dokploy, this means polling the API until status is `done` or `failed`; for shell, it's process exit code. Adapter streams build/deploy logs to stdout during dispatch (see Logs below).
3. **Post-deploy verification — detect deploy-introduced errors.** Naive "is prod healthy?" doesn't work, because error counts in `/ops/status` are cumulative-since-uptime and reset on restart. The signal that actually matters is: *did this deploy introduce error types that didn't exist before?*

   The post-check has two phases, both consuming the server-side `/ops/regressions` endpoint built in Phase 0:

   - **Quick check (default, ~30s wait):** Wait for fresh uptime (`/ops/status.app.uptime` lower than dispatch start) and a healthy status, then query `GET /ops/regressions`. Any entry returned is a *new error kind* the server detected since the deploy restarted it. Empty → clean. Non-empty → report each with route and count.

   - **Watch mode (`--watch <duration>`, e.g. `--watch 10m`):** After the quick check, hold the CLI open and re-poll `/ops/regressions` and `/ops/status` (5xx burst, slow-route degradation) every 10–15 seconds for the configured window. Surface new error types as they appear. At end of window, summarize: *"watched 10m: 0 new error types, 1,240 requests served, no 5xx burst → deploy clean"* or *"watched 10m: 2 new error types appeared 4m after deploy: NPE @ GET /checkout (8 occurrences), TimeoutException @ POST /jobs (3 occurrences) → regression suspected."*

The CLI watch is a convenience for the developer who wants to stay attached. The *real* watch is server-side and continues independent of the CLI — if the dev closes their laptop, regressions still get detected and (if configured) Slack/email-notified via Phase 0's notifier hooks.

This model handles the "deploy is the fix" case naturally: only errors with `firstSeen >= app.startedAt` count as regressions, so old unresolved errors from before the deploy are ignored entirely. The deploy succeeds even if old unresolved errors are still in the table — those are what you're fixing, not what you broke.

This is the real Brace value-add. The framework knows when the app started, knows when each error type first appeared, and can answer "did *your deploy* break anything new?" — which is what you actually want to know, not "is prod globally healthy."

### Logs

Two distinct log streams, both first-class:

**Build/deploy logs** — produced by the provider during dispatch. The agent debugging a failed deploy needs these.

- **TTY mode**: stream live to stdout as the deploy progresses. Shell adapter pipes subprocess output; Dokploy adapter polls the API's log endpoint and prints incrementally.
- **JSON mode (`--json`)**: capture the full log into the result under `phases[].log`. Truncate to last N KB with a pointer to the provider's full log URL when available.
- **`brace deploy --logs <deploymentId>`** — fetch logs for a past deploy after the fact (useful for agents arriving on a deploy that already failed).

**Runtime logs** — already covered by `brace logs --since <deploy-time>`. The post-check phase records `deployedAt` so a follow-up `brace logs --since <deployedAt>` shows only what the new code emitted.

### Output and exit codes

```bash
brace deploy --env prod --json
```

```json
{
  "ok": true,
  "provider": "dokploy",
  "deployedAt": "2026-05-25T14:32:18Z",
  "phases": [
    {"phase": "baseline", "status": "pass", "durationMs": 412,
     "snapshot": {"version": "0.1.5+abc", "uptime": "3d 4h", "errorCount": 7, "slowRoutes": 1}},
    {"phase": "dispatch",  "status": "pass", "durationMs": 47213,
     "providerOutput": {"deploymentId": "dpl_abc123", "buildLogUrl": "https://..."},
     "log": "...truncated to last 8KB..."},
    {"phase": "post-check", "status": "pass", "durationMs": 8194,
     "snapshot":   {"version": "0.1.6+def", "uptime": "8s"},
     "newErrors":  [],
     "watchedFor": "30s"}
  ],
  "summary": "Deployed 0.1.6+def; 0 new error types in 30s"
}
```

With `--watch 10m` and a regression:

```json
{
  "ok": false,
  "phases": [
    ...
    {"phase": "post-check", "status": "regression", "durationMs": 612400,
     "snapshot":   {"version": "0.1.6+def", "uptime": "10m 12s"},
     "newErrors":  [
       {"type": "NullPointerException", "route": "GET /checkout",
        "firstSeen": "2026-05-25T14:36:42Z", "count": 8},
       {"type": "TimeoutException", "route": "POST /jobs",
        "firstSeen": "2026-05-25T14:39:11Z", "count": 3}
     ],
     "watchedFor": "10m"}
  ],
  "summary": "Deployed 0.1.6+def; 2 new error types appeared after deploy"
}
```

Exit codes:
- `0` — deployed, no new error types appeared in the watch window
- `1` — deployed, but new error types appeared after `deployedAt` (regression — run `brace errors --since <deployedAt>` for detail)
- `2` — provider dispatch failed (build/deploy error — read `phases[1].log` or follow `buildLogUrl`)
- `3` — deployed but app didn't come up (post-check timed out waiting for fresh uptime)
- `4` — unreachable / config error / couldn't capture `deployedAt`

These map cleanly to agent decisions: `1` → investigate current prod; `2` → read build log; `3` → roll back or investigate runtime; `4` → fix config.

### Adapter contract

```java
interface DeployAdapter {
    DeployResult deploy(CliConfig cfg, DeployOptions opts) throws Exception;
    Optional<String> currentVersion(CliConfig cfg) throws Exception;   // for pre/post diff
}
```

Adapter returns a `DeployResult` with provider-native output (build log URL, deployment ID, etc.) plus status. The CLI wraps this with pre/post phases.

### Version detection

Post-check needs to know "did the new code actually land." Approach: `/ops/status` already exposes `app.uptime` and we can add `app.version` (from `BraceVersion` plus the app's own version if exposed). Post-check waits for `app.uptime` to be lower than the dispatch start time *and* `app.version` to match the expected new version (when provided).

For shell-adapter deploys where the new version is unknown, fall back to "uptime is fresh" + healthy checks.

## Phases

### Phase 0 — Server-side regression detection (1 day)

Move "did a new error type appear since startup?" from the CLI to the server. The CLI watch in Phase 1 then becomes a thin poll instead of doing the bookkeeping itself.

Why this belongs server-side, not client-side:
- Survives the CLI being closed (`brace deploy --watch 10m && close-laptop` shouldn't lose detection).
- Works for deploys Brace didn't initiate (push-to-git → Dokploy webhook → no `brace deploy` ran, but the server still wants to know).
- Enables push notifications (webhook, email) with nothing client-side running.
- Detection is nearly free — `ErrorStore.record()` already does an existence check by `(error_type, route)` to decide insert-vs-increment. If that lookup misses, it's a *new error kind*. Fire an event there.

Pieces:

- **New error → event.** In `ErrorStore.record()`, when the existence lookup returns empty (a brand new `(type, route)` for this process lifetime), publish to an in-process `RegressionTracker`.
- **`RegressionTracker` state.** Tracks: `{type, route, firstSeen, count, acknowledgedAt?}`. Lives in memory, seeded from DB on startup with errors whose `firstSeen >= app.startedAt` (so a restart mid-deploy doesn't lose context). Optionally ignores the first N seconds after startup (warmup, configurable via `regressions.warmup_seconds`, default 30s) to avoid flagging "DB connect failure during cold boot" as a regression.
- **`GET /ops/regressions`** — returns the current set of new error kinds since startup, with the same JSON shape as `/ops/errors` plus an `acknowledged: bool` field.
- **`POST /ops/regressions/{id}/acknowledge`** — operator says "I've seen this, stop flagging it." Used by `brace deploy --acknowledge` and by the dashboard.
- **Notifier hooks (opt-in).** A small `Notifier` interface with implementations: `WebhookNotifier`, `MailerNotifier` (reuses existing `Mailer`), `LogNotifier`. Config:
  ```
  regressions.notify.webhook = https://hooks.slack.com/...
  regressions.notify.email   = ops@example.com
  regressions.notify.debounce_seconds = 60   # don't spam on rapid bursts
  ```
  This covers the "app.error.new" TODO line directly. "app.error.spike" (existing error type whose rate jumps) is a separate, harder problem — punt to a future phase.
- **Dashboard surfacing.** Add a "Regressions since startup" banner to `/ops/dashboard` when the list is non-empty.

Acceptance: a fresh app start + an exception in a new code path produces an entry in `/ops/regressions` and (if configured) a webhook/email. Existing unresolved errors from before startup do *not* show up.

### Phase 1 — Shell adapter MVP (1–2 days)

- `brace deploy` command in `Cli.java` → `CliDeploy.run()`
- `DeployAdapter` interface + `ShellAdapter`
- Config keys + parsing
- Pre-check (`brace check` reuse) + post-check (poll `/ops/status` until uptime is fresh & healthy)
- Exit codes, `--json` output, `--force` flag
- Tests: integration test against `TestApp` simulating a deploy via shell command

Acceptance: `brace deploy --env prod` works for any project whose deploy is a single command (`git push dokploy main`, `fly deploy`, etc.) with full pre/post safety net.

### Phase 2 — Dokploy adapter (1–2 days)

- `DokployAdapter` — REST calls to Dokploy API to trigger redeploy and poll status
- Token resolution order: `deploy.dokploy.token` in `.brace.local` → env var named by `deploy.dokploy.token_env` → fail with a clear "no token" error pointing at both options
- Polls Dokploy's log endpoint during dispatch, streams to stdout in TTY mode and captures last N KB into JSON in `--json` mode
- Surfaces Dokploy's build log URL in JSON output for full-log follow-up
- Tests: mock Dokploy server (see `MockHttpServer` pattern if it exists, else inline `HttpServer`)

Acceptance: Brace projects on Dokploy can `brace deploy` without configuring a shell command.

### Phase 3 — Version tracking (0.5 day)

- Add `app.version` field to `/ops/status` (framework version + optional app version)
- App-version source: `Brace.app("name", "1.2.3")` or env var, opt-in
- Post-check uses version diff when available, uptime diff when not
- Document in `BRACE-AGENTS.md` storage table

Acceptance: post-check can distinguish "old code restarted" from "new code is running" when the app opts in.

### Phase 4 — Agent skill (`brace-deploy`) (0.5 day)

Lives in `~/.claude/skills/brace-deploy/` (or shipped with `brace`).

Skill knows:
- When to use `brace deploy` (any "deploy this" / "ship this" / "push to prod" request in a Brace project)
- How to read exit codes and route to next action (read build log on `2`, run `brace errors` on `3`)
- Provider-specific gotchas (Dokploy webhook lag, common shell adapter pitfalls)
- How to roll back manually per provider (until Phase 5)

Triggers on: presence of `.brace` file + deploy-intent verbs in user request.

Acceptance: Claude, asked to "deploy this fix to prod," runs `brace deploy --env prod --json`, reads the JSON, and either reports success or starts triage based on exit code.

### Phase 5 — Rollback (deferred, scope TBD)

`brace deploy rollback --env prod` — provider-dependent:
- Dokploy: redeploy previous image tag via API
- Shell: requires user to configure `deploy.shell.rollback_command`

Punt until we feel pain. Phase 4 skill can document manual rollback per provider in the meantime.

### Phase 6 — `brace provision` (greenfield app setup, 2–3 days)

Today the agent loop is: a human clicks through the Dokploy UI once to create the project / Postgres / app / domain, then `brace deploy` takes over forever after. Phase 6 closes that gap so an agent asked to *"create and ship a new brace app"* can do the whole thing.

Scope: one-time, idempotent provisioning of all services a brace app needs on the configured provider. Not part of `brace deploy` — separate verb, because the failure modes, auth scope, and idempotency semantics are different.

```bash
brace provision --provider dokploy --env prod
  → project.create / environment.create  (idempotent: skip if exists)
  → postgres.create                       (capture creds)
  → application.create                    (from git URL in .brace)
  → application.saveEnvironment           (DATABASE_URL, SESSION_SECRET, etc)
  → domain.create                         (Let's Encrypt via Dokploy)
  → write service IDs back into .brace
```

The agent loop becomes:

```bash
brace provision --provider dokploy --env prod   # one-time, idempotent
brace deploy    --env prod                      # repeatable
brace check     --env prod --wait               # health
```

#### Adapter contract

```java
interface ProvisionAdapter {
    ProvisionResult provision(CliConfig cfg, ProvisionOptions opts) throws Exception;
}
```

Returns a `ProvisionResult` with provider-native IDs for each created service (project, environment, postgres, application, domain) plus per-step status. The CLI writes the IDs back into `.brace` so subsequent `brace deploy` / `brace provision` calls reference them.

Likely sharing the same provider implementation class as `DeployAdapter` (one `DokployAdapter` implements both), since the auth, HTTP client, and config plumbing are identical.

#### Config additions

```
# Inputs to provision (in .brace, gitignored creds in .brace.local)
deploy.dokploy.project       = my-app           # logical project name
deploy.dokploy.git_url        = git@github.com:me/my-app.git
deploy.dokploy.git_branch     = main
deploy.dokploy.domain         = my-app.example.com
deploy.dokploy.postgres_name  = my-app-db

# Written back by `brace provision` (in .brace, source-of-truth for IDs)
deploy.dokploy.project_id      = prj_xxx
deploy.dokploy.environment_id  = env_xxx
deploy.dokploy.application_id  = app_xxx
deploy.dokploy.postgres_id     = pg_xxx
```

`brace deploy` should prefer `application_id` over `application` (name) when present — names can collide, IDs can't. Phase 2 config schema should accommodate this so the upgrade is non-breaking.

#### Secrets bootstrap

Brace requires a 32+ char session secret. `brace provision` generates it itself (cryptographically random) and writes it via `application.saveEnvironment` as `SESSION_SECRET`. It also surfaces the value once in `--json` output (so the operator/agent can store it elsewhere if desired) but does *not* persist it locally — Dokploy is the source of truth for env vars going forward.

Same pattern for the Postgres password: generated by `brace provision`, set on the Postgres service, threaded into the application's `DATABASE_URL` env var, never written to `.brace`.

#### Idempotency

Every step is "exists? skip or update : create." The adapter looks up by ID first (from `.brace`), falls back to name lookup, only creates if neither match. `brace provision` can be safely re-run — to add a domain, rotate a secret (with `--rotate-secret`), or recover from a half-failed first run.

#### Output and exit codes

```json
{
  "ok": true,
  "provider": "dokploy",
  "steps": [
    {"step": "project",     "status": "created", "id": "prj_xxx"},
    {"step": "environment", "status": "created", "id": "env_xxx"},
    {"step": "postgres",    "status": "created", "id": "pg_xxx",
     "credentials": {"user": "...", "password": "...", "database": "..."}},
    {"step": "application", "status": "created", "id": "app_xxx"},
    {"step": "environment_vars", "status": "set",
     "keys": ["DATABASE_URL", "SESSION_SECRET"]},
    {"step": "domain",      "status": "created", "host": "my-app.example.com"}
  ],
  "summary": "Provisioned my-app on dokploy; ready to `brace deploy --env prod`"
}
```

Exit codes:
- `0` — all services exist (created or already-present)
- `2` — provider API error mid-way (partial state; safe to re-run)
- `4` — config error / unreachable / auth failure

#### DNS

Out of scope for v1. `brace provision` creates the domain in Dokploy and surfaces "point `my-app.example.com` at `<host-ip>`" in the output. A future `--dns-provider cloudflare` flag could close this loop, but Phase 6 punts.

#### Tests

Mock Dokploy server (extend the Phase 2 mock) covering: clean create, idempotent re-run, partial failure recovery, name-collision handling.

Acceptance: an agent in a fresh repo with `.brace` configured for Dokploy can run `brace provision --provider dokploy --env prod` followed by `brace deploy --env prod` and get a live app — no Dokploy UI clicks.

## What changes in the codebase

New files:
- `src/main/java/com/larvalabs/brace/RegressionTracker.java` (phase 0)
- `src/main/java/com/larvalabs/brace/Notifier.java` + `WebhookNotifier`, `MailerNotifier`, `LogNotifier` (phase 0)
- `src/main/java/com/larvalabs/brace/CliDeploy.java`
- `src/main/java/com/larvalabs/brace/deploy/DeployAdapter.java`
- `src/main/java/com/larvalabs/brace/deploy/DeployResult.java`
- `src/main/java/com/larvalabs/brace/deploy/ShellAdapter.java`
- `src/main/java/com/larvalabs/brace/deploy/DokployAdapter.java` (phase 2)
- `src/main/java/com/larvalabs/brace/CliProvision.java` (phase 6)
- `src/main/java/com/larvalabs/brace/deploy/ProvisionAdapter.java` (phase 6)
- `src/main/java/com/larvalabs/brace/deploy/ProvisionResult.java` (phase 6)

Modified:
- `src/main/java/com/larvalabs/brace/ErrorStore.java` — publish to `RegressionTracker` when a new `(type, route)` first appears (phase 0)
- `src/main/java/com/larvalabs/brace/OpsHandler.java` — add `GET /ops/regressions`, `POST /ops/regressions/{id}/acknowledge` (phase 0); add `app.version` to status (phase 3)
- `src/main/java/com/larvalabs/brace/OpsDashboard.java` — surface regressions banner (phase 0)
- `src/main/java/com/larvalabs/brace/Brace.java` — wire `RegressionTracker` + notifiers from config (phase 0)
- `src/main/java/com/larvalabs/brace/Cli.java` — add `"deploy"` case; add `"provision"` case (phase 6)
- `src/main/java/com/larvalabs/brace/CliConfig.java` — parse `deploy.*` keys; support write-back of provisioned service IDs (phase 6)
- `src/main/java/com/larvalabs/brace/deploy/DokployAdapter.java` — implement `ProvisionAdapter` in addition to `DeployAdapter` (phase 6)
- `BRACE-AGENTS.md` — document the deploy command + regressions endpoint + JSON shape
- `docs/agent-ops-guide.md` — add deploy + regression-watch workflow section
- `bin/brace` — route `deploy` subcommand

## Open questions

1. **Should `brace deploy` block on the build, or fire-and-forget with a separate `brace deploy --status`?** Recommend blocking by default (matches agent expectations); add `--detach` later if needed.
2. **Should the skill ship with `brace` (in the distribution zip) or live in the user's `~/.claude/skills/`?** Probably both — bundle a copy in `share/brace/skills/brace-deploy/` and let users symlink. Decide in Phase 4.
3. **What about migrations?** Brace migrations run on app startup, so they happen as part of dispatch automatically. Document this; no separate phase needed. A future `brace migrate --dry-run --env prod` could be a useful pre-check.
4. **Multi-instance deploys?** Out of scope for v1 — Brace projects are single-instance today. Revisit if/when that changes.

## Why this beats the alternatives

- **vs skill-only**: agents that aren't Claude (CI scripts, other tooling) get nothing. CLI verb is universal.
- **vs CLI-only**: provider quirks would either bloat the adapter library or get ignored. Skill handles the long tail.
- **vs status quo**: nothing currently ensures "deploy succeeded AND new code is healthy" — `git push` and walking away is the norm. Brace already has the health check; wiring it into deploy is the missing 20 lines.
