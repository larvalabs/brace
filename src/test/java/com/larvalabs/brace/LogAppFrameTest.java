package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class LogAppFrameTest {

    @BeforeEach
    void reset() { LogTap.clear(); }

    private static RuntimeException withStack(StackTraceElement... frames) {
        var e = new RuntimeException("boom");
        e.setStackTrace(frames);
        return e;
    }

    @Test
    void firstAppFrameWins() {
        var e = withStack(
            new StackTraceElement("org.eclipse.jetty.server.Server", "handle", "Server.java", 10),
            new StackTraceElement("com.larvalabs.brace.BraceHandler", "handle", "BraceHandler.java", 300),
            new StackTraceElement("app.controllers.PostController", "show", "PostController.java", 42),
            new StackTraceElement("app.App", "main", "App.java", 12));
        assertEquals("app.controllers.PostController.show(PostController.java:42)", Log.appFrame(e));
    }

    @Test
    void orgPackagedAppsAreNotFilteredOut() {
        var e = withStack(
            new StackTraceElement("org.hibernate.Session", "get", "Session.java", 5),
            new StackTraceElement("org.mycompany.app.UserController", "list", "UserController.java", 7));
        assertEquals("org.mycompany.app.UserController.list(UserController.java:7)", Log.appFrame(e));
    }

    @Test
    void allLibraryFramesFallBackToTopFrame() {
        var e = withStack(
            new StackTraceElement("com.larvalabs.brace.Session", "fromCookie", "Session.java", 99),
            new StackTraceElement("java.base.Thread", "run", "Thread.java", 1));
        assertEquals("com.larvalabs.brace.Session.fromCookie(Session.java:99)", Log.appFrame(e));
    }

    @Test
    void emptyStackYieldsNull() {
        assertNull(Log.appFrame(withStack()));
    }

    @Test
    void httpErrorLogEntryCarriesAtField() {
        var e = withStack(
            new StackTraceElement("app.controllers.TalkController", "create", "TalkController.java", 21));
        Log.error("POST", "/talks", e);
        var snap = LogTap.snapshot();
        assertEquals(1, snap.size());
        assertEquals("http.error", snap.get(0).fields().get("event"));
        assertEquals("app.controllers.TalkController.create(TalkController.java:21)",
            snap.get(0).fields().get("at"));
    }
}
