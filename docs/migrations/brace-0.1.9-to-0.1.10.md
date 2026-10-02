# Migrating from Brace 0.1.9 → 0.1.10

<!-- In progress. Each workstream fills in only its own section below. The intro, the
breaking-change summary and the Index table are written at integration time, from the
sections. -->

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|

---

<!-- section: correctness -->
## Correctness fixes

_No entries yet._

<!-- end section: correctness -->

---

<!-- section: streaming-io -->
## Streaming uploads and responses

_No entries yet._

<!-- end section: streaming-io -->

---

<!-- section: proxies -->
## Trusted proxies

_No entries yet._

<!-- end section: proxies -->

---

<!-- section: http-streaming -->
## Streaming in the `Http` client

_No entries yet._

<!-- end section: http-streaming -->

---

<!-- section: ops-dashboard -->
## Ops dashboard

_No entries yet._

<!-- end section: ops-dashboard -->

---

<!-- section: ops-jvm-cli -->
## GC pause figures and CLI

### Fix: GC pause figures count only stop-the-world time

**What changed.** `/ops/status` `jvm.gc` pause figures (`totalPauseMs`, `avgPauseMs`,
`recentPauses[].durationMs`), the dashboard's GC card and table, the `jvm.gc_total_pause_ms`
/ `jvm.gc_max_pause_ms` timeseries and `brace check`'s `gc_pressure` now come from JFR's
`sumOfPauses` and `longestPause`, the time the application was actually stopped. Before,
they used each collection's whole duration. For concurrent collections (G1's `G1Old`
marking cycle, ZGC, Shenandoah) most of that time runs alongside the application, so pause
time was overstated, often about 3x on G1.

New fields, all additive:

| Field | Meaning |
|---|---|
| `jvm.gc.maxPauseMs` | Longest single pause in the last 100 collections |
| `jvm.gc.fullCount` | Whole-heap stop-the-world collections (`G1Full`, `SerialOld`, `ParallelOld`) |
| `recentPauses[].longestPauseMs` | Longest single pause within that collection |
| `recentPauses[].cycleMs` | The collection's wall-clock span (the old `durationMs` value) |
| `recentPauses[].full` | `true` on full collections; absent otherwise |

`gc_pressure` still fails when `avgPauseMs` exceeds `check.gc_pause_ms`. It now also warns
(without failing `brace check`) when a `G1Full` appears among the recent collections, and
its `followUp` is `brace status --include profiling` for allocation data.

**Who needs to act.** No code changes. If you alert on `jvm.gc` figures or raised
`check.gc_pause_ms` in `.brace` to quiet false `gc_pressure` failures, expect lower numbers
and consider restoring the default (50). A long `cycleMs` with a small `durationMs` is a
normal concurrent cycle.

**Before (0.1.9)**, a 120 ms G1 concurrent cycle with 6 ms of real pause:

```json
{ "collector": "G1Old", "durationMs": 120.0 }
```

**After (0.1.10):**

```json
{ "collector": "G1Old", "durationMs": 6.0, "longestPauseMs": 4.0, "cycleMs": 120.0 }
```

### New (optional): `brace status --include profiling,timeseries`

**What changed.** `brace status` always fetched the bare `/ops/status`, so the opt-in
blocks (JFR hot methods and top allocations, per-minute timeseries) were reachable only
over HTTP. `--include` passes a comma-separated list through as `?include=...`. Unknown
names are rejected, since the server would silently ignore them. Without the flag the
request and output are unchanged.

**Who needs to act.** Nobody. Scripts that called the endpoint directly to get profiling
can switch to the CLI, which handles auth.

**Before (0.1.9):**

```bash
curl -H "Authorization: Bearer $TOKEN" "$URL/ops/status?include=profiling"
```

**After (0.1.10):**

```bash
brace status --env prod --include profiling --json
```

<!-- end section: ops-jvm-cli -->

---

<!-- section: dx -->
## New projects and custom metrics

_No entries yet._

<!-- end section: dx -->
