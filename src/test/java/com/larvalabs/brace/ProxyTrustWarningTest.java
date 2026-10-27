package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L5: a private-network peer sending forwarding headers without being a trusted proxy is warned
 * about once, and {@code trustedProxies(String...)} takes a config value straight through
 * (comma-separated, blanks and nulls skipped).
 */
class ProxyTrustWarningTest {

    static final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        LogTap.clear();
    }

    private static Brace start(Brace app) throws Exception {
        app.port(0).banner(false);
        app.get("/ip", req -> Result.text(req.ip())).publicRoute();
        app.start();
        return app;
    }

    private static String get(Brace app, String forwardedFor) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.actualPort() + "/ip"));
        if (forwardedFor != null) builder.header("X-Forwarded-For", forwardedFor);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static java.util.List<String> proxyWarnings() {
        return LogTap.snapshot().stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .map(e -> String.valueOf(e.fields().get("message")))
            .filter(m -> m.contains("carry X-Forwarded-For/Forwarded"))
            .toList();
    }

    @Test
    void warnsOnceWhenNoProxyIsConfigured() throws Exception {
        Brace app = start(Brace.app());
        try {
            assertEquals("127.0.0.1", get(app, "203.0.113.9"), "the header is ignored");
            get(app, "203.0.113.10");
            var warnings = proxyWarnings();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("no trusted proxies are configured"), warnings.get(0));
            assertTrue(warnings.get(0).contains("TRUSTED_PROXIES"), warnings.get(0));
        } finally {
            app.stop();
        }
    }

    @Test
    void warnsWhenThePeerIsNotInTheConfiguredList() throws Exception {
        Brace app = start(Brace.app().trustedProxies("10.9.8.7"));
        try {
            assertEquals("127.0.0.1", get(app, "203.0.113.9"));
            var warnings = proxyWarnings();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("isn't in trustedProxies"), warnings.get(0));
        } finally {
            app.stop();
        }
    }

    @Test
    void noWarningWithoutForwardingHeadersOrFromATrustedProxy() throws Exception {
        Brace plain = start(Brace.app());
        Brace trusted = start(Brace.app().trustedProxies(" 127.0.0.1 , ::1 "));
        try {
            assertEquals("127.0.0.1", get(plain, null));
            assertEquals("203.0.113.9", get(trusted, "203.0.113.9"), "comma-separated value parsed");
            assertEquals(java.util.List.of(), proxyWarnings());
        } finally {
            plain.stop();
            trusted.stop();
        }
    }

    @Test
    void blankOrNullConfigMeansNoTrustedProxies() throws Exception {
        Brace app = start(Brace.app().trustedProxies((String) null).trustedProxies("", " , "));
        try {
            assertEquals("127.0.0.1", get(app, "203.0.113.9"));
        } finally {
            app.stop();
        }
    }
}
