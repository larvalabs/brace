# Brace Analytics: server-side visitor counts

Status: **phase 1 implemented** (0.1.11). Decisions taken: concrete paths by default, no public
share link. Mockup: `docs/analytics-mockup.html`. Code: `Analytics`, `UserAgents`,
`AnalyticsDashboard`, framework migration V18.

## Why

We ran a self-hosted Plausible instance to answer one question: *how much is the site being hit,
and by roughly whom?* It was heavy to run (Elixir app + Postgres + ClickHouse), it was one more
internet-facing app with its own login, and it was compromised. Everything it told us that we
actually looked at can be computed by the Brace app itself, from requests it already handles.

Doing it server-side has a second benefit: there is no tracking script on the page, so nothing for
an ad blocker or privacy extension to block, no third-party request, and no consent banner.

## Goals

- Opt-in with one line: `app.analytics()`.
- Answer "how busy is the site": visitors, pageviews, top pages, where visitors came from,
  device/browser mix, optionally country. Today / 7 days / 30 days / 12 months.
- No cookies, no client-side script, no IP addresses or user agents stored.
- No new public endpoint. The viewer sits behind the existing ops auth.
- Cheap on the hot path, works on H2 and Postgres, correct behind a load balancer.

## Non-goals

Goals/conversions, custom events, funnels, user flows, outbound-link tracking, revenue, session
recording, scroll depth, A/B tests, cross-day visitor identity. If an app needs any of that it
should use a real product-analytics tool. `Metrics.counter(...)` already covers "count this
business event" on the ops dashboard.

## What we keep from Plausible, and what we drop

| Plausible feature | Brace analytics |
|---|---|
| Unique visitors (daily-salted hash, no cookie) | **Keep**, same technique |
| Pageviews, views per visit | **Keep** |
| Top pages | **Keep** (concrete path; per-route `.analyticsByRoute()` to group by pattern) |
| Sources (referrer, `utm_source`, `?ref=`) | **Keep** |
| Devices, browsers, OS | **Keep** (hand-rolled UA + client-hints classifier, no dependency) |
| Countries | **Keep if a proxy supplies it** (`CF-IPCountry` etc.); no GeoIP database |
| Realtime "visitors now" | **Keep** (cheap: distinct visitors in the last 5 min) |
| Click-to-filter (e.g. pages for one source) | Phase 2 |
| Bounce rate, visit duration | Drop (needs a JS ping to be meaningful) |
| Entry/exit pages, goals, funnels, events, props | Drop |
| Public share links, embeds | Open question (default off) |
| Email reports | Maybe later, via `Mailer` |

## How it works

Every response already passes through one choke point, `BraceHandler.recordAndLog(...)`, which
runs after the response is sent and feeds `Stats` and the request log. Analytics adds one call
there:

```
request ─▶ route ─▶ handler ─▶ response sent ─▶ recordAndLog
                                                  ├─ Stats.recordRequestPattern   (ops, existing)
                                                  ├─ Log.request                  (existing)
                                                  └─ Analytics.observe(...)        (new, BraceHandler.send)
                                                        │ classify: is this a pageview?
                                                        │ hash visitor, parse UA/referrer
                                                        ▼
                                                  bounded in-memory buffer
                                                        │ every 10s (everyLocal job)
                                                        ▼
                                           brace_analytics_pageviews  (raw, ~35 days)
                                                        │ phase 2: nightly rollup
                                                        ▼
                                           brace_analytics_daily      (aggregates, kept)
```

Because it runs after the response is written, analytics adds nothing to user-visible latency.
The per-pageview cost is a handful of header checks, one SHA-256 over ~100 bytes, and an
enqueue. Non-pageview requests (assets, JSON, htmx partials, bots) exit after the first few
checks.

The hook sits in `send()`, which has the final `Result`, so the classifier reads the response
`Content-Type` directly. The IP and user agent stay in the in-memory buffer until the flush
hashes them; they are never written.

### What counts as a pageview

Requests that were never candidates (non-GET, no matched route, `/ops/*`, non-HTML, non-2xx) are
ignored silently. Candidates that fail a filter increment a per-reason counter (`bot`, `htmx`,
`background`, `prefetch`, `excluded`, plus `dropped` for a full buffer), so the dashboard can
show what was filtered and the numbers can be trusted.

| Rule | Why |
|---|---|
| `GET` only | Forms, APIs and preflights are not page views |
| Status 2xx or 304 | 404s from scanners would otherwise dominate; 304 is a real revisit |
| Response `Content-Type: text/html` | Excludes JSON, files, feeds on the same app |
| A matched route; not `/ops/*`, `/__brace/*`, static files, or a configured exclude | Our own traffic and assets |
| htmx: `HX-Request` without `HX-Boosted` → **skip**; `HX-Boosted` or `HX-History-Restore-Request` → **count** | A partial swap is not a page load; a boosted link click is a navigation |
| `Sec-Fetch-Mode` present and not `navigate` → skip (unless boosted, above) | Browsers send `Sec-Fetch-*` on every request since 2023 (Safari 16.4); bots mostly don't |
| `Sec-Purpose`/`Purpose: prefetch` → skip | Speculative prefetches would double-count |
| User-Agent missing, or matches the bot list (`bot`, `crawl`, `spider`, `curl`, `python`, `headless`, `lighthouse`, uptime monitors, link-preview fetchers …) → skip | The bulk of non-human traffic |
| Client IP in `excludeIps(...)`, or the request carries the "don't count me" cookie | Keep our own visits out |

A `strictNavigation()` option requires `Sec-Fetch-Mode: navigate` outright. That removes nearly
all scripted traffic at the cost of very old browsers.

### Visitors without cookies

Same approach as Plausible:

```
visitor = first 8 bytes of SHA-256( dailySalt ‖ host ‖ clientIp ‖ userAgent )   → stored as BIGINT
```

- `dailySalt` is 32 random bytes per calendar day (in the configured timezone), stored in
  `brace_analytics_salts` so every instance in a fleet uses the same one. The first instance to
  need a day's salt inserts it (`ON CONFLICT DO NOTHING` on Postgres); the others read it. Each
  flush deletes salts whose day ended more than 5 minutes ago (the grace covers views buffered
  just before midnight), so after that nobody can recompute yesterday's hashes, even with a list
  of candidate IPs.
- `clientIp` comes from `req.ip()`, which honours `trustedProxies(...)`. **If the app sits behind
  a proxy without trusted proxies configured, every visitor hashes to the proxy's address and the
  site shows one visitor.** Analytics logs a startup warning when it sees forwarding headers from
  an untrusted peer.
- The IP and user agent are used for the hash and the classifier, then dropped. They are never
  written anywhere.

Consequences, stated on the dashboard so nobody misreads them:

- A visitor is counted once **per day**. "Visitors, last 30 days" is the sum of daily visitors,
  not distinct people. (Plausible behaves the same way.)
- People behind one NAT with identical browsers merge; one person switching networks splits.

### What is stored

New base-tier migration (runs on H2 and Postgres), `V18__brace_analytics.sql`. Columns are
`view_date`/`view_hour` rather than `day`/`hour`, which are reserved words in H2:

```sql
CREATE TABLE brace_analytics_pageviews (
    ts        TIMESTAMP WITH TIME ZONE NOT NULL,
    view_date DATE         NOT NULL,   -- calendar day in the configured timezone
    view_hour SMALLINT     NOT NULL,   -- hour of that day, for the "today" chart
    visitor   BIGINT       NOT NULL,   -- salted hash, unlinkable once the salt is gone
    path      VARCHAR(512) NOT NULL,   -- decoded, redacted, no query string
    source    VARCHAR(255),            -- referrer host, utm_source or ref; NULL = direct
    device    VARCHAR(16),             -- desktop | mobile | tablet
    browser   VARCHAR(32),
    os        VARCHAR(32),
    country   VARCHAR(2)               -- only when a country header is configured
);
CREATE INDEX idx_brace_analytics_pageviews_day ON brace_analytics_pageviews (view_date);

-- Filter tallies. Each instance adds to its own rows; readers sum across instance_id.
CREATE TABLE brace_analytics_rejects (
    view_date DATE, reason VARCHAR(32), instance_id VARCHAR(128), n BIGINT,
    PRIMARY KEY (view_date, reason, instance_id)
);

CREATE TABLE brace_analytics_salts (
    view_date DATE PRIMARY KEY,
    salt      VARCHAR(64) NOT NULL     -- hex
);
```

Phase 2 adds `brace_analytics_daily (view_date, dim, dim_value, visitors, pageviews)` in a new
migration, filled by a nightly rollup, for ranges longer than the raw window.

Sizing: a raw row is roughly 120–150 bytes with its index share. 50k pageviews/day is about
7 MB/day, so ~250 MB at the default 35-day raw retention. The daily table is a few hundred rows
per day once each dimension is capped (top 500 paths, top 200 sources; the rest fold into
`(other)`), so keeping it forever costs almost nothing.

Phase 1 reads everything from the raw table (Today, 7 days, 30 days, the live count). In phase 2,
longer ranges read `brace_analytics_daily`; the rollup will be idempotent (delete the day's rows,
re-insert), so a missed night just rolls up later.

Two things the raw table gives us that pure counters wouldn't: exact per-page visitor counts, and
phase-2 filtering (click a source, see its pages) with a plain `WHERE`.

### Multi-server

Each instance buffers and flushes its own rows every 10 seconds. Raw inserts don't conflict, so
there is nothing to coordinate except the salt (shared table, insert-if-absent) and the nightly
prune (`jobScheduler.daily(...)` is already cluster-deduped). The live count is a query over the last
5 minutes of raw rows, so it is fleet-wide automatically, lagging by at most one flush interval.

## API

```java
app.analytics();                                   // defaults

app.analytics(Analytics.options()
    .timezone("America/New_York")                 // day boundaries + salt rotation; default UTC
    .exclude("/admin/*", "/account/*")            // exact or trailing /* prefix, never counted
    .excludeIps("203.0.113.0/24")                 // the office
    .countryHeader("CF-IPCountry")                // trusted only from trustedProxies peers
    .rawRetention("35d")
    .strictNavigation(false));

// Per route: opt out, or group by pattern instead of concrete path
app.get("/invite/{token}", ctrl::invite).analytics(false);
app.get("/posts/{slug}",   ctrl::show);                        // default: /posts/hello-world
app.get("/u/{username}",   ctrl::profile).analyticsByRoute();  // counted as /u/{username}
```

Preconditions, checked at `start()`: a database is configured (analytics fails fast without one)
and ops is enabled (otherwise there is no way to view it; fail fast with a message that says so).

## How it fits with ops

Analytics is a sibling of ops, not part of `Stats`. `Stats` is per-process, in-memory, and
operational (latency, errors, heap); analytics is durable, per-day, and about people. They must
not share `ops_timeseries`: different retention, different cardinality, different audience.

What it reuses from ops:

| Ops piece | Used for |
|---|---|
| Ed25519 keys + `/ops/auth` + the `/ops` session cookie | Viewer auth. Read scope is enough |
| `OpsDashboard` look and layout | The analytics page is styled to match |
| `jobScheduler.everyLocal` / `daily` | Flush, rollup, prune, salt rotation |
| `Redactor.redactPath` | Paths are redacted before they are stored |
| CLI auth + output conventions (TTY table / JSON when piped) | `brace analytics` |

New surface:

| Endpoint / command | Returns | Scope |
|---|---|---|
| `GET /ops/analytics` | HTML dashboard | read |
| `GET /ops/analytics/data?range=7d` | JSON: totals, series, top pages, sources, devices, browsers, countries, filtered counts | read |
| `GET /ops/status` | New `analytics` block: today's visitors, pageviews, live | read |
| `brace analytics [--range 7d] [--env prod]` | Summary table, JSON when piped | read |
| `brace ops dashboard --analytics` | Opens `/ops/analytics` via the existing login exchange (`?next=analytics`, an allowlist, not a path) | read |
| `POST /ops/analytics/ignore` | Sets or clears the "don't count me" cookie | read |

On `/ops/dashboard` the change is small: a **Visitors today** stat card in the top row and an
`ops · analytics` switch in the header. The mockup shows both.

### "Don't count me"

The ops cookie is scoped to `/ops`, so the browser never sends it on public pages and the server
can't recognise the owner from it. Instead, the analytics page offers a toggle that sets a
separate `__brace_analytics_ignore=1` cookie on `/` (one year, HttpOnly, SameSite=Lax). Requests
carrying it are not counted. This is the only cookie the feature ever sets, and only in the
browsers of people who have ops access.

## Security

The main point of this design is a smaller attack surface than the Plausible setup:

- **No ingest endpoint.** Plausible's `/api/event` accepts arbitrary JSON from anyone; that's
  where spam and injection live. Here the data comes from requests the app already served, and
  nothing new is exposed.
- **No new login.** The viewer uses the existing Ed25519 ops auth. No passwords, no user table,
  no invite flow, no separate admin app.
- **No new process or database.** Same JVM, same Postgres.
- **Little worth stealing.** No IPs, no user agents, no full referrer URLs, no query strings.
  Paths are redacted; routes that carry secrets in the path (`/reset/{token}`) should use
  `.analytics(false)`. A later improvement could warn at startup when a counted route's pattern
  has a parameter named like `token`/`key`/`secret`.
- **Bounded.** Only matched routes returning HTML count, so a scanner spraying random URLs
  produces 404s, not rows. The flush buffer is bounded (drop and count when full). Rendered
  values are escaped like the ops dashboard.

## Accuracy versus a JS tracker

Numbers will be **higher** than Plausible's for the same site. That is expected:

- Ad blockers no longer hide anyone (often 10–30% of a technical audience).
- Bots that don't run JavaScript are now in scope and have to be filtered by headers. The
  filters above catch the common ones, and the dashboard shows how much was filtered.

Things the server can't see:

- Pages served from a CDN or browser cache without reaching the app. (A 304 revalidation does
  reach it and is counted. Brace's own page cache runs inside the handler, so it is counted.)
- Back/forward-cache restores, which make no request at all.
- Prerendered pages that are never activated look like a view. Rare in practice.

## Phasing

1. **Collect and show (done).** Classifier, hashing, buffer + flush, raw table, `/ops/analytics`
   with Today / 7d / 30d, top pages, sources, devices, browsers, OS, live count, filtered counts,
   countries via header, the "don't count me" toggle, CLI, `/ops/status` block and dashboard card.
2. **History and filters.** Nightly rollup + 12 months, click-to-filter.
3. **Maybe.** Weekly email digest through `Mailer`, CSV export, a read-only public share link.

## Decisions

1. **Concrete paths by default.** Routes opt into pattern grouping with `.analyticsByRoute()` or
   out entirely with `.analytics(false)`.
2. **No public share link.** Viewing stays behind ops auth.

## Open questions

1. **Who looks at it?** If non-engineers need it, Ed25519 keys are awkward. A read-only key
   minted with `brace ops keypair --read-only` plus `brace ops dashboard --analytics` may be
   enough.
2. **Raw retention default:** 35 days covers the 30-day view with margin. Longer means filters
   work further back, at ~7 MB/day per 50k pageviews.
