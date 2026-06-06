# Migrating from Brace 0.1.6 → 0.1.7

This release has **no breaking changes** — no code changes are required. It does fix a
packaging gap for Postgres (lets most projects **delete a manual dependency**), and adds an
**optional** shared cache backend for multi-server deploys.

## Recommended cleanup: drop the manual `flyway-database-postgresql` dependency

**Background.** Flyway 10 (which Brace uses) split per-database support out of
`flyway-core` into separate modules. `flyway-core` bundles only a few handlers — H2 among
them — but **not** PostgreSQL. Through 0.1.6, Brace declared only `flyway-core`, so an app
running on Postgres failed at startup the moment `DatabaseFactory` ran migrations:

```
org.flywaydb.core.api.FlywayException: No database found to handle jdbc:postgresql://…
```

The standard workaround was to add the missing module — and the Postgres JDBC driver — to
your **own** `pom.xml`. Many Brace+Postgres projects carry exactly that.

**What changed in 0.1.7.** Brace now bundles `flyway-database-postgresql` itself (at
`runtime` scope, so it reaches your app transitively). The PostgreSQL JDBC driver was
already bundled this way. So a Brace project on Postgres needs **no Postgres dependencies of
its own** — Brace brings everything.

**The cleanup.** If your project's `pom.xml` declares either of these only to make Postgres
work, you can remove them:

**Before:**

```xml
<dependencies>
    <dependency>
        <groupId>com.larvalabs</groupId>           <!-- or com.github.larvalabs (JitPack) -->
        <artifactId>brace</artifactId>
        <version>0.1.7</version>
    </dependency>

    <!-- Added by hand to work around the packaging gap — no longer needed: -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.4</version>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
        <version>10.22.0</version>
    </dependency>
</dependencies>
```

**After:**

```xml
<dependencies>
    <dependency>
        <groupId>com.larvalabs</groupId>           <!-- or com.github.larvalabs (JitPack) -->
        <artifactId>brace</artifactId>
        <version>0.1.7</version>
    </dependency>
</dependencies>
```

**This cleanup is optional and safe to skip.** Leaving the explicit declarations in place is
harmless — Maven dedupes them against Brace's transitive versions. One reason to remove them:
a hand-pinned `org.postgresql` version (e.g. `42.7.4`) *overrides* the newer driver Brace
ships, so deleting it lets you track Brace's tested version instead.

**How to apply (mechanical):** in each project's `pom.xml`, delete any `<dependency>` on
`org.flywaydb:flyway-database-postgresql` and any `org.postgresql:postgresql` that you only
added for Brace. Recompile and start the app; migrations should run on Postgres unchanged.

## New (optional): shared cache backend for multi-server consistency

**Nothing to do unless you run more than one server.** The cache is unchanged by default —
in-process, same API, no new dependency or table. This release just adds the *option* to back
it with a shared store.

**Why you'd want it.** The default cache is per-process: on a horizontally-scaled deploy each
server keeps its own copy, so `cache.delete`/`clearTag` only invalidate the box that handled
the write, `cache.incr` counts per-instance (a rate limiter is off by a factor of N), and a
cached page can differ between servers. Opt into a shared, durable, cross-server-consistent
backend with one line — it reuses the Postgres database Brace already requires (no new infra):

**Before (per-process, all versions):**

```java
app.cache(Brace.cache());        // or omit entirely — in-process is the default
```

**After (opt into shared, 0.1.7+):**

```java
app.cache(CacheBackend.postgres(dbFactory));   // shared, durable, cross-server-consistent
```

That's the whole change. The shared backend creates its own table (`brace_cache`) via Brace's
framework migrations — no migration to write. `clear()` becomes a fleet-wide `TRUNCATE`,
`incr` is a single atomic statement, and a page rendered on one server is served by any other.

**Constraints (only on the shared backend; the in-process default has none):** values must be
Jackson-round-trippable (POJOs, records, collections, primitives, String) — a non-serializable
value throws at `set` time; `getOrSet` single-flight is per-server, not global. You can also run
both — keep the in-process default for hot read-through pages and a separate
`new Cache(CacheBackend.postgres(dbFactory))` for counters/invalidation that must be consistent.

**Choose per use case, not per deployment.** Full guidance, including the deferred near-cache
(L1/L2) tier, is in `docs/2026-06-04-brace-shared-cache.md` and the Cache section of
`BRACE-AGENTS.md`.

## Why the Postgres packaging gap went unnoticed until now

Brace's test suite ran entirely on in-memory H2, whose Flyway handler *is* bundled in
`flyway-core` — so the framework's own tests never exercised the real Postgres migration
path and never saw the gap. 0.1.7 adds a Postgres testcontainer test tier (`mvn verify`)
that runs the shipped migrations against real Postgres, which is what surfaced this. See
`docs/2026-06-05-pg-testcontainers.md`.
