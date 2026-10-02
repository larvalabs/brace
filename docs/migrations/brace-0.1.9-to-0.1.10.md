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

### New (optional): `Http.stream()` and `fetchEvents` for streamed responses

**Nothing to do.** Every `fetch*` call still buffers the whole body, and `.timeout()` still
means what it did for them. Before 0.1.10 there was no way to read a response as it arrived,
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
    .timeout(timeout)                       // the whole stream, not just the headers
    .idleTimeout(Duration.ofSeconds(60))    // optional: no bytes for 60s
    .fetchEvents(ev -> onEvent(ev.data()));
if (!res.ok()) return new Reply(res.status(), res.body(), res.header("retry-after"));
```

Details:

- `fetchEvents(Consumer<Http.Event>)` parses the full event-stream format (`event`,
  multi-line `data`, `id`, `retry`, comments, CRLF/LF/CR) and returns a `Response` once the
  server closes the stream. It sends `Accept: text/event-stream` unless you set an Accept
  header. An event the server ends the stream on without a trailing blank line is still
  delivered.
- A non-2xx status is returned, not thrown, as with `fetch()`: no events are parsed and
  `body()` is the error body. A timeout or dropped connection throws.
- `stream()` returns an `AutoCloseable` `Http.StreamResponse` (`status()`, `ok()`,
  `header()`, `body()` as an `InputStream`, `lines()`, `readString()`) for other streamed
  formats such as NDJSON. Use try-with-resources; closing it releases the connection.
- On these two calls `.timeout()` (default 30s) is the **deadline for the whole call**, from
  sending the request to the end of the body. Raise it for long generations. `.idleTimeout()`
  (off by default) bounds how long a read waits for the next bytes; time your code spends
  between reads doesn't count. Either closes the connection and throws
  `Http.StreamTimeoutException`, an `UncheckedIOException` with `idle()` and `limit()`. The
  timeouts run on one shared daemon thread, not a thread per call.
- Both work on `multipart()` requests.

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
