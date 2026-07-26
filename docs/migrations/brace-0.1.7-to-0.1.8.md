# Migrating from Brace 0.1.7 → 0.1.8

This release combines the findings of the [2026-07 security review](../2026-07-24-security-review-todos.md),
which tightened several framework defaults, with fixes for two ways a durable job could be
lost or wedged forever and a cut to durable-job pickup latency.

Two cases **are breaking** and need action:

- **Session cookies now carry `Secure`** on every non-loopback request. An app deliberately
  served over plain HTTP on a real hostname will stop receiving its session cookie back
  until it opts out with `.secure(false)` — see
  "session cookies are `Secure` by default" below. Local development, `http://localhost`,
  and in-process test suites are unaffected.
- **Ops auth protocol v1 is rejected.** A `brace` CLI older than 0.1.7 can no longer
  authenticate against a 0.1.8 server — see "ops auth v1 removed" below.

The job and mailer changes ship as new **defaults** and need no code change. The only reason
to touch your code for those is if you run jobs longer than 30 minutes, talk to an unusually
slow SMTP relay, or want to tune the poll rate:

- **Durable jobs claimed by an instance that dies are now recovered** instead of being
  stranded permanently.
- **`Mailer` now bounds its SMTP timeouts**, so a wedged relay fails the send instead of
  hanging the calling thread forever.
- **Durable jobs now start as soon as the enqueuing transaction commits**, instead of
  waiting up to 10 seconds for the next poll.

Everything else is a hardening change with no action required.

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|
| Session cookies `Secure` by default | breaking | plain-HTTP prod deployments: add `.secure(false)` | [§](#breaking-session-cookies-are-secure-by-default) |
| Ops auth v1 removed | breaking | upgrade the `brace` CLI to 0.1.7+ | [§](#breaking-ops-auth-protocol-v1-removed) |
| Static files no longer follow symlinks out of the root | behavior change | re-point symlinked assets inside the served directory | [§](#security-fix-static-files-no-longer-follow-symlinks-out-of-the-served-directory) |
| Security headers now cover errors and static files | behavior change | none | [§](#security-fix-security-headers-now-apply-to-static-files-404s-500s-and-413s) |
| `/ops/*` responses are `no-store` | behavior change | none | [§](#security-fix-ops-responses-are-no-store) |
| WebSocket upgrades are `Origin`-checked | behavior change | cross-origin WS clients: declare `wsAllowedOrigins(...)` | [§](#security-fix-websocket-upgrades-are-origin-checked) |
| Request bodies read after before-middleware | behavior change | before-middleware reading `req.body()` still works | [§](#security-fix-request-bodies-are-read-after-before-middleware) |
| `Result.download` escapes the filename | behavior change | none | [§](#security-fix-resultdownload-escapes-the-filename) |
| `Http.multipart` rejects control chars in part names | behavior change | none unless passing raw user filenames | [§](#security-fix-httpmultipart-rejects-control-characters-in-part-names) |
| `Result.cookie` rejects invalid names/values | behavior change | none unless setting cookies from raw user input | [§](#security-fix-resultcookie-validates-names-and-values) |
| Ops session cookie scoped to `/ops` | behavior change | none | [§](#security-fix-ops-session-cookie-is-scoped-to-ops) |
| `Storage` rejects traversal in keys | behavior change | none unless building keys from user input | [§](#security-fix-storage-rejects-traversal-segments-in-object-keys) |
| Stalled durable jobs are recovered | new default | raise `jobLease(...)` if jobs run >30 min | [§](#stalled-durable-jobs-are-recovered-new-default) |
| Durable jobs start on enqueue | new default | none; tune `jobPollInterval(...)` if multi-instance | [§](#durable-jobs-start-on-enqueue-not-on-the-next-poll-new-default) |
| Mailer SMTP timeouts bounded | new default | raise timeouts for a slow relay | [§](#mailer-smtp-timeouts-are-bounded-new-default) |
| Bundled htmx 2.0.4 → 2.0.10 | dependency bump | none | [§](#bundled-htmx-is-now-2010) |
| Large uploads spill to disk | behavior change | budget disk; point `uploadTempDir(...)` at a real volume | [§](#large-uploads-now-spill-to-disk-new-default) |
| `Storage.put` streams | behavior change | none | [§](#storageput-streams-instead-of-buffering) |
| `Storage` write paths were broken | bug fix | none — they now work | [§](#bug-fix-storage-put-and-delete-threw-on-every-call) |
| Oversized multipart is 413, not 500 | bug fix | none | [§](#bug-fix-oversized-multipart-uploads-return-413-instead-of-500) |
| Streaming responses (`Result.file`/`stream`) | new capability | none | [§](#new-streaming-responses-and-range-support) |
| Static files stream and support `Range` | behavior change | none | [§](#new-streaming-responses-and-range-support) |

---

## Breaking: session cookies are `Secure` by default

**What changed.** `SessionOptions` no longer defaults `secure` to `false`. With no explicit
setting the `Secure` attribute is now resolved **per request**: on for every request, off
only when the request's `Host` is a loopback address (`localhost`, `127.0.0.0/8`, `::1`).
An `X-Forwarded-Proto: https` from a **trusted** proxy forces it on regardless of `Host`.

**Why.** Brace serves cleartext HTTP/1.1 and expects TLS to be terminated by a reverse
proxy, so it cannot read the scheme off its own connector. The old fixed `false` meant
every app built the documented way — `.sessions(secret)` — shipped a session cookie with no
`Secure` attribute, and the cookie *is* the session: any cleartext request to the domain
(a typed `http://` URL, a stale bookmark, a first visit before HSTS is pinned) handed it to
a network attacker. A fixed `true` would have been no better — it silently breaks every app
and test suite on `http://localhost` — hence the per-request resolution.

**Who needs to act.** Only an app served over **plain HTTP on a real hostname** in
production. It will now set a `Secure` cookie the browser refuses to send back, so sessions
appear to reset on every request.

**Before (0.1.7) — cookie had no `Secure` attribute:**

```java
var app = Brace.app().sessions(System.getenv("SESSION_SECRET"));
// Set-Cookie: brace_session=...; Path=/; HttpOnly; SameSite=Lax
```

**After (0.1.8) — same code, `Secure` added off-loopback:**

```java
var app = Brace.app().sessions(System.getenv("SESSION_SECRET"));
// Set-Cookie: brace_session=...; Path=/; HttpOnly; Secure; SameSite=Lax
```

**Opting out** (plain-HTTP deployment on a private network, a prod smoke test):

```java
var app = Brace.app()
    .sessions(SessionOptions.of(System.getenv("SESSION_SECRET")).secure(false));
```

Startup logs a warning whenever that opt-out is in effect, so the state is visible rather
than silent. `SessionOptions.secure(secret)` and `.sameSiteNone()` still force `Secure` on,
and an explicit `.secure(true)` / `.secure(false)` always wins over the per-request
resolution.

**Tests are unaffected.** `Brace.test()` and any suite hitting `http://localhost` keep
working with no change: a loopback `Host` resolves to no `Secure` attribute.

**If you set `Secure` explicitly already**, nothing changes — your value still wins.

---

## Breaking: ops auth protocol v1 removed

Protocol v1 signed the timestamp alone: the signature was not bound to the public key and
carried no nonce, so a captured `/ops/auth` request could be replayed verbatim by anyone
within the ±30 s acceptance window to mint a fresh token at the key's full scope ceiling.

v1 shipped in 0.1.6 and was deprecated in 0.1.7, which stated that a future release would
reject it. This is that release: a v1 body (no `v` field, or `v: "1"`) now gets a 401 naming
the cause.

**Who needs to act.** Anyone still running a `brace` CLI older than 0.1.7, or a hand-rolled
`/ops/auth` client that has not moved to v2.

**Upgrade the CLI** — 0.1.7 and later send v2 natively. (The CLI's own v1 fallback, which
retried with a v1 body when a pre-0.1.7 server rejected v2, is removed too: a 401 now means
what it says instead of silently downgrading to a replayable protocol.)

**Hand-rolled clients** sign `publicKey + "\n" + timestamp + "\n" + nonce` and send the
envelope:

```jsonc
// Before (v1) — removed
{ "publicKey": "...", "timestamp": "2026-07-24T12:00:00Z", "signature": "<sign(timestamp)>" }

// After (v2)
{
  "v": "2",
  "publicKey": "...",
  "timestamp": "2026-07-24T12:00:00Z",
  "nonce": "<base64url of 16+ random bytes, fresh per attempt>",
  "signature": "<sign(publicKey + \"\\n\" + timestamp + \"\\n\" + nonce)>"
}
```

`OpsKeys.v2AuthMessage(publicKey, timestamp, nonce)` builds the canonical signed message if
you are calling from Java.

---

## Security fix: static files no longer follow symlinks out of the served directory

`app.staticFiles(prefix, dir)` checked containment with `Path.normalize()` +
`startsWith(baseDir)`. That is a lexical check — it collapses `..` in the path *string* and
does not resolve symlinks — while the file read follows them. A symlink anywhere under a
served directory therefore served whatever it pointed at, **including files outside the web
root**.

Containment is now re-checked against the link-resolved paths (`toRealPath()` on both the
candidate and the base directory).

**Action required only if** you relied on a symlink pointing *out* of a served directory —
e.g. `public/uploads` → `/var/app/uploads`. That now returns 404. Either move the target
inside the served tree, or register the target directory as its own mapping:

```java
// Before: public/uploads was a symlink to /var/app/uploads
app.staticFiles("/assets", "public");

// After: serve the real directory directly
app.staticFiles("/assets", "public");
app.staticFiles("/assets/uploads", "/var/app/uploads");
```

Symlinks that stay **inside** the served directory keep working unchanged.

---

## Security fix: request bodies are read after before-middleware

The request body (including multipart parsing) used to be buffered *before* the
`before(...)` middleware chain ran, so a rate limiter or auth guard could not reject a
request until up to `maxUploadSize` had already been read into the heap — with handlers on
virtual threads, nothing bounded how many of those were in flight at once.

The body is now read lazily, after the guards have run. **No action required.**

Two consequences worth knowing:

- A request rejected by before-middleware now returns the **guard's** status rather than
  `413`, even when the body was oversized. Previously an oversized body on a guarded route
  returned `413` before the guard was consulted.
- Middleware that genuinely needs the body — a webhook signature check, say — still works
  unchanged; reading `req.body()` inside a guard triggers the read at that point.

```java
// Still works: the body read happens on access, inside the guard.
app.before("/webhooks/*", req ->
    verifySignature(req.header("X-Signature"), req.body()) ? null : Result.unauthorized());
```

The `maxUploadSize` cap itself is unchanged: a request that reaches a handler with an
oversized body still gets `413`.

---

## Security fix: security headers now apply to static files, 404s, 500s and 413s

After-middleware — including `app.after(SecurityHeaders.defaults())` — used to run only on
the normal handler path. Every other response left the server undecorated: static files
(which got `nosniff` and nothing else), unmatched-route 404s, handler-thrown 404s, CSRF
403s, 500s, and `413 Payload Too Large`. An app that followed the documented hardening step
had no `X-Frame-Options`, `Referrer-Policy`, or CSP on exactly the responses that most need
them.

After-middleware now runs for every response leaving the handler. **No action required.**

Path-scoped middleware is unaffected — `app.after("/admin/*", …)` still only fires for
matching paths, including for static files and error responses under that prefix.

One nuance: on the 404-thrown and 500 paths, an after-middleware that itself throws is
logged and skipped rather than replacing the error response. On all other paths a throwing
after-middleware surfaces as a 500, as before.

---

## Security fix: WebSocket upgrades are `Origin`-checked

`app.ws(path, …)` accepted an upgrade from any origin and then decrypted the
`brace_session` cookie straight into the handler's `WsContext`. A WebSocket handshake is
not subject to the same-origin policy, so an attacker's page could open a socket to your
app, have the browser attach the victim's session cookie, and hold an authenticated socket
for its lifetime — cross-site WebSocket hijacking. The only thing standing in the way was
the default `SameSite=Lax` cookie, which an app disables the moment it calls
`sameSiteNone()`.

Upgrades whose `Origin` names a different host are now rejected with 403.

**No action required** for same-origin browser clients or for non-browser clients (a
missing `Origin` is allowed — only browsers send one, and only browsers need the check).
The host is compared without scheme or port, so TLS terminated at a proxy is fine.

**Action required** only for a deliberately cross-origin browser client:

```java
var app = Brace.app()
    .wsAllowedOrigins("https://studio.example.com")   // full origin, or a bare host
    .ws("/live", ctx -> new LiveHandler(ctx));
```

Pass `"*"` to disable the check entirely — sound only if the socket carries no ambient
authority (i.e. it does not rely on the session cookie for authorization).

---

## Security fix: `/ops/*` responses are `no-store`

Only `/ops/auth/exchange` sent `Cache-Control: no-store`. Every other ops endpoint relied
on the caller's credential channel to suppress caching — which RFC 9111 guarantees for the
CLI's `Authorization` header, but *not* for the `__brace_ops_session` cookie a browser
uses. A response with no `Cache-Control` is heuristically cacheable, and `/ops/dashboard`
embeds a live bearer token in its HTML, so an intermediary could store one operator's
dashboard and serve it — token included — to the next requester.

Every `/ops/*` response now carries `Cache-Control: no-store`. **No action required.**

---

## Security fix: `Result.download` escapes the filename

`Result.download(bytes, contentType, filename)` interpolated the filename raw into
`Content-Disposition: attachment; filename="…"`. A name containing a double quote closed the
quoted-string early and everything after it was parsed by the client as further parameters —
`Result.download(b, "text/plain", "x\"; name=\"y")` put
`Content-Disposition: attachment; filename="x"; name="y"` on the wire. Serving a user-uploaded
file under its original name is the method's primary use case, which is exactly where the
value is attacker-supplied.

The header is now built safely and emits the RFC 6266 pair — an ASCII-safe `filename="…"`
plus `filename*=UTF-8''…` carrying the exact name. Directory components are dropped (a
download name is a leaf), and characters that can't appear literally in a quoted-string are
replaced with `_` in the ASCII form.

**No action required.** One visible improvement: non-ASCII filenames now reach the browser
intact instead of being mangled.

```java
Result.download(pdf, "application/pdf", "отчёт.pdf");
// Content-Disposition: attachment; filename="______.pdf"; filename*=UTF-8''%D0%BE%D1%82%D1%87%D1%91%D1%82.pdf
```

---

## Security fix: `Http.multipart` rejects control characters in part names

`Http.post(url).multipart().field(name, bytes, filename)` wrote part names and filenames into
the multipart headers verbatim, so CR/LF in either value terminated the part headers and let
the caller forge **additional parts** in the outbound request — parameter smuggling into
whatever third-party API the app was calling (adding a `visibility=public` part to an upload,
say).

Quotes and backslashes are now escaped per RFC 7578, and a control character throws
`IllegalArgumentException` rather than being silently stripped: for an outbound API call, a
request that quietly differs from what the caller asked for is worse than one that doesn't
happen.

**Action required only if** you pass raw user-supplied filenames straight through. Sanitize
or catch:

```java
// A filename from an upload — now rejected loudly instead of smuggling parts.
var safeName = upload.filename().replaceAll("[\\p{Cntrl}]", "");
Http.post(url).multipart().field("file", upload.bytes(), safeName).fetch();
```

---

## Security fix: `Result.cookie` validates names and values

The cookie value was appended raw ahead of the framework's own attributes, so a `;` in it
injected cookie attributes: a value of `1; Path=/; Domain=evil` produced
`Set-Cookie: c=1; Path=/; Domain=evil; Max-Age=60; …`. A handler setting a cookie from user
input — a theme, a locale, a `returnTo` — could have that cookie re-scoped by the input.

`Result.cookie(...)` now throws `IllegalArgumentException` for a value containing control
characters, spaces, quotes, backslashes, `;` or `,`, and for a name that isn't an RFC 6265
token.

**Action required only if** you pass raw user input as a cookie value. URL-encode it:

```java
// Before: could inject attributes
result.cookie("returnTo", req.queryParam("next"), 600, true, true, "Lax");

// After
result.cookie("returnTo",
    URLEncoder.encode(req.queryParam("next"), StandardCharsets.UTF_8),
    600, true, true, "Lax");
```

There is also a new overload taking an explicit `path`, so a cookie can be scoped to a
subtree instead of the hardcoded `/`:

```java
result.cookie(name, value, maxAge, httpOnly, secure, sameSite, "/admin");
```

---

## Security fix: ops session cookie is scoped to `/ops`

`__brace_ops_session` was set with `Path=/`, so an operator credential was attached to every
request to the application — readable by any handler through `req.cookie(...)` and captured
by any request logging the app does. Every endpoint that accepts it lives under `/ops`, so
that is where it is now scoped. **No action required**; existing sessions simply re-issue on
next login.

---

## Security fix: `Storage` rejects traversal segments in object keys

`Storage.uriEncodePath` percent-encodes each `/`-separated segment, but `.` is unreserved, so
a `..` segment survived encoding intact — and `buildUploadUrl` and `canonicalUri` built the
same unnormalized path, meaning the request was *validly signed* for the traversed key. An
endpoint that normalizes the path would then act on it.

`put`, `delete`, and `url` now throw `IllegalArgumentException` for a key with a `.` or `..`
segment or a leading `/`.

**Action required only if** you assemble keys from user input. `Storage.safeKey` /
`putGenerated` were never affected (UUID keys):

```java
// Safe — the extension is sanitized and the name is a UUID
var stored = storage.putGenerated("avatars", upload);
```

---

## Smaller hardening (no action required)

- **`Csrf.validateToken`** compares with an explicit UTF-8 encoding instead of the platform
  default charset.
- **`Csrf.hiddenField`** HTML-escapes the token value.
- **`/ops/auth`** returns `400` for a non-positive `ttlSeconds` instead of minting an
  already-expired token that then fails with a confusing `401`.
- A dead clause in the weak-secret startup check (a mixed-case literal compared against a
  lowercased string, so it could never match) was removed; the `change-me` checks beside it
  already covered the scaffold placeholder.

---

## Stalled durable jobs are recovered (new default)

### What was wrong

`JobPoller` commits `started_at` before running a job body, so other instances skip the row.
Every terminal outcome — completed, failed, released-for-retry — is written *after* the body
runs. If the process died in between, the row was left with `started_at` set and both terminal
timestamps NULL. No claim predicate matched it (all of them require `started_at IS NULL`), and
`purgeFinishedJobs` never deleted it (it filters on the terminal timestamps). The job was
permanently invisible and permanently undeletable.

This was not limited to crashes. `Brace.stop()` halts the poll loop but never joins the per-job
virtual threads, and virtual threads are always daemon — so **JVM exit kills in-flight jobs
mid-run, and every ordinary deploy could strand up to `poolSize / 2` jobs per instance.**

The knock-on effects compounded: a stranded parent blocked its entire `depends_on_id` subtree
forever, and those permanently-blocked children sat at the head of the claim query's `run_at`
ordering, taxing every subsequent poll on every instance.

### What changed

Jobs now hold their claim under a **lease**, default **30 minutes**. A background sweeper returns
expired claims to the queue:

- **Attempts remain** → `started_at` is cleared and the job is claimable again. The attempt the
  original claim spent is *not* refunded, so a job that strands repeatedly exhausts its budget
  rather than looping forever.
- **Attempts exhausted** → `failed_at` is set, matching what a live job writes when it runs out of
  retries. The row becomes terminal and prunable.

Nothing in the claim path changed — recovered rows re-enter through the ordinary query and index.

### What you may need to do

**If all your jobs finish well within 30 minutes: nothing.**

A lease cannot distinguish a dead instance from a job that is merely slow, so a job still running
when its lease expires **will be picked up again elsewhere**. This is consistent with the
at-least-once contract `DurableJob` already carried, but it makes idempotency matter in a case
where it previously didn't come up.

If you have long-running jobs, raise the lease above your longest expected runtime. It takes either
an interval string (`"30s"`, `"15m"`, `"2h"` — the same format `every()` uses) or a `Duration`:

```java
// Before (0.1.7): no lease existed; a killed job was stranded forever.
var app = Brace.app()
    .database(dbFactory);

// After (0.1.8): default is 30 minutes. Raise it if jobs run longer.
var app = Brace.app()
    .database(dbFactory)
    .jobLease("2h");                    // nightly report job takes ~90 min
```

Because it accepts a string, the lease can be tuned per environment from your existing config
without a code change — `Config.get` falls back to an environment variable, so this reads
`jobs.lease` from the conf file or `JOBS_LEASE` from the environment:

```java
var app = Brace.app()
    .database(dbFactory)
    .jobLease(config.get("jobs.lease", "30m"));
```

```
# brace.conf — a longer lease in production, where the nightly rollup runs
jobs.lease=30m
%prod.jobs.lease=2h
```

Keep the `"30m"` fallback in the `config.get` call. A null or blank string is treated as "keep the
default" rather than "disable", specifically so a missing key can't silently turn off recovery —
but relying on that is less clear than stating the default at the call site.

To keep the 0.1.7 behavior exactly — no recovery, stranded jobs stay stranded:

```java
var app = Brace.app()
    .database(dbFactory)
    .jobLease("0s");        // or jobLease((Duration) null)
```

### Cleaning up rows stranded before the upgrade

Jobs stranded by earlier deploys are still in `scheduled_jobs`. On startup the sweeper picks them
up automatically — they are indistinguishable from a fresh stall — so they will be retried (or
failed, if their attempts were spent) within a sweep interval of the first 0.1.8 boot.

**If you'd rather inspect them first**, before upgrading:

```sql
SELECT id, name, started_at, attempts, max_attempts
FROM scheduled_jobs
WHERE started_at IS NOT NULL AND completed_at IS NULL AND failed_at IS NULL
ORDER BY started_at;
```

Anything with an old `started_at` is stranded work. If some of it is stale enough that re-running
would be wrong (an expired promotional email, say), mark those rows failed before upgrading:

```sql
UPDATE scheduled_jobs SET failed_at = CURRENT_TIMESTAMP, error = 'abandoned before 0.1.8 upgrade'
WHERE started_at < '2026-01-01' AND completed_at IS NULL AND failed_at IS NULL;
```

### New framework migration

`V16__brace_scheduled_jobs_stalled_index.sql` (Postgres only) adds a partial index over
currently-claimed, unfinished rows so the sweep is an index scan rather than a sequential one. It
applies automatically on startup. In steady state the index holds at most `poolSize / 2` rows per
instance, so it costs effectively nothing to maintain.

### Manual sweeps

`JobPoller.reclaimStalledJobs(db, cutoff)` is public if you want to run recovery on your own
schedule (it returns `SweepResult(reclaimed, failed)`), the same way `purgeFinishedJobs` is public
for custom retention.

---

## Durable jobs start on enqueue, not on the next poll (new default)

### What changed

`Jobs.schedule(db, job, Duration.ZERO)` now wakes the poller directly, as an after-commit hook on
the transaction doing the scheduling. A job with no delay starts as soon as your transaction
commits rather than waiting for the next poll — previously up to 10 seconds on an idle app.

The wake fires *after* the commit deliberately. `schedule` runs inside the caller's transaction
(usually a web request's), so waking any sooner would have the poller look under READ COMMITTED
before the row exists — it would find nothing and go back to sleep, which is worse than not waking,
because it also consumes the poll that would otherwise have found the job.

Polling continues underneath, now at **5 seconds** (was 10). It is a safety net rather than the
primary path, covering the five cases a wake cannot reach:

- jobs scheduled with a delay, whose `run_at` is in the future
- retries whose backoff has expired
- rows returned to the queue by the new stalled-job sweeper
- work enqueued on a **different** instance (the wake is in-process only)
- anything already queued when the app starts

A missed wake costs latency, never correctness — polling still finds the job.

The wait after a *partial* batch (previously a separate 1-second tier) is now the same configured
interval, so the setting means what it says rather than applying only to the fully-idle case.

### What you may need to do

**For most apps: nothing.** Job pickup gets faster and background query volume roughly halves.

Tune the interval if either of these applies:

- **Multi-instance with bursty load.** The wake is in-process, so a job enqueued on instance A
  while A's slots are full waits for a poll before an idle instance B can take it. Lower the
  interval if that matters.
- **A high-RTT database, or wide `depends_on_id` fan-out.** Dependency-blocked children sit in the
  claim index with `run_at` in the past, and the scan walks them before reaching claimable work.
  Raise the interval if you routinely have thousands of jobs blocked behind one unfinished parent.

```java
var app = Brace.app()
    .database(dbFactory)
    .jobPollInterval("2s");                                 // or a Duration

// or from config, like the lease:
var app = Brace.app()
    .database(dbFactory)
    .jobPollInterval(config.get("jobs.poll-interval", "5s"));
```

The interval must be positive; there is no disable value, since zero would spin the poll loop
against the database. To restore 0.1.7's cadence exactly, use `"10s"` — note this also makes
partial batches wait 10 seconds, which 0.1.7 did not.

### New: after-commit hooks

The mechanism behind the wake is public API: `db.afterCommit(Runnable)` runs an action after the
session's next successful commit, or drops it if the transaction rolls back. Useful for any side
effect that must not fire until the data it describes is visible to other sessions — enqueuing
external work, invalidating a shared cache, sending a notification. Hook exceptions are logged, not
propagated, since the transaction has already succeeded.

### What this does *not* fix

Chained jobs still advance one poll interval per link. A child is invisible to the claim query
until its parent's completion commits, and completing a job does not fire an enqueue wake, so a
5-deep chain on an idle app takes about 5 poll intervals end to end.

---

## Mailer SMTP timeouts are bounded (new default)

### What was wrong

`Mailer` set no SMTP timeouts, and Jakarta Mail defaults every one of them to **infinite**. A
blackholed or wedged relay hung `Transport.send` forever.

That was worse than a slow send. `send()` is synchronous, and the common case is sending from a
`DurableJob` — where the hung job held both its `JobPoller` execution slot and a pooled database
connection for the entire hang. With the default pool of 10 (so 5 job slots), five hung sends
wedged the whole job system permanently, with no recovery short of a restart.

### What changed

Defaults are now **10s connect** and **30s per read/write**, matching `Http`. A wedged relay now
produces an ordinary send failure — which `sendAsync()` logs and counts, and which the durable job
queue retries with backoff.

### What you may need to do

**For most apps: nothing.** Typical SMTP sends complete in 100ms–2s.

If you use a slow relay or send large attachments, raise the timeouts:

```java
// Before (0.1.7): unbounded — a wedged relay hung the caller forever.
var mail = new Mailer(config.get("smtp.url")).from("noreply@app.com");

// After (0.1.8): 10s connect / 30s per operation by default. Raise if your relay is slow.
var mail = new Mailer(config.get("smtp.url"))
    .from("noreply@app.com")
    .connectTimeout(Duration.ofSeconds(20))
    .timeout(Duration.ofMinutes(2));
```

Note `timeout()` bounds each individual socket read/write, not the send as a whole, so it does not
need to cover total transfer time for a large message — only the slowest single operation.

If a previously-hanging send now surfaces as a failure, that is the fix working: the error was
always there, it just used to present as a stuck thread instead of an exception.

---

## Large uploads now spill to disk (new default)

**What changed.** Multipart parsing used to be configured with `setMaxMemoryFileSize(-1)` —
"unlimited memory file size" — so every uploaded part was held in the heap for the whole request.
Parts over the new `uploadMemoryThreshold` (default **1MB**) are now written to a temp file instead.

**Why.** The old shape cost `concurrent uploads × maxUploadSize × ~2` in heap, with virtual threads
leaving concurrency unbounded. The default configuration could be made to pin 10MB of heap per
in-flight request by any client.

**Action required: none for your code.** `UploadedFile.bytes()` still works — it reads the file back
— so existing handlers are unaffected.

**Action required for your deployment:** the spill directory needs room for
`concurrent uploads × maxUploadSize`. It defaults to `${java.io.tmpdir}/brace-uploads`, which on a
container with a small writable layer may not be where you want it.

```java
app.maxUploadSize("500M")                            // accept large media
   .uploadMemoryThreshold("256K")                    // ...without holding it in heap
   .uploadTempDir(Path.of("/var/lib/myapp/uploads")); // ...on a volume with room
```

New `UploadedFile` methods, all bounded-memory:

```java
// Before — materializes the whole upload in heap
byte[] data = file.bytes();
storage.put(key, file.bytes(), file.contentType());

// After — nothing materializes
try (var in = file.stream()) { ... }   // repeatable
file.transferTo(outputStream);
file.saveTo(path);                     // a filesystem move for a spilled part
storage.put(key, file);                // streams
```

`bytes()` is still correct for small uploads and is not deprecated. It is simply the one method that
costs the whole object in heap, so it is the wrong choice once uploads get large.

**One lifetime rule:** an `UploadedFile` is valid only for the duration of its request. Spill files
are deleted when the handler returns, so save or upload the file before then rather than handing it
to a background job. Reading one afterwards throws a message saying so.

## `Storage.put` streams instead of buffering

`Storage.put(key, UploadedFile)` and `putGenerated(...)` now stream: the payload is hashed and sent
without being read into the heap, so an upload that spilled to disk goes to S3 without a heap round
trip. New `put(key, Path, contentType)` for content already on disk. `put(key, byte[], contentType)`
is unchanged and still buffers by definition.

Objects over S3's 5 GiB single-`PUT` ceiling are now rejected with a message naming that limit,
rather than failing opaquely at the endpoint after a long upload. Brace does not implement the
multipart upload API; upload such objects to the bucket directly.

## Bug fix: `Storage` put and delete threw on every call

**Both `Storage.put(...)` and `Storage.delete(...)` were non-functional in every release that
shipped them.** They set the `Host` header explicitly, and `Host` is on the JDK HttpClient's
restricted list, so each call threw:

```
IllegalArgumentException: restricted header name: "Host"
```

unless the JVM happened to be started with `-Djdk.httpclient.allowRestrictedHeaders=host`.

**Action required: none.** The header is no longer set; the client derives `Host` from the request
URI, and the SigV4 signature is built from that same authority, so signing still matches. If you
added `-Djdk.httpclient.allowRestrictedHeaders=host` to work around this, you can drop it.

## Bug fix: oversized multipart uploads return 413 instead of 500

The `Content-Length` fast-reject ran only for non-multipart bodies, so an oversized **multipart**
upload reached Jetty's internal cap and surfaced as a `500` — recording a framework error and
feeding the regression notifier every time. An unauthenticated client could flood both just by
POSTing large files. Oversized uploads are now `413` with no error recorded, matching what the
documentation already claimed and what non-multipart bodies already did.

## New: streaming responses and `Range` support

Response bodies can now be streamed rather than materialized:

```java
Result.file(path)                          // Content-Length, Range, type from extension
Result.file(path, "video/mp4")
Result.download(path, "report.csv")        // streaming Content-Disposition attachment
Result.stream(inputStream, "image/png")    // unknown length, chunked
Result.stream(inputStream, "image/png", n) // known length
Result.stream(out -> { ... }, "text/csv")  // generated as it is produced
```

**Static files stream too**, with no code change on your side. Serving a large asset no longer costs
its full size in heap per concurrent request, and responses now carry `Accept-Ranges: bytes` and
answer `Range` requests with `206` — so seeking in a served video works instead of re-fetching from
the start. Conditional GETs (`ETag` / `If-None-Match` / `304`) behave exactly as before.

Three constraints are worth knowing before you reach for a streaming response:

1. **It cannot be page cached.** `Cache.wrap` over a streaming result throws rather than caching an
   empty body. Cache the underlying data and build the response per request.
2. **It cannot read from the request's `Database`.** The transaction commits and its connection
   returns to the pool before the response is written. For a large export, open a dedicated session
   inside the writer — and note that a slow client then holds that connection for the download.
3. **It cannot change status mid-stream.** Once bytes are on the wire the status line is gone, so a
   source that fails partway aborts the connection. That is deliberate: the alternative is a clean
   `200` carrying a silently truncated body.

An after-middleware that rewrites response bodies should check `result.isStreaming()` and pass
through — `body()` and `rawBytes()` are null for a streaming result.

## Bundled htmx is now 2.0.10

**What changed.** The htmx served from `/__brace/htmx.min.js` moved from 2.0.4 (Dec 2024) to
2.0.10 (Apr 2026) — the current stable release. There is no framework API change:
`req.isHtmx()`, the automatic `Vary: HX-Request`, and the `Cache` page-key split behave
exactly as before.

**What you need to do.** Nothing. htmx 2.0.5–2.0.10 are bug-fix releases with no breaking
changes to attributes, headers, or events.

The `ETag` on `/__brace/htmx.min.js` is derived from the file's bytes, so browsers and
proxies pick up the new asset on the first request after deploy without a cache bust.

**What you get.** The history cache moved from `localStorage` to `sessionStorage` (2.0.5),
so history DOM snapshots no longer persist across tabs; `parseHTML` uses
`Document.parseHTMLUnsafe()` for Web Components, and `hx-sync`/`htmx:abort` work inside
Shadow DOM (2.0.8); `HX-Location` honors `replace` when `push` is false, and
`hx-disabled-elt` no longer re-enables elements that were already disabled in the source
HTML (2.0.9); settle lookup escapes selectors with `CSS.escape()` (2.0.10).

**If you pin your own htmx** — a CDN `<script>` rather than `/__brace/htmx.min.js` — this
change does not affect you.

**On htmx 4.** htmx 4 is in beta (`4.0.0-beta6` as of this release) and Brace does **not**
bundle it. There is no rush: the htmx maintainers plan to keep 2.x as npm `latest` into
early 2027 and have committed to supporting 2.0 indefinitely. See
[the htmx 4 evaluation](../2026-07-26-htmx-4-evaluation.md) for what adopting it would cost
and what it would buy — the short version is that `HX-Request` is unchanged in v4, so
`req.isHtmx()` and `Vary` survive, but v4 swaps 4xx/5xx responses by default and drops
implicit attribute inheritance, both of which need an audit of app-side htmx code.

---

## Upgrading

Bump `<brace.version>` to `0.1.8` and re-run `brace agents-md` to regenerate `BRACE-AGENTS.md`
from the new jar.
