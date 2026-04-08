package io.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;

class StorageIntegrationTest {
    private static Brace app;
    private static int port;
    private static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void startApp() throws Exception {
        var storage = new Storage("test-key", "test-secret", "test-bucket", "us-east-1", null, "https://cdn.test.com");
        app = Brace.app().port(0).storage(storage);
        app.get("/has-storage", req -> {
            var s = req.storage();
            return Result.text(s.url("test.jpg"));
        });
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception { app.stop(); }

    @Test
    void storageAvailableViaRequest() throws Exception {
        var response = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/has-storage")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("https://cdn.test.com/test.jpg", response.body());
    }
}
