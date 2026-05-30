# Plan: scoped read-only ops token

Status: draft
Date: 2026-05-30

## Goal

Give the ops surface a notion of **token scope** so an operator can hand out a
credential that reads `status`/`errors`/`logs`/`routes`/`cache`-stats (and the
future `regressions`) but *cannot* hit any control endpoint (`cache/clear`,
`errors/{id}/resolve`, future restart/rollback). This is the first gating
prerequisite for the `brace-oncall` agent (see
`2026-05-29-brace-oncall-agent-plan.md`): an always-on autonomous agent holding a
full-power ops key is an unacceptable blast radius. Tracked in `TODO.md:175`.

## Current model (the starting point)

- **Identity = Ed25519 keypairs.** Authorized public keys live in a flat file
  (`opsKeysPath`), loaded as a `Set<String>` by `OpsKeys.loadAuthorizedKeys` —
  one base64 key per line, optional label, `#` comments.
- **Mint flow:** CLI signs a timestamp with its private key → `POST /ops/auth`
  (`OpsHandler.auth`) → server checks the key is authorized + signature valid +
  timestamp fresh → mints an HMAC bearer token `base64url({"exp":…}).hmac` via
  `OpsToken.create(secret, ttl)`.
- **The token carries expiry and nothing else** — `{"exp":…}`. `OpsToken.validate`
  returns a boolean.
- **`authorize(req)` is one undifferentiated gate** (`OpsHandler.authorize`, line
  481) — every endpoint calls it identically, so read endpoints and control
  endpoints are indistinguishable to the auth layer.

The gap is exactly one thing: **there is no scope anywhere in the model.**

## Core design decision: scope lives on the *key*, not just the token

Two layers, and the distinction is load-bearing:

1. **Key scope = the ceiling** (in the authorized-keys file). A `read`-ceiling key
   can *never* obtain a control token. This is what makes handing the agent a
   read-only key safe — escalation is impossible by construction, not by policy.
2. **Token scope = the carried grant** (≤ the key's ceiling). `authorize` reads it
   and compares against what the endpoint requires.

If scope lived *only* in the token (client asks for it at mint time), any key could
mint an admin token — worthless for least-privilege. The key file is the source of
truth for the ceiling; the token just carries the (possibly down-capped) grant.

## Scope model — two scopes, ordered enum

- `READ` → `status`, `errors`, `logs`, `routes`, `cache` (stats), future `regressions`.
- `CONTROL` → everything `READ` can do **plus** `cache/clear`, `errors/{id}/resolve`,
  future restart/rollback. `CONTROL` implies `READ`.

Represented as an **ordered enum** (`CONTROL` ≥ `READ`) so a third tier (e.g. a
dashboard/operator split) can be added later without rework. A human exchanging a
login token for a browser session is treated as an operator → `CONTROL`.

**Decisions (confirmed):**
- **Two scopes to start** (`READ` + `CONTROL`), ordered enum for future growth.
- **`errors/{id}/resolve` requires `CONTROL`** — it mutates error state. The
  on-call agent's tier-1 "auto-resolve self-healed errors" therefore needs a
  *separate* narrowly-scoped grant later, never the read key (matches on-call plan
  rationale #6).
- **Embed a key id (`kid`) in tokens now** to unblock the audit-log prerequisite.

**Backward compatibility is the linchpin — the change is purely additive:**
- Token with **no `scope` field → treated as `CONTROL`** → existing CLI / dashboard
  / htmx tokens keep working across the deploy.
- Key with **no declared scope → ceiling `CONTROL`** → existing keys files work
  unchanged.

You opt in by declaring a `read` key; everything else behaves as today.

## Concrete changes

### 1. `OpsToken` — carry scope + key id

- Add `create(secret, ttl, scope, kid)`; payload becomes
  `{"exp":…,"scope":"read","kid":"<fp>"}`.
- Replace boolean `validate` with `verify()` returning the claims (keep a boolean
  wrapper for existing call sites). Absent `scope` → `CONTROL`.
- ⚠️ The current payload parse is a brittle `replaceAll("[^0-9]","")` regex on
  `exp`. Adding string fields means switching the decode to **Jackson** (already a
  dependency) to parse `exp`/`scope`/`kid` properly.
- `kid` = a short fingerprint of the minting public key. Nearly free at mint time;
  it's what lets the audit log record *which* key made each read.

### 2. Scope enum + per-endpoint requirement

- `enum OpsScope { READ, CONTROL }` with ordinal comparison.
- `authorize(req)` → `authorize(req, OpsScope required)`; true iff token scope ≥
  required. Absent scope → `CONTROL` (back-compat).
- Tag routes: `READ` for `status`/`routes`/`logs`/`errors`/`cacheStats`; `CONTROL`
  for `clearCache`/`resolveError`/`loginToken`. `dashboard` GET requires `READ`
  (harmless to view); its action buttons POST to control endpoints, which
  self-enforce `CONTROL`, so a read token sees the dashboard but gets 401 on
  clear/resolve.

### 3. `/ops/auth` mints at the key's ceiling

- `OpsKeys.loadAuthorizedKeys`: `Set<String>` → `Map<String, OpsScope>`. Keys-file
  line gains an optional `scope:read` / `scope:control` token (absent → control);
  label stays free text:
  ```
  <key>   scope:read   oncall-agent
  <key>                ops-laptop      # no scope → control
  ```
- In `auth()`: after verifying the key, look up its ceiling; the requested scope
  (new optional field in `OpsAuthRequest`, default = ceiling) is capped at
  `min(requested, ceiling)`. A read-ceiling key asking for control is silently
  down-capped to read (safe, friendlier than 403).

### 4. CLI

- `brace ops authorize <pubkey> --read-only --label oncall` writes the `scope:read`
  line.
- The agent's own `/ops/auth` call needs **no client change** — its read key caps
  it automatically. Operator CLI is unaffected (no scope field → control ceiling).

### 5. Audit seam (pairs with prerequisite — not built here)

`authorize` is now the single choke point that resolves a token *with* its `kid` and
scope — the natural place a later audit store will attach to emit
`(kid, scope, endpoint, timestamp)`. This plan creates the hook, not the store.

### 6. Tests

- `OpsTokenTest`: scope + kid round-trip; absent scope → control; tampered scope
  fails HMAC.
- Read token → 401 on `clearCache`/`resolveError`, 200 on `status`/`errors`/`logs`.
- `auth()` down-caps a control request from a read-ceiling key.
- Back-compat: unscoped key → control ceiling; legacy token (no scope field) → control.
- `CliOpsTest`: `--read-only` writes a `scope:read` authorized-keys line.

### 7. Docs

Update `BRACE-AGENTS.md` + `README` ops sections (scopes, keys-file format, minting
a read-only agent key) per the CLAUDE.md public-API rule. Check the item off
`TODO.md`.

## Rollout

Purely additive — **no DB migration** (tokens are stateless HMAC, keys are a flat
file), zero breakage on deploy. Onboarding the agent: generate an agent keypair, add
its public key with `scope:read`, hand it the private key + ops URL. It can then read
`status`/`errors`/`logs` and *nothing* else.

## Touched files

- `OpsToken.java` — scope+kid in payload, Jackson parse, `verify()` returning claims.
- `OpsScope.java` *(new)* — the ordered enum.
- `OpsHandler.java` — `authorize(req, required)`, per-endpoint scope tags, `auth()`
  ceiling cap, `OpsAuthRequest.scope`.
- `OpsKeys.java` — `loadAuthorizedKeys` → `Map<String, OpsScope>`, parse `scope:` token.
- `Brace.java` — thread the scoped key map into `OpsHandler`.
- `CliOps.java` / `CliAuth.java` — `--read-only` authorize; optional scope request.
- Tests + `BRACE-AGENTS.md` + `README` + `TODO.md`.
