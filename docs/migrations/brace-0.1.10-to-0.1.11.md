# Migrating from Brace 0.1.10 → 0.1.11

This release has no breaking changes. It adds opt-in server-side analytics, and `HEAD` requests
now reach `GET` routes instead of returning 404.

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|
| Server-side page-view analytics | new, opt-in | none; add `.analytics()` to use it | [§](#new-server-side-page-view-analytics) |
| Framework migration V18 | schema | none (applied automatically) | [§](#schema-framework-migration-v18) |
| `HEAD` requests reach `GET` routes | fix | ops tooling: expect `HEAD <pattern>` rows | [§](#fix-head-requests-reach-get-routes) |

## New: server-side page-view analytics

Visitors, pageviews, top pages, sources, devices and browsers, counted from the requests the app
already serves. There is no tracking script and no cookie, and no IP address or user agent is
stored. Viewed at `/ops/analytics`, with `brace analytics`, and as a "Visitors today" card on the
ops dashboard. Off unless you turn it on.

Before:

```java
var app = Brace.app()
    .database(db)
    .ops("ops-authorized-keys");
```

After:

```java
var app = Brace.app()
    .database(db)
    .ops("ops-authorized-keys")
    .analytics(Analytics.options().timezone("America/New_York"));

// Optional, per route:
app.get("/invite/{code}", ctrl::invite).analytics(false);    // don't record URLs that carry a secret
app.get("/u/{username}", ctrl::profile).analyticsByRoute();  // count as /u/{username}
```

Things to check when you turn it on:

- **It needs a database and ops.** `start()` throws with a message naming the missing piece.
- **Behind a reverse proxy, configure `trustedProxies(...)`.** Visitors are told apart by IP and
  user agent; without trusted proxies every visitor has the proxy's IP. The app logs a warning
  when it sees `X-Forwarded-For` from an untrusted peer.
- **Routes with secrets in the URL.** Parameters named like a secret (`{token}`, `{apiKey}`, ...)
  are stored as their placeholder, and long random-looking segments are redacted, using the same
  rules as error records. A short code under an ordinary name (`/invite/{code}`) is not caught:
  mark those routes `.analytics(false)`.
- **Numbers will be higher than a JavaScript tracker's** for the same site: ad blockers no longer
  hide anyone, and filtering bots by headers is less exact than requiring JavaScript. The page
  shows how many requests each filter rejected.
- **Comparisons appear after a while.** "vs the previous 30 days" needs 60 days of data, so for
  the first weeks the page says "no comparison yet".
- **History is kept as daily summaries.** A nightly job (`analytics-rollup`) summarizes each
  completed day into `brace_analytics_daily`, kept indefinitely, and then deletes raw page views
  older than `rawRetention` (35 days by default). The page and CLI have a 12-month range.

The full guide, with what each number means, is `docs/analytics.md` in the brace repo.

New CLI and endpoints (all need a `read` ops token): `brace analytics [--range today|7d|30d|12mo]`,
`brace ops dashboard --analytics`, `GET /ops/analytics`, `GET /ops/analytics/data`,
`POST /ops/analytics/ignore`, and an `analytics` block in `/ops/status` when enabled.

## Schema: framework migration V18

`V18__brace_analytics.sql` creates `brace_analytics_pageviews`, `brace_analytics_rejects`,
`brace_analytics_daily` and `brace_analytics_salts` on both H2 and Postgres. The tables are created whether or not the app
enables analytics, and stay empty unless it does. Nothing to do.

## Fix: `HEAD` requests reach `GET` routes

Before, a `HEAD` request returned 404 for every route registered with `app.get(...)`, because
routes were matched on the exact method. Only `staticFiles` mappings answered it. Uptime
monitors, link checkers and some link-preview fetchers send `HEAD`, so they saw a site that was
up as down.

```
$ curl -sI -o /dev/null -w '%{http_code}\n' https://example.com/
404   # 0.1.10
200   # 0.1.11
```

Now a `HEAD` request with no `HEAD` route of its own runs the matching `GET` route and gets the
`GET`'s status and headers, `Content-Length` and `ETag` included, with no body (RFC 9110
§9.3.2). Details:

- The handler runs as it does for `GET`. `req.method()` returns `"HEAD"`, so a handler that
  wants to skip expensive work for `HEAD` can check it.
- Streamed bodies (`Result.file`, `Result.stream`, `Result.sse`) are not produced for `HEAD`. An
  SSE producer never starts, and a file is not read.
- A route wrapped in `cache.wrap(...)` serves `HEAD` from the same cache entry as `GET`.
- `/ops/routes` and the request log record these requests as `HEAD`, under their own
  `HEAD <pattern>` row. Expect new rows if a monitor polls your site.
- Analytics does not count them: only `GET` requests are page-view candidates, so a monitor's
  `HEAD` checks never show up as visitors.

No code changes are needed.
