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

---

<!-- section: merge-restore -->
## Fixes restored from the 0.1.7 performance review

Merging the 0.1.7 runtime-performance review (merge `b8609b6`) silently dropped a few
fixes while resolving conflicts. They are restored here, each with a test that fails if it
is lost again.

### Fix: request headers are no longer copied twice per request

**What changed.** `Request` again adopts the case-insensitive header map Brace builds for
each request instead of copying it into a second identical map. Header lookups behave
exactly as before; a `Request` you construct yourself still gets a defensive copy unless
you pass a `TreeMap` ordered by `String.CASE_INSENSITIVE_ORDER`, which the `Request` then
shares (don't modify it afterwards).

**Who needs to act.** No one.

### Fix: an unfiltered error list is capped at 500 rows

**What changed.** `GET /ops/errors` (and `ErrorStore.list(status)`) without `?since=` returns
at most the 500 most recently seen errors again, newest first, instead of every stored row
with its full stack trace and request detail. A `?since=`-filtered list is not capped. Per-id
detail at `/ops/errors/{id}` is unchanged.

**Who needs to act.** Only tooling that pages through the full unfiltered list expecting more
than 500 rows. Pass `?since=` for a complete window instead.

### Fix: resolving an error returns the same fields as fetching it

**What changed.** The JSON from `POST /ops/errors/{id}/resolve` (and `ErrorStore.resolve`)
includes `queriesBefore` and `requestHeaders` again, so it has exactly the fields of
`GET /ops/errors/{id}`.

**Who needs to act.** No one.

### Fix: regression tracking seeds every error kind since startup

**What changed.** On startup the in-memory regression tracker now reads every error kind first
seen since the process started, not just the 500 most recent, so a known kind that is resolved
and then recurs no longer sends a second regression notification.

**Who needs to act.** No one.

<!-- end section: merge-restore -->
