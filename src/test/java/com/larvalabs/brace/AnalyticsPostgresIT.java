package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Analytics on real Postgres: the {@code ON CONFLICT} salt path that H2 can't exercise, and two
 * instances (two collectors, one database) agreeing on the day's salt so one browser is one visitor
 * fleet-wide, with their filter tallies summed.
 */
class AnalyticsPostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;
    private static final Route PAGE = new Route("GET", "/posts/{slug}", (Handler) r -> null, null);

    @BeforeAll
    static void buildFactory() {
        dbFactory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void closeFactory() {
        if (dbFactory != null) dbFactory.close();
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("brace_analytics_pageviews");
        truncate("brace_analytics_rejects");
        truncate("brace_analytics_salts");
        truncate("brace_analytics_daily");
    }

    private static Request view(String path, String ua) {
        return new Request("GET", path, Map.of(), Map.of(),
            Map.of("User-Agent", ua, "Accept", "text/html", "Sec-Fetch-Mode", "navigate", "Host", "example.com"),
            null, Map.of(), "203.0.113.9", null);
    }

    @Test
    void twoInstancesShareOneSaltAndSumTheirTallies() {
        var a = new Analytics(Analytics.options(), dbFactory);
        var b = new Analytics(Analytics.options(), dbFactory);
        a.setInstanceId("web-1");
        b.setInstanceId("web-2");
        var html = Result.html("<p>x</p>");

        a.observe(view("/posts/one", AnalyticsTest.CHROME_MAC), PAGE, html);
        b.observe(view("/posts/two", AnalyticsTest.CHROME_MAC), PAGE, html);
        b.observe(view("/posts/two", AnalyticsTest.SAFARI_IPHONE), PAGE, html);
        a.observe(view("/posts/one", AnalyticsTest.GOOGLEBOT), PAGE, html);
        b.observe(view("/posts/one", AnalyticsTest.GOOGLEBOT), PAGE, html);
        assertEquals(1, a.flush());
        assertEquals(2, b.flush());

        long salts = dbFactory.withSession(db -> { return db.sqlQueryLong("SELECT COUNT(*) FROM brace_analytics_salts"); });
        assertEquals(1, salts);

        var report = a.report("today");
        assertEquals(3, report.pageviews());
        assertEquals(2, report.visitors(), "the same browser on two instances is one visitor");
        assertEquals(2L, report.notCounted().get("bot"));
        assertEquals("/posts/two", report.pages().getFirst().key());
        assertEquals(3, report.series().stream().mapToLong(Analytics.Point::pageviews).sum(),
            "the hourly series adds up to the total");
        assertEquals(30, a.report("30d").series().size());
    }

    @Test
    void summaryAndPrune() {
        var a = new Analytics(Analytics.options(), dbFactory);
        a.observe(view("/posts/one", AnalyticsTest.FIREFOX_WIN), PAGE, Result.html("x"));
        a.flush();
        var s = a.summary();
        assertEquals(1, s.visitors());
        assertEquals(1, s.pageviews());
        assertEquals(1, s.live());
        dbFactory.withSession(db -> { a.prune(db); });
        assertEquals(1, a.report("7d").pageviews(), "today's rows are inside the retention window");
    }

    @Test
    void rollupAndTwelveMonthReport() {
        var a = new Analytics(Analytics.options(), dbFactory);
        var old = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(90);
        dbFactory.withSession(db -> {
            for (long v = 1; v <= 3; v++) {
                db.sql("INSERT INTO brace_analytics_pageviews (ts, view_date, view_hour, visitor, path, source, device, browser, os) "
                    + "VALUES (?, ?, 0, ?, '/old', NULL, 'desktop', 'Chrome', 'macOS')",
                    java.time.OffsetDateTime.of(old.atStartOfDay(), java.time.ZoneOffset.UTC), old, v);
            }
            db.sql("INSERT INTO brace_analytics_rejects (view_date, reason, instance_id, n) VALUES (?, 'bot', 'i', 4)", old);
        });
        int days = dbFactory.withSession(db -> { return a.rollup(db); });
        assertEquals(90, days, "every completed day from the first raw one through yesterday");
        dbFactory.withSession(db -> { a.prune(db); });

        var year = a.report("12mo");
        assertEquals(3, year.visitors());
        assertEquals(3, year.pageviews());
        assertEquals("/old", year.pages().getFirst().key());
        assertNull(year.sources().getFirst().key());
        assertEquals(4L, year.notCounted().get("bot"));
        assertEquals(12, year.series().size());
    }
}
