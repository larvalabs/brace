## Short answer

**Yes — the core thesis makes sense.**  
“Explicit, small, predictable APIs are better for AI agents than magic-heavy frameworks” is a strong and defensible idea.

But your current framing sometimes **overstates certainty**, and the docs/specs still show some **internal drift** between the original thesis, the review docs, and the current README/security story.

If I were reviewing this as a product/framework proposal, I’d say:

> **Brace has a coherent and promising thesis, a good amount of real implementation depth, and a notably strong AI-ergonomics direction — but it should tighten its claims, reduce API/doc drift, and finish a few maturity/security gaps before leaning too hard on “production-ready” + “AI-optimized” as settled facts.**

---

## What’s strong

### 1. The core thesis is good
Your main argument is basically:

- AI agents do better with:
    - explicit wiring
    - fewer abstractions
    - less hidden runtime behavior
    - compile-time feedback
    - smaller API surfaces
- traditional “magic” frameworks increase context load
- a full-stack but compact framework can outperform both:
    - big frameworks on inference cost
    - microframeworks on fragmentation cost

That is **very plausible**.

This part especially is strong:

- explicit `main()` as architecture map
- request-scoped capabilities passed as parameters
- fewer places where behavior can hide
- built-in operational surface instead of “assemble 9 libraries and pray”

That’s a real design philosophy, not just marketing garnish.

---

### 2. “Batteries included, but explicit” is a good positioning wedge
This is probably your best product distinction.

You are not just saying “small framework.”  
You’re saying:

- small
- explicit
- integrated
- operationally aware
- AI-friendly

That combination is interesting. It avoids the usual trap where “microframework” means “you now own 17 integration decisions.”

---

### 3. The API review direction is mostly right
The proposed shifts toward:

- typed route registration
- source-specific request accessors
- unified `Result.*`
- constrained DB helpers
- safer storage defaults

are all solid.

Those changes do improve:

- agent generation reliability
- readability
- consistency
- misuse resistance

So the API redesign thesis also makes sense.

---

### 4. Built-in ops/diagnostics is a legitimate differentiator
A framework-native `/ops/status` with structured diagnostics is actually a good idea, especially if the framework claims to support autonomous or semi-autonomous agents.

That said, it’s also your highest-risk surface, which I’ll come back to.

---

## Where the thesis is too strong or under-proven

### 1. “Built for AI agents” is plausible, but still partly a hypothesis
You have some benchmark evidence, which is good, but right now the broader thesis still reads a bit like:

- one benchmark
- one app shape
- one comparison target
- then broad conclusions

That’s not wrong, but it’s not fully generalizable yet.

### What I’d change
Tone down from:

- “AI agents write better code when...”
- “Brace’s context scales linearly while Spring’s scales super-linearly”

to something like:

- “In our benchmark and design experience, agents tend to perform better when...”
- “Brace is designed to reduce context tracing and hidden behavior”

That makes the argument more credible, not weaker.

### Why
Because some of your claims are:
- **directionally true**
- but not yet **universally demonstrated**

Especially around:
- token efficiency across varied app types
- performance claims across production workloads
- autonomous ops loops being safe/reliable enough in practice

---

### 2. “Token efficiency” needs sharper definition
This is one of your best ideas, but also one of the easiest to hand-wave.

Right now it risks sounding like:
- fewer lines of framework code = fewer tokens
- therefore better for AI

That’s too simple.

### Token efficiency actually depends on at least:
- API regularity
- number of concepts
- consistency of examples
- ease of locating the “one right pattern”
- compile-time feedback quality
- predictability of failure modes
- documentation drift
- amount of generated glue code
- number of valid ways to do the same thing

So I’d explicitly define token efficiency in terms of:
- **context needed to correctly modify a feature**
- **number of candidate patterns an agent must evaluate**
- **frequency of runtime-only failures**
- **entropy of the framework API**

That makes the thesis much stronger.

---

### 3. Your “small API surface” claim is good, but only if you aggressively prevent drift
You currently risk losing the advantage by accumulating:
- aliases
- multiple ways to do the same thing
- older patterns remaining visible in docs
- transitional styles staying around forever

For AI ergonomics, **backward compatibility can quietly become anti-feature creep**.

Example shape of drift:
- `Json.of()` vs `Result.json()`
- `View.of()` vs `Result.view()`
- cast-based handlers vs typed route methods
- `param()` vs `pathParam/queryParam/formParam`
- `req.storage()` vs injected storage style

If all of these remain “equally documented,” the AI advantage degrades.

### Recommendation
Have:
- **supported**
- **preferred**
- **legacy but allowed**

and reflect that clearly in docs/examples.

Otherwise your small API becomes a medium API wearing a fake mustache.

---

## Biggest problems I see

## 1. Documentation drift is currently one of the biggest product risks
You have clear evidence of docs reflecting multiple generations of the framework:

- older design/security docs describe signed-only sessions and broad JSON CSRF exemptions
- current README/agents/security material presents encrypted sessions and explicit CSRF opt-out
- some design materials still describe logging/ops behavior differently from current implementation/docs
- counts/features appear to have evolved beyond some docs

This is not just a docs quality issue. For a framework claiming **AI reliability**, doc drift is especially expensive because agents learn from examples first.

### Why this matters for Brace more than normal
Your value proposition is:
- inferability
- explicitness
- one obvious way

Doc inconsistency directly attacks that thesis.

### Recommendation
Create a hard rule:

- **README = current public truth**
- **AGENTS.md = canonical API truth**
- **design docs = historical context unless marked current**
- **review docs = inputs, not truths**

And label old docs as:
- superseded
- historical
- implemented / not implemented

A simple frontmatter/status banner would help a lot.

---

## 2. The current README slightly overclaims maturity
The README is strong, but the tone is more mature than the remaining gaps justify.

Examples:
- big claims about production readiness
- strong AI and performance positioning
- broad included-feature surface

Yet your TODO/review material still shows important maturity items around:
- key rotation
- upload streaming/spooling
- redaction
- scoped ops tokens
- some metrics gaps
- documentation incompleteness
- migration guidance

That doesn’t mean the framework is weak. It means the message should be:

> “production-capable in many cases, but still maturing in a few security and platform areas”

rather than sounding fully settled.

---

## 3. Ops is powerful, but dangerously close to being “too much trust in one surface”
Your ops story is genuinely compelling.

It’s also the place where “AI-native framework” can become “one mistake = spectacular breach.”

### Main risks
- sensitive diagnostics exposure
- overly broad token power
- insufficient redaction
- control endpoints coupled with observability
- query-token fallback for dashboards increasing accidental leakage risk
- temptation for users to expose `/ops/*` too broadly because it’s useful

### Recommendation
You need a stronger opinionated model here:

#### Separate scopes
At minimum:
- `status:read`
- `errors:read`
- `dashboard:read`
- `cache:write`
- `errors:resolve`
- `control:admin`

#### Separate config knobs
- `app.opsStatus(...)`
- `app.opsDashboard(...)`
- `app.opsControl(...)`

or a config object that lets people disable control endpoints entirely.

#### Default redaction
Must be built-in, not optional hand-waving.

#### Stronger anti-leak posture
I would seriously reconsider query-param token support except maybe as an explicit temporary/dev-only dashboard mode. Query parameters leak into:
- browser history
- logs
- referrers
- monitoring systems

That’s one of the few places where convenience may be working against your own security thesis.

---

## 4. Logging/diagnostics need a redaction layer before you market observability heavily
If logs and ops diagnostics can capture:
- auth headers
- cookies
- tokens
- request bodies
- secrets in exception text
- storage keys or sensitive params

then your observability feature becomes a liability.

### Recommendation
Make redaction first-class and unavoidable in the ops pipeline:
- redact common sensitive header names
- redact common sensitive param names
- redact known secret-like values in structured payloads
- truncate large request bodies
- avoid storing raw session payloads entirely

This should be part of the framework identity, not a future maybe.

---

## 5. Upload handling is still a meaningful DoS/memory risk until streaming/spooling lands
You already know this from your roadmap, and I agree with the prioritization.

If uploads are memory-backed, then:
- concurrency becomes the real problem, not just size limit
- a “safe max upload size” can still be unsafe under load
- GC churn rises fast
- object storage integration doesn’t fully help if buffering happens first

### Recommendation
Move to:
- small files in memory
- larger files spooled to temp storage
- streaming path to storage provider when possible
- configurable part count limit
- configurable file count limit
- configurable spool threshold

This is a maturity gap worth addressing before leaning too hard into “production-safe uploads.”

---

## 6. Your DB API is good, but stringly-typed field names are still a weak point
The constrained helpers are a real improvement:

- `findBy`
- `findAllBy`
- `countBy`
- `existsBy`
- `deleteBy`

But they still rely on string field names, which means:
- typos are runtime errors
- refactors are brittle
- agents can still generate invalid field names
- nested-field semantics may get fuzzy

### Recommendation
Short term:
- keep them, they’re useful

Longer term:
- consider generated metamodel constants or a tiny field-ref helper if you want more compile-time safety without building a giant query DSL.

Not urgent, but worth noting: this is an “AI-friendly” improvement, not just a “Java purist” one.

---

## 7. Too many helper styles still exist
This is a repeat of the drift point, but it’s important enough to say plainly:

For agent reliability, **the existence of multiple valid styles is a tax**.

You should prefer:
- one default response style
- one default request-param style
- one default route-registration style
- one default storage upload style
- one default controller style

If alternative styles exist, they should be visibly secondary.

---

## Missing features / maturity gaps I’d prioritize

## Tier 1: important before stronger “production-ready” messaging
### 1. Key rotation
For:
- sessions
- ops auth

This is a real operational requirement, not just polish.

### 2. Redaction
Already discussed. High leverage.

### 3. Scoped ops tokens
Current broad ops authority is too coarse long-term.

### 4. Upload spooling/streaming
Important for safety and memory stability.

### 5. Better CSP story
Security headers are good; CSP support is where full-stack apps get real leverage.

### 6. Static file caching/validation
You likely want:
- `Cache-Control`
- `ETag`
- `Last-Modified`
- optional immutable asset mode

This is both performance and correctness.

---

## Tier 2: important for the AI thesis specifically
### 1. Canonical style guide as a real public artifact
Not just implied through examples.

Something like:
- preferred route methods
- preferred request accessors
- preferred response grammar
- preferred controller pattern
- preferred storage pattern

### 2. Migration guides per release
This is unusually important for an AI-oriented framework.
Agents need version-aware upgrade patterns.

### 3. “Golden path” reference app
One example app in the preferred style, kept current, is worth a lot.

### 4. A benchmark suite broader than one API app
Include:
- CRUD pages
- JSON API
- auth-heavy flows
- htmx app
- file upload flow
- background job feature addition

That would make the token-efficiency thesis much stronger.

---

## Specific incorrect assumptions / statements / weak claims

## 1. Old docs still describe security behavior that appears no longer true
You’ve got historical material that still says things like:
- sessions are signed but not encrypted
- JSON requests are CSRF-exempt by default

while newer docs indicate:
- encrypted sessions
- CSRF required by default for mutating requests, with explicit opt-out

That mismatch needs cleanup fast.

### Why it matters
Security docs cannot be “kind of right.”

---

## 2. “Safe to store emails, roles, permissions in session” is technically true but should still be phrased carefully
Even with encrypted cookies, I would avoid sounding too relaxed.

Why:
- cookie size limits still matter
- session replay semantics still matter
- confidentiality is not the only concern
- clients still possess the blob, even if unreadable
- revocation is harder with stateless sessions

A better phrasing is:

> “Encrypted sessions make storing moderate-sensitivity values practical, but keep session payloads small and avoid treating stateless cookies as a substitute for revocable server-side state.”

That’s more precise.

---

## 3. “Autonomous deploy/monitor/fix loops” is aspirational and should be framed carefully
This is exciting marketing, but it reads like a solved problem.

Real-world caveats:
- agents can misdiagnose
- ops endpoints can expose too much
- auto-remediation can amplify mistakes
- rollback and approval mechanisms matter

I’d frame this as:
- “supports agent-assisted operations”
- “enables autonomous workflows when paired with proper deployment controls”

Not as a casually complete system on its own.

---

## 4. “Context scales linearly” is too absolute
Nice intuition, too mathematically confident.

Better:
- “tends to scale more predictably”
- “reduces cross-cutting hidden context”
- “keeps most feature work local to controller + entity + template”

That reads more honest.

---

## 5. The design doc’s “~15 core types” and current feature breadth may be in tension
As you add:
- storage
- ops auth
- metrics
- websocket
- cache
- jobs
- uploads
- rate limiting
- security headers
- session options
- trusted proxies

the conceptual surface grows even if the framework code stays small.

That’s okay — but be careful not to oversell “tiny” once the mental model is no longer tiny.

A better framing is:
- **compact and regular**
  rather than simply
- **tiny**

Regularity is actually more important than raw count.

---

## Token-efficiency improvements I’d recommend

## 1. Make docs aggressively canonical
This is the highest ROI item.

For each feature, show:
- preferred style
- supported alternatives
- anti-patterns to avoid

Example:
### Routing
- preferred: `getDb`, `postFull`
- supported: cast-based registration
- avoid in new code: cast-based examples

### Request params
- preferred: `intPathParam`, `queryInt`, `formParam`
- fallback: `param()`
- avoid in new code: ambiguous source lookup

This helps humans and agents equally.

---

## 2. Reduce alternate spellings and namespaces
Try to converge on:
- `Result.*`
- typed route methods
- source-specific request accessors
- generated storage keys

Even if aliases remain, keep them out of most examples.

---

## 3. Prefer named objects over varargs once complexity grows
This applies especially to template/view models.

Key/value varargs are fine for 1–2 values.  
Past that, they become a token and correctness trap.

Good next step:
- typed view model object
- or `Map`
- or a tiny `Model` builder

This reduces:
- odd/even arg mistakes
- wrong key names
- duplicated names
- hard-to-read long calls

---

## 4. Add copy-pasteable recipes for common patterns
For AI generation, recipes beat reference docs.

High-value recipes:
- CRUD page with auth
- JSON API with bearer auth and no CSRF
- cookie-auth JSON endpoint with CSRF
- file upload with safe storage key
- login with rate limiting
- background email job
- htmx list/detail flow

That’s where token savings become real.

---

## 5. Consider capability bundles carefully
Your current parameter-injection idea is good, but avoid exploding handler types forever.

If you create too many:
- `DbHandler`
- `SessionHandler`
- `FullHandler`
- `StorageHandler`
- `CacheHandler`
- `MailerHandler`
- etc.

you may trade one kind of complexity for another.

### My take
Keep the set small:
- `Request`
- `Database`
- `Session`

For less common things, either:
- use `req.storage()` / `req.cache()`
- or introduce only a very small number of additional typed helpers

Too many combinatorial handler types would hurt your thesis.

---

## 6. Make generated/project reference docs versioned and machine-legible
If you really want agent friendliness, consider a generated structured reference artifact, not just prose.

For example:
- routes
- handlers
- templates and params
- forms and validations
- entities and fields
- jobs
- config keys

JSON or YAML alongside human-readable docs would help tools/agents enormously.

That would be very on-brand for Brace.

---

## Security issues still worth watching

Even with the improvements already made, I’d still watch these closely:

### 1. Constant-time compare for CSRF token validation
Small issue, easy win.

### 2. Query-parameter token auth for ops dashboard
Convenient, but risky as a long-term default.

### 3. Redaction in logs and ops dumps
Needs to be systematic.

### 4. Stateless session limitations
Encrypted cookies don’t solve:
- revocation
- forced logout
- replay considerations
- server-side invalidation complexity

If you support auth-heavy apps, document that clearly.

### 5. Storage/content validation
Safe key generation is good, but not enough.
You still need guidance for:
- content type verification
- extension allowlists
- public/private separation
- serving uploaded HTML/SVG carefully

### 6. WebSocket operational/security guidance
Since you support WS now, you should probably document:
- auth on connect
- per-room authorization
- origin checks
- message size limits
- connection metrics
- abuse/rate-limit strategy

That feels like a missing docs/security section.

---

## My overall assessment

## Does the thesis make sense?
**Yes. Definitely.**

More precisely:

- the **design thesis** is strong
- the **API direction** is strong
- the **AI ergonomics argument** is plausible and interesting
- the **observability angle** is differentiated

## What weakens it today?
Mostly not the code idea — the **positioning discipline**:

- too much doc drift
- a few claims stated too absolutely
- some maturity/security gaps still open
- too many transitional API styles still visible

## What I’d say publicly right now
Something like:

> Brace is a compact, explicit Java web framework designed to be easier for AI agents and humans to understand, modify, and operate. Its strongest advantages are predictable wiring, compile-time-friendly patterns, and built-in observability. It is already quite capable, but still maturing in a few areas like redaction, key rotation, upload streaming, and ops authorization scope.

That would be accurate and convincing.

---

## Highest-priority next moves

If I had to pick **5**:

1. **Eliminate doc drift** across README / AGENTS / SECURITY / design docs
2. **Ship redaction** for logs + ops diagnostics
3. **Add key rotation** for sessions and ops auth
4. **Add scoped ops tokens** and reconsider query-token usage
5. **Finish upload spooling/streaming** and document safe upload patterns

And for the AI-token thesis specifically:

6. **Make one canonical style unavoidable in docs/examples**

---

If you want, I can next turn this into either:

1. **a line-by-line critique of the README/thesis wording**, or
2. **a prioritized action plan split into “must fix / should fix / nice to have.”**

## Prioritized action plan

I’d structure this as **4 tiers** so you can keep momentum without blurring “must fix” with “nice to have.”

---

# Tier 0 — Fix now before stronger marketing claims
These are the items that most affect **credibility**, **security posture**, and the **AI-friendly thesis**.

## 1. Eliminate documentation drift
**Why first:** your framework’s pitch is explicitness and inferability. Inconsistent docs directly undermine that.

### Do
- Make **README** the public product narrative
- Make **AGENTS.md** the canonical API reference
- Make **SECURITY.md** the canonical security truth
- Mark older design/review docs as:
  - `historical`
  - `implemented`
  - `superseded`
  - `proposal only`

### Deliverables
- Add status banners to old docs
- Remove outdated wording from current-facing docs
- Ensure all examples use the same preferred style

### Success metric
A new reader should not be able to find two contradictory descriptions of the same feature in 10 minutes.

---

## 2. Ship redaction for logs, ops, and error diagnostics
**Why second:** this is the biggest remaining “observability becomes liability” risk.

### Do
- Redact sensitive headers by default
- Redact sensitive query params by default
- Redact obvious secret-like keys in structured data
- Truncate large request bodies
- Avoid storing raw session/auth material in diagnostics

### Minimum built-in defaults
Redact keys matching patterns like:
- `authorization`
- `cookie`
- `set-cookie`
- `token`
- `secret`
- `password`
- `key`

### Success metric
Sensitive request/auth material should not appear in logs or ops payloads by default.

---

## 3. Add key rotation for sessions and ops auth
**Why third:** this is a real production requirement, not a polish item.

### Do
- Support:
  - current signing/encryption key
  - previous verification/decryption keys
- Apply the same concept to:
  - sessions
  - ops tokens / ops auth keys if applicable
- Document rotation procedure

### Success metric
Operators can rotate secrets without forcing global logout or breaking ops access instantly.

---

## 4. Tighten ops hardening model
**Why fourth:** your ops surface is a major differentiator and a major blast radius.

### Do
- Add **scoped ops tokens**:
  - read-only
  - dashboard
  - control/admin
- Allow disabling control endpoints separately
- Strengthen docs around:
  - HTTPS-only exposure
  - reverse proxy restriction
  - private network expectations
- Reconsider query-parameter token usage as a long-term default

### Success metric
A compromised read-only token cannot mutate state, and users have a clear least-privilege ops setup.

---

# Tier 1 — Highest ROI product improvements
These improve the **AI ergonomics story** the most with relatively low conceptual risk.

## 5. Finish the additive API cleanup
This is the main product-shaping work.

### Order within this bucket
1. **Typed route methods**
2. **Source-specific request accessors**
3. **Unified `Result.*` helpers**
4. **`Form.hasErrors()`**
5. **Constrained DB helpers**

### Why this order
- Routing + request accessors remove the most ambiguity
- `Result.*` creates a single response grammar
- `hasErrors()` improves readability cheaply
- DB helpers reduce string-generation burden

### Success metric
The preferred style for common app code becomes mechanically obvious.

---

## 6. Publish an official “Brace style guide”
**Why:** APIs alone don’t create consistency; examples do.

### Include
- preferred controller pattern
- preferred route registration pattern
- preferred request-access pattern
- preferred response pattern
- preferred storage upload pattern
- preferred auth/CSRF pattern
- when to use alternatives

### Phrase it clearly
Use:
- **Preferred**
- **Supported**
- **Avoid in new code**

### Success metric
Agents and humans see one dominant pattern per feature.

---

## 7. Update all public examples to the canonical style
**Why:** examples teach the framework more than reference docs do.

### Update examples to prefer
- typed routes over casts
- source-specific accessors over ambiguous access
- `Result.*` over fragmented helpers
- safe storage helpers over raw filename composition
- explicit CSRF behavior in auth examples

### Success metric
A user copy-pasting from docs naturally lands on the recommended style.

---

# Tier 2 — Security and production maturity
These matter a lot for real-world confidence, but come after the above because they’re slightly less central to the “what is Brace?” story.

## 8. Implement upload spooling/streaming
**Why:** current in-memory upload handling is a real resource-risk area.

### Do
- small files in memory
- larger files spooled to temp storage
- streaming path to object storage where possible
- configurable thresholds
- configurable multipart part/file count limits

### Success metric
Concurrent uploads don’t translate directly into heap pressure.

---

## 9. Add CSP helpers
**Why:** security headers are a good start; CSP is the more meaningful XSS control for HTML apps.

### Do
- builder API for CSP
- nonce support if you want inline script compatibility
- sane defaults for full-stack HTML apps
- docs showing how to enable it safely

### Success metric
HTML apps have a practical, first-class path to stronger browser-side security.

---

## 10. Improve static asset serving maturity
### Add
- `Cache-Control`
- `ETag`
- `Last-Modified`
- optional immutable asset mode
- extension/dotfile safety guidance

### Success metric
Static assets are both safer and more production-efficient out of the box.

---

## 11. Expand operational metrics where you already know gaps exist
Prioritize:
- WebSocket metrics
- upload metrics
- maybe per-endpoint auth/rate-limit counters

### Success metric
Your ops story covers the major runtime surfaces consistently.

---

# Tier 3 — Evidence, onboarding, and adoption
These don’t unblock correctness, but they greatly improve trust and adoption.

## 12. Build a stronger benchmark/evidence package
**Why:** your AI-token thesis is promising, but still under-proven.

### Add
- at least one more benchmark app besides the current shape
- feature-addition benchmarks across:
  - CRUD app
  - API app
  - auth-heavy app
  - htmx/full-stack flow
- methodology page focused on:
  - prompt standardization
  - token accounting
  - failure/retry handling
  - version pinning

### Success metric
The “AI-efficient” claim is repeatable, not just suggestive.

---

## 13. Create migration guides per release
**Why:** very on-brand for an AI-first framework.

### Do
- one migration file per release
- “what changed / what to replace / before / after”
- keep them short and diff-oriented

### Success metric
Upgrades become agent-friendly and lower-risk.

---

## 14. Add a real getting-started guide and one “golden path” sample app
**Why:** you need one curated example that represents current best practice.

### Include
- routing
- forms
- auth/session
- CSRF
- database
- templates
- uploads
- metrics/ops basics

### Success metric
A new project can follow one blessed path end-to-end.

---

## 15. Improve release positioning
**Why:** right now the strongest risk is not bad implementation, but overclaiming.

### Suggested positioning
Avoid saying:
- universally production-ready
- autonomous fix loops as if solved
- mathematically strong context-scaling claims

Prefer:
- production-capable, maturing in a few areas
- agent-assisted / agent-friendly
- designed to reduce hidden context and runtime surprises

### Success metric
Your claims become harder to dismiss and easier to trust.

---

# Recommended execution order by sprint

## Sprint 1
- Documentation drift cleanup
- Official style guide
- Example cleanup
- Release-positioning polish

## Sprint 2
- Redaction layer
- Ops hardening docs
- Scoped ops token design
- Query-token policy decision

## Sprint 3
- Key rotation
- Typed route methods
- Source-specific request accessors

## Sprint 4
- Unified `Result.*`
- `Form.hasErrors()`
- Constrained DB helpers
- Safe storage examples/docs sweep

## Sprint 5
- Upload spooling/streaming
- Upload metrics
- Static asset caching/validation improvements

## Sprint 6
- CSP helpers
- WebSocket metrics
- Getting started guide
- Golden path sample app

## Sprint 7+
- Broader token-efficiency benchmark suite
- Migration guides per release
- Secondary maturity items

---

# Simple priority table

| Priority | Item | Why |
|---|---|---|
| P0 | Doc drift cleanup | Protects the core thesis immediately |
| P0 | Redaction layer | Biggest remaining ops/security risk |
| P0 | Key rotation | Real production requirement |
| P0 | Ops token scopes / hardening | Reduces blast radius |
| P1 | Typed routes | High AI ergonomics ROI |
| P1 | Source-specific request accessors | Removes ambiguity |
| P1 | Unified `Result.*` | One response grammar |
| P1 | Style guide + examples | Makes the “golden path” real |
| P2 | Upload spooling/streaming | Resource safety |
| P2 | CSP helpers | Stronger HTML app security |
| P2 | Static asset maturity | Safer and faster defaults |
| P3 | Better benchmark suite | Strengthens thesis |
| P3 | Migration guides | Very agent-friendly |
| P3 | Getting started + sample app | Adoption and clarity |

---

# My recommended top 5

If you only do **five things next**, do these:

1. **Clean up documentation drift**
2. **Add redaction**
3. **Add key rotation**
4. **Add scoped ops tokens / tighten ops model**
5. **Finish typed routes + source-specific request accessors**

That combination most improves:
- trust
- safety
- AI usability
- product clarity

---

If you want, I can next turn this into a **concrete roadmap with issue titles, acceptance criteria, and suggested ordering for the TODO files**.