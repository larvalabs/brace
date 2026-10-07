# Analytics

Brace can count the visitors and page views your site gets, on the server, from the requests it
already handles. Nothing is added to your pages: no script for an ad blocker to block, no
third-party request, no cookie, no consent banner. No IP address or user agent is stored.

It answers "how busy is the site, and roughly who is visiting": visitors, pageviews, top pages,
where visitors came from, and device, browser, OS and (optionally) country mix. It does not do
goals, events, funnels, user flows or cross-day visitor identity. For counting business events
inside the app, use [`Metrics.counter(...)`](../BRACE-AGENTS.md#custom-metrics).

Design rationale and what's planned next: [`2026-10-07-brace-analytics.md`](2026-10-07-brace-analytics.md).

## Contents

- [Turning it on](#turning-it-on)
- [What the numbers mean](#what-the-numbers-mean)
- [What counts as a page view](#what-counts-as-a-page-view)
- [Viewing it](#viewing-it)
- [Per-route options](#per-route-options)
- [Privacy: what is and isn't stored](#privacy-what-is-and-isnt-stored)
- [Running more than one instance](#running-more-than-one-instance)
- [Accuracy compared with a JavaScript tracker](#accuracy-compared-with-a-javascript-tracker)
- [Storage and retention](#storage-and-retention)
- [Troubleshooting](#troubleshooting)

## Turning it on

```java
var app = Brace.app()
    .database(db)                       // required: page views are stored in your database
    .ops("ops-authorized-keys")         // required: the numbers are viewed through ops
    .trustedProxies("127.0.0.1")        // required behind a reverse proxy, see below
    .analytics();
```

`start()` throws if analytics is on without a database or without ops, naming what's missing.

With options:

```java
app.analytics(Analytics.options()
    .timezone("America/New_York")       // where "today" starts and ends; default UTC
    .exclude("/admin/*", "/account")    // exact path, or a trailing /* prefix
    .excludeIps("203.0.113.0/24")       // your office, an uptime monitor
    .countryHeader("CF-IPCountry")      // two-letter country from your proxy (see below)
    .rawRetention("60d")                // how long individual page views are kept
    .strictNavigation(false));          // see "What counts as a page view"
```

| Option | Default | What it does |
|---|---|---|
| `timezone(zone)` | `UTC` | Day boundaries for "today" and the daily charts, and when the visitor salt rotates. Use the zone your audience (or you) think in. |
| `exclude(patterns...)` | none | Paths never counted. `"/admin"` matches exactly; `"/admin/*"` matches `/admin` and everything under it. |
| `excludeIps(cidrs...)` | none | Client IPs or CIDR ranges never counted. Matched against `req.ip()`, so it needs trusted proxies behind a proxy. |
| `countryHeader(name)` | off | Read a two-letter country code from this request header, e.g. `CF-IPCountry` on Cloudflare. Only believed when the request came through a configured trusted proxy. Adds a Countries panel. |
| `rawRetention(duration)` | `60d` | How long raw page views and filter tallies are kept (minimum `1d`). The 30-day view compares against the 30 days before it, so below `60d` that comparison is dropped. |
| `strictNavigation(bool)` | `false` | Require the `Sec-Fetch-Mode: navigate` header on every counted view. See below. |

**Behind a reverse proxy or CDN, configure `trustedProxies(...)`.** Visitors are told apart by IP
address and browser. Without trusted proxies, every request appears to come from the proxy's IP, so
the site shows about one visitor per browser type per day. Brace logs a warning when it sees
`X-Forwarded-For` from a peer that isn't trusted. Behind Cloudflare use
`TrustedProxies.cloudflare()`; see [Trusted Proxies](SECURITY.md#trusted-proxies).

## What the numbers mean

| Number | Definition |
|---|---|
| **Pageviews** | Counted page views (see the next section) in the range. |
| **Visitors** | Distinct visitors in the range. A visitor is identified by a hash of a random value that changes every day, your host name, the client IP and the user agent. The same person is a new visitor each day, so "visitors, last 30 days" is the sum of the 30 daily counts, not the number of distinct people. Plausible counts the same way. |
| **Views per visit** | Pageviews ÷ visitors. |
| **Visitors now** | Distinct visitors with at least one counted page view in the **last 5 minutes**. It counts people who *loaded a page* recently, not people with a tab open: someone reading one long article for 6 minutes drops out until they click again. Views reach the database up to 10 seconds after they happen, and the ops dashboard card and `/ops/status` reuse a figure for up to 15 seconds, so treat it as "the last 5 minutes, give or take 20 seconds". It covers every instance in a fleet. |
| **Visitors today** | Visitors since midnight in the configured timezone (ops dashboard card, `/ops/status`). |
| **Comparison** (`↑ 23% vs the previous 30 days`) | The same metric over the period immediately before: the previous 7 or 30 days, or yesterday up to the current hour for "Today". Shown only when stored page views reach back to the start of that period; otherwise the page says "no comparison yet" and the JSON has `null`, because a partial period would read as a large rise. Expect that for the first weeks after enabling analytics. |
| **Not counted** | Requests that looked like page views but were rejected by a filter, per reason. These are not in any of the numbers above. |

Two consequences of the daily visitor hash worth knowing:

- People behind one shared IP (an office NAT, a mobile carrier) using the same browser version
  count as one visitor. One person switching from Wi-Fi to mobile data counts as two.
- At midnight everyone gets a new ID, so for the 5 minutes after midnight someone browsing across
  it can be counted twice in "visitors now".

## What counts as a page view

A request is a **candidate** when all of these hold. Anything else is ignored silently:

- It is a `GET` to one of your app's routes (not a static file, not a 404, not `/ops/*` or
  `/__brace/*`).
- The route hasn't opted out with `.analytics(false)`.
- The response is 2xx with `Content-Type: text/html`, or a 304 to a request whose `Accept` asks for
  HTML (a browser revalidating a cached page). JSON, feeds, files, redirects and errors don't count.

A candidate is then **counted** unless a filter rejects it. Rejections are tallied by reason and
shown under "Not counted":

| Reason | Rejected when |
|---|---|
| `excluded` | The path matches `exclude(...)`, the IP matches `excludeIps(...)`, or the browser has the "don't count" cookie. |
| `prefetch` | `Sec-Purpose` or `Purpose` says `prefetch`: the browser is fetching ahead of a click that may never happen. |
| `htmx` | `HX-Request: true` without `HX-Boosted` or `HX-History-Restore-Request`: a partial swap, not a page load. Boosted link clicks and history restores **are** counted. |
| `background` | `Sec-Fetch-Mode` is present and isn't `navigate`: a `fetch()`/XHR, an iframe or similar. (Boosted htmx requests are exempt.) |
| `bot` | The user agent is missing, doesn't start with `Mozilla/`, or names a crawler, script, monitor or link-preview fetcher. With `strictNavigation(true)`, also any request without `Sec-Fetch-Mode`. |
| `dropped` | Not a filter: the in-memory buffer (20,000 views) was full because the database was slow or down. Shown only when non-zero. |

Every browser released since spring 2023 sends `Sec-Fetch-Mode`, and most scripts and crawlers
don't. `strictNavigation(true)` uses that to reject almost all non-browser traffic, at the cost of
not counting visitors on older browsers (Safari before 16.4).

## Viewing it

All of these need an ops token with at least `read` scope (`brace ops keypair --read-only` makes a
key that can view analytics but can't change anything).

**In a browser:** `brace ops dashboard --analytics` signs you in and opens `/ops/analytics`. From the
ops dashboard, use the `ops · analytics` switch in the header or the "Visitors today" card. The page
has Today / 7 days / 30 days ranges; click the Visitors or Pageviews tile to switch the chart.

The "Don't count this browser's visits" button at the bottom sets a cookie (`__brace_analytics_ignore`,
one year, `Path=/`) so your own browsing stops showing up. It is the only cookie analytics ever
sets, and only for people who can open the ops page.

**From the terminal:**

```bash
brace analytics                      # last 7 days
brace analytics --range today --env prod
brace analytics --range 30d --json   # the full report as JSON
```

Exit code: 0 on success, 1 when the app doesn't have analytics enabled, 2 when it's unreachable.

**Over HTTP:** `GET /ops/analytics/data?range=today|7d|30d` (default `7d`) returns:

```json
{
  "range": "7d", "timezone": "America/New_York", "from": "2026-10-01", "to": "2026-10-07",
  "visitors": 7792, "pageviews": 13877, "previousVisitors": 6310, "previousPageviews": 11504,
  "live": 7,
  "series": [{ "bucket": "2026-10-01", "visitors": 1104, "pageviews": 1960 }],
  "pages":     [{ "key": "/", "visitors": 4831, "pageviews": 5314 }],
  "sources":   [{ "key": null, "visitors": 3195, "pageviews": 5112 }, { "key": "google.com", "visitors": 1853, "pageviews": 2960 }],
  "devices":   [{ "key": "desktop", "visitors": 4750, "pageviews": 8811 }],
  "browsers":  [{ "key": "Chrome", "visitors": 4050, "pageviews": 7300 }],
  "os":        [{ "key": "macOS", "visitors": 2600, "pageviews": 4900 }],
  "countries": [],
  "notCounted": { "bot": 8710, "htmx": 3120, "background": 402, "prefetch": 590, "excluded": 61, "dropped": 0 }
}
```

`series` is per hour for `today` (`"bucket": "00"` … the current hour) and per day otherwise. In
`sources`, `"key": null` means direct traffic or no referrer. `countries` is empty unless
`countryHeader(...)` is set. `pages` and `sources` list the top 10; the breakdowns the top 8.

`/ops/status` gains `"analytics": { "todayVisitors": …, "todayPageviews": …, "live": … }`, and
`brace status` prints it as one line.

## Per-route options

```java
app.get("/reset/{token}", ctrl::reset).analytics(false);     // never recorded
app.get("/u/{username}", ctrl::profile).analyticsByRoute();  // recorded as /u/{username}
```

By default each page is recorded under its **concrete path**, decoded and without the query string:
`/posts/hello-world`, `/tags/red hat`. That's what you want to see for articles and products.

- **`.analytics(false)`** for routes whose URL carries a secret or a personal identifier:
  password resets, invites, magic links, unsubscribe links. Path segments that look like long
  random tokens are redacted to `[redacted]` automatically (the same rule as error records), but
  short codes and numeric IDs are not.
- **`.analyticsByRoute()`** for routes with many URLs that are only interesting together, like
  user profiles or search pages. All their views are recorded under the route pattern.

Both exist on route groups too.

## Privacy: what is and isn't stored

For each counted page view, one row is stored: the time, the day and hour in your timezone, the
visitor hash, the path, the source, the device class, browser family, OS family and, if
configured, the country code.

What is **not** stored anywhere: IP addresses, user agents, full referrer URLs (only the referring
host), query strings, cookies, or anything from the request body. The IP and user agent are held in
memory for at most about 10 seconds, until the next write hashes them.

The visitor hash uses a random 32-byte value generated for each day and kept in
`brace_analytics_salts`. It is deleted 5 minutes after the day ends. After that, nobody can work
out which visitor hash belongs to which IP, even with the database and a list of candidate IPs.
During the current day, someone with database access *and* a candidate IP and user agent could
check whether they match a stored hash.

Analytics adds no public endpoint: data comes only from requests your app already served, and the
viewing endpoints sit behind ops authentication. See [SECURITY.md](SECURITY.md#analytics).

## Running more than one instance

It works across a fleet on Postgres with no extra setup. Each instance buffers its own views and
writes them every 10 seconds; all instances share the day's salt, so one browser hitting two
instances is still one visitor. Filter tallies are kept per instance and summed when read. The
nightly cleanup runs once per fleet. "Visitors now" and every other number reflect the whole fleet,
not just the instance serving the page.

## Accuracy compared with a JavaScript tracker

Expect higher numbers than Google Analytics or Plausible show for the same site:

- **Ad blockers and privacy tools no longer hide anyone.** On sites with a technical audience that is
  often 10–30% of visitors.
- **Bots are filtered by headers, not by "did it run JavaScript".** The common crawlers and scripts
  are caught; a headless browser that copies a real browser's headers is not. The "Not counted"
  panel shows how much was filtered, which is a good sense check.

Some page views never reach the server, so they aren't counted:

- Pages served entirely by a CDN or the browser cache. A conditional revalidation (304) does reach
  the app and is counted. Brace's own page cache runs inside the app, so its hits are counted.
- Back/forward navigations restored from the browser's back-forward cache.

## Storage and retention

| Table | Holds | Kept |
|---|---|---|
| `brace_analytics_pageviews` | One row per counted view (~120–150 bytes with index) | `rawRetention`, default 60 days; deleted by the daily `analytics-prune` job |
| `brace_analytics_rejects` | Filter tallies per day, reason and instance | Same as above |
| `brace_analytics_salts` | One random salt per day | Until 5 minutes after its day ends |

Rough sizing: 50,000 page views a day is about 7 MB a day, about 420 MB at 60 days. The tables are
created by framework migration V18 whether or not analytics is enabled, and stay empty unless it is.

## Troubleshooting

**Visitors is about 1 per day, or far lower than pageviews suggest.** The app is behind a proxy
that isn't in `trustedProxies(...)`, so every visitor has the proxy's IP. Look for the warning in
the log ("Analytics sees X-Forwarded-For from a peer that is not a trusted proxy").

**Nothing is counted.** Check "Not counted": if `bot` or `background` is high, your test client
isn't a real browser. `curl` is rejected as a bot by design. Check that your pages return
`Content-Type: text/html` with a 2xx status, and that the routes aren't excluded.

**Your own visits show up.** Click "Don't count this browser's visits" on `/ops/analytics` in each
browser you use, or add your office to `excludeIps(...)`.

**`dropped` is non-zero.** The database was slow or unreachable long enough for 20,000 views to
pile up in memory, and newer ones were dropped instead of growing the heap. The log has
`analytics flush failed` warnings with the cause.

**`brace analytics` says it isn't enabled.** The app hasn't called `.analytics()`, or the running
build predates it.
