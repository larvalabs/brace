package io.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0);

        app.before("/admin/*", req -> Result.unauthorized("forbidden"));
        app.after((req, result) -> {
            result.header("X-Powered-By", "Brace");
            return result;
        });

        app.get("/hello", req -> Result.text("Hello, World!"));
        app.get("/greet/{name}", req -> Result.text("Hello, " + req.pathParam("name") + "!"));
        app.get("/json", req -> Json.of(java.util.Map.of("status", "ok")));
        app.get("/redirect", req -> Redirect.to("/hello"));
        app.get("/admin/secret", req -> Result.text("secret"));
        app.get("/not-found-if-null", req -> {
            Result.notFoundIfNull(null);
            return Result.text("should not reach here");
        });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void simpleTextResponse() throws Exception {
        var response = get("/hello");
        assertEquals(200, response.statusCode());
        assertEquals("Hello, World!", response.body());
    }

    @Test
    void pathParameter() throws Exception {
        var response = get("/greet/Alice");
        assertEquals(200, response.statusCode());
        assertEquals("Hello, Alice!", response.body());
    }

    @Test
    void jsonResponse() throws Exception {
        var response = get("/json");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\""));
        assertTrue(response.body().contains("\"ok\""));
    }

    @Test
    void redirectResponse() throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/redirect"))
            .GET()
            .build();
        var response = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(302, response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElse("").equals("/hello"));
    }

    @Test
    void notFoundFor404() throws Exception {
        var response = get("/nonexistent");
        assertEquals(404, response.statusCode());
    }

    @Test
    void beforeMiddlewareBlocksRequest() throws Exception {
        var response = get("/admin/secret");
        assertEquals(401, response.statusCode());
        assertEquals("forbidden", response.body());
    }

    @Test
    void afterMiddlewareAddsHeader() throws Exception {
        var response = get("/hello");
        assertEquals("Brace", response.headers().firstValue("X-Powered-By").orElse(""));
    }

    @Test
    void notFoundIfNullReturns404() throws Exception {
        var response = get("/not-found-if-null");
        assertEquals(404, response.statusCode());
        assertEquals("Not Found", response.body());
    }

    @Test
    void routeTableSize() {
        var routes = app.routes();
        assertEquals(6, routes.size());
    }
}
