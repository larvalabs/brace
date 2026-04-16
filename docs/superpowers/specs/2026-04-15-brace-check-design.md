# `brace check` — Automated Health Check for AI Agents

**Date:** 2026-04-15
**Status:** Approved

## Problem

An AI agent acting as on-call for a Brace app currently needs 3+ CLI calls (`status`, `errors`, `logs`) to assess production health, then must manually apply threshold logic from the runbook. This is token-expensive and error-prone — the agent might miss a check or apply thresholds inconsistently.

## Solution

A new CLI command `brace check` that runs a full health assessment against a running Brace app and returns a structured verdict. One command to know if something needs attention, with pointers to drill deeper.

## Command Interface

```bash
brace check [--env <name>] [--json] [--pretty]
```

Exit codes follow existing convention:
- **0** — all checks pass
- **1** — any check is fail or warn
- **2** — app unreachable

Output mode follows existing convention: human-readable table on TTY, JSON when piped. Override with `--json` or `--pretty`.

## Output Structure (JSON)

```json
{
  "healthy": false,
  "summary": "2 issues: 3 unresolved errors, 1 failing job",
  "checks": [
    {
      "name": "reachability",
      "status": "pass",
      "message": "App up for 2h 15m, Java 21"
    },
    {
      "name": "errors",
      "status": "fail",
      "message": "3 unresolved errors",
      "details": [
        {"type": "NullPointerException", "route": "GET /posts/{id}", "count": 3, "id": "err_abc123"}
      ],
      "followUp": "brace errors --env prod --json"
    },
    {
      "name": "http_5xx",
      "status": "pass",
      "message": "0 server errors in 6 requests"
    },
    {
      "name": "slow_routes",
      "status": "pass",
      "message": "All routes under 500ms"
    },
    {
      "name": "heap",
      "status": "pass",
      "message": "39MB / 512MB (7%)"
    },
    {
      "name": "gc_pressure",
      "status": "pass",
      "message": "Avg pause 7ms"
    },
    {
      "name": "jobs",
      "status": "fail",
      "message": "1 of 2 jobs failing",
      "details": [
        {"name": "email-digest", "lastStatus": "error", "failCount": 3, "lastError": "Connection refused"}
      ]
    },
    {
      "name": "cache",
      "status": "pass",
      "message": "Hit rate 81% (5000 hits / 1200 misses)"
    },
    {
      "name": "recent_logs",
      "status": "pass",
      "message": "0 error-level entries in last 30m",
      "followUp": "brace logs --env prod --since 30m --level error --json"
    }
  ]
}
```

### Field semantics

- **`healthy`** — single boolean. `false` if any check has status `"fail"`.
- **`summary`** — one-line natural language description. Either `"All checks passed"` or `"N issues: ..."` listing each failure/warning.
- **`checks[].status`** — `"pass"`, `"warn"`, or `"fail"`.
- **`checks[].details`** — only present when status is `"warn"` or `"fail"`. Contains just enough to identify the problem: IDs, names, counts, types, routes.
- **`checks[].followUp`** — only present when there's something to investigate. The exact `brace` CLI command the agent should run next.

## Checks and Default Thresholds

| Check | Fail condition | Warn condition | Config key | Default |
|---|---|---|---|---|
| reachability | Can't connect to app | Uptime < 5m (recent restart) | — | — |
| errors | Any unresolved errors | — | — | — |
| http_5xx | Any 500 status codes | — | — | — |
| slow_routes | Any route avg > threshold | — | `check.slow_route_ms` | `500` |
| heap | Used > fail% of max | Used > warn% of max | `check.heap_warn_percent`, `check.heap_fail_percent` | `70`, `80` |
| gc_pressure | Avg GC pause > threshold | — | `check.gc_pause_ms` | `50` |
| jobs | Any job with lastStatus != "ok" | failCount > 0 but last run ok | — | — |
| cache | Hit rate < threshold (only if cache is in use, i.e. hits + misses > 0) | — | `check.cache_hit_rate` | `0.5` |
| recent_logs | Any error-level log entries in window | Any warn-level entries in window | `check.log_window_minutes` | `30` |

## Configuration

Thresholds are configured in `.brace` alongside existing config:

```
# Health check thresholds (all optional, defaults shown)
check.slow_route_ms=500
check.heap_warn_percent=70
check.heap_fail_percent=80
check.gc_pause_ms=50
check.cache_hit_rate=0.5
check.log_window_minutes=30
```

Absent keys use the defaults above. No new files or config formats needed.

## Human-Readable Output (TTY)

```
✓ reachability    App up for 2h 15m, Java 21
✗ errors          3 unresolved errors
                  → brace errors --env prod
✓ http_5xx        0 server errors in 6 requests
✓ slow_routes     All routes under 500ms
✓ heap            39MB / 512MB (7%)
✓ gc_pressure     Avg pause 7ms
✗ jobs            1 of 2 jobs failing
✓ cache           Hit rate 81%
✓ recent_logs     0 error-level entries in last 30m

2 issues found
```

Symbols: `✓` for pass, `⚠` for warn, `✗` for fail. The `followUp` command is shown indented below failed/warned checks.

## Implementation

### Architecture

This is a **CLI-side command only**. No new server endpoints needed. The command:

1. Authenticates via existing `CliAuth.bearer()` mechanism
2. Fetches data from three existing endpoints in parallel:
   - `GET /ops/status` — app info, HTTP stats, JVM, jobs, cache, metrics
   - `GET /ops/errors` — unresolved error list
   - `GET /ops/logs?level=warn&since_ts=<window>` — recent warn/error log entries
3. Applies threshold logic to the combined data
4. Formats and outputs the verdict

### New files

- **`CliCheck.java`** — main check logic
  - `run(CliConfig cfg, String[] args)` — entry point called from `Cli.java`
  - `CheckResult check(Map status, List errors, List logs, CheckThresholds thresholds)` — pure function: takes parsed JSON data, returns structured result. Testable without HTTP.
  - Individual check methods: `checkReachability()`, `checkErrors()`, `checkHttp5xx()`, `checkSlowRoutes()`, `checkHeap()`, `checkGcPressure()`, `checkJobs()`, `checkCache()`, `checkRecentLogs()`
  - `CheckThresholds` — record holding threshold values, constructed from `.brace` config with defaults

### Changed files

- **`Cli.java`** — add `"check"` case to the command dispatch switch
- **`CliConfig.java`** — add `checkThresholds()` method to parse `check.*` keys with defaults

### Test approach

- Unit tests for `CliCheck.check()` with synthetic JSON payloads covering each check in pass/warn/fail states
- Test threshold override behavior (custom values from config vs defaults)
- Test summary generation with various combinations of failures
- Test exit code logic (0/1/2)
- Integration test using `Brace.test()` to verify the HTTP fetch → check pipeline end-to-end

## BRACE-AGENTS.md Update

Add a new top-level section to the ops runbook:

> ### Quick health check
>
> When asked to check on production or act as on-call, start here:
>
> ```bash
> brace check --env prod --json
> ```
>
> If `healthy` is `true`, report healthy and stop. If `false`, read `summary` for an overview, then look at each check with status `"fail"` or `"warn"`. Use the `followUp` command on any failed check to investigate further.
>
> **Do not run `brace status` first.** `brace check` already fetches status data and applies threshold analysis. Only use the individual commands (`brace errors`, `brace logs`, `brace status`) for follow-up investigation.

## What This Does NOT Do

- No new server-side endpoints or middleware
- No alerting, webhooks, or notification system — the agent/cron consumer handles that
- No historical trend analysis — checks current state only
- No custom check plugins — the nine checks above cover what `/ops/status` exposes
