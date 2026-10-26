package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H1 (2026-10 security review): every route carries access requirements bound to the route
 * object, and start() refuses to run while a route has none and isn't marked public. Runs with
 * the framework-test fallback switched off ({@code strictAccessCheck()}), i.e. exactly as an app
 * would.
 */
class RouteAccessTest {

    static final String SECRET = "route-access-test-secret-at-least-32-chars";

    // --- Startup check ---

    @Test
    void unmarkedRouteFailsStartAndNamesIt() {
        var ex = assertThrows(IllegalStateException.class, () -> Brace.test().sessions(SECRET).start(app -> {
            app.strictAccessCheck();
            app.get("/", req -> Result.text("home")).publicRoute();
            app.get("/reports/export", req -> Result.text("secret")).name("reports.export");
        }));
        assertTrue(ex.getMessage().contains("GET    /reports/export"), ex.getMessage());
        assertTrue(ex.getMessage().contains("reports.export"), ex.getMessage());
        assertFalse(ex.getMessage().contains("GET    /\n"), "the public route is not listed: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(".publicRoute()"), ex.getMessage());
    }

    @Test
    void publicRouteAndDefaultAccessEachSatisfyTheCheck() throws Exception {
        var app = Brace.test().sessions(SECRET).start(a -> {
            a.strictAccessCheck();
            a.defaultAccess(Access.session("userId", "/login"));
            a.get("/login", req -> Result.text("login")).publicRoute();
            a.get("/dash", req -> Result.text("dash"));
        });
        try {
            assertEquals(200, app.get("/login").status());
            assertEquals(302, app.get("/dash").status());
            assertEquals(200, app.get("/dash", Session.of("userId", "1")).status());
        } finally {
            app.stop();
        }
    }

    @Test
    void accessNoneIsAnExplicitPublicDefault() throws Exception {
        LogTap.clear();
        var app = Brace.test().start(a -> {
            a.strictAccessCheck();
            a.defaultAccess(Access.none());
            a.get("/anything", req -> Result.text("open"));
        });
        try {
            assertEquals(200, app.get("/anything").status());
            assertTrue(LogTap.snapshot().stream().anyMatch(e ->
                String.valueOf(e.fields().get("message")).contains("Access.none()")), "none() is logged");
        } finally {
            app.stop();
        }
    }

    @Test
    void accessNoneIsRejectedOutsideTheDefault() {
        assertThrows(IllegalArgumentException.class, () -> Brace.test().start(a ->
            a.get("/x", req -> Result.text("x")).access(Access.none())));
        assertThrows(IllegalArgumentException.class, () -> Brace.test().start(a ->
            a.group("/g", Access.none(), g -> g.get("/x", req -> Result.text("x")))));
    }

    // --- Stacking ---

    @Test
    void defaultGroupAndRouteRequirementsAllRunInOrder() throws Exception {
        var order = new ArrayList<String>();
        var app = Brace.test().sessions(SECRET).start(a -> {
            a.strictAccessCheck();
            a.defaultAccess(Access.check((req, s) -> { order.add("default"); return null; }));
            a.group("/outer", Access.checkRequest(req -> { order.add("outer"); return null; }), outer ->
                outer.group("/inner", Access.checkRequest(req -> { order.add("inner"); return null; }), inner ->
                    inner.get("/x", req -> Result.text("x"))
                        .access(Access.checkRequest(req -> { order.add("route"); return null; }))));
            a.get("/sibling", req -> Result.text("sibling"));
        });
        try {
            assertEquals(200, app.get("/outer/inner/x").status());
            assertEquals(List.of("default", "outer", "inner", "route"), order);
            order.clear();
            assertEquals(200, app.get("/sibling").status());
            assertEquals(List.of("default"), order, "group requirements don't leak to sibling routes");
        } finally {
            app.stop();
        }
    }

    @Test
    void anyFailingRequirementRejects() throws Exception {
        var app = Brace.test().sessions(SECRET).start(a -> {
            a.strictAccessCheck();
            a.defaultAccess(Access.session("userId", "/login"));
            a.group("/admin", Access.check((req, s) -> "admin".equals(s.get("role")) ? null : Result.forbidden()),
                admin -> admin.get("/panel", req -> Result.text("panel")));
        });
        try {
            assertEquals(302, app.get("/admin/panel").status(), "not logged in: the default rejects first");
            assertEquals(403, app.get("/admin/panel", Session.of("userId", "1")).status());
            assertEquals(200, app.get("/admin/panel", Session.of("userId", "1", "role", "admin")).status());
        } finally {
            app.stop();
        }
    }

    // --- Contradictions ---

    @Test
    void publicRouteWithItsOwnRequirementFailsStart() {
        var ex = assertThrows(IllegalStateException.class, () -> Brace.test().start(a -> {
            a.strictAccessCheck();
            a.get("/x", req -> Result.text("x"))
                .access(Access.checkRequest(req -> null))
                .publicRoute();
        }));
        assertTrue(ex.getMessage().contains("/x"), ex.getMessage());
    }

    @Test
    void publicRouteInsideAGuardedGroupFailsStart() {
        var ex = assertThrows(IllegalStateException.class, () -> Brace.test().start(a -> {
            a.strictAccessCheck();
            a.group("/admin", Access.checkRequest(req -> null), admin ->
                admin.get("/open", req -> Result.text("x")).publicRoute());
        }));
        assertTrue(ex.getMessage().contains("/admin/open"), ex.getMessage());
    }

    // --- Equivalent paths of a guarded route ---

    @Test
    void trailingSlashEncodedAndHeadVariantsAreGuarded() throws Exception {
        var app = Brace.test().sessions(SECRET).start(a -> {
            a.strictAccessCheck();
            a.defaultAccess(Access.session("userId", "/login"));
            a.get("/docs/{name}", req -> Result.text("doc:" + req.pathParam("name")));
        });
        try {
            assertEquals(302, app.get("/docs/a").status());
            assertEquals(302, app.get("/docs/a/").status());
            assertEquals(302, app.get("/docs/a%20b").status());
            assertEquals(302, app.request("HEAD", "/docs/a").send().status());
            assertEquals("doc:a b", app.get("/docs/a%20b", Session.of("userId", "1")).body());
        } finally {
            app.stop();
        }
    }

    // --- Rejections ---

    @Test
    void sessionRejectionFitsTheClient() throws Exception {
        var app = Brace.test().sessions(SECRET).start(a -> {
            a.strictAccessCheck();
            a.defaultAccess(Access.session("userId", "/login"));
            a.after(SecurityHeaders.defaults());
            a.get("/page", req -> Result.text("page"));
        });
        try {
            var page = app.get("/page");
            assertEquals(302, page.status());
            assertEquals("/login", page.redirectedTo());
            assertEquals("DENY", page.header("X-Frame-Options"), "rejections go through after-middleware");

            var htmx = app.request("GET", "/page").header("HX-Request", "true").send();
            assertEquals(401, htmx.status());
            assertEquals("/login", htmx.header("HX-Redirect"));

            var json = app.request("GET", "/page").header("Accept", "application/json").send();
            assertEquals(401, json.status());
            assertTrue(json.body().contains("unauthorized"), json.body());
        } finally {
            app.stop();
        }
    }

    // --- Lifecycle order ---

    @Test
    void plainBeforeRunsFirstAndRejectedBodiesAreNeverRead() throws Exception {
        var app = Brace.test().sessions(SECRET).start(a -> {
            a.strictAccessCheck();
            a.before(req -> "1".equals(req.queryParam("shed")) ? Result.error(429, "slow down") : null);
            a.defaultAccess(Access.session("userId", "/login"));
            a.post("/upload", req -> Result.text("got " + req.body().length())).csrf(false);
        });
        try {
            assertEquals(429, app.request("POST", "/upload?shed=1").send().status(),
                "a plain before() runs before access");
            long readsBefore = app.app().handler().plainBodyReads.sum();
            var rejected = app.request("POST", "/upload").body("x".repeat(10_000), "text/plain").send();
            assertEquals(302, rejected.status());
            assertEquals(readsBefore, app.app().handler().plainBodyReads.sum(), "an unauthenticated body is never read");
        } finally {
            app.stop();
        }
    }

    @Test
    void handlerGetsTheSessionInstanceTheRequirementSaw() throws Exception {
        var seen = new AtomicReference<Session>();
        var app = Brace.test().sessions(SECRET).start(a -> {
            a.strictAccessCheck();
            a.defaultAccess(Access.check((req, s) -> { seen.set(s); s.set("touched", "yes"); return null; }));
            a.getSession("/me", (req, s) -> Result.text(String.valueOf(s == seen.get()) + ":" + s.get("touched")));
        });
        try {
            assertEquals("true:yes", app.get("/me").body());
        } finally {
            app.stop();
        }
    }

    @Test
    void sessionlessRequirementDoesNotDecryptTheCookie() throws Exception {
        var app = Brace.test().sessions(SECRET).start(a -> {
            a.strictAccessCheck();
            a.defaultAccess(Access.checkRequest(req -> null));
            a.get("/api", req -> Result.text("ok"));
        });
        try {
            long before = app.app().handler().sessionDecrypts.sum();
            assertEquals(200, app.get("/api", Session.of("userId", "1")).status());
            assertEquals(before, app.app().handler().sessionDecrypts.sum());
        } finally {
            app.stop();
        }
    }
}
