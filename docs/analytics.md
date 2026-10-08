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
- [History: daily summaries](#history-daily-summaries)
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
    .rawRetention("35d")                // how long individual page views are kept
    .strictNavigation(false));          // see "What counts as a page view"
```

| Option | Default | What it does |
|---|---|---|
| `timezone(zone)` | `UTC` | Day boundaries for "today" and the daily charts, and when the visitor salt rotates. Use the zone your audience (or you) think in. |
| `exclude(patterns...)` | none | Paths never counted. `"/admin"` matches exactly; `"/admin/*"` matches `/admin` and everything under it. |
| `excludeIps(cidrs...)` | none | Client IPs or CIDR ranges never counted. Matched against `req.ip()`, so it needs trusted proxies behind a proxy. |
| `countryHeader(name)` | off | Read a two-letter country code from this request header, e.g. `CF-IPCountry` on Cloudflare. Only believed when the request came through a configured trusted proxy. Adds a Countries panel. |
| `rawRetention(duration)` | `35d` | How long raw page views and filter tallies are kept (minimum `1d`). Reports don't depend on it: every completed day is summarized first and the summaries are kept indefinitely (see [History](#history-daily-summaries)). |
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
| `bot` | The user agent is missing, doesn't start with `Mozilla/`, or names a crawler, script, monitor or link-preview fetcher. Also a request without `Sec-Fetch-Mode` whose user agent claims Chrome 76+, Edge, Firefox 90+ or another browser that always sends it, and with `strictNavigation(true)` any request without it. |
| `dropped` | Not a filter: the in-memory buffer (20,000 views) was full because the database was slow or down. Shown only when non-zero. |

Every browser released since spring 2023 sends `Sec-Fetch-Mode`, and most scripts and crawlers
don't. Scrapers often send a real browser's user agent, so a request that claims Chromium 76+
(Chrome, Edge, Opera, Brave, Samsung Internet, Android WebView) or Firefox 90+ but has no
`Sec-Fetch-Mode` is always rejected as a bot. Those browsers have sent the header on every
navigation since 2019 and 2021. Browsers on iOS (all WebKit) and older Safari aren't held to this,
since they may not send it. `strictNavigation(true)` requires the header from everyone, which
rejects almost all non-browser traffic at the cost of not counting visitors on older browsers
(Safari before 16.4).

Browsers send `Sec-Fetch-*` headers only over HTTPS and to localhost. Brace assumes production is
served over HTTPS (as it does for the session cookie's `Secure` attribute). A site served to
browsers over plain HTTP would see its Chrome, Edge and Firefox visitors counted as bots.

## Viewing it

All of these need an ops token with at least `read` scope (`brace ops keypair --read-only` makes a
key that can view analytics but can't change anything).

**In a browser:** `brace ops dashboard --analytics` signs you in and opens `/ops/analytics`. From the
ops dashboard, use the `ops · analytics` switch in the header or the "Visitors today" card. The page
has Today / 7 days / 30 days / 12 months ranges (12 months is per calendar month, including the current one); click the Visitors or Pageviews tile to switch the chart.

The "Don't count this browser's visits" button at the bottom sets a cookie (`__brace_analytics_ignore`,
one year, `Path=/`) so your own browsing stops showing up. It is the only cookie analytics ever
sets, and only for people who can open the ops page.

**From the terminal:**

```bash
brace analytics                      # last 7 days
brace analytics --range today --env prod
brace analytics --range 30d --json   # the full report as JSON
brace analytics --range 12mo         # the last 12 calendar months
```

Exit code: 0 on success, 1 when the app doesn't have analytics enabled, 2 when it's unreachable.

**Over HTTP:** `GET /ops/analytics/data?range=today|7d|30d|12mo` (default `7d`) returns:

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

`series` is per hour for `today` (`"bucket": "00"` … the current hour), per month for `12mo`
(`"bucket": "2026-10"`), and per day otherwise. In
`sources`, `"key": null` means direct traffic or no referrer. `countries` is empty unless
`countryHeader(...)` is set. `pages` and `sources` list the top 10; the breakdowns the top 8.

`/ops/status` gains `"analytics": { "todayVisitors": …, "todayPageviews": …, "live": … }`, and
`brace status` prints it as one line.

## Per-route options

```java
app.get("/invite/{code}", ctrl::invite).analytics(false);    // never recorded
app.get("/u/{username}", ctrl::profile).analyticsByRoute();  // recorded as /u/{username}
```

By default each page is recorded under its **concrete path**, decoded and without the query string:
`/posts/hello-world`, `/tags/red hat`. That's what you want to see for articles and products.

Two kinds of redaction happen automatically, using the same rules as error records:

- **By parameter name.** A route parameter whose name contains token, secret, password, passwd,
  pwd, apikey, credential, privatekey, accesskey, sessionid, bearer, csrf, cookie or
  authorization (case and `-`/`_` ignored) keeps its placeholder. `/reset/{token}` visited as
  `/reset/abc123` is stored as `/reset/{token}`: the visits are counted and grouped, and the value
  is never written.
- **By value.** A segment that looks like a long random token (16+ characters mixing letters and
  digits, or a JWT) is stored as `[redacted]`, whatever the parameter is called.

Neither catches a short code under an ordinary name, like `/invite/{code}` visited as
`/invite/7KQ2`. For those:

- **`.analytics(false)`** for routes whose URL carries a secret or a personal identifier that the
  rules above miss: invite codes, magic links, unsubscribe links. Renaming the parameter to
  something like `{inviteToken}` works too.
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

## History: daily summaries

Every night at 03:29 (server time, once per fleet), the `analytics-rollup` job summarizes each
completed day, in your analytics timezone, into `brace_analytics_daily`: that day's visitors and
pageviews in total and per page, source, device, browser, OS and country, plus the filter
tallies. Summaries are kept indefinitely; at a few hundred small rows a day that's a few MB a
year. Then raw page views older than `rawRetention` are deleted.

- **Reports read summaries where they exist.** Summarized days come from `brace_analytics_daily`
  and the rest (always today, and any day the job hasn't reached) from raw rows, so numbers are
  the same before and after a day is summarized. Visitors add up across the two because a visitor
  ID never spans days.
- **Nothing is lost if the job misses a night.** Each run summarizes every completed day not yet
  summarized, and raw rows are never deleted for a day that hasn't been summarized.
- **Long-range lists are slightly approximate.** Each day keeps its top 500 pages and top 200
  sources. A page that is never in a single day's top 500 won't appear in the 12-month list,
  which only shows the top 10 anyway. Totals and the chart are exact.
- **What a summary can't answer:** anything needing individual views, such as "today, hour by
  hour" (today is never summarized) or, in a future version, filtering one breakdown by another.
  Those work within `rawRetention`.

## Storage and retention

| Table | Holds | Kept |
|---|---|---|
| `brace_analytics_pageviews` | One row per counted view (~120–150 bytes with index) | `rawRetention`, default 35 days, once the day is summarized; deleted by the nightly `analytics-rollup` job |
| `brace_analytics_rejects` | Filter tallies per day, reason and instance | Same as above |
| `brace_analytics_daily` | Per-day totals and top values per breakdown, plus filter tallies | Indefinitely |
| `brace_analytics_salts` | One random salt per day | Until 5 minutes after its day ends |

Rough sizing: 50,000 page views a day is about 7 MB a day of raw rows, about 250 MB at 35 days,
plus a few MB a year of summaries. The tables are
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
