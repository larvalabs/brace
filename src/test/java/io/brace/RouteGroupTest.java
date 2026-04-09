package io.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteGroupTest {

    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test().start(app -> {
            app.group("/admin", admin -> {
                admin.get("/users", req -> Result.text("list users"));
                admin.post("/users", req -> Result.text("create user"));
                admin.put("/users/{id}", req -> Result.text("update user " + req.pathParam("id")));
                admin.delete("/users/{id}", req -> Result.text("delete user " + req.pathParam("id")));
            });

            app.group("/api", api -> {
                api.group("/v1", v1 -> {
                    v1.get("/posts", req -> Result.text("v1 posts"));
                    v1.group("/nested", nested -> {
                        nested.get("/deep", req -> Result.text("deep nested"));
                    });
                });
                api.get("/health", req -> Result.text("ok"));
            });

            app.get("/standalone", req -> Result.text("standalone"));
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
    }

    @Test
    void groupPrefixesGetRoute() {
        var response = testApp.get("/admin/users");
        assertEquals(200, response.status());
        assertEquals("list users", response.body());
    }

    @Test
    void groupPrefixesPostRoute() {
        var response = testApp.post("/admin/users");
        assertEquals(200, response.status());
        assertEquals("create user", response.body());
    }

    @Test
    void groupPrefixesPutRoute() {
        var response = testApp.put("/admin/users/42");
        assertEquals(200, response.status());
        assertEquals("update user 42", response.body());
    }

    @Test
    void groupPrefixesDeleteRoute() {
        var response = testApp.delete("/admin/users/99");
        assertEquals(200, response.status());
        assertEquals("delete user 99", response.body());
    }

    @Test
    void nestedGroupCombinesPrefixes() {
        var response = testApp.get("/api/v1/posts");
        assertEquals(200, response.status());
        assertEquals("v1 posts", response.body());
    }

    @Test
    void doubleNestedGroup() {
        var response = testApp.get("/api/v1/nested/deep");
        assertEquals(200, response.status());
        assertEquals("deep nested", response.body());
    }

    @Test
    void routeAtParentGroupLevel() {
        var response = testApp.get("/api/health");
        assertEquals(200, response.status());
        assertEquals("ok", response.body());
    }

    @Test
    void standaloneRouteUnaffected() {
        var response = testApp.get("/standalone");
        assertEquals(200, response.status());
        assertEquals("standalone", response.body());
    }

    @Test
    void groupRoutesAreRegisteredInRouteTable() {
        var routes = testApp.app().routes();
        // admin: get, post, put, delete (4)
        // api/v1: get, nested/deep get (2), api/health get (1) = 3
        // standalone (1)
        // total = 8
        assertEquals(8, routes.size());
    }

    @Test
    void wrongMethodReturns405OrRouteNotFound() {
        // POST to a GET-only grouped route should return 404 (no route registered)
        var response = testApp.post("/api/v1/posts");
        assertEquals(404, response.status());
    }
}
