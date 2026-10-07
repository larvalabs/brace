package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the page-view classifier and the small parsers behind {@link Analytics}. */
class AnalyticsTest {

    static final String CHROME_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";
    static final String SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
    static final String FIREFOX_WIN = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0";
    static final String EDGE_WIN = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0";
    static final String ANDROID_TABLET = "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";
    static final String GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    private static final Route PAGE = new Route("GET", "/posts/{slug}", (Handler) r -> null, null);
    private static final Analytics.Options DEFAULTS = Analytics.options();

    private static Request get(String path, Map<String, String> headers) {
        return get(path, headers, Map.of(), "203.0.113.9", null);
    }

    private static Request get(String path, Map<String, String> headers, Map<String, String> query,
                               String remoteAddr, TrustedProxies proxies) {
        return new Request("GET", path, Map.of(), query, headers, null, Map.of(), remoteAddr, proxies);
    }

    private static Map<String, String> browser(String... extra) {
        var h = new HashMap<String, String>();
        h.put("User-Agent", CHROME_MAC);
        h.put("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
        h.put("Sec-Fetch-Mode", "navigate");
        for (int i = 0; i < extra.length; i += 2) {
            if (extra[i + 1] == null) h.remove(extra[i]);
            else h.put(extra[i], extra[i + 1]);
        }
        return h;
    }

    private static Analytics.Verdict classify(Request req) {
        return Analytics.classify(req, PAGE, Result.html("<p>hi</p>"), DEFAULTS);
    }

    @Test
    void browserNavigationToAnHtmlPageCounts() {
        assertEquals(Analytics.Verdict.COUNT, classify(get("/posts/hello", browser())));
    }

    @Test
    void olderBrowserWithoutFetchMetadataStillCounts() {
        assertEquals(Analytics.Verdict.COUNT, classify(get("/posts/hello", browser("Sec-Fetch-Mode", null))));
    }

    @Test
    void nonCandidatesAreIgnoredNotTallied() {
        var html = Result.html("<p>hi</p>");
        var post = new Request("POST", "/posts/hello", Map.of(), Map.of(), browser(), null);
        assertEquals(Analytics.Verdict.IGNORE, Analytics.classify(post, PAGE, html, DEFAULTS));
        // No route matched: static files and 404s.
        assertEquals(Analytics.Verdict.IGNORE, Analytics.classify(get("/x", browser()), null, html, DEFAULTS));
        // JSON from a page route, and error pages.
        assertEquals(Analytics.Verdict.IGNORE,
            Analytics.classify(get("/posts/hello", browser()), PAGE, Json.of(Map.of("a", 1)), DEFAULTS));
        assertEquals(Analytics.Verdict.IGNORE,
            Analytics.classify(get("/posts/hello", browser()), PAGE, new Result(404, "text/html", "gone"), DEFAULTS));
        // The framework's own routes.
        var ops = new Route("GET", "/ops/dashboard", (Handler) r -> null, null);
        assertEquals(Analytics.Verdict.IGNORE, Analytics.classify(get("/ops/dashboard", browser()), ops, html, DEFAULTS));
    }

    @Test
    void routeLevelOptOut() {
        var secret = new Route("GET", "/reset/{token}", (Handler) r -> null, null);
        secret.setAnalytics(Analytics.Track.OFF);
        assertEquals(Analytics.Verdict.IGNORE,
            Analytics.classify(get("/reset/abc", browser()), secret, Result.html("x"), DEFAULTS));
    }

    @Test
    void notModifiedCountsOnlyForAnHtmlRequest() {
        var nm = Result.notModified();
        assertEquals(Analytics.Verdict.COUNT, Analytics.classify(get("/posts/a", browser()), PAGE, nm, DEFAULTS));
        assertEquals(Analytics.Verdict.IGNORE,
            Analytics.classify(get("/posts/a", browser("Accept", "application/json")), PAGE, nm, DEFAULTS));
    }

    @Test
    void htmxPartialIsRejectedButBoostedNavigationCounts() {
        assertEquals(Analytics.Verdict.HTMX,
            classify(get("/posts/a", browser("HX-Request", "true", "Sec-Fetch-Mode", "cors"))));
        assertEquals(Analytics.Verdict.COUNT,
            classify(get("/posts/a", browser("HX-Request", "true", "HX-Boosted", "true", "Sec-Fetch-Mode", "cors"))));
        assertEquals(Analytics.Verdict.COUNT,
            classify(get("/posts/a", browser("HX-Request", "true", "HX-History-Restore-Request", "true"))));
    }

    @Test
    void fetchesPrefetchesAndBotsAreRejected() {
        assertEquals(Analytics.Verdict.BACKGROUND, classify(get("/posts/a", browser("Sec-Fetch-Mode", "cors"))));
        assertEquals(Analytics.Verdict.PREFETCH, classify(get("/posts/a", browser("Sec-Purpose", "prefetch;prerender"))));
        assertEquals(Analytics.Verdict.PREFETCH, classify(get("/posts/a", browser("Purpose", "prefetch"))));
        assertEquals(Analytics.Verdict.BOT, classify(get("/posts/a", browser("User-Agent", GOOGLEBOT, "Sec-Fetch-Mode", null))));
        assertEquals(Analytics.Verdict.BOT, classify(get("/posts/a", browser("User-Agent", "curl/8.4.0", "Sec-Fetch-Mode", null))));
        assertEquals(Analytics.Verdict.BOT, classify(get("/posts/a", browser("User-Agent", null, "Sec-Fetch-Mode", null))));
    }

    @Test
    void strictNavigationRequiresFetchMetadata() {
        var strict = Analytics.options().strictNavigation(true);
        var html = Result.html("x");
        assertEquals(Analytics.Verdict.BOT,
            Analytics.classify(get("/posts/a", browser("Sec-Fetch-Mode", null)), PAGE, html, strict));
        assertEquals(Analytics.Verdict.COUNT, Analytics.classify(get("/posts/a", browser()), PAGE, html, strict));
    }

    @Test
    void exclusions() {
        var opts = Analytics.options().exclude("/admin/*").excludeIps("198.51.100.0/24");
        var html = Result.html("x");
        var admin = new Route("GET", "/admin/users", (Handler) r -> null, null);
        assertEquals(Analytics.Verdict.EXCLUDED, Analytics.classify(get("/admin/users", browser()), admin, html, opts));
        assertEquals(Analytics.Verdict.EXCLUDED, Analytics.classify(
            get("/posts/a", browser(), Map.of(), "198.51.100.20", null), PAGE, html, opts));
        assertEquals(Analytics.Verdict.EXCLUDED, Analytics.classify(
            get("/posts/a", browser("Cookie", "theme=dark; " + Analytics.IGNORE_COOKIE + "=1")), PAGE, html, opts));
        assertEquals(Analytics.Verdict.COUNT, Analytics.classify(get("/posts/a", browser()), PAGE, html, opts));
    }

    @Test
    void userAgentFamilies() {
        assertFalse(UserAgents.isBot(CHROME_MAC));
        assertFalse(UserAgents.isBot(SAFARI_IPHONE));
        assertTrue(UserAgents.isBot(GOOGLEBOT));
        assertTrue(UserAgents.isBot("python-requests/2.32"));
        assertTrue(UserAgents.isBot("Mozilla/5.0 (X11; Linux x86_64) HeadlessChrome/129.0.0.0 Safari/537.36"));

        assertEquals("desktop", UserAgents.device(CHROME_MAC, null));
        assertEquals("mobile", UserAgents.device(SAFARI_IPHONE, null));
        assertEquals("tablet", UserAgents.device(ANDROID_TABLET, null));
        assertEquals("mobile", UserAgents.device(CHROME_MAC, "?1"), "client hint wins over a frozen UA");

        assertEquals("Chrome", UserAgents.browser(CHROME_MAC));
        assertEquals("Safari", UserAgents.browser(SAFARI_IPHONE));
        assertEquals("Firefox", UserAgents.browser(FIREFOX_WIN));
        assertEquals("Edge", UserAgents.browser(EDGE_WIN));

        assertEquals("macOS", UserAgents.os(CHROME_MAC, null));
        assertEquals("iOS", UserAgents.os(SAFARI_IPHONE, null));
        assertEquals("Windows", UserAgents.os(FIREFOX_WIN, null));
        assertEquals("Android", UserAgents.os(ANDROID_TABLET, null));
        assertEquals("ChromeOS", UserAgents.os(CHROME_MAC, "\"Chrome OS\""));
    }

    @Test
    void sourceComesFromTagsThenReferrerHost() {
        var tagged = get("/", browser(), Map.of("utm_source", "Newsletter Oct!"), "203.0.113.9", null);
        assertEquals("newsletteroct", Analytics.source(tagged, "example.com"));
        var ref = get("/", browser(), Map.of("ref", "hn"), "203.0.113.9", null);
        assertEquals("hn", Analytics.source(ref, "example.com"));
        assertEquals("news.ycombinator.com",
            Analytics.source(get("/", browser("Referer", "https://news.ycombinator.com/item?id=1")), "example.com"));
        assertEquals("google.com", Analytics.source(get("/", browser("Referer", "https://www.google.com/")), "example.com"));
        assertNull(Analytics.source(get("/", browser("Referer", "https://www.example.com/blog")), "example.com"),
            "same-site navigation is not a source");
        assertNull(Analytics.source(get("/", browser()), "example.com"));
    }

    @Test
    void storedPathIsDecodedRedactedAndBounded() {
        assertEquals("/tags/red hat", Analytics.storedPath("/tags/red%20hat"));
        assertEquals("/reset/[redacted]", Analytics.storedPath("/reset/9f8e7d6c5b4a39281706f5e4d3c2b1a0"));
        assertEquals("/ab", Analytics.storedPath("/a%0Ab"));
        assertEquals(512, Analytics.storedPath("/" + "x".repeat(2000)).length());
    }

    @Test
    void countryAndHostNormalization() {
        assertEquals("US", Analytics.countryCode("us"));
        assertNull(Analytics.countryCode("XX"));
        assertNull(Analytics.countryCode("USA"));
        assertNull(Analytics.countryCode("<b"));
        assertEquals("example.com", Analytics.normalizeHost("WWW.Example.com:8443"));
    }

    @Test
    void visitorIdIsStablePerSaltAndChangesWithIt() throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        byte[] saltA = new byte[32];
        byte[] saltB = new byte[32];
        saltB[0] = 1;
        long a1 = Analytics.visitorId(md, saltA, "example.com", "203.0.113.9", CHROME_MAC);
        long a2 = Analytics.visitorId(md, saltA, "example.com", "203.0.113.9", CHROME_MAC);
        assertEquals(a1, a2);
        assertNotEquals(a1, Analytics.visitorId(md, saltB, "example.com", "203.0.113.9", CHROME_MAC));
        assertNotEquals(a1, Analytics.visitorId(md, saltA, "example.com", "203.0.113.10", CHROME_MAC));
        assertNotEquals(a1, Analytics.visitorId(md, saltA, "example.com", "203.0.113.9", FIREFOX_WIN));
    }

    @Test
    void chartAxisTopIsDivisibleIntoFourTicks() {
        for (long v : new long[] {0, 1, 5, 7, 13, 99, 113, 1234, 6789, 98765}) {
            long top = AnalyticsDashboard.niceMax(v);
            assertTrue(top >= v, v + " -> " + top);
            assertEquals(0, top % 4, v + " -> " + top);
        }
    }

    @Test
    void cliRendersTheReport() throws Exception {
        var json = Json.mapper().readTree("""
            {"range":"7d","timezone":"UTC","from":"2026-10-01","to":"2026-10-07","visitors":40,"pageviews":70,
             "previousVisitors":0,"previousPageviews":0,"live":3,"series":[],
             "pages":[{"key":"/posts/a","visitors":30,"pageviews":50}],
             "sources":[{"key":null,"visitors":25,"pageviews":40},{"key":"google.com","visitors":15,"pageviews":30}],
             "devices":[{"key":"desktop","visitors":40,"pageviews":70}],"browsers":[],"os":[],"countries":[],
             "notCounted":{"bot":12,"htmx":0}}
            """);
        String out = CliCommands.renderAnalytics(json);
        assertTrue(out.contains("Visitors 40   Pageviews 70   Views/visit 1.75   Now 3"), out);
        assertTrue(out.contains("/posts/a"), out);
        assertTrue(out.contains("(direct)"), out);
        assertTrue(out.contains("Not counted: 12 (bot 12)"), out);
        assertFalse(out.contains("Countries"), out);
    }
}
