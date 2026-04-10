package io.brace;

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

            // Extract headers
            Map<String, String> headers = new LinkedHashMap<>();
            for (var field : jettyRequest.getHeaders()) {
                headers.put(field.getName(), field.getValue());
            }

            // Read request body — multipart or plain
            String body = "";
            Map<String, List<UploadedFile>> uploadedFiles = Map.of();
            String requestContentType = headers.getOrDefault("Content-Type", "");

            if (requestContentType.contains("multipart/form-data")) {
                var parsed = parseMultipart(jettyRequest, requestContentType);
                body = parsed.formBody();
                uploadedFiles = parsed.files();
            } else {
                body = Content.Source.asString(jettyRequest);
                if (body == null) body = "";
            }

            // Match route
            RouteMatch match = router.match(method, path);

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

            // CSRF validation for routes that require it when sessions are enabled
            if (sessionSecret != null && match.route().csrfRequired()) {
                boolean isMutating = method.equals("POST") || method.equals("PUT") || method.equals("DELETE");
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

            // Write session cookie if modified
            if (session != null && session.isModified() && sessionSecret != null) {
                String cookieValue = session.toCookie(sessionSecret);
                if (sessionOptions != null) {
                    result.header("Set-Cookie", sessionOptions.buildSetCookie(cookieValue));
                } else {
                    // Fallback for backward compatibility
                    result.header("Set-Cookie",
                        "brace_session=" + cookieValue + "; Path=/; HttpOnly; SameSite=Lax");
                }
            }

            // Run after middleware
            for (var after : afterMiddleware) {
                result = after.apply(braceRequest, result);
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
            String routeInfo = errorMethod + " " + errorPath;
            String requestInfo = errorMethod + " " + errorPath + (errorQuery != null ? "?" + errorQuery : "");
            if (stats != null) {
                int qc = db != null ? db.queryCount() : 0;
                long qu = db != null ? db.queryDurationUs() : 0;
                stats.recordRequest(errorMethod, errorPath, 500, durationUs, qc, qu);
                stats.recordError(e.getClass().getSimpleName(), e.getMessage(),
                    routeInfo, stackTraceToString(e), requestInfo, "");
                Log.error(errorMethod, errorPath, e);
            }
            if (errorStore != null) {
                String errorType = e.getClass().getSimpleName();
                String errorMessage = e.getMessage();
                String stackTrace = stackTraceToString(e);
                Thread.startVirtualThread(() -> errorStore.record(errorType, errorMessage, routeInfo, stackTrace, requestInfo));
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
            return Result.bytes(htmxJs, "text/javascript; charset=utf-8");
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
                return Result.bytes(fileBytes, contentType);
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
                params.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } else {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return params;
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
