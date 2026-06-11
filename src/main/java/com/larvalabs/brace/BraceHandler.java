package com.larvalabs.brace;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.io.File;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class BraceHandler extends org.eclipse.jetty.server.Handler.Abstract {

    private final Router router;
    private final List<Middleware.BoundBefore> beforeMiddleware;
    private final List<Middleware.BoundAfter> afterMiddleware;
    private final DatabaseFactory databaseFactory;
    private final String sessionSecret;
    private final SessionOptions sessionOptions;
    private final Stats stats;
    private final ErrorStore errorStore;
    private final List<StaticFileMapping> staticFileMappings;
    private final long maxUploadSize;
    private final Storage storage;
    private final TrustedProxies trustedProxies;
    private final byte[] htmxJs;

    static final long DEFAULT_MAX_UPLOAD_SIZE = 10 * 1024 * 1024; // 10MB

    record StaticFileMapping(String urlPrefix, String directory) {}

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware) {
        this(router, beforeMiddleware, afterMiddleware, null, null, null, null, null, List.of(), DEFAULT_MAX_UPLOAD_SIZE, null, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory) {
        this(router, beforeMiddleware, afterMiddleware, databaseFactory, null, null, null, null, List.of(), DEFAULT_MAX_UPLOAD_SIZE, null, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory,
                        String sessionSecret) {
        this(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret, null, null, null, List.of(), DEFAULT_MAX_UPLOAD_SIZE, null, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory,
                        String sessionSecret,
                        Stats stats) {
        this(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret, null, stats, null, List.of(), DEFAULT_MAX_UPLOAD_SIZE, null, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory,
                        String sessionSecret,
                        Stats stats,
                        ErrorStore errorStore,
                        List<StaticFileMapping> staticFileMappings) {
        this(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret, null, stats, errorStore, staticFileMappings, DEFAULT_MAX_UPLOAD_SIZE, null, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory,
                        String sessionSecret,
                        Stats stats,
                        ErrorStore errorStore,
                        List<StaticFileMapping> staticFileMappings,
                        long maxUploadSize) {
        this(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret, null, stats, errorStore, staticFileMappings, maxUploadSize, null, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory,
                        String sessionSecret,
                        Stats stats,
                        ErrorStore errorStore,
                        List<StaticFileMapping> staticFileMappings,
                        long maxUploadSize,
                        Storage storage) {
        this(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret, null, stats, errorStore, staticFileMappings, maxUploadSize, storage, null);
    }

    public BraceHandler(Router router,
                        List<Middleware.BoundBefore> beforeMiddleware,
                        List<Middleware.BoundAfter> afterMiddleware,
                        DatabaseFactory databaseFactory,
                        String sessionSecret,
                        SessionOptions sessionOptions,
                        Stats stats,
                        ErrorStore errorStore,
                        List<StaticFileMapping> staticFileMappings,
                        long maxUploadSize,
                        Storage storage,
                        TrustedProxies trustedProxies) {
        this.router = router;
        this.beforeMiddleware = beforeMiddleware;
        this.afterMiddleware = afterMiddleware;
        this.databaseFactory = databaseFactory;
        this.sessionSecret = sessionSecret;
        this.sessionOptions = sessionOptions;
        this.stats = stats;
        this.errorStore = errorStore;
        this.staticFileMappings = staticFileMappings;
        this.maxUploadSize = maxUploadSize;
        this.storage = storage;
        this.trustedProxies = trustedProxies;
        byte[] htmxBytes = null;
        try {
            var stream = BraceHandler.class.getResourceAsStream("/brace/htmx.min.js");
            if (stream != null) {
                htmxBytes = stream.readAllBytes();
                stream.close();
            }
        } catch (Exception ignored) {}
        this.htmxJs = htmxBytes;
    }

    @Override
    public boolean handle(org.eclipse.jetty.server.Request jettyRequest,
                          Response response,
                          Callback callback) throws Exception {
        var startNanos = System.nanoTime();
        Database db = null;
        try {
            String method = jettyRequest.getMethod();
            String path = jettyRequest.getHttpURI().getPath();

            // Parse query parameters
            Map<String, String> queryParams = parseQuery(jettyRequest.getHttpURI().getQuery());

            // Extract headers into a case-insensitive map. HTTP header names are
            // case-insensitive (and arrive lowercased over HTTP/2), so lookups like
            // headers.get("Cookie") / "Content-Type" must not depend on the wire casing.
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (var field : jettyRequest.getHeaders()) {
                headers.put(field.getName(), field.getValue());
            }

            // Match route first. Static files, 404s, and before-middleware short-circuits
            // must not pay request-body or multipart-parsing cost — the body is read only
            // once we know a route will consume it. (This also keeps an unmatched POST with a
            // large multipart body from being fully parsed into memory before the 404.)
            RouteMatch match = router.match(method, path);

            // Read request body — multipart or plain — only for matched routes.
            String body = "";
            Map<String, List<UploadedFile>> uploadedFiles = Map.of();
            if (match != null) {
                String requestContentType = headers.getOrDefault("Content-Type", "");
                if (requestContentType.contains("multipart/form-data")) {
                    var parsed = parseMultipart(jettyRequest, requestContentType);
                    body = parsed.formBody();
                    uploadedFiles = parsed.files();
                } else {
                    // Fast-reject on Content-Length before reading any bytes.
                    String contentLengthHeader = headers.get("Content-Length");
                    long declaredLength = -1; // -1: header absent or malformed
                    if (contentLengthHeader != null) {
                        try {
                            declaredLength = Long.parseLong(contentLengthHeader.strip());
                            if (declaredLength > maxUploadSize) {
                                writeResult(Result.error(413, "Payload Too Large"), response, callback);
                                return true;
                            }
                        } catch (NumberFormatException ignored) {
                            // malformed Content-Length — fall through and let the read cap it
                        }
                    }
                    // H2: read only when the request declares a body — Content-Length > 0,
                    // Transfer-Encoding (chunked has no Content-Length), or a malformed
                    // Content-Length (read defensively, the bounded read caps it). Bodyless
                    // requests — every GET — previously allocated a 64KB buffer here.
                    boolean declaresBody = declaredLength > 0
                        || (contentLengthHeader != null && declaredLength == -1)
                        || headers.containsKey("Transfer-Encoding");
                    if (declaresBody) {
                        // Bounded incremental read: cap at maxUploadSize bytes regardless of
                        // Content-Length (which clients can lie about or omit for chunked
                        // bodies). Read maxUploadSize+1 bytes: if we get more than
                        // maxUploadSize the body is too large and we return 413.
                        byte[] bodyBytes = readBoundedBody(jettyRequest, maxUploadSize,
                            bodyBufferSize(declaredLength));
                        if (bodyBytes == null) {
                            writeResult(Result.error(413, "Payload Too Large"), response, callback);
                            return true;
                        }
                        body = new String(bodyBytes, StandardCharsets.UTF_8);
                    }
                }
            }

            // Extract remote address from socket
            String remoteAddr = org.eclipse.jetty.server.Request.getRemoteAddr(jettyRequest);

            // Build Brace Request (path params come from match, or empty if no match)
            Map<String, String> pathParams = match != null ? match.pathParams() : Map.of();
            Request braceRequest = new Request(method, path, pathParams, queryParams, headers, body, uploadedFiles, remoteAddr, trustedProxies);
            if (storage != null) {
                braceRequest.setStorage(storage);
            }

            // Run before middleware
            for (var before : beforeMiddleware) {
                Result earlyResult = before.apply(braceRequest);
                if (earlyResult != null) {
                    writeResult(earlyResult, response, callback);
                    return true;
                }
            }

            // Check static file mappings if no route matched
            if (match == null) {
                Result staticResult = serveStaticFile(path);
                if (staticResult != null) {
                    writeResult(staticResult, response, callback);
                    return true;
                }
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
                    session.consumeFlash();
                } else {
                    session = new Session();
                    session.consumeFlash();
                }
            }

            // Expose flash data to templates
            if (session != null) {
                View.setFlash(session.flashData());
            }

            // CSRF validation for routes that require it when sessions are enabled.
            // M5a: PATCH is mutating — added alongside POST/PUT/DELETE.
            if (sessionSecret != null && match.route().csrfRequired()) {
                boolean isMutating = method.equals("POST") || method.equals("PUT")
                    || method.equals("DELETE") || method.equals("PATCH");
                if (isMutating) {
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
                        writeResult(Result.json(java.util.Map.of("error", "csrf_required"), 403),
                            response, callback);
                        return true;
                    }
                }
            }

            // Ensure a CSRF token exists in session and expose it to templates.
            // M5c: when the handler doesn't take a Session, build a local csrfSession to
            // hold the token.  If ensureToken mints a fresh token (csrfSession.isModified()),
            // we persist it as a cookie below just like a handler-session cookie — otherwise
            // the rendered token is orphaned and every subsequent POST 403s.
            // handlerSession is the session the handler itself was given (may be null); it is
            // written separately via the handler-session cookie block below.
            // csrfOnlySession is non-null only when the handler had no Session param AND a
            // fresh token was minted; it must never shadow a real handler session.
            Session csrfOnlySession = null;
            if (sessionSecret != null) {
                if (session == null) {
                    String cookieHeader = headers.get("Cookie");
                    String sessionCookie = parseCookieValue(cookieHeader, "brace_session");
                    Session localCsrfSession = Session.fromCookie(sessionCookie, sessionSecret);
                    Csrf.ensureToken(localCsrfSession);
                    View.setCsrfField(Csrf.hiddenField(localCsrfSession));
                    // Track so we can write the cookie after the handler runs (M5c).
                    if (localCsrfSession.isModified()) {
                        csrfOnlySession = localCsrfSession;
                    }
                } else {
                    Csrf.ensureToken(session);
                    View.setCsrfField(Csrf.hiddenField(session));
                }
            }

            // Invoke with per-request database lifecycle if needed
            Result result;
            try {
                if (invoker.needsDatabase() && databaseFactory != null) {
                    db = new Database(databaseFactory.openSession());
                    try {
                        if (invoker.needsReadOnlyDatabase()) {
                            result = invoker.invoke(braceRequest, db, session);
                        } else {
                            db.beginTransaction();
                            result = invoker.invoke(braceRequest, db, session);
                            db.commitTransaction();
                        }
                    } catch (Exception e) {
                        if (!invoker.needsReadOnlyDatabase()) {
                            db.rollbackTransaction();
                        }
                        throw e;
                    } finally {
                        db.close();
                    }
                } else {
                    result = invoker.invoke(braceRequest, null, session);
                }
            } finally {
                View.clearCsrfField();
                View.clearFlash();
            }

            // Run after middleware first — after-middleware may return a brand-new Result
            // instance (e.g. a wrapper that rewrites the body). Attaching the session cookie
            // before this step would silently discard it whenever the instance changed (M6).
            for (var after : afterMiddleware) {
                result = after.apply(braceRequest, result);
            }

            // Write session cookie to the surviving Result after after-middleware has run.
            if (session != null && session.isModified() && sessionSecret != null) {
                session.maxAgeSeconds(sessionMaxAgeSeconds());
                String cookieValue = session.toCookie(sessionSecret);
                if (sessionOptions != null) {
                    result.header("Set-Cookie", sessionOptions.buildSetCookie(cookieValue));
                } else {
                    // Fallback for backward compatibility
                    result.header("Set-Cookie",
                        "brace_session=" + cookieValue + "; Path=/; HttpOnly; SameSite=Lax");
                }
            }

            // M5c: write the CSRF-only session cookie when the handler had no Session param
            // and ensureToken minted a fresh token.  The handler-session block above and this
            // block are mutually exclusive: csrfOnlySession is null whenever session != null.
            if (csrfOnlySession != null && sessionSecret != null) {
                csrfOnlySession.maxAgeSeconds(sessionMaxAgeSeconds());
                String cookieValue = csrfOnlySession.toCookie(sessionSecret);
                if (sessionOptions != null) {
                    result.header("Set-Cookie", sessionOptions.buildSetCookie(cookieValue));
                } else {
                    result.header("Set-Cookie",
                        "brace_session=" + cookieValue + "; Path=/; HttpOnly; SameSite=Lax");
                }
            }

            // Add Vary header for htmx requests (caching correctness)
            if ("true".equals(braceRequest.header("HX-Request"))) {
                result.header("Vary", "HX-Request");
            }

            writeResult(result, response, callback);
            var durationUs = (System.nanoTime() - startNanos) / 1000;
            if (stats != null) {
                int qc = db != null ? db.queryCount() : 0;
                long qu = db != null ? db.queryDurationUs() : 0;
                stats.recordRequest(method, path, result.status(), durationUs, qc, qu);
                Log.request(method, path, result.status(), durationUs, qc, qu);
            }
            return true;

        } catch (NotFoundException e) {
            var durationUs = (System.nanoTime() - startNanos) / 1000;
            Result notFoundResult = Result.notFound();
            writeResult(notFoundResult, response, callback);
            if (stats != null) {
                String errorMethod = jettyRequest.getMethod();
                String errorPath = jettyRequest.getHttpURI().getPath();
                // db may be null (no route matched) or closed (query stats still readable)
                int qc = db != null ? db.queryCount() : 0;
                long qu = db != null ? db.queryDurationUs() : 0;
                stats.recordRequest(errorMethod, errorPath, 404, durationUs, qc, qu);
                Log.request(errorMethod, errorPath, 404, durationUs, qc, qu);
            }
            return true;
        } catch (Exception e) {
            var durationUs = (System.nanoTime() - startNanos) / 1000;
            String errorMethod = jettyRequest.getMethod();
            String errorPath = jettyRequest.getHttpURI().getPath();
            String errorQuery = jettyRequest.getHttpURI().getQuery();
            // Redact high-entropy path segments (reset tokens, invite tokens, etc.) so they
            // are not persisted in the error store or surfaced on /ops/errors.
            String redactedPath = Redactor.redactPath(errorPath);
            String routeInfo = errorMethod + " " + redactedPath;
            // Redact sensitive query params (?token=…, ?password=…) before the request detail
            // is stored in the error record and served over /ops/errors.
            String requestInfo = errorMethod + " " + redactedPath
                + (errorQuery != null ? "?" + Redactor.redactQuery(errorQuery) : "");
            int qc = db != null ? db.queryCount() : 0;
            long qu = db != null ? db.queryDurationUs() : 0;
            if (stats != null) {
                stats.recordRequest(errorMethod, errorPath, 500, durationUs, qc, qu);
                stats.recordError(e.getClass().getSimpleName(), e.getMessage(),
                    routeInfo, stackTraceToString(e), requestInfo, "");
                Log.error(errorMethod, errorPath, e);
            }
            if (errorStore != null) {
                String errorType = e.getClass().getSimpleName();
                // Redact the exception message: name-based pass (via Redactor.isSensitive on
                // tokens that look like key=value) is not applicable here, so we run the
                // value-shaped pass to strip embedded bearer tokens, SQL literals, etc.
                String errorMessage = Redactor.redactMessage(e.getMessage());
                String stackTrace = stackTraceToString(e);
                // Instant-of-failure context: how much DB work ran before the throw, and the
                // redacted request headers. Captured synchronously (the Jetty request isn't safe
                // to read off-thread), then persisted on a virtual thread.
                String queriesBefore = "{\"count\":" + qc + ",\"durationMs\":" + (Math.round(qu / 100.0) / 10.0) + "}";
                String requestHeaders = captureRedactedHeaders(jettyRequest);
                Thread.startVirtualThread(() -> errorStore.record(
                    errorType, errorMessage, routeInfo, stackTrace, requestInfo, queriesBefore, requestHeaders));
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
        // Set-Cookie may repeat — append each value so multiple cookies survive to the wire.
        for (var setCookie : result.setCookies()) {
            response.getHeaders().add("Set-Cookie", setCookie);
        }
        byte[] bytes;
        if (result.rawBytes() != null) {
            bytes = result.rawBytes();
        } else if (result.body() != null) {
            bytes = result.body().getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = new byte[0];
        }
        response.write(true, ByteBuffer.wrap(bytes), callback);
    }

    private Result serveStaticFile(String requestPath) {
        if ("/__brace/htmx.min.js".equals(requestPath) && htmxJs != null) {
            return Result.bytes(htmxJs, "text/javascript; charset=utf-8")
                .header("X-Content-Type-Options", "nosniff");
        }
        for (var mapping : staticFileMappings) {
            String prefix = mapping.urlPrefix();
            if (!requestPath.startsWith(prefix)) continue;

            if (requestPath.contains("..")) {
                return Result.notFound();
            }

            String relativePath = requestPath.substring(prefix.length());
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }

            if (relativePath.isEmpty()) {
                return Result.notFound();
            }

            Path baseDir = Path.of(mapping.directory()).toAbsolutePath().normalize();
            Path filePath = baseDir.resolve(relativePath).normalize();

            if (!filePath.startsWith(baseDir)) {
                return Result.notFound();
            }

            File file = filePath.toFile();
            if (!file.exists() || !file.isFile()) {
                return Result.notFound();
            }

            try {
                byte[] fileBytes = Files.readAllBytes(filePath);
                String contentType = contentTypeForPath(filePath.toString());
                return Result.bytes(fileBytes, contentType)
                    .header("X-Content-Type-Options", "nosniff");
            } catch (Exception e) {
                return Result.error(500, "Internal Server Error");
            }
        }
        return null;
    }

    private String contentTypeForPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = path.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css"         -> "text/css; charset=utf-8";
            case "js"          -> "text/javascript; charset=utf-8";
            case "json"        -> "application/json";
            case "png"         -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif"         -> "image/gif";
            case "svg"         -> "image/svg+xml";
            case "ico"         -> "image/x-icon";
            case "woff"        -> "font/woff";
            case "woff2"       -> "font/woff2";
            case "ttf"         -> "font/ttf";
            case "pdf"         -> "application/pdf";
            default            -> "application/octet-stream";
        };
    }

    /**
     * Expiry horizon (seconds) stamped into the encrypted session payload (_exp), derived from
     * the app's SessionOptions.maxAge when positive; otherwise 0, which tells Session to use its
     * 14-day default. This is the server-enforced session lifetime (M2), independent of the
     * client-side Max-Age cookie hint. Expiry is fixed from last write — there is no sliding
     * refresh, so a session re-mints (and its window extends) only when a handler modifies it.
     */
    private long sessionMaxAgeSeconds() {
        if (sessionOptions != null && sessionOptions.maxAge() != null
                && sessionOptions.maxAge().getSeconds() > 0) {
            return sessionOptions.maxAge().getSeconds();
        }
        return 0;
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

    /**
     * Snapshot the request headers as a redacted JSON object for the error record.
     * Sensitive headers (authorization, cookie, …) are replaced with [REDACTED] by the
     * Redactor. Returns null if there are no headers or serialization fails.
     */
    private static String captureRedactedHeaders(org.eclipse.jetty.server.Request request) {
        try {
            var headers = new LinkedHashMap<String, Object>();
            for (var field : request.getHeaders()) {
                headers.put(field.getName(), field.getValue());
            }
            if (headers.isEmpty()) return null;
            return Json.mapper().writeValueAsString(Redactor.redact(headers));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } else {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return params;
    }

    /**
     * Reads the request body up to {@code limit} bytes, returning the raw bytes.
     * Returns {@code null} if the body exceeds the limit (caller should send 413).
     * Reads incrementally via an InputStream so chunked/absent-length bodies are
     * bounded too — we never buffer more than {@code limit + 1} bytes.
     */
    /**
     * Initial buffer size for the body read: the declared Content-Length when known
     * (clamped to 64KB so a lying client can't make us pre-allocate megabytes), 8KB when
     * unknown (chunked or malformed length). Before H2 this was a flat 64KB per request.
     */
    private static int bodyBufferSize(long declaredLength) {
        if (declaredLength > 0) {
            return (int) Math.min(declaredLength, 64 * 1024);
        }
        return 8192;
    }

    private static byte[] readBoundedBody(org.eclipse.jetty.server.Request jettyRequest, long limit,
                                          int initialCapacity) throws java.io.IOException {
        try (var in = Content.Source.asInputStream(jettyRequest)) {
            // Read up to limit+1 bytes: if we get limit+1 the body is too large.
            var out = new java.io.ByteArrayOutputStream(initialCapacity);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > limit) {
                    return null; // exceeded limit
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private record MultipartResult(String formBody, Map<String, List<UploadedFile>> files) {}

    private MultipartResult parseMultipart(org.eclipse.jetty.server.Request jettyRequest, String contentType) throws Exception {
        String boundary = null;
        for (String part : contentType.split(";")) {
            String trimmed = part.strip();
            if (trimmed.startsWith("boundary=")) {
                boundary = trimmed.substring("boundary=".length()).strip();
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                break;
            }
        }
        if (boundary == null) {
            return new MultipartResult("", Map.of());
        }

        var parser = new MultiPartFormData.Parser(boundary);
        parser.setMaxLength(maxUploadSize);
        parser.setMaxMemoryFileSize(-1);

        MultiPartFormData.Parts parts = parser.parse(jettyRequest).join();

        var formParams = new LinkedHashMap<String, String>();
        var files = new LinkedHashMap<String, List<UploadedFile>>();

        try {
            for (var part : parts) {
                String name = part.getName();
                String fileName = part.getFileName();

                if (fileName != null) {
                    byte[] bytes;
                    var source = part.getContentSource();
                    if (source != null) {
                        var buf = Content.Source.asByteBuffer(source);
                        bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                    } else {
                        bytes = part.getContentAsString(StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1);
                    }
                    String partContentType = "application/octet-stream";
                    HttpField ctField = part.getHeaders().getField("Content-Type");
                    if (ctField != null) {
                        partContentType = ctField.getValue();
                    }
                    var uploaded = new UploadedFile(fileName, partContentType, bytes);
                    files.computeIfAbsent(name, k -> new ArrayList<>()).add(uploaded);
                } else {
                    formParams.put(name, part.getContentAsString(StandardCharsets.UTF_8));
                }
            }
        } finally {
            parts.close();
        }

        var formBody = new StringBuilder();
        for (var entry : formParams.entrySet()) {
            if (!formBody.isEmpty()) formBody.append('&');
            formBody.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            formBody.append('=');
            formBody.append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        return new MultipartResult(formBody.toString(), files);
    }
}
