package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** End to end: real requests through BraceHandler, flushed to H2, read back through /ops/analytics. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalyticsIntegrationTest {

    static Brace app;
    static DatabaseFactory dbFactory;
    static HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static int port;
    static OpsKeys.Keypair keypair;
    static String token;

    @TempDir
    static Path tmpDir;

    @BeforeAll
    static void startApp() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, keypair.publicKey() + " test-key\n");
        dbFactory = new DatabaseFactory("jdbc:h2:mem:analyticsit;DB_CLOSE_DELAY=-1", null, null, List.of(Post.class));

        app = Brace.app().port(0).banner(false).database(dbFactory).ops(keysFile.toString())
            .analytics(Analytics.options().exclude("/admin/*"));
        app.get("/", req -> Result.html("<h1>home</h1>"));
        app.get("/posts/{slug}", req -> Result.html("<h1>" + req.pathParam("slug") + "</h1>"));
        app.get("/u/{name}", req -> Result.html("<h1>profile</h1>")).analyticsByRoute();
        app.get("/reset/{token}", req -> Result.html("<h1>reset</h1>")).analytics(false);
        app.get("/admin/users", req -> Result.html("<h1>admin</h1>"));
        app.get("/api/posts", req -> Json.of(List.of("a", "b")));
        app.start();
        port = app.actualPort();
        token = authenticate();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private static String authenticate() throws Exception {
        String timestamp = java.time.Instant.now().toString();
        byte[] nonceBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(nonceBytes);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String signature = OpsKeys.sign(OpsKeys.v2AuthMessage(keypair.publicKey(), timestamp, nonce), keypair.privateKey());
        String body = "{\"v\":\"2\",\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + timestamp
            + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + signature + "\"}";
        var res = client.send(HttpRequest.newBuilder(URI.create(url("/ops/auth")))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return Json.mapper().readTree(res.body()).get("token").asText();
    }

    private static String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpResponse<String> visit(String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url(path))).GET();
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String[] browser(String ua, String... extra) {
        var h = new ArrayList<>(List.of("User-Agent", ua, "Accept", "text/html,*/*;q=0.8", "Sec-Fetch-Mode", "navigate"));
        h.addAll(List.of(extra));
        return h.toArray(String[]::new);
    }

    private static HttpResponse<String> opsGet(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url(path)))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Requests are recorded after the response is written, so wait for the buffer to catch up. */
    private static void flushWhenBuffered(int expectedViews) throws Exception {
        int written = 0;
        long deadline = System.currentTimeMillis() + 5000;
        while (written < expectedViews && System.currentTimeMillis() < deadline) {
            written += app.analyticsCollector().flush();
            if (written < expectedViews) Thread.sleep(20);
        }
        Thread.sleep(50);
        app.analyticsCollector().flush();
        assertEquals(expectedViews, written, "page views written");
    }

    private static JsonNode row(JsonNode rows, String key) {
        for (var r : rows) if (key.equals(r.path("key").asText(null))) return r;
        return null;
    }

    @Test
    @Order(1)
    void countsPageViewsAndFiltersTheRest() throws Exception {
        // Two people (different browsers, same IP), five counted views between them.
        visit("/", browser(AnalyticsTest.CHROME_MAC, "Referer", "https://news.ycombinator.com/item?id=1"));
        visit("/posts/hello-world", browser(AnalyticsTest.CHROME_MAC, "Referer", url("/")));
        visit("/posts/hello-world", browser(AnalyticsTest.SAFARI_IPHONE));
        visit("/u/alice", browser(AnalyticsTest.SAFARI_IPHONE));
        visit("/u/bob?utm_source=newsletter", browser(AnalyticsTest.SAFARI_IPHONE));
        // Rejected candidates, tallied by reason.
        visit("/", browser(AnalyticsTest.GOOGLEBOT));
        visit("/", browser(AnalyticsTest.CHROME_MAC, "HX-Request", "true"));
        visit("/admin/users", browser(AnalyticsTest.CHROME_MAC));
        // Never candidates.
        visit("/reset/abc123", browser(AnalyticsTest.CHROME_MAC));
        visit("/api/posts", browser(AnalyticsTest.CHROME_MAC));
        visit("/nope", browser(AnalyticsTest.CHROME_MAC));
        opsGet("/ops/status");

        flushWhenBuffered(5);

        var res = opsGet("/ops/analytics/data?range=today");
        assertEquals(200, res.statusCode(), res.body());
        JsonNode r = Json.mapper().readTree(res.body());
        assertEquals(2, r.path("visitors").asLong());
        assertEquals(5, r.path("pageviews").asLong());
        assertEquals(LocalDate.now(java.time.ZoneOffset.UTC).toString(), r.path("to").asText());

        var pages = r.path("pages");
        assertEquals(2, row(pages, "/posts/hello-world").path("visitors").asLong());
        assertEquals(2, row(pages, "/u/{name}").path("pageviews").asLong(), "analyticsByRoute groups by pattern");
        assertNull(row(pages, "/reset/abc123"));
        assertNull(row(pages, "/api/posts"));
        assertNull(row(pages, "/admin/users"));

        var sources = r.path("sources");
        assertNotNull(row(sources, "news.ycombinator.com"));
        assertNotNull(row(sources, "newsletter"));
        assertNull(row(sources, "localhost"), "same-site navigation is not a source");

        assertEquals(1, row(r.path("devices"), "mobile").path("visitors").asLong());
        assertEquals(1, row(r.path("browsers"), "Safari").path("visitors").asLong());

        var notCounted = r.path("notCounted");
        assertEquals(1, notCounted.path("bot").asLong());
        assertEquals(1, notCounted.path("htmx").asLong());
        assertEquals(1, notCounted.path("excluded").asLong());
        assertEquals(2, r.path("live").asLong());
    }

    @Test
    @Order(2)
    void dashboardPageRendersTheReport() throws Exception {
        var res = opsGet("/ops/analytics?range=today");
        assertEquals(200, res.statusCode());
        assertEquals("no-store", res.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(res.body().contains("Top pages"));
        assertTrue(res.body().contains("/posts/hello-world"));
        assertTrue(res.body().contains("/u/{name}"));

        assertEquals(400, opsGet("/ops/analytics?range=1y").statusCode());
    }

    @Test
    @Order(3)
    void opsStatusAndDashboardShowToday() throws Exception {
        JsonNode status = Json.mapper().readTree(opsGet("/ops/status").body());
        assertEquals(2, status.path("analytics").path("todayVisitors").asLong());
        assertEquals(5, status.path("analytics").path("todayPageviews").asLong());

        var dash = opsGet("/ops/dashboard").body();
        assertTrue(dash.contains("Visitors Today"));
        assertTrue(dash.contains("href=\"/ops/analytics\""));
    }

    @Test
    @Order(4)
    void requiresOpsAuth() throws Exception {
        assertEquals(401, visit("/ops/analytics").statusCode());
        assertEquals(401, visit("/ops/analytics/data").statusCode());
        var post = client.send(HttpRequest.newBuilder(URI.create(url("/ops/analytics/ignore")))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("on=1")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, post.statusCode());
    }

    @Test
    @Order(5)
    void ignoreToggleSetsASiteWideCookieThatStopsCounting() throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(url("/ops/analytics/ignore")))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("on=1&range=7d")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, res.statusCode());
        assertEquals("/ops/analytics?range=7d", res.headers().firstValue("Location").orElse(""));
        String cookie = res.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.startsWith(Analytics.IGNORE_COOKIE + "=1"), cookie);
        assertTrue(cookie.contains("Path=/"), cookie);

        visit("/", browser(AnalyticsTest.FIREFOX_WIN, "Cookie", Analytics.IGNORE_COOKIE + "=1"));
        Thread.sleep(100);
        assertEquals(0, app.analyticsCollector().flush());
    }

    @Test
    @Order(6)
    void exchangeCanLandOnTheAnalyticsPage() throws Exception {
        var login = client.send(HttpRequest.newBuilder(URI.create(url("/ops/auth/login-token")))
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        String loginToken = Json.mapper().readTree(login.body()).get("loginToken").asText();
        var analytics = visit("/ops/auth/exchange?token=" + loginToken + "&next=analytics");
        assertEquals("/ops/analytics", analytics.headers().firstValue("Location").orElse(""));
        var other = visit("/ops/auth/exchange?token=" + loginToken + "&next=https://evil.example");
        assertEquals("/ops/dashboard", other.headers().firstValue("Location").orElse(""));
    }

    @Test
    @Order(7)
    void expiredSaltsAndOldRowsAreDeleted() throws Exception {
        LocalDate old = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(40);
        dbFactory.withSession(db -> {
            db.sql("INSERT INTO brace_analytics_salts (view_date, salt) VALUES (?, ?)", old, "00".repeat(32));
            db.sql("INSERT INTO brace_analytics_pageviews (ts, view_date, view_hour, visitor, path, device, browser, os) "
                + "VALUES (?, ?, 0, 1, '/old', 'desktop', 'Chrome', 'macOS')",
                java.time.OffsetDateTime.now().minusDays(40), old);
        });
        visit("/", browser(AnalyticsTest.FIREFOX_WIN));
        flushWhenBuffered(1);
        dbFactory.withSession(db -> { app.analyticsCollector().prune(db); });
        long salts = dbFactory.withSession(db -> { return db.sqlQueryLong("SELECT COUNT(*) FROM brace_analytics_salts"); });
        assertEquals(1, salts, "only today's salt survives");
        long oldRows = dbFactory.withSession(db -> { return db.sqlQueryLong(
            "SELECT COUNT(*) FROM brace_analytics_pageviews WHERE path = '/old'"); });
        assertEquals(0, oldRows);
    }

    @Test
    void startFailsWithoutADatabaseOrOps() {
        var noDb = Brace.app().port(0).banner(false).ops(tmpDir.resolve("authorized-keys").toString()).analytics();
        var e1 = assertThrows(IllegalStateException.class, noDb::start);
        assertTrue(e1.getMessage().contains("database"), e1.getMessage());

        var db = new DatabaseFactory("jdbc:h2:mem:analyticsit-noops;DB_CLOSE_DELAY=-1", null, null, List.of(Post.class));
        var noOps = Brace.app().port(0).banner(false).database(db).analytics();
        var e2 = assertThrows(IllegalStateException.class, noOps::start);
        assertTrue(e2.getMessage().contains("ops"), e2.getMessage());
    }
}
