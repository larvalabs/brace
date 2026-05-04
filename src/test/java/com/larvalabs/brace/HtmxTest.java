package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmxTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0);

        app.get("/htmx-check", req -> Result.text(req.isHtmx() ? "htmx" : "normal"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET();
        for (int i = 0; i < headers.length - 1; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void isHtmxReturnsFalseForNormalRequest() throws Exception {
        var response = get("/htmx-check");
        assertEquals("normal", response.body());
    }

    @Test
    void isHtmxReturnsTrueWhenHeaderPresent() throws Exception {
        var response = get("/htmx-check", "HX-Request", "true");
        assertEquals("htmx", response.body());
    }

    @Test
    void varyHeaderSetForHtmxRequests() throws Exception {
        var response = get("/htmx-check", "HX-Request", "true");
        assertEquals("HX-Request", response.headers().firstValue("Vary").orElse(""));
    }

    @Test
    void varyHeaderNotSetForNormalRequests() throws Exception {
        var response = get("/htmx-check");
        assertTrue(response.headers().firstValue("Vary").isEmpty());
    }

    @Test
    void htmxJsServedFromClasspath() throws Exception {
        var response = get("/__brace/htmx.min.js");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/javascript"));
        assertTrue(response.body().contains("htmx"));
    }
}
