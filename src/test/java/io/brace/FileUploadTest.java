package io.brace;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileUploadTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0);

        app.post("/upload", req -> {
            var file = req.file("file");
            if (file == null) return Result.text("no file");
            return Result.text(file.filename() + "|" + file.contentType() + "|" + file.size());
        });

        app.post("/upload-content", req -> {
            var file = req.file("file");
            if (file == null) return Result.text("no file");
            return Result.text(new String(file.bytes(), StandardCharsets.UTF_8));
        });

        app.post("/upload-multi", req -> {
            var files = req.files("photos");
            var sb = new StringBuilder();
            for (var f : files) {
                if (!sb.isEmpty()) sb.append(",");
                sb.append(f.filename());
            }
            return Result.text(files.size() + ":" + sb);
        });

        app.post("/upload-mixed", req -> {
            var name = req.param("name");
            var file = req.file("avatar");
            var fileName = file != null ? file.filename() : "none";
            return Result.text(name + "|" + fileName);
        });

        app.post("/upload-save", req -> {
            var file = req.file("file");
            if (file == null) return Result.text("no file");
            try {
                var tmp = Files.createTempDirectory("brace-test");
                var dest = tmp.resolve(file.filename());
                file.saveTo(dest);
                var content = Files.readString(dest);
                Files.delete(dest);
                Files.delete(tmp);
                return Result.text(content);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        app.post("/no-file", req -> {
            var file = req.file("missing");
            var files = req.files("missing");
            return Result.text("file=" + (file == null ? "null" : "present") + "|size=" + files.size());
        });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    private HttpResponse<String> postMultipart(String path, String body, String boundary)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void singleFileUpload() throws Exception {
        var boundary = "----TestBoundary";
        var body = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
            "Content-Type: text/plain\r\n\r\n" +
            "hello world\r\n" +
            "--" + boundary + "--\r\n";

        var resp = postMultipart("/upload", body, boundary);
        assertEquals(200, resp.statusCode());
        assertEquals("test.txt|text/plain|11", resp.body());
    }

    @Test
    void singleFileContent() throws Exception {
        var boundary = "----TestBoundary";
        var body = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"data.txt\"\r\n" +
            "Content-Type: text/plain\r\n\r\n" +
            "file content here\r\n" +
            "--" + boundary + "--\r\n";

        var resp = postMultipart("/upload-content", body, boundary);
        assertEquals(200, resp.statusCode());
        assertEquals("file content here", resp.body());
    }

    @Test
    void multipleFileUpload() throws Exception {
        var boundary = "----TestBoundary";
        var body = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"photos\"; filename=\"a.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n" +
            "jpegdata1\r\n" +
            "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"photos\"; filename=\"b.png\"\r\n" +
            "Content-Type: image/png\r\n\r\n" +
            "pngdata2\r\n" +
            "--" + boundary + "--\r\n";

        var resp = postMultipart("/upload-multi", body, boundary);
        assertEquals(200, resp.statusCode());
        assertEquals("2:a.jpg,b.png", resp.body());
    }

    @Test
    void mixedFormFieldsAndFile() throws Exception {
        var boundary = "----TestBoundary";
        var body = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"name\"\r\n\r\n" +
            "Alice\r\n" +
            "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"avatar\"; filename=\"photo.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n" +
            "imgdata\r\n" +
            "--" + boundary + "--\r\n";

        var resp = postMultipart("/upload-mixed", body, boundary);
        assertEquals(200, resp.statusCode());
        assertEquals("Alice|photo.jpg", resp.body());
    }

    @Test
    void fileMetadata() throws Exception {
        var boundary = "----TestBoundary";
        var body = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"report.pdf\"\r\n" +
            "Content-Type: application/pdf\r\n\r\n" +
            "pdfcontent123\r\n" +
            "--" + boundary + "--\r\n";

        var resp = postMultipart("/upload", body, boundary);
        assertEquals(200, resp.statusCode());
        assertEquals("report.pdf|application/pdf|13", resp.body());
    }

    @Test
    void fileReturnsNullWhenNoFileUploaded() throws Exception {
        var boundary = "----TestBoundary";
        var body = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"other\"\r\n\r\n" +
            "value\r\n" +
            "--" + boundary + "--\r\n";

        var resp = postMultipart("/no-file", body, boundary);
        assertEquals(200, resp.statusCode());
        assertEquals("file=null|size=0", resp.body());
    }

    @Test
    void filesReturnsEmptyListWhenNoFiles() throws Exception {
        var boundary = "----TestBoundary";
        var body = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"name\"\r\n\r\n" +
            "Bob\r\n" +
            "--" + boundary + "--\r\n";

        var resp = postMultipart("/no-file", body, boundary);
        assertEquals(200, resp.statusCode());
        assertEquals("file=null|size=0", resp.body());
    }

    @Test
    void saveTo() throws Exception {
        var boundary = "----TestBoundary";
        var body = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"save-me.txt\"\r\n" +
            "Content-Type: text/plain\r\n\r\n" +
            "saved content\r\n" +
            "--" + boundary + "--\r\n";

        var resp = postMultipart("/upload-save", body, boundary);
        assertEquals(200, resp.statusCode());
        assertEquals("saved content", resp.body());
    }

    @Test
    void fileUploadReturnsNullOnNonMultipartRequest() throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/upload"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("foo=bar"))
            .build();
        var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("no file", resp.body());
    }
}
