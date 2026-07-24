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

### New (optional): `TrustedProxies.cloudflare()` preset with auto-refresh

For apps behind Cloudflare, trusted proxies no longer require hand-pasting Cloudflare's
published CIDR list. A new preset ships with the published egress ranges bundled, an
optional background refresh, and a way to add your own hops.

**Before (0.1.9):**

```java
app.trustedProxies("173.245.48.0/20", "103.21.244.0/22", /* ...the rest of cloudflare.com/ips... */);
```

**After (0.1.10):**

```java
app.trustedProxies(TrustedProxies.cloudflare().autoRefresh());

// with nginx between Cloudflare and the app, also trust the local hop:
app.trustedProxies(TrustedProxies.cloudflare().plus("127.0.0.1", "::1").autoRefresh());
```

- `cloudflare()` starts from the bundled list, so there's no network dependency at startup.
- `.autoRefresh()` re-fetches `cloudflare.com/ips-v4` and `/ips-v6` on a background virtual
  thread (daily, with an hourly retry after a failure). A failed or partial fetch is
  discarded wholesale, so the trust set never shrinks on a network blip.
- `.plus(cidrs)` adds CIDRs/IPs that survive refreshes (local reverse proxy, LAN ranges).
- A new `app.trustedProxies(TrustedProxies)` overload accepts the pre-built instance. The
  existing varargs/list overloads are unchanged.

Existing `app.trustedProxies("10.0.0.0/8", ...)` configurations keep working as-is. Still
pass the real `Host` through from your proxy, as the 0.1.8 guide describes.

### Behavior: startup warning when `RateLimiter.perIp` runs without trusted proxies

`Brace.start()` now logs a `WARN` when a `RateLimiter.perIp(...)` middleware is registered
but `app.trustedProxies(...)` was never called. In that configuration `req.ip()` is the
socket peer, so behind a reverse proxy or CDN every client shares the proxy's address and
the per-IP limit is silently site-wide.

No action required: it's a log line only. Nothing fails, and apps whose clients connect
directly can ignore it. To resolve the warning, configure `app.trustedProxies(...)`
(see the section above for Cloudflare).

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

_No entries yet._

<!-- end section: ops-jvm-cli -->

---

<!-- section: dx -->
## New projects and custom metrics

_No entries yet._

<!-- end section: dx -->
