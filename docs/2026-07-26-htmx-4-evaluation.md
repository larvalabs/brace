# htmx 4 evaluation (beta6) — what it changes for Brace

**Date:** 2026-07-26
**Evaluated against:** htmx `4.0.0-beta6` (published 2026-07-23), read from source, not just the docs
**Outcome:** bump the bundled asset to htmx **2.0.10** now; **do not** adopt htmx 4 until it goes GA.

---

## Status of the two lines

| | Version | Date | npm tag |
|---|---|---|---|
| What Brace bundled before this doc | 2.0.4 | 2024-12-13 | — |
| Current stable | **2.0.10** | 2026-04-21 | `latest` |
| Current v4 preview | **4.0.0-beta6** | 2026-07-23 | `next` |

htmx.org advertises v4 as "in beta, with a target release date of Summer '26". Two schedule
facts matter more than that banner, both from the author's [*The fetch()ening*](https://htmx.org/essays/the-fetchening/)
essay:

- **"4.0 will be marked `latest` in early-2027ish."** Even after 4.0 ships, npm `latest`
  is planned to stay on 2.x for months. The 2.x line is not being retired at GA.
- **"htmx 2.0 (like htmx 1.0 & intercooler.js 1.0) will be supported *in perpetuity*."**

Beta6 is also still making breaking changes — it renamed `htmx:swap:finally` to
`htmx:finally:swap` three days before this evaluation. The API is not settled.

## Bundled asset: 2.0.4 → 2.0.10 (done)

No CVE or Snyk advisory exists against 2.0.4, so this is hygiene rather than an incident.
It is a drop-in file swap — `BraceHandler` derives the ETag from the file's bytes, so
nothing else in the framework needed to change. Notable fixes picked up:

- **2.0.5** — history cache moved from `localStorage` to `sessionStorage` (stops history
  DOM snapshots leaking across tabs).
- **2.0.7** — `reportValidity()` for form errors; screen-reader-compatible indicator styling.
- **2.0.8** — `parseHTML` uses `Document.parseHTMLUnsafe()` (Web Components); `hx-sync` and
  `htmx:abort` fixed inside Shadow DOM.
- **2.0.9** — `HX-Location` honors `replace` when `push` is false; history path
  normalization; `hx-disabled-elt` no longer re-enables already-disabled elements.
- **2.0.10** — `CSS.escape()` in settle lookup; restored TypeScript definitions.

## What htmx 4 would break in Brace: less than expected

Verified by reading `htmx.org@4.0.0-beta6/dist/htmx.js`, not by trusting the migration guide.

**`HX-Request: "true"` is still sent on every request.** From `#createCoreHeaders` in the
beta source. This is the load-bearing fact for Brace: `Request.isHtmx()`
(`Request.java:311`), the automatic `Vary: HX-Request` (`BraceHandler.java:411`), and the
`page:hx:` page-cache key split (`Cache.java:360`) all keep working with no change.

**The whole ops-dashboard attribute vocabulary survives** — `hx-get`, `hx-post`,
`hx-headers`, `hx-select`, `hx-target`, `hx-swap`, and `hx-trigger="every 5s"` are all
still present and behave the same.

**The explicit-inheritance change does not bite us.** htmx 4 drops implicit attribute
inheritance (opt back in per-attribute with `:inherited`, or globally with
`htmx.config.implicitInheritance = true`). `OpsDashboard` already repeats
`hx-headers`/`hx-target`/`hx-select`/`hx-swap` on every requesting element
(`OpsDashboard.java:120-122`, `:517-519`, `:610-613`), so there is nothing to annotate.

**Most of the v4 breakage list is inapplicable.** Brace uses no `hx-ext`, `hx-vars`,
`hx-params`, `hx-disable`, `hx-disinherit`, `hx-on`, no JS event listeners, and no
`htmx.*` JS API calls — so the attribute renames, the event-name overhaul
(`htmx:afterSwap` → `htmx:after:swap`), the extension-API rewrite, and the removed helper
methods cost us nothing.

### The one real regression: ops dashboard on token expiry

htmx 4 defaults to `noSwap: [204, 304]` — **every other status swaps**, including 4xx and
5xx. htmx 2 did not swap error responses at all.

The dashboard polls `/ops/dashboard` every 5s with a token that has a 2h TTL. When that
token expires today, the 401 simply doesn't swap and the page keeps showing stale data.
Under htmx 4 the 401 body *would* be swapped: `hx-select="#dashboard-content"` matches
nothing in an error body, and because `#processMainSwap` applies the select **after** its
emptiness check (the raw error body is non-empty, so the swap proceeds), the resulting
fragment is empty and the `outerHTML` swap replaces the dashboard with nothing. The page
goes blank.

One attribute fixes it — `hx-status:4xx="swap:none"` on the polling div and the action
buttons — and the better version renders an explicit "session expired, re-authenticate"
state. Either way this is a required change, not an optional one, whenever we do move.

## What htmx 4 improves, ranked by what it's worth to Brace

1. **Morph swaps (`innerMorph` / `outerMorph`), in core.** The single most valuable one,
   because it repairs the weak point of *the pattern Brace documents as its default*:
   render the full page, `hx-select` the element, swap `outerHTML`. That destroys and
   recreates the node every cycle, losing focus, scroll position, text selection, and open
   `<details>`. Morphing patches in place instead. Confirmed present as a native `#morph`
   implementation — no idiomorph dependency to bundle.
2. **~29% smaller.** 36,282 bytes vs 51,238 minified. Brace ships this on every page that
   opts in, so it is a real page-weight win. (Caveat: v4 splits more functionality into
   extensions, so it is not a strict like-for-like build.)
3. **`HX-Request-Type: full|partial`.** Set to `full` when the target is `<body>` *or*
   `hx-select` is present, `partial` otherwise. This is exactly the distinction
   `req.isHtmx()` cannot make today: an `hx-select` request *is* an htmx request that needs
   the whole page. Our current guidance ("use `isHtmx()` to return partials") is therefore
   subtly wrong for the `hx-select` recipe the same docs recommend. A `req.htmxWantsPartial()`
   built on this header would make the guidance correct — and would require extending both
   the automatic `Vary` and the `Cache` page-key prefix to include `HX-Request-Type`.
4. **History no longer snapshots the DOM into web storage.** v4 re-fetches on back
   navigation instead of restoring a stored snapshot, removing a "sensitive rendered HTML
   sitting in sessionStorage" concern. Worth a line in `docs/SECURITY.md` when we adopt.
5. **`hx-status:XXX`** — per-status swap/target/select control. What makes finding #1 above
   a non-issue, and generally useful for validation-error responses.

Also in the box, lower relevance to us today: `<hx-partial>` for multi-target responses,
built-in `hx-preload`, View Transitions via `htmx.config.transitions`, `fetch()`-based
streaming with the new `hx-multipart` extension, and a `htmx-2-compat` extension that
restores implicit inheritance, old event names, and the old error-swap defaults.

## Recommendation

**Now (this change):** bundle 2.0.10. Zero code changes, no behavior change for apps.

**Not now:** htmx 4. It is a `0.2.0`-shaped change for Brace, not a patch, and it needs:

- `hx-status:4xx="swap:none"` (or an explicit expired-session render) in `OpsDashboard`
- a decision on `req.htmxWantsPartial()` / `HX-Request-Type`, including `Vary` and
  `Cache` page-key implications
- adopting morph swaps in the documented default pattern, and rewriting the htmx sections
  of `AGENTS.md`, `BRACE-AGENTS.md`, `README.md`, and `ClaudeMdGenerator` around them
- a `docs/migrations/` entry covering all of the above for apps that wrote their own htmx
- a call on whether to ship both files for one release, since apps have their own htmx code
  that upgrades on our schedule, not theirs

Revisit when 4.0.0 GA ships. There is no pressure to be early: 2.x keeps `latest` into
early 2027 and is supported indefinitely.
