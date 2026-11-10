package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L4: the page cache stored any status and keyed on the raw path. One {@code GET /cached?fail=1}
 * on a cold entry pinned a 400 for every visitor for the TTL, and {@code /posts/hello} and
 * {@code /posts/hell%6f} were two entries for one page (unbounded entries from one route).
 */
class PageCacheKeyTest {

    static Brace app;
    static Cache cache;
    static int port;
    static final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final AtomicInteger runs = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        cache = new Cache();
        app = Brace.app().port(0);
        app.get("/cached", cache.wrap("5m", req -> {
            runs.incrementAndGet();
            var fail = req.queryParam("fail");
            if (fail == null) return Result.text("ok");
            return switch (fail) {
                case "400" -> Result.error(400, "bad");
                case "404" -> Result.notFound();
                case "302" -> Result.redirect("/elsewhere");
                default -> Result.error(500, "boom");
            };
        }));
        app.get("/posts/{slug}", cache.wrap("5m", req -> {
            runs.incrementAndGet();
            return Result.text("post " + req.pathParam("slug"));
        }));
        app.get("/other/{slug}", cache.wrap("5m", req -> {
            runs.incrementAndGet();
            return Result.text("other " + req.pathParam("slug"));
        }));
        app.get("/pair/{a}/{b}", cache.wrap("5m", req -> {
            runs.incrementAndGet();
            return Result.text(req.pathParam("a") + "|" + req.pathParam("b"));
        }));
        app.get("/vary/{slug}", cache.wrap("5m", req -> {
            runs.incrementAndGet();
            return Result.text("[" + req.pathParam("slug") + "] q=" + req.queryParam("q"));
        }).vary("q"));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        app.stop();
        cache.close();
    }

    @BeforeEach
    void reset() {
        cache.clear();
        runs.set(0);
    }

    private static HttpResponse<String> get(String rawPath) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void nonSuccessResponsesAreNotStored() throws Exception {
        for (var status : new String[]{"400", "404", "302", "500"}) {
            cache.clear();
            runs.set(0);
            // fail is not a declared vary param, so it doesn't reach the key: the error response
            // and the plain request share one key. The error must not be what is stored there.
            assertEquals(Integer.parseInt(status), get("/cached?fail=" + status).statusCode());
            var plain = get("/cached");
            assertEquals(200, plain.statusCode(), "a " + status + " must not be replayed to the next visitor");
            assertEquals("ok", plain.body());
            assertEquals(2, runs.get(), "the plain request re-ran the handler");
            assertEquals(200, get("/cached").statusCode());
            assertEquals(2, runs.get(), "the 200 was stored");
        }
    }

    @Test
    void encodedPathVariantsShareOneEntry() throws Exception {
        assertEquals("post hello", get("/posts/hello").body());
        assertEquals("post hello", get("/posts/hell%6f").body());
        assertEquals("post hello", get("/posts/%68%65%6C%6C%6F").body());
        assertEquals(1, runs.get(), "one page, one entry");
        assertEquals(1, cache.size());
    }

    @Test
    void differentRoutesNeverShareAnEntry() throws Exception {
        assertEquals("post x", get("/posts/x").body());
        assertEquals("other x", get("/other/x").body());
        assertEquals(2, runs.get());
    }

    @Test
    void aParameterValueCannotForgeAnotherKey() throws Exception {
        // Decoded, "a?q=1" would spell the key of /vary/a?q=1 if values were not encoded.
        assertEquals("[a] q=1", get("/vary/a?q=1").body());
        assertEquals("[a?q=1] q=null", get("/vary/a%3Fq%3D1").body());
        // Decoded, "x:y" + "z" and "x" + "y:z" are two values that must stay two keys.
        assertEquals("x:y|z", get("/pair/x%3Ay/z").body());
        assertEquals("x|y:z", get("/pair/x/y%3Az").body());
        assertEquals(4, runs.get(), "separator characters inside a value are encoded in the key");
    }

    @Test
    void handBuiltRequestWithoutARouteStillCaches() {
        runs.set(0);
        Handler h = cache.wrap("5m", req -> {
            runs.incrementAndGet();
            return Result.text("v");
        });
        var req = new Request("GET", "/x", Map.of(), Map.of(), Map.of(), null);
        h.apply(req);
        h.apply(req);
        assertEquals(1, runs.get());
    }
}
