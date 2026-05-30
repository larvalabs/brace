# Plan: `brace-oncall` — an autonomous on-call AI developer

Status: draft
Date: 2026-05-29

## Goal

Stand up an AI agent that watches Brace apps in production, wakes on real
incidents (new error kinds, elevated failure rates, latency creep, process
down), triages them against the source and recent deploys, and acts within
explicit guardrails — annotate/resolve transient noise, open a diagnosed PR for
real regressions, page a human and propose rollback for outages.

Brace is unusually well-positioned for this because the ops surface is already
HTTP-reachable with token auth: `/ops/status`, `/ops/errors`, `/ops/logs`, and
(Phase 0 of the deploy plan) `/ops/regressions`. The agent is a consumer of that
surface, not new framework machinery — most of the work here is *ops plumbing and
guardrails*, not AI.

After this plan, an operator can hand a project a scoped read-only ops token and a
notifier webhook, and an agent will run the loop:

```
incident fires → pull context → correlate with deploys → classify → act within tier
```

…dormant (zero cost) until something actually breaks.

## Non-goals

- **Not a monitoring platform.** Brace doesn't replace Sentry/Datadog/Grafana.
  It surfaces its own error/log/stat ring buffers; the agent reasons over them.
  Apps that already ship to an external APM can point the agent at that instead.
- **Not autonomous production mutation.** The agent diagnoses and *proposes*
  freely (PRs, Slack posts, issues). Anything that mutates prod — rollback, cache
  clear, deploy, restart — stays behind a human approval gate in v1.
- **Not a new always-on server process.** The agent is event-triggered (webhook →
  remote agent run) plus a slow heartbeat poll. No long-lived daemon to babysit.
- **Not content-level PII/secret detection.** Log redaction is the separate Tier-0
  redaction-layer item; this plan *depends on* it but doesn't reimplement it.

## Design

### The shape: event-driven primary, poll as fallback

```
                    ┌─────────────────────────────────────────┐
  Brace app         │  Agent host (Claude Code remote agent)   │
  ─────────         │  ──────────────────────────────────────  │
  /ops/regressions ─┼─(webhook, Phase 0 notifier)─▶ incident   │  PRIMARY: push.
  fires on new      │                               triage run │  Dormant until a
  error kind/rate   │                                          │  real event fires.
                    │                                          │
  brace check ◀─────┼─(cron, every 15–30m)──────── heartbeat   │  FALLBACK: poll.
  brace status      │                               run        │  Catches what a
                    │                                          │  webhook can't:
                    │                                          │  process down,
                    └──────────────────────────────────────────┘  notifier wedged,
                                                                   silent latency creep.
```

The push path handles incidents. The poll path answers "is the thing that detects
incidents still alive?" — the smoke-detector-battery check. Leading with a pure
poll (a `/loop` running `brace check` every N minutes) is the anti-pattern: it
burns tokens every tick when healthy and makes incident latency equal to the poll
interval.

### Trigger wiring

- **Push:** Phase 0 of the deploy plan builds `/ops/regressions` plus
  webhook/email notifiers. That notifier *is* the wake signal. Wiring:
  Brace notifier webhook → small endpoint or queue → `RemoteTrigger` kicks a
  remote agent run with the incident payload as input. The agent stays stateless
  between incidents; the trigger is the audit record of "what woke it and why."
- **Poll:** A scheduled remote agent (the `schedule`/routines skill) runs
  `brace check --env prod` every 15–30 min. `brace check` already does 9 health
  checks (errors, latency, cache, mailer, JVM, …) with configurable thresholds —
  the agent reads its JSON and only escalates to a full triage run on a real
  failure.

### The triage loop (per incident)

```
1. Ingest        incident payload: error kind, count, first-seen timestamp
2. Pull context  brace errors <id>     → stack trace, occurrence count
                 brace logs            → ring buffer around the timestamp
                 brace status          → latency / throughput / heap at the time
3. Correlate     did this start at a version boundary? (Phase 3 version tracking
                 turns this from a guess into "first seen 4 min after v0.2.1")
4. Classify      transient one-off · elevated rate · outage / deploy-correlated
5. Diagnose      read the stack frames, map to source (agent has the repo),
                 form a hypothesis
6. Act           within the agent's tier (below)
```

Step 3 is where Phase 3 (deploy version tracking) pays for itself — "this error
first appeared N minutes after vX shipped" is 80% of real incident triage.

### Guardrails: tiered autonomy

The single most important design decision is being explicit about
autonomous-vs-escalate. The agent's power scales with confidence and severity;
production mutation is always gated.

| Severity | Autonomous action | Escalation |
|---|---|---|
| Transient / one-off | annotate; `brace resolve` if clearly self-healed | none |
| Elevated rate | diagnose + post to Slack with hypothesis; open a GitHub issue or **draft** PR with proposed fix | tag a human |
| Outage / deploy-correlated | page a human immediately; **propose** rollback with the deploy-correlation evidence | human approves rollback (deploy-plan Phase 5) |

Rule: **the agent proposes freely (PRs, issues, Slack), but mutating production —
rollback, cache clear, deploy, restart — requires a human approval gate.** Opening
a PR is safe and high-value; merging it isn't. Rollback execution lands when
deploy-plan Phase 5 ships; until then the agent documents the manual rollback
steps in its page-out.

### Auth & accountability — the prerequisites

Building this *safely* depends on two items already on the roadmap. They are the
real gating work:

- **Scoped read-only ops token** (currently under TODO "Future Considerations").
  The agent holds a token that can read `logs`/`errors`/`status`/`regressions`
  but *cannot* clear cache or hit any control endpoint. The existing TODO note
  already calls this out as "particularly valuable for handing a read-only token
  to an AI agent." A full-power ops key on an always-on autonomous agent is an
  unacceptable blast radius. **This is the first thing to build.**
- **Ops endpoint audit log** (Tier 0). Every read the agent makes is recorded
  (key-id + timestamp + endpoint). The agent's footprint must be auditable after
  the fact — for trust, and for debugging the agent itself when it misfires.
- **Redaction layer** (Tier 0). The agent reads `brace logs`/`errors` over HTTP;
  those must be redacted before they reach it. This plan assumes the redaction
  layer is in place and does not reimplement it.

So the honest sequencing: **scoped read-only token + audit log + redaction layer**
turn "a cron that runs `brace check`" into a real, safe on-call agent. All three
are already tracked; the scoped token is the one to promote in priority if we want
this direction.

### Noise & cost control

- **Dedupe + cooldown** on error kind — a flapping error must not trigger 50 agent
  runs. `/ops/regressions` is already scoped to "new since startup," which
  naturally dedupes within a deploy generation; add a cooldown window on top.
- **Acknowledged-incident state.** The agent keeps a small state file (or uses its
  own memory) of known/acknowledged incidents so it doesn't re-diagnose the same
  thing on every fire. Don't re-alert on resolved.
- **Two-tier model.** A cheap classifier decides "real regression vs. noise"; only
  genuine regressions wake the expensive deep-dive-and-propose-a-fix agent. Keeps
  cost proportional to real incident volume, not log volume.

## Phases

### Phase 0 — Prerequisites (gating, mostly already-tracked)

Not new work in this plan — pointers to existing TODO items that must land first:
- Scoped read-only ops token (TODO "Future Considerations") — **promote priority**
- Ops endpoint audit log (TODO Tier 0)
- Redaction layer (TODO Tier 0)
- `/ops/regressions` + webhook notifier (deploy plan Phase 0)

The on-call agent is not safe to run autonomously until these four exist. It can
be prototyped read-only against a full ops key in a staging app before then.

### Phase 1 — Heartbeat agent (poll only) (~0.5 day)

A scheduled remote agent running `brace check --env prod` on a 15–30 min cron.
On a non-green result it pulls `brace status`/`brace errors`/`brace logs`,
diagnoses, and posts a summary to Slack. No autonomous mutation. This is the
minimum useful version and validates the triage loop against real output before
wiring push.

### Phase 2 — `brace-oncall` agent skill (~1 day)

Package the triage loop as a reusable skill (`brace-oncall`) so any project can
adopt it. Encodes: the context-pull sequence, the deploy-correlation step, the
severity classification, and the tiered-action policy. Reads project config from
`.brace` (ops URL, env, Slack webhook, repo). Ships in the distribution zip under
`share/brace/skills/brace-oncall/` (mirrors the deploy-plan Phase 4 decision for
`brace-deploy`).

### Phase 3 — Push trigger (event-driven) (~1 day)

Wire the Phase-0 notifier webhook to a `RemoteTrigger` so incidents wake the agent
on arrival instead of waiting for the next poll. Incident payload becomes the
agent's input. The heartbeat poll from Phase 1 stays as the fallback. Add the
dedupe/cooldown + acknowledged-incident state here.

### Phase 4 — Propose-a-fix tier (~1–2 days)

Give the elevated-rate tier the ability to open a diagnosed **draft** PR: map the
stack trace to source, write a hypothesis + proposed change, open the PR with the
incident evidence (error count, first-seen, deploy correlation, relevant log
lines) in the description. Human reviews and merges. No auto-merge.

### Phase 5 — Rollback proposal (deferred)

Once deploy-plan Phase 5 (rollback) exists, the outage tier can *propose* a
rollback with one-click human approval, attaching the deploy-correlation evidence.
Execution stays gated. Punt until deploy-plan Phase 5 lands.

## What changes in the codebase

Minimal framework changes — this is mostly skill + config + the gating ops items:
- Promote/implement scoped read-only ops token (separate TODO item).
- `brace-oncall` skill bundled in the distribution.
- `.brace` config: Slack webhook URL, repo path for source mapping, oncall env.
- Possibly a thin `brace oncall test` to dry-run the triage loop against a
  synthetic incident.

## Open questions

1. **State store for acknowledged incidents** — agent-local file vs. a server-side
   `/ops/incidents` ack endpoint. Server-side survives agent restarts and lets
   multiple agents/humans share ack state, but adds a write endpoint (and thus a
   control-scope token). Lean file-local for v1.
2. **Skill distribution** — bundle in the dist zip *and* `~/.claude/skills/`?
   Same question the deploy plan defers to its Phase 4; decide once for both.
3. **Severity thresholds** — reuse `brace check`'s configurable thresholds as the
   classification source of truth rather than inventing a parallel set. Probably
   yes; confirm the threshold config is rich enough.

## Why this beats the alternatives

- **vs. a pure `/loop` poll:** event-driven means zero cost when healthy and
  near-zero incident latency, instead of paying every tick and waiting up to a
  poll interval to notice an outage.
- **vs. an external APM + its own alerting:** the agent reasons over the *same*
  JSON surface the framework already exposes, with the source repo in hand and
  deploy version correlation built in — so it diagnoses, not just alerts.
- **vs. full autonomy:** the tiered-gate model captures most of the value (fast
  triage, diagnosed PRs) while keeping production mutation human-approved, which
  is the only version that's actually deployable on day one.
