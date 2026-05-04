package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class SessionOptionsTest {

    @Test
    public void testDefaultOptions() {
        var opts = SessionOptions.of("secret123");
        assertEquals("secret123", opts.secret());
        assertTrue(opts.httpOnly());
        assertFalse(opts.secure());
        assertEquals("Lax", opts.sameSite());
        assertNull(opts.maxAge());
        assertEquals("/", opts.path());
        assertNull(opts.domain());
    }

    @Test
    public void testSecureDefaults() {
        var opts = SessionOptions.secure("secret123");
        assertEquals("secret123", opts.secret());
        assertTrue(opts.httpOnly());
        assertTrue(opts.secure());
        assertEquals("Lax", opts.sameSite());
    }

    @Test
    public void testCustomHttpOnly() {
        var opts = SessionOptions.of("secret").httpOnly(false);
        assertFalse(opts.httpOnly());
    }

    @Test
    public void testCustomSecure() {
        var opts = SessionOptions.of("secret").secure(true);
        assertTrue(opts.secure());
    }

    @Test
    public void testSameSiteStrict() {
        var opts = SessionOptions.of("secret").sameSiteStrict();
        assertEquals("Strict", opts.sameSite());
    }

    @Test
    public void testSameSiteLax() {
        var opts = SessionOptions.of("secret").sameSiteLax();
        assertEquals("Lax", opts.sameSite());
    }

    @Test
    public void testSameSiteNone() {
        var opts = SessionOptions.of("secret").sameSiteNone();
        assertEquals("None", opts.sameSite());
        assertTrue(opts.secure()); // Should auto-enable Secure
    }

    @Test
    public void testMaxAge() {
        var opts = SessionOptions.of("secret").maxAge(Duration.ofHours(2));
        assertEquals(Duration.ofHours(2), opts.maxAge());
    }

    @Test
    public void testMaxAgeDays() {
        var opts = SessionOptions.of("secret").maxAgeDays(14);
        assertEquals(Duration.ofDays(14), opts.maxAge());
    }

    @Test
    public void testCustomPath() {
        var opts = SessionOptions.of("secret").path("/app");
        assertEquals("/app", opts.path());
    }

    @Test
    public void testCustomDomain() {
        var opts = SessionOptions.of("secret").domain(".example.com");
        assertEquals(".example.com", opts.domain());
    }

    @Test
    public void testBuildSetCookieBasic() {
        var opts = SessionOptions.of("secret");
        var cookie = opts.buildSetCookie("cookievalue123");
        assertEquals("brace_session=cookievalue123; Path=/; HttpOnly; SameSite=Lax", cookie);
    }

    @Test
    public void testBuildSetCookieWithSecure() {
        var opts = SessionOptions.secure("secret");
        var cookie = opts.buildSetCookie("cookievalue123");
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
    }

    @Test
    public void testBuildSetCookieWithMaxAge() {
        var opts = SessionOptions.of("secret").maxAgeDays(7);
        var cookie = opts.buildSetCookie("cookievalue123");
        assertTrue(cookie.contains("Max-Age=" + (7 * 24 * 60 * 60)));
    }

    @Test
    public void testBuildSetCookieWithDomain() {
        var opts = SessionOptions.of("secret").domain(".example.com");
        var cookie = opts.buildSetCookie("cookievalue123");
        assertTrue(cookie.contains("Domain=.example.com"));
    }

    @Test
    public void testBuildSetCookieComplete() {
        var opts = SessionOptions.secure("secret")
            .maxAgeDays(30)
            .domain(".example.com")
            .path("/app")
            .sameSiteStrict();

        var cookie = opts.buildSetCookie("val");
        assertTrue(cookie.startsWith("brace_session=val"));
        assertTrue(cookie.contains("Path=/app"));
        assertTrue(cookie.contains("Domain=.example.com"));
        assertTrue(cookie.contains("Max-Age=" + (30 * 24 * 60 * 60)));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict"));
    }

    @Test
    public void testFluentAPI() {
        var opts = SessionOptions.of("secret")
            .secure(true)
            .httpOnly(true)
            .maxAgeDays(14)
            .sameSiteStrict()
            .path("/api")
            .domain(".example.com");

        assertEquals("secret", opts.secret());
        assertTrue(opts.secure());
        assertTrue(opts.httpOnly());
        assertEquals(Duration.ofDays(14), opts.maxAge());
        assertEquals("Strict", opts.sameSite());
        assertEquals("/api", opts.path());
        assertEquals(".example.com", opts.domain());
    }
}
