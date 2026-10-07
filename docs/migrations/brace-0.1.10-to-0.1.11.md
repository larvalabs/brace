# Migrating from Brace 0.1.10 → 0.1.11

This release has no breaking changes.

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|
| `HEAD` requests reach `GET` routes | fix | ops tooling: expect `HEAD <pattern>` rows | [§](#fix-head-requests-reach-get-routes) |

### Fix: `HEAD` requests reach `GET` routes

Before, a `HEAD` request returned 404 for every route registered with `app.get(...)`, because
routes were matched on the exact method. Only `staticFiles` mappings answered it. Uptime
monitors, link checkers and some link-preview fetchers send `HEAD`, so they saw a site that was
up as down.

```
$ curl -sI -o /dev/null -w '%{http_code}\n' https://example.com/
404   # 0.1.10
200   # 0.1.11
```

Now a `HEAD` request with no `HEAD` route of its own runs the matching `GET` route and gets the
`GET`'s status and headers, `Content-Length` and `ETag` included, with no body (RFC 9110
§9.3.2). Details:

- The handler runs as it does for `GET`. `req.method()` returns `"HEAD"`, so a handler that
  wants to skip expensive work for `HEAD` can check it.
- Streamed bodies (`Result.file`, `Result.stream`, `Result.sse`) are not produced for `HEAD`. An
  SSE producer never starts, and a file is not read.
- A route wrapped in `cache.wrap(...)` serves `HEAD` from the same cache entry as `GET`.
- `/ops/routes` and the request log record these requests as `HEAD`, under their own
  `HEAD <pattern>` row. Expect new rows if a monitor polls your site.

No code changes are needed.
