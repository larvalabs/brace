# Migrating from Brace 0.1.9 → 0.1.10

<!-- In progress. Each workstream fills in only its own section below. The intro, the
breaking-change summary and the Index table are written at integration time, from the
sections. -->

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|

---

<!-- section: correctness -->
## Correctness fixes

_No entries yet._

<!-- end section: correctness -->

---

<!-- section: streaming-io -->
## Streaming uploads and responses

_No entries yet._

<!-- end section: streaming-io -->

---

<!-- section: proxies -->
## Trusted proxies

_No entries yet._

<!-- end section: proxies -->

---

<!-- section: http-streaming -->
## Streaming in the `Http` client

_No entries yet._

<!-- end section: http-streaming -->

---

<!-- section: ops-dashboard -->
## Ops dashboard

This section has no breaking changes for applications. `/ops/status` only gains fields,
and the dashboard is framework-rendered. One thing to know if you consume `Stats`
directly: see "`Stats.MinuteSnapshot` gained components" below.

### Per-route stats are keyed by route pattern again

The 0.1.7 fix that recorded matched requests under their route pattern
(`GET /users/{id}`) instead of the concrete path (`GET /users/42`) had been lost in a
merge, so `http.slowestRoutes` again listed one entry per distinct URL and the per-route
map grew with traffic. It is restored. If you had tooling matching concrete paths in
`http.slowestRoutes`, match the pattern instead:

```text
before: { "route": "GET /users/42", ... }, { "route": "GET /users/43", ... }
after:  { "route": "GET /users/{id}", "count": 2, ... }
```

Requests that matched no route still use their redacted raw path.

### New `/ops/status` fields (additive)

```json
"http": {
  "statusCodes": { "200": 1523, "404": 12 },
  "totalRequests": 1535,
  "requestsPerMinute": { "lastMinute": 42, "avg": 25.6, "windowMinutes": 60 },
  "slowestRoutes": [ ... ],
  "topRoutes": [{ "route": "GET /posts/{id}", "count": 610, "perMinute": 122.0, "sharePct": 58.3 }],
  "topRoutesWindowMinutes": 5
}
```

- `totalRequests` is the lifetime total, the sum of `statusCodes`.
- `requestsPerMinute` is absent until the first minute has rotated in.
- `topRoutes` ranks routes by request count over the last 5 full minutes. Requests that
  matched no route fold into a single `"(unmatched)"` entry.
- `?include=timeseries` minutes gain `p95Ms` next to `avgMs`.

No action is required. `brace status` and `brace check` read only `statusCodes` and
`slowestRoutes`.

### Dashboard changes

- The **Requests** card is now **Req / Min**: the last full minute, with the average over
  the retained minutes as the subtitle. The lifetime total moved to
  `http.totalRequests` in `/ops/status`.
- A **Top Routes** table (req/min and share over the last 5 minutes) sits next to
  **Slowest Routes**, with or without JFR. With JFR, **Hot Methods** now pairs with
  **Top Allocations**, and **Recent GC Pauses** gets its own full-width row.
- A **Latency ms** sparkline shows per-minute average and p95.
- Long method and class names stay on one line, truncated from the left (package first)
  with a leading `…`. Hover for the full name. Value columns no longer wrap.

### `Stats.MinuteSnapshot` gained components

`MinuteSnapshot` is a record and gained two trailing components: `routeCounts`
(`Map<String, Long>`, the per-route counts for that minute) and `p95LatencyUs` (with a
`p95LatencyMs()` accessor). Code that reads snapshots from `stats.minuteSnapshots()`
needs no change. Code that *constructs* a `MinuteSnapshot` itself, which is unusual
outside tests, must pass the two new arguments:

```java
// before
new Stats.MinuteSnapshot(ts, requests, errors, latencyUs, maxUs, queries, queryUs, heapMB,
    counters, gauges, timers);
// after
new Stats.MinuteSnapshot(ts, requests, errors, latencyUs, maxUs, queries, queryUs, heapMB,
    counters, gauges, timers, Map.of(), 0L);
```

New read-only helpers on `Stats`: `requestRate()` (null before the first minute) and
`topRoutes(windowMinutes, limit)`.

<!-- end section: ops-dashboard -->

---

<!-- section: ops-jvm-cli -->
## GC pause figures and CLI

_No entries yet._

<!-- end section: ops-jvm-cli -->

---

<!-- section: dx -->
## New projects and custom metrics

_No entries yet._

<!-- end section: dx -->
