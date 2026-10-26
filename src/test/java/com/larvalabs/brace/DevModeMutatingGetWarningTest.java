package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review L11: in dev mode (brace.mode=dev, read at handler construction), a GET or HEAD
 * whose transaction performed a write logs a one-time warning per route pattern. Reading GETs,
 * writing POSTs and prod mode stay quiet. Asserts through LogTap, which is written synchronously.
 */
class DevModeMutatingGetWarningTest {

    static Brace devApp;
    static Brace prodApp;
    static int devPort;
    static int prodPort;
    static String originalMode;
    static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void startApps() throws Exception {
        originalMode = System.getProperty("brace.mode");

        System.setProperty("brace.mode", "dev");
        devApp = Brace.app().port(0).database(db("l11dev"));
        registerRoutes(devApp);
        devApp.start();
        devPort = devApp.actualPort();

        System.clearProperty("brace.mode");
        prodApp = Brace.app().port(0).database(db("l11prod"));
        registerRoutes(prodApp);
        prodApp.start();
        prodPort = prodApp.actualPort();
    }

    static DatabaseFactory db(String name) {
        return new DatabaseFactory("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", null, null, List.of(Post.class));
    }

    static void registerRoutes(Brace app) {
        app.get("/read", (DbHandler) (req, db) -> Result.text("count=" + db.count(Post.class)));
        app.get("/insert/{n}", (DbHandler) (req, db) -> {
            db.insert(newPost());
            return Result.text("inserted");
        });
        app.get("/raw-sql", (DbHandler) (req, db) -> {
            db.sql("DELETE FROM posts WHERE title = ?", "nope");
            return Result.text("ok");
        });
        app.get("/head-insert", (DbHandler) (req, db) -> {
            db.insert(newPost());
            return Result.text("inserted");
        });
        app.post("/insert", (DbHandler) (req, db) -> {
            db.insert(newPost());
            return Result.text("inserted");
        }).csrf(false);
    }

    static Post newPost() {
        var p = new Post();
        p.title = "t";
        p.body = "b";
        p.createdAt = Instant.now();
        return p;
    }

    @AfterAll
    static void stopApps() throws Exception {
        if (originalMode != null) {
            System.setProperty("brace.mode", originalMode);
        } else {
            System.clearProperty("brace.mode");
        }
        if (devApp != null) devApp.stop();
        if (prodApp != null) prodApp.stop();
    }

    @BeforeEach
    void clearLog() {
        LogTap.clear();
    }

    private static long warnings() {
        return LogTap.snapshot().stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .map(e -> String.valueOf(e.fields().get("message")))
            .filter(m -> m.contains("wrote to the database"))
            .count();
    }

    private static boolean warned(String pattern) {
        return LogTap.snapshot().stream()
            .map(e -> String.valueOf(e.fields().get("message")))
            .anyMatch(m -> m.contains("wrote to the database") && m.contains(pattern));
    }

    private static int send(int port, String method, String path) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
        var request = switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
            case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            default -> builder.GET().build();
        };
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void writingGetWarnsOncePerRoutePattern() throws Exception {
        assertEquals(200, send(devPort, "GET", "/insert/1"));
        assertEquals(1, warnings());
        assertTrue(warned("GET /insert/{n}"), "warning names the route pattern, not the concrete URL");

        assertEquals(200, send(devPort, "GET", "/insert/2"));
        assertEquals(200, send(devPort, "GET", "/insert/3"));
        assertEquals(1, warnings(), "the same pattern warns only once");
    }

    @Test
    void sqlWriteInAGetWarns() throws Exception {
        assertEquals(200, send(devPort, "GET", "/raw-sql"));
        assertTrue(warned("/raw-sql"));
    }

    @Test
    void writingHeadWarns() throws Exception {
        assertEquals(200, send(devPort, "HEAD", "/head-insert"));
        assertTrue(warned("/head-insert"));
    }

    @Test
    void readingGetDoesNotWarn() throws Exception {
        assertEquals(200, send(devPort, "GET", "/read"));
        assertEquals(0, warnings());
    }

    @Test
    void writingPostDoesNotWarn() throws Exception {
        assertEquals(200, send(devPort, "POST", "/insert"));
        assertEquals(0, warnings());
    }

    @Test
    void prodModeDoesNotWarn() throws Exception {
        assertEquals(200, send(prodPort, "GET", "/insert/1"));
        assertEquals(0, warnings());
    }
}
