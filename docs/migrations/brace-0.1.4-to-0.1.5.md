# Migrating from Brace 0.1.4 → 0.1.5

This release fixes a request-handling deadlock on large request bodies and changes one CLI
default. There are **no breaking changes to any Java public API** (handler interfaces,
`Brace`, `Request`, `Result`, etc. are unchanged). There is **one behavioral change in the
CLI** — read the default-environment section if you script `brace` commands.

## Headline fix: large request bodies no longer hang

In 0.1.4, Jetty was configured with a synchronous executor (`Runnable::run`) for its virtual
thread executor, which made Jetty *think* virtual threads were enabled while actually running
handlers inline on the producer thread. A blocking multi-chunk body read (reading a large
request body, or a JDBC query mid-read) could deadlock the producer until the idle timeout
fired.

0.1.5 wires in a real virtual-thread executor, so a blocking call parks the virtual thread
and frees its carrier to keep reading body chunks:

```java
// Before (0.1.4)
var threadPool = new QueuedThreadPool();
threadPool.setVirtualThreadsExecutor(Runnable::run);

// After (0.1.5)
var threadPool = new QueuedThreadPool();
var virtualThreads = VirtualThreads.getDefaultVirtualThreadsExecutor();
if (virtualThreads != null) {
    threadPool.setVirtualThreadsExecutor(virtualThreads);
}
```

**Impact:** apps that accept large or multi-chunk request bodies (file uploads, large
JSON/form posts) that previously hung until timeout now work. No application code change is
required — upgrade and the fix applies.

## Behavioral change: CLI default env is now `prod` when `ops.prod.url` is set

Previously the CLI defaulted `--env` to `local` whenever `ops.env` was unset. Now, if
`ops.env` is not set **and** `ops.prod.url` is configured (non-empty) in `.brace`, commands
default to `prod`. If `ops.prod.url` is absent/empty it still falls back to `local`.

Resolution order: `--env` flag → `ops.env` config key → (`prod` if `ops.prod.url` is set,
else `local`).

```bash
# Before (0.1.4)
$ brace status        # always targeted local when ops.env was unset

# After (0.1.5)
$ brace status        # targets prod if ops.prod.url is set; use --env local for localhost
```

**To restore the old behavior**, set `ops.env=local` explicitly in `.brace.local` (or pass
`--env local`).

Newly-initialized projects pick this up automatically: `brace init` now writes `ops.env`
**commented out** in `.brace.local` (it used to write an active `ops.env=local`), so the
prod-by-default logic takes effect:

```
# .brace.local generated in 0.1.5
ops.key=ops-private.key

# Default env: prod when ops.prod.url is configured, else local.
# Uncomment to override.
# ops.env=local
```

Existing projects are unaffected by the `init` change — it only writes when the file is
absent. `brace --help` now documents the rule:

```
Default env: prod when ops.prod.url is configured, else local.
```
