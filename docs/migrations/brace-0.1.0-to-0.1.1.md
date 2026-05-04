# Migrating from Brace 0.1.0 → 0.1.1

This release adds new APIs (HTTP client multipart/binary, asset fingerprinting, async
tasks, custom job messages) and contains **one breaking change** to the `Job` interface
signature. Brace is pre-1.0, so the API surface is allowed to change in service of
clarity. All other changes are additive.

## Breaking changes

### `Job.run` now receives a `JobContext`

The `Job` functional interface signature changed from `run(Database)` to
`run(Database, JobContext)`. Every recurring job lambda registered with
`app.every(...)` or `app.daily(...)` must add a `ctx` parameter.

The new `JobContext` lets a job report a short status message that's shown on the ops
dashboard alongside its last-run timestamp.

**Before:**

```java
app.every("5m", "cleanup", db -> db.sql("DELETE FROM expired WHERE ts < NOW()"));
app.daily("02:00", "digest", db -> sendDigest(db));
```

**After:**

```java
app.every("5m", "cleanup", (db, ctx) -> db.sql("DELETE FROM expired WHERE ts < NOW()"));
app.daily("02:00", "digest", (db, ctx) -> sendDigest(db));
```

The `ctx` parameter can be ignored if you don't need it. To use it:

```java
app.every("30s", "fetch-listings", (db, ctx) -> {
    int n = fetchAndStore(db);
    ctx.message("Retrieved " + n + " new listings");  // shown on /ops/dashboard
});
```

**Mechanical fix:** find every `db ->` registered as a job and rewrite to `(db, ctx) ->`.
A regex search like `\.(every|daily)\([^,]+,[^,]+,\s*db\s*->` will catch them.

`DurableJob` is unchanged.

## New APIs (additive — no migration required)

- **HTTP client multipart + binary bodies** — `Http.post(url).bodyBytes(bytes, contentType)`
  and `Http.post(url).multipart().field(...)` for S3/R2 uploads, image APIs, etc.
- **Asset fingerprinting** — `Assets.url("/assets/app.css")` returns
  `/assets/app.css?v=<8-char-md5>` for cache busting. Hash cached per `(path, mtime)`.
- **Async tasks** — `Jobs.run(Runnable)` and `Jobs.submit(Callable<T>)` for
  fire-and-forget background work on virtual threads. Counters available via
  `Jobs.asyncSubmitted()` / `Jobs.asyncFailed()`.
- **Custom job messages** — see breaking change above.

See `BRACE-AGENTS.md` for full documentation of each new API.
