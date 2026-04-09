package io.brace;

import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HttpTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0);

        app.get("/echo", req -> Json.of(Map.of(
            "method", req.method(),
            "path", req.path(),
            "auth", req.header("Authorization") != null ? req.header("Authorization") : "",
            "custom", req.header("X-Custom") != null ? req.header("X-Custom") : ""
        )));

        app.post("/echo-body", req -> Result.text(req.body()));

        app.get("/text", req -> Result.text("hello world"));

        app.get("/slow", req -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Result.text("too late");
        });

        app.get("/status/{code}", req -> Result.error(req.intPathParam("code"), "error"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @Test
    void getJson() {
        var resp = Http.get(url("/echo")).fetch();
        assertTrue(resp.ok());
        assertEquals(200, resp.status());
        assertTrue(resp.body().contains("\"method\":\"GET\""));
    }

    @Test
    void getJsonDeserialized() {
        record Echo(String method, String path, String auth, String custom) {}
        var echo = Http.get(url("/echo")).fetchJson(Echo.class);
        assertEquals("GET", echo.method());
        assertEquals("/echo", echo.path());
    }

    @Test
    void fetchString() {
        var body = Http.get(url("/text")).fetchString();
        assertEquals("hello world", body);
    }

    @Test
    void postWithJsonBody() {
        var resp = Http.post(url("/echo-body"))
            .bodyJson(Map.of("name", "Alice"))
            .fetch();
        assertTrue(resp.ok());
        assertTrue(resp.body().contains("Alice"));
    }

    @Test
    void postWithFormBody() {
        var resp = Http.post(url("/echo-body"))
            .bodyForm(Map.of("name", "Bob", "age", "30"))
            .fetch();
        assertTrue(resp.ok());
        assertTrue(resp.body().contains("name=Bob"));
        assertTrue(resp.body().contains("age=30"));
    }

    @Test
    void postWithStringBody() {
        var resp = Http.post(url("/echo-body"))
            .bodyString("raw text")
            .fetch();
        assertTrue(resp.ok());
        assertEquals("raw text", resp.body());
    }

    @Test
    void headersAreSent() {
        record Echo(String method, String path, String auth, String custom) {}
        var echo = Http.get(url("/echo"))
            .header("X-Custom", "test-value")
            .fetchJson(Echo.class);
        assertEquals("test-value", echo.custom());
    }

    @Test
    void bearerAuth() {
        record Echo(String method, String path, String auth, String custom) {}
        var echo = Http.get(url("/echo"))
            .bearer("my-token")
            .fetchJson(Echo.class);
        assertEquals("Bearer my-token", echo.auth());
    }

    @Test
    void responseHeader() {
        var resp = Http.get(url("/text")).fetch();
        assertNotNull(resp.header("Content-Type"));
    }

    @Test
    void nonOkResponse() {
        var resp = Http.get(url("/status/404")).fetch();
        assertFalse(resp.ok());
        assertEquals(404, resp.status());
    }

    @Test
    void timeoutThrowsException() {
        assertThrows(RuntimeException.class, () ->
            Http.get(url("/slow"))
                .timeout(Duration.ofMillis(100))
                .fetch());
    }

    @Test
    void putRequest() {
        var resp = Http.put(url("/echo-body"))
            .bodyString("put-data")
            .fetch();
        // Will get 404 since no PUT route, but the request is sent
        // Just verify it doesn't throw
        assertNotNull(resp);
    }

    @Test
    void deleteRequest() {
        var resp = Http.delete(url("/echo-body")).fetch();
        assertNotNull(resp);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
