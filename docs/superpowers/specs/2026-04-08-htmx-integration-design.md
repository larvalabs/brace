# htmx Integration — Design Spec

## Overview

Add built-in htmx support to Brace, enabling dynamic page updates without writing JavaScript. The approach follows the Rails/Turbo model: handlers always return full pages, and htmx on the client extracts and swaps the elements it needs via `hx-select`. Developers can optionally optimize hot paths by returning partial templates directly.

The ops dashboard serves as the first test case, converting from a client-side JS SPA to server-rendered JTE templates with htmx polling.

## Design Principles

- **No new abstractions.** Handlers remain functional, returning `Result`. No `Context` API, no mutable state.
- **Client-side extraction by default.** The server renders the full page; htmx uses `hx-select` to pull out the relevant element. This eliminates server-side fragment detection logic.
- **Optimization is opt-in.** When full-page rendering is expensive, developers use `req.isHtmx()` to return a partial template. This is a choice, not a requirement.
- **Convention over configuration.** Partial templates use `_` prefix naming (`_list.jte`, `_stats.jte`). This is a naming convention, not enforced by the framework.

## Framework Changes

### 1. `req.isHtmx()`

Single method on `Request`. Returns `true` when the `HX-Request` header is present.

```java
public boolean isHtmx() {
    return "true".equals(header("HX-Request"));
}
```

### 2. Bundle htmx.min.js

Serve htmx from `/__brace/htmx.min.js` as a classpath resource. Pin a specific version (htmx 2.0.x) and update deliberately with framework releases. Served via the existing static file mechanism in `BraceHandler`.

### 3. `Vary: HX-Request` header

`BraceHandler` automatically adds `Vary: HX-Request` to responses when it detects an htmx request. This ensures caches don't serve a partial response to a full-page request or vice versa.

## Developer Patterns

### Default: full page render + client extraction

The handler returns a full page. htmx attributes on HTML elements declare what to request, what to extract, and where to put it.

```java
app.get("/contacts", (req, db) -> {
    var contacts = db.query("from Contact where name like ?", "%" + req.param("q") + "%");
    return View.of("contacts/index", "contacts", contacts, "query", req.param("q"));
});
```

```html
<!-- contacts/index.jte -->
@param List<Contact> contacts
@param String query

<h1>Contacts</h1>
<input type="search" name="q" value="${query}"
       hx-get="/contacts" hx-select="#contact-list" hx-target="#contact-list"
       hx-trigger="keyup changed delay:300ms">

@template.contacts._list(contacts = contacts)
```

The same handler serves both the full page load and the htmx search request. No branching, no extra endpoints.

### Optimization: explicit partial rendering

When rendering the full page is wasteful, return the partial directly:

```java
app.get("/contacts", (req, db) -> {
    var contacts = db.query("from Contact where name like ?", "%" + req.param("q") + "%");
    if (req.isHtmx()) {
        return View.of("contacts/_list", "contacts", contacts);
    }
    return View.of("contacts/index", "contacts", contacts, "query", req.param("q"));
});
```

### Partial template convention

Partial templates are prefixed with `_` and placed alongside their parent:

```
templates/
  contacts/
    index.jte          # Full page
    _list.jte          # Contact list partial
    _row.jte           # Single contact row partial
```

Partials declare only the `@param` lines they need. JTE silently ignores extra map keys when rendering via `Map<String, Object>`, so passing the full parent param set to a partial is safe — verified by test.

**Constraint:** Partials that are independently rendered (via `req.isHtmx()` optimization) can only depend on data the handler provides. If a parent template computes values before including a partial, those values won't be available when the partial is rendered directly. Keep computed values in handlers, not templates.

## Test Case: Ops Dashboard Conversion

The current ops dashboard (`OpsDashboard.java`) is a ~280-line inline HTML string containing a client-side JS SPA. It fetches JSON from `/ops/status` every 5 seconds and rebuilds the entire DOM in JavaScript.

### Conversion plan

Replace with server-rendered JTE templates using htmx polling:

**Templates:**
- `ops/dashboard.jte` — full page layout, styles, htmx script tag, includes all section partials
- `ops/_stats.jte` — stat cards (requests, error rate, heap, cache, emails)
- `ops/_sparkline.jte` — requests/minute sparkline chart
- `ops/_routes.jte` — slowest routes table
- `ops/_errors.jte` — recent errors (in-memory) table
- `ops/_error_tracking.jte` — persisted error tracking with tabs
- `ops/_jobs.jte` — scheduled jobs table
- `ops/_rate_limiters.jte` — rate limiter stats table
- `ops/_cache.jte` — cache details and clear button
- `ops/_status_codes.jte` — status code breakdown table

**Handler changes:**
- `OpsHandler.dashboard()` returns `View.of("ops/dashboard", ...)` with all stats data
- Remove `OpsDashboard.java` entirely

**htmx behavior:**
- The dashboard page loads fully server-rendered on first visit
- A wrapper div uses `hx-get="/ops/dashboard" hx-select="#dashboard-content" hx-target="#dashboard-content" hx-trigger="every 5s"` to poll and replace the dynamic content
- Actions like "resolve error" and "clear cache" use `hx-post` targeting specific sections
- No JavaScript rendering code — all HTML generated server-side by JTE

**Authentication:**
- The ops key is passed via query param on the initial page load (`/ops/dashboard?key=...`)
- A parent element sets `hx-headers='{"X-Ops-Key": "..."}' ` so all htmx requests within it inherit the auth header automatically
- `OpsHandler.authorize()` already checks `X-Ops-Key` header, so no auth changes needed

## Future

Features validated by the design discussion but deferred until real usage shows they're needed:

### SSE for server-push (Phase 2)

Server-Sent Events for server-initiated page updates without polling. A new `Result.sse()` that holds the connection open and provides an emitter:

```java
app.get("/events/notifications", (req, db) -> {
    return Result.sse(emitter -> {
        emitter.send("notification", View.render("notifications/_badge", "count", 5));
    });
});
```

Client-side via htmx's built-in SSE extension:

```html
<div hx-ext="sse" sse-connect="/events/notifications" sse-swap="notification">
  <!-- server pushes HTML fragments here -->
</div>
```

SSE is simpler than WebSockets, works through proxies/load balancers reliably, and auto-reconnects natively. Covers the majority of server-push needs (notifications, live feeds, background job progress).

### HX-Fragment auto-detection

Automatic server-side fragment detection: `BraceHandler` intercepts a `View` result, reads an `HX-Fragment` request header, and renders the corresponding partial template instead of the full page. Eliminates the `if (req.isHtmx())` branch in handlers. Deferred because `hx-select` achieves the same result without framework complexity.

### View.compose() for multi-fragment responses

Concatenate multiple rendered partials in a single response for htmx out-of-band (OOB) swaps:

```java
return View.compose(
    View.of("contacts/_list", "contacts", contacts),
    View.of("contacts/_count", "count", count).oob()
);
```

Each fragment is a real JTE template with typed params. The `.oob()` marker injects `hx-swap-oob="true"` on the root element. Deferred because most use cases can update a single target, and explicit separate requests are simpler.

### htmx response header helpers

Convenience methods on `Result` for htmx response headers (redirect, push-url, trigger, retarget, reswap). Currently achievable with `result.header("HX-Redirect", "/new-url")` — sugar can be added if the raw header calls prove tedious.

### WebSocket pub/sub

Broadcast HTML fragments to multiple connected clients via WebSocket channels. For collaborative editing, chat, real-time dashboards across multiple users. Brace already has `ws()` for raw WebSocket support. A higher-level pub/sub abstraction with channel management is deferred — SSE covers most server-push needs for single-user interactions.

### @fragment JTE preprocessor

Native `@fragment` / `@endfragment` syntax in JTE templates, compiled to independently renderable template sections. Would eliminate the need for separate `_partial.jte` files. Deferred in favor of convention-based partials which work with stock JTE.
