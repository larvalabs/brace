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
        // With trusted proxy, X-Forwarded-For should be parsed
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
        // Should return the first (client) IP in the chain
        var headers = Map.of("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 10.0.0.5");
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.2.3.4", req.ip());
    }

    @Test
    public void testIpForwardedRFC7239() {
        // Test RFC 7239 Forwarded header
        var headers = Map.of("Forwarded", "for=1.2.3.4;proto=https");
        var trusted = new TrustedProxies("10.0.0.0/8");
        var req = new Request("GET", "/", Map.of(), Map.of(), headers, null, Map.of(), "10.0.0.1", trusted);
        assertEquals("1.2.3.4", req.ip());
    }

    @Test
    public void testIpForwardedRFC7239IPv6() {
        // Test RFC 7239 Forwarded header with IPv6
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

        // 10.x.x.x proxy
        var headers1 = Map.of("X-Forwarded-For", "1.2.3.4");
        var req1 = new Request("GET", "/", Map.of(), Map.of(), headers1, null, Map.of(), "10.1.2.3", trusted);
        assertEquals("1.2.3.4", req1.ip());

        // 172.16-31.x.x proxy
        var headers2 = Map.of("X-Forwarded-For", "5.6.7.8");
        var req2 = new Request("GET", "/", Map.of(), Map.of(), headers2, null, Map.of(), "172.20.0.1", trusted);
        assertEquals("5.6.7.8", req2.ip());

        // 192.168.x.x proxy
        var headers3 = Map.of("X-Forwarded-For", "9.10.11.12");
        var req3 = new Request("GET", "/", Map.of(), Map.of(), headers3, null, Map.of(), "192.168.1.1", trusted);
        assertEquals("9.10.11.12", req3.ip());
    }
}
