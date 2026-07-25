package com.larvalabs.brace;

import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.DateParser;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class BraceHandler extends org.eclipse.jetty.server.Handler.Abstract {

    private final Router router;
    private final List<Middleware.BoundBefore> beforeMiddleware;
    // Set post-construction by Brace.start() (avoids widening eight telescoping ctors);
    // immutable empty by default so tests constructing BraceHandler directly are unaffected.
    private List<Middleware.BoundBeforeSession> beforeSessionMiddleware = List.of();
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
    private final String htmxEtag; // content-derived validator for the bundled htmx asset (L21)
    // L3: dev mode is detected once at construction from the brace.mode system property —
    // the same signal Brace's startup banner uses. The mode isn't otherwise threaded into
    // the handler, and a property read here avoids widening eight telescoping constructors.
    private final boolean devMode;

    static final long DEFAULT_MAX_UPLOAD_SIZE = 10 * 1024 * 1024; // 10MB

    record StaticFileMapping(String urlPrefix, String directory) {}

    void setBeforeSessionMiddleware(List<Middleware.BoundBeforeSession> middleware) {
        this.beforeSessionMiddleware = middleware;
    }

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
        // Content-derived ETag computed once: changes if the bundled bytes change across a brace
        // upgrade (length + array hash), so revalidation can't return a false 304 after an upgrade.
        this.htmxEtag = htmxBytes == null ? null
            : "\"htmx-" + Long.toHexString(htmxBytes.length) + "-"
                + Integer.toHexString(java.util.Arrays.hashCode(htmxBytes)) + "\"";
        this.devMode = "dev".equals(System.getProperty("brace.mode"));
    }

    @Override
    public boolean handle(org.eclipse.jetty.server.Request jettyRequest,
                          Response response,
                          Callback callback) throws Exception {
        var startNanos = System.nanoTime();
        Database db = null;
        // Hoisted above the try so the catch paths (thrown 404, 500) can persist session
        // mutations too — see the write-back choke point on writeResult below.
        Session session = null;
        Session csrfOnlySession = null;
        // Hoisted for the same reason: the catch paths record stats against the matched
        // ROUTE PATTERN, not the concrete URL (H1), and can only do that if they can see
        // the match. It stays null when the throw happened before routing (e.g. a malformed
        // percent-escape in the query string).
        RouteMatch match = null;
        try {
            String method = jettyRequest.getMethod();
            String path = jettyRequest.getHttpURI().getPath();

            // Parse query parameters (raw string kept for Request.queryParams(name) multi-value access)
            String rawQuery = jettyRequest.getHttpURI().getQuery();
            Map<String, String> queryParams = parseQuery(rawQuery);

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
            match = router.match(method, path);

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
                    if (contentLengthHeader != null) {
                        try {
                            long declaredLength = Long.parseLong(contentLengthHeader.strip());
                            if (declaredLength > maxUploadSize) {
                                writeResult(Result.error(413, "Payload Too Large"), response, callback, session, csrfOnlySession);
                                return true;
                            }
                        } catch (NumberFormatException ignored) {
                            // malformed Content-Length — fall through and let the read cap it
                        }
                    }
                    // Bounded incremental read: cap at maxUploadSize bytes regardless of
                    // Content-Length (which clients can lie about or omit for chunked bodies).
                    // Read maxUploadSize+1 bytes: if we get more than maxUploadSize the body
                    // is too large and we return 413.
                    byte[] bodyBytes = readBoundedBody(jettyRequest, maxUploadSize);
                    if (bodyBytes == null) {
                        writeResult(Result.error(413, "Payload Too Large"), response, callback, session, csrfOnlySession);
                        return true;
                    }
                    body = new String(bodyBytes, StandardCharsets.UTF_8);
                }
            }

            // Extract remote address from socket
            String remoteAddr = org.eclipse.jetty.server.Request.getRemoteAddr(jettyRequest);

            // Build Brace Request (path params come from match, or empty if no match)
            Map<String, String> pathParams = match != null ? match.pathParams() : Map.of();
            Request braceRequest = new Request(method, path, pathParams, queryParams, headers, body, uploadedFiles, remoteAddr, trustedProxies);
            braceRequest.setRawQuery(rawQuery);
            if (storage != null) {
                braceRequest.setStorage(storage);
            }

            // Run before middleware
            for (var before : beforeMiddleware) {
                Result earlyResult = before.apply(braceRequest);
                if (earlyResult != null) {
                    writeResult(earlyResult, response, callback, session, csrfOnlySession);
                    return true;
                }
            }

            // Run session-aware before middleware (after all plain before middleware).
            // The Session built here is the SAME instance later handed to the handler —
            // that identity is the contract: a middleware mutation (login touch, flash)
            // persists through the normal cookie write-back, and the handler never sees
            // a different session than its guard did.
            if (!beforeSessionMiddleware.isEmpty()) {
                for (var before : beforeSessionMiddleware) {
                    if (!before.matches(path)) continue;
                    if (session == null) {
                        session = buildSession(headers);
                    }
                    Result earlyResult = before.handler().handle(braceRequest, session);
                    if (earlyResult != null) {
                        // A short-circuiting guard may have mutated the session (e.g. a
                        // flash message before redirecting to login) — persisted by the
                        // write-back choke point.
                        writeResult(earlyResult, response, callback, session, csrfOnlySession);
                        return true;
                    }
                }
            }

            // Check static file mappings if no route matched. A pass-through
            // session-aware middleware may have mutated the session above (login touch,
            // counters) — the write-back contract holds on these paths too via the
            // choke point, instead of silently dropping the mutation.
            if (match == null) {
                Result staticResult = serveStaticFile(braceRequest);
                if (staticResult != null) {
                    writeResult(staticResult, response, callback, session, csrfOnlySession);
                    return true;
                }
                Result notFoundResult = noRouteFound(method, path);
                writeResult(notFoundResult, response, callback, session, csrfOnlySession);
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

            // Build session if needed — reusing the instance a session-aware before
            // middleware already built (identity invariant; see above).
            if (session == null && invoker.needsSession()) {
                session = buildSession(headers);
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
                    // One body parser: _csrf extraction sees the same decoded, last-wins
                    // view of the form body as FormBinder (it used to be a third divergent
                    // pair parser — raw-key compare, first-match-wins, swallowed decode
                    // failures). Only form bodies are parsed: for JSON and other content
                    // types the token rides the X-CSRF-Token header, and parsing an
                    // arbitrary body as pairs risks URL-decode failures.
                    String submittedToken = braceRequest.isFormPost() || braceRequest.isMultipart()
                        ? braceRequest.formParam("_csrf") : null;
                    if (submittedToken == null) {
                        submittedToken = headers.get("X-CSRF-Token");
                    }
                    if (!Csrf.validateToken(csrfSession, submittedToken)) {
                        // Choke point persists guard mutations on the 403 too.
                        writeResult(Result.json(java.util.Map.of("error", "csrf_required"), 403),
                            response, callback, session, csrfOnlySession);
                        return true;
                    }
                }
            }

            // Ensure a CSRF token exists in session and expose it to templates.
            // M5c: when the handler doesn't take a Session, build a local csrfOnlySession to
            // hold the token. The choke point persists it whenever it ends up modified —
            // a freshly minted token (otherwise the rendered token is orphaned and every
            // subsequent POST 403s) or a render-time flash consumption. It must never
            // shadow a real handler session: it is built only when session == null.
            if (sessionSecret != null) {
                if (session == null) {
                    String cookieHeader = headers.get("Cookie");
                    String sessionCookie = parseCookieValue(cookieHeader, "brace_session");
                    csrfOnlySession = Session.fromCookie(sessionCookie, sessionSecret);
                }
                // Lazy token mint (H5): ensureToken used to run eagerly here on every matched
                // request, minting a token — and forcing a Set-Cookie below — even for responses
                // that never render a form (JSON, redirects). The supplier mints and builds the
                // hidden field only when something consumes it: View.of() putting csrfField into a
                // template, or a handler calling View.getCsrfField(). ensureToken is idempotent, and
                // the consumption marks the session modified so the write-back choke point persists
                // it (the M5c invariant — otherwise the rendered token is orphaned and POSTs 403).
                final Session tokenSession = session != null ? session : csrfOnlySession;
                View.setCsrfField(() -> {
                    Csrf.ensureToken(tokenSession);
                    return Csrf.hiddenField(tokenSession);
                });
            }

            // Flash is consumed lazily at View render time, whatever the handler's
            // signature — a redirect-after-POST landing on a DbHandler or plain-Handler
            // page renders flash too. The source consumes only cookie-borne entries
            // (an in-flight guard flash stays pending for the next request) and marks
            // the session modified, which the write-back choke point picks up.
            Session flashSession = session != null ? session : csrfOnlySession;
            if (flashSession != null) {
                View.setFlashSource(() -> {
                    flashSession.consumeFlash();
                    return flashSession.flashData();
                });
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

            // Add Vary header for htmx requests (caching correctness)
            if ("true".equals(braceRequest.header("HX-Request"))) {
                result.header("Vary", "HX-Request");
            }

            // Session cookies (handler session + M5c CSRF-only session) are attached to the
            // surviving Result by the write-back choke point.
            writeResult(result, response, callback, session, csrfOnlySession);
            recordAndLog(match, method, path, result.status(), startNanos, db);
            return true;

        } catch (NotFoundException e) {
            Result notFoundResult = Result.notFound();
            writeResult(notFoundResult, response, callback, session, csrfOnlySession);
            // db may be null (no route matched) or closed (query stats still readable)
            recordAndLog(match, jettyRequest.getMethod(), jettyRequest.getHttpURI().getPath(),
                404, startNanos, db);
            return true;
        } catch (Exception e) {
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
                stats.recordRequestPattern(errorMethod, routeKey(match), 500,
                    (System.nanoTime() - startNanos) / 1000, qc, qu);
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
            // Guard/middleware session mutations persist on the 500 too — the DB rollback
            // is orthogonal to middleware session touches.
            Result errorResult = Result.error(500, "Internal Server Error");
            writeResult(errorResult, response, callback, session, csrfOnlySession);
            return true;
        }
    }

    /**
     * Stats key for a request: the matched route's PATTERN, or {@link #UNMATCHED_ROUTE_KEY} when
     * nothing matched (H1).
     *
     * <p>The concrete URL must never become a stats key. {@code Stats.routes} is a cumulative map
     * that is never reset, so keying it by path leaks one entry — plus two {@code LongAdder}s — per
     * distinct URL ever requested, for the life of the process. With ids in the path that is
     * unbounded in the app's own data; on the unmatched path it is unbounded in whatever an
     * attacker types, since every {@code /<random>} 404 would mint a permanent key. Patterns are
     * code-site literals, so the map stays bounded by the route table.
     *
     * <p>This is also why patterns skip the {@code Redactor.redactPath} pass that
     * {@code Stats.recordRequest} applies to raw paths: a pattern carries no user data, and
     * redacting it would rewrite {@code /reset/{token}} into a key that no longer matches what the
     * route table shows.
     */
    private static String routeKey(RouteMatch match) {
        return match != null ? match.route().pattern() : UNMATCHED_ROUTE_KEY;
    }

    /** Stats bucket for requests that matched no route — see {@link #routeKey}. */
    static final String UNMATCHED_ROUTE_KEY = "(unmatched)";

    /**
     * Record one finished request into {@link Stats} and the structured log.
     *
     * <p>Stats are keyed by route pattern ({@link #routeKey}); the log deliberately keeps the
     * CONCRETE (redacted) path. They serve different purposes: the routes table is a bounded
     * per-route latency aggregate, while the log is an unbounded stream where the actual URL is
     * the entire diagnostic value — knowing that {@code GET /users/{id}} 404'd is useless without
     * knowing which id.
     */
    private void recordAndLog(RouteMatch match, String method, String path, int status,
                              long startNanos, Database db) {
        if (stats == null) return;
        long durationUs = (System.nanoTime() - startNanos) / 1000;
        int qc = db != null ? db.queryCount() : 0;
        long qu = db != null ? db.queryDurationUs() : 0;
        stats.recordRequestPattern(method, routeKey(match), status, durationUs, qc, qu);
        Log.request(method, path, status, durationUs, qc, qu);
    }

    /**
     * Write-back choke point: every response leaving {@link #handle} is routed through
     * here, so a session mutated by a guard or handler is persisted no matter which path
     * produced the result — early short-circuits, CSRF 403s, static files, thrown 404s,
     * and 500s included. {@code session} is the handler/guard session; {@code csrfOnlySession}
     * is the M5c local session (non-null only when the handler had no Session param) —
     * never both non-null. Attachment is a no-op for unmodified sessions.
     */
    private void writeResult(Result result, Response response, Callback callback,
                             Session session, Session csrfOnlySession) {
        attachSessionCookie(result, session);
        attachSessionCookie(result, csrfOnlySession);
        writeResult(result, response, callback);
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

    /**
     * 404 for the no-route-matched path. In dev mode the body lists up to 5 same-method
     * registered route patterns to catch fat-fingered paths —
     * {@code Not Found: GET /user/42 — registered: GET /users/{id}, GET /users} —
     * preferring patterns that share a path prefix with the request (longest shared
     * character prefix first, registration order on ties), falling back to any
     * same-method patterns when none share more than the leading "/". Production
     * behavior is unchanged (plain "Not Found"; registered routes are not disclosed).
     * Deliberately NOT applied to the {@link NotFoundException} catch path: there a
     * handler on an existing route chose to 404, so route suggestions are noise.
     */
    private Result noRouteFound(String method, String path) {
        if (!devMode) return Result.notFound();
        List<String> sameMethod = new ArrayList<>();
        for (var route : router.routes()) {
            if (route.method().equals(method) && !sameMethod.contains(route.pattern())) {
                sameMethod.add(route.pattern());
            }
        }
        if (sameMethod.isEmpty()) return Result.notFound();
        var candidates = new ArrayList<>(sameMethod);
        candidates.sort(java.util.Comparator.comparingInt(p -> -commonPrefixLength(path, p)));
        List<String> picks = new ArrayList<>();
        for (var pattern : candidates) {
            if (commonPrefixLength(path, pattern) <= 1) break; // sorted: rest share at most "/"
            picks.add(pattern);
            if (picks.size() == 5) break;
        }
        if (picks.isEmpty()) {
            picks = sameMethod.subList(0, Math.min(5, sameMethod.size()));
        }
        var body = new StringBuilder("Not Found: ").append(method).append(' ').append(path)
            .append(" — registered: ");
        for (int i = 0; i < picks.size(); i++) {
            if (i > 0) body.append(", ");
            body.append(method).append(' ').append(picks.get(i));
        }
        return Result.error(404, body.toString());
    }

    /** Length of the common leading character prefix of {@code a} and {@code b}. */
    private static int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private Result serveStaticFile(Request request) {
        String requestPath = request.path();
        if ("/__brace/htmx.min.js".equals(requestPath) && htmxJs != null) {
            // Bundled, version-pinned asset served from memory. NOT immutable — a brace upgrade can
            // change the bytes at this fixed URL — so use an ETag + revalidate-always, letting
            // browsers skip the ~50KB re-download on each page via a conditional GET (L21).
            String cacheControl = "public, max-age=0, must-revalidate";
            if (isNotModified(request, htmxEtag, 0)) {
                return Result.notModified()
                    .header("ETag", htmxEtag)
                    .header("Cache-Control", cacheControl);
            }
            return Result.bytes(htmxJs, "text/javascript; charset=utf-8")
                .header("X-Content-Type-Options", "nosniff")
                .header("ETag", htmxEtag)
                .header("Cache-Control", cacheControl);
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

            BasicFileAttributes attrs;
            try {
                // One stat for existence + regular-file + size + mtime, vs four separate File
                // syscalls (exists/isFile/length/lastModified) on this hot path.
                attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            } catch (java.io.IOException e) {
                return Result.notFound(); // missing file (NoSuchFileException) or unreadable
            }
            if (!attrs.isRegularFile()) {
                return Result.notFound();
            }

            // L21: emit validators + Cache-Control so browsers/CDNs can cache and revalidate.
            // Only a "?v=" whose value matches the file's CURRENT content fingerprint (the one
            // Assets.url would mint) earns the immutable year — a stale or hand-rolled "?v=" falls
            // back to revalidate-always, so wrong/old bytes are never pinned content-addressed.
            // Otherwise the client holding a current copy revalidates via a cheap conditional GET.
            long mtime = attrs.lastModifiedTime().toMillis();
            long length = attrs.size();
            String etag = "\"" + Long.toHexString(length) + "-" + Long.toHexString(mtime) + "\"";
            String version = request.queryParam("v");
            boolean immutable = version != null && version.equals(Assets.currentVersion(requestPath));
            String cacheControl = immutable
                ? "public, max-age=31536000, immutable"
                : "public, max-age=0, must-revalidate";

            if (isNotModified(request, etag, mtime)) {
                return Result.notModified()
                    .header("ETag", etag)
                    .header("Cache-Control", cacheControl);
            }

            try {
                byte[] fileBytes = Files.readAllBytes(filePath);
                String contentType = contentTypeForPath(filePath.toString());
                Result result = Result.bytes(fileBytes, contentType)
                    .header("X-Content-Type-Options", "nosniff")
                    .header("ETag", etag)
                    .header("Cache-Control", cacheControl);
                if (mtime > 0) {
                    result.header("Last-Modified", DateGenerator.formatDate(mtime));
                }
                return result;
            } catch (Exception e) {
                return Result.error(500, "Internal Server Error");
            }
        }
        return null;
    }

    /** True when the client's conditional-GET headers show it already holds the current resource. */
    private static boolean isNotModified(Request request, String etag, long mtime) {
        String ifNoneMatch = request.header("If-None-Match");
        if (ifNoneMatch != null) {
            // ETag wins over If-Modified-Since per RFC 9110 when both are present.
            if (ifNoneMatch.trim().equals("*")) return true;
            String want = stripWeakPrefix(etag);
            for (String candidate : ifNoneMatch.split(",")) {
                if (stripWeakPrefix(candidate.trim()).equals(want)) return true;
            }
            return false;
        }
        String ifModifiedSince = request.header("If-Modified-Since");
        if (ifModifiedSince != null && mtime > 0) {
            long since = DateParser.parseDate(ifModifiedSince);
            // HTTP dates carry second resolution — compare truncated to seconds.
            return since >= 0 && mtime / 1000 <= since / 1000;
        }
        return false;
    }

    private static String stripWeakPrefix(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
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

    /**
     * Build the request's Session from the cookie (or a fresh one when sessions are off).
     * Deliberately does NOT consume flash: a session built for a before-middleware guard
     * may belong to a request whose handler can never render flash (a plain-Handler JSON
     * or polling route) or to a short-circuiting redirect — consuming here would destroy
     * a pending flash message before the page that should show it ever renders. Flash is
     * consumed lazily at View render time (or by an explicit {@code session.flash(key)} read).
     */
    private Session buildSession(Map<String, String> headers) {
        if (sessionSecret != null) {
            String cookieHeader = headers.get("Cookie");
            String sessionCookie = parseCookieValue(cookieHeader, "brace_session");
            return Session.fromCookie(sessionCookie, sessionSecret);
        }
        return new Session();
    }

    /** Attach the Set-Cookie for a modified session to a Result; no-op otherwise. */
    private void attachSessionCookie(Result result, Session session) {
        if (session == null || !session.isModified() || sessionSecret == null) return;
        session.maxAgeSeconds(sessionMaxAgeSeconds());
        String cookieValue = session.toCookie(sessionSecret);
        if (sessionOptions != null) {
            result.header("Set-Cookie", sessionOptions.buildSetCookie(cookieValue));
        } else {
            // Fallback for backward compatibility
            result.header("Set-Cookie",
                "brace_session=" + cookieValue + "; Path=/; HttpOnly; SameSite=Lax");
        }
        // A response carrying a session cookie is per-user by definition. Without this,
        // a response with no caching headers (e.g. a static file under a lastSeen-touch
        // middleware) is heuristically cacheable, and a force-cache proxy would replay
        // user A's Set-Cookie to everyone. An explicit Cache-Control wins.
        if (result.header("Cache-Control") == null) {
            result.header("Cache-Control", "private");
        }
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
        // Single pair parser for query strings and form bodies — see Request.scanPairs.
        return Request.parseSingleValues(query, true);
    }

    /**
     * Reads the request body up to {@code limit} bytes, returning the raw bytes.
     * Returns {@code null} if the body exceeds the limit (caller should send 413).
     * Reads incrementally via an InputStream so chunked/absent-length bodies are
     * bounded too — we never buffer more than {@code limit + 1} bytes.
     */
    private static byte[] readBoundedBody(org.eclipse.jetty.server.Request jettyRequest, long limit) throws java.io.IOException {
        try (var in = Content.Source.asInputStream(jettyRequest)) {
            // Read up to limit+1 bytes: if we get limit+1 the body is too large.
            long cap = limit + 1;
            var out = new java.io.ByteArrayOutputStream((int) Math.min(cap, 64 * 1024));
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
