# Plan: Streaming uploads and responses

Status: Draft — not implemented. Targets 0.1.8; security-reviewed on the PR before merge.
Date: 2026-07-26

## Goal

Make request bodies and response bodies flow through Brace in **bounded memory**, regardless of
their size. Today every byte of every upload and every download is materialized in the heap, so
the largest object an app can move is a function of its heap size divided by its concurrency —
and `maxUploadSize` (default 10 MB) is the only thing standing between an app and an OOM.

After this plan:

- A 2 GB upload costs a bounded buffer plus disk, not 2 GB of heap.
- A 2 GB download streams from disk / S3 / a generator with a bounded buffer, and supports
  `Range` so video seeks and resumable downloads work.
- **Existing app code keeps working unchanged** — the common paths (`file.saveTo(path)`,
  `storage.put(key, file)`, static file serving) become streaming *underneath* their current
  signatures.

## Current state

### Ingest

| Step | Where | Cost |
|---|---|---|
| Multipart parse | `BraceHandler.java:978-980` — `setMaxMemoryFileSize(-1)` | Jetty explicitly told **never** to spill to disk |
| Part → `byte[]` | `BraceHandler.java:996-998` — `Content.Source.asByteBuffer` then a copy | transient ~2× part size |
| Part held | `UploadedFile.java:11` — `private final byte[] bytes` | 1× per part, for the whole request |
| `saveTo(Path)` | `UploadedFile.java:24-27` — `Files.write(path, bytes)` | memory → disk, never stream → disk |
| Non-multipart body | `BraceHandler.java:899-916` — `ByteArrayOutputStream`, then `new String(bytes, UTF_8)` at `:952` | ~3× the body: BAOS doubling, `toByteArray` copy, then the String |
| S3 upload | `Storage.java:125-160` — `byte[]` param, `sha256Hex(data)`, `BodyPublishers.ofByteArray` | another full pass; hash requires the whole payload up front |

Peak heap is roughly `concurrent_uploads × size × 2–3`. Handlers run on virtual threads, so
nothing bounds `concurrent_uploads` — this is exactly the shape the 2026-07 security review's M2
flagged. M2 fixed *when* the buffering happens (lazily, after before-middleware, so a rate limiter
can shed load first — `BraceHandler.java:207-223`). It did not change *that* it happens.

### Egress

| Step | Where | Cost |
|---|---|---|
| Every response | `BraceHandler.java:587-595` — one `ByteBuffer.wrap(bytes)`, one `response.write(true, …)` | whole body in heap |
| Static files | `BraceHandler.java:736` — `Files.readAllBytes(realFile)` | whole file in heap, **per concurrent request** |
| `Range` | — | not implemented anywhere; no `Accept-Ranges`, no 206 |
| `Result.stream(...)` | — | does not exist, despite `docs/2026-03-29-brace-framework-design.md:180` listing it |
| `Storage.get(...)` | — | does not exist at all; there is no download path |

Serving a 200 MB video from `/static` today costs 200 MB of heap per concurrent viewer, and
seeking in it re-downloads from byte zero.

This limitation is already recorded as accepted-and-documented: L13 in
`docs/2026-06-11-runtime-performance-review-todos.md` and the caveat at `docs/scaling.md:129-132`,
which names "temp-file + `BodyPublishers.ofFile`" as the future fix. This plan is that fix, widened
to cover ingest and egress symmetrically.

## Non-goals

- **Not raising `maxUploadSize`'s default.** Streaming makes a large cap *survivable*; it does not
  make it *advisable*. The default stays 10 MB.
- **Not zero-disk.** Spilling to disk is the point: it converts an unbounded heap cost into a
  bounded, observable, cheap one. Apps that want zero-copy straight to S3 use presigned URLs
  (Phase 4).
- **Not HTTP/2, TLS, or compression.** Brace serves HTTP/1.1 and expects a reverse proxy
  (`docs/SECURITY.md`). Chunked transfer encoding is the only wire-level change here.
- **Not streaming *from* the request database session.** See "The transaction boundary" below —
  this is a deliberate, load-bearing restriction, not an oversight.
- **Not S3 multipart upload (>5 GB objects) in v1.** Deferred to Phase 8.

## Design principles

1. **The fix lands under the existing API.** `UploadedFile.saveTo`, `Storage.put(key, file)`, and
   static file serving become streaming without a signature change. An app that never learns this
   feature exists still stops OOMing. New API is for cases the old API genuinely can't express.
2. **Bounded memory is a property, not a suggestion.** Every new path gets a test that runs under a
   deliberately small `-Xmx` and moves an object larger than the heap.
3. **Streaming must not silently degrade correctness.** CSRF, the page cache, after-middleware, and
   the DB transaction boundary all have assumptions that a streaming body breaks. Each one either
   keeps working or fails loudly. None of them fails quietly.

---

## Part 1 — Ingest

### Phase 1: Spill multipart parts to disk (default; no app change)

The one-line core of it is at `BraceHandler.java:978-980`:

```java
var parser = new MultiPartFormData.Parser(boundary);
parser.setMaxLength(maxUploadSize);
parser.setMaxMemoryFileSize(-1);        // ← "never spill to disk"
```

becomes a threshold plus a directory, and the `asByteBuffer` copy at `:996-998` goes away in favour
of keeping the part's backing file.

**New config** (on `Brace`, alongside `maxUploadSize` at `Brace.java:340-347`):

| Knob | Default | Meaning |
|---|---|---|
| `app.uploadMemoryThreshold("1M")` | 1 MB | parts larger than this spill to a temp file |
| `app.uploadTempDir(Path)` | `${java.io.tmpdir}/brace-uploads` | where they spill |
| `app.maxUploadSize("10M")` | unchanged | still caps the whole request body |

Choosing 1 MB rather than "equal to `maxUploadSize`" (which would be a pure no-op default) is
deliberate: it means the default configuration stops being able to pin 10 MB of heap per in-flight
request, which is the DoS shape M2 identified. Sub-1 MB uploads — avatars, CSVs, the overwhelming
majority — are untouched and never see the disk.

**`UploadedFile` gains a backing-store abstraction.** It keeps `byte[] bytes()` and grows the
streaming accessors:

```java
public InputStream stream()              // always works, bounded memory
public long size()                       // already exists; now free for file-backed parts
public byte[] bytes()                    // still works — reads the file back for file-backed parts
public void saveTo(Path path)            // now a move (same filesystem) or a streaming copy
public long transferTo(OutputStream out) // new: stream out without materializing
```

`bytes()` staying functional is what makes this a non-breaking change: `FileUploadTest` and every
app that calls `file.bytes()` keeps passing. It is documented as "materializes the whole part in
heap — prefer `stream()`", and it is bounded by `maxUploadSize` exactly as it is today. An app that
raises `maxUploadSize` to 2 GB *and* calls `bytes()` gets what it asked for; an app that raises it
and uses `saveTo`/`storage.put` never materializes anything.

**Temp-file lifecycle is the risky part of this phase.** Today `parseMultipart` closes the parts in
a `finally` before returning (`BraceHandler.java:1013-1015`), which is correct when parts are heap
buffers and catastrophic when they are files — it would delete them before the handler ever runs.
The lifecycle has to move out to the request:

- `Request` gains a package-private list of resources to release.
- `handle()` releases them in a `finally` that wraps **every** exit path — the 413 catch at `:427`,
  the `NotFoundException` catch at `:438`, the generic catch at `:452`, the CSRF 403 at `:322-327`,
  and the normal path. This is the same "every exit from `handle()`" discipline the M1 after-middleware
  fix established for `writeResult`; reuse the pattern rather than sprinkling deletes.
- `saveTo` moving the file marks it released, so cleanup is a no-op rather than a spurious failure.
- Temp dir is created with owner-only permissions (POSIX 700). Uploaded content is untrusted and
  the default umask on some systems would leave the files group-readable.
- A startup sweep deletes orphans older than a few hours (a hard kill leaves files behind), logged
  as a `brace.uploads.sweep` event.
- `/ops/status` exposes the current temp-file count and total bytes, so a leak is visible before it
  is an outage.

**New failure mode to be honest about:** disk exhaustion replaces heap exhaustion. It is a far more
graceful failure (writes fail, the app keeps serving, deletes still work) and `maxUploadSize` still
bounds each request, but concurrency × `maxUploadSize` can now fill a volume. The ops counter above
plus a documented note in `docs/scaling.md` is the mitigation; a global in-flight-bytes cap is a
possible later addition, deliberately not in v1.

### Phase 2: `Storage` streams from the file

With Phase 1 landed, `Storage.put(key, file)` (`Storage.java:165-168`) can avoid the heap entirely:

```java
public StoredFile put(String key, UploadedFile file)   // now streams from the part's file
public String put(String key, Path path, String contentType)          // new
public String put(String key, InputStream in, long length, String contentType)  // new
```

The interesting constraint is SigV4: `Storage.java:132` computes `sha256Hex(data)` over the whole
payload, and the hash goes in a signed header — so the payload must be known before the first byte
is sent. Three ways out, in preference order:

1. **Hash the file, then send the file** (default). Two sequential disk passes, zero heap:
   `MessageDigest` over a streamed read, then `BodyPublishers.ofFile(path)`. Keeps a fully signed
   payload, so it works identically on S3, R2, MinIO, and Spaces. The second pass is usually served
   from page cache anyway.
2. **`x-amz-content-sha256: UNSIGNED-PAYLOAD`** for sources that cannot be re-read (a raw
   `InputStream` from Phase 3). Valid over HTTPS on S3/R2/MinIO. Offered as an opt-in
   (`app.storageUnsignedPayload(true)` or a per-call overload) with the trade documented: no
   end-to-end payload integrity from the signature.
3. **Spool the stream to a temp file first**, then take path 1. The safe fallback when the caller
   has a stream, `UNSIGNED-PAYLOAD` is not acceptable, and the length is unknown.

Note the 5 GB ceiling on a single S3 `PUT`. Objects above that need the multipart upload API —
Phase 8, deferred. The `put` methods should reject above 5 GB with a message naming that limit
rather than failing opaquely at the endpoint.

### Phase 3: Raw body streaming (opt-in, per route)

For `PUT /files/{key}` style APIs where the body *is* the file and there is no multipart wrapper:

```java
app.put("/files/{key}", (Request req) -> {
    try (var in = req.bodyStream()) {
        storage.put(req.param("key"), in, req.contentLength(), req.contentType());
    }
    return Result.noContent();
}).streaming();
```

Three interactions have to be handled explicitly:

- **The eager resolve at `BraceHandler.java:281`** (`braceRequest.resolveBody()`) would drain the
  stream before the handler sees it. The `.streaming()` route flag skips it. This is why it is a
  route flag and not a runtime decision — the framework has to know *before* the body is touched.
- **CSRF cannot read a streamed body.** `BraceHandler.java:317-318` pulls `_csrf` from the parsed
  form body; on a streaming route there is no parsed form body and never will be. The token must
  ride the `X-CSRF-Token` header. Enforce this **at route registration**: `.streaming()` on a
  mutating route with CSRF enabled either requires the header or requires an explicit
  `.csrf(false)`, and says so in the exception message. Failing at startup beats failing at 3am.
- **`maxUploadSize` enforcement moves into the stream.** A counting `InputStream` wrapper throws
  `PayloadTooLargeException` past the cap. Because the handler has not written anything yet, the
  existing 413 catch at `:427` still owns the response — but a handler that has *already* streamed
  bytes to S3 when the cap trips will need to clean up, so the exception must be documented as
  "may fire mid-handler."

`req.body()` / `req.formParam(...)` after `bodyStream()` throws `IllegalStateException` with a
message that names the route flag. A drained stream cannot be re-read and silently returning `""`
is how this becomes a data-loss bug.

### Phase 4: Presigned URLs — the architectural fix

The cheapest large-file path is the one where the bytes never enter the app process at all. The
SigV4 machinery in `Storage` (`buildAuthHeader`, `signingKey`, `canonicalUri`) is most of a
query-string signer already:

```java
String url = storage.presignedPut(key, contentType, Duration.ofMinutes(15));
String url = storage.presignedGet(key, Duration.ofMinutes(5));   // private object, temporary access
```

The app hands the browser a signed `PUT` URL, the browser uploads straight to S3/R2, and a small
confirm endpoint records the key. The app's heap, its bandwidth, and `maxUploadSize` are all out of
the picture. Requires a bucket CORS rule, and the key must be app-allocated (`Storage.safeKey`) so
the client cannot choose where it writes.

**For an app hitting this problem right now, this is the recommendation** — it is a small, additive
change to `Storage` with no framework-lifecycle risk, and it removes the constraint rather than
raising it.

---

## Part 2 — Egress

### Phase 5: Streaming `Result`

`writeResult` (`BraceHandler.java:577-596`) branches on a new `StreamResult extends Result` that
carries a Jetty `Content.Source` instead of a `byte[]`, and pumps it with `Content.copy(source,
response, callback)` — async, backpressured, bounded buffer.

```java
Result.stream(InputStream in, String contentType)
Result.stream(InputStream in, String contentType, long contentLength)
Result.file(Path path)                                  // content type from extension
Result.file(Path path, String contentType)
Result.download(Path path, String filename)             // streaming sibling of the byte[] download at Result.java:38
Result.stream(Consumer<OutputStream> writer, String ct) // generated content: CSV, ZIP, NDJSON
```

Known length → `Content-Length`. Unknown → chunked. The `Consumer<OutputStream>` form runs on the
request's virtual thread against `Content.Sink.asOutputStream(response)`; blocking there is fine and
is the simplest correct thing on virtual threads.

Five existing mechanisms assume a materialized body. Each needs an explicit answer:

- **After-middleware** (`BraceHandler.java:512-516`) runs over every response. Header-only
  middleware (`SecurityHeaders.defaults()`) is unaffected. A *body-rewriting* middleware calling
  `result.body()` gets `null` from a `StreamResult` — document it, and consider a
  `result.isStreaming()` predicate so such middleware can pass through deliberately.
- **The page cache** (`Cache.RenderedResponse.from`, `Cache.java:182-192`) snapshots
  `result.rawBytes()`. Caching a stream is meaningless — it must **throw** a named error, not cache
  an empty body. A silently-empty cached page is the worst outcome available here.
- **`materialize()`** (`Result.java:202`, the M12 deferred-render hook) is a no-op for streams; the
  contract is unchanged.
- **Mid-stream failure.** Once the first buffer is written the status line is gone and the response
  cannot become a 500. The only honest signal is to fail the callback and abort the connection so
  the client sees a truncated transfer rather than a well-formed lie. Log a distinct
  `response.stream.failed` event and record it in the error store; do **not** route it into the
  generic 500 path, which would try to write a second response.
- **Stats and logging** (`BraceHandler.java:418-424`) are recorded when `writeResult` returns, which
  for a stream is before the bytes are on the wire. Move the stats/log call into the callback's
  completion for streaming results so a 40-second download is not logged as a 2 ms request.

#### The transaction boundary (the load-bearing restriction)

`db.close()` runs in the `finally` at `BraceHandler.java:388-390`, and `writeResult` is called at
`:417` — **after**. So a `Result` that streams out of the request's `Database` session is reading
from a closed session. This is not a bug to fix; it is the M12 design (render after commit, so
template rendering does not hold a pooled connection) working as intended.

The rule for v1: **streaming sources must not depend on the request's DB session.** Files, S3
objects, and in-memory generators are fine. For a "stream a 2 GB CSV out of Postgres" export, the
writer callback opens its own session from the `DatabaseFactory` and closes it when done — explicit
about the fact that a slow client now holds a connection for the duration.

The alternative — deferring `db.close()` until the response completes — is rejected for v1 because
it makes every slow download hold a pooled connection, turning a slowloris client into pool
exhaustion. If it is ever added it should be an explicit, obviously-named opt-in.

### Phase 6: `Range` requests and streaming static files

Once bodies can stream, `Range` is what makes them *useful* — video seeking, resumable downloads,
and byte-range fetches by CDNs all depend on it.

- Parse `Range: bytes=start-end` (single range only; a multi-range request is served as a full 200,
  which is what most servers do and is spec-legal).
- `206 Partial Content` + `Content-Range: bytes start-end/total`; unsatisfiable → `416` with
  `Content-Range: bytes */total`.
- `Accept-Ranges: bytes` on every streamable result.
- `If-Range` honored against the existing ETag so a stale resumption gets the whole file.

Then `serveStaticFile` (`BraceHandler.java:646`) swaps its `Files.readAllBytes` at `:736` for
`Result.file(...)`, keeping the ETag / `Last-Modified` / 304 logic at `:729-744` exactly as is. No
API change, no config change — static file serving simply stops loading whole files into heap and
starts supporting seeks.

### Phase 7: `Storage` downloads

```java
public InputStream stream(String key)                    // bounded-memory read
public InputStream stream(String key, long start, long end)  // Range pass-through
public byte[] get(String key)                            // small objects, explicit about the cost
```

This is what lets an app serve *private* objects through its own auth without a presigned URL:
`Result.stream(storage.stream(key), contentType)`, with the client's `Range` forwarded to S3 and
S3's `Content-Range` forwarded back.

### Phase 8 (deferred)

- **S3 multipart upload** for objects >5 GB — needed only when Phase 2's single-`PUT` ceiling is hit.
- **`Http` client streaming** — the outbound fluent client buffers responses; same treatment,
  separate problem.
- **Global in-flight upload-bytes cap** — a backstop against the disk-exhaustion mode Phase 1
  introduces.

---

## Phase 0: Verify the Jetty API surface first — DONE (Jetty 12.0.33)

Everything the plan assumed is present and behaves as assumed:

- `Parser.setMaxMemoryFileSize(long)` — `-1` is documented as "unlimited memory file size"
  (`MultiPartFormData.java:640`); a value `>= 0` spills above the threshold. Plus
  `setFilesDirectory(Path)`, `setMaxFileSize(long)`, `setMaxParts(long)`,
  `setUseFilesForPartsWithoutFileName(boolean)`.
- `MultiPart.Part.newContentSource()` returns a **fresh, re-readable** source each call (both
  `ChunksPart` and `PathPart`) — which is what makes Phase 2's hash-then-send two-pass upload
  possible without spooling.
- `Part.writeTo(Path)` is exactly the primitive `saveTo` wants: `Files.move` when the part is
  file-backed, a streaming copy when it is memory-backed, and it sets `temporary = false` so the
  subsequent `close()` does not delete the file it just moved (`MultiPart.java:326-348`).
- `Content.Source.from(Path)` and `from(Path, long offset, long length)` — the Range-limited file
  source. Also `from(InputStream)`.
- `Content.copy(Source, Sink, Callback)` and `Content.Sink.asOutputStream(Sink)`.

Two findings that **change the design**:

1. **Non-file parts fail rather than spill.** For a part with no filename — an ordinary form field —
   `MultiPartFormData.java:765-775` treats `maxMemoryFileSize` as a hard limit and fails the request
   with "max memory file size exceeded" instead of spilling. So setting a 1 MB threshold naively
   would break any app posting a form field larger than 1 MB (a long markdown body, a base64 blob).
   The fix is to pair the threshold with `setUseFilesForPartsWithoutFileName(true)`, so large form
   fields spill like file parts do; Brace still classifies file-vs-field by `getFileName() != null`,
   so nothing downstream changes.
2. **Jetty's own `Parts.close()` is the deleter** — it closes each part, and `Part.close()` runs
   `Files.deleteIfExists` on the backing file (`MultiPart.java:380-387`). This confirms the hazard
   the plan predicted: the existing `finally { parts.close(); }` at `BraceHandler.java:1013-1015`
   would delete every spilled file before the handler ever runs. The `Parts` handle is also the
   natural per-request cleanup token — one `Closeable` covering all parts, rather than tracking
   files individually.

Jetty creates spilled files with `Files.createTempFile(dir, "MultiPart", "")`
(`MultiPartFormData.java:1000`), which is owner-only on POSIX. It calls plain
`Files.createDirectories(dir)` for the directory, though, so **Brace must pre-create the temp
directory with 700** rather than letting Jetty create it under the ambient umask.

## Tests

The compatibility oracle is that **`FileUploadTest` passes unchanged** through Phases 1–2. If it
needs edits, the "fix lands under the existing API" principle has been broken.

New coverage:

- **Bounded memory, the only test that actually proves the thesis.** A forked surefire JVM at
  `-Xmx64m` uploads and then downloads a ~512 MB body and asserts a 200. Tag it so it can be run
  deliberately; it is worth the wall-clock.
- **Temp-file cleanup on every exit path**: 413, CSRF 403, handler throw, `NotFoundException`,
  client disconnect mid-upload, and the happy path. Assert the temp dir is empty after each.
- **`saveTo` across filesystems** (move fast-path vs copy fallback).
- **Range matrix**: full, `0-`, `-500`, `100-199`, past-EOF (416), multi-range (200), `If-Range`
  hit and miss.
- **Mid-stream failure** aborts rather than truncating with a clean 200.
- **Page cache refuses a streaming result** — asserts the throw, not an empty cached body.
- **Streaming route + CSRF**: header token accepted, body token impossible, registration-time error
  when neither is configured.
- **Static file 206** and unchanged 304 behavior.
- **Postgres IT tier** (`*IT`) for the `Storage` paths against a real S3-compatible container
  (MinIO), including the two-pass hash and a Range GET.

## Rollout

Ships in **0.1.8** (`pom.xml` is at `0.1.8-SNAPSHOT`, untagged), alongside the 2026-07 security
review's changes. A dedicated security review runs on the PR before merge — see "Security review
scope" below for what it should aim at, since these phases touch the request lifecycle, the response
choke point, and a temp-file lifecycle that did not previously exist.

Two PRs rather than one, sequenced for reviewability, both landing in 0.1.8:

| PR | Phases | Why together |
|---|---|---|
| 1 | 1, 2, 5, 6 | The whole "large files stop touching heap" story, all non-breaking, no new API to review |
| 2 | 3, 4, 7 | New opt-in API surface: streaming routes, presigned URLs, storage reads |

Splitting this way keeps the temp-file lifecycle (PR 1) and the CSRF-relevant streaming-route flag
(PR 2) in separate diffs — they are the two highest-risk pieces and they fail in unrelated ways.

**Migration guide:** `docs/migrations/brace-0.1.7-to-0.1.8.md` already exists, so these are *added*
to it rather than starting a new guide — new sections plus rows in its Index table at line 30. The
observable behavior changes needing entries: uploads above 1 MB now touch disk (and where the temp
dir lives), static files stream and advertise `Accept-Ranges`, streaming results cannot be page
cached, and `X-CSRF-Token` is mandatory on streaming routes. All are behavior changes with no action
required except the last, which only affects code that opts into `.streaming()`.

**Migration gate:** re-run `./run-migrate.sh --from 0.1.7 --to 0.1.8-SNAPSHOT` in `ai-benchmark`
after these land — the guide will have grown substantially since its last clean pass, and
`fix_attempts: 0` has to still hold against the widened guide. The fixture does not currently
exercise uploads at all; if the gate is to bite on this work, extend
`ai-benchmark/migrate-fixture/` with an upload endpoint and a static-asset fetch.

Docs to update on the way out: `BRACE-AGENTS.md` and `README.md` (new public API, per the
"Updating documentation" convention), `docs/SECURITY.md` §File Uploads (temp-file handling,
streaming-route CSRF rule), and `docs/scaling.md:129-132` — where the L13 caveat gets rewritten from
"known limitation" to "resolved, here's the knob."

### Security review scope

The review on the PR should aim at these specifically, all of which are new attack surface rather
than refinements of existing surface:

- **Temp-file handling.** Directory permissions (700, not umask-dependent), predictable-name
  attacks, symlink attacks on the temp dir, and whether cleanup truly covers every exit from
  `handle()` — a missed path is a disk-fill DoS, not just a leak. The startup sweep must not follow
  symlinks out of the temp dir (same class of bug as the 0.1.8 static-file symlink fix).
- **`saveTo` and app-supplied paths.** The move fast-path means an attacker-influenced destination
  now relocates a file rather than writing bytes; confirm traversal handling is unchanged.
- **CSRF on streaming routes.** The registration-time enforcement is the whole defense. Verify it
  cannot be bypassed by a route registered before the flag, or by a `Content-Type` that makes the
  framework think a body was parsed when it was not.
- **`maxUploadSize` in the streaming path.** The counting wrapper is now the only enforcement point
  for `.streaming()` routes; the Content-Length fast-reject at `BraceHandler.java:934-940` does not
  cover a chunked body.
- **`UNSIGNED-PAYLOAD`** (Phase 2, option 2) if it ships — it removes payload integrity from the
  signature and should be opt-in with that stated plainly.
- **Presigned URLs** (Phase 4) — expiry defaults, whether the client can influence the key, and
  whether a presigned `PUT` can overwrite an existing object.
- **Range parsing** — integer overflow on absurd ranges, negative suffix lengths, and 416 handling
  that does not leak whether a file exists beyond what a 200/404 already leaks.

## Sequencing for an app hitting this today

1. **Phase 4 (presigned URLs)** if browser-direct-to-S3 is acceptable. Smallest change, removes the
   constraint entirely rather than raising it.
2. **Phases 1 + 2** otherwise — fixes the heap under the app's existing handler code, no app-side
   changes beyond possibly raising `maxUploadSize`.
3. **Phases 5 + 6** for the download side, which is where the `Range` support arrives.

## Open questions

- **Is 1 MB the right spill threshold?** It trades a little disk I/O on 1–10 MB uploads for the
  removal of the default-config heap DoS. Worth a quick benchmark before committing to it.
- **Should `.streaming()` be a route flag or inferred from the handler signature?** The `Invoker`
  already inspects signatures at startup (`Invoker`), so a handler declaring an `InputStream`
  parameter could opt in implicitly. Explicit is clearer; implicit is less to remember. Leaning
  explicit, because the CSRF consequence deserves to be visible at the registration site.
- **Does the disk-exhaustion backstop (Phase 8) need to ship with Phase 1** rather than after it?
  Depends on whether ops visibility alone is judged sufficient for the first release.
