package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

public class CliCommands {

    private CliCommands() {}

    // ---------- brace errors ----------

    public static int errors(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);
        var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));

        // brace errors <id> — fetch full detail for one error from /ops/errors/{id}
        if (args.length > 0 && !args[0].startsWith("--")) {
            return errorDetail(cfg, projectDir, args[0], mode);
        }

        String url = cfg.url() + "/ops/errors";
        var params = new ArrayList<String>();
        String since = parseFlag(args, "--since");
        if (since != null) params.add("since=" + parseDuration(since));
        // --full: the pre-0.1.7 detail shape (stackTrace etc. on every row).
        // Same ?include= grammar as /ops/status.
        if (hasFlag(args, "--full")) params.add("include=detail");
        if (!params.isEmpty()) url += "?" + String.join("&", params);

        var response = CliAuth.sendAuthenticated(cfg, projectDir,
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET());

        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
            return 2;
        }

        JsonNode root = Json.mapper().readTree(response.body());

        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(root));
        } else {
            renderErrorsTable(root);
        }
        return root.size() == 0 ? 0 : 1;
    }

    private static int errorDetail(CliConfig cfg, Path projectDir, String id, CliOutput.Mode mode) throws Exception {
        var response = CliAuth.sendAuthenticated(cfg, projectDir,
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/errors/" + id))
                .header("Accept", "application/json")
                .GET());

        if (response.statusCode() == 404) {
            CliOutput.printError("error " + id + " not found"
                + " (unknown id, or the server predates the 0.1.7 /ops/errors/{id} endpoint)");
            return 1;
        }
        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
            return 2;
        }

        JsonNode detail = Json.mapper().readTree(response.body());
        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(detail));
        } else {
            renderErrorDetail(detail);
        }
        return 0;
    }

    private static void renderErrorDetail(JsonNode e) {
        System.out.println(e.path("errorType").asText("?") + ": " + e.path("message").asText(""));
        System.out.println("  id          " + e.path("id").asText("?"));
        System.out.println("  route       " + e.path("route").asText(""));
        System.out.println("  count       " + e.path("occurrenceCount").asInt(0));
        System.out.println("  first seen  " + e.path("firstSeen").asText(""));
        System.out.println("  last seen   " + e.path("lastSeen").asText(""));
        if (!e.path("resolvedAt").isNull() && !e.path("resolvedAt").isMissingNode()) {
            System.out.println("  resolved    " + e.path("resolvedAt").asText(""));
        }
        printTextSection("Request", e.path("requestDetail"));
        printTextSection("Request headers", e.path("requestHeaders"));
        printTextSection("Queries before failure", e.path("queriesBefore"));
        printTextSection("Stack trace", e.path("stackTrace"));
    }

    private static void printTextSection(String title, JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return;
        String text = value.asText("");
        if (text.isBlank()) return;
        System.out.println();
        System.out.println(title + ":");
        for (var line : text.split("\n")) System.out.println("  " + line);
    }

    private static void renderErrorsTable(JsonNode errors) {
        if (errors.size() == 0) {
            System.out.println("No errors.");
            return;
        }
        var rows = new ArrayList<List<String>>();
        for (var e : errors) {
            rows.add(List.of(
                e.path("id").asText("?"),
                String.valueOf(e.path("occurrenceCount").asInt(0)),
                e.path("lastSeen").asText(""),
                e.path("route").asText(""),
                e.path("message").asText("")));
        }
        System.out.println(CliOutput.table(
            List.of("ID", "COUNT", "LAST SEEN", "ROUTE", "MESSAGE"),
            rows, 120));
    }

    // ---------- brace logs ----------

    public static int logs(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);

        String level = parseFlag(args, "--level");
        String since = parseFlag(args, "--since");
        String limit = parseFlag(args, "--limit");   // passthrough to ?limit= (server default 200)
        boolean follow = hasFlag(args, "-f") || hasFlag(args, "--follow");
        var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));

        String baseUrl = cfg.url() + "/ops/logs";
        StringBuilder query = new StringBuilder();
        if (since != null) {
            Instant cutoff = parseDuration(since);
            query.append("since_ts=").append(cutoff.toString());
        }
        if (level != null) {
            if (query.length() > 0) query.append("&");
            query.append("level=").append(level);
        }
        if (limit != null) {
            if (query.length() > 0) query.append("&");
            query.append("limit=").append(limit);
        }
        String firstUrl = query.length() == 0 ? baseUrl : baseUrl + "?" + query;

        long lastId = renderLogsOnce(cfg, projectDir, firstUrl, mode);
        if (!follow) return 0;

        while (true) {
            Thread.sleep(1000);
            StringBuilder q = new StringBuilder("since=").append(lastId);
            if (level != null) q.append("&level=").append(level);
            if (limit != null) q.append("&limit=").append(limit);
            long newLast = renderLogsOnce(cfg, projectDir, baseUrl + "?" + q, mode);
            if (newLast > 0) lastId = newLast;
        }
    }

    private static long renderLogsOnce(CliConfig cfg, Path projectDir, String url, CliOutput.Mode mode) throws Exception {
        var response = CliAuth.sendAuthenticated(cfg, projectDir,
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET());
        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
            return 0;
        }
        JsonNode entries = Json.mapper().readTree(response.body());
        long lastId = 0;
        for (var e : entries) {
            if (mode == CliOutput.Mode.JSON) {
                System.out.println(CliOutput.json(e));
            } else {
                renderLogLine(e);
            }
            long id = e.path("id").asLong(0);
            if (id > lastId) lastId = id;
        }
        return lastId;
    }

    private static void renderLogLine(JsonNode e) {
        var sb = new StringBuilder();
        sb.append("[").append(e.path("ts").asText("?")).append("] ");
        sb.append(String.format("%-5s ", e.path("level").asText("INFO")));
        String msg = e.has("message") ? e.path("message").asText() : e.path("event").asText("");
        sb.append(msg);
        var fields = e.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String k = entry.getKey();
            if (k.equals("id") || k.equals("ts") || k.equals("level") || k.equals("message") || k.equals("event")) continue;
            sb.append(" ").append(k).append("=").append(entry.getValue().asText());
        }
        System.out.println(sb);
    }

    // ---------- brace status ----------

    /** The opt-in /ops/status blocks {@code brace status --include} accepts. */
    static final List<String> STATUS_INCLUDES = List.of("profiling", "timeseries");

    public static int status(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);
        String url = cfg.url() + "/ops/status" + statusQuery(args);

        HttpResponse<String> response;
        try {
            response = CliAuth.sendAuthenticated(cfg, projectDir,
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET());
        } catch (Exception e) {
            CliOutput.printError("Cannot reach " + cfg.url() + ": " + e.getMessage());
            return 2;
        }

        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode());
            return 2;
        }

        JsonNode root = Json.mapper().readTree(response.body());
        var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));

        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(root));
        } else {
            renderStatus(root);
        }

        return errorCount(root) > 0 ? 1 : 0;
    }

    /**
     * {@code ?include=...} for {@code --include profiling,timeseries} (empty without the
     * flag). Unknown names are rejected: the server ignores them, so a typo would silently
     * return the default snapshot.
     */
    static String statusQuery(String[] args) {
        if (!hasFlag(args, "--include")) return "";
        String include = parseFlag(args, "--include");
        if (include == null || include.isBlank() || include.startsWith("--")) {
            throw new IllegalArgumentException("--include needs a value: " + String.join(",", STATUS_INCLUDES));
        }
        var parts = new ArrayList<String>();
        for (var part : include.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (!STATUS_INCLUDES.contains(p)) {
                throw new IllegalArgumentException("Unknown --include value: " + p
                    + " (expected " + String.join(", ", STATUS_INCLUDES) + ")");
            }
            parts.add(p);
        }
        return "?include=" + String.join(",", parts);
    }

    /**
     * Unresolved error count from a /ops/status payload. 0.1.7+ servers emit
     * {@code errors.count}; pre-0.1.7 servers never did (so `brace status` always reported
     * 0 errors and exited 0 — the bug this fixes), but they do emit {@code errors.recent},
     * whose length is the correct fallback there.
     */
    private static long errorCount(JsonNode root) {
        var errors = root.path("errors");
        var count = errors.path("count");
        if (count.isNumber()) return count.asLong();
        return errors.path("recent").size();
    }

    static void renderStatus(JsonNode root) {
        System.out.println();
        System.out.println("App");
        var app = root.path("app");
        System.out.println("  uptime    " + app.path("uptime").asText("-"));
        System.out.println("  java      " + app.path("javaVersion").asText("-"));
        System.out.println();
        System.out.println("HTTP");
        var http = root.path("http");
        System.out.println("  status    " + http.path("statusCodes").toString());
        // 0.1.10+ servers only; older ones omit these, so print nothing rather than zeros.
        var rpm = http.path("requestsPerMinute");
        if (!rpm.isMissingNode()) {
            System.out.printf("  req/min   %d last minute, %.1f avg over %d min%n",
                rpm.path("lastMinute").asLong(), rpm.path("avg").asDouble(), rpm.path("windowMinutes").asInt());
        }
        var top = http.path("topRoutes");
        if (top.size() > 0) {
            System.out.println("  busiest (last " + http.path("topRoutesWindowMinutes").asInt() + " min):");
            for (var r : top) {
                System.out.printf("    %s  %.1f/min (%.1f%%)%n",
                    r.path("route").asText(), r.path("perMinute").asDouble(), r.path("sharePct").asDouble());
            }
        }
        var slow = http.path("slowestRoutes");
        if (slow.size() > 0) {
            System.out.println("  slowest:");
            for (var r : slow) {
                System.out.println("    " + r.path("route").asText() + "  "
                    + r.path("avgMs").asDouble() + "ms (" + r.path("count").asInt() + ")");
            }
        }
        // Present only when the app enabled analytics.
        var analytics = root.path("analytics");
        if (analytics.has("todayVisitors")) {
            System.out.println();
            System.out.printf("Visitors  %,d today, %,d pageviews, %,d now%n", analytics.path("todayVisitors").asLong(),
                analytics.path("todayPageviews").asLong(), analytics.path("live").asLong());
        }
        System.out.println();
        System.out.println("Errors    " + errorCount(root));
        System.out.println();
        var jvm = root.path("jvm");
        if (!jvm.isMissingNode()) {
            var heap = jvm.path("heap");
            System.out.println("Heap      " + heap.path("usedMB").asLong() + "MB / "
                + heap.path("maxMB").asLong() + "MB");
        }
        // Opt-in blocks (--include): only printed when the server returned them.
        var profiling = jvm.path("profiling");
        if (!profiling.isMissingNode()) {
            System.out.println();
            System.out.println("Hot methods (samples, last " + profiling.path("windowSeconds").asInt() + "s)");
            for (var m : first(profiling.path("hotMethods"), 10)) {
                System.out.printf("  %8d  %s%n", m.path("samples").asLong(), m.path("method").asText());
            }
            System.out.println("Top allocations");
            for (var a : first(profiling.path("topAllocations"), 10)) {
                System.out.printf("  %8s  %s%n", formatBytes(a.path("bytes").asLong()), a.path("class").asText());
            }
        }
        var minutes = root.path("timeseries").path("minutes");
        if (minutes.size() > 0) {
            System.out.println();
            System.out.println("Last minutes (requests / errors / avg ms)");
            for (int i = Math.max(0, minutes.size() - 10); i < minutes.size(); i++) {
                var m = minutes.get(i);
                System.out.printf("  %s  %6d  %4d  %8.1f%n", m.path("ts").asText(),
                    m.path("requests").asLong(), m.path("errors").asLong(), m.path("avgMs").asDouble());
            }
        }
        System.out.println();
    }

    private static List<JsonNode> first(JsonNode array, int n) {
        var out = new ArrayList<JsonNode>();
        for (var e : array) {
            if (out.size() == n) break;
            out.add(e);
        }
        return out;
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1fGB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        return bytes + "B";
    }

    // ---------- brace cache ----------

    public static int cache(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);

        var response = CliAuth.sendAuthenticated(cfg, projectDir,
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/cache"))
                .header("Accept", "application/json")
                .GET());

        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode());
            return 2;
        }
        JsonNode root = Json.mapper().readTree(response.body());
        var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));
        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(root));
        } else {
            if (!root.path("enabled").asBoolean(false)) {
                System.out.println("Cache: disabled");
            } else {
                System.out.println("Cache");
                System.out.println("  size       " + root.path("size").asLong());
                System.out.println("  hits       " + root.path("hits").asLong());
                System.out.println("  misses     " + root.path("misses").asLong());
                System.out.println("  hit rate   " + String.format("%.1f%%", root.path("hitRate").asDouble() * 100));
                System.out.println("  evictions  " + root.path("evictions").asLong());
            }
        }
        return 0;
    }

    /** {@code brace analytics [--range today|7d|30d]}: the {@code /ops/analytics/data} report. */
    public static int analytics(Path projectDir, String[] args) throws Exception {
        String range = hasFlag(args, "--range") ? parseFlag(args, "--range") : "7d";
        if (!List.of("today", "7d", "30d").contains(range)) {
            CliOutput.printError("--range must be today, 7d or 30d");
            return 1;
        }
        var cfg = CliConfig.load(projectDir, args);
        HttpResponse<String> response;
        try {
            response = CliAuth.sendAuthenticated(cfg, projectDir,
                HttpRequest.newBuilder()
                    .uri(URI.create(cfg.url() + "/ops/analytics/data?range=" + range))
                    .header("Accept", "application/json")
                    .GET());
        } catch (Exception e) {
            CliOutput.printError("Cannot reach " + cfg.url() + ": " + e.getMessage());
            return 2;
        }
        if (response.statusCode() == 404) {
            CliOutput.printError("Analytics is not enabled on this app — add .analytics() to its Brace setup");
            return 1;
        }
        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode());
            return 2;
        }
        JsonNode root = Json.mapper().readTree(response.body());
        var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));
        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(root));
        } else {
            System.out.print(renderAnalytics(root));
        }
        return 0;
    }

    static String renderAnalytics(JsonNode r) {
        var sb = new StringBuilder();
        sb.append(r.path("from").asText()).append(" – ").append(r.path("to").asText())
          .append("  (").append(r.path("timezone").asText()).append(")\n\n");
        long v = r.path("visitors").asLong(), pv = r.path("pageviews").asLong();
        sb.append(String.format("Visitors %,d   Pageviews %,d   Views/visit %.2f   Now %,d%n%n",
            v, pv, v == 0 ? 0.0 : (double) pv / v, r.path("live").asLong()));
        analyticsTable(sb, "Top pages", "PAGE", r.path("pages"), true);
        analyticsTable(sb, "Sources", "SOURCE", r.path("sources"), true);
        analyticsTable(sb, "Devices", "DEVICE", r.path("devices"), false);
        analyticsTable(sb, "Browsers", "BROWSER", r.path("browsers"), false);
        if (r.path("countries").size() > 0) analyticsTable(sb, "Countries", "COUNTRY", r.path("countries"), false);
        var parts = new ArrayList<String>();
        long total = 0;
        for (var it = r.path("notCounted").fields(); it.hasNext(); ) {
            var e = it.next();
            total += e.getValue().asLong();
            if (e.getValue().asLong() > 0) parts.add(e.getKey() + " " + String.format("%,d", e.getValue().asLong()));
        }
        sb.append(String.format("Not counted: %,d", total));
        if (!parts.isEmpty()) sb.append(" (").append(String.join(", ", parts)).append(')');
        sb.append('\n');
        return sb.toString();
    }

    private static void analyticsTable(StringBuilder sb, String title, String keyHeader, JsonNode rows, boolean views) {
        if (rows.size() == 0) return;
        var out = new ArrayList<List<String>>();
        for (var row : rows) {
            String key = row.path("key").isNull() ? "(direct)" : row.path("key").asText();
            out.add(views
                ? List.of(key, String.format("%,d", row.path("visitors").asLong()), String.format("%,d", row.path("pageviews").asLong()))
                : List.of(key, String.format("%,d", row.path("visitors").asLong())));
        }
        sb.append(title).append('\n');
        sb.append(CliOutput.table(views ? List.of(keyHeader, "VISITORS", "VIEWS") : List.of(keyHeader, "VISITORS"), out));
        sb.append('\n');
    }

    public static int cacheClear(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);

        var response = CliAuth.sendAuthenticated(cfg, projectDir,
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/cache/clear"))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()));

        if (response.statusCode() == 404) {
            // Cache not configured — nothing to clear
            CliOutput.printSuccess("cache not configured");
            return 0;
        }
        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
            return 2;
        }
        var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));
        if (mode == CliOutput.Mode.JSON) {
            System.out.println(response.body());
        } else {
            CliOutput.printSuccess("cache cleared");
        }
        return 0;
    }

    // ---------- brace resolve ----------

    public static int resolve(Path projectDir, String[] args) throws Exception {
        if (args.length == 0 || args[0].startsWith("--")) {
            CliOutput.printError("Usage: brace resolve <error-id>");
            return 2;
        }
        String id = args[0];

        var cfg = CliConfig.load(projectDir, args);

        var response = CliAuth.sendAuthenticated(cfg, projectDir,
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/errors/" + id + "/resolve"))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()));

        if (response.statusCode() == 404) {
            CliOutput.printError("error " + id + " not found");
            return 1;
        }
        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
            return 2;
        }
        var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));
        if (mode == CliOutput.Mode.JSON) {
            System.out.println(response.body());
        } else {
            CliOutput.printSuccess("resolved error " + id);
        }
        return 0;
    }

    // ---------- shared helpers ----------

    static String parseFlag(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) return args[i + 1];
        }
        return null;
    }

    static boolean hasFlag(String[] args, String name) {
        for (var a : args) if (name.equals(a)) return true;
        return false;
    }

    static Instant parseDuration(String s) {
        if (s == null) return Instant.EPOCH;
        if (s.length() < 2 || !Character.isLetter(s.charAt(s.length() - 1))) {
            throw new IllegalArgumentException("Unknown duration: " + s + " (expected e.g. 10m, 1h)");
        }
        char unit = s.charAt(s.length() - 1);
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        Duration d = switch (unit) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("Unknown duration: " + s);
        };
        return Instant.now().minus(d);
    }
}
