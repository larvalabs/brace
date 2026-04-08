# Ops Dashboard Polish — Design Spec

## Overview

Restyle the ops dashboard (`OpsDashboard.java`) from the current dark theme to a TUI-inspired design combining GitHub Dark's high-contrast readability with Tokyo Night's accent color palette. Add additional sparkline graphs, improve JFR data formatting, and reorganize sections for better visual hierarchy.

## Visual Style

### Color Palette

| Role | Color | Hex |
|---|---|---|
| Background | GitHub Dark | `#0d1117` |
| Border | Subtle gray | `#30363d` |
| Text (primary) | Light gray | `#c9d1d9` |
| Text (muted/labels) | Dim gray | `#565f89` |
| Accent: requests | Blue | `#7aa2f7` |
| Accent: success/ok | Green | `#9ece6a` |
| Accent: memory | Purple | `#bb9af7` |
| Accent: warning/cache | Amber | `#e0af68` |
| Accent: error | Red | `#f7768e` |
| Accent: jobs/threads | Cyan | `#7dcfff` |
| Sparkline bar (low) | Dark green | `#238636` |
| Sparkline bar (mid) | Mid green | `#2ea043` |
| Sparkline bar (high) | Bright green | `#3fb950` |

### Typography

- Monospace font stack: `'JetBrains Mono', Menlo, Consolas, monospace`
- Labels: muted color (`#565f89`), uppercase, `letter-spacing: 0.5px`, small font size (~10px)
- Values: bright accent colors per domain, bold, larger font (~20px for stat cards)
- Table text: 11px, primary color for data, muted for secondary columns

### Borders and Spacing

- 1px solid `#30363d` borders on all panels/cards
- Consistent padding: 8-10px inside panels
- 10px gap between side-by-side panels
- 12-14px vertical gap between section rows

## Layout

Single scrolling page. No tabs, no collapsible sections. Dense TUI aesthetic — everything visible at once. Sections render conditionally based on what features are active (same as current behavior).

### Section Order (top to bottom)

1. **Header bar** — app name (styled as `┌ BRACE`), uptime, Java version, start time, refresh interval. Separated by `│` delimiters. Bottom border.
2. **Stat cards row** — horizontal flex row of bordered cards: Requests, Error Rate, Heap, CPU, Threads, GC Avg. Conditional cards for Cache (entries + hit rate) and Mailer (sent + failures) appended when active. Each card: muted label on top, large colored value, small detail line below.
3. **Req/min sparkline** — bordered panel, full width. CSS bar chart (existing approach). Bars colored with green gradient based on relative height. Label: "Requests / Minute" with "last 60 min" right-aligned.
4. **Hot Methods + Slowest Routes** — two-column, equal width. Section headers color-coded (blue). Tables with muted column headers.
5. **Top Allocations + GC Pauses** — two-column, equal width. Allocations header in purple, GC Pauses header in red.
6. **Error tracking** — full width. Red header with unresolved count. Table with type, route, count, last seen, actions (trace toggle + resolve). Expandable stack traces preserved.
7. **Jobs + Cache** — two-column. Jobs header in cyan, Cache header in amber. Cache includes inline stats (entries, hit rate, misses, evictions) and `[clear all]` action.
8. **Rate Limiters** — full width, conditional. Table with label, allowed, blocked, active windows, limit.
9. **Status Codes** — full width. Table of HTTP status codes and counts.

## Sparkline Graphs

CSS-only bar charts using inline `div` elements with percentage heights — same technique as the current req/min sparkline. No charting libraries.

### Existing

- **Requests / Minute** — 60 bars from the minute ring buffer. Green gradient based on relative value.

### New Sparklines

- **Error rate over time** — derived from the minute snapshots (errors/requests per minute). Bars colored red. Placed below the req/min sparkline or as a secondary row within the same panel.
- **Heap usage over time** — requires adding heap snapshots to the minute ring buffer in `Stats`. Bars colored purple. Placed in the JVM section near GC pauses.
- **GC pause durations** — recent pause durations as bars. Color-coded: green (<10ms), amber (10-100ms), red (>100ms). Placed above the GC pauses table.

### Implementation Note

The minute ring buffer in `Stats` currently stores: timestamp, requests, errors, totalLatency, maxLatency, queryCount, queryMicros. Heap usage will need to be added as a new field captured at each minute tick (from `ManagementFactory.getMemoryMXBean()` or from the JFR profiler snapshot).

## JFR Data Formatting

### Method Names (Hot Methods table)

Display as two styled spans: dimmed package path + bright class.method.

```
<span class="pkg">io.brace.</span><span class="method">Database.query</span>
```

- `.pkg`: muted color (`#565f89`), `text-overflow: ellipsis` with `direction: rtl` to ellipsis from the left when space is tight
- `.method`: primary text color, bold
- Full qualified name in `title` attribute (tooltip on hover)
- The split point: everything before the second-to-last `.` is package, the rest is `Class.method`

### Allocation Classes

Same dim/bright treatment — package dimmed, class name bright. Formatted sizes already exist (B/KB/MB/GB).

### GC Pause Duration Color Coding

| Duration | Color | Treatment |
|---|---|---|
| < 10ms | Green (`#9ece6a`) | Normal weight |
| 10–100ms | Amber (`#e0af68`) | Normal weight |
| > 100ms | Red (`#f7768e`) | Bold |

Collector name and cause in muted color. Timestamp in muted color. Duration is the visually dominant column.

## Existing Behavior Preserved

- String-built HTML in `OpsDashboard.java` — no templates
- HTMX 5-second auto-refresh (`hx-trigger="every 5s"`)
- HTMX interactions: resolve errors (`hx-post`), clear cache (`hx-post`)
- Conditional section rendering (JFR, errors, jobs, cache, rate limiters, mailer)
- HTML escaping via `esc()` on all rendered values
- Ops key authorization (header or query param)
- Tab switching for resolved/unresolved errors
- Stack trace expand/collapse

## No New Dependencies

- No CSS frameworks or charting libraries
- No additional JS beyond existing vanilla functions
- htmx remains the only external JS dependency

## Files to Modify

- `OpsDashboard.java` — primary change: restyle all HTML/CSS output, add new sparklines, improve JFR formatting
- `Stats.java` — add heap usage field to minute snapshots
- `OpsHandler.java` — minor: pass any new data needed to `OpsDashboard.html()`

## Out of Scope

- Moving to JTE templates
- Collapsible sections
- Tab-based navigation
- WebSocket metrics (separate TODO item)
- File upload metrics (separate TODO item)
