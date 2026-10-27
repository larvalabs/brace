package com.larvalabs.brace;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H5: {@link Http} refuses destinations that aren't on the public internet unless the request
 * is {@code trusted()}, and caps buffered response bodies at {@code maxResponseBytes}.
 *
 * <p>The classifier is tested on address literals, which the JDK parses without DNS, so nothing
 * here needs the network. End-to-end checks run against a local server that counts the
 * requests it receives, so a refused request is shown never to have arrived.
 */
class HttpSsrfTest {

    static HttpServer server;
    static int port;
    static final AtomicInteger hits = new AtomicInteger();

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            var body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        // ?n=<bytes>[&chunked]: a body of n bytes, with or without Content-Length.
        server.createContext("/bytes", exchange -> {
            var query = exchange.getRequestURI().getQuery();
            int n = Integer.parseInt(query.replaceAll(".*n=(\\d+).*", "$1"));
            boolean chunked = query.contains("chunked");
            exchange.sendResponseHeaders(200, chunked ? 0 : n);
            try (var out = exchange.getResponseBody()) {
                var block = new byte[8192];
                java.util.Arrays.fill(block, (byte) 'x');
                for (int left = n; left > 0; left -= block.length) {
                    out.write(block, 0, Math.min(left, block.length));
                }
            } catch (java.io.IOException ignored) {
                // The client hung up once it had seen enough: that's the behavior under test.
            }
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void resetHits() {
        hits.set(0);
    }

    // --- The address classifier ---

    @ParameterizedTest
    @ValueSource(strings = {
        "0.0.0.0", "0.1.2.3", "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255",
        "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "100.127.255.255",
        "169.254.169.254", "169.254.0.1", "192.0.0.170", "192.0.2.1", "198.51.100.7",
        "203.0.113.9", "198.18.0.1", "198.19.255.255", "192.88.99.1", "224.0.0.1",
        "239.255.255.250", "240.0.0.1", "255.255.255.255",
        "::", "::1", "::127.0.0.1", "::8.8.8.8", "fc00::1", "fd12:3456::1", "fe80::1",
        "febf::1", "fec0::1", "ff02::1", "100::1", "64:ff9b:1::1",
        "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b::a00:1",
        "2002:7f00:1::", "2002:a9fe:a9fe::1", "2002:c0a8:101::",
        "2001::1", "2001:0:4136:e378::1", "2001:2::1", "2001:db8::1", "3fff::1"
    })
    void nonPublicAddressesAreBlocked(String literal) throws Exception {
        assertFalse(Http.isPublicAddress(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8", "1.1.1.1", "93.184.216.34", "100.63.255.255", "100.128.0.0", "172.15.255.255",
        "172.32.0.0", "169.253.255.255", "192.167.255.255", "198.17.255.255", "198.20.0.0",
        "223.255.255.255", "11.0.0.1", "126.255.255.255", "128.0.0.1",
        "2606:4700:4700::1111", "2001:4860:4860::8888", "2a00:1450::1", "64:ff9b::808:808",
        "2002:808:808::1", "2001:db9::1", "3fff:1000::1"
    })
    void publicAddressesPass(String literal) throws Exception {
        assertTrue(Http.isPublicAddress(InetAddress.getByName(literal)), literal);
    }

    @Test
    void ipv4MappedAddressesAreJudgedByTheEmbeddedAddress() throws Exception {
        // The JDK normally turns ::ffff:a.b.c.d into an Inet4Address; build the Inet6 form
        // directly to cover the classifier's own handling of it.
        var loopback = new byte[16];
        loopback[10] = (byte) 0xff;
        loopback[11] = (byte) 0xff;
        loopback[12] = 127;
        loopback[15] = 1;
        assertFalse(Http.isPublicAddress(Inet6Address.getByAddress(null, loopback, -1)));
        var metadata = loopback.clone();
        metadata[12] = (byte) 169; metadata[13] = (byte) 254; metadata[14] = (byte) 169; metadata[15] = (byte) 254;
        assertFalse(Http.isPublicAddress(Inet6Address.getByAddress(null, metadata, -1)));
        var google = loopback.clone();
        google[12] = 8; google[13] = 8; google[14] = 8; google[15] = 8;
        assertTrue(Http.isPublicAddress(Inet6Address.getByAddress(null, google, -1)));

        assertFalse(Http.isPublicAddress(InetAddress.getByName("::ffff:127.0.0.1")));
        assertFalse(Http.isPublicAddress(InetAddress.getByName("::ffff:10.1.2.3")));
        assertTrue(Http.isPublicAddress(InetAddress.getByName("::ffff:1.1.1.1")));
    }

    @Test
    void aPublicLiteralPassesThePolicyCheck() {
        assertDoesNotThrow(() -> Http.checkDestination("8.8.8.8"));
        assertDoesNotThrow(() -> Http.checkDestination("[2606:4700:4700::1111]"));
    }

    @Test
    void aNameIsBlockedIfAnyOfItsAddressesIsNotPublic() throws Exception {
        var mixed = new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.5")};
        withResolver(host -> mixed, () -> {
            var e = assertThrows(Http.BlockedDestinationException.class,
                () -> Http.checkDestination("mixed.example"));
            assertEquals("mixed.example", e.host());
            assertEquals("10.0.0.5", e.address().getHostAddress());
        });
    }

    // --- End to end: refused before anything is sent ---

    /** Every way of writing the local server's address, plus the private ranges' representatives. */
    static List<String> blockedHosts() {
        return List.of("127.0.0.1", "localhost", "0.0.0.0", "2130706433", "[::ffff:127.0.0.1]",
            "[::1]", "169.254.169.254", "10.0.0.1", "172.16.0.1", "192.168.1.1", "100.64.0.1",
            "[fc00::1]");
    }

    @Test
    void everyFetchVariantRefusesNonPublicDestinations() {
        for (var host : blockedHosts()) {
            var url = "http://" + host + ":" + port + "/";
            assertBlocked(host, () -> Http.get(url).fetch());
            assertBlocked(host, () -> Http.get(url).fetchString());
            assertBlocked(host, () -> Http.get(url).fetchBytes());
            assertBlocked(host, () -> Http.get(url).fetchJson(Map.class));
            assertBlocked(host, () -> Http.get(url).stream());
            assertBlocked(host, () -> Http.get(url).fetchEvents(event -> { }));
            assertBlocked(host, () -> Http.post(url).bodyJson(Map.of("a", 1)).fetch());
            assertBlocked(host, () -> Http.put(url).fetch());
            assertBlocked(host, () -> Http.delete(url).fetch());
            assertBlocked(host, () -> Http.post(url).multipart().field("a", "b").fetch());
            assertBlocked(host, () -> Http.post(url).multipart().field("a", "b").stream());
        }
        assertEquals(0, hits.get(), "a refused request must never reach the server");
    }

    @Test
    void theMessageNamesTheHostAndTheEscapeHatchButNotTheAddress() {
        var e = assertThrows(Http.BlockedDestinationException.class,
            () -> Http.get("http://localhost:" + port + "/secret?token=abc").fetch());
        assertTrue(e.getMessage().contains("localhost"), e.getMessage());
        assertTrue(e.getMessage().contains(".trusted()"), e.getMessage());
        // The resolved address is for logs, not the message: shown to a user, it would map
        // internal names to private IPs.
        assertTrue(e.address().isLoopbackAddress());
        assertFalse(e.getMessage().contains("127.0.0.1"), e.getMessage());
        // Nothing was connected to, so nothing about the port or path is reported.
        assertFalse(e.getMessage().contains(String.valueOf(port)), e.getMessage());
        assertFalse(e.getMessage().contains("token"), e.getMessage());
    }

    @Test
    void nonCanonicalNumericHostsAreRefusedWhateverTheyResolveTo() {
        // 0177.0.0.1 is 177.0.0.1 (public) to Java but 127.0.0.1 to a proxy using inet_aton.
        for (var host : List.of("0177.0.0.1", "010.0.0.1", "2130706433", "0x7f000001", "127.1", "1.2.3.4.5", "8.8.8.08")) {
            assertTrue(Http.isNonCanonicalNumericHost(host), host);
        }
        var e = assertThrows(Http.BlockedDestinationException.class,
            () -> Http.get("http://0177.0.0.1:" + port + "/").fetch());
        assertNull(e.address());
        assertTrue(e.getMessage().contains("dotted quad"), e.getMessage());
        for (var host : List.of("8.8.8.8", "0.0.0.0", "255.255.255.255", "8.8.8.8.", "deadbeef.cafe",
                "123.example.com", "1e100.net", "[::1]", "example.com")) {
            assertFalse(Http.isNonCanonicalNumericHost(host), host);
        }
        assertEquals(0, hits.get());
    }

    @Test
    void azureWireServerIsRefused() throws Exception {
        assertFalse(Http.isPublicAddress(java.net.InetAddress.getByName("168.63.129.16")));
        assertTrue(Http.isPublicAddress(java.net.InetAddress.getByName("168.63.129.17")));
    }

    @Test
    void metadataServiceIsRefused() {
        var e = assertThrows(Http.BlockedDestinationException.class,
            () -> Http.get("http://169.254.169.254/hetzner/v1/userdata").fetchString());
        assertEquals("169.254.169.254", e.address().getHostAddress());
    }

    @Test
    void trustedBypassesThePolicy() {
        for (var host : List.of("127.0.0.1", "localhost", "2130706433", "[::ffff:127.0.0.1]")) {
            var resp = Http.get("http://" + host + ":" + port + "/").trusted().fetch();
            assertEquals(200, resp.status(), host);
            assertEquals("ok", resp.body());
        }
        var multipart = Http.post("http://127.0.0.1:" + port + "/").multipart().trusted().field("a", "b").fetch();
        assertEquals(200, multipart.status());
        try (var stream = Http.get("http://127.0.0.1:" + port + "/").trusted().stream()) {
            assertEquals("ok", stream.readString());
        }
        assertEquals(6, hits.get());
    }

    @Test
    void aNameThatTurnsPrivateMidRequestIsDiscardedBeforeTheCallerSeesIt() throws Exception {
        // DNS rebinding: the first check sees a public address, the connect (here, the real
        // resolver) reaches loopback, and the check made when the headers arrive catches it.
        var calls = new AtomicInteger();
        var publicAddress = new InetAddress[] {InetAddress.getByName("8.8.8.8")};
        withResolver(host -> calls.getAndIncrement() == 0 ? publicAddress : InetAddress.getAllByName(host), () -> {
            assertThrows(Http.BlockedDestinationException.class,
                () -> Http.get("http://localhost:" + port + "/").fetchString());
        });
        assertEquals(2, calls.get(), "the destination is checked before sending and on the response");
        calls.set(0);
        withResolver(host -> calls.getAndIncrement() == 0 ? publicAddress : InetAddress.getAllByName(host), () -> {
            assertThrows(Http.BlockedDestinationException.class,
                () -> Http.get("http://localhost:" + port + "/").stream());
        });
    }

    // --- maxResponseBytes ---

    @Test
    void bodiesOverTheLimitFailWithAndWithoutContentLength() {
        var base = "http://127.0.0.1:" + port + "/bytes?n=";
        for (var suffix : List.of("", "&chunked")) {
            var e = assertThrows(Http.ResponseTooLargeException.class,
                () -> Http.get(base + 1001 + suffix).trusted().maxResponseBytes(1000).fetch());
            assertEquals(1000, e.limit());
            assertTrue(e.getMessage().contains(".maxResponseBytes("), e.getMessage());
            assertThrows(Http.ResponseTooLargeException.class,
                () -> Http.get(base + 5000 + suffix).trusted().maxResponseBytes(1000).fetchBytes());
            assertThrows(Http.ResponseTooLargeException.class,
                () -> Http.get(base + 5000 + suffix).trusted().maxResponseBytes(1000).fetchString());
            assertThrows(Http.ResponseTooLargeException.class,
                () -> Http.post(base + 5000 + suffix).multipart().trusted().maxResponseBytes(1000).field("a", "b").fetch());

            assertEquals(1000, Http.get(base + 1000 + suffix).trusted().maxResponseBytes(1000).fetchBytes().length);
        }
    }

    @Test
    void theDefaultLimitIsTenMegabytes() {
        assertEquals(10L * 1024 * 1024, Http.DEFAULT_MAX_RESPONSE_BYTES);
        var url = "http://127.0.0.1:" + port + "/bytes?chunked&n=";
        assertThrows(Http.ResponseTooLargeException.class,
            () -> Http.get(url + (Http.DEFAULT_MAX_RESPONSE_BYTES + 1)).trusted().fetchBytes());
        assertEquals(Http.DEFAULT_MAX_RESPONSE_BYTES,
            Http.get(url + Http.DEFAULT_MAX_RESPONSE_BYTES).trusted().fetchBytes().length);
    }

    @Test
    void streamIsNotCapped() throws Exception {
        try (var s = Http.get("http://127.0.0.1:" + port + "/bytes?n=5000").trusted().maxResponseBytes(1000).stream()) {
            assertEquals(5000, s.body().readAllBytes().length);
        }
    }

    @Test
    void maxResponseBytesMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Http.get("http://example.com/").maxResponseBytes(0));
        assertThrows(IllegalArgumentException.class, () -> Http.get("http://example.com/").maxResponseBytes(-1));
    }

    // --- helpers ---

    private static void assertBlocked(String host, Executable call) {
        var e = assertThrows(Http.BlockedDestinationException.class, call, host);
        // A non-canonical numeric spelling (2130706433) is refused before any lookup.
        if (e.address() != null) assertFalse(Http.isPublicAddress(e.address()), host);
        else assertTrue(Http.isNonCanonicalNumericHost(e.host()), host);
    }

    private static void withResolver(Http.Resolver resolver, Executable body) throws Exception {
        var previous = Http.resolver;
        Http.resolver = resolver;
        try {
            body.execute();
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        } finally {
            Http.resolver = previous;
        }
    }
}
