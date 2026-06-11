package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TrustedProxiesTest {

    @Test
    public void testSingleIPv4() {
        var trusted = new TrustedProxies("10.0.0.1");
        assertTrue(trusted.isTrusted("10.0.0.1"));
        assertFalse(trusted.isTrusted("10.0.0.2"));
        assertFalse(trusted.isTrusted("192.168.1.1"));
    }

    @Test
    public void testIPv4CIDR() {
        var trusted = new TrustedProxies("10.0.0.0/8");
        assertTrue(trusted.isTrusted("10.0.0.1"));
        assertTrue(trusted.isTrusted("10.255.255.255"));
        assertTrue(trusted.isTrusted("10.1.2.3"));
        assertFalse(trusted.isTrusted("11.0.0.1"));
        assertFalse(trusted.isTrusted("9.255.255.255"));
    }

    @Test
    public void testPrivateNetworks() {
        var trusted = new TrustedProxies("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

        // 10.x.x.x
        assertTrue(trusted.isTrusted("10.0.0.1"));
        assertTrue(trusted.isTrusted("10.255.255.255"));

        // 172.16-31.x.x
        assertTrue(trusted.isTrusted("172.16.0.1"));
        assertTrue(trusted.isTrusted("172.31.255.255"));
        assertFalse(trusted.isTrusted("172.15.255.255"));
        assertFalse(trusted.isTrusted("172.32.0.1"));

        // 192.168.x.x
        assertTrue(trusted.isTrusted("192.168.0.1"));
        assertTrue(trusted.isTrusted("192.168.255.255"));
        assertFalse(trusted.isTrusted("192.169.0.1"));
    }

    @Test
    public void testMultipleRanges() {
        var trusted = new TrustedProxies("10.0.1.0/24", "192.168.1.0/24");
        assertTrue(trusted.isTrusted("10.0.1.5"));
        assertTrue(trusted.isTrusted("192.168.1.100"));
        assertFalse(trusted.isTrusted("10.0.2.5"));
        assertFalse(trusted.isTrusted("192.168.2.100"));
    }

    @Test
    public void testLocalhostIPv4() {
        var trusted = new TrustedProxies("127.0.0.0/8");
        assertTrue(trusted.isTrusted("127.0.0.1"));
        assertTrue(trusted.isTrusted("127.0.0.2"));
        assertTrue(trusted.isTrusted("127.255.255.255"));
        assertFalse(trusted.isTrusted("128.0.0.1"));
    }

    @Test
    public void testIPv6() {
        var trusted = new TrustedProxies("::1");
        assertTrue(trusted.isTrusted("::1"));
        assertTrue(trusted.isTrusted("0:0:0:0:0:0:0:1"));
        assertFalse(trusted.isTrusted("::2"));
    }

    @Test
    public void testIPv6CIDR() {
        var trusted = new TrustedProxies("2001:db8::/32");
        assertTrue(trusted.isTrusted("2001:db8::1"));
        assertTrue(trusted.isTrusted("2001:db8:0:0:0:0:0:1"));
        assertFalse(trusted.isTrusted("2001:db9::1"));
    }

    @Test
    public void testEmptyIP() {
        var trusted = new TrustedProxies("10.0.0.0/8");
        assertFalse(trusted.isTrusted(""));
        assertFalse(trusted.isTrusted(null));
    }

    @Test
    public void testInvalidIP() {
        var trusted = new TrustedProxies("10.0.0.0/8");
        assertFalse(trusted.isTrusted("not-an-ip"));
        assertFalse(trusted.isTrusted("999.999.999.999"));
    }

    @Test
    public void testInvalidCIDR() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TrustedProxies("10.0.0.0/invalid");
        });
    }

    @Test
    public void testNoTrustedProxies() {
        var trusted = new TrustedProxies();
        assertFalse(trusted.isTrusted("10.0.0.1"));
        assertFalse(trusted.isTrusted("192.168.1.1"));
    }

    // -----------------------------------------------------------------------
    // CR1: isIpLiteral validator — hostname input must never trigger DNS
    // -----------------------------------------------------------------------

    @Test
    public void testIsIpLiteralValidIPv4() {
        assertTrue(TrustedProxies.isIpLiteral("1.2.3.4"));
        assertTrue(TrustedProxies.isIpLiteral("0.0.0.0"));
        assertTrue(TrustedProxies.isIpLiteral("255.255.255.255"));
        assertTrue(TrustedProxies.isIpLiteral("10.0.0.1"));
        assertTrue(TrustedProxies.isIpLiteral("192.168.1.100"));
    }

    @Test
    public void testIsIpLiteralValidIPv6() {
        assertTrue(TrustedProxies.isIpLiteral("::1"));
        assertTrue(TrustedProxies.isIpLiteral("2001:db8::1"));
        assertTrue(TrustedProxies.isIpLiteral("0:0:0:0:0:0:0:1"));
        assertTrue(TrustedProxies.isIpLiteral("fe80::1"));
        assertTrue(TrustedProxies.isIpLiteral("::ffff:192.0.2.1")); // IPv4-mapped
    }

    @Test
    public void testIsIpLiteralRejectsHostnames() {
        assertFalse(TrustedProxies.isIpLiteral("a1.attacker.com"));
        assertFalse(TrustedProxies.isIpLiteral("localhost"));
        assertFalse(TrustedProxies.isIpLiteral("evil.example.org"));
        assertFalse(TrustedProxies.isIpLiteral("proxy.internal"));
    }

    @Test
    public void testIsIpLiteralRejectsGarbage() {
        assertFalse(TrustedProxies.isIpLiteral(""));
        assertFalse(TrustedProxies.isIpLiteral(null));
        assertFalse(TrustedProxies.isIpLiteral("not-an-ip"));
        assertFalse(TrustedProxies.isIpLiteral("999.999.999.999"));
        assertFalse(TrustedProxies.isIpLiteral("1.2.3"));
        assertFalse(TrustedProxies.isIpLiteral("1.2.3.4.5"));
        assertFalse(TrustedProxies.isIpLiteral("01.2.3.4"));   // leading zero
        assertFalse(TrustedProxies.isIpLiteral("1.2.3.256"));  // octet out of range
    }

    @Test
    public void testIsTrustedRejectsHostname() {
        // CR1: isTrusted must return false for hostname-shaped strings — no DNS lookup attempted.
        var trusted = new TrustedProxies("10.0.0.0/8");
        assertFalse(trusted.isTrusted("a1.attacker.com"));
        assertFalse(trusted.isTrusted("localhost"));
        assertFalse(trusted.isTrusted("evil.example.org"));
    }
}
