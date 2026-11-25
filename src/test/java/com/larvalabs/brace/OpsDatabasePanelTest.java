package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Renders the Database card and panel of {@link OpsDashboard#html} from hand-built snapshots. */
class OpsDatabasePanelTest {

    static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

    static String render(Stats stats, DatabaseStats.Snapshot db) {
        return OpsDashboard.html(OpsScope.READ, stats, null, null, null, null, null, false, null, db);
    }

    static DatabaseStats.LoadMinute minute(int i, boolean partial, double cpu, double io, double lock, double other,
                                           int poolPeak, int poolWaiting) {
        return new DatabaseStats.LoadMinute(T0.plusSeconds(60L * i), partial, 12, cpu, io, lock, other,
            poolPeak, poolWaiting, 10);
    }

    static DatabaseStats.Snapshot postgres(DatabaseFactory.PoolStats pool, List<DatabaseStats.Table> tables) {
        var load = List.of(
            minute(0, false, 0.5, 0.25, 0, 0.25, 4, 0),
            minute(1, false, 2.0, 1.0, 0.5, 0.5, 10, 3),
            minute(2, true, 1.0, 0, 0, 0, 2, 0));
        var activity = new DatabaseStats.Activity(14, 100, 1, 1, 2, 0, 1, 3, 42.5, 75);
        var throughput = new DatabaseStats.Throughput(12.34, 0.5, 97.25, 2048, 1, 30);
        var io = new DatabaseStats.Io(31.5, 4.25, 0.8, null);
        return new DatabaseStats.Snapshot(pool, true, "16.4", 160004, 7_340_032L, activity, throughput, io,
            tables, load, T0.plusSeconds(150), null);
    }

    @Test
    void postgresPanelShowsLoadPoolActivityAndTables() {
        var stats = new Stats();
        stats.recordRequestPattern("GET", "/a", 200, 5000, 4, 2000);
        stats.snapshot();
        var tables = List.of(
            new DatabaseStats.Table("orders", 900, 4_500_000, 12, 5000, 2500, null),
            new DatabaseStats.Table("audit.<log>", 3, 30, 400, 10, 0, Instant.now().minusSeconds(7200)));
        var html = render(stats, postgres(new DatabaseFactory.PoolStats(3, 7, 10, 10, 0), tables));

        // Top-row card: pool in use, last complete minute's load (not the partial one).
        assertTrue(html.contains("<div class=\"label\">Database</div><div class=\"value c-green\">3/10</div>"
            + "<div class=\"detail\">in use · load 4.0</div>"), html);

        assertTrue(html.contains("Database <span class=\"c-muted head-note\">Postgres 16.4 · 7 MB</span>"), html);
        assertTrue(html.contains("<span class=\"c-muted small\">QUERIES / MIN</span><br/><span class=\"c-blue bold\">4</span>"), html);
        assertTrue(html.contains("<span class=\"c-muted small\">AVG QUERY</span><br/><span class=\"c-blue bold\">0.5 ms</span>"), html);
        assertTrue(html.contains("<span class=\"c-muted small\">CONNECTIONS</span><br/><span class=\"c-muted bold\">14/100</span>"));
        assertTrue(html.contains("<span class=\"c-muted small\">LOCK WAITS</span><br/><span class=\"c-amber bold\">2</span>"));
        assertTrue(html.contains("<span class=\"c-muted small\">LONGEST QUERY</span><br/><span class=\"c-amber bold\">42.5s</span>"));
        assertTrue(html.contains("<span class=\"c-muted small\">IDLE IN TX</span><br/><span class=\"c-amber bold\">1</span>"));
        assertTrue(html.contains("oldest 1m 15s"), "idle-in-transaction age in the tooltip");
        assertTrue(html.contains("<span class=\"c-muted small\">CACHE HIT</span><br/><span class=\"c-green bold\">97.3%</span>"));
        assertTrue(html.contains("<span class=\"c-muted small\">DEADLOCKS</span><br/><span class=\"c-red bold\">1</span>"));
        assertTrue(html.contains("<span class=\"c-muted small\">TEMP / S</span><br/><span class=\"c-amber bold\">2 KB</span>"));
        assertTrue(html.contains("0.8 ms avg"), "read time with track_io_timing on");
        assertTrue(html.contains("3 sessions belong to other roles"), html);

        // Load chart: stacked layers, tallest first; the minute in progress is marked.
        assertTrue(html.contains("<div class=\"bar stack\" title=\"4.0 avg active: 2.0 cpu, 1.0 io, 0.5 lock, 0.5 other @ "
            + T0.plusSeconds(60) + "\"><div class=\"load-other h100\"></div><div class=\"load-lock h88\"></div>"
            + "<div class=\"load-io h75\"></div><div class=\"load-cpu h50\"></div></div>"), html);
        assertTrue(html.contains("(so far)"));
        assertTrue(html.contains("<span class=\"right\">this min 1.0</span>"), html);

        // Pool chart: the minute with waiters is red.
        assertTrue(html.contains("<div class=\"bar bar-err h100\" title=\"peak 10/10 in use, 3 waiting @ "), html);
        assertTrue(html.contains("1 minute with waits"));

        // Query-time chart from the request stats.
        assertTrue(html.contains("Query ms · avg, in requests"));

        // Tables: escaped names, a scan-heavy big table flagged, vacuum ages.
        assertTrue(html.contains("<bdi>audit.&lt;log&gt;</bdi>"), html);
        assertFalse(html.contains("audit.<log>"));
        assertTrue(html.contains("<td class=\"num c-amber\">4,500,000</td>"), html);
        assertTrue(html.contains("<td class=\"num c-amber\">33%</td>"), "dead row share");
        assertTrue(html.contains("<td class=\"num c-muted\">never</td>"));
        assertTrue(html.contains("<td class=\"num c-muted\">2h ago</td>"), html);

        assertFalse(html.contains("style="), "no style attributes under the ops CSP");
    }

    @Test
    void waitingThreadsTurnTheCardRed() {
        var html = render(new Stats(), postgres(new DatabaseFactory.PoolStats(10, 0, 10, 10, 4), List.of()));
        assertTrue(html.contains("<div class=\"value c-red\">10/10</div><div class=\"detail\">4 waiting</div>"), html);
        assertTrue(html.contains("<span class=\"c-muted small\">POOL WAITING</span><br/><span class=\"c-red bold\">4</span>"));
        assertFalse(html.contains("Most rows read"), "no table list when empty");
    }

    @Test
    void nonPostgresShowsPoolOnly() {
        var load = new ArrayList<DatabaseStats.LoadMinute>();
        load.add(new DatabaseStats.LoadMinute(T0, true, 0, 0, 0, 0, 0, 1, 0, 10));
        var db = new DatabaseStats.Snapshot(new DatabaseFactory.PoolStats(1, 9, 10, 10, 0), false, null, 0, null,
            null, null, null, List.of(), load, null, null);
        var html = render(new Stats(), db);
        assertTrue(html.contains("<div class=\"detail\">in use · 9 idle</div>"), html);
        assertTrue(html.contains("POOL IN USE"));
        assertTrue(html.contains("Pool · peak connections in use"));
        assertFalse(html.contains("Load · avg active sessions"));
        assertFalse(html.contains("Postgres"));
        assertFalse(html.contains("CONNECTIONS"));
    }

    @Test
    void postgresErrorAndPendingStates() {
        var pending = new DatabaseStats.Snapshot(new DatabaseFactory.PoolStats(0, 10, 10, 10, 0), true, null, 0, null,
            null, null, null, List.of(), List.of(), null, null);
        assertTrue(render(new Stats(), pending).contains("first Postgres sample pending"));

        var failed = new DatabaseStats.Snapshot(new DatabaseFactory.PoolStats(0, 10, 10, 10, 0), true, null, 0, null,
            null, null, null, List.of(), List.of(), T0, "Postgres statistics unavailable: <boom>");
        var html = render(new Stats(), failed);
        assertTrue(html.contains("<div class=\"c-red small db-note\">Postgres statistics unavailable: &lt;boom&gt;</div>"), html);
        assertFalse(html.contains("first Postgres sample pending"));
    }

    @Test
    void noDatabaseNoPanel() {
        var html = render(new Stats(), null);
        assertFalse(html.contains("<div class=\"label\">Database</div>"));
        assertFalse(html.contains("POOL IN USE"));
    }

    @Test
    void secondsFormatting() {
        assertEquals("0.3s", OpsDatabasePanel.seconds(0.25));
        assertEquals("59.0s", OpsDatabasePanel.seconds(59));
        assertEquals("2m 5s", OpsDatabasePanel.seconds(125));
        assertEquals("1h 1m", OpsDatabasePanel.seconds(3661));
    }
}
