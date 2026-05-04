# ReadDbHandler — Read-Only Database Handler

## Problem

Brace wraps every database handler invocation in an explicit `beginTransaction()` / `commitTransaction()` cycle. Profiling shows `commit()` costs ~170 us per request — a full Postgres round-trip — even for read-only handlers where there's nothing to commit. This accounts for ~46% of the per-request DB overhead.

## Design

Add a `ReadDbHandler` functional interface that signals the handler only reads from the database. BraceHandler skips the transaction lifecycle for these handlers, eliminating the commit round-trip.

### New Interface

```java
// ReadDbHandler.java
package com.larvalabs.brace;

@FunctionalInterface
public interface ReadDbHandler {
    Result apply(Request request, Database database);
}
```

Same signature as `DbHandler`. The type itself is the signal — no annotations, no config.

### Also Add: ReadFullHandler

For handlers that need both a read-only DB and a session:

```java
// ReadFullHandler.java
package com.larvalabs.brace;

@FunctionalInterface
public interface ReadFullHandler {
    Result apply(Request request, Database database, Session session);
}
```

### Invoker Changes

Add `needsReadOnlyDatabase()` to Invoker. Add `fromReadDbFunction()` and `fromReadFullFunction()` factory methods that return invokers where `needsDatabase()` returns true and `needsReadOnlyDatabase()` also returns true.

### Brace Route Registration

Add `get`/`post`/`put`/`delete` overloads for `ReadDbHandler` and `ReadFullHandler` in `Brace.java`, following the existing pattern. Each creates the invoker via the new factory method and registers the route.

### BraceHandler Changes

Replace the single DB block (lines 166-177) with a branch:

```java
if (invoker.needsDatabase() && databaseFactory != null) {
    Database db = new Database(databaseFactory.openSession());
    try {
        if (invoker.needsReadOnlyDatabase()) {
            result = invoker.invoke(braceRequest, db, session);
        } else {
            db.beginTransaction();
            result = invoker.invoke(braceRequest, db, session);
            db.commitTransaction();
        }
    } catch (Exception e) {
        if (!invoker.needsReadOnlyDatabase()) {
            db.rollbackTransaction();
        }
        throw e;
    } finally {
        db.close();
    }
}
```

### DatabaseFactory / HikariCP

No changes. Read-only sessions work fine without autocommit — the implicit transaction from the connection is harmlessly rolled back on `close()` since there's nothing to commit.

## Usage

```java
// Read-only — no transaction overhead
app.get("/db", (ReadDbHandler) (req, db) -> {
    var world = db.find(World.class, id);
    return Json.of(world);
});

// Read-write — automatic transaction (unchanged)
app.get("/updates", (DbHandler) (req, db) -> {
    var world = db.find(World.class, id);
    world.randomNumber = newValue;
    db.update(world);
    return Json.of(world);
});
```

## Files Changed

| File | Change |
|---|---|
| `ReadDbHandler.java` | New file — functional interface |
| `ReadFullHandler.java` | New file — functional interface |
| `Invoker.java` | Add `needsReadOnlyDatabase()`, `fromReadDbFunction()`, `fromReadFullFunction()` |
| `BraceHandler.java` | Branch on read-only in DB lifecycle block |
| `Brace.java` | Add route registration overloads for `ReadDbHandler` and `ReadFullHandler` |

## Expected Impact

Eliminates ~170 us per request on read-only DB endpoints (the `commit()` round-trip). Based on profiling:

- Single Query: ~370 us → ~200 us per request (~45% improvement, theoretical ~5,000 req/s single-threaded)
- Fortunes: similar improvement

Won't close the full gap with Spring's JdbcTemplate (which also has lower query overhead), but removes the most wasteful cost — committing nothing.

## What This Doesn't Change

- `DbHandler` and `FullHandler` behavior is completely unchanged
- No changes to `Database`, `DatabaseFactory`, or HikariCP config
- No new dependencies
- Existing tests unaffected
