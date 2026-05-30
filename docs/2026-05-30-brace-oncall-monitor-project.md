# Kickoff: `brace-oncall` monitor project

Status: ready to start
Date: 2026-05-30

This is the **standalone build brief** for the on-call monitor — a separate Brace
app that watches your production Brace apps, wakes on incidents, triages them
against source + recent deploys, and acts within guardrails (notify, diagnose,
open PRs; never mutate prod unapproved). It consolidates the design from
[`2026-05-29-brace-oncall-agent-plan.md`](2026-05-29-brace-oncall-agent-plan.md)
(read its "Decision rationale" log for the *why* behind each choice) into one file
you can open in a fresh session and run with.

**It lives in its own (private) repo**, not in the framework repo. It is deployed to
a **separate hardened VPS** — separate from the apps it monitors, so it survives the
outage it's meant to triage.

---

## 1. What the framework already gives you (shipped — do not rebuild)

The four Phase-0 prerequisites are **done and on `main`** as of this date. The
monitor is a *consumer* of this surface; none of it needs reworking.

**Consumable ops surface** (per monitored app, token-authenticated over HTTP):

| Endpoint | Scope | What the monitor uses it for |
|---|---|---|
| `GET /ops/regressions` | `read` | **The wake signal.** New error kinds since startup: `{id, errorType, route, message, firstSeen, count, acknowledged}`. Empty = healthy. |
| `GET /ops/errors[?since=]` | `read` | Full error records incl. stack trace, redacted `requestDetail`, `queriesBefore`, redacted `requestHeaders`. |
| `GET /ops/logs[?since=&level=]` | `read` | Log ring buffer (already redacted at source). Filter `event=ops.access` to see the audit trail. |
| `GET /ops/status` | `read` | Latency / throughput / heap / job health at the time of an incident. |
| `POST /ops/regressions/{id}/acknowledge` | `control` | Mark a regression handled. **Needs a control token** — see credentials below. |

**Security primitives the monitor relies on (all shipped):**
- **Scoped read-only tokens.** Hand the monitor a `read`-ceiling key per app; it
  physically cannot hit a control endpoint. Mint with
  `brace ops keypair --read-only --label oncall-agent` on each monitored app, add
  the public line to that app's `ops-authorized-keys`.
- **Ops audit log.** Every read the monitor makes is logged as an `ops.access`
  event attributed to its key `kid` — its footprint is auditable.
- **Redaction.** Logs and error records are redacted *before* they leave the app,
  so secrets never reach the monitor or Anthropic.
- **Richer error capture.** Error rows already carry redacted request headers +
  pre-error DB query summary — the instant-of-failure context, no extra work.

**Push wake-signal (mostly shipped, one contract decision left — see §4):**
- Each monitored app already detects new error kinds (`RegressionTracker`) and
  fires `Notifier`s once per kind. `WebhookNotifier` posts a Slack-shape
  `{"text": "..."}`. `app.notifyRegressions(...)` wires them; `app.regressionsWarmup(s)`
  suppresses cold-boot noise.

---

## 2. Architecture: Brace is the control plane, Claude is the brain

The monitor host is **itself a Brace app** (dogfoods the framework — an on-call
agent for Brace apps, built as a Brace app, monitorable by its own `/ops/*`). But
**Brace is the harness, not the reasoner.** The reasoning is Claude Code / the Agent
SDK, spawned as a **subprocess**. Brace does the plumbing it's batteries-included
for; Claude does the thinking.

```
  monitored app A ──notifier POST──▶ ┌───────────────────────────────────────┐
  monitored app B ──notifier POST──▶ │  brace-oncall monitor (Brace app, VPS) │
        ...                          │                                        │
                                     │  HTTP:  POST /incident, /slack/*        │ ← control plane
                                     │  DB:    incidents, agent_runs, …        │   (Brace)
  monitored apps' ◀──read token──────│  Jobs:  hourly heartbeat poll           │
  /ops/* surface                     │  spawns ▼                              │
                                     │  claude -p / Agent SDK  (cwd=checkout)  │ ← the brain
                                     │  dontAsk + allow/deny, scoped key       │   (Claude)
                                     └───────────────────────────────────────┘
```

**Brace owns:**

| Primitive | Responsibility |
|---|---|
| HTTP routes | `POST /incident` (notifier webhooks in), `POST /slack/events` + `/slack/interactivity` (two-way Slack — §5), control UI |
| `Database` | `monitored_apps`, `incidents`, `acked_incidents`, `agent_runs` (schema §3) |
| `JobScheduler` | Heartbeat poll per app — **in-process, no 1-hour cloud floor**, poll as often as wanted |
| `DurableJob` queue | One incident → one durable job; survives restart; dedupe/cooldown enforced at enqueue |
| `Config`/`.brace` | Per-app registry, Slack token, Anthropic key from env |
| `OpsHandler` + dashboard | The monitor exposes its *own* `/ops/*` — watch the watcher |
| `Mailer` | Page-out fallback when Slack is down |

**Brace spawns Claude:** per incident job, a worker invokes `claude -p
--output-format json` (simplest first cut) or the Agent SDK `query(...)`, with
`cwd` = that app's local checkout, the incident payload + scoped read token as
input, and **harness-enforced guardrails**: `--permission-mode dontAsk` +
allow-list (`Read`, `Bash(git ...)`, `Bash(gh pr create ...)`) + deny-list
(`git push`, `rm`). Capture `total_cost_usd` and PR links into `agent_runs`.

⚠️ **The exec wrapper is security-critical.** The "propose freely, gate prod
mutation" guarantee is a property of *how Claude is spawned*. Keep that invocation
in **one audited function** and test that bypass perms can never leak in.

---

## 3. Database schema (first cut)

```sql
-- The N codebases this monitor watches. Add a server by inserting a row.
monitored_apps(
  id, name, ops_url, env, repo_path, deployed_sha,
  slack_channel, read_token_ref, created_at)

-- One row per detected incident (deduped by dedupe_key).
incidents(
  id, app_id, dedupe_key,            -- e.g. "RuntimeException|GET /checkout"
  error_type, route, first_seen, count, status,   -- new|triaging|acked|resolved
  cooldown_until, created_at)

-- Ack state (resolves open question #1 — host DB, not file, not a monitored-app endpoint).
acked_incidents(id, incident_id, acked_by, acked_at, note)

-- Audit of every agent run: what woke it, what it did, what it cost.
agent_runs(
  id, incident_id, model, started_at, finished_at,
  action,                            -- annotated|posted|opened_pr|paged
  pr_url, cost_usd, summary, raw_output_ref)
```

These are raw-SQL/Flyway tables in the monitor's *own* DB (it's a separate app with
its own database) — not the framework's `ops_*` tables.

---

## 4. The `/incident` payload contract (decide this first)

The monitored app's notifier POSTs to the monitor's `POST /incident`. **The shipped
`WebhookNotifier` posts Slack-shape `{"text": "..."}`** — fine for a human Slack
channel, lossy for machine-to-machine. Two options:

- **(A, recommended) Add a structured notifier on the framework side** — a small
  `JsonWebhookNotifier` (or extend `WebhookNotifier` with a mode) that posts the
  raw regression: `{type, route, firstSeen, count, app, env}`. The monitor's
  `/incident` ingests that, then **pulls full detail from `/ops/regressions` +
  `/ops/errors` using its read token** (the payload is the wake-up; the detail
  comes from the authenticated callback). This keeps the wake-up small and the
  monitor authoritative.
- **(B) Monitor works purely from the pushed text** — no callback, no network
  allowlist to the app. Simpler but the monitor only knows what the text carried.

Recommendation: **A.** It's a ~30-line framework follow-up and gives the agent the
redacted headers + queries-before + stack trace it needs to actually diagnose.
Authenticate `/incident` with a shared secret per app (separate from ops tokens).

---

## 5. Two-way Slack (the gap the framework doesn't cover)

The framework's `WebhookNotifier` is **one-way** (agent → channel). "Interact with
the agent via Slack" — reply in-thread to ack, ask it to dig deeper, approve a
rollback — is the monitor's job:

- **Slack app** with an Events API + interactivity request URL pointing at the
  monitor's `POST /slack/events` and `POST /slack/interactivity`.
- Outbound triage posts include action buttons (Ack / Dig deeper / Approve) whose
  `value` carries the `incident_id`. Slack POSTs the button click back to
  `/slack/interactivity`; the handler updates `incidents`/`acked_incidents` and (for
  "Dig deeper") enqueues another agent run.
- This is a natural fit for the Brace control plane — it's just more authenticated
  HTTP routes + DB writes. Verify Slack request signatures.

---

## 6. Credentials (three narrowly-scoped, never one powerful identity)

- **Anthropic: a dedicated metered API key** (`ANTHROPIC_API_KEY`), ideally in its
  own Console workspace for isolated billing/limits. **Not** subscription login —
  metered billing matches the dormant-until-incident model and keeps the agent off
  your personal identity. (See the plan's auth discussion.)
- **GitHub: a least-privileged PAT** — PRs to `claude/` branches only.
- **Ops: the scoped read-only token per monitored app.** If you want the agent to
  auto-acknowledge self-healed regressions (a `control` action), give it a
  *separate, narrowly-scoped control grant* for that one app — never upgrade the
  read key. (Matches rationale #6: read key never mutates.)

The VPS holds a **persistent checkout per repo**; before triage, `git fetch` +
checkout the app's `deployed_sha` so source-mapping matches prod.

---

## 7. Suggested milestones

1. **M0 — Scaffold + registry.** Brace app, `monitored_apps` table, a config-driven
   list of apps, its own `/ops/*`. `POST /incident` accepting + persisting an
   incident (no agent yet).
2. **M1 — Heartbeat poll.** `JobScheduler` polls each app's `/ops/regressions` +
   `brace check`; non-green → create an incident. Validates the read-token pull path
   end-to-end before wiring push.
3. **M2 — Agent triage (read-only).** DurableJob per incident → spawn `claude -p`
   with `dontAsk` + allow/deny in the checkout; post a triage summary to Slack via
   webhook. Record `agent_runs` + cost. **No mutation.**
4. **M3 — Push wake-up.** Framework JsonWebhookNotifier (§4 option A) → `/incident`.
   Add dedupe/cooldown + `acked_incidents`.
5. **M4 — Two-way Slack.** Events/interactivity endpoints; Ack / Dig-deeper buttons.
6. **M5 — Propose-a-fix.** Elevated-rate tier opens a diagnosed **draft** PR
   (`gh pr create`) with the incident evidence. Human merges. No auto-merge.

M0–M3 is the minimum useful monitor. Rollback execution stays deferred until the
deploy plan's Phase 5.

---

## 8. Caveats carried forward

- **Polyglot box by necessity** — JVM (Brace) + Node (Claude CLI/SDK) + `git`/`gh`.
  Inherent to any host since the brain is Claude.
- **Separate VPS, hardened** — it holds repo-write + `gh` + an Anthropic key + ops
  read access; it's a high-value target and must not be co-located with a monitored
  app.
- **Cost control** — dedupe + cooldown per error kind; a cheap classifier gate
  before the expensive deep-dive; log what was dropped (no silent caps).

## 9. Pointers

- Framework design + rationale log: [`2026-05-29-brace-oncall-agent-plan.md`](2026-05-29-brace-oncall-agent-plan.md)
- Token model detail: [`2026-05-30-scoped-ops-token-plan.md`](2026-05-30-scoped-ops-token-plan.md)
- Consumable endpoints + notifier usage: `BRACE-AGENTS.md` → Ops section
- Shipped framework classes to read: `OpsScope`, `OpsToken`, `RegressionTracker`,
  `Notifier`/`WebhookNotifier`, `Redactor`, `OpsAudit`.
