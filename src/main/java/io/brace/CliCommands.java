package io.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

public class CliCommands {

    private static final HttpClient http = HttpClient.newHttpClient();

    private CliCommands() {}

    // ---------- brace errors ----------

    public static int errors(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);
        String token = CliAuth.bearer(cfg, projectDir);

        String url = cfg.url() + "/ops/errors";
        String since = parseFlag(args, "--since");
        if (since != null) {
            Instant cutoff = parseDuration(since);
            url += "?since=" + cutoff.toString();
        }

        var response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
            return 2;
        }

        JsonNode root = Json.mapper().readTree(response.body());
        var mode = CliOutput.autoMode(hasFlag(args, "--json"), hasFlag(args, "--pretty"));

        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(root));
        } else {
            renderErrorsTable(root);
        }
        return root.size() == 0 ? 0 : 1;
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
        String token = CliAuth.bearer(cfg, projectDir);

        String level = parseFlag(args, "--level");
        String since = parseFlag(args, "--since");
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
        String firstUrl = query.length() == 0 ? baseUrl : baseUrl + "?" + query;

        long lastId = renderLogsOnce(firstUrl, token, mode);
        if (!follow) return 0;

        while (true) {
            Thread.sleep(1000);
            StringBuilder q = new StringBuilder("since=").append(lastId);
            if (level != null) q.append("&level=").append(level);
            long newLast = renderLogsOnce(baseUrl + "?" + q, token, mode);
            if (newLast > 0) lastId = newLast;
        }
    }

    private static long renderLogsOnce(String url, String token, CliOutput.Mode mode) throws Exception {
        var response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            CliOutput.printError("HTTP " + response.statusCode() + ": " + response.body());
            return 0;
        }
        JsonNode entries = Json.mapper().readTree(response.body());
        long lastId = 0;
        for (var e : entries) {
            if (mode == CliOutput.Mode.JSON) {
                System.out.println(CliOutput.jsonCompact(e));
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

    public static int status(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);
        String token;
        try {
            token = CliAuth.bearer(cfg, projectDir);
        } catch (Exception e) {
            CliOutput.printError("Cannot reach " + cfg.url() + ": " + e.getMessage());
            return 2;
        }

        HttpResponse<String> response;
        try {
            response = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(cfg.url() + "/ops/status"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
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

        int errorCount = root.path("errors").path("count").asInt(0);
        return errorCount > 0 ? 1 : 0;
    }

    private static void renderStatus(JsonNode root) {
        System.out.println();
        System.out.println("App");
        var app = root.path("app");
        System.out.println("  uptime    " + app.path("uptime").asText("-"));
        System.out.println("  java      " + app.path("javaVersion").asText("-"));
        System.out.println();
        System.out.println("HTTP");
        var http = root.path("http");
        System.out.println("  status    " + http.path("statusCodes").toString());
        var slow = http.path("slowestRoutes");
        if (slow.size() > 0) {
            System.out.println("  slowest:");
            for (var r : slow) {
                System.out.println("    " + r.path("route").asText() + "  "
                    + r.path("avgMs").asDouble() + "ms (" + r.path("count").asInt() + ")");
            }
        }
        System.out.println();
        var errors = root.path("errors");
        System.out.println("Errors    " + errors.path("count").asInt(0));
        System.out.println();
        var jvm = root.path("jvm");
        if (!jvm.isMissingNode()) {
            var heap = jvm.path("heap");
            System.out.println("Heap      " + heap.path("usedMB").asLong() + "MB / "
                + heap.path("maxMB").asLong() + "MB");
        }
        System.out.println();
    }

    // ---------- brace cache ----------

    public static int cache(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);
        String token = CliAuth.bearer(cfg, projectDir);

        var response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/cache"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

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

    public static int cacheClear(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);
        String token = CliAuth.bearer(cfg, projectDir);

        var response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/cache/clear"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

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
        String token = CliAuth.bearer(cfg, projectDir);

        var response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(cfg.url() + "/ops/errors/" + id + "/resolve"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

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
