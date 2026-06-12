package com.larvalabs.brace;

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
    private final CookieManager cookieManager;

    TestApp(Brace app, DatabaseFactory databaseFactory) {
        this.app = app;
        this.databaseFactory = databaseFactory;
        this.cookieManager = new CookieManager();
        this.client = HttpClient.newBuilder()
            // Brace serves HTTP/1.1 only (see docs/SECURITY.md). The JDK client defaults to
            // HTTP/2, which over cleartext triggers an h2c upgrade negotiation against a
            // server that doesn't speak it — a flaky source of garbled responses
            // ("parsing HTTP/1.1 status line, receiving [binary]") under full-suite load.
            // Pin the client to match the server.
            .version(HttpClient.Version.HTTP_1_1)
            .cookieHandler(cookieManager)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    // --- HTTP methods ---

    /**
     * General request builder — for anything the fixed methods don't cover, e.g. custom
     * headers for bearer-token APIs:
     * {@code testApp.request("GET", "/api/items").header("Authorization", "Bearer t").send()}.
     */
    public TestRequest request(String method, String path) {
        return new TestRequest(this, method, path);
    }

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

    /**
     * POST form params with this session's encrypted cookie. Routed through
     * {@link TestRequest} (like every other session variant) so the explicit session
     * evicts any framework-minted {@code brace_session} cookie from the shared jar —
     * otherwise the request carries two session values and the server reads the
     * stale jar one.
     */
    public TestResponse post(String path, Map<String, String> formParams, Session session) {
        return request("POST", path)
            .session(session)
            .body(encodeForm(formParams), "application/x-www-form-urlencoded")
            .send();
    }

    /** GET with this session's encrypted cookie. */
    public TestResponse get(String path, Session session) {
        return request("GET", path).session(session).send();
    }

    /**
     * POST form params with this session's cookie and a valid CSRF token (minted via
     * {@code Csrf.ensureToken} and injected as the {@code _csrf} form param). Use this for
     * any mutating route when sessions are enabled — CSRF is required by default. The plain
     * {@code post(...)} deliberately does NOT auto-inject a token, so missing-token 403
     * behavior stays testable.
     */
    public TestResponse postWithCsrf(String path, Map<String, String> formParams, Session session) {
        Csrf.ensureToken(session);
        return request("POST", path)
            .session(session)
            .body(encodeForm(withCsrfParam(formParams, session)), "application/x-www-form-urlencoded")
            .send();
    }

    /** PUT form params with session cookie + CSRF token as the {@code _csrf} form param. See {@link #postWithCsrf}. */
    public TestResponse putWithCsrf(String path, Map<String, String> formParams, Session session) {
        Csrf.ensureToken(session);
        return request("PUT", path)
            .session(session)
            .body(encodeForm(withCsrfParam(formParams, session)), "application/x-www-form-urlencoded")
            .send();
    }

    /**
     * DELETE with session cookie + CSRF token. DELETE has no form body, so the token rides
     * the {@code X-CSRF-Token} header (accepted by Brace's CSRF validation alongside the
     * {@code _csrf} form param). See {@link #postWithCsrf}.
     */
    public TestResponse deleteWithCsrf(String path, Session session) {
        Csrf.ensureToken(session);
        return request("DELETE", path)
            .session(session)
            .header("X-CSRF-Token", Csrf.getToken(session))
            .send();
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

    /** POST a JSON body with this session's encrypted cookie. */
    public TestResponse postJson(String path, Object body, Session session) {
        try {
            var json = Json.mapper().writeValueAsString(body);
            return request("POST", path)
                .session(session)
                .body(json, "application/json")
                .send();
        } catch (Exception e) {
            throw new RuntimeException("POST JSON " + path + " with session failed", e);
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

    /** PUT form params with this session's encrypted cookie. */
    public TestResponse put(String path, Map<String, String> formParams, Session session) {
        return request("PUT", path)
            .session(session)
            .body(encodeForm(formParams), "application/x-www-form-urlencoded")
            .send();
    }

    public TestResponse put(String path) {
        return put(path, Map.of());
    }

    /** DELETE with this session's encrypted cookie. */
    public TestResponse delete(String path, Session session) {
        return request("DELETE", path).session(session).send();
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

    /**
     * Truncates every non-Flyway table. <strong>H2-only:</strong> uses H2-specific SQL
     * ({@code SET REFERENTIAL_INTEGRITY} and the {@code PUBLIC}-schema
     * {@code INFORMATION_SCHEMA} filter) and throws {@link UnsupportedOperationException}
     * on any other database — on the Postgres/Testcontainers tier, manage test data with
     * explicit fixtures instead. Note each {@code Brace.test()} builder gets its own
     * in-memory H2 database by default, so this is only needed for isolation <em>within</em>
     * a test class (e.g. a {@code @BeforeEach} reset), not between classes.
     */
    public void resetDatabase() {
        var url = databaseFactory.jdbcUrl();
        if (url == null || !url.startsWith("jdbc:h2:")) {
            throw new UnsupportedOperationException(
                "resetDatabase() is H2-only (it relies on H2's SET REFERENTIAL_INTEGRITY and "
                + "INFORMATION_SCHEMA layout) but the JDBC URL is " + url
                + ". On Postgres, set up and tear down test data with explicit fixtures.");
        }
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

    HttpClient httpClient() {
        return client;
    }

    String sessionSecret() {
        return app.sessionSecret();
    }

    /**
     * Evict any {@code brace_session} cookie captured in the shared cookie jar. With
     * sessions enabled the framework mints a CSRF token (and Set-Cookie) on the first
     * request, and the jar replays it on every subsequent request — which would conflict
     * with an explicitly supplied {@link Session} cookie (two {@code brace_session} values,
     * server picks one nondeterministically). Called by {@link TestRequest#send()} whenever
     * an explicit session is set, so "send this session" means exactly that.
     */
    void evictSessionCookie() {
        var store = cookieManager.getCookieStore();
        for (var cookie : new java.util.ArrayList<>(store.getCookies())) {
            if ("brace_session".equals(cookie.getName())) {
                store.remove(null, cookie);
            }
        }
    }

    /** Copy of {@code params} with the session's CSRF token added as the {@code _csrf} param. */
    private static Map<String, String> withCsrfParam(Map<String, String> params, Session session) {
        var withToken = new java.util.LinkedHashMap<>(params);
        withToken.put(Csrf.TOKEN_KEY, Csrf.getToken(session));
        return withToken;
    }

    private String encodeForm(Map<String, String> params) {
        return params.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                     + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }
}
