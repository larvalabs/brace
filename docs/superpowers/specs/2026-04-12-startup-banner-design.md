# Startup Banner Design

**Date:** 2026-04-12
**Status:** Approved

## Overview

Replace the plain `Brace started on port N` startup line with a small ASCII-art banner shaped like a Java block — the framework name rendered in figlet letters inside an outer `{ ... }`, with app info and a nested `routes { ... }` block. Plays on the fact that the framework is literally named after the curly-brace character.

## Goals

- Give the framework a recognizable startup signature
- Keep output plain ASCII/Unicode (no ANSI colors) so it works in every terminal, CI log, and log aggregator
- Make the banner optional so test runs and production deployments can suppress it
- Preserve the existing route-table content (nothing currently shown should disappear)

## Banner format

```
  {
      _                    
     | |__ _ _ __ _ __ ___ 
     | '_ \ '_/ _` / _/ -_)
     |_.__/_| \__,_\__\___|

     port   8080
     mode   dev
     ready  ✓

     routes (12) {
        GET    /
        GET    /users
        POST   /users
        GET    /users/{id}
        WS     /chat
     }
  }
```

**Letters:** figlet "Small" font for the word `brace`, indented to sit inside the outer brace.

**Outer brace:** `{` on its own line at column 2, `}` on its own line at column 2. The whole banner reads as one valid-looking Java block.

**Info fields** (inside the outer brace, above `routes`):

| Field | Source |
|---|---|
| `port` | `actualPort()` |
| `mode` | `System.getProperty("brace.mode")`, defaulting to `—` if unset |
| `ready` | always `✓` (the banner only prints after successful startup) |

**Routes block:**
- Header line: `routes (N) {` where `N = router.routes().size() + wsRoutes.size()`
- One indented line per HTTP route: `METHOD  pattern` (6-char method column, matching today's format)
- One indented line per websocket route: `WS      pattern`
- Closing `}` on its own line
- If there are zero routes, still print `routes (0) {` and `}` on consecutive lines (empty block is shown, not omitted)

## Suppression

New builder method:

```java
public Brace banner(boolean show)
```

Default: `true`. When set to `false`, `start()` skips the banner entirely and prints nothing on startup.

**TestApp** calls `.banner(false)` on the Brace instance it builds so the 410-test suite doesn't emit ~15 lines of banner per test run.

**Production users** who want clean logs can call `.banner(false)` in their `main()`.

## Implementation surface

All changes are in `Brace.java` plus a one-line addition to `TestApp.java`:

1. New field: `private boolean showBanner = true;`
2. New builder: `public Brace banner(boolean show) { this.showBanner = show; return this; }`
3. New private method `printBanner()` holding the figlet text block and formatting the info + routes block
4. In `start()`, the block at line 579-586 that prints `"Brace started on port ..."` + route table is replaced with `if (showBanner) printBanner();`
5. `TestApp` adds `.banner(false)` when constructing its Brace instance

The figlet letters live as a Java text block constant inside `printBanner()`. No new files, no new classes.

## Out of scope

- ANSI colors / bold (plain text only — renders identically in every terminal and log pipeline)
- Runtime version string (the framework doesn't currently expose one; can be added later if needed)
- DB connection status or other health probes
- Configurable banner content or custom banners per app
