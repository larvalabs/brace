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
