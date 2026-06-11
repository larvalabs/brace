package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RequestIpTest {

    @Test
    public void testIpWithoutTrustedProxies() {
        // Without trusted proxies, X-Forwarded-For should be ignored
        var headers = Map.of("X-Forwarded-For", "1.2.3.4, 10.0.0.1");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", null);
        assertEquals("10.0.0.1", req.ip());
    }

    @Test
    public void testIpWithTrustedProxy() {
        // Rightmost-untrusted: header "1.2.3.4, 10.0.0.5", 10.0.0.5 is trusted → returns 1.2.3.4
        // (Previously the leftmost-only bug also returned 1.2.3.4 in this case; semantics unchanged
        // when there is exactly one untrusted entry on the left.)
        var headers = Map.of("X-Forwarded-For", "1.2.3.4, 10.0.0.5");
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.2.3.4", req.ip());
    }

    @Test
    public void testIpWithUntrustedProxy() {
        // If immediate peer is not trusted, ignore X-Forwarded-For
        var headers = Map.of("X-Forwarded-For", "1.2.3.4");
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "192.168.1.1", trusted);
        assertEquals("192.168.1.1", req.ip());
    }

    @Test
    public void testIpMultipleForwardedFor() {
        // Multi-hop: "1.2.3.4, 5.6.7.8, 10.0.0.5" where 10.x is trusted.
        // Rightmost-untrusted walk: skip 10.0.0.5 (trusted), 5.6.7.8 is untrusted → 5.6.7.8.
        // NOTE: The old leftmost bug returned 1.2.3.4 here (wrong). The correct untrusted
        // rightmost is 5.6.7.8 — the address appended by the last untrusted hop.
        var headers = Map.of("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 10.0.0.5");
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        // 5.6.7.8 is the rightmost untrusted entry; 1.2.3.4 is client-appended (unverified)
        assertEquals("5.6.7.8", req.ip());
    }

    @Test
    public void testIpForwardedRFC7239() {
        // Test RFC 7239 Forwarded header — single element, no multi-element bug
        var headers = Map.of("Forwarded", "for=1.2.3.4;proto=https");
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.2.3.4", req.ip());
    }

    @Test
    public void testIpForwardedRFC7239IPv6() {
        // Test RFC 7239 Forwarded header with IPv6 bracketed form
        var headers = Map.of("Forwarded", "for=\"[2001:db8::1]\"");
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("2001:db8::1", req.ip());
    }

    @Test
    public void testIpXForwardedForPrecedence() {
        // X-Forwarded-For should take precedence over Forwarded
        var headers = Map.of(
            "X-Forwarded-For", "1.2.3.4",
            "Forwarded", "for=5.6.7.8"
        );
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.2.3.4", req.ip());
    }

    @Test
    public void testIpNoHeaders() {
        // Without forwarding headers, should return remote addr
        var headers = Map.<String, String>of();
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("10.0.0.1", req.ip());
    }

    @Test
    public void testIpNoRemoteAddr() {
        // If remote addr is null, should return "unknown"
        var headers = Map.of("X-Forwarded-For", "1.2.3.4");
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), null, trusted);
        assertEquals("unknown", req.ip());
    }

    @Test
    public void testIpEmptyForwardedFor() {
        // Empty X-Forwarded-For should be ignored
        var headers = Map.of("X-Forwarded-For", "");
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("10.0.0.1", req.ip());
    }

    @Test
    public void testIpPrivateNetworkProxies() {
        // Test all three private network ranges
        var trusted = new TrustedProxies("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

        // 10.x.x.x proxy with single untrusted entry
        var headers1 = Map.of("X-Forwarded-For", "1.2.3.4");
        var req1 = new Request("GET", "/", Map.of(), Map.of(), headers1, null, Map.of(), "10.1.2.3", trusted);
        assertEquals("1.2.3.4", req1.ip());

        // 172.16-31.x.x proxy with single untrusted entry
        var headers2 = Map.of("X-Forwarded-For", "5.6.7.8");
        var req2 = new Request("GET", "/", Map.of(), Map.of(), headers2, null, Map.of(), "172.20.0.1", trusted);
        assertEquals("5.6.7.8", req2.ip());

        // 192.168.x.x proxy with single untrusted entry
        var headers3 = Map.of("X-Forwarded-For", "9.10.11.12");
        var req3 = new Request("GET", "/", Map.of(), Map.of(), headers3, null, Map.of(), "192.168.1.1", trusted);
        assertEquals("9.10.11.12", req3.ip());
    }

    // -----------------------------------------------------------------------
    // New tests for H2 rightmost-untrusted semantics
    // -----------------------------------------------------------------------

    @Test
    public void testIpSpoofedLeftmost() {
        // Spoofed leftmost: attacker sends "1.2.3.4, 9.9.9.9"; the trusted proxy appends 9.9.9.9.
        // 9.9.9.9 is untrusted → must return 9.9.9.9, not the attacker-supplied 1.2.3.4.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", "1.2.3.4, 9.9.9.9");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("9.9.9.9", req.ip());
    }

    @Test
    public void testIpMultiHopBothProxiesTrusted() {
        // Multi-hop: "clientIP, proxy2, proxy1" where proxy1 and proxy2 are both trusted.
        // Walk right-to-left: proxy1 trusted, proxy2 trusted, clientIP untrusted → clientIP.
        var trusted = new TrustedProxies("10.0.0.0/8", "172.16.0.0/12");
        var headers = Map.of("X-Forwarded-For", "203.0.113.5, 172.16.0.2, 10.0.0.3");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("203.0.113.5", req.ip());
    }

    @Test
    public void testIpAllTrustedChain() {
        // All-trusted chain: every entry in the header is within trusted CIDRs.
        // Should return the leftmost (best guess at original client within our infrastructure).
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", "10.1.0.1, 10.2.0.2, 10.3.0.3");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("10.1.0.1", req.ip());
    }

    @Test
    public void testIpPortSuffixIPv4() {
        // IPv4 with port: "1.2.3.4:5678" → stripped to "1.2.3.4"
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", "1.2.3.4:5678");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.2.3.4", req.ip());
    }

    @Test
    public void testIpBracketedIPv6WithPort() {
        // Bracketed IPv6 with port: "[2001:db8::1]:443" → stripped to "2001:db8::1"
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", "[2001:db8::1]:443");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("2001:db8::1", req.ip());
    }

    @Test
    public void testIpBareIPv6Untouched() {
        // Bare IPv6 (no brackets, no port): colons are part of the address — must not be stripped.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", "2001:db8::cafe");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("2001:db8::cafe", req.ip());
    }

    @Test
    public void testIpForwardedMultiElement() {
        // RFC 7239 multi-element: "for=1.1.1.1, for=2.2.2.2" where 2.2.2.2 is untrusted.
        // Rightmost-untrusted: 2.2.2.2 is not in trusted CIDRs → returns 2.2.2.2.
        // (The old split-on-";" bug would have returned only the first element 1.1.1.1.)
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("Forwarded", "for=1.1.1.1, for=2.2.2.2");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("2.2.2.2", req.ip());
    }

    @Test
    public void testIpForwardedMultiElementTrustedRightmost() {
        // RFC 7239 multi-element: "for=1.1.1.1, for=10.0.0.5" where 10.0.0.5 is trusted.
        // Walk right-to-left: 10.0.0.5 trusted, 1.1.1.1 untrusted → 1.1.1.1.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("Forwarded", "for=1.1.1.1, for=10.0.0.5");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.1.1.1", req.ip());
    }

    @Test
    public void testIpForwardedMultiElementQuotedBracketed() {
        // RFC 7239 quoted and bracketed IPv6: for="[2001:db8::1]", for="[2001:db8::2]"
        // 2001:db8::2 is untrusted → returns 2001:db8::2.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("Forwarded", "for=\"[2001:db8::1]\", for=\"[2001:db8::2]\"");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("2001:db8::2", req.ip());
    }

    @Test
    public void testIpUntrustedImmediatePeerIgnoresHeaders() {
        // Untrusted immediate peer: header must be ignored entirely even if it looks clean.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", "9.9.9.9");
        // remoteAddr is 5.5.5.5 — not in 10.0.0.0/8
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "5.5.5.5", trusted);
        assertEquals("5.5.5.5", req.ip());
    }

    // -----------------------------------------------------------------------
    // CR5: blank/whitespace XFF segments skipped; no empty ip(), no crash on bare comma
    // -----------------------------------------------------------------------

    @Test
    public void testIpXffBareComma() {
        // Header of exactly "," splits to ["", ""] — all blank — must not crash and must fall
        // back to remoteAddr, not throw ArrayIndexOutOfBoundsException.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", ",");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("10.0.0.1", req.ip());
    }

    @Test
    public void testIpXffEmbeddedBlankSegment() {
        // "1.2.3.4,,10.0.0.5" — middle segment is blank; with 10/8 trusted, must return 1.2.3.4.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", "1.2.3.4,,10.0.0.5");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.2.3.4", req.ip());
    }

    @Test
    public void testIpXffWhitespaceOnlySegment() {
        // "1.2.3.4,   ,10.0.0.5" — middle segment is whitespace-only; same expectation.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", "1.2.3.4,   ,10.0.0.5");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.2.3.4", req.ip());
    }

    @Test
    public void testIpXffAllBlankSegments() {
        // " , , " — all blank — must fall back to remoteAddr, never return "".
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("X-Forwarded-For", " , , ");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("10.0.0.1", req.ip());
        assertFalse(req.ip().isEmpty());
    }

    @Test
    public void testIpForwardedBareComma() {
        // Forwarded header of exactly "," — no "for=" values extracted, must fall back to remoteAddr.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("Forwarded", ",");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("10.0.0.1", req.ip());
    }

    @Test
    public void testIpForwardedBlankElementSkipped() {
        // "for=1.2.3.4, , for=10.0.0.5" — blank element in the middle contributes no "for=";
        // with 10/8 trusted the walk returns 1.2.3.4.
        var trusted = new TrustedProxies("10.0.0.0/8");
        var headers = Map.of("Forwarded", "for=1.2.3.4, , for=10.0.0.5");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.2.3.4", req.ip());
    }

    // -----------------------------------------------------------------------
    // Unit tests for the stripPort helper
    // -----------------------------------------------------------------------

    @Test
    public void testStripPortIPv4WithPort() {
        assertEquals("1.2.3.4", Request.stripPort("1.2.3.4:5678"));
    }

    @Test
    public void testStripPortIPv4NoPort() {
        assertEquals("1.2.3.4", Request.stripPort("1.2.3.4"));
    }

    @Test
    public void testStripPortBracketedIPv6WithPort() {
        assertEquals("2001:db8::1", Request.stripPort("[2001:db8::1]:443"));
    }

    @Test
    public void testStripPortBracketedIPv6NoPort() {
        assertEquals("2001:db8::1", Request.stripPort("[2001:db8::1]"));
    }

    @Test
    public void testStripPortBareIPv6() {
        assertEquals("::1", Request.stripPort("::1"));
        assertEquals("2001:db8::cafe", Request.stripPort("2001:db8::cafe"));
    }
}
