package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MiddlewareTest {

    private Request fakeRequest(String method, String path) {
        return new Request(method, path, Map.of(), Map.of(), Map.of(), null);
    }

    @Test
    void beforeMiddlewareCanShortCircuit() {
        Middleware.Before before = req -> Result.unauthorized("nope");
        var result = before.handle(fakeRequest("GET", "/secret"));
        assertNotNull(result);
        assertEquals(401, result.status());
    }

    @Test
    void beforeMiddlewareReturnsNullToContinue() {
        Middleware.Before before = req -> null;
        var result = before.handle(fakeRequest("GET", "/ok"));
        assertNull(result);
    }

    @Test
    void afterMiddlewareCanModifyResult() {
        Middleware.After after = (req, result) -> {
            result.header("X-Frame-Options", "DENY");
            return result;
        };
        var result = Result.text("hello");
        var modified = after.handle(fakeRequest("GET", "/"), result);
        assertEquals("DENY", modified.header("X-Frame-Options"));
    }

    @Test
    void pathPatternMatchesWildcard() {
        var pattern = Middleware.PathPattern.compile("/admin/*");
        assertTrue(pattern.matches("/admin/dashboard"));
        assertTrue(pattern.matches("/admin/users"));
        assertFalse(pattern.matches("/login"));
        assertFalse(pattern.matches("/admin"));
    }

    @Test
    void pathPatternMatchesExact() {
        var pattern = Middleware.PathPattern.compile("/login");
        assertTrue(pattern.matches("/login"));
        assertFalse(pattern.matches("/login/callback"));
    }

    @Test
    void boundBeforeAppliesOnlyToMatchingPaths() {
        var bound = new Middleware.BoundBefore(
            Middleware.PathPattern.compile("/admin/*"),
            req -> Result.unauthorized("forbidden")
        );
        assertNotNull(bound.apply(fakeRequest("GET", "/admin/dashboard")));
        assertNull(bound.apply(fakeRequest("GET", "/login")));
    }

    @Test
    void boundBeforeWithNullPatternMatchesAll() {
        var bound = new Middleware.BoundBefore(
            null,
            req -> Result.unauthorized("forbidden")
        );
        assertNotNull(bound.apply(fakeRequest("GET", "/anything")));
    }

    @Test
    void boundAfterAppliesOnlyToMatchingPaths() {
        var bound = new Middleware.BoundAfter(
            Middleware.PathPattern.compile("/api/*"),
            (req, result) -> {
                result.header("X-Api", "true");
                return result;
            }
        );
        var result1 = Result.text("hello");
        var modified1 = bound.apply(fakeRequest("GET", "/api/data"), result1);
        assertEquals("true", modified1.header("X-Api"));

        var result2 = Result.text("hello");
        var modified2 = bound.apply(fakeRequest("GET", "/login"), result2);
        assertNull(modified2.header("X-Api"));
    }
}
