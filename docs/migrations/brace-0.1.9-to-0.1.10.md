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

Fixes from the 2026-07 correctness review
([record](../reviews/2026-07-correctness-opus-5.md),
[findings](../2026-07-24-correctness-review-todos.md)).

### Fix: `/ops/routes` shows route patterns, and every response is counted

**Type:** fix (changes ops output; no application code changes).

**What changed.** Per-route stats in `/ops/routes` and `/ops/status` are keyed by route
pattern, not the request URL. Previously `GET /users/1` and `GET /users/2` were separate rows,
so the table grew by one entry per URL ever requested, for the life of the process, and
per-route latency figures were meaningless because almost every row had a count of 1. They now
aggregate under `GET /users/{id}`:

```
# Before (0.1.9)                    # After (0.1.10)
GET /users/1     count=1            GET /users/{id}    count=48210
GET /users/2     count=1            GET /posts/{slug}  count=9930
GET /users/3     count=1            GET (unmatched)    count=412
...one row per id, forever...       GET (static)       count=88301
```

Requests with no route land in two constant buckets: `(unmatched)` for 404s and `(static)`
for files served from a `staticFiles` mapping. They are constants on purpose: the URL there is
client-supplied, so a row per URL would be unbounded in whatever a client sends.

Every response is now recorded. Before, only the handler path and the thrown-404/500 paths
reached the stats and the request log, so these were invisible: rate-limiter 429s and other
before-middleware short-circuits, auth-guard redirects, CSRF 403s, 413s, static-file serves and
unmatched-route 404s. Static-file requests now also appear in the request log; raise
`BRACE_LOG_LEVEL` (or serve assets from a CDN or proxy) if that is too noisy. A 500 still
produces exactly one log line (`http.error`).

**Who needs to act.** Only tooling that parses `/ops/status` or `/ops/routes`. Expect route
patterns where you saw concrete URLs, the two literal keys `(unmatched)` and `(static)`, and
higher request counts and status totals: that is traffic that was previously dropped, not new
traffic. To find which concrete URLs 404, use `/ops/logs`; the request log still records the
concrete (redacted) path.

### Fix: static files with percent-encoded names are served

**Type:** fix.

**What changed.** 0.1.9 started decoding path parameters; static files were still looked up
by the raw, encoded path, so `/assets/my%20file.css` searched for a file literally named
`my%20file.css` and 404'd. The relative path is now percent-decoded per segment before the
traversal checks and the file lookup, with the same path decoding as path parameters (`+` is a
literal plus). A `?v=` fingerprint from `Assets` now also matches for such files, so they get
the immutable cache headers instead of revalidate-always.

**Who needs to act.** Nobody. If you renamed asset files to avoid spaces or other encoded
characters, you no longer need to.

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

_No entries yet._

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
