package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L16 (2026-11 security review): analytics growth was bounded only by the buffer rate. A visitor
 * cap per flush window and a daily row budget bound it; views over a cap are tallied as
 * {@code dropped}, so the dashboard's "Not counted" shows them instead of the totals being silently
 * wrong.
 */
class AnalyticsCapsTest {

    static DatabaseFactory dbFactory;
    private static final Route PAGE = new Route("GET", "/posts/{slug}", (Handler) r -> null, null);

    @BeforeAll
    static void setup() {
        dbFactory = new DatabaseFactory("jdbc:h2:mem:analyticscaps;DB_CLOSE_DELAY=-1", null, null, List.of(Post.class));
    }

    @AfterAll
    static void teardown() {
        dbFactory.close();
    }

    @BeforeEach
    void clean() {
        dbFactory.withSession(db -> {
            for (var t : List.of("brace_analytics_pageviews", "brace_analytics_rejects", "brace_analytics_daily")) {
                db.sql("DELETE FROM " + t);
            }
        });
    }

    private static void view(Analytics a, String ip) {
        view(a, ip, AnalyticsTest.CHROME_MAC);
    }

    private static void view(Analytics a, String ip, String ua) {
        var headers = new HashMap<String, String>();
        headers.put("User-Agent", ua);
        headers.put("Accept", "text/html");
        headers.put("Sec-Fetch-Mode", "navigate");
        var req = new Request("GET", "/posts/x", Map.of(), Map.of(), headers, null, Map.of(), ip, null);
        a.observe(req, PAGE, Result.html("<p>hi</p>"));
    }

    private static long rows() {
        return dbFactory.withSession(db -> { return db.sqlQueryLong("SELECT COUNT(*) FROM brace_analytics_pageviews"); });
    }

    private static long dropped() {
        return dbFactory.withSession(db -> {
            Long n = db.sqlQueryLong("SELECT COALESCE(SUM(n), 0) FROM brace_analytics_rejects WHERE reason = 'dropped'");
            return n;
        });
    }

    @Test
    void aVisitorOverItsWindowCapIsTalliedNotStored() {
        var a = new Analytics(Analytics.options().maxViewsPerVisitorPerFlush(3), dbFactory);
        for (int i = 0; i < 5; i++) view(a, "203.0.113.1");
        view(a, "203.0.113.2"); // another visitor is unaffected
        assertEquals(4, a.flush());
        assertEquals(4, rows());
        assertEquals(2, dropped());
        // The cap is per flush window: the next window starts fresh.
        view(a, "203.0.113.1");
        assertEquals(1, a.flush());
        assertEquals(5, rows());
    }

    @Test
    void defaultVisitorCapIsConservativeButNotTight() {
        var a = new Analytics(Analytics.options(), dbFactory);
        for (int i = 0; i < 30; i++) view(a, "203.0.113.1");
        view(a, "203.0.113.1");
        assertEquals(30, a.flush());
        assertEquals(1, dropped());
    }

    @Test
    void dailyRowBudgetStopsRowByRowInsertsButKeepsCounting() {
        var a = new Analytics(Analytics.options().dailyRowBudget(4), dbFactory);
        for (int i = 1; i <= 6; i++) view(a, "203.0.113." + i);
        assertEquals(4, a.flush());
        assertEquals(4, rows());
        assertEquals(2, dropped(), "over-budget views are tallied so the dashboard shows them as not counted");
    }

    @Test
    void capsCanBeDisabled() {
        var a = new Analytics(Analytics.options().maxViewsPerVisitorPerFlush(0).dailyRowBudget(0)
            .maxViewsPerIpPerFlush(0), dbFactory);
        for (int i = 0; i < 400; i++) view(a, "203.0.113.1", AnalyticsTest.CHROME_MAC + " r" + i);
        assertEquals(400, a.flush());
        assertEquals(0, dropped());
    }

    // ---- L16 final review: one IP rotating User-Agent; the daily budget as a blinding primitive ----

    @Test
    void userAgentRotationFromOneIpIsCapped() {
        // Visitor identity is host + IP + UA, so rotating the UA made every view a new visitor: one
        // IP stored 20,000 of 25,000 views in a window. The per-IP cap holds it to a small share.
        var a = new Analytics(Analytics.options(), dbFactory);
        for (int i = 0; i < 2_000; i++) view(a, "203.0.113.7", AnalyticsTest.CHROME_MAC + " r" + i);
        view(a, "198.51.100.1");
        int stored = a.flush();
        assertEquals(Analytics.DEFAULT_VIEWS_PER_IP + 1, stored, "one IP is capped per window; others are not");
        assertEquals(2_000 - Analytics.DEFAULT_VIEWS_PER_IP, dropped());
    }

    @Test
    void ipv6AddressesInOneSlash64ShareTheCap() {
        var a = new Analytics(Analytics.options().maxViewsPerIpPerFlush(10), dbFactory);
        for (int i = 0; i < 50; i++) view(a, "2001:db8:1:2::" + Integer.toHexString(i + 1), AnalyticsTest.CHROME_MAC + " r" + i);
        assertEquals(10, a.flush());
    }

    @Test
    void oneIpCannotExhaustTheDailyBudget() {
        // 500,000 requests used to spend the day's budget, after which legitimate views stored 0
        // rows until midnight. Each IP may now take at most 2% of it.
        var a = new Analytics(Analytics.options().dailyRowBudget(1_000), dbFactory);
        for (int w = 0; w < 20; w++) {
            for (int i = 0; i < 100; i++) view(a, "203.0.113.7", AnalyticsTest.CHROME_MAC + " w" + w + " r" + i);
            a.flush();
        }
        assertEquals(20, rows(), "one IP keeps at most 2% of a 1,000-row budget");
        for (int i = 1; i <= 50; i++) view(a, "198.51.100." + i);
        assertEquals(50, a.flush(), "other visitors are still stored after the attacker hit its share");
        assertNull(a.report("today").rowBudget(), "the budget was not reached");
    }

    @Test
    void reachingTheBudgetIsRecordedAndShown() {
        var a = new Analytics(Analytics.options().dailyRowBudget(10), dbFactory);
        for (int i = 1; i <= 15; i++) view(a, "198.51.100." + i);
        assertEquals(10, a.flush());
        var report = a.report("today");
        var budget = report.rowBudget();
        assertNotNull(budget);
        assertEquals(10, budget.budget());
        assertEquals(5, budget.notStored());
        assertNotNull(budget.reachedAt());
        assertEquals(5L, report.notCounted().get("dropped"), "totals stay honest");
        String html = AnalyticsDashboard.html(report, "visitors", false);
        assertTrue(html.contains("Row budget reached at"), html);
        assertTrue(html.contains("5 views not stored"), html);
        String json = new String(Json.of(report).rawBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"rowBudget\""), json);
        assertTrue(json.contains("\"notStored\":5"), json);
    }
}
