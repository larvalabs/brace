# htmx 4.0.0 GA — reassessment

**Date:** 2026-08-28
**Follows:** [`2026-07-26-htmx-4-evaluation.md`](2026-07-26-htmx-4-evaluation.md) (beta6 evaluation)
**Evaluated against:** htmx `4.0.0` GA (released 2026-08-28), verified by reading
`htmx.org@4.0.0/dist/htmx.js` from the CDN, not just the announcement
**Outcome:** worth adopting, but **not urgent and not now** — schedule it as its own
`0.2.0`-shaped release once 4.0.x has a patch release or two under it. Everything the
July evaluation found still holds at GA; nothing shipped between beta6 and GA changes
the analysis.

## What GA confirms (checked in the 4.0.0 source)

Every load-bearing fact from the beta6 evaluation survived to GA:

- **`HX-Request: "true"` is still sent on every request** (`dist/htmx.js` line 433).
  `Request.isHtmx()`, the automatic `Vary: HX-Request`, and the `page:hx:` page-cache
  key split all keep working unchanged.
- **`HX-Request-Type: full|partial`** is in GA exactly as described: `full` when the
  target is `<body>` or `hx-select` is present, `partial` otherwise. The
  `req.htmxWantsPartial()` opportunity stands.
- **`noSwap: [204, 304]` is the GA default** — 4xx/5xx responses swap. The ops-dashboard
  token-expiry regression (401 body swapped through `hx-select`, dashboard goes blank)
  is real in GA and remains the one required code fix (`hx-status:4xx="swap:none"` or an
  explicit expired-session render).
- **`hx-status:XXX`** per-status overrides are in GA with pattern matching
  (`401`, `40x`, `4xx`).
- **Morph swaps (`innerMorph`/`outerMorph`) are native core**, no idiomorph dependency,
  with node matching improved since beta6.
- **Size: 36,716 bytes minified** vs our bundled 2.0.10 at 51,238 — a 28% reduction
  (same caveat: v4 pushes more into extensions, e.g. SSE left core after beta6).

New since beta6, none of it affecting Brace's surface: SSE extracted to a standalone
extension, a reworked `hx-ws` WebSocket extension, `hx-target`/`hx-source` request
headers, a `textContent` swap style, and an upgrade checker
(`npx htmx.org@4.0.0 upgrade-check`) useful to point app developers at in our
migration guide.

## Release strategy: still no pressure

The GA announcement repeats the schedule the July doc relied on: npm `latest` stays on
2.x and 4.0 stays `next` **until early 2027**, and htmx 2 "will continue to be supported
indefinitely." Staying on 2.0.10 costs nothing today — no CVEs, no deprecation.

## How big is the breaking change?

**For the framework itself: small.** `isHtmx()`, `Vary`, page-cache keys, and the entire
ops-dashboard attribute vocabulary (`hx-get`, `hx-headers`, `hx-select`, `hx-target`,
`hx-swap`, `hx-trigger="every 5s"`) work unchanged; `OpsDashboard` already repeats
attributes per element, so explicit inheritance doesn't bite. The one required fix is the
ops-dashboard 401 handling above.

**For apps: potentially large, and on our schedule, not theirs.** The bundled
`/__brace/htmx.min.js` is version-invisible to apps — swapping the file force-upgrades
every app's own htmx markup, where the removal of implicit attribute inheritance is the
big-ticket break (mitigable per-attribute with `:inherited`, globally with
`htmx.config.implicitInheritance = true`, or via the `htmx-2-compat` extension). This is
what makes the upgrade a minor-version framework release with a real migration guide,
not a file swap like 2.0.4 → 2.0.10 was.

## Recommendation

Adopt htmx 4 — morph swaps fix the focus/scroll-losing weak point of the full-page +
`hx-select` pattern we document as the default, `HX-Request-Type` lets us make the
partial-rendering guidance actually correct, and the page-weight win is real. But:

1. **Not on GA day.** Let 4.0.x collect its first patch releases; we hold a supported,
   current asset and npm `latest` agrees with us until early 2027.
2. **As its own release**, carrying the July doc's checklist unchanged: the ops-dashboard
   `hx-status` fix, the `req.htmxWantsPartial()`/`Vary`/`Cache`-key decision, morph-based
   default-pattern docs rewrite (`AGENTS.md`, `BRACE-AGENTS.md`, `README.md`,
   `ClaudeMdGenerator`), a `docs/migrations/` entry, and the call on shipping both
   assets for one transition release (recommendation: yes — serve v2 at the existing URL
   and v4 alongside, so apps opt in per-page and migrate on their own schedule; the
   migration guide should point apps at `npx htmx.org@4.0.0 upgrade-check`).

Revisit when scheduling the next minor release, or sooner if a 2.x advisory lands.
