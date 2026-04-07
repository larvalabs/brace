# Exception Tracking & Ops Errors Endpoint

## Overview

Persistent exception tracking with a dedicated API for coding agents to pull unresolved errors from production and mark them resolved after fixing.

## Database Schema

Flyway migration:

```sql
CREATE TABLE ops_errors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    error_type VARCHAR(255) NOT NULL,
    message TEXT,
    stack_trace TEXT,
    route VARCHAR(255),
    request_detail TEXT,
    occurrence_count INT DEFAULT 1,
    first_seen TIMESTAMP NOT NULL,
    last_seen TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP
);
```

## ErrorStore Class

New class that handles persistence. Takes a `DatabaseFactory` and opens its own short-lived session for each operation (cannot reuse the request session since it rolled back).

### Recording errors

1. Query for existing unresolved error matching `error_type + route` (dedup key).
2. If found: increment `occurrence_count`, update `last_seen`, `stack_trace`, `message`, `request_detail` to latest values.
3. If not found: insert new row with `occurrence_count = 1`, `first_seen = now`, `last_seen = now`.
4. After insert, check total row count. If over `ops.errors.max` config limit (default 1000), delete oldest rows — resolved errors first, then unresolved.

### Querying errors

- `listErrors(String status)` — returns errors filtered by resolution status, sorted by `last_seen` desc.
- `resolveError(long id)` — sets `resolved_at = now`.

## BraceHandler Changes

### Improved error context

Currently the catch block passes `"?"` for route and request detail. Fix: capture the matched route pattern and request info (method, path, query params) before invoking the handler, so they're available in the catch block.

### Recording flow

On unhandled exception:
1. `Stats.recordError()` called synchronously (in-memory, for `/ops/status` instant visibility).
2. `ErrorStore.record()` fired on a virtual thread (`Thread.startVirtualThread(...)`) — non-blocking, keeps the error response fast, avoids competing with the user's job thread pool.

## Endpoints

### `GET /ops/errors`

Returns unresolved errors by default. Optional `?status=resolved` for resolved errors.

Uses existing `authorize()` method (same auth as other ops endpoints).

Response:

```json
[
  {
    "id": 1,
    "errorType": "NullPointerException",
    "message": "Cannot invoke method on null",
    "stackTrace": "...",
    "route": "GET /posts/{id}",
    "requestDetail": "GET /posts/42?format=json",
    "occurrenceCount": 14,
    "firstSeen": "2026-04-07T10:00:00Z",
    "lastSeen": "2026-04-07T14:30:00Z",
    "resolvedAt": null
  }
]
```

Sorted by `last_seen` descending (most recent activity first).

### `POST /ops/errors/{id}/resolve`

Marks an error as resolved by setting `resolved_at` to now. Returns the updated error record.

If the same `error_type + route` occurs again after resolution, a new row is created (new incident).

## Configuration

| Key | Default | Description |
|---|---|---|
| `ops.errors.max` | 1000 | Maximum rows in `ops_errors` table. Oldest resolved errors pruned first, then oldest unresolved. |

## Design Decisions

- **Virtual thread for persistence** — keeps error recording off the request path. The in-memory `Stats.recordError()` provides instant visibility; the DB write is eventual. If the virtual thread write fails, the error is still visible in-memory via `/ops/status`.
- **Own DB session** — `ErrorStore` opens a fresh session via `DatabaseFactory.openSession()` directly (framework-internal access). The request's session has already rolled back after the exception. This is the same pattern that `db.withSession()` will later expose as a public API for user code.
- **Dedup by error_type + route** — matches existing in-memory dedup in `Stats`. Simple and effective for grouping repeated occurrences of the same bug.
- **Resolved errors create new rows on recurrence** — a resolved error that reappears is a new incident, not a continuation. This lets agents distinguish between "fixed and regressed" vs "never fixed".
- **Row limit, not time-based** — simpler, configurable, and prevents unbounded growth regardless of error rate. Resolved errors are pruned first to preserve unresolved ones.
