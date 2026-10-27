package com.larvalabs.brace;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders {@code /ops/analytics}: a sibling of {@link OpsDashboard} with the same look. Plain
 * server-rendered HTML (range and metric are links, tooltips are {@code title} attributes) read
 * with the ops session cookie, so there is no token in the page. Like the ops dashboard, htmx
 * re-fetches the page and swaps its content in, every {@link #REFRESH_SECONDS} seconds.
 *
 * <p>No inline {@code <style>}, {@code style=""} or script: the stylesheet is served from
 * {@code /__brace/ops-analytics.css} and bar sizes are whole-percent classes, so the page renders
 * under the ops Content-Security-Policy (M6).
 */
final class AnalyticsDashboard {

    private AnalyticsDashboard() {}

    /** Matches the flush interval: polling faster would re-run the report on unchanged data. */
    static final long REFRESH_SECONDS = Analytics.FLUSH_INTERVAL_MS / 1000;

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM", Locale.US);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US);

    private static final Map<String, String[]> REASONS = Map.of(
        "bot", new String[] {"Bots and crawlers", "user agent looked automated"},
        "htmx", new String[] {"htmx partial swaps", "HX-Request without HX-Boosted"},
        "background", new String[] {"Background fetches", "Sec-Fetch-Mode other than navigate"},
        "prefetch", new String[] {"Prefetches", "Sec-Purpose: prefetch"},
        "excluded", new String[] {"Excluded", "configured paths or IPs, or the don't-count cookie"},
        "dropped", new String[] {"Dropped", "buffer full: database slow or unreachable"});

    static String html(Analytics.Report r, String metric, boolean ignored) {
        var sb = new StringBuilder(16_384);
        boolean today = "today".equals(r.range());
        boolean months = "12mo".equals(r.range());
        String unit = today ? "hour" : months ? "month" : "day";
        String span = today ? "today so far" : months ? "last 12 months" : "last " + r.series().size() + " days";
        sb.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <title>Brace Analytics</title>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="htmx-config" content='{"includeIndicatorStyles":false,"allowEval":false,"allowScriptTags":false}'>
            <link rel="stylesheet" href="/__brace/ops-analytics.css">
            <script src="/__brace/htmx.min.js"></script>
            </head>
            <body>
            """);

        // Content: htmx re-fetches this same view (range and metric kept) and swaps this div.
        String self = "/ops/analytics?range=" + esc(r.range()) + ("pageviews".equals(metric) ? "&amp;metric=pageviews" : "");
        sb.append("<div id=\"analytics-content\" class=\"wrap\" hx-get=\"").append(self)
          .append("\" hx-select=\"#analytics-content\" hx-target=\"this\" hx-swap=\"outerHTML\" hx-trigger=\"every ")
          .append(REFRESH_SECONDS).append("s\">\n");

        // Header
        sb.append("<div class=\"header\"><span class=\"brand\"><span class=\"title\">┌ BRACE</span>")
          .append("<span class=\"switch\"><a href=\"/ops/dashboard\">ops</a>")
          .append("<a href=\"/ops/analytics\" aria-current=\"page\">analytics</a></span></span>")
          .append("<span class=\"meta\"><span class=\"live\" title=\"Distinct visitors who loaded a page in the last ")
          .append(Analytics.LIVE_WINDOW.toMinutes()).append(" minutes, across all instances\">● ").append(fmt(r.live()))
          .append(" visitor").append(r.live() == 1 ? "" : "s").append(" now</span><span>")
          .append(esc(r.timezone())).append("</span><span>").append(REFRESH_SECONDS)
          .append("s refresh</span></span></div>\n");

        // Range + dates
        sb.append("<div class=\"controls\"><nav class=\"ranges\" aria-label=\"Date range\">");
        for (var range : List.of(new String[] {"today", "Today"}, new String[] {"7d", "7 days"}, new String[] {"30d", "30 days"},
                new String[] {"12mo", "12 months"})) {
            sb.append("<a href=\"/ops/analytics?range=").append(range[0]);
            if ("pageviews".equals(metric)) sb.append("&amp;metric=pageviews");
            sb.append('"');
            if (range[0].equals(r.range())) sb.append(" aria-current=\"page\"");
            sb.append('>').append(range[1]).append("</a>");
        }
        sb.append("</nav><span class=\"note\">")
          .append(today ? esc(r.to().format(LONG))
              : months ? esc(r.from().format(MONTH_YEAR) + " – " + r.to().format(MONTH_YEAR))
              : esc(r.from().format(SHORT) + " – " + r.to().format(SHORT)))
          .append("</span></div>\n");

        // Tiles
        long notCounted = r.notCounted().values().stream().mapToLong(Long::longValue).sum();
        String compare = today ? "yesterday so far" : months ? "the previous 12 months" : "the previous " + r.series().size() + " days";
        sb.append("<div class=\"tiles\">");
        metricTile(sb, r, "visitors", "Visitors", fmt(r.visitors()), delta(r.visitors(), r.previousVisitors(), compare), metric);
        metricTile(sb, r, "pageviews", "Pageviews", fmt(r.pageviews()), delta(r.pageviews(), r.previousPageviews(), compare), metric);
        double ppv = r.visitors() == 0 ? 0 : (double) r.pageviews() / r.visitors();
        Double prevPpv = r.previousVisitors() == null || r.previousVisitors() == 0 ? null
            : (double) r.previousPageviews() / r.previousVisitors();
        sb.append("<div class=\"tile\"><span class=\"label\">Views per visit</span><span class=\"value\">")
          .append(String.format(Locale.US, "%.2f", ppv)).append("</span><span class=\"detail\">")
          .append(delta(ppv, prevPpv, compare)).append("</span></div>");
        sb.append("<div class=\"tile\"><span class=\"label\">Not counted</span><span class=\"value\">")
          .append(fmt(notCounted)).append("</span><span class=\"detail\">bots, prefetches, htmx partials</span></div>");
        sb.append("</div>\n");

        // Chart
        boolean pv = "pageviews".equals(metric);
        sb.append("<section class=\"section\"><div class=\"section-head\"><span class=\"h\">")
          .append(pv ? "Pageviews" : "Visitors").append(" / ").append(unit).append("</span><span class=\"sub\">")
          .append(span).append("</span></div>");
        chart(sb, r.series(), pv, today);
        sb.append("</section>\n");

        // Pages + sources
        sb.append("<div class=\"grid2\">");
        table(sb, "Top pages", "by visitors", "Page", r.pages(), true, r.visitors());
        table(sb, "Sources", "referrer host · utm_source · ref", "Source", r.sources(), true, r.visitors());
        sb.append("</div>\n");

        // Breakdowns
        sb.append("<div class=\"grid3\">");
        table(sb, "Devices", null, "Device", r.devices(), false, r.visitors());
        table(sb, "Browsers", null, "Browser", r.browsers(), false, r.visitors());
        table(sb, "Operating systems", null, "OS", r.os(), false, r.visitors());
        if (!r.countries().isEmpty()) {
            table(sb, "Countries", "from the proxy's country header", "Country", r.countries(), false, r.visitors());
        }
        sb.append("</div>\n");

        // Not counted
        sb.append("<section class=\"section\"><div class=\"section-head\"><span class=\"h\">Not counted</span>")
          .append("<span class=\"sub\">page requests the filters rejected in this range</span></div><div class=\"filtered\">");
        for (var e : r.notCounted().entrySet()) {
            if (e.getKey().equals("dropped") && e.getValue() == 0) continue;
            String[] meta = REASONS.getOrDefault(e.getKey(), new String[] {e.getKey(), ""});
            sb.append("<div><span class=\"label\">").append(esc(meta[0])).append("</span><span class=\"v\">")
              .append(fmt(e.getValue())).append("</span><span class=\"detail\">").append(esc(meta[1])).append("</span></div>");
        }
        sb.append("</div></section>\n");

        // Footer
        sb.append("<div class=\"foot\"><span>Counted on the server · no script · no cookies · no IPs stored</span>")
          .append("<span>A visitor is counted once per day</span>")
          .append("<form method=\"post\" action=\"/ops/analytics/ignore\">")
          .append("<input type=\"hidden\" name=\"range\" value=\"").append(esc(r.range())).append("\">")
          .append("<input type=\"hidden\" name=\"on\" value=\"").append(ignored ? "0" : "1").append("\">");
        if (ignored) {
            sb.append("<button class=\"on\" type=\"submit\">This browser's visits are not counted · count them</button>");
        } else {
            sb.append("<button type=\"submit\">Don't count this browser's visits</button>");
        }
        sb.append("</form></div>\n</div>\n</body>\n</html>\n");
        return sb.toString();
    }

    private static void metricTile(StringBuilder sb, Analytics.Report r, String key, String label, String value,
                                   String detail, String metric) {
        sb.append("<a class=\"tile\" href=\"/ops/analytics?range=").append(esc(r.range()));
        if (key.equals("pageviews")) sb.append("&amp;metric=pageviews");
        sb.append('"');
        if (key.equals(metric)) sb.append(" aria-current=\"true\"");
        sb.append("><span class=\"label\">").append(label).append("</span><span class=\"value\">").append(value)
          .append("</span><span class=\"detail\">").append(detail).append("</span></a>");
    }

    private static void chart(StringBuilder sb, List<Analytics.Point> series, boolean pageviews, boolean today) {
        long max = 0;
        for (var p : series) max = Math.max(max, pageviews ? p.pageviews() : p.visitors());
        long top = niceMax(max);
        sb.append("<div class=\"chart\"><div class=\"yaxis\">");
        for (int i = 4; i >= 0; i--) sb.append("<span>").append(axis(top * i / 4)).append("</span>");
        sb.append("</div><div class=\"plot\">");
        for (int i = 0; i < series.size(); i++) {
            var p = series.get(i);
            long v = pageviews ? p.pageviews() : p.visitors();
            double pct = top == 0 ? 0 : v * 100.0 / top;
            boolean partial = i == series.size() - 1;
            sb.append("<div class=\"bar ").append(pageviews ? "p" : "v").append(partial ? " partial" : "")
              .append(' ').append(size("h", v > 0 ? Math.max(pct, 1) : 0))
              .append("\" title=\"").append(esc(bucketLabel(p.bucket(), today))).append(partial ? " (so far)" : "")
              .append(": ").append(fmt(p.visitors())).append(" visitors, ").append(fmt(p.pageviews()))
              .append(" views\"></div>");
        }
        sb.append("</div><div class=\"xaxis\">");
        int every = series.size() <= 12 ? 1 : today ? 4 : 5;
        for (int i = 0; i < series.size(); i++) {
            boolean show = i % every == 0;
            sb.append("<span>").append(show ? esc(tickLabel(series.get(i).bucket(), today)) : "").append("</span>");
        }
        sb.append("</div></div>");
    }

    private static void table(StringBuilder sb, String title, String sub, String keyHeader, List<Analytics.Row> rows,
                              boolean withViews, long totalVisitors) {
        sb.append("<section class=\"section\"><div class=\"section-head\"><span class=\"h\">").append(esc(title))
          .append("</span>");
        if (sub != null) sb.append("<span class=\"sub\">").append(esc(sub)).append("</span>");
        sb.append("</div>");
        if (rows.isEmpty()) {
            sb.append("<div class=\"empty\">No views in this range yet.</div></section>");
            return;
        }
        long top = rows.getFirst().visitors();
        sb.append("<table><tr><th>").append(esc(keyHeader)).append("</th><th class=\"n\">Visitors</th><th class=\"n\">")
          .append(withViews ? "Views" : "%").append("</th></tr>");
        for (var row : rows) {
            double share = top == 0 ? 0 : row.visitors() * 100.0 / top;
            String key = row.key() != null && keyHeader.equals("Device")
                ? Character.toUpperCase(row.key().charAt(0)) + row.key().substring(1) : row.key();
            String name = key == null ? "<span class=\"direct\">Direct / none</span>" : esc(key);
            sb.append("<tr><td><div class=\"rowbar\"><span class=\"fill ").append(size("w", share))
              .append("\"></span><span class=\"txt\" title=\"")
              .append(key == null ? "Direct / none" : esc(key)).append("\">").append(name)
              .append("</span></div></td><td class=\"n\">").append(fmt(row.visitors())).append("</td><td class=\"n m\">");
            if (withViews) {
                sb.append(fmt(row.pageviews()));
            } else {
                sb.append(totalVisitors == 0 ? "0" : Math.round(row.visitors() * 100.0 / totalVisitors)).append('%');
            }
            sb.append("</td></tr>");
        }
        sb.append("</table></section>");
    }

    /**
     * The ops-analytics.css class for a bar {@code pct} percent tall ({@code h}) or wide ({@code w}):
     * one per whole percent, 0 to 100. A class rather than a {@code style} attribute, which the ops
     * CSP blocks.
     */
    private static String size(String axis, double pct) {
        return axis + Math.round(Math.min(100, Math.max(0, pct)));
    }

    /** {@code previous} is null when stored data doesn't cover the whole earlier period. */
    private static String delta(double current, Number previous, String compare) {
        if (previous == null) {
            return "<span title=\"Stored page views don't reach back to the start of " + esc(compare)
                + " yet\">no comparison yet</span>";
        }
        if (previous.doubleValue() <= 0) return "up from 0 " + (compare.startsWith("the ") ? "in " : "") + esc(compare);
        return deltaPct(current, previous.doubleValue(), compare);
    }

    private static String deltaPct(double current, double previous, String compare) {
        double pct = (current - previous) / previous * 100;
        String cls = pct >= 0 ? "up" : "down";
        return "<span class=\"" + cls + "\">" + (pct >= 0 ? "↑ " : "↓ ")
            + String.format(Locale.US, "%.0f%%", Math.abs(pct)) + "</span> vs " + esc(compare);
    }

    static long niceMax(long v) {
        if (v <= 4) return 4;
        double p = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[] {1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10}) {
            if (m * p >= v) {
                long n = (long) Math.ceil(m * p);
                return n % 4 == 0 ? n : n + (4 - n % 4);
            }
        }
        return (long) (10 * p);
    }

    private static String axis(long v) {
        if (v >= 10_000) return (v % 1000 == 0 ? String.valueOf(v / 1000) : String.format(Locale.US, "%.1f", v / 1000.0)) + "k";
        return fmt(v);
    }

    /** Buckets are an hour ({@code "14"}), a day ({@code "2026-10-07"}) or a month ({@code "2026-10"}). */
    private static String bucketLabel(String bucket, boolean hourly) {
        if (hourly) return bucket + ":00";
        if (bucket.length() == 7) return java.time.YearMonth.parse(bucket).format(MONTH_YEAR);
        return java.time.LocalDate.parse(bucket).format(LONG);
    }

    private static String tickLabel(String bucket, boolean hourly) {
        if (hourly) return bucket;
        if (bucket.length() == 7) return java.time.YearMonth.parse(bucket).format(MONTH);
        return java.time.LocalDate.parse(bucket).format(SHORT);
    }

    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
