package io.brace;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Wraps a running Brace instance for integration testing.
 * Provides convenience methods for HTTP requests, database access, and mailer inspection.
 */
public class TestApp {

    private final Brace app;
    private final DatabaseFactory databaseFactory;
    private final HttpClient client;

    TestApp(Brace app, DatabaseFactory databaseFactory) {
        this.app = app;
        this.databaseFactory = databaseFactory;
        this.client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    // --- HTTP methods ---

    public TestResponse get(String path) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .GET()
                .build();
            return new TestResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new RuntimeException("GET " + path + " failed", e);
        }
    }

    public TestResponse post(String path, Map<String, String> formParams) {
        try {
            var body = encodeForm(formParams);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            return new TestResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new RuntimeException("POST " + path + " failed", e);
        }
    }

    public TestResponse post(String path, Map<String, String> formParams, Session session) {
        try {
            var body = encodeForm(formParams);
            var cookie = "brace_session=" + session.toCookie(app.sessionSecret());
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            return new TestResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new RuntimeException("POST " + path + " with session failed", e);
        }
    }

    public TestResponse postJson(String path, Object body) {
        try {
            var json = Json.mapper().writeValueAsString(body);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            return new TestResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new RuntimeException("POST JSON " + path + " failed", e);
        }
    }

    public TestResponse post(String path) {
        return post(path, Map.of());
    }

    public TestResponse put(String path, Map<String, String> formParams) {
        try {
            var body = encodeForm(formParams);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
            return new TestResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new RuntimeException("PUT " + path + " failed", e);
        }
    }

    public TestResponse put(String path) {
        return put(path, Map.of());
    }

    public TestResponse delete(String path) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .DELETE()
                .build();
            return new TestResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new RuntimeException("DELETE " + path + " failed", e);
        }
    }

    // --- Database access ---

    public Database db() {
        var db = new Database(databaseFactory.openSession());
        db.beginTransaction();
        return db;
    }

    public void withDb(Consumer<Database> action) {
        var db = new Database(databaseFactory.openSession());
        db.beginTransaction();
        try {
            action.accept(db);
            db.commitTransaction();
        } catch (Exception e) {
            db.rollbackTransaction();
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            db.close();
        }
    }

    public void resetDatabase() {
        var db = new Database(databaseFactory.openSession());
        db.beginTransaction();
        try {
            db.sql("SET REFERENTIAL_INTEGRITY FALSE");
            @SuppressWarnings("unchecked")
            var tables = (java.util.List<Object>) (java.util.List<?>) db.sqlQuery(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'");
            for (var row : tables) {
                String tableName = row.toString();
                if (!tableName.toLowerCase().startsWith("flyway_")) {
                    db.sql("TRUNCATE TABLE " + tableName);
                }
            }
            db.sql("SET REFERENTIAL_INTEGRITY TRUE");
            db.commitTransaction();
        } catch (Exception e) {
            db.rollbackTransaction();
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            db.close();
        }
    }

    // --- Mailer access ---

    public Mailer mailer() {
        return app.mailer();
    }

    // --- Other ---

    public Brace app() {
        return app;
    }

    public String url(String path) {
        return "http://localhost:" + app.actualPort() + path;
    }

    public void stop() throws Exception {
        app.stop();
    }

    public int port() {
        return app.actualPort();
    }

    // --- Internal ---

    private String encodeForm(Map<String, String> params) {
        return params.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                     + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }
}
