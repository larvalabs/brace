package com.larvalabs.brace;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L10: a route whose access was never resolved (a BraceHandler built without Brace.start()) is
 * closed, not open. Brace.start() is the only thing that resolves access.
 */
class UnresolvedAccessTest {

    Server server;
    int port;

    void serve(Router router) throws Exception {
        server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new BraceHandler(router, List.of(), List.of(), null, null, new Stats()));
        server.start();
        port = connector.getLocalPort();
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.stop();
    }

    private int get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void routeAccessThrowsUntilResolved() {
        var route = new Route("GET", "/x", null, null);
        assertThrows(IllegalStateException.class, route::access);
        route.setResolvedAccess(List.of());
        assertEquals(List.of(), route.access());
    }

    @Test
    void unresolvedRouteAnswers500AndNeverRunsTheHandler() throws Exception {
        var calls = new AtomicInteger();
        var router = new Router();
        router.add("GET", "/open", req -> {
            calls.incrementAndGet();
            return Result.text("reached");
        });
        serve(router);
        assertEquals(500, get("/open"));
        assertEquals(0, calls.get());
    }

    @Test
    void resolvedRouteServes() throws Exception {
        var calls = new AtomicInteger();
        var router = new Router();
        router.add("GET", "/open", req -> {
            calls.incrementAndGet();
            return Result.text("reached");
        }).setResolvedAccess(List.of());
        serve(router);
        assertEquals(200, get("/open"));
        assertEquals(1, calls.get());
    }
}
