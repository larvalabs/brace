package io.brace;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BraceHandler extends org.eclipse.jetty.server.Handler.Abstract {

    private final Router router;
    private final List<Middleware.BoundBefore> beforeMiddleware;
    private final List<Middleware.BoundAfter> afterMiddleware;
    private final DatabaseFactory databaseFactory;
    private final String sessionSecret;
    private final Stats stats;

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware) {
        this(router, beforeMiddleware, afterMiddleware, null, null, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory) {
        this(router, beforeMiddleware, afterMiddleware, databaseFactory, null, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory,
                        String sessionSecret) {
        this(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory,
                        String sessionSecret,
                        Stats stats) {
        this.router = router;
        this.beforeMiddleware = beforeMiddleware;
        this.afterMiddleware = afterMiddleware;
        this.databaseFactory = databaseFactory;
        this.sessionSecret = sessionSecret;
        this.stats = stats;
    }

    @Override
    public boolean handle(org.eclipse.jetty.server.Request jettyRequest,
                          Response response,
                          Callback callback) throws Exception {
        var startNanos = System.nanoTime();
        try {
            String method = jettyRequest.getMethod();
            String path = jettyRequest.getHttpURI().getPath();

            // Parse query parameters
            Map<String, String> queryParams = parseQuery(jettyRequest.getHttpURI().getQuery());

            // Extract headers
            Map<String, String> headers = new LinkedHashMap<>();
            for (var field : jettyRequest.getHeaders()) {
                headers.put(field.getName(), field.getValue());
            }

            // Read request body
            String body = Content.Source.asString(jettyRequest);
            if (body == null) body = "";

            // Match route
            RouteMatch match = router.match(method, path);

            // Build Brace Request (path params come from match, or empty if no match)
            Map<String, String> pathParams = match != null ? match.pathParams() : Map.of();
            Request braceRequest = new Request(method, path, pathParams, queryParams, headers, body);

            // Run before middleware
            for (var before : beforeMiddleware) {
                Result earlyResult = before.apply(braceRequest);
                if (earlyResult != null) {
                    writeResult(earlyResult, response, callback);
                    return true;
                }
            }

            // 404 if no route matches
            if (match == null) {
                writeResult(Result.notFound(), response, callback);
                return true;
            }

            // Build the invoker
            Invoker invoker;
            if (match.route().invoker() != null) {
                invoker = match.route().invoker();
            } else {
                Handler handler = (Handler) match.route().handler();
                invoker = Invoker.fromFunction(handler);
            }

            // Build session if needed
            Session session = null;
            if (invoker.needsSession()) {
                if (sessionSecret != null) {
                    String cookieHeader = headers.get("Cookie");
                    String sessionCookie = parseCookieValue(cookieHeader, "brace_session");
                    session = Session.fromCookie(sessionCookie, sessionSecret);
                } else {
                    session = new Session();
                }
            }

            // CSRF validation for mutating requests when sessions are enabled
            if (sessionSecret != null) {
                String contentType = headers.getOrDefault("Content-Type", "");
                boolean isMutating = method.equals("POST") || method.equals("PUT") || method.equals("DELETE");
                boolean isJson = contentType.contains("application/json");
                if (isMutating && !isJson) {
                    // Ensure a session object exists for CSRF check even if handler doesn't use sessions
                    Session csrfSession = session;
                    if (csrfSession == null) {
                        String cookieHeader = headers.get("Cookie");
                        String sessionCookie = parseCookieValue(cookieHeader, "brace_session");
                        csrfSession = Session.fromCookie(sessionCookie, sessionSecret);
                    }
                    String submittedToken = parseFormParam(body, "_csrf");
                    if (submittedToken == null) {
                        submittedToken = headers.get("X-CSRF-Token");
                    }
                    if (!Csrf.validateToken(csrfSession, submittedToken)) {
                        writeResult(Result.error(403, "Forbidden"), response, callback);
                        return true;
                    }
                }
            }

            // Ensure a CSRF token exists in session and expose it to templates
            if (sessionSecret != null) {
                Session csrfSession = session;
                if (csrfSession == null) {
                    String cookieHeader = headers.get("Cookie");
                    String sessionCookie = parseCookieValue(cookieHeader, "brace_session");
                    csrfSession = Session.fromCookie(sessionCookie, sessionSecret);
                    // keep a reference for later cookie write — but since handler doesn't
                    // need the session, we only need the token for the View ThreadLocal
                    Csrf.ensureToken(csrfSession);
                    View.setCsrfField(Csrf.hiddenField(csrfSession));
                } else {
                    Csrf.ensureToken(session);
                    View.setCsrfField(Csrf.hiddenField(session));
                }
            }

            // Invoke with per-request database lifecycle if needed
            Result result;
            try {
                if (invoker.needsDatabase() && databaseFactory != null) {
                    Database db = new Database(databaseFactory.openSession());
                    try {
                        db.beginTransaction();
                        result = invoker.invoke(braceRequest, db, session);
                        db.commitTransaction();
                    } catch (Exception e) {
                        db.rollbackTransaction();
                        throw e;
                    } finally {
                        db.close();
                    }
                } else {
                    result = invoker.invoke(braceRequest, null, session);
                }
            } finally {
                View.clearCsrfField();
            }

            // Write session cookie if modified
            if (session != null && session.isModified() && sessionSecret != null) {
                result.header("Set-Cookie",
                    "brace_session=" + session.toCookie(sessionSecret) + "; Path=/; HttpOnly; SameSite=Lax");
            }

            // Run after middleware
            for (var after : afterMiddleware) {
                result = after.apply(braceRequest, result);
            }

            writeResult(result, response, callback);
            var durationUs = (System.nanoTime() - startNanos) / 1000;
            if (stats != null) {
                stats.recordRequest(method, path, result.status(), durationUs, 0, 0);
                Log.request(method, path, result.status(), durationUs, 0, 0);
            }
            return true;

        } catch (Exception e) {
            var durationUs = (System.nanoTime() - startNanos) / 1000;
            if (stats != null) {
                stats.recordRequest("?", "?", 500, durationUs, 0, 0);
                stats.recordError(e.getClass().getSimpleName(), e.getMessage(),
                    "?", stackTraceToString(e), "", "");
                Log.error("?", "?", e);
            }
            Result errorResult = Result.error(500, "Internal Server Error");
            writeResult(errorResult, response, callback);
            return true;
        }
    }

    private void writeResult(Result result, Response response, Callback callback) {
        response.setStatus(result.status());
        response.getHeaders().put("Content-Type", result.contentType());
        for (var entry : result.headers().entrySet()) {
            response.getHeaders().put(entry.getKey(), entry.getValue());
        }
        byte[] bytes = result.body() != null
                ? result.body().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        response.write(true, ByteBuffer.wrap(bytes), callback);
    }

    private String parseCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isEmpty()) return null;
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.strip();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }

    private String parseFormParam(String body, String paramName) {
        if (body == null || body.isEmpty()) return null;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                if (key.equals(paramName)) {
                    try {
                        return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        return pair.substring(eq + 1);
                    }
                }
            }
        }
        return null;
    }

    private String stackTraceToString(Throwable t) {
        var sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            } else {
                params.put(pair, "");
            }
        }
        return params;
    }
}
