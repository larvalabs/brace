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
        var headers = new HashMap<String, String>();
        headers.put("User-Agent", AnalyticsTest.CHROME_MAC);
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
        var a = new Analytics(Analytics.options().maxViewsPerVisitorPerFlush(0).dailyRowBudget(0), dbFactory);
        for (int i = 0; i < 60; i++) view(a, "203.0.113.1");
        assertEquals(60, a.flush());
        assertEquals(0, dropped());
    }
}
