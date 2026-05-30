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
  brace check ◀─────┼─(cron, hourly floor)──────── heartbeat   │  FALLBACK: poll.
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

**Cadence constraint (cloud routines).** Scheduled remote agents (routines) run on
Anthropic's cloud infrastructure with a **1-hour minimum cron interval**. So the
laptop-off heartbeat floor is hourly — fine for "is it alive," since fast incident
response comes from the push path, not the poll. If a sub-hourly heartbeat is ever
wanted it has to run as a **desktop scheduled task** or an in-session `/loop`
(1-minute minimum), both of which require the operator's machine to be on. For a
true unattended agent: hourly routine heartbeat + push for everything time-sensitive.

### Hosting options

Where the agent physically runs is a real decision, not a detail — it determines
trigger latency, what the agent can reach, and how the guardrails are enforced. The
cadence/allowlist constraints above are properties of *one* choice (cloud
routines), not the design as a whole.

| | Cloud routine | **Self-hosted VPS (headless)** | Desktop scheduled task |
|---|---|---|---|
| Runs on | Anthropic cloud | a box you operate | operator's machine |
| Laptop-off | yes | yes | **no** |
| Incident latency | push only (poll floors at 1h) | **sub-second, roll-your-own** | 1-min poll |
| Reach internal `/ops/*` | needs Allowed-domains config | **direct, no allowlist** | direct |
| Repo + `git`/`gh` + tests | fresh clone, GitHub-App OAuth | **persistent checkout, own PAT** | local checkout |
| Ack-state store | ephemeral / needs `/ops/incidents` | **local file, free** | local file |
| Guardrails | prompt + connector scoping | **`dontAsk` + deny-list, harness-enforced** | same as VPS |
| You operate | nothing | the box (patch, key, monitor) | the machine |

**Recommended for the strongest version: a separate hardened VPS.** It removes the
two frictions the routine path has to work around — the 1-hour floor and the
network allowlist — and turns the core guardrail from a prompt instruction into
enforced config.

- **Build shape.** Either the **Claude Agent SDK** as a small daemon (holds a port,
  calls `query(...)` on each webhook; gives session resume + programmatic
  `PreToolUse` permission hooks) or **`claude -p` print mode** behind a tiny
  listener (Flask/Express shelling out, `--output-format json`). No built-in
  webhook trigger exists — the listener is roll-your-own, which is exactly what
  buys sub-second response with no cron floor. Prefer the SDK for a long-running
  service; `claude -p` is fine for a first cut.
- **Guardrails become enforced, not requested.** Run with
  `--permission-mode dontAsk` + an explicit `--allowedTools` allow-list (e.g.
  `Read`, `Bash(git status *)`, `Bash(git diff *)`, `Bash(gh pr create *)`,
  `Bash(gh issue *)`) and a `settings.json` `deny` list for `Bash(git push:*)`,
  `Bash(rm *)`, etc. `dontAsk` auto-denies anything not pre-approved (no interactive
  prompt exists headless), so the "propose freely, gate production mutation" rule is
  enforced by the harness — the agent *cannot* force-push or run destructive
  commands. Stronger than the cloud model's connector scoping.
- **Auth.** `ANTHROPIC_API_KEY` (simplest, no expiry) or an `apiKeyHelper` script
  pulling from a vault if rotation is wanted. Avoid `CLAUDE_CODE_OAUTH_TOKEN` — it's
  a 1-year token with no refresh, i.e. manual rotation.
- **Put it on a *separate* box from the app.** Same "dies with the app" trap as
  in-app reasoning: an agent co-located with the host it monitors goes down in the
  outage it's meant to triage. A distinct small VPS survives the app's bad days.
- **That box is a high-value target** — it holds repo write + `gh` + an Anthropic
  key + ops access. Harden it, least-privilege the GitHub PAT (PRs to `claude/`
  branches only), and hand it the **scoped read-only ops token** (the Phase-0
  prerequisite), never a full ops key.
- **Observability/cost are yours.** `--output-format json` carries
  `total_cost_usd` per run — wrap and log it; there's no built-in dashboard of what
  the agent did.

Cloud routines remain the **zero-ops** option: no box to run/secure/patch, at the
cost of push-only fast response, the allowlist step, and ephemeral state. Pick the
VPS when fast first-party response and native git/gh access matter more than
avoiding ops work; pick routines when they don't.

### Trigger wiring

- **Push:** Phase 0 of the deploy plan builds `/ops/regressions` plus
  webhook/email notifiers. That notifier *is* the wake signal. Wiring: Brace
  notifier webhook → **POST to the routine's API trigger** (routines expose an
  HTTP trigger; there is no separate `RemoteTrigger` primitive — the remote-trigger
  mechanisms are the API POST and GitHub events) with the incident payload as the
  run input. The agent stays stateless between incidents; the API trigger is the
  audit record of "what woke it and why."
- **Poll:** A scheduled remote agent (the `schedule`/routines skill) runs
  `brace check --env prod` on the **hourly floor** (see cadence constraint above).
  `brace check` already does 9 health checks (errors, latency, cache, mailer, JVM,
  …) with configurable thresholds — the agent reads its JSON and only escalates to
  a full triage run on a real failure.
- **Network reachability.** Routines run in the cloud behind a **Trusted-domains
  allowlist**; a Brace app's `/ops/*` endpoints are internal, so the routine must
  add the app's host to **Allowed domains** (or use Full network access) to call
  back out. The push path sidesteps this when the incident payload carries enough
  context — but any callback to pull `brace errors`/`logs` needs the host
  allowlisted. Worth deciding per-deployment whether the agent pulls from `/ops/*`
  or works purely from the pushed payload + the captured error record (below).

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

### In-app context: capture, not reason

A tempting alternative is to embed a Claude API key in each app and have it
*self-diagnose* errors in-process. With Brace's ops surface already this rich
(`/ops/errors` stack traces, `/ops/logs` ring buffer, `/ops/status` metrics, the
JFR profiler, per-request DB query instrumentation), almost all of the
"runtime context advantage" an in-process reasoner would have is **already exposed
over HTTP** — so the external agent can fetch it. An in-app *reasoner* therefore
buys little while carrying real downsides: it dies with the app (useless in exactly
the OOM/crash-loop/502 cases you most need it), egresses stack traces + params to
Anthropic on the failure hot path, multiplies cost in a crash loop, and puts an API
key in every app.

What the ops endpoints *don't* already expose is the narrow residual that only
exists at the catch point and was never recorded:

1. **Live locals at the throw** — the request body, the entity that failed
   validation, the value that was null. Endpoints show what was *logged*, not the
   full live frame.
2. **The exact-request join** — in-process the error binds to *the* request
   (id, params, session); from outside the agent fuzzy-joins by timestamp.
3. **Ephemeral state that ages out** — the log ring buffer and the 60-minute metric
   window are bounded, so an agent polling after the fact may find the relevant
   lines already evicted.

Every one of those is solved by in-app **capture**, not in-app **reasoning**: at
the catch point, snapshot the relevant locals + exact request + ephemeral state,
redact, and attach it to the `ops_errors` row. The capture *must* be in-process
(only the handler is there at the throw); the reasoning does not. The external
code-aware agent then reads the now-richer error record via `/ops/errors` and gets
the residual context for free — no second in-process brain, no per-app API key, no
hot-path egress beyond what redaction already governs.

Design conclusion: **one reasoner, external, with repo access.** The in-app piece
is purely *richer error capture* (Phase 1.5 below), widening what the ops record
holds. The thing that never collapses regardless — the app being down — only the
external reasoner can cover, which is why it stays the single brain.

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

A scheduled remote agent running `brace check --env prod` on the hourly cron floor
(see cadence constraint). On a non-green result it pulls
`brace status`/`brace errors`/`brace logs`, diagnoses, and posts a summary to
Slack. No autonomous mutation. This is the minimum useful version and validates the
triage loop against real output before wiring push.

### Phase 1.5 — Richer error capture (in-app, no AI) (~0.5–1 day)

Widen what the `ops_errors` record holds so the external agent gets the
instant-of-failure context the ops endpoints don't already expose (live locals,
exact-request binding, ephemeral state — see "In-app context: capture, not
reason"). At the catch point: snapshot the request (method/path/redacted params/id)
+ relevant captured context, run it through the redaction layer, attach to the
error row. **No Claude call in the process** — capture only. Independent of the
agent itself; also improves the human dashboard. Gated on the redaction layer.

### Phase 2 — `brace-oncall` agent skill (~1 day)

Package the triage loop as a reusable skill (`brace-oncall`) so any project can
adopt it. Encodes: the context-pull sequence, the deploy-correlation step, the
severity classification, and the tiered-action policy. Reads project config from
`.brace` (ops URL, env, Slack webhook, repo). Ships in the distribution zip under
`share/brace/skills/brace-oncall/` (mirrors the deploy-plan Phase 4 decision for
`brace-deploy`).

### Phase 3 — Push trigger (event-driven) (~1 day)

Wire the Phase-0 notifier webhook to **POST the routine's API trigger** so
incidents wake the agent on arrival instead of waiting for the next poll. Incident
payload (including the Phase 1.5 captured context) becomes the agent's input. The
heartbeat poll from Phase 1 stays as the fallback. Add the dedupe/cooldown +
acknowledged-incident state here. Decide here whether the agent works purely from
the pushed payload or also calls back to `/ops/*` (which requires allowlisting the
app's host in the routine's Allowed domains).

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
- Richer error capture into `ops_errors` (Phase 1.5) — the one real in-framework
  change: capture + redact + attach instant-of-failure context. No AI in-process.
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

## Decision rationale (revisit log)

The reasoning behind the load-bearing choices, recorded so they can be
re-litigated when the constraints change. Each notes *what would make us revisit*.

1. **One external reasoner, not an in-app self-diagnostician.**
   *Why:* Brace's ops endpoints already expose the bulk of runtime context (stack
   traces, logs, metrics, slow queries) over HTTP, so an in-process reasoner adds
   little. Its downsides are structural: it dies with the app (useless in the OOM /
   crash-loop / 502 cases you most need it), egresses sensitive data on the failure
   hot path, multiplies cost in a crash loop, and forces an API key into every app.
   The genuinely in-process-only value (live locals, exact-request binding,
   ephemeral state that ages out of bounded buffers) is *capturable* — so we move
   capture in-process (Phase 1.5) and keep reasoning external.
   *Revisit if:* errors routinely carry context that's impractical to serialize
   into `ops_errors` (huge object graphs, streaming state), or per-app API keys
   become cheap to manage and the apps gain enough isolation that hot-path egress
   and crash-loop cost stop mattering. Then a thin in-app triage tier could return.

2. **Capture, not reason, is the in-app job.**
   *Why:* the residual context the ops endpoints miss exists only at the catch
   point and is cheap to snapshot+redact+attach; reasoning over it does not need to
   be co-located. Keeps exactly one brain, no per-app key, and improves the human
   dashboard as a side effect.
   *Revisit if:* we find capture can't reach the context without unacceptable
   coupling to handler internals, or redaction-at-capture proves harder than
   redaction-at-read.

3. **Event-driven primary, poll only as a liveness fallback.**
   *Why:* polling burns tokens every tick when healthy and bounds incident latency
   to the poll interval; the push path (notifier → routine API trigger) is both
   cheaper and faster. The poll exists solely to catch what a webhook can't report
   — the app being down or the notifier itself wedged.
   *Revisit if:* a sub-hourly liveness signal becomes necessary (cloud routines
   floor at 1 hour) — then either accept a local desktop-task/`/loop` heartbeat
   (needs the operator's machine on) or push a dead-man's-switch heartbeat from the
   app so absence, not a poll, signals death.

4. **Hosting is a deliberate tradeoff; a separate VPS is the strongest version.**
   *Why:* see "Hosting options." Cloud routines are the zero-ops choice but pay for
   it with push-only fast response, the network allowlist, and ephemeral state. A
   self-hosted headless VPS removes all three — sub-second triggering, direct
   internal `/ops/*` access, persistent state, native git/gh for the PR tier — and
   makes the production-mutation gate harness-enforced (`dontAsk` + deny-list)
   rather than prompt-requested. The cost is operating and securing one box. The
   VPS must be *separate* from the monitored app (else it dies in the outage it
   triages) and holds a high-value credential set, so it gets the scoped read-only
   ops token, a least-privileged GitHub PAT, and hardening. Desktop scheduled tasks
   are the local-tooling option but need the operator's machine on.
   *Revisit if:* ops burden of the VPS outweighs its latency/access benefits (fall
   back to routines), routines gain sub-hourly schedules + first-party network
   reach (the gap narrows), or compliance/data-residency forces self-hosting
   (locks in the VPS).

5. **Tiered autonomy with a hard human gate on production mutation.**
   *Why:* proposing (PRs, issues, Slack) is safe and high-value; mutating prod
   (rollback, cache clear, restart) is not, and an always-on autonomous agent with
   mutation rights is an unacceptable blast radius on day one.
   *Revisit if:* operational trust and an audit/undo story mature enough to let
   specific narrow mutations (e.g. one-click-reversible rollback) move inside the
   gate.

6. **Scoped read-only token treated as a hard prerequisite, not a nicety.**
   *Why:* an always-on agent holding a full-power ops key is the single largest
   blast-radius risk in the design; read-only scoping is what makes autonomous
   operation defensible. This is why the plan promotes that TODO item to gating.
   *Revisit if:* the agent needs a control action often enough to justify a
   separate, narrowly-scoped control token (still never the full key).

7. **Reuse `brace check` thresholds rather than a parallel severity config.**
   *Why:* one source of truth for "what's healthy"; avoids drift between the
   dashboard's notion of healthy and the agent's.
   *Revisit if:* the agent needs severity dimensions `brace check` doesn't model
   (e.g. business-impact weighting) — extend `brace check`, don't fork.
