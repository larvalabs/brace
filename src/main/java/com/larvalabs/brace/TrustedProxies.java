package com.larvalabs.brace;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validates whether a client IP should be trusted as a proxy for forwarding headers.
 * Supports CIDR notation (e.g., "10.0.0.0/8") and individual IPs.
 *
 * <p>For apps behind Cloudflare, {@link #cloudflare()} builds an instance pre-loaded with
 * Cloudflare's published egress ranges; add your own hops with {@link #plus(String...)} and
 * keep the ranges current with {@link #autoRefresh()}:
 *
 * <pre>{@code
 * app.trustedProxies(TrustedProxies.cloudflare().autoRefresh());
 * // with nginx between Cloudflare and the app:
 * app.trustedProxies(TrustedProxies.cloudflare().plus("127.0.0.1", "::1").autoRefresh());
 * }</pre>
 */
public class TrustedProxies {

    /**
     * Cloudflare's published egress ranges (www.cloudflare.com/ips), bundled so
     * {@link #cloudflare()} works with no network dependency. The list changes rarely and
     * changes are announced; {@link #autoRefresh()} replaces it with the live list at runtime.
     */
    static final List<String> CLOUDFLARE_IPV4 = List.of(
        "173.245.48.0/20",
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "141.101.64.0/18",
        "108.162.192.0/18",
        "190.93.240.0/20",
        "188.114.96.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17",
        "162.158.0.0/15",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "131.0.72.0/22");

    static final List<String> CLOUDFLARE_IPV6 = List.of(
        "2400:cb00::/32",
        "2606:4700::/32",
        "2803:f800::/32",
        "2405:b500::/32",
        "2405:8100::/32",
        "2a06:98c0::/29",
        "2c0f:f248::/32");

    /** Plain-text one-CIDR-per-line endpoints — simpler than the JSON API, same data. */
    static final String CLOUDFLARE_IPS_V4_URL = "https://www.cloudflare.com/ips-v4";
    static final String CLOUDFLARE_IPS_V6_URL = "https://www.cloudflare.com/ips-v6";

    private static final Duration REFRESH_INTERVAL = Duration.ofHours(24);
    private static final Duration RETRY_INTERVAL = Duration.ofHours(1);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The active, fully-parsed trust set. Volatile: {@link #autoRefresh()} and {@link #plus}
     * swap in a freshly built immutable list; {@link #isTrusted} reads one snapshot per call.
     */
    private volatile List<CidrRange> ranges;

    /**
     * Provider-managed CIDRs (non-null only for {@link #cloudflare()} instances). Kept separate
     * from {@link #staticCidrs} so a refresh replaces the provider list wholesale without
     * touching CIDRs the app added itself.
     */
    private volatile List<String> providerCidrs;

    /** CIDRs from constructors and {@link #plus} — retained as strings so ranges can be rebuilt. */
    private final List<String> staticCidrs = new ArrayList<>();

    private final boolean cloudflarePreset;
    private final AtomicBoolean refreshStarted = new AtomicBoolean(false);

    public TrustedProxies(String... cidrs) {
        this(List.of(cidrs));
    }

    public TrustedProxies(List<String> cidrs) {
        this.cloudflarePreset = false;
        this.providerCidrs = null;
        this.staticCidrs.addAll(cidrs);
        this.ranges = buildRanges();
    }

    private TrustedProxies(List<String> providerCidrs, boolean cloudflarePreset) {
        this.cloudflarePreset = cloudflarePreset;
        this.providerCidrs = List.copyOf(providerCidrs);
        this.ranges = buildRanges();
    }

    /**
     * Trust Cloudflare's published egress ranges, so {@code req.ip()} resolves the real client
     * address from the forwarding headers Cloudflare sets. Starts from the bundled list (no
     * network dependency); chain {@link #autoRefresh()} to keep it synced with
     * cloudflare.com/ips, and {@link #plus(String...)} to also trust hops between Cloudflare
     * and the app (e.g. a local nginx).
     *
     * <p>Only sound when the origin is reachable exclusively through Cloudflare (or you accept
     * that direct-to-origin clients are untrusted peers whose headers are ignored — which is the
     * safe failure mode).
     */
    public static TrustedProxies cloudflare() {
        var combined = new ArrayList<String>(CLOUDFLARE_IPV4.size() + CLOUDFLARE_IPV6.size());
        combined.addAll(CLOUDFLARE_IPV4);
        combined.addAll(CLOUDFLARE_IPV6);
        return new TrustedProxies(combined, true);
    }

    /**
     * Trust additional CIDRs or IPs on top of the current set — typically the reverse proxy
     * sitting between the CDN and the app ({@code "127.0.0.1"}, {@code "::1"}, a LAN range).
     * These survive {@link #autoRefresh()} updates. Fluent; returns {@code this}.
     */
    public synchronized TrustedProxies plus(String... cidrs) {
        // Validate before mutating so a bad CIDR leaves the instance unchanged.
        for (var cidr : cidrs) {
            CidrRange.parse(cidr);
        }
        staticCidrs.addAll(List.of(cidrs));
        ranges = buildRanges();
        return this;
    }

    /**
     * Keep the Cloudflare ranges synced with the live published list. Fetches
     * cloudflare.com/ips-v4 and /ips-v6 on a background virtual thread — immediately, then
     * every 24 hours (hourly retry after a failure). The bundled list serves until the first
     * fetch succeeds, and any failed or partial fetch is discarded wholesale, so the trust set
     * never shrinks to empty on a network blip. CIDRs added via {@link #plus} are preserved
     * across refreshes. Fluent; returns {@code this}. Idempotent.
     *
     * @throws IllegalStateException if this instance was not built by {@link #cloudflare()}
     */
    public TrustedProxies autoRefresh() {
        if (!cloudflarePreset) {
            throw new IllegalStateException("autoRefresh() is only available on TrustedProxies.cloudflare()");
        }
        if (refreshStarted.compareAndSet(false, true)) {
            Thread.ofVirtual().name("trusted-proxies-refresh").start(() -> {
                while (true) {
                    boolean ok = refreshNow();
                    try {
                        Thread.sleep(ok ? REFRESH_INTERVAL : RETRY_INTERVAL);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }
        return this;
    }

    /**
     * One refresh attempt: fetch both published lists and swap them in. Any failure — HTTP
     * error, empty list, unparsable line — abandons the whole attempt and keeps the current
     * ranges. Package-private so tests can drive a refresh without the background thread.
     */
    boolean refreshNow() {
        try {
            var fetched = new ArrayList<String>();
            fetched.addAll(fetchCidrList(CLOUDFLARE_IPS_V4_URL));
            fetched.addAll(fetchCidrList(CLOUDFLARE_IPS_V6_URL));
            applyProviderCidrs(fetched);
            Log.info("trusted-proxies: refreshed Cloudflare IP ranges (" + fetched.size() + " CIDRs)");
            return true;
        } catch (RuntimeException e) {
            Log.warn("trusted-proxies: Cloudflare IP range refresh failed, keeping current ranges — " + e.getMessage());
            return false;
        }
    }

    /**
     * Replace the provider-managed CIDR list and rebuild the trust set, preserving constructor
     * and {@link #plus} CIDRs. All-or-nothing: an empty list or an unparsable entry throws and
     * leaves the current ranges in place.
     */
    synchronized void applyProviderCidrs(List<String> cidrs) {
        if (cidrs.isEmpty()) {
            throw new IllegalArgumentException("empty CIDR list");
        }
        var replacement = List.copyOf(cidrs);
        // Parse everything before assigning anything, so a bad entry can't half-apply.
        var previous = providerCidrs;
        providerCidrs = replacement;
        try {
            ranges = buildRanges();
        } catch (RuntimeException e) {
            providerCidrs = previous;
            throw e;
        }
    }

    /** One published list: one CIDR per line, blank lines ignored. Throws on a non-2xx status. */
    private static List<String> fetchCidrList(String url) {
        var response = Http.get(url).timeout(FETCH_TIMEOUT).fetch();
        if (!response.ok()) {
            throw new RuntimeException("GET " + url + " returned status " + response.status());
        }
        var cidrs = new ArrayList<String>();
        for (var line : response.body().split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                cidrs.add(trimmed);
            }
        }
        if (cidrs.isEmpty()) {
            throw new RuntimeException("GET " + url + " returned an empty list");
        }
        return cidrs;
    }

    /** Parse provider + static CIDRs into a fresh immutable range list (throws on a bad CIDR). */
    private List<CidrRange> buildRanges() {
        var provider = providerCidrs;
        var built = new ArrayList<CidrRange>();
        if (provider != null) {
            for (var cidr : provider) {
                built.add(CidrRange.parse(cidr));
            }
        }
        for (var cidr : staticCidrs) {
            built.add(CidrRange.parse(cidr));
        }
        return List.copyOf(built);
    }

    /**
     * Returns true if the string is a syntactic IPv4 or IPv6 literal (no DNS resolution
     * performed). Used to gate {@link #isTrusted} on request-path input so that
     * attacker-controlled hostname-shaped values never trigger a DNS lookup.
     *
     * <ul>
     *   <li>IPv4: four decimal octets 0–255 separated by exactly three dots, no leading zeros,
     *       no extra characters.</li>
     *   <li>IPv6: one or more colon characters in the string (the minimal structural marker for
     *       any valid IPv6 address — compressed or full). We rely on {@link InetAddress#getByName}
     *       to reject strings that pass this coarse check but are still malformed IPv6; the point
     *       is to reject hostname-shaped strings (letters + dots, no colons) before they reach
     *       the resolver.</li>
     * </ul>
     */
    static boolean isIpLiteral(String s) {
        if (s == null || s.isEmpty()) return false;

        // IPv6 heuristic: any colon present → treat as IPv6 literal candidate.
        // Hostnames never contain colons; malformed strings with colons will fail
        // InetAddress.getByName (UnknownHostException) safely.
        if (s.indexOf(':') >= 0) return true;

        // IPv4: exactly 4 decimal octets, no leading zeros, values 0–255.
        var parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (var part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            // Reject leading zeros (e.g. "01") — not valid in dotted-decimal notation.
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            int val = Integer.parseInt(part);
            if (val < 0 || val > 255) return false;
        }
        return true;
    }

    /**
     * Check if the given IP address is trusted as a proxy.
     *
     * <p>Only IP literals (IPv4 dotted-quad or IPv6 with colons) are evaluated. Hostname-shaped
     * strings are rejected immediately without any DNS resolution, preventing request-latency
     * DoS and DNS side-channel attacks from attacker-controlled forwarding headers.
     */
    public boolean isTrusted(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        // Reject non-literal input before touching InetAddress — never do DNS on hot path.
        if (!isIpLiteral(ip)) return false;
        try {
            var addr = InetAddress.getByName(ip);
            for (var range : ranges) {
                if (range.contains(addr)) return true;
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static class CidrRange {
        // L4: precompute the masked network bytes and a per-byte mask at construction so
        // contains() is an allocation-free byte compare on the hot path (no per-check BigIntegers
        // and no repeated network.getAddress() clones). The incoming addr's getAddress() clone is
        // the only unavoidable allocation (InetAddress always copies its backing array).
        private final byte[] networkBytes; // already masked
        private final byte[] maskBytes;
        private final int addressLength;   // 4 (IPv4) or 16 (IPv6)

        private CidrRange(InetAddress network, int prefixLength) {
            byte[] net = network.getAddress();
            this.addressLength = net.length;
            this.maskBytes = createMask(prefixLength, net.length);
            this.networkBytes = new byte[net.length];
            for (int i = 0; i < net.length; i++) {
                this.networkBytes[i] = (byte) (net[i] & maskBytes[i]);
            }
        }

        static CidrRange parse(String cidr) {
            try {
                if (!cidr.contains("/")) {
                    // Single IP
                    var addr = InetAddress.getByName(cidr);
                    return new CidrRange(addr, addr.getAddress().length * 8);
                }

                var parts = cidr.split("/");
                var addr = InetAddress.getByName(parts[0]);
                var prefix = Integer.parseInt(parts[1]);
                return new CidrRange(addr, prefix);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid CIDR: " + cidr, e);
            }
        }

        boolean contains(InetAddress addr) {
            byte[] a = addr.getAddress();
            if (a.length != addressLength) {
                return false; // IPv4 vs IPv6 mismatch
            }
            for (int i = 0; i < a.length; i++) {
                if ((byte) (a[i] & maskBytes[i]) != networkBytes[i]) {
                    return false;
                }
            }
            return true;
        }

        private static byte[] createMask(int prefixLength, int addressLength) {
            byte[] mask = new byte[addressLength];
            int fullBytes = prefixLength / 8;
            int remainderBits = prefixLength % 8;
            for (int i = 0; i < fullBytes && i < addressLength; i++) {
                mask[i] = (byte) 0xFF;
            }
            if (remainderBits > 0 && fullBytes < addressLength) {
                mask[fullBytes] = (byte) (0xFF << (8 - remainderBits));
            }
            return mask;
        }
    }
}
