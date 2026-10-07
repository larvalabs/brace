package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The nightly rollup into brace_analytics_daily, and reports that read it: a report must give the
 * same numbers before and after its days are summarized, and keep giving them once raw rows are gone.
 */
class AnalyticsRollupTest {

    static DatabaseFactory dbFactory;
    Analytics analytics;
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @BeforeAll
    static void setup() {
        dbFactory = new DatabaseFactory("jdbc:h2:mem:analyticsrollup;DB_CLOSE_DELAY=-1", null, null, List.of(Post.class));
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
        analytics = new Analytics(Analytics.options(), dbFactory);
    }

    private static void view(LocalDate day, long visitor, String path, String source) {
        dbFactory.withSession(db -> {
            db.sql("INSERT INTO brace_analytics_pageviews (ts, view_date, view_hour, visitor, path, source, device, browser, os) "
                + "VALUES (?, ?, 0, ?, ?, ?, 'desktop', 'Chrome', 'macOS')",
                OffsetDateTime.of(day.atStartOfDay(), ZoneOffset.UTC), day, visitor, path, source);
        });
    }

    private static void rejected(LocalDate day, String reason, long n) {
        dbFactory.withSession(db -> {
            db.sql("INSERT INTO brace_analytics_rejects (view_date, reason, instance_id, n) VALUES (?, ?, 'i', ?)", day, reason, n);
        });
    }

    private int rollup() {
        return dbFactory.withSession(db -> { return analytics.rollup(db); });
    }

    private long count(String sql, Object... params) {
        return dbFactory.withSession(db -> { return db.sqlQueryLong(sql, params); });
    }

    /** Three past days and today; visitor 1 returns on two days (a new id each day in reality). */
    private void seedWeek() {
        LocalDate d3 = TODAY.minusDays(3), d2 = TODAY.minusDays(2), d1 = TODAY.minusDays(1);
        view(d3, 1, "/", null);
        view(d3, 1, "/posts/a", null);
        view(d3, 2, "/posts/a", "google.com");
        view(d2, 3, "/", "news.ycombinator.com");
        // d1 has no views at all: the rollup must still mark it summarized.
        rejected(d3, "bot", 7);
        rejected(d2, "htmx", 2);
        view(TODAY, 4, "/posts/a", "google.com");
        view(TODAY, 5, "/", null);
    }

    @Test
    void reportIsTheSameBeforeAndAfterRollup() {
        seedWeek();
        var before = analytics.report("7d");
        assertEquals(3, rollup(), "three completed days, including the empty one");
        var after = analytics.report("7d");

        assertEquals(before.visitors(), after.visitors());
        assertEquals(before.pageviews(), after.pageviews());
        assertEquals(before.series(), after.series());
        assertEquals(before.pages(), after.pages());
        assertEquals(before.sources(), after.sources());
        assertEquals(before.devices(), after.devices());
        assertEquals(before.notCounted(), after.notCounted());

        assertEquals(5, after.visitors());
        assertEquals(6, after.pageviews());
        var posts = after.pages().stream().filter(r -> r.key().equals("/posts/a")).findFirst().orElseThrow();
        assertEquals(3, posts.pageviews(), "a page in both the summarized days and today is summed");
        assertNull(after.sources().getFirst().key(), "direct traffic round-trips through '' as null");
        assertEquals(7L, after.notCounted().get("bot"));
        assertEquals(2L, after.notCounted().get("htmx"));
    }

    @Test
    void rollupWritesATotalsRowPerDayAndIsIdempotent() {
        seedWeek();
        rollup();
        assertEquals(3, count("SELECT COUNT(*) FROM brace_analytics_daily WHERE dim = 'total'"));
        assertEquals(0, count("SELECT COUNT(*) FROM brace_analytics_daily WHERE view_date = ?", TODAY),
            "today is never summarized");
        long rows = count("SELECT COUNT(*) FROM brace_analytics_daily");
        assertEquals(0, rollup(), "nothing left to summarize");
        assertEquals(rows, count("SELECT COUNT(*) FROM brace_analytics_daily"));
    }

    @Test
    void pruneNeverDeletesAnUnsummarizedDayAndHistorySurvivesIt() {
        LocalDate old = TODAY.minusDays(100);
        view(old, 9, "/old-post", "google.com");
        view(old, 10, "/old-post", null);

        dbFactory.withSession(db -> { analytics.prune(db); });
        assertEquals(2, count("SELECT COUNT(*) FROM brace_analytics_pageviews"), "not summarized yet, so kept");

        rollup();
        dbFactory.withSession(db -> { analytics.prune(db); });
        assertEquals(0, count("SELECT COUNT(*) FROM brace_analytics_pageviews"), "past retention once summarized");

        var year = analytics.report("12mo");
        assertEquals(2, year.visitors());
        assertEquals(2, year.pageviews());
        assertEquals("/old-post", year.pages().getFirst().key());
    }

    @Test
    void twelveMonthsIsMonthlyAndAddsUp() {
        LocalDate from = TODAY.withDayOfMonth(1).minusMonths(11);
        view(from, 1, "/", null);                        // first day of the range
        view(from.minusDays(1), 2, "/", null);           // the day before it: previous period
        view(from.minusMonths(12), 6, "/", null);        // start of the previous 12 months
        view(TODAY, 3, "/", null);
        rollup();
        var year = analytics.report("12mo");
        assertEquals(12, year.series().size());
        assertEquals(TODAY.toString().substring(0, 7), year.series().getLast().bucket());
        assertEquals(2, year.pageviews(), "the day before the range is excluded");
        assertEquals(year.pageviews(), year.series().stream().mapToLong(Analytics.Point::pageviews).sum());
        assertEquals(2L, year.previousPageviews(), "data reaches back to the previous 12 months' start");
    }

    @Test
    void monthlyBucketsSumDays() {
        var days = List.of(new Analytics.Point("2026-09-30", 2, 3), new Analytics.Point("2026-10-01", 4, 5),
            new Analytics.Point("2026-10-02", 1, 1));
        assertEquals(List.of(new Analytics.Point("2026-09", 2, 3), new Analytics.Point("2026-10", 5, 6)),
            Analytics.monthly(days));
    }
}
