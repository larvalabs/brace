package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** M1: a static mapping prefix matches only at a path-segment boundary. */
class StaticPrefixBoundaryTest {

    static HttpClient client = HttpClient.newHttpClient();

    private static HttpResponse<String> get(Brace app, String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + app.actualPort() + path))
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path dirWithPrivateSecret() throws Exception {
        var dir = Files.createTempDirectory("brace-m1");
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("X"), "x");
        Files.createDirectory(dir.resolve("private"));
        Files.writeString(dir.resolve("private/secret.txt"), "secret");
        return dir;
    }

    @Test
    void prefixRequiresSegmentBoundary() throws Exception {
        var dir = dirWithPrivateSecret();
        var app = Brace.app().port(0).staticFiles("/files", dir.toString());
        app.start();
        try {
            assertEquals(404, get(app, "/filesX").statusCode());
            assertEquals(404, get(app, "/filesprivate/secret.txt").statusCode());
            assertEquals(404, get(app, "/filesa.txt").statusCode());
            var ok = get(app, "/files/private/secret.txt");
            assertEquals(200, ok.statusCode());
            assertEquals("secret", ok.body());
            assertEquals(200, get(app, "/files/a.txt").statusCode());
        } finally {
            app.stop();
        }
    }

    @Test
    void trailingSlashPrefixStillWorks() throws Exception {
        var dir = dirWithPrivateSecret();
        var app = Brace.app().port(0).staticFiles("/files/", dir.toString());
        app.start();
        try {
            assertEquals(200, get(app, "/files/a.txt").statusCode());
            assertEquals(200, get(app, "/files/private/secret.txt").statusCode());
            assertEquals(404, get(app, "/filesa.txt").statusCode());
        } finally {
            app.stop();
        }
    }

    @Test
    void rootPrefixStillWorks() throws Exception {
        var dir = dirWithPrivateSecret();
        var app = Brace.app().port(0).staticFiles("/", dir.toString());
        app.start();
        try {
            assertEquals(200, get(app, "/a.txt").statusCode());
            assertEquals(200, get(app, "/private/secret.txt").statusCode());
        } finally {
            app.stop();
        }
    }
}
