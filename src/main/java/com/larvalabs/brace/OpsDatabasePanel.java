package com.larvalabs.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.larvalabs.brace.OpsDashboard.esc;
import static com.larvalabs.brace.OpsDashboard.formatBytes;
import static com.larvalabs.brace.OpsDashboard.formatMetric;
import static com.larvalabs.brace.OpsDashboard.height;

/**
 * The Database card and panel of {@code /ops/dashboard}, rendered from a
 * {@link DatabaseStats.Snapshot}. Like the rest of the dashboard: no inline style or script,
 * bar heights are {@code .h0}–{@code .h100} classes, every value is escaped.
 */
final class OpsDatabasePanel {

    private static final int SLOTS = 60;

    private OpsDatabasePanel() {}

    /** The top-row card: pool connections in use, with waiting threads or the load as detail. */
    static void statCard(StringBuilder sb, DatabaseStats.Snapshot db) {
        var pool = db.pool();
        if (pool == null) {
            OpsDashboard.statCard(sb, "Database", "-", "pool unavailable", "c-muted", null);
            return;
        }
        String color = pool.waiting() > 0 ? "c-red" : pool.active() >= pool.max() ? "c-amber" : "c-green";
        String detail;
        if (pool.waiting() > 0) {
            detail = pool.waiting() + " waiting";
        } else {
            var last = lastLoad(db.load(), true);
            detail = last != null ? "in use · load " + formatMetric(last.total()) : "in use · " + pool.idle() + " idle";
        }
        OpsDashboard.statCard(sb, "Database", pool.active() + "/" + pool.max(), detail, color,
            "Pool connections in use / pool size. Waiting: requests or jobs queued for a connection."
                + (db.postgres() ? " Load: average sessions running a query in Postgres over the last minute." : ""));
    }

    /** The Database section: figures, load / pool / query-time sparklines, and the table list. */
    static void section(StringBuilder sb, DatabaseStats.Snapshot db, List<Stats.MinuteSnapshot> minutes) {
        sb.append("<div class=\"section\">");
        sb.append("<div class=\"section-head c-green\">Database");
        if (db.postgres()) {
            sb.append(" <span class=\"c-muted head-note\">Postgres");
            if (db.serverVersion() != null) sb.append(' ').append(esc(db.serverVersion()));
            if (db.sizeBytes() != null) sb.append(" · ").append(esc(formatBytes(db.sizeBytes())));
            sb.append("</span>");
        }
        sb.append("</div>");

        if (db.error() != null) {
            sb.append("<div class=\"c-red small db-note\">").append(esc(db.error())).append("</div>");
        }

        sb.append("<div class=\"db-stats\">");
        var pool = db.pool();
        if (pool != null) {
            item(sb, "Pool in use", pool.active() + "/" + pool.max(),
                pool.active() >= pool.max() ? "c-amber" : "c-green",
                pool.idle() + " idle, " + pool.total() + " open");
            item(sb, "Pool waiting", String.valueOf(pool.waiting()), pool.waiting() > 0 ? "c-red" : "c-muted",
                "Threads waiting for a pool connection right now");
        }
        var lastMinute = minutes.isEmpty() ? null : minutes.getLast();
        if (lastMinute != null) {
            item(sb, "Queries / min", String.format("%,d", lastMinute.queries()), "c-blue",
                "Queries run by request handlers in the last full minute");
            item(sb, "Avg query", lastMinute.queries() > 0
                    ? formatMetric(lastMinute.queryUs() / 1000.0 / lastMinute.queries()) + " ms" : "-",
                "c-blue", "Average time per query run by request handlers in the last full minute");
        }
        var a = db.activity();
        if (a != null) {
            double connPct = a.maxConnections() > 0 ? a.connections() * 100.0 / a.maxConnections() : 0;
            item(sb, "Connections", a.connections() + "/" + a.maxConnections(),
                connPct > 90 ? "c-red" : connPct > 80 ? "c-amber" : "c-muted",
                "Backends connected to the server, every role and database (pool, sampler and autovacuum included) / max_connections");
            item(sb, "Active", String.valueOf(a.active()), "c-cyan",
                "Sessions running a query now: " + a.cpu() + " on CPU, " + a.io() + " waiting on I/O, "
                    + a.lock() + " waiting on a lock, " + a.other() + " other");
            item(sb, "Lock waits", String.valueOf(a.lock()), a.lock() > 0 ? "c-amber" : "c-muted",
                "Sessions blocked by another transaction's lock");
            item(sb, "Idle in tx", String.valueOf(a.idleInTransaction()),
                a.longestIdleInTransactionSeconds() > 60 ? "c-amber" : "c-muted",
                "Sessions holding a transaction open between queries"
                    + (a.idleInTransaction() > 0 ? "; oldest " + seconds(a.longestIdleInTransactionSeconds()) : ""));
            item(sb, "Longest query", a.active() > 0 ? seconds(a.longestQuerySeconds()) : "-",
                a.longestQuerySeconds() > 300 ? "c-red" : a.longestQuerySeconds() > 30 ? "c-amber" : "c-muted",
                "Running time of the oldest query in progress");
        }
        var t = db.throughput();
        if (t != null) {
            Double hit = t.cacheHitPct();
            item(sb, "Cache hit", hit == null ? "-" : String.format("%.1f%%", hit),
                hit == null ? "c-muted" : hit < 90 ? "c-red" : hit < 95 ? "c-amber" : "c-green",
                "Share of block reads served from Postgres's buffer cache, last "
                    + Math.round(t.windowSeconds()) + "s");
            item(sb, "Commits / s", formatMetric(t.commitsPerSecond()), "c-blue", null);
            item(sb, "Rollbacks / s", formatMetric(t.rollbacksPerSecond()),
                t.rollbacksPerSecond() > 0 ? "c-amber" : "c-muted", null);
            item(sb, "Temp / s", formatBytes(Math.round(t.tempBytesPerSecond())),
                t.tempBytesPerSecond() > 0 ? "c-amber" : "c-muted",
                "Bytes written to temporary files by sorts and hashes that didn't fit in work_mem");
            item(sb, "Deadlocks", String.valueOf(t.deadlocks()), t.deadlocks() > 0 ? "c-red" : "c-muted",
                "Deadlocks in this database since this instance started sampling");
        }
        var io = db.io();
        if (io != null) {
            item(sb, "Reads / s", formatMetric(io.readsPerSecond()), "c-purple",
                "Reads of blocks not in Postgres's buffer cache (served by the OS cache or the disk), whole server"
                    + (io.avgReadMs() != null ? "; " + formatMetric(io.avgReadMs()) + " ms avg" : "; track_io_timing is off"));
            item(sb, "Writes / s", formatMetric(io.writesPerSecond()), "c-purple",
                "Block writes, whole server"
                    + (io.avgWriteMs() != null ? "; " + formatMetric(io.avgWriteMs()) + " ms avg" : ""));
        }
        sb.append("</div>");

        if (db.postgres() && a == null && db.error() == null) {
            sb.append("<div class=\"c-muted small db-note\">first Postgres sample pending</div>");
        }
        if (a != null && a.hidden() > 0) {
            sb.append("<div class=\"c-muted small db-note\">").append(a.hidden())
              .append(a.hidden() == 1 ? " session belongs" : " sessions belong")
              .append(" to other roles and is not in the load or the counts above (grant pg_read_all_stats to include them)</div>");
        }

        if (db.postgres()) loadSparkline(sb, db.load());
        poolSparkline(sb, db.load());
        querySparkline(sb, minutes);
        tables(sb, db.tables());

        sb.append("</div>\n");
    }

    private static void item(StringBuilder sb, String label, String value, String color, String title) {
        sb.append("<div");
        if (title != null) sb.append(" title=\"").append(esc(title)).append('"');
        sb.append("><span class=\"c-muted small\">").append(esc(label.toUpperCase()))
          .append("</span><br/><span class=\"").append(color).append(" bold\">").append(esc(value))
          .append("</span></div>");
    }

    /** Average active sessions per minute, stacked by wait class (cpu at the bottom). */
    private static void loadSparkline(StringBuilder sb, List<DatabaseStats.LoadMinute> load) {
        double max = load.stream().mapToDouble(DatabaseStats.LoadMinute::total).max().orElse(0);
        var last = lastLoad(load, false);
        sb.append("<div class=\"spark-label next\">Load · avg active sessions ")
          .append("<span class=\"key-cpu\">■ cpu</span> <span class=\"key-io\">■ io</span> ")
          .append("<span class=\"key-lock\">■ lock</span> <span class=\"key-other\">■ other</span>");
        if (last != null) {
            sb.append("<span class=\"right\">").append(last.partial() ? "this min " : "last min ")
              .append(formatMetric(last.total())).append("</span>");
        }
        sb.append("</div>");
        sb.append("<div class=\"spark-row\"><div class=\"spark-axis\"><span>")
          .append(formatMetric(Math.max(1, max))).append("</span><span>0</span></div>");
        sb.append("<div class=\"sparkline\">");
        double scale = Math.max(1, max);
        for (int i = load.size(); i < SLOTS; i++) sb.append("<div class=\"bar\"></div>");
        for (var m : load) {
            if (m.sessionSamples() == 0) {
                sb.append("<div class=\"bar\" title=\"no sample @ ").append(m.minute()).append("\"></div>");
                continue;
            }
            sb.append("<div class=\"bar stack\" title=\"").append(formatMetric(m.total())).append(" avg active: ")
              .append(formatMetric(m.cpu())).append(" cpu, ").append(formatMetric(m.io())).append(" io, ")
              .append(formatMetric(m.lock())).append(" lock, ").append(formatMetric(m.other())).append(" other @ ")
              .append(m.minute()).append(m.partial() ? " (so far)" : "").append("\">");
            // Tallest first, each layer drawn over the one below it.
            layer(sb, "load-other", m.total(), scale);
            layer(sb, "load-lock", m.cpu() + m.io() + m.lock(), scale);
            layer(sb, "load-io", m.cpu() + m.io(), scale);
            layer(sb, "load-cpu", m.cpu(), scale);
            sb.append("</div>");
        }
        sb.append("</div></div>\n");
    }

    private static void layer(StringBuilder sb, String cls, double value, double scale) {
        if (value <= 0) return;
        sb.append("<div class=\"").append(cls).append(' ').append(height(Math.max(2, value * 100.0 / scale))).append("\"></div>");
    }

    /** Peak pool connections in use per minute, red where a thread waited for one. */
    private static void poolSparkline(StringBuilder sb, List<DatabaseStats.LoadMinute> load) {
        if (load.isEmpty()) return;
        int poolMax = Math.max(1, load.getLast().poolMax());
        int waitedMinutes = (int) load.stream().filter(m -> m.poolWaitingPeak() > 0).count();
        sb.append("<div class=\"spark-label next\">Pool · peak connections in use");
        sb.append("<span class=\"right\">").append(waitedMinutes == 0 ? "no waits"
            : waitedMinutes + (waitedMinutes == 1 ? " minute" : " minutes") + " with waits").append("</span></div>");
        sb.append("<div class=\"spark-row\"><div class=\"spark-axis\"><span>").append(poolMax)
          .append("</span><span>0</span></div>");
        sb.append("<div class=\"sparkline sparkline-sm\">");
        for (int i = load.size(); i < SLOTS; i++) sb.append("<div class=\"bar\"></div>");
        for (var m : load) {
            double pct = m.poolActivePeak() * 100.0 / Math.max(1, m.poolMax());
            sb.append("<div class=\"bar ").append(m.poolWaitingPeak() > 0 ? "bar-err" : "bar-lo").append(' ')
              .append(height(Math.max(m.poolActivePeak() > 0 ? 4 : 0, pct)))
              .append("\" title=\"peak ").append(m.poolActivePeak()).append('/').append(m.poolMax()).append(" in use");
            if (m.poolWaitingPeak() > 0) sb.append(", ").append(m.poolWaitingPeak()).append(" waiting");
            sb.append(" @ ").append(m.minute()).append(m.partial() ? " (so far)" : "").append("\"></div>");
        }
        sb.append("</div></div>\n");
    }

    /** Average query time per minute, for queries run by request handlers. */
    private static void querySparkline(StringBuilder sb, List<Stats.MinuteSnapshot> minutes) {
        double max = 0;
        for (var m : minutes) if (m.queries() > 0) max = Math.max(max, avgQueryMs(m));
        if (max <= 0) return;
        sb.append("<div class=\"spark-label next\">Query ms · avg, in requests</div>");
        sb.append("<div class=\"spark-row\"><div class=\"spark-axis\"><span>").append(formatMetric(max))
          .append("</span><span>0</span></div>");
        sb.append("<div class=\"sparkline sparkline-sm\">");
        for (int i = minutes.size(); i < SLOTS; i++) sb.append("<div class=\"bar\"></div>");
        for (var m : minutes) {
            if (m.queries() == 0) {
                sb.append("<div class=\"bar\" title=\"no queries @ ").append(m.ts()).append("\"></div>");
                continue;
            }
            double ms = avgQueryMs(m);
            sb.append("<div class=\"bar bar-timer ").append(height(Math.max(4, ms * 100.0 / max)))
              .append("\" title=\"").append(formatMetric(ms)).append(" ms avg over ")
              .append(String.format("%,d", m.queries())).append(" queries @ ").append(m.ts()).append("\"></div>");
        }
        sb.append("</div></div>\n");
    }

    private static double avgQueryMs(Stats.MinuteSnapshot m) {
        return m.queryUs() / 1000.0 / m.queries();
    }

    private static void tables(StringBuilder sb, List<DatabaseStats.Table> tables) {
        if (tables.isEmpty()) return;
        var now = Instant.now();
        sb.append("<div class=\"spark-label next\">Tables · most rows read by sequential scans")
          .append("<span class=\"right\">since stats reset</span></div>");
        sb.append("<table><tr><th>Table</th><th class=\"num\">Seq scans</th><th class=\"num\">Seq rows read</th>")
          .append("<th class=\"num\">Index scans</th><th class=\"num\">Live rows</th><th class=\"num\">Dead</th>")
          .append("<th class=\"num\">Vacuumed</th></tr>");
        for (var t : tables) {
            long rows = t.liveRows() + t.deadRows();
            double deadPct = rows > 0 ? t.deadRows() * 100.0 / rows : 0;
            sb.append("<tr><td class=\"name\"><span class=\"lt\"><bdi>").append(esc(t.name())).append("</bdi></span></td>");
            sb.append("<td class=\"num\">").append(String.format("%,d", t.seqScans())).append("</td>");
            // A table big enough for an index to matter, scanned sequentially more often than not.
            boolean scanHeavy = t.liveRows() >= 1000 && t.seqScans() > t.indexScans();
            sb.append("<td class=\"num ").append(scanHeavy ? "c-amber" : "c-muted").append("\">").append(String.format("%,d", t.seqRowsRead())).append("</td>");
            sb.append("<td class=\"num\">").append(String.format("%,d", t.indexScans())).append("</td>");
            sb.append("<td class=\"num\">").append(String.format("%,d", t.liveRows())).append("</td>");
            sb.append("<td class=\"num ").append(deadPct > 20 ? "c-amber" : "c-muted").append("\">")
              .append(String.format("%.0f%%", deadPct)).append("</td>");
            sb.append("<td class=\"num c-muted\">").append(t.lastVacuum() == null ? "never"
                : ago(Duration.between(t.lastVacuum(), now))).append("</td></tr>");
        }
        sb.append("</table>");
    }

    /** The last minute with session samples; with {@code completeOnly}, the minute in progress is skipped. */
    private static DatabaseStats.LoadMinute lastLoad(List<DatabaseStats.LoadMinute> load, boolean completeOnly) {
        for (int i = load.size() - 1; i >= 0; i--) {
            var m = load.get(i);
            if (m.sessionSamples() > 0 && !(completeOnly && m.partial())) return m;
        }
        return null;
    }

    static String seconds(double s) {
        if (s < 60) return String.format("%.1fs", s);
        long secs = Math.round(s);
        if (secs < 3600) return (secs / 60) + "m " + (secs % 60) + "s";
        return (secs / 3600) + "h " + (secs % 3600 / 60) + "m";
    }

    private static String ago(Duration d) {
        if (d.isNegative() || d.toMinutes() < 1) return "just now";
        if (d.toHours() < 1) return d.toMinutes() + "m ago";
        if (d.toDays() < 1) return d.toHours() + "h ago";
        return d.toDays() + "d ago";
    }
}
