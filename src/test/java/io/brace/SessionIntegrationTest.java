package io.brace;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionIntegrationTest {

    static Brace app;
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0).sessions("test-secret-at-least-32-chars-long");

        // Login sets session
        app.get("/login", (SessionHandler) (req, session) -> {
            session.set("userId", 42);
            session.set("role", "admin");
            return Result.text("logged in");
        });

        // Profile reads session
        app.get("/profile", (SessionHandler) (req, session) -> {
            if (!session.has("userId")) return Result.unauthorized("not logged in");
            return Result.text("user:" + session.get("userId") + " role:" + session.get("role"));
        });

        // Logout clears session
        app.get("/logout", (SessionHandler) (req, session) -> {
            session.clear();
            return Result.text("logged out");
        });

        // Flash: set and redirect
        app.get("/set-flash", (SessionHandler) (req, session) -> {
            session.flash("success", "Item saved!");
            return Redirect.to("/read-flash");
        });

        // Flash: read
        app.get("/read-flash", (SessionHandler) (req, session) -> {
            String msg = session.flash("success");
            return Result.text("flash:" + (msg != null ? msg : "none"));
        });

        // No session needed
        app.get("/health", req -> Result.text("ok"));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    @Test
    void loginSetsSessionCookie() throws Exception {
        var response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var setCookie = response.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.startsWith("brace_session="), "Expected session cookie, got: " + setCookie);
        assertTrue(setCookie.contains("HttpOnly"));
    }

    @Test
    void sessionPersistsAcrossRequests() throws Exception {
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new java.net.CookieManager()).build();

        // Login
        client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        // Profile should see session
        var profile = client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/profile")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, profile.statusCode());
        assertTrue(profile.body().contains("user:42"));
        assertTrue(profile.body().contains("role:admin"));
    }

    @Test
    void noSessionReturnsUnauthorized() throws Exception {
        var response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/profile")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void healthCheckWithoutSession() throws Exception {
        var response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
    }

    @Test
    void flashMessageAvailableAfterRedirect() throws Exception {
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        // Set flash and redirect
        var flashResponse = client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/set-flash")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(302, flashResponse.statusCode());

        // Extract session cookie from redirect response
        var setCookie = flashResponse.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.startsWith("brace_session="), "Expected session cookie on redirect");
        var cookie = setCookie.split(";")[0]; // just "brace_session=..."

        // Follow redirect with cookie — flash should be available
        var readResponse = client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/read-flash"))
                .header("Cookie", cookie).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, readResponse.statusCode());
        assertEquals("flash:Item saved!", readResponse.body());

        // Extract updated cookie (flash consumed)
        var updatedCookie = readResponse.headers().firstValue("Set-Cookie").orElse(setCookie).split(";")[0];

        // Third request — flash should be gone
        var gone = client.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/read-flash"))
                .header("Cookie", updatedCookie).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, gone.statusCode());
        assertEquals("flash:none", gone.body());
    }
}
