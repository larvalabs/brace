package io.brace;

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
}
