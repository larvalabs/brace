# Brace Agent Ops Guide

This guide explains how AI agents (and humans) inspect a running Brace app
using the project-scoped `brace` CLI commands.

## Setup

Once per project:

```bash
brace init         # scaffolds .brace, .brace.local, .gitignore
brace ops keypair  # generates an Ed25519 keypair and wires it up
```

`brace ops keypair` does two things:

- Writes the private half to `ops-private.key` (gitignored — never committed,
  never leaves your machine). This is the file `ops.key` points at.
- Adds the public half to `ops-authorized-keys` (committed to git — this is the
  allow-list the *server* checks), labeled `<git-user.email>@<hostname>` by
  default so each developer/machine has its own line. Override with `--label`.

You do **not** copy a printed key by hand anymore — both files are written for
you.

### The deploy-phase loop

Getting ops auth working is inherently a two-machine handshake: the server's URL
has to come *to* you, and your public key has to get *to* the server. `brace
init` is idempotent and is the spine of this — run it, do the one thing it asks,
run it again, until it's green.

1. **Generate your key** (above). Your public line lands in `ops-authorized-keys`.
2. **Set the prod URL.** Uncomment/add `ops.prod.url=https://your-app...` in
   `.brace` (you get this once the server exists).
3. **Get `ops-authorized-keys` onto the server.** It's committed, so this just
   means deploy — the running app reads it at startup. (A push-to-deploy or
   `brace deploy` ships it like any other file.)
4. **Verify.** Re-run `brace init --env prod` (or just `brace init` once
   `ops.prod.url` is set — prod becomes the default). It performs a remote check:
   reachability + whether the server accepted your key, and prints the exact next
   action for whatever is still missing. Repeat from the failing step until every
   check is ✓.

### Rotating or adding keys

- **Rotate your own key:** `rm ops-private.key && brace ops keypair`, then
  redeploy. Because the label is stable (`email@host`), the new public key
  *replaces* your existing line in `ops-authorized-keys` in place — no orphaned,
  still-trusted key left behind.
- **Add another developer:** they run `brace ops keypair` on their own machine.
  Their distinct `email@host` label gets its own line; committing it never
  clobbers anyone else's entry.

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
| `Authentication failed (401)` | Server is running an older `ops-authorized-keys` than the one your key is in | Redeploy so the committed `ops-authorized-keys` reaches the server, then `brace init --env prod` to confirm |
| `Cannot reach <url>` | Server down or wrong URL | Check deployment status |
| `Run inside a Brace project` | Not in a project directory | `cd` into the project, or `brace init` |
| `Private key not found` | Missing `ops-private.key` | `brace ops keypair` (writes it for you) |

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
