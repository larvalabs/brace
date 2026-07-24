# Migrating from Brace 0.1.7 → 0.1.8

This release (in progress) has **no breaking changes**. It adds an **optional**
Cloudflare trusted-proxy preset and a **startup warning** for a common rate-limiter
misconfiguration.

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|
| `TrustedProxies.cloudflare()` preset | new-optional | none — additive | [§](#new-optional-trustedproxiescloudflare-preset-with-auto-refresh) |
| Startup warning: `perIp` without trusted proxies | behavior | none — log line only | [§](#behavior-startup-warning-when-ratelimiterperip-runs-without-trusted-proxies) |

## New (optional): `TrustedProxies.cloudflare()` preset with auto-refresh

For apps behind Cloudflare, trusted proxies no longer require hand-pasting Cloudflare's
published CIDR list. A new preset ships with the published egress ranges bundled, an
optional background refresh, and a way to add your own hops:

**Before (0.1.7):**

```java
app.trustedProxies("173.245.48.0/20", "103.21.244.0/22", /* ...the rest of cloudflare.com/ips... */);
```

**After (0.1.8):**

```java
app.trustedProxies(TrustedProxies.cloudflare().autoRefresh());

// with nginx between Cloudflare and the app, also trust the local hop:
app.trustedProxies(TrustedProxies.cloudflare().plus("127.0.0.1", "::1").autoRefresh());
```

- `cloudflare()` starts from the bundled list — no network dependency at startup.
- `.autoRefresh()` re-fetches `cloudflare.com/ips-v4` + `/ips-v6` on a background virtual
  thread (daily; hourly retry after a failure). A failed or partial fetch is discarded
  wholesale, so the trust set never shrinks on a network blip.
- `.plus(cidrs)` adds CIDRs/IPs that survive refreshes (local reverse proxy, LAN ranges).
- A new `app.trustedProxies(TrustedProxies)` overload accepts the pre-built instance;
  the existing varargs/list overloads are unchanged.

Existing `app.trustedProxies("10.0.0.0/8", ...)` configurations continue to work as-is.

## Behavior: startup warning when `RateLimiter.perIp` runs without trusted proxies

`Brace.start()` now logs a `WARN` when a `RateLimiter.perIp(...)` middleware is registered
but `app.trustedProxies(...)` was never called. In that configuration `req.ip()` is the
socket peer, so behind a reverse proxy or CDN every client shares the proxy's address and
the per-IP limit is silently site-wide.

No action required: it is a log line only — nothing fails, and apps whose clients connect
directly can ignore it. To resolve the warning, configure `app.trustedProxies(...)`
(see the section above for Cloudflare).
