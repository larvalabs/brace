# Migrating from Brace 0.1.10 → 0.1.11

This release has no breaking changes. It adds opt-in server-side analytics.

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|
| Server-side page-view analytics | new, opt-in | none; add `.analytics()` to use it | [§](#new-server-side-page-view-analytics) |
| Framework migration V18 | schema | none (applied automatically) | [§](#schema-framework-migration-v18) |

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
app.get("/reset/{token}", ctrl::reset).analytics(false);     // don't record URLs that carry a secret
app.get("/u/{username}", ctrl::profile).analyticsByRoute();  // count as /u/{username}
```

Things to check when you turn it on:

- **It needs a database and ops.** `start()` throws with a message naming the missing piece.
- **Behind a reverse proxy, configure `trustedProxies(...)`.** Visitors are told apart by IP and
  user agent; without trusted proxies every visitor has the proxy's IP. The app logs a warning
  when it sees `X-Forwarded-For` from an untrusted peer.
- **Routes with secrets in the URL** (`/reset/{token}`, `/invite/{code}`) should use
  `.analytics(false)`. Paths are redacted with the same rules as error records, which catch
  long random tokens but not every short code.
- **Numbers will be higher than a JavaScript tracker's** for the same site: ad blockers no longer
  hide anyone, and filtering bots by headers is less exact than requiring JavaScript. The page
  shows how many requests each filter rejected.

New CLI and endpoints (all need a `read` ops token): `brace analytics [--range today|7d|30d]`,
`brace ops dashboard --analytics`, `GET /ops/analytics`, `GET /ops/analytics/data`,
`POST /ops/analytics/ignore`, and an `analytics` block in `/ops/status` when enabled.

## Schema: framework migration V18

`V18__brace_analytics.sql` creates `brace_analytics_pageviews`, `brace_analytics_rejects` and
`brace_analytics_salts` on both H2 and Postgres. The tables are created whether or not the app
enables analytics, and stay empty unless it does. Nothing to do.
