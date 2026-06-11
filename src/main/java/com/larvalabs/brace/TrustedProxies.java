package com.larvalabs.brace;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates whether a client IP should be trusted as a proxy for forwarding headers.
 * Supports CIDR notation (e.g., "10.0.0.0/8") and individual IPs.
 */
public class TrustedProxies {

    private final List<CidrRange> ranges;

    public TrustedProxies(String... cidrs) {
        this.ranges = new ArrayList<>();
        for (var cidr : cidrs) {
            ranges.add(CidrRange.parse(cidr));
        }
    }

    public TrustedProxies(List<String> cidrs) {
        this.ranges = new ArrayList<>();
        for (var cidr : cidrs) {
            ranges.add(CidrRange.parse(cidr));
        }
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
        private final InetAddress network;
        private final int prefixLength;
        private final BigInteger mask;

        private CidrRange(InetAddress network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
            this.mask = createMask(prefixLength, network.getAddress().length * 8);
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
            if (addr.getAddress().length != network.getAddress().length) {
                return false; // IPv4 vs IPv6 mismatch
            }

            var addrBits = new BigInteger(1, addr.getAddress());
            var netBits = new BigInteger(1, network.getAddress());

            return addrBits.and(mask).equals(netBits.and(mask));
        }

        private static BigInteger createMask(int prefixLength, int totalBits) {
            return BigInteger.ONE
                .shiftLeft(totalBits - prefixLength)
                .subtract(BigInteger.ONE)
                .not()
                .and(BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE));
        }
    }
}
