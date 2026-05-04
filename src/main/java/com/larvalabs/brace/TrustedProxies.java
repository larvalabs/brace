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
     * Check if the given IP address is trusted as a proxy.
     */
    public boolean isTrusted(String ip) {
        if (ip == null || ip.isEmpty()) return false;
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
