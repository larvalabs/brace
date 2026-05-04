package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app()
            .port(0)
            .templates("src/test/resources/views");

        app.get("/hello", req -> View.of("hello"));
        app.get("/greet/{name}", req ->
            View.of("params", "name", req.pathParam("name"), "count", 42));
        app.get("/layout", req ->
            View.of("withLayout", "message", "It works!"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
        // Reset the static engine so other tests (ResultTest) still work with stub
        View.setEngine(null);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void simpleTemplate() throws Exception {
        var response = get("/hello");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello from JTE!"));
    }

    @Test
    void templateWithParams() throws Exception {
        var response = get("/greet/Alice");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello Alice"));
        assertTrue(response.body().contains("42 items"));
    }

    @Test
    void templateWithLayout() throws Exception {
        var response = get("/layout");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<!DOCTYPE html>"));
        assertTrue(response.body().contains("<title>Test Page</title>"));
        assertTrue(response.body().contains("<h1>It works!</h1>"));
    }

    @Test
    void viewRenderReturnsString() {
        var html = View.render("hello");
        assertTrue(html.contains("Hello from JTE!"));
    }

    @Test
    void contentTypeIsHtml() throws Exception {
        var response = get("/hello");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
    }

}
