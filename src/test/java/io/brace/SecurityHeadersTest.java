package io.brace;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SecurityHeaders middleware.
 */
class SecurityHeadersTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app()
            .port(0)
            .after(SecurityHeaders.defaults());

        app.get("/hello", req -> Result.text("hello"));
        app.get("/custom", req -> Result.text("custom").header("X-Custom", "value"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @Test
    void defaultSecurityHeadersAreSet() throws Exception {
        var response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/hello"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("strict-origin-when-cross-origin", response.headers().firstValue("Referrer-Policy").orElse(null));
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(null));
        assertEquals("interest-cohort=()", response.headers().firstValue("Permissions-Policy").orElse(null));
    }

    @Test
    void securityHeadersWorkWithCustomHeaders() throws Exception {
        var response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/custom"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

        // Both custom and security headers present
        assertEquals("value", response.headers().firstValue("X-Custom").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
    }

    @Test
    void customSecurityHeadersBuilder() {
        var middleware = SecurityHeaders.builder()
            .contentTypeOptions("nosniff")
            .referrerPolicy("no-referrer")
            .frameOptions("SAMEORIGIN")
            .permissionsPolicy("geolocation=(), microphone=()")
            .strictTransportSecurity("max-age=31536000")
            .contentSecurityPolicy("default-src 'self'")
            .header("X-Custom-Security", "enabled")
            .build();

        var req = new Request("GET", "/", java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), null);
        var result = Result.text("ok");
        var updated = middleware.handle(req, result);

        assertEquals("nosniff", updated.header("X-Content-Type-Options"));
        assertEquals("no-referrer", updated.header("Referrer-Policy"));
        assertEquals("SAMEORIGIN", updated.header("X-Frame-Options"));
        assertEquals("geolocation=(), microphone=()", updated.header("Permissions-Policy"));
        assertEquals("max-age=31536000", updated.header("Strict-Transport-Security"));
        assertEquals("default-src 'self'", updated.header("Content-Security-Policy"));
        assertEquals("enabled", updated.header("X-Custom-Security"));
    }
}

