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

Four of these are **breaking**, each only for code that worked around the old bug or shared a
`DatabaseFactory`: single-column `sqlQuery`/`hql` rows, `stop()` closing the database factory,
`View.of` rejecting an odd argument count, and stricter `trustedProxies` CIDR validation.

### Breaking: single-column `db.sqlQuery` / `db.hql` return real rows

**Type:** breaking (only for code that worked around the old shape).

**What changed.** Both methods declare `List<Object[]>`, but for a select with one item they
returned bare scalars, so `for (Object[] row : db.sqlQuery("SELECT name FROM users"))` threw
`ClassCastException` inside your loop. Every element is now an `Object[]`, whatever the column
count; read `row[0]`.

**Who needs to act.** Code that cast the result to `List<Object>` (or `List<String>`) to get at
the scalars. That code now sees `Object[]` elements, so `toString()` yields
`[Ljava.lang.Object;@...` and a `String` cast throws. Grep for `sqlQuery(` and `hql(` calls with
a single selected column.

**Before (0.1.9), the workaround:**

```java
@SuppressWarnings("unchecked")
var names = (List<Object>) (List<?>) db.sqlQuery("SELECT name FROM users");
for (var n : names) use(n.toString());
```

**After (0.1.10):**

```java
for (var row : db.sqlQuery("SELECT name FROM users")) use(String.valueOf(row[0]));
```

Multi-column selects are unchanged. `db.sqlQueryLong(...)` already handled both shapes.

### Breaking: `app.stop()` closes the `DatabaseFactory`

**Type:** breaking (only when a factory is shared), plus a new optional builder method.

**What changed.** `stop()` never closed the factory passed to `.database(...)`, so every stopped
app left its whole connection pool (and a Hibernate `SessionFactory`) open until the process
exited. It now closes it, along with the rate limiter's shared state that pointed at it. The
new `.ownsDatabase(false)` opts out, for a factory that outlives the app.

**Who needs to act.** Code that hands one `DatabaseFactory` to several `Brace` apps, or keeps
using it after `stop()`: typically a test fixture that builds the factory once per class and
starts an app per test. Without the opt-out, the next use fails with
`IllegalStateException: EntityManagerFactory is closed`.

**Before (0.1.9):**

```java
static DatabaseFactory factory = new DatabaseFactory(url, user, pass, entities);

var app = Brace.app().database(factory);
app.start();
// ...
app.stop();            // factory still open
factory.openSession(); // fine
```

**After (0.1.10):**

```java
var app = Brace.app().database(factory).ownsDatabase(false); // you close the factory
app.start();
// ...
app.stop();
factory.openSession(); // still fine
// ...and when you're done with it:
factory.close();
```

`Brace.test()` apps that let Brace build the database need no change.

### Breaking: `View.of` / `View.render` reject an odd number of arguments

**Type:** breaking (only for calls that were already dropping a variable).

**What changed.** The key/value varargs loop stopped one short, so a trailing key with no value
was silently discarded and the template rendered without it. An odd count now throws
`IllegalArgumentException` naming the dangling key, as `Session.of` always has.

**Who needs to act.** Only code with an unpaired argument, which was rendering a page with a
blank where that variable should be. The error surfaces as a 500 when the page renders, so load
each page or run tests that render them.

**Before (0.1.9), silently rendered without `user`:**

```java
return View.of("posts/index", "posts", posts, "user");
```

**After (0.1.10), throws; pass the value:**

```java
return View.of("posts/index", "posts", posts, "user", user);
```

### Breaking: out-of-range CIDR prefixes in `trustedProxies` throw

**Type:** breaking (only for configurations that were already wrong).

**What changed.** A negative prefix (`10.0.0.0/-1`) produced an all-zero mask, which trusts
**every** address's forwarding headers, the opposite of what the setting is for. An over-wide
prefix (`/33` for IPv4, `/129` for IPv6) was silently clamped. Both now throw
`IllegalArgumentException` at startup, naming the valid range. `/0` still means every address.

**Who needs to act.** If startup fails with `Invalid CIDR` / `Prefix length must be 0..32`, fix
the entry.

**Before (0.1.9), trusted every client:**

```java
app.trustedProxies("10.0.0.0/-1");
```

**After (0.1.10), throws at startup; write the range you meant:**

```java
app.trustedProxies("10.0.0.0/8");
```

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

### Fix: HTML checkboxes bind to `boolean` form fields

**Type:** fix.

**What changed.** `boolean` (and `Boolean`) form components used `Boolean.parseBoolean`, which
is true only for the string `"true"`. A checked HTML checkbox submits `name=on`, so it bound
`false`. Now `on`, `true`, `1`, `yes` and `checked` (case-insensitive) bind `true`; anything else,
and an absent field, binds `false`.

**Who needs to act.** Nobody has to. If you declared the component as `String` and compared it
to `"on"` yourself, that keeps working, and you can switch to `boolean`:

```java
record Signup(String email, String agree) {}   // 0.1.9 workaround: "on".equals(form.value().agree())
record Signup(String email, boolean agree) {}  // 0.1.10
```

### Fix: repeated multipart fields keep every value

**Type:** fix.

**What changed.** For a `multipart/form-data` submission, `req.formParams("tag")` returned only
the last value of a repeated field (a checkbox group, a `<select multiple>`). It now returns all
of them, as it already did for `application/x-www-form-urlencoded`. `req.formParam("tag")` is
unchanged (last value wins).

**Who needs to act.** Nobody.

### Fix: a trailing slash reaches the route

**Type:** fix.

**What changed.** `GET /users/` used to 404 when the route was `/users`. It now matches the
`/users` route, for every method; there is no redirect, because a 301 would turn a `POST` into a
`GET` and drop its body. The request is treated as `/users` from routing on: before/after
middleware patterns, `req.path()` and the handler all see the canonical path, so a guard on
exactly `/admin` also covers `/admin/`. `/` is unaffected and an unknown path still 404s.

**Who needs to act.** Only code that relied on the 404 to reject trailing slashes.

### Fix: the htmx `Vary` header is appended, not overwritten

**Type:** fix.

**What changed.** On htmx requests Brace set `Vary: HX-Request`, replacing any `Vary` the
handler had set (`Accept-Encoding`, `Accept-Language`), so a shared cache varied on the wrong
dimension. `HX-Request` is now appended to the handler's value (once, and not at all when the
value is `*`). An after-middleware that sets `Vary` still replaces the whole value, so append
there too.

**Who needs to act.** Nobody, unless you appended `HX-Request` to `Vary` yourself in the handler;
that still works and is no longer necessary.

### Fix: `daily(...)` jobs keep their wall-clock time across DST

**Type:** fix.

**What changed.** `daily("03:00", ...)` scheduled a fixed 24-hour period, so after a daylight
saving change it ran an hour off until restart, and a run near midnight UTC could be deduplicated
away and skipped for a day. It now reschedules from the wall clock after each run, and cluster
deduplication uses the local calendar day. This also applies to the framework's own nightly
prune jobs.

**Who needs to act.** Run every instance in the same time zone (UTC is the usual choice). A
local firing time already assumed that.

### Fix: SMTP credentials in `smtpUrl` are percent-decoded

**Type:** fix.

**What changed.** A username or password embedded in the SMTP URL is now percent-decoded, as
`DatabaseFactory` already does for database URLs. A password containing `@`, `/` or `:` has to
be encoded to parse at all, and used to be sent with the literal `%40` and fail to authenticate.

**Who needs to act.** Only if your SMTP user name or password contains a literal `%` or `+`
that you did not encode: write them as `%25` and `%2B` (decoding follows `DatabaseFactory`, where
`+` means a space). `smtp://user:p%40ss@host:587` now authenticates with `p@ss`.

### New-optional: day intervals for jobs and timeouts

**Type:** new-optional.

**What changed.** Interval strings accept `d`, matching cache TTLs: `every("1d", ...)`,
`jobTimeout("2d")`, `jobShutdownTimeout(...)` and `jobPollInterval(...)` used to throw
`Unknown time unit: d` while `cache.set(k, v, "1d")` accepted the same string.

**Who needs to act.** Nobody.

### New-optional: `CacheBackend.getOrCompute`

**Type:** new-optional (for custom `CacheBackend` implementations).

**What changed.** `cache.getOrSet(...)` cast the backend to the built-in in-memory class, so a
custom live-object `CacheBackend` threw `ClassCastException` on it. `getOrSet` now goes through a
new SPI method, `CacheBackend.getOrCompute`, whose default is a plain get, compute, set. The
built-in backend overrides it to keep its per-key single-flight.

**Who needs to act.** Nobody. A custom backend can override `getOrCompute` if it can do better
than the default.

### Fix: smaller fixes, no action needed

- **Unchanged session writes no longer re-issue the cookie.** `session.set(k, v)` with the value
  it already had, `remove` of an absent key and `clear` of an empty session no longer mark the
  session modified, so they no longer cost a re-encrypted `Set-Cookie` and `Cache-Control:
  private` on every response.
- **One failing WebSocket member no longer aborts a broadcast** to the rest of the room, and a
  send that throws no longer leaks the slow-consumer byte budget.
- **S3 keys containing `*` or `~` are signed correctly** (SigV4 encoding instead of form
  encoding), instead of failing with `SignatureDoesNotMatch`.
- **`Log.error(message, throwable)` redacts the exception message**, like the request error path
  already did.
- **HQL `?` numbering survives `LIKE'...'` with no space before the quote**, where a trailing `E`
  of the keyword was mistaken for a Postgres `E'...'` string.
- **`Http.fetch()` and friends document their status handling:** they return non-2xx responses
  rather than throwing (`fetchBytes()` throws), and redirects are not followed. No behavior
  change.

<!-- end section: correctness -->

---

<!-- section: streaming-io -->
## Streaming uploads and responses

Request and response bodies no longer have to live in the heap. Large multipart parts spill to
disk, `Storage.put` streams, and handlers can stream a response back. One change is breaking: an
`UploadedFile` is only readable while its request is in flight.

### Breaking: an `UploadedFile` is only valid during its request

**What changed.** Uploaded parts are now released when the request ends: spilled parts (below) are
deleted from disk, and in-memory parts are freed. An `UploadedFile` read after that throws
`IllegalStateException` with a message saying the content was released when the request finished.

**Who must act.** Only code that keeps an `UploadedFile` past the handler's return: handing it to
a background thread, a `Jobs` payload built later, or a field read on a later request. Reading it
inside the handler, including `saveTo`, `transferTo` and `storage.put(key, file)`, is unaffected.

**Before (0.1.9), worked because the bytes were on the heap:**

```java
app.post("/import", req -> {
    var file = req.file("csv");
    Thread.startVirtualThread(() -> importer.run(file.bytes()));  // reads after return
    return Result.redirect("/imports");
});
```

**After (0.1.10), copy what the background work needs before returning:**

```java
app.post("/import", req -> {
    var file = req.file("csv");
    byte[] data = file.bytes();            // small file: read now
    // or: file.saveTo(path) and hand the background work the path
    Thread.startVirtualThread(() -> importer.run(data));
    return Result.redirect("/imports");
});
```

### New-optional: large uploads spill to disk

**What changed.** Multipart parts over `uploadMemoryThreshold` (default **1MB**) are written to a
temp file instead of being held in the heap for the whole request. Smaller parts stay in memory.
`UploadedFile.bytes()` still works (it reads the file back), so handlers need no change. New
bounded-memory accessors:

```java
try (var in = file.stream()) { ... }   // repeatable: each call starts at byte 0
file.transferTo(outputStream);
file.saveTo(path);                     // a filesystem move for a spilled part
storage.put(key, file);                // streams (see below)
```

`bytes()` is not deprecated; it is the one method that costs the whole upload in heap, so avoid it
for large files.

**Who must act.** Deployments that accept large uploads. The spill directory needs room for
concurrent uploads × `maxUploadSize`, and defaults to `${java.io.tmpdir}/brace-uploads`, which in
a container with a small writable layer may be the wrong place:

```java
app.maxUploadSize("500M")
   .uploadMemoryThreshold("256K")
   .uploadTempDir(Path.of("/var/lib/myapp/uploads"));
```

The directory is created owner-only (700). Files left by a hard kill are swept on the next startup.

### New-optional: `Storage.put` streams

**What changed.** `storage.put(key, UploadedFile)` and `putGenerated(...)` hash and send the payload
without reading it into the heap, so a spilled upload reaches S3 without a heap round trip. New
`storage.put(key, Path, contentType)` for content already on disk. `put(key, byte[], contentType)`
is unchanged. Objects over S3's 5 GiB single-`PUT` limit are rejected up front with a message
naming that limit.

**Who must act.** Nobody.

### New-optional: streaming responses and `Range` support

**What changed.** New `Result` factories stream the body instead of materializing it:

```java
Result.file(path)                          // Content-Length, Range, type from the extension
Result.file(path, "video/mp4")
Result.download(path, "report.csv")        // attachment; filename escaped like download(byte[], ...)
Result.stream(inputStream, "image/png")    // unknown length, chunked
Result.stream(inputStream, "image/png", n) // known length
Result.stream(out -> { ... }, "text/csv")  // generated as it is written
```

Static files now stream too, advertise `Accept-Ranges: bytes`, and answer a single `Range` with
`206` (or `416`). `If-Range` is checked against the `ETag`. Conditional GETs (`304`) are unchanged.

**Who must act.** Nobody, unless you use one of these. Three rules apply to a streaming result:

1. **No page caching.** `Cache` page caching of a streaming result throws instead of storing an
   empty body.
2. **No request `Database` inside the body.** The request transaction commits and its connection
   returns to the pool before the body is written. Fetch what you need in the handler, or open a
   separate session inside the writer.
3. **No status change mid-stream.** A body that fails partway aborts the connection, so the client
   sees a truncated transfer rather than a clean `200`.

An after-middleware that rewrites response bodies should pass through when `result.isStreaming()`;
`body()` and `rawBytes()` are null for a streaming result.

### New-optional: Server-Sent Events with `Result.sse`

**What changed.** A handler can return a Server-Sent Events stream. The producer gets an
`EventStream` and sends events as they happen; each one is flushed before the call returns.

```java
app.get("/ticks", req -> Result.sse(events -> {
    for (int i = 0; events.isOpen(); i++) {
        events.send("tick", "n=" + i, String.valueOf(i));   // event, data, id
        Thread.sleep(1000);
    }
}));
```

Also `send(data)`, `sendJson(event, value)`, `comment(text)`, `retry(Duration)` and
`heartbeat(Duration)`. The response is `text/event-stream` with `Cache-Control: no-cache` and
`X-Accel-Buffering: no`.

The rules that matter:

- **The producer runs after the request transaction commits** and holds no database connection.
  The handler's `db` is closed by then; open a short `dbFactory.withSession(...)` per unit of work.
- **A disconnect ends the producer.** A send to a gone client throws `UncheckedIOException`. A
  heartbeat comment every 15 seconds notices a client that left while the producer was waiting and
  interrupts the producer thread, so `Thread.sleep` or `queue.take()` throws
  `InterruptedException`. Both are the normal end of a stream and are not logged as failures.
- **Reconnects** carry the last event id in the `Last-Event-ID` request header:
  `req.header("Last-Event-ID")`.
- The request is recorded in stats and the request log when the stream opens, with the handler's
  duration, not the stream's lifetime.

**Who must act.** Nobody. Apps that hand-rolled SSE with `Result.stream(out -> ...)` can switch to
get heartbeats and disconnect handling.

### Fix: `Storage.put` and `Storage.delete` threw on every call

**What changed.** Both set the `Host` header explicitly, which the JDK HttpClient refuses
(`IllegalArgumentException: restricted header name: "Host"`) unless the JVM runs with
`-Djdk.httpclient.allowRestrictedHeaders=host`. The header is no longer set; the client derives it
from the URI, and the SigV4 signature uses the same authority.

**Who must act.** Nobody. If you added `-Djdk.httpclient.allowRestrictedHeaders=host` as a
workaround, you can remove it.

### Fix: oversized multipart uploads return 413, not 500

**What changed.** The `Content-Length` fast-reject only covered non-multipart bodies, so an
oversized multipart upload hit Jetty's internal cap and became a `500`, recording a framework error
and notifying on every attempt. It is now a `413` with no error recorded, like other bodies.

**Who must act.** Nobody.

<!-- end section: streaming-io -->

---

<!-- section: proxies -->
## Trusted proxies

### New (optional): `TrustedProxies.cloudflare()` preset with auto-refresh

For apps behind Cloudflare, trusted proxies no longer require hand-pasting Cloudflare's
published CIDR list. A new preset ships with the published egress ranges bundled, an
optional background refresh, and a way to add your own hops.

**Before (0.1.9):**

```java
app.trustedProxies("173.245.48.0/20", "103.21.244.0/22", /* ...the rest of cloudflare.com/ips... */);
```

**After (0.1.10):**

```java
app.trustedProxies(TrustedProxies.cloudflare().autoRefresh());

// with nginx between Cloudflare and the app, also trust the local hop:
app.trustedProxies(TrustedProxies.cloudflare().plus("127.0.0.1", "::1").autoRefresh());
```

- `cloudflare()` starts from the bundled list, so there's no network dependency at startup.
- `.autoRefresh()` re-fetches `cloudflare.com/ips-v4` and `/ips-v6` on a background daemon
  thread (daily, with an hourly retry after a failure) that `app.stop()` ends. A failed or
  partial fetch is discarded wholesale, so the trust set never shrinks on a network blip.
- `.plus(cidrs)` adds CIDRs/IPs that survive refreshes (local reverse proxy, LAN ranges).
- A new `app.trustedProxies(TrustedProxies)` overload accepts the pre-built instance. The
  existing varargs/list overloads are unchanged.

Existing `app.trustedProxies("10.0.0.0/8", ...)` configurations keep working as-is. Still
pass the real `Host` through from your proxy, as the 0.1.8 guide describes.

### Behavior: startup warning when `RateLimiter.perIp` runs without trusted proxies

`Brace.start()` now logs a `WARN` when a `RateLimiter.perIp(...)` middleware is registered
but `app.trustedProxies(...)` was never called. In that configuration `req.ip()` is the
socket peer, so behind a reverse proxy or CDN every client shares the proxy's address and
the per-IP limit is silently site-wide.

No action required: it's a log line only. Nothing fails, and apps whose clients connect
directly can ignore it. To resolve the warning, configure `app.trustedProxies(...)`
(see the section above for Cloudflare).

<!-- end section: proxies -->

---

<!-- section: http-streaming -->
## Streaming in the `Http` client

### New (optional): `Http.stream()` and `fetchEvents` for streamed responses

**Nothing to do.** Every `fetch*` call still buffers the whole body, and `.timeout()` and its
30s default still mean what they did for them. Before 0.1.10 there was no way to read a response as it arrived,
so apps calling streaming LLM APIs dropped to raw `java.net.http`, parsed SSE `data:` lines
by hand, and ran their own watchdog thread, because `HttpRequest.timeout` stops counting once
the headers arrive and a stalled stream would block forever. If your app has code like that,
replace it.

**Before (all versions, still works):**

```java
var res = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
var stream = res.body();
var watchdog = WATCHDOG.schedule(() -> { try { stream.close(); } catch (Exception ignored) { } },
    timeout.toMillis(), TimeUnit.MILLISECONDS);
try (var reader = new BufferedReader(new InputStreamReader(stream, UTF_8))) {
    if (res.statusCode() / 100 != 2) { /* read the error body by hand */ }
    var data = new StringBuilder();
    for (String line; (line = reader.readLine()) != null; ) {
        if (line.isEmpty()) { if (!data.isEmpty()) onEvent(data.toString()); data.setLength(0); }
        else if (line.startsWith("data:")) { /* append, strip, join with \n */ }
    }
} finally {
    watchdog.cancel(false);
}
```

**After (0.1.10+):**

```java
var res = Http.post(baseUrl + "/v1/messages")
    .header("x-api-key", apiKey).bodyJson(body)
    .idleTimeout(Duration.ofSeconds(60))    // no headers/bytes for 60s (default 30s)
    .timeout(timeout)                       // optional: deadline for the whole stream
    .fetchEvents(ev -> onEvent(ev.data()));
if (!res.ok()) return new Reply(res.status(), res.body(), res.header("retry-after"));
```

Details:

- `fetchEvents(Consumer<Http.Event>)` parses the full event-stream format (`event`,
  multi-line `data`, `id`, `retry`, comments, CRLF/LF/CR) and returns a `Response` once the
  server closes the stream. It sends `Accept: text/event-stream` unless you set an Accept
  header. As the SSE spec requires, an event left unterminated (no closing blank line) when
  the stream ends is dropped, since the stream was most likely cut off mid-event.
- A non-2xx status is returned, not thrown, as with `fetch()`: no events are parsed and
  `body()` is the error body. A timeout or dropped connection throws.
- `stream()` returns an `AutoCloseable` `Http.StreamResponse` (`status()`, `ok()`,
  `header()`, `body()` as an `InputStream`, `lines()`, `readString()`, `events(consumer)`).
  Use try-with-resources; closing it releases the connection. Use `events(...)` instead of
  `fetchEvents` to check the status and headers before reading events, or to stop early by
  calling `close()` from the consumer. `lines()` suits other formats such as NDJSON.
- Stream timeouts differ from `fetch*`. `.idleTimeout()` (**default 30s**; `Duration.ZERO`
  or `null` turns it off) bounds each wait for the response headers or the next bytes of
  the body; time your code spends between reads doesn't count. There is **no total
  deadline by default**, so long but healthy generations aren't cut off; an explicit
  `.timeout()` sets one for the whole call, from sending the request to the end of the body.
  Either closes the connection and throws `Http.StreamTimeoutException`, an
  `UncheckedIOException` with `idle()` and `limit()`. The timeouts run on one shared daemon
  thread, not a thread per call.
- Both work on `multipart()` requests.

<!-- end section: http-streaming -->

---

<!-- section: ops-dashboard -->
## Ops dashboard

This section has no breaking changes. `/ops/status` only gains fields, the dashboard is
framework-rendered, and `Stats.MinuteSnapshot` keeps its old constructor.

### Per-route stats are keyed by route pattern again

**Type: fix. Action required: none.** Per-route stats (`http.slowestRoutes`, Top Routes) are
keyed by route pattern again (H7), so they stay bounded by the route table instead of
growing one entry per distinct URL.

The 0.1.7 fix that recorded matched requests under their route pattern
(`GET /users/{id}`) instead of the concrete path (`GET /users/42`) had been lost in a
merge, so `http.slowestRoutes` again listed one entry per distinct URL and the per-route
map grew with traffic. It is restored. If you had tooling matching concrete paths in
`http.slowestRoutes`, match the pattern instead:

```text
before: { "route": "GET /users/42", ... }, { "route": "GET /users/43", ... }
after:  { "route": "GET /users/{id}", "count": 2, ... }
```

Requests that matched no route are counted in the constant `(unmatched)` and `(static)`
buckets; see "Fix: `/ops/routes` shows route patterns, and every response is counted" above.

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
  matched no route fold into a single `"(unmatched)"` entry and static files into a single
  `"(static)"` entry, whatever their method. Both count toward `sharePct`.
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

### New `Stats` read-only data

`MinuteSnapshot` gained two trailing components: `routeCounts` (`Map<String, Long>`, the
per-route counts for that minute) and `p95LatencyUs` (with a `p95LatencyMs()` accessor).
The old 11-argument constructor still exists and fills them with `Map.of()` and `0`, so
existing code compiles unchanged.

New read-only helpers on `Stats`: `requestRate()` (null before the first minute) and
`topRoutes(windowMinutes, limit)`.

<!-- end section: ops-dashboard -->

---

<!-- section: ops-jvm-cli -->
## GC pause figures and CLI

### Fix: GC pause figures count only stop-the-world time

**What changed.** `/ops/status` `jvm.gc` pause figures (`totalPauseMs`, `avgPauseMs`,
`recentPauses[].durationMs`), the dashboard's GC card and table, the `jvm.gc_total_pause_ms`
/ `jvm.gc_max_pause_ms` timeseries and `brace check`'s `gc_pressure` now come from JFR's
`sumOfPauses` and `longestPause`, the time the application was actually stopped. Before,
they used each collection's whole duration. For concurrent collections (G1's `G1Old`
marking cycle, ZGC, Shenandoah) most of that time runs alongside the application, so pause
time was overstated, often about 3x on G1.

New fields, all additive:

| Field | Meaning |
|---|---|
| `jvm.gc.maxPauseMs` | Longest single pause in the last 100 collections |
| `jvm.gc.fullCount` | Whole-heap stop-the-world collections (`G1Full`, `SerialOld`, `ParallelOld`) |
| `recentPauses[].longestPauseMs` | Longest single pause within that collection |
| `recentPauses[].cycleMs` | The collection's wall-clock span (the old `durationMs` value) |
| `recentPauses[].full` | `true` on full collections; absent otherwise |

`gc_pressure` still fails when `avgPauseMs` exceeds `check.gc_pause_ms`. It now also warns
(without failing `brace check`) when a `G1Full` appears among the recent collections, and
its `followUp` is `brace status --include profiling` for allocation data.

**Who needs to act.** No code changes. If you alert on `jvm.gc` figures or raised
`check.gc_pause_ms` in `.brace` to quiet false `gc_pressure` failures, expect lower numbers
and consider restoring the default (50). A long `cycleMs` with a small `durationMs` is a
normal concurrent cycle.

**Before (0.1.9)**, a 120 ms G1 concurrent cycle with 6 ms of real pause:

```json
{ "collector": "G1Old", "durationMs": 120.0 }
```

**After (0.1.10):**

```json
{ "collector": "G1Old", "durationMs": 6.0, "longestPauseMs": 4.0, "cycleMs": 120.0 }
```

### New (optional): `brace status --include profiling,timeseries`

**What changed.** `brace status` always fetched the bare `/ops/status`, so the opt-in
blocks (JFR hot methods and top allocations, per-minute timeseries) were reachable only
over HTTP. `--include` passes a comma-separated list through as `?include=...`. Unknown
names are rejected, since the server would silently ignore them. Without the flag the
request and output are unchanged.

**Who needs to act.** Nobody. Scripts that called the endpoint directly to get profiling
can switch to the CLI, which handles auth.

**Before (0.1.9):**

```bash
curl -H "Authorization: Bearer $TOKEN" "$URL/ops/status?include=profiling"
```

**After (0.1.10):**

```bash
brace status --env prod --include profiling --json
```

### Fix: `brace ops --help` lists the ops subcommands

**What changed.** `brace ops --help` and `brace ops -h` printed "Unknown ops command:
--help" and exited 1. They now list `keypair` and `dashboard` with one-line descriptions and
exit 0, as do `brace ops help` and bare `brace ops` (which used to exit 1 with a one-line
usage). `--help` after a subcommand (`brace ops keypair --help`) also prints the list;
before, `keypair` ignored the flag and generated a keypair (or, when `ops-private.key`
already existed, refused with an error).

**Who needs to act.** Nobody.

<!-- end section: ops-jvm-cli -->

---

<!-- section: dx -->
## New projects and custom metrics

### Fix: scaffolded `Dockerfile` (precompiled templates on a JRE, `JAVA_OPTS`, heap cap)

**What changed.** 0.1.7 changed the scaffolded `Dockerfile` to precompile templates and run
in prod mode on `eclipse-temurin:25-jre`, but a merge dropped that change before release, so
0.1.7 through 0.1.9 still scaffolded `FROM eclipse-temurin:21-jre` with `java -jar app.jar`.
That image fails on the first rendered page, because JTE then compiles templates with
`javac` and a JRE doesn't include it. `brace new` now writes a `Dockerfile` that:

- runs on `eclipse-temurin:25-jre`, copies `target/jte-classes/`, and runs with
  `-Dbrace.mode=prod`, so templates load precompiled and no compiler is needed;
- runs `exec java -Dbrace.mode=prod $JAVA_OPTS -jar app.jar` through `sh -c`, so JVM flags can
  be set per deployment and `java` is PID 1 (it gets `docker stop`'s SIGTERM and Brace's
  shutdown hook runs);
- defaults `JAVA_OPTS` to `-XX:MaxRAMPercentage=50`. Without a heap flag the JVM sizes its heap
  from the host's RAM when the container has no memory limit;
- copies `ops-authorized-keys`, which the scaffold's `main()` fails to start without.

The scaffolded `pom.xml` also precompiles `views/` into `target/jte-classes` during
`mvn package`, so the Dockerfile's single build step always ships classes that match the jar.
`brace compile` writes the same directory.

Forgetting the precompile step now fails at startup with a message naming `brace compile`: in
prod mode on a JRE with no matching precompiled classes, `app.templates(...)` throws
`IllegalStateException` instead of failing inside JTE's compiler. On a JDK, prod mode still
compiles all templates at startup as before.

**Who needs to act.** Existing projects keep the `Dockerfile` and `pom.xml` they were generated
with; nothing regenerates them. If your Dockerfile still says `eclipse-temurin:21-jre` or
`CMD ["java", "-jar", "app.jar"]`, update it by hand:

1. Precompile, copy the classes and run in prod mode as described in
   [Deploying with Docker](brace-0.1.6-to-0.1.7.md#deploying-with-docker-or-any-non-cli-launch)
   in the 0.1.6 → 0.1.7 guide. Precompile as part of `mvn package` (below) rather than as a
   separate manual step: stale `target/jte-classes` from an earlier build would be served as-is.
2. Replace the `CMD` with the `JAVA_OPTS` entrypoint, and copy `ops-authorized-keys` if
   `main()` calls `.ops(...)` (never copy `ops-private.key`).

**Before (0.1.9 scaffold):**

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/app.jar app.jar
COPY application.conf.example application.conf
COPY views/ views/
COPY public/ public/
COPY migrations/ migrations/
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

**After (0.1.10 scaffold):**

```dockerfile
# Build first: mvn package (writes target/app.jar and target/jte-classes)
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/app.jar app.jar
COPY application.conf.example application.conf
COPY target/jte-classes/ target/jte-classes/
COPY views/ views/
COPY public/ public/
COPY migrations/ migrations/
COPY ops-authorized-keys ops-authorized-keys
EXPOSE 8080
# Heap cap as a share of the container's memory limit. Run with a limit (docker run
# --memory=1g); without one, use an explicit -Xmx. On JDK 25 consider adding
# -XX:+UseCompactObjectHeaders (usually 10-20% less heap for entity-heavy apps).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50"
ENTRYPOINT ["sh", "-c", "exec java -Dbrace.mode=prod $JAVA_OPTS -jar app.jar"]
```

**Add to `pom.xml`**, inside `<plugins>` after `maven-shade-plugin`:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.5.0</version>
    <executions>
        <execution>
            <id>precompile-templates</id>
            <phase>package</phase>
            <goals><goal>exec</goal></goals>
            <configuration>
                <executable>${java.home}/bin/java</executable>
                <arguments>
                    <argument>-cp</argument>
                    <classpath/>
                    <argument>com.larvalabs.brace.TemplatePrecompiler</argument>
                    <argument>views</argument>
                    <argument>target/jte-classes</argument>
                </arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Keep the `exec` in the entrypoint. Without it `sh` stays PID 1, does not forward SIGTERM, and
the container is killed after the stop timeout without a clean shutdown. Prod mode also applies
`%prod.` config keys, so check for any your container wasn't using before.

---

### Security fix: the scaffold's placeholder session secret is refused at startup

**Action required: set a real session secret before upgrading** if your app might be running on
the placeholder. Otherwise the upgraded app will not start.

**What changed.** `brace new` used to write
`session.secret=CHANGE-ME-to-a-random-string-at-least-32-chars` into
`application.conf.example`, and the scaffolded `Dockerfile` copied that file into the image as
`application.conf`. A key in the file beats an environment variable of the same name, so
`docker run -e SESSION_SECRET=...` was ignored and those containers sign session cookies with a
public string: anyone can forge a session, including a logged-in one. Brace only logged a
"weak secret" warning. `.sessions(...)` now throws `IllegalArgumentException` for that exact
value unless `brace.mode` is `dev`. That includes runs with no `brace.mode` at all, which is how
the old scaffolded Dockerfile launched the app. In dev mode it is still only a warning.

**Who needs to act.** Check the secret your production app actually uses: the
`session.secret` line in the `application.conf` that ends up on the server or in the image, and
the `SESSION_SECRET` variable. If it is the placeholder:

1. Generate a secret once: `openssl rand -base64 32`. Store it with your other secrets and use
   the same value on every instance.
2. Set it as `SESSION_SECRET` in your deploy platform.
3. Make the deployed config read it: `session.secret=${SESSION_SECRET}` (see the next entry for
   the full env-based `application.conf.example`).
4. Deploy that before (or together with) the Brace upgrade. Everyone is logged out once, since
   existing cookies were signed with the old secret.

**Before (0.1.9):** the placeholder logs a warning and the app runs.

**After (0.1.10):** outside dev mode, startup fails with:

```
java.lang.IllegalArgumentException: session secret is the placeholder that older `brace new`
scaffolds shipped in application.conf.example (CHANGE-ME-to-a-random-string-at-least-32-chars).
It is public, so anyone can forge session cookies. ...
```

---

### Fix: scaffolded container config reads secrets from the environment

**What changed.** The scaffolded `Dockerfile` copies `application.conf.example` into the image as
`application.conf`. That file used to hold a placeholder `session.secret` and literal database
settings. `Config` only falls back to an environment variable when a key is *absent* from the
file, so `docker run -e SESSION_SECRET=... -e DB_PASS=...` (as the Dockerfile suggested) was
ignored: every container signed sessions with the public placeholder, and only logged a "weak
secret" warning. `brace new` now writes the example with `${VAR}` references, so the container
takes them from the environment and fails to start when `SESSION_SECRET` is unset.

The local `application.conf` (gitignored, with a generated secret) is unchanged.

**Who needs to act.** Projects scaffolded before 0.1.10 whose Docker image copies
`application.conf.example`: your containers are running on the placeholder secret unless you
edited the file. Change the per-deployment keys to `${VAR}` references, set the variables in
your deploy platform, and redeploy. Changing the secret logs everyone out once.

**Before (0.1.9 `application.conf.example`):**

```properties
port=8080
db.url=jdbc:postgresql://localhost:5432/myapp
db.user=myapp
db.pass=
session.secret=CHANGE-ME-to-a-random-string-at-least-32-chars
```

**After (0.1.10):**

```properties
port=8080
db.url=${DATABASE_URL}
db.user=${DB_USER}
db.pass=${DB_PASS}
session.secret=${SESSION_SECRET}
```

`DATABASE_URL` may be a JDBC URL or a PaaS-style `postgresql://user:pass@host:5432/db`
(credentials embedded in it are used when `DB_USER`/`DB_PASS` are unset). Generate the secret
once, for example `openssl rand -base64 32`, and keep it identical across instances and restarts.

---

### Fix: a failed `start()` no longer leaves the JVM running

When `app.start()` threw part-way (missing `ops-authorized-keys`, port already in use), the JFR
profiler's non-daemon thread kept the process alive with no server, so containers stayed
"running" and restart policies never fired. A failed `start()` now stops what it had started
and the process exits. No action required.

---

### New (optional): static custom metrics with `Metrics`

**What changed.** `Metrics.counter(...)`, `Metrics.gauge(...)` and `Metrics.timer(...)` are
static, like `Log`, so a service can record a metric without being handed the app's `Stats`.
They record into the same `Stats` that `app.stats()` returns: the most recently constructed
app's. Metrics recorded before `Brace.app()` runs (for example a gauge registered in a service
constructor earlier in `main()`) are kept and adopted by the first app.

**Who needs to act.** Nobody. `app.stats()` and its `counter`/`gauge`/`timer` methods are
unchanged. If you thread `app.stats()` into services only to record metrics, you can drop that
plumbing. Keep using `app.stats()` in tests that read values (`counterTotal(name)`) or that run
several apps in one JVM.

There is no static `Stats.counter(...)`; older docs showed it, but it never compiled.

**Before (0.1.9):**

```java
// main()
var weather = new WeatherClient(http).withStats(app.stats());

// WeatherClient
private Stats stats;
public WeatherClient withStats(Stats stats) { this.stats = stats; return this; }
void fetch() { ...; if (stats != null) stats.counter("weather.calls"); }
```

**After (0.1.10):**

```java
// main()
var weather = new WeatherClient(http);

// WeatherClient
void fetch() { ...; Metrics.counter("weather.calls"); }
```

---

### Docs: java.time values in JSON responses

**What changed.** Documentation only; `Json` already behaved this way. `BRACE-AGENTS.md` and the
`CLAUDE.md` that `brace new` writes now say: put `LocalDateTime`/`LocalDate`/`Instant` values
into the returned record or `Json.obj(...)` and let `Json` serialize them as ISO-8601. Don't
call `.toString()` on them: `LocalDateTime.toString()` drops zero seconds (`2025-06-15T09:00`
instead of `2025-06-15T09:00:00`), which strict ISO-8601 consumers reject.

**Who needs to act.** Nobody. `brace agents-md` picks up the `BRACE-AGENTS.md` change. Existing
projects' `CLAUDE.md` is not regenerated; to give agents the hint there too, add this to its
Responses line: "Put java.time values in as objects (`Json` writes ISO-8601); never
`.toString()` them."

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

### Fix: requests without a body no longer allocate a body buffer

**What changed.** A request that declares no body (no `Content-Length` above 0 and no
`Transfer-Encoding`, as with almost every `GET`) skips the body read and sees `req.body()` as
`""`, as before. A request that does declare one gets a read buffer sized from its
`Content-Length` (up to 64KB) instead of a flat 64KB. Chunked bodies, multipart uploads and the
413 limit behave as before, and a `GET` that declares a body still has it read.

**Who needs to act.** No one.

### Fix: the session cookie is decrypted at most once per request

**What changed.** When the handler takes no `Session`, Brace decrypts the session cookie only
when something needs it: the CSRF check on a mutating request, a rendered CSRF field, or a
rendered flash message. All three share one decrypt. A mutating request used to decrypt it
twice, and a `.csrf(false)` route that never touches the session (a bearer-token API) now does
no session crypto at all. Flash messages still render on `.csrf(false)` routes.

**Who needs to act.** No one.

### Fix: a template that fails to render returns a clean 500

**What changed.** A `View` is rendered again after the transaction commits and before the
response status and headers are written. A template that throws now produces the normal 500,
recorded once as a 500 in `/ops/status` and the request log. Before this fix, the 500 still
carried the handler's headers and cookies and was recorded as a 200. The transaction still
commits before the render, as in 0.1.7. Streamed responses and event streams are not affected.

**Who needs to act.** No one.

<!-- end section: merge-restore -->
