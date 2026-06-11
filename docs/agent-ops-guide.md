# Brace Agent Ops Guide

The single ops reference for inspecting and operating a running Brace app — written for
AI agents, useful for humans. It covers auth setup, the CLI and HTTP endpoint surfaces,
health-check runbooks, scaling, and data retention. In a Brace project this document is
written to the project root as **`BRACE-OPS.md`** (`brace new` writes it at scaffold time;
`brace agents-md` refreshes it together with `BRACE-AGENTS.md`, the dev-time API
reference). In the brace repo it lives at `docs/agent-ops-guide.md`.

## Agent health check (start here)

When asked to check on production, act as on-call, or verify app health, start with this
single command:

```bash
brace check --env prod --json
```

If `healthy` is `true`, report healthy and stop. If `false`, read `summary` for an
overview, then look at each check with status `"fail"` or `"warn"`. Use the `followUp`
command on any failed check to investigate further.

**Do not run `brace status` first.** `brace check` already fetches status data and
applies threshold analysis. Only use the individual commands (`brace errors`,
`brace logs`, `brace status`) for follow-up investigation.

**Output structure:**

```json
{
  "healthy": false,
  "summary": "2 issues: 3 unresolved errors, 1 failing job",
  "checks": [
    {
      "name": "errors",
      "status": "fail",
      "message": "3 unresolved errors",
      "details": [{"type": "NullPointerException", "route": "GET /posts/{id}", "count": 3, "id": "42"}],
      "followUp": "brace errors --env prod --json"
    }
  ]
}
```

**Checks performed:** reachability, errors, http_5xx, slow_routes, heap, gc_pressure, jobs, cache, recent_logs.

**Thresholds** are configurable in `.brace`:

```
check.slow_route_ms=500
check.heap_warn_percent=70
check.heap_fail_percent=80
check.gc_pause_ms=50
check.cache_hit_rate=0.5
check.log_window_minutes=30
```

## CLI commands

| Command | Purpose | Exit code |
|---|---|---|
| `brace status [--env prod]` | Full system snapshot | 0 healthy / 1 errors exist / 2 unreachable |
| `brace check [--env prod]` | Run all health checks, return structured verdict | 0 all pass / 1 issues / 2 unreachable |
| `brace errors [--since 1h] [--full] [--env prod]` | List unresolved error summaries (`--full` for the per-row detail shape) | 0 none / 1 some / 2 unreachable |
| `brace errors <id> [--env prod]` | Full detail for one error (stack trace, request context, headers, queries) | 0 / 1 not found / 2 unreachable |
| `brace logs [-f] [--since 10m] [--level warn] [--limit 50] [--env prod]` | Tail structured log entries (`--limit` caps entries; server default 200) | 0 |
| `brace cache [--env prod]` | Cache size, hit rate, evictions | 0 / 2 unreachable |
| `brace cache clear [--env prod]` | Empty the cache | 0 / 2 unreachable |
| `brace resolve <id> [--env prod]` | Mark an error as resolved | 0 / 1 not found / 2 unreachable |
| `brace init` | Scaffold `.brace`/`.brace.local` and run ops readiness checks | 0 ok / 1 issues |
| `brace ops keypair [--label <l>] [--read-only]` | Generate an Ed25519 keypair and wire it up (see Setup) | 0 / 1 |
| `brace ops dashboard` | Open the ops dashboard in a browser (login handled via token exchange) | 0 / 1 |

All commands auto-detect output: human-readable table when stdout is a TTY, JSON when
piped. Force with `--json` or `--pretty`.

## HTTP endpoints

The CLI commands call these under the hood. Use them directly when you need raw JSON or
aren't in a project directory.

| Endpoint | Returns |
|---|---|
| `GET /ops/status` | System snapshot (app, http, jvm, error summary, jobs, cache, metrics); `?include=timeseries,profiling` for the bulky blocks |
| `GET /ops/errors[?status=open&since=<iso8601>&full=true]` | Tracked error **summaries** (`id, errorType, message, route, occurrenceCount, firstSeen, lastSeen, at`), filterable by status and time window; `?full=true` returns the pre-0.1.7 full-detail rows |
| `GET /ops/errors/{id}` | Full detail for one error: `stackTrace`, `requestDetail`, `queriesBefore`, `requestHeaders` plus the summary fields; 404 for unknown ids |
| `GET /ops/logs[?since=<id>&since_ts=<iso8601>&level=<info\|warn\|error>&limit=200]` | Recent log entries from in-memory ring buffer |
| `GET /ops/cache` | Cache stats: shared, size, hits, misses, hitRate, evictions |
| `GET /ops/routes` | All registered routes |
| `GET /ops/regressions` | New error kinds since startup (the `/ops/errors` shape + an `acknowledged` flag). The on-call wake signal — empty means no new error types this process lifetime. |
| `GET /ops/dashboard` | HTML dashboard (browser) |
| `POST /ops/errors/{id}/resolve` | Mark error resolved (returns the resolved record with `Accept: application/json`) — **control scope** |
| `POST /ops/cache/clear` | Clear cache (returns `{"cleared": true, "scope": "instance"|"fleet"}` with `Accept: application/json`; `fleet` when a shared backend is configured) — **control scope** |
| `POST /ops/regressions/{id}/acknowledge` | Stop flagging a regression (returns `{"acknowledged": true}`) — **control scope** |

Read endpoints (the `GET`s above) require a `read`-scope token; the mutating `POST`s
require `control`. See "Token scopes" below. Authenticate with `POST /ops/auth`
(see "Auth protocol (v2)" below), then pass `Authorization: Bearer <token>`.

### Regression notifications

When a new error kind first appears since startup, Brace notifies the registered
notifiers once (recurrences don't re-notify). A `LogNotifier` is always attached (emits a
`regression` log event); add more with `app.notifyRegressions(new WebhookNotifier(slackUrl),
new MailerNotifier(mailer, "ops@example.com"))`. `WebhookNotifier` posts a
Slack/Mattermost-shape `{"text": "..."}` payload. `app.regressionsWarmup(seconds)`
(default 30) suppresses cold-boot noise. Requires a database (regressions ride the error
store).

**Multi-instance.** On Postgres the regression set is shared fleet-wide (table
`brace_regressions`): a new error kind notifies **exactly once** across all instances,
the `/ops/regressions` list and acknowledge are consistent on every box, and the
regression `id` is a stable string (a hash of `type`+`route`+`deploy`) — so an id listed
on one instance acknowledges correctly on another. The baseline is anchored to a
**deploy marker** set with `app.deploy("<git-sha>")` (or the `BRACE_DEPLOY` env var;
defaults to `"default"`): every instance of one deploy shares it, and a new deploy
re-evaluates regressions from a clean baseline. Without Postgres the set is per-process
(single-server).

## What `/ops/status` returns

```json
{
  "app": { "uptime": "2h 15m", "startedAt": "...", "javaVersion": "21" },
  "http": {
    "statusCodes": { "200": 1523, "404": 12, "500": 3 },
    "slowestRoutes": [{ "route": "GET /search", "count": 45, "avgMs": 234.5 }]
  },
  "jvm": {
    "heap": { "usedMB": 128, "maxMB": 512 },
    "cpu": { "jvmUser": 0.12 },
    "threads": { "active": 42 },
    "gc": { "totalCount": 15, "avgPauseMs": 2.1, "recentPauses": [...] }
  },
  "errors": {
    "count": 3,
    "recent": [{
      "id": 7,
      "errorType": "NullPointerException",
      "message": "Cannot invoke method on null",
      "route": "GET /posts/{id}",
      "occurrenceCount": 3,
      "lastSeen": "..."
    }]
  },
  "jobs": { "scheduled": [{ "name": "cleanup", "lastStatus": "ok", "lastError": null }] },
  "cache": { "shared": false, "entries": 42, "hits": 1200, "misses": 80 },
  "metrics": { "counters": {...}, "gauges": {...}, "timers": {...} }
}
```

Notes on the shape:

- `errors.count` is the unresolved error count (database-backed when one is configured)
  and `errors.recent` the 5 most recent summaries — no stack traces. Drill into one error
  with `GET /ops/errors/{id}` / `brace errors <id>`. `id` is present when a database backs
  the error store.
- Two bulky blocks are **opt-in** via `?include=timeseries,profiling`:
  `timeseries.minutes` (60 per-minute snapshots: `ts`, `requests`, `errors`, `avgMs`) and
  `jvm.profiling` (JFR `hotMethods` + `topAllocations`). `jvm.cpu` and `jvm.gc` appear
  only when the JFR profiler is attached (it always is when ops is enabled).

## Runbooks

### Detailed status inspection

> **Note:** For most health checks, use `brace check` above. Use `brace status` directly
> when you need the full raw data for deeper investigation.

```bash
brace status --env prod --json
```

Read the output in this order:

1. **`app.uptime`** — if very short, the app recently restarted. Check logs for crash/OOM.
2. **`http.statusCodes`** — look at 5xx count. Any 500s mean unhandled exceptions.
3. **`errors.count`** — if > 0, switch to the error investigation runbook below.
4. **`http.slowestRoutes`** — anything over 500ms avg deserves investigation.
5. **`jvm.heap.usedMB` vs `maxMB`** — if usage is above 80% of max, memory pressure is likely. Check `jvm.gc.avgPauseMs` for GC impact.
6. **`jobs.scheduled`** — any job with `lastStatus` != `"ok"` needs attention.
7. **`cache`** — compute hit rate (hits / (hits + misses)). Below 50% means the cache isn't helping; review TTLs and key strategies.

If everything looks normal, report healthy and stop.

### Error investigation

When users report errors or `brace check`/`brace status` shows a non-zero error count:

```bash
brace errors --env prod --json
# or filter to one route:
brace errors --env prod --json | jq '.[] | select(.route == "/checkout")'
```

Each summary includes: `id`, `errorType`, `message`, `route`, `occurrenceCount`,
`firstSeen`, `lastSeen`, and `at` — the first stack frame in app code. That is usually
enough to locate the bug without pulling the full trace.

**Triage by route and count.** High-count errors on critical routes are the priority. Then:

1. **Start from `at`** — it names the app class/method/line that threw. If you need the full stack trace and request context, fetch one error's detail:
   ```bash
   brace errors <id> --env prod --json
   ```
2. **Check recent logs around the error time:**
   ```bash
   brace logs --env prod --since 30m --level warn --json
   ```
   Look for log entries on the same route or with related context (e.g., the same user ID, request path).
3. **Check if it's new or recurring** — compare `firstSeen` vs `lastSeen`. A new error after a deploy is likely a regression; a long-running error is a latent bug.
4. **Find the code** — use the route from the error (e.g., `GET /posts/{id}`) to find the handler registration in `main()`, then trace into the handler method.
5. **Fix, deploy, then resolve:**
   ```bash
   brace resolve <id> --env prod
   ```

### Slow endpoint diagnosis

When `brace status` shows a route with high average latency:

1. **Get the route name** from `http.slowestRoutes` (e.g., `GET /search`).
2. **Check logs for that route:**
   ```bash
   brace logs --env prod --since 1h --json | jq '.[] | select(.path == "/search")'
   ```
   Look at `durationMs` and `queries` / `queryMs` fields in the structured log entries.
3. **If `queryMs` dominates `durationMs`** — the database is the bottleneck. Look at the handler code for N+1 queries, missing indexes, or full table scans.
4. **If `durationMs` is high but `queryMs` is low** — the handler is CPU-bound or waiting on an external service. Check `jvm.profiling.hotMethods` in status output (opt-in: `GET /ops/status?include=profiling`).
5. **Check for GC pauses** — `jvm.gc.avgPauseMs` above 50ms can cause latency spikes across all routes.
6. **Check heap pressure** — if heap usage is near max, GC runs more frequently and takes longer.

### Post-deploy verification

Run immediately after deploying a new version:

```bash
brace check --env prod --json           # threshold verdict on the new build
brace status --env prod --json          # confirm app restarted (short uptime)
brace errors --env prod --since 5m --json   # any new errors since deploy?
brace logs --env prod --since 5m --level error --json   # any error-level log entries?
```

If errors appeared that weren't present before the deploy, they are likely regressions.
Investigate with the error runbook above.

### Cache diagnosis

When performance is worse than expected or cache hit rate is low:

```bash
brace cache --env prod --json
```

- **`hitRate` below 0.5** — cache is missing more than it hits. Check that frequently-accessed data is being cached, TTLs aren't too short, and cache keys match the access pattern.
- **`evictions` growing fast** — cache is full and dropping entries. Consider whether the working set is too large for the configured size.
- **Multi-server note.** Check the `"shared"` field. On a shared backend, `size` is fleet-wide (one store) but `hitRate`/`hits`/`misses`/`evictions` are **per-instance** — each box you query reports its own numbers, so hit rate can differ between servers even though they share the same data. On the in-process default (`"shared": false`), everything — data included — is per-instance, so a low hit rate on one box says nothing about the others.
- **Stale data suspected** — clear and let it repopulate:
  ```bash
  brace cache clear --env prod
  ```

### Scheduled health check (cron / agent)

```bash
# Exit non-zero if any health check fails OR there are recent unresolved errors
brace check --env prod || alert "brace check failed"
brace errors --env prod --since 15m || alert "new errors"
```

## Cache clear semantics across a fleet

`clear()` and `POST /ops/cache/clear` empty the cached **data**:

- **Shared backend:** a single fleet-wide `TRUNCATE` — one call clears every server's
  view. There is no separate in-memory data tier, so nothing stale is left behind (the
  near-cache that *would* introduce per-server L1 copies is deferred — see the design doc).
- **In-process default:** clears only the instance that received the call; other servers
  keep their copies until TTL.

Two things are **not** cleared fleet-wide, because they live in each instance's memory:

- **Hit/miss/eviction stats are per-instance.** A clear resets the counters only on the
  box that handled it; every server reports its own hit rate on the dashboard by design.
  So after a fleet-wide data clear, other boxes' stat numbers stay until they next drain —
  that's expected, not a bug.
- **Only the `Cache` registered via `app.cache(...)`** is touched by the ops endpoint. If
  you run the two-instance pattern (a separate `new Cache(...)` you hold yourself), clear
  that one in your own code.

The clear response reports which happened: `{"cleared": true, "scope": "fleet"}` on a
shared backend, `"instance"` otherwise. The dashboard shows a `shared`/`in-process` label
and a `[clear fleet]` vs `[clear]` button.

## Setup — ops auth

Once per project:

```bash
brace init         # scaffolds .brace, .brace.local, .gitignore
brace ops keypair  # generates an Ed25519 keypair and wires it up
```

Then enable the endpoints in `main()` with `app.ops("ops-authorized-keys")`.

`brace new` writes both key files at scaffold time (the initial `dev` entry corresponds
to the local `ops-private.key`). After that, `brace ops keypair` generates a new keypair
and writes **both halves itself**: it creates `ops-private.key` (owner-only permissions,
gitignored — never committed, never leaves your machine) and adds the public entry to
`ops-authorized-keys`, labeled `<git-user.email>@<hostname>` by default so each
developer/machine has its own line (override with `--label`). You do **not** copy a
printed key by hand. It is safe to re-run: it **refuses to overwrite** an existing
`ops-private.key` (delete it first to rotate), and the authorized entry is keyed by label
— re-running with the same label *replaces* that line rather than appending an orphan.
Different developers get different labels, so they never clobber each other's entries.

### Key files

Ops auth uses Ed25519 keypairs. Two files matter:

- **`ops-authorized-keys`** — committed. Public keys allowed to authenticate, one per
  line as `<base64-pubkey> [scope:read|scope:control] <label>`. Loaded by
  `app.ops("ops-authorized-keys")` — this is the allow-list the *server* checks.
- **`ops-private.key`** — gitignored, per-developer. Three lines: a comment, the base64
  private key, and the matching public key. Path is recorded in `.brace.local` as
  `ops.key=...`.

### Token scopes (read-only keys)

Each authorized key has a **scope ceiling** that caps every token it can mint. Two scopes:

- **`read`** — read endpoints only: `status`, `errors`, `logs`, `routes`, `cache` stats.
- **`control`** — everything `read` can do, plus mutating endpoints: `cache/clear`,
  `errors/{id}/resolve`. `control` implies `read`.

A line with no `scope:` marker defaults to `control` (backward compatible). Mark a key
read-only by adding `scope:read`:

```
<base64-pubkey>  scope:read  oncall-agent
```

`POST /ops/auth` caps the minted token at the key's ceiling, so a `scope:read` key
**cannot** obtain a control token even if it requests one — escalation is impossible by
construction. This is what lets you hand an autonomous agent (e.g. `brace-oncall`) a key
that can pull `logs`/`errors`/`status` but never clear the cache or resolve errors.
Generate one with `brace ops keypair --read-only --label oncall-agent`. Tokens also
carry a `kid` (key fingerprint) so ops access can be attributed to a key.

**Audit log.** Every authenticated ops request is recorded as a structured `ops.access`
log event (`kid`, scope, method, path, `granted`) — including authenticated-but-scope-denied
attempts (`granted=false`). It rides the normal log stream, so a stolen or misused key is
visible after the fact via `brace logs` (filter on `event=ops.access`); no separate store
and works with or without a database.

### Ops session secret (multi-instance)

Ops tokens and the browser login cookie are HMAC-signed. The signing secret is resolved
at startup in this order: an explicit **`app.opsSecret("…")`** → derived from the
**session secret** (`app.sessions(…)`) → a per-process random value (with a startup
warning). The first two are shared config, identical on every instance, so the **browser
login works behind a load balancer**: the `login-token` → `exchange` handshake is
stateless (a short-lived HMAC token, no server-side store) and a session cookie minted on
one instance validates on any other. The per-process fallback is single-instance only —
set `opsSecret(…)` (or `sessions(…)`) on any multi-instance deployment, or ops login will
fail ~(N−1)/N of the time behind an LB. Use `opsSecret(…)` for bearer-token APIs that
enable ops without sessions.

### The deploy-phase loop

Getting ops auth working is inherently a two-machine handshake: the server's URL has to
come *to* you, and your public key has to get *to* the server. `brace init` is idempotent
and is the spine of this — run it, do the one thing it asks, run it again, until it's
green.

1. **Generate your key** (above). Your public line lands in `ops-authorized-keys`.
2. **Set the prod URL.** Uncomment/add `ops.prod.url=https://your-app...` in `.brace`
   (you get this once the server exists).
3. **Get `ops-authorized-keys` onto the server.** It's committed, so this just means
   deploy — the running app reads it at startup. (A push-to-deploy or `brace deploy`
   ships it like any other file.)
4. **Verify.** Re-run `brace init` (once `ops.prod.url` is set, prod becomes the default
   environment). It performs a remote check: reachability + whether the server accepted
   your key, and prints the exact next action for whatever is still missing. Repeat from
   the failing step until every check is ✓.

### Rotating or adding keys

- **Rotate your own key:** `rm ops-private.key && brace ops keypair`, then redeploy.
  Because the label is stable (`email@host`), the new public key *replaces* your existing
  line in `ops-authorized-keys` in place — no orphaned, still-trusted key left behind.
- **Add another developer:** they run `brace ops keypair` on their own machine. Their
  distinct `email@host` label gets its own line; committing it never clobbers anyone
  else's entry. Commit the updated `ops-authorized-keys` and deploy so servers accept
  the new operator.
- **Check whether your local key is already authorized:** there is currently no
  `brace ops whoami` — `grep -F "$(sed -n '3p' ops-private.key)" ops-authorized-keys` is
  the manual check.
- **Registering a coworker's existing public key:** there is no CLI for this today;
  append the line to `ops-authorized-keys` by hand (raw base64 public key, optional
  `scope:read` marker, then the label).

## Credential channels

All ops endpoints accept credentials through exactly two channels:

1. `Authorization: Bearer <token>` header — the standard channel for the CLI,
   scripts, and the dashboard's htmx polling.
2. The `__brace_ops_session` httpOnly cookie — set automatically by the
   browser exchange flow (`brace ops dashboard` → `/ops/auth/exchange`).

**`?token=` query-parameter auth is not accepted on general ops endpoints.**
Tokens in URLs leak into proxy access logs, browser history, and the `Referer`
header on outbound links. Always pass credentials in the `Authorization: Bearer`
header:

```bash
# Correct
curl -H "Authorization: Bearer $TOKEN" https://app.example.com/ops/status

# Wrong — 401
curl "https://app.example.com/ops/status?token=$TOKEN"
```

The only exception is `/ops/auth/exchange?token=...`, which is the browser-redirect
handoff from the CLI. That endpoint accepts `?token=` because there is no other
channel that can carry a credential into a plain GET redirect. The token it accepts
is short-lived (60s) and scope-capped. See `docs/SECURITY.md` → "Ops Endpoints".

## Auth protocol (v2)

The CLI handles this for you. It matters only if you implement the handshake
yourself (an agent talking to `/ops/*` over raw HTTP).

`POST /ops/auth` with a JSON body:

```json
{
  "v": "2",
  "publicKey": "<base64 Ed25519 public key>",
  "timestamp": "<ISO-8601 instant, e.g. 2026-06-09T12:00:00Z>",
  "nonce": "<base64url of 16+ random bytes, fresh per attempt>",
  "signature": "<base64 Ed25519 signature>",
  "ttlSeconds": 3600
}
```

The signature is computed over exactly:

```
publicKey + "\n" + timestamp + "\n" + nonce
```

(newline-delimited; none of the three components can contain a newline). A
200 response carries `{"token": "...", "expiresAt": "...", "scope": "..."}` —
pass the token as `Authorization: Bearer <token>` on every `/ops/*` call.
Optional `scope` in the request body (`read`/`control`) caps the minted token
below your key's ceiling.

Rules the server enforces:

- **Timestamp freshness:** the timestamp must be within ±30 seconds of server
  time, or you get `401 Stale timestamp`.
- **Nonce single-use:** generate a fresh random nonce (16+ bytes, base64url)
  for every attempt. A reused nonce gets `401 Nonce already used`.
- **Key binding:** the public key is part of the signed message, so a
  signature is only valid for the key that produced it.

**Replay caveat (per-instance, best-effort):** the seen-nonce set is held in
memory on each server instance — ops works without shared fleet state, so it
cannot be fleet-global. Behind a load balancer, a captured auth request could
still be replayed against a *different* instance within the ±30s window. Keep
`/ops/*` behind HTTPS (the protocol assumes the request body is not observable
in transit); see `docs/SECURITY.md` → "Ops Endpoints".

**Protocol v1 is deprecated.** The pre-0.1.7 format (no `v`, no `nonce`,
signature over the timestamp alone) is still accepted this release — the
server logs a deprecation warning — and will be **rejected in a future
release** with `ops auth protocol v2 required; upgrade the brace CLI`. If you
implemented v1 by hand, switch to the v2 signing payload above.

## Environment selection

`.brace` defines URLs:

```
ops.local.url=http://localhost:8080
ops.prod.url=https://app.example.com
```

`.brace.local` selects the active environment (gitignored, per developer):

```
ops.env=local
ops.key=ops-private.key
```

Override per command with `--env prod`. All commands accept `--env`. Default env:
prod when `ops.prod.url` is configured, else local.

## Scaling horizontally

Brace runs correctly as **N instances behind a load balancer sharing one Postgres** — no
sticky sessions. Full contract: `docs/scaling.md` in the brace repo. Essentials:

- **Requirements:** run on **Postgres**; set the **session secret from config/env
  identical on every instance** (per-process secrets break cross-box cookies — Brace
  warns at startup); optionally set a **deploy marker** (`BRACE_DEPLOY` /
  `app.deploy("<sha>")`) for per-deploy regression baselines.
- **Automatic on Postgres** (no code change): sessions, CSRF, durable jobs, recurring
  scheduler (once-per-interval cluster-wide), WebSocket broadcast (`LISTEN`/`NOTIFY`),
  rate limiter (shared counter — enforced cluster-wide, not per instance), ops console
  login (shared secret), regression detection (shared table), instance-tagged metrics feed.
- **Opt-in:** the shared cache backend (`CacheBackend.postgres(dbFactory)`) — per-process
  by default even on Postgres, since it trades latency for consistency.
- **Per-instance by design:** `/ops/dashboard`, `/ops/status`, `/ops/logs`, JFR/heap
  reflect the serving box (`/ops/status` carries `app.instanceId`). Use an external
  aggregator over the instance-tagged `ops_timeseries` feed + stdout JSON logs for the
  fleet view.
- **Watch:** rate-limiter DB load on busy servers (Redis recommended for very high
  volume / hot keys — see `docs/2026-06-07-rate-limiter-load.md`); ephemeral counters
  reset on crash/failover (by design).

## Multi-instance observability

Behind a load balancer, `/ops/status`, `/ops/logs`, and the JFR/heap figures are
**per-instance** — each reflects the box that served the request, so consecutive
dashboard refreshes (which self-poll every ~5s) may land on different instances and show
different numbers. `/ops/status` includes the serving box's `app.instanceId`
(`<host>:<port>-<rand>`) so you can tell which one you hit. The durable metric feed
`ops_timeseries` is **instance-tagged**: every instance flushes its own rows (column
`instance_id`, primary key `(ts, metric, instance_id)`), so an external dashboard
(Grafana, etc.) can sum across instances for a fleet total or filter to one box — they
are no longer silently summed or limited to a single instance. The authoritative fleet
picture is an external metrics/log aggregator over that feed and the stdout JSON logs
(which go to every instance's stdout).

## Storage and retention

| Data | Where it lives | Capacity | Eviction | Survives restart? |
|---|---|---|---|---|
| Errors (`/ops/errors`) | `ops_errors` table (Postgres/H2) | 1000 rows (hardcoded in `Brace.start()`) | When count > 1000: deletes resolved rows first (oldest), then oldest unresolved | Yes |
| Logs (`/ops/logs`) | `LogTap` in-memory ring (`ConcurrentLinkedDeque`) | 1000 entries (configurable via `LogTap.setCapacity`) | Oldest entry dropped when full | No |
| Stats (`/ops/status`) | `Stats` in-memory counters / ring buffers | Per-route + timeseries window | Rolling | No |

Errors are **deduplicated on `error_type + route`** for unresolved rows — repeated
occurrences increment `occurrence_count` on the existing row rather than inserting a new
one. This means a noisy app produces few rows, not thousands.

Retention is **count-based, not time-based** for both errors and logs — nothing is
dropped purely because it got old. If you need durable logs, capture stdout from the
process itself.

## Redaction in error records

Error records are scrubbed before storage — the data you see via `brace errors`
or `/ops/errors` has already been cleaned. Two passes run at capture time:

**Name-based (query params and headers):** fields whose name looks sensitive
(`token`, `password`, `authorization`, `cookie`, `secret`, `api-key`, etc.) have
their values replaced with `[REDACTED]`. This is a deliberate over-redact — a
field named `token_count` is also redacted.

**Value-shaped (path segments and exception message tokens):** high-entropy
tokens are detected by their shape and replaced with `[redacted]`, regardless of
field name. A segment or whitespace-delimited token is redacted when it is 16+
characters long, consists entirely of base64url/hex characters, and contains at
least one digit and at least one letter. JWTs (two-dot three-part base64url
tokens) are also caught.

What remains visible (intentionally):
- **UUIDs** (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) — these are usually record
  identifiers, not secrets, and are needed for debugging.
- **Numeric IDs** and **short slugs** (below the 16-char threshold).
- Exception message text that does not contain high-entropy tokens.

If your app routes carry user-supplied secrets in path positions, prefer opaque
non-entropic route parameters (e.g. a lookup key in a database rather than a raw
token in the URL) so the route is meaningful even after redaction. The redaction
heuristic is conservative by design — an over-eager redactor makes error records
useless. See `docs/SECURITY.md` → "Error Store Redaction" for details.

## Output stability

JSON shapes returned by `--json` are stable within a minor version. Field
additions are non-breaking. Removals or renames will be flagged in the
release migration notes.

## Failure modes

| Symptom | Likely cause | Fix |
|---|---|---|
| `Authentication failed (401)` | Server is running an older `ops-authorized-keys` than the one your key is in | Redeploy so the committed `ops-authorized-keys` reaches the server, then `brace init` to confirm |
| `Cannot reach <url>` | Server down or wrong URL | Check deployment status |
| `Run inside a Brace project` | Not in a project directory | `cd` into the project, or `brace init` |
| `Private key not found` | Missing `ops-private.key` | `brace ops keypair` (writes it for you) |
