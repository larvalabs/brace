# Brace Agent Ops Guide

This guide explains how AI agents (and humans) inspect a running Brace app
using the project-scoped `brace` CLI commands.

## Setup

Once per project:

```bash
brace init       # scaffolds .brace, .brace.local, .gitignore
brace ops keypair  # generates Ed25519 keypair
```

Then add the printed public key line to the *server's* `ops-authorized-keys`
file and re-run `brace init` — it will perform a remote check against
`ops.prod.url` (if configured in `.brace`) and confirm the key is accepted.

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

Override per command with `--env prod`. All commands below accept `--env`.

## Commands

| Command | Purpose | Exit code |
|---|---|---|
| `brace status` | App health snapshot | 0 healthy / 1 errors > 0 / 2 unreachable |
| `brace errors [--since 1h]` | List unresolved errors | 0 none / 1 some / 2 unreachable |
| `brace logs [-f] [--since 10m]` | Tail recent structured log entries | always 0 |
| `brace cache` | Cache size / hit rate / evictions | 0 / 2 unreachable |
| `brace cache clear` | Empty the cache | 0 / 2 unreachable |
| `brace resolve <id>` | Mark an error as resolved | 0 / 1 not found / 2 |

All read commands auto-detect output mode: human table when stdout is a TTY,
JSON when piped or redirected. Override with `--json` or `--pretty`.

## Workflows

### Checking on production after a deploy

```bash
brace status --env prod && echo "healthy" || echo "needs attention"
brace errors --env prod --since 5m   # any errors since deploy?
brace logs --env prod --since 5m     # what did it say?
```

### Investigating a user-reported error

```bash
brace errors --env prod --json | jq '.[] | select(.route == "/checkout")'
brace logs --env prod --since 1h --level warn
brace resolve <id>
```

### Scheduled health check (cron / agent)

```bash
# Exit non-zero if status reports issues OR there are unresolved errors
brace status --env prod || alert "brace status failed"
brace errors --env prod --since 15m || alert "new errors"
```

## Failure modes

| Symptom | Likely cause | Fix |
|---|---|---|
| `Authentication failed (401)` | Public key not in server's `ops-authorized-keys` | `brace init --env prod` to see what to add |
| `Cannot reach <url>` | Server down or wrong URL | Check deployment status |
| `Run inside a Brace project` | Not in a project directory | `cd` into the project, or `brace init` |
| `Private key not found` | Missing `ops-private.key` | `brace ops keypair` |

## Output stability

JSON shapes returned by `--json` are stable within a minor version. Field
additions are non-breaking. Removals or renames will be flagged in the
release migration notes.

## Data retention

- **Errors** are persisted in the `ops_errors` table and survive restarts.
  Capacity is 1000 rows; repeated errors with the same type + route are
  deduplicated (the row's `occurrence_count` increments instead of inserting).
  When the cap is hit, resolved rows are pruned first, then oldest unresolved.
- **Logs** are kept in an in-memory ring buffer (1000 entries by default) and
  are **lost on restart**. If you need durable logs, capture stdout from the
  process itself.
- Neither store evicts based on age — only on count.

See `BRACE-AGENTS.md` → "Storage and retention" for the full table.
