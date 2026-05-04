package com.larvalabs.brace;

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

        app.post("/echo-meta", req -> Json.of(Map.of(
            "contentType", req.header("Content-Type") != null ? req.header("Content-Type") : "",
            "length", req.body() != null ? req.body().length() : 0
        )));

        app.post("/upload", req -> {
            var name = req.formParam("name");
            var file = req.file("file");
            return Json.of(Map.of(
                "name", name != null ? name : "",
                "filename", file != null ? file.filename() : "",
                "fileContentType", file != null ? file.contentType() : "",
                "fileSize", file != null ? file.size() : 0,
                "fileFirstByte", file != null && file.bytes().length > 0 ? (file.bytes()[0] & 0xff) : -1
            ));
        });

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

    @Test
    void postWithBytesBody() {
        record Meta(String contentType, int length) {}
        var bytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        var meta = Http.post(url("/echo-meta"))
            .bodyBytes(bytes, "application/octet-stream")
            .fetchJson(Meta.class);
        assertEquals("application/octet-stream", meta.contentType());
        assertEquals(8, meta.length());
    }

    @Test
    void bodyBytesRespectsExplicitContentType() {
        record Meta(String contentType, int length) {}
        var bytes = "PNG-DATA".getBytes();
        var meta = Http.post(url("/echo-meta"))
            .bodyBytes(bytes, "image/png")
            .fetchJson(Meta.class);
        assertEquals("image/png", meta.contentType());
    }

    @Test
    void multipartTextField() {
        record Upload(String name, String filename, String fileContentType, long fileSize, int fileFirstByte) {}
        var upload = Http.post(url("/upload"))
            .multipart()
            .field("name", "Alice")
            .fetchJson(Upload.class);
        assertEquals("Alice", upload.name());
        assertEquals("", upload.filename());
    }

    @Test
    void multipartFileUpload() {
        record Upload(String name, String filename, String fileContentType, long fileSize, int fileFirstByte) {}
        var bytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
        var upload = Http.post(url("/upload"))
            .multipart()
            .field("name", "Bob")
            .field("file", bytes, "logo.png")
            .fetchJson(Upload.class);
        assertEquals("Bob", upload.name());
        assertEquals("logo.png", upload.filename());
        assertEquals("image/png", upload.fileContentType());
        assertEquals(4, upload.fileSize());
        assertEquals(0x89, upload.fileFirstByte());
    }

    @Test
    void multipartExplicitContentType() {
        record Upload(String name, String filename, String fileContentType, long fileSize, int fileFirstByte) {}
        var bytes = new byte[]{0x42};
        var upload = Http.post(url("/upload"))
            .multipart()
            .field("file", bytes, "weird.xyz", "application/x-custom")
            .fetchJson(Upload.class);
        assertEquals("application/x-custom", upload.fileContentType());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
