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
    /**
     * Multipart parts above this spill to a temp file instead of the heap. Mutable for the same
     * reason {@code devMode} is a property read: eight telescoping public constructors is already
     * one too many to widen. {@link Brace} sets both before the server starts.
     */
    private long uploadMemoryThreshold = DEFAULT_UPLOAD_MEMORY_THRESHOLD;
    private Path uploadTempDir = DEFAULT_UPLOAD_TEMP_DIR;

    static final long DEFAULT_MAX_UPLOAD_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * Default spill threshold. Chosen well below {@link #DEFAULT_MAX_UPLOAD_SIZE} so the default
     * configuration cannot pin 10 MB of heap per in-flight upload, and well above the size of the
     * uploads most apps actually take (avatars, CSVs, attachments), which stay in memory and never
     * touch the disk.
     */
    static final long DEFAULT_UPLOAD_MEMORY_THRESHOLD = 1024 * 1024; // 1MB

    static final Path DEFAULT_UPLOAD_TEMP_DIR =
        Path.of(System.getProperty("java.io.tmpdir"), "brace-uploads");

    /** Set by {@link Brace} before start; see {@link #uploadMemoryThreshold}. */
    void setUploadSpill(Path tempDir, long memoryThreshold) {
        this.uploadTempDir = tempDir;
        this.uploadMemoryThreshold = memoryThreshold;
        prepareUploadTempDir(tempDir);
    }

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
        // Eagerly, not lazily on first upload: the directory's permissions have to be right before
        // any request can arrive, and a lazy guard would race two concurrent first-uploads into
        // letting Jetty create the directory itself under the ambient umask.
        prepareUploadTempDir(uploadTempDir);
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
        // Hoisted with session/csrfOnlySession so the catch paths (thrown 404, 500) resolve the
        // same Secure attribute the try-path would have. Recomputed inside the try once headers
        // are parsed; the conservative default holds if we fail before that.
        boolean cookieSecure = true;
        // Hoisted for the same reason as session/cookieSecure: the catch paths (thrown 404, 500)
        // need it to run after-middleware over their responses (M1). Null until the route is
        // matched, which is before any response can be written.
        Request braceRequest = null;
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

            // Resolve the session cookie's Secure attribute from this request (H2). Computed here,
            // before the first writeResult can fire, and threaded through the choke point so every
            // exit path agrees. See SessionOptions.resolveSecure for the rule.
            cookieSecure = resolveCookieSecure(
                headers, org.eclipse.jetty.server.Request.getRemoteAddr(jettyRequest));

            // Match route first. Static files and 404s must not pay request-body or
            // multipart-parsing cost. (This also keeps an unmatched POST with a large multipart
            // body from being fully parsed into memory before the 404.)
            RouteMatch match = router.match(method, path);

            // M2: the body is *supplied* here, not read. Buffering it before the before-middleware
            // loops put the cost ahead of the layer that exists to shed it — a rate limiter or auth
            // guard could not reject a request until up to maxUploadSize had already been read into
            // the heap, on virtual threads with no pool bounding in-flight concurrency. The supplier
            // runs on first access to body/form/files: either a middleware that genuinely needs the
            // body (a webhook signature guard) or the forced resolution below, once the guards have
            // had their say.
            java.util.function.Supplier<Request.BodyContent> bodySource =
                match == null ? () -> new Request.BodyContent("", Map.of())
                              : () -> readRequestBody(jettyRequest, headers);

            // Extract remote address from socket
            String remoteAddr = org.eclipse.jetty.server.Request.getRemoteAddr(jettyRequest);

            // Build Brace Request (path params come from match, or empty if no match)
            Map<String, String> pathParams = match != null ? match.pathParams() : Map.of();
            braceRequest = new Request(method, path, pathParams, queryParams, headers, bodySource, remoteAddr, trustedProxies);
            braceRequest.setRawQuery(rawQuery);
            if (storage != null) {
                braceRequest.setStorage(storage);
            }

            // Run before middleware
            for (var before : beforeMiddleware) {
                Result earlyResult = before.apply(braceRequest);
                if (earlyResult != null) {
                    writeResult(braceRequest, earlyResult, response, callback, session, csrfOnlySession, cookieSecure);
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
                        writeResult(braceRequest, earlyResult, response, callback, session, csrfOnlySession, cookieSecure);
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
                    writeResult(braceRequest, staticResult, response, callback, session, csrfOnlySession, cookieSecure);
                    return true;
                }
                Result notFoundResult = noRouteFound(method, path);
                writeResult(braceRequest, notFoundResult, response, callback, session, csrfOnlySession, cookieSecure);
                return true;
            }

            // M2: guards have run and none rejected the request, so the body is now worth reading.
            // Forcing it here (rather than leaving it to the first handler access) keeps the 413 at
            // the same point in the lifecycle it has always occupied — ahead of CSRF extraction and
            // the handler — so nothing downstream sees a half-set-up request. An over-limit body
            // throws to the PayloadTooLargeException catch below, which owns every 413 regardless of
            // who triggered the read.
            braceRequest.resolveBody();

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
                        writeResult(braceRequest, Result.json(java.util.Map.of("error", "csrf_required"), 403),
                            response, callback, session, csrfOnlySession, cookieSecure);
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

            // After-middleware runs inside writeResult now (M1), so it covers every response
            // leaving handle() rather than only this path. It still runs before the session cookie
            // is attached, so a middleware returning a brand-new Result instance cannot silently
            // discard the cookie (M6).
            //
            // One precedence nuance did change: the htmx Vary below used to be written *after* the
            // middleware chain and therefore won; it is now written before, so an after-middleware
            // that sets Vary overwrites it. Vary lives in the single-value header map and cannot be
            // combined either way, so neither order is right for an app that sets its own Vary —
            // such an app should append "HX-Request" itself.

            // Add Vary header for htmx requests (caching correctness)
            if ("true".equals(braceRequest.header("HX-Request"))) {
                result.header("Vary", "HX-Request");
            }

            // Session cookies (handler session + M5c CSRF-only session) are attached to the
            // surviving Result by the write-back choke point.
            writeResult(braceRequest, result, response, callback, session, csrfOnlySession, cookieSecure);
            var durationUs = (System.nanoTime() - startNanos) / 1000;
            if (stats != null) {
                int qc = db != null ? db.queryCount() : 0;
                long qu = db != null ? db.queryDurationUs() : 0;
                stats.recordRequest(method, path, result.status(), durationUs, qc, qu);
                Log.request(method, path, result.status(), durationUs, qc, qu);
            }
            return true;

        } catch (PayloadTooLargeException e) {
            // Owns every 413, wherever the body read was triggered from. A before-middleware that
            // reads the body itself (a webhook signature guard) throws from *inside* the guard
            // loop, which the narrower catch around the forced resolution never saw: the request
            // fell through to the generic handler below and became a 500 that also recorded a
            // framework error — so an unauthenticated client could flood the error store and the
            // regression notifier just by POSTing oversized bodies. Deliberately records no error:
            // an over-limit body is a client mistake, not an application fault.
            writeResult(braceRequest, Result.error(413, "Payload Too Large"),
                response, callback, session, csrfOnlySession, cookieSecure);
            return true;
        } catch (NotFoundException e) {
            var durationUs = (System.nanoTime() - startNanos) / 1000;
            Result notFoundResult = applyAfterQuietly(braceRequest, Result.notFound());
            writeResult(null, notFoundResult, response, callback, session, csrfOnlySession, cookieSecure);
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
            // Guard/middleware session mutations persist on the 500 too — the DB rollback
            // is orthogonal to middleware session touches.
            Result errorResult = applyAfterQuietly(braceRequest, Result.error(500, "Internal Server Error"));
            writeResult(null, errorResult, response, callback, session, csrfOnlySession, cookieSecure);
            return true;
        } finally {
            // Every exit from handle() releases the temp files this request's uploads spilled to —
            // including the ones that never reached a handler (413, CSRF 403, thrown 404/500) and
            // the ones that threw halfway through. A missed path here is not a leak of one file,
            // it is an unbounded disk fill that any client can drive.
            //
            // A streaming response is the one case this must NOT do: its bytes are still being
            // written when handle() returns, so writeResult takes ownership of the cleanup and the
            // call below finds nothing left to release.
            if (braceRequest != null) braceRequest.releaseUploads();
        }
    }

    /**
     * Write-back choke point: every response leaving {@link #handle} is routed through
     * here, so a session mutated by a guard or handler is persisted no matter which path
     * produced the result — early short-circuits, CSRF 403s, static files, thrown 404s,
     * and 500s included. {@code session} is the handler/guard session; {@code csrfOnlySession}
     * is the M5c local session (non-null only when the handler had no Session param) —
     * never both non-null. Attachment is a no-op for unmodified sessions.
     */
    private void writeResult(Request req, Result result, Response response, Callback callback,
                             Session session, Session csrfOnlySession, boolean cookieSecure) {
        // M1: after-middleware used to run only on the handler path, so an app that added
        // SecurityHeaders.defaults() got no X-Frame-Options / Referrer-Policy / CSP on static
        // files, 404s, 500s, CSRF 403s or 413s — verifiably, and on exactly the responses that
        // most need them. Applying it here means every exit from handle() is covered. A null
        // request means "already applied, or nothing to apply it against" (the catch paths,
        // which decorate defensively via applyAfterQuietly first).
        if (req != null) {
            for (var after : afterMiddleware) {
                result = after.apply(req, result);
            }
        }
        attachSessionCookie(result, session, cookieSecure);
        attachSessionCookie(result, csrfOnlySession, cookieSecure);
        if (req != null && result instanceof StreamResult streamResult) {
            result = applyRange(req, streamResult);
            // A streaming response outlives handle(): its bytes are still going out when the
            // end-of-request finally runs. Take the upload cleanup with it, or a handler streaming
            // an upload straight back would have the file deleted mid-response.
            writeResult(result, response, callback, req.takeUploadCleanup());
            return;
        }
        writeResult(result, response, callback);
    }

    /**
     * Narrows a streaming response to the client's requested byte range, or leaves it whole.
     *
     * <p>Only file-backed streams can be ranged — seeking is the whole mechanism, and a one-shot
     * InputStream or a generated writer cannot be seeked. Those keep serving 200s regardless of
     * what the client asks for, which is why {@code Accept-Ranges} is only set by
     * {@link Result#file}.
     */
    private Result applyRange(Request req, StreamResult result) {
        if (result.status() != 200) return result;
        if (!(result.streamBody() instanceof StreamResult.FileBody file)) return result;
        String header = req.header("Range");
        if (header == null) return result;
        // If-Range: only honour the range when the client's copy still matches, otherwise it would
        // splice new bytes into a stale download.
        String ifRange = req.header("If-Range");
        if (ifRange != null) {
            String etag = result.header("ETag");
            if (etag == null || !stripWeakPrefix(ifRange.strip()).equals(stripWeakPrefix(etag))) {
                return result;
            }
        }

        long total = file.length() >= 0 ? file.length() : result.totalLength();
        ByteRange range = ByteRange.parse(header, total);
        if (range == ByteRange.UNSUPPORTED) return result;
        if (range == ByteRange.UNSATISFIABLE) {
            return Result.error(416, "Range Not Satisfiable")
                .header("Content-Range", "bytes */" + total)
                .header("Accept-Ranges", "bytes");
        }

        var ranged = new StreamResult(206, result.contentType(),
            new StreamResult.FileBody(file.path(), file.offset() + range.first(), range.length()),
            range.length());
        // Carry the original's headers (ETag, Cache-Control, nosniff, ...), minus the full-body
        // Content-Length, which the constructor has already replaced with the range's length.
        result.headers().forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Content-Length")) ranged.header(name, value);
        });
        result.setCookies().forEach(cookie -> ranged.header("Set-Cookie", cookie));
        return ranged.header("Content-Range",
            "bytes " + range.first() + "-" + range.last() + "/" + total);
    }

    /**
     * Run the after-middleware chain over an <em>error</em> response, swallowing any failure.
     * The normal paths let an after-middleware exception surface as a 500, but here we are
     * already writing an error response — a throwing middleware must not stop it from reaching
     * the client, and must not recurse back into the catch block that called us.
     */
    private Result applyAfterQuietly(Request req, Result result) {
        if (req == null) return result;
        for (var after : afterMiddleware) {
            try {
                result = after.apply(req, result);
            } catch (Exception e) {
                Log.warn("after-middleware threw while decorating an error response: " + e);
            }
        }
        return result;
    }

    /**
     * Whether the session cookie for this request should carry {@code Secure} when the app has
     * not set it explicitly (H2). Delegates the policy to {@link SessionOptions#resolveSecure};
     * this method's job is only to extract the two request signals.
     *
     * <p>{@code X-Forwarded-Proto} is honoured only when the immediate peer is a configured
     * trusted proxy — the same trust gate {@link Request#ip()} applies, so a client cannot claim
     * https (or, more to the point, cannot claim anything at all) by sending the header itself.
     */
    private boolean resolveCookieSecure(Map<String, String> headers, String remoteAddr) {
        if (sessionOptions == null) return false; // legacy fallback cookie: unchanged behavior
        boolean forwardedHttps = false;
        if (trustedProxies != null && remoteAddr != null && trustedProxies.isTrusted(remoteAddr)) {
            String proto = headers.get("X-Forwarded-Proto");
            if (proto != null) {
                // A multi-hop chain appends, so the original client's scheme is the leftmost entry.
                int comma = proto.indexOf(',');
                if (comma >= 0) proto = proto.substring(0, comma);
                forwardedHttps = "https".equalsIgnoreCase(proto.strip());
            }
        }
        return sessionOptions.resolveSecure(isLoopbackHost(headers.get("Host")), forwardedHttps);
    }

    /**
     * True when the request's {@code Host} names a loopback address — {@code localhost},
     * {@code 127.0.0.0/8}, or {@code ::1}, with or without a port. A missing or unparseable
     * Host is treated as non-loopback (the safe direction: cookie gets {@code Secure}).
     */
    static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) return false;
        String h = Request.stripPort(host.strip());
        if (h.equalsIgnoreCase("localhost")) return true;
        if (h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) return true;
        return h.startsWith("127.") && TrustedProxies.isIpLiteral(h);
    }

    private void writeResult(Result result, Response response, Callback callback) {
        writeResult(result, response, callback, null);
    }

    /**
     * @param onWritten released when the response is done with the request's resources — for a
     *                  streaming response that is when the last byte is on the wire, not when this
     *                  method returns. Null when there is nothing to release.
     */
    private void writeResult(Result result, Response response, Callback callback,
                             java.io.Closeable onWritten) {
        response.setStatus(result.status());
        response.getHeaders().put("Content-Type", result.contentType());
        for (var entry : result.headers().entrySet()) {
            response.getHeaders().put(entry.getKey(), entry.getValue());
        }
        // Set-Cookie may repeat — append each value so multiple cookies survive to the wire.
        for (var setCookie : result.setCookies()) {
            response.getHeaders().add("Set-Cookie", setCookie);
        }
        if (result instanceof StreamResult streamResult) {
            writeStream(streamResult, response, callback, onWritten);
            return;
        }
        byte[] bytes;
        if (result.rawBytes() != null) {
            bytes = result.rawBytes();
        } else if (result.body() != null) {
            bytes = result.body().getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = new byte[0];
        }
        closeQuietly(onWritten);
        response.write(true, ByteBuffer.wrap(bytes), callback);
    }

    /**
     * Pumps a {@link StreamResult} to the client with a bounded buffer.
     *
     * <p>File and stream bodies go through {@code Content.copy}, which is asynchronous and applies
     * backpressure — it reads the next chunk only once the previous one has been flushed, so a slow
     * client throttles the read instead of filling memory. A writer body is generated inline on the
     * request thread (a virtual thread, where blocking on the socket is the right thing to do).
     *
     * <p>Whatever the shape, once the first chunk is written the status line is gone: a failure
     * after that point can only be signalled by failing the callback, which aborts the connection.
     * A truncated transfer is a visible error to the client; a short 200 would not be.
     */
    private void writeStream(StreamResult result, Response response, Callback callback,
                             java.io.Closeable onWritten) {
        Callback wrapped = Callback.from(
            () -> closeQuietly(onWritten),
            t -> {
                closeQuietly(onWritten);
                Log.event("response.stream.failed", Map.of(
                    "status", result.status(),
                    "error", String.valueOf(t)));
            });
        // Callback.from(Runnable, Consumer) builds a callback whose completion does NOT propagate,
        // so chain the real one explicitly: release our resources first, then complete the request.
        Callback completing = new Callback() {
            @Override
            public void succeeded() {
                wrapped.succeeded();
                callback.succeeded();
            }

            @Override
            public void failed(Throwable t) {
                wrapped.failed(t);
                callback.failed(t);
            }
        };

        switch (result.streamBody()) {
            case StreamResult.FileBody file -> {
                Content.Source source;
                try {
                    source = Content.Source.from(file.path(), file.offset(), file.length());
                } catch (Throwable t) {
                    completing.failed(t);
                    return;
                }
                Content.copy(source, response, completing);
            }
            case StreamResult.StreamBody stream ->
                Content.copy(Content.Source.from(stream.stream()), response, completing);
            case StreamResult.WriterBody writer -> {
                // Deliberately NOT try-with-resources. Closing this stream writes the terminal
                // chunk, which completes the response *successfully* — so an automatic close on
                // the exception path would turn a generator that died halfway into a clean 200
                // carrying a silently truncated body. The close happens only when the writer
                // returned normally; a failure aborts instead.
                var out = Content.Sink.asOutputStream(response);
                try {
                    writer.writer().accept(out);
                    out.close();
                } catch (Throwable t) {
                    completing.failed(t);
                    return;
                }
                completing.succeeded();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable t) {
            Log.warn("failed to release request resources after response: " + t);
        }
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

            // H1: the check above is lexical — Path.normalize() collapses ".." in the *string*
            // and does not resolve symlinks, while the reads below follow them. A symlink under
            // the served directory pointing outside it therefore passed containment and served
            // the target's bytes. Re-check against the real (link-resolved) paths so containment
            // holds in the filesystem, not just in the path text. Links that stay inside the
            // served tree keep working. IOException here means missing/broken/unreadable → 404.
            Path realFile;
            try {
                realFile = filePath.toRealPath();
                if (!realFile.startsWith(baseDir.toRealPath())) {
                    return Result.notFound();
                }
            } catch (java.io.IOException e) {
                return Result.notFound();
            }

            BasicFileAttributes attrs;
            try {
                // One stat for existence + regular-file + size + mtime, vs four separate File
                // syscalls (exists/isFile/length/lastModified) on this hot path.
                attrs = Files.readAttributes(realFile, BasicFileAttributes.class);
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
                String contentType = contentTypeForPath(filePath.toString());
                // Streamed, not read whole: this used to be Files.readAllBytes, so serving a large
                // asset cost its full size in heap for every concurrent request. Range support
                // rides along with the streaming result, which is what makes seeking in a served
                // video work rather than re-fetching from byte zero.
                Result result = Result.file(realFile, contentType)
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
    private void attachSessionCookie(Result result, Session session, boolean cookieSecure) {
        if (session == null || !session.isModified() || sessionSecret == null) return;
        session.maxAgeSeconds(sessionMaxAgeSeconds());
        String cookieValue = session.toCookie(sessionSecret);
        if (sessionOptions != null) {
            result.header("Set-Cookie", sessionOptions.buildSetCookie(cookieValue, cookieSecure));
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

    /**
     * Read and parse the request body — multipart or plain — enforcing {@code maxUploadSize}.
     * Invoked lazily through the supplier handed to {@link Request} (M2), so the cost lands only
     * on requests that survive before-middleware, or that need the bytes earlier for a reason.
     *
     * @throws PayloadTooLargeException when the body exceeds the cap (the caller writes the 413)
     */
    private Request.BodyContent readRequestBody(org.eclipse.jetty.server.Request jettyRequest,
                                                Map<String, String> headers) {
        try {
            String requestContentType = headers.getOrDefault("Content-Type", "");
            // Fast-reject on Content-Length before reading any bytes. Hoisted above the multipart
            // branch (it used to sit below it, covering only plain bodies), because an oversized
            // multipart body reached Jetty's own maxLength check instead — and that throws an
            // IllegalStateException, which fell through to the generic 500 handler. So the
            // documented "413 for anything over maxUploadSize" was in fact a 500 for multipart,
            // and every oversized upload recorded a framework error and fed the regression
            // notifier: exactly the error-store flood the PayloadTooLargeException catch was added
            // to prevent, just reached by a different door.
            String contentLengthHeader = headers.get("Content-Length");
            if (contentLengthHeader != null) {
                try {
                    long declaredLength = Long.parseLong(contentLengthHeader.strip());
                    if (declaredLength > maxUploadSize) {
                        throw new PayloadTooLargeException();
                    }
                } catch (NumberFormatException ignored) {
                    // malformed Content-Length — fall through and let the read cap it
                }
            }
            if (requestContentType.contains("multipart/form-data")) {
                var parsed = parseMultipart(jettyRequest, requestContentType);
                return new Request.BodyContent(parsed.formBody(), parsed.files(), parsed.cleanup());
            }
            // Bounded incremental read: cap at maxUploadSize bytes regardless of Content-Length
            // (which clients can lie about or omit for chunked bodies). Read maxUploadSize+1
            // bytes: if we get more than maxUploadSize the body is too large.
            byte[] bodyBytes = readBoundedBody(jettyRequest, maxUploadSize);
            if (bodyBytes == null) {
                throw new PayloadTooLargeException();
            }
            return new Request.BodyContent(new String(bodyBytes, StandardCharsets.UTF_8), Map.of());
        } catch (PayloadTooLargeException e) {
            throw e;
        } catch (Exception e) {
            if (isSizeViolation(e)) throw new PayloadTooLargeException();
            throw new RuntimeException("Failed to read request body", e);
        }
    }

    /**
     * Whether an exception from multipart parsing is Jetty reporting a size cap, which is a 413
     * rather than a 500.
     *
     * <p>The Content-Length fast-reject above catches the ordinary case — browsers always send a
     * length for multipart — so this is the backstop for a chunked body that declares no length and
     * only reveals its size as it arrives. Jetty signals all three of its caps with a plain
     * {@link IllegalStateException} ({@code MultiPartFormData.Parser}), so the message is the only
     * discriminator available; blanket-mapping every IllegalStateException here would swallow
     * genuine framework faults as client errors.
     *
     * <p>Matching on a message is fragile across a Jetty upgrade, but it fails in the safe
     * direction — a changed message reverts to today's 500 rather than mis-classifying something —
     * and {@code UploadSpillTest} pins the behavior so the upgrade fails loudly instead of quietly.
     */
    private static boolean isSizeViolation(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof IllegalStateException && t.getMessage() != null
                    && (t.getMessage().startsWith("max length exceeded")
                        || t.getMessage().startsWith("max file size exceeded")
                        || t.getMessage().startsWith("max memory file size exceeded"))) {
                return true;
            }
        }
        return false;
    }

    private record MultipartResult(String formBody, Map<String, List<UploadedFile>> files,
                                   java.io.Closeable cleanup) {}

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
            return new MultipartResult("", Map.of(), null);
        }

        var parser = new MultiPartFormData.Parser(boundary);
        parser.setMaxLength(maxUploadSize);
        // Parts above the threshold spill to a temp file instead of living in the heap for the
        // duration of the request. This used to be setMaxMemoryFileSize(-1) — "unlimited memory
        // file size" — which meant a maxUploadSize-sized heap allocation per in-flight upload, on
        // virtual threads with nothing bounding in-flight concurrency.
        parser.setFilesDirectory(uploadTempDir);
        parser.setMaxMemoryFileSize(uploadMemoryThreshold);
        // Without this, Jetty treats the threshold as a hard *limit* for parts that have no
        // filename — an ordinary form field over the threshold fails the request with "max memory
        // file size exceeded" rather than spilling. Large text fields are legitimate, so let them
        // spill too. Brace still classifies file-vs-field by getFileName(), not by storage.
        parser.setUseFilesForPartsWithoutFileName(true);

        MultiPartFormData.Parts parts = parser.parse(jettyRequest).join();

        var formParams = new LinkedHashMap<String, String>();
        var files = new LinkedHashMap<String, List<UploadedFile>>();

        // NOTE: parts are deliberately NOT closed here. Closing a part deletes the temp file
        // behind it, and the UploadedFiles built below are handed to the handler — closing at the
        // end of parsing would hand out uploads whose bytes had already been deleted. The Parts
        // handle is returned as the request's cleanup token and closed by handle()'s finally.
        boolean ok = false;
        try {
            for (var part : parts) {
                String name = part.getName();
                String fileName = part.getFileName();

                if (fileName != null) {
                    String partContentType = "application/octet-stream";
                    HttpField ctField = part.getHeaders().getField("Content-Type");
                    if (ctField != null) {
                        partContentType = ctField.getValue();
                    }
                    long size = part.getLength();
                    if (size < 0) {
                        // Length is only unknown for sources that can't report one; fall back to
                        // draining once so size() stays truthful rather than negative.
                        try (var in = Content.Source.asInputStream(part.newContentSource())) {
                            size = in.transferTo(java.io.OutputStream.nullOutputStream());
                        }
                    }
                    var uploaded = new UploadedFile(part, fileName, partContentType, size);
                    files.computeIfAbsent(name, k -> new ArrayList<>()).add(uploaded);
                } else {
                    formParams.put(name, part.getContentAsString(StandardCharsets.UTF_8));
                }
            }
            ok = true;
        } finally {
            // Only on the failure path: nothing downstream will ever get the cleanup handle, so
            // release here rather than leaking every temp file this request spilled.
            if (!ok) parts.close();
        }

        var formBody = new StringBuilder();
        for (var entry : formParams.entrySet()) {
            if (!formBody.isEmpty()) formBody.append('&');
            formBody.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            formBody.append('=');
            formBody.append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        return new MultipartResult(formBody.toString(), files, parts);
    }

    /**
     * Creates the upload spill directory with owner-only permissions, and sweeps orphans left by a
     * previous process.
     *
     * <p>Jetty creates the individual temp files with {@code Files.createTempFile}, which is
     * owner-only on POSIX, but it creates the <em>directory</em> with a plain
     * {@code createDirectories} under the ambient umask. Uploaded content is untrusted and may be
     * sensitive, so the directory is created here instead, with 700.
     *
     * <p>The sweep exists because a hard kill (SIGKILL, OOM, container eviction) skips every
     * cleanup path there is. Files older than {@link #UPLOAD_ORPHAN_AGE_MS} are from a dead
     * process by definition — a live request cannot outlive {@code maxUploadSize} bytes by hours.
     */
    static void prepareUploadTempDir(Path dir) {
        try {
            if (!Files.exists(dir)) {
                try {
                    var ownerOnly = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
                    Files.createDirectories(dir,
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerOnly));
                } catch (UnsupportedOperationException e) {
                    Files.createDirectories(dir); // non-POSIX filesystem
                }
            }
            sweepOrphanedUploads(dir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to prepare upload temp directory: " + dir, e);
        }
    }

    /** Age past which a spilled upload can only belong to a process that is no longer running. */
    static final long UPLOAD_ORPHAN_AGE_MS = 6 * 60 * 60 * 1000L;

    static void sweepOrphanedUploads(Path dir) {
        long cutoff = System.currentTimeMillis() - UPLOAD_ORPHAN_AGE_MS;
        int swept = 0;
        // Depth 1 and no symlink following: this walks a directory of untrusted-content temp files,
        // and a symlink planted in it must not turn the sweep into an arbitrary-delete primitive.
        try (var entries = Files.newDirectoryStream(dir)) {
            for (var entry : entries) {
                try {
                    var attrs = Files.readAttributes(entry, BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.isRegularFile()) continue;
                    if (attrs.lastModifiedTime().toMillis() > cutoff) continue;
                    Files.deleteIfExists(entry);
                    swept++;
                } catch (Exception ignored) {
                    // Racing with another instance sharing the directory, or a permissions issue.
                }
            }
        } catch (Exception e) {
            Log.warn("upload temp sweep failed for " + dir + ": " + e);
            return;
        }
        if (swept > 0) {
            Log.event("brace.uploads.sweep", Map.of("directory", dir.toString(), "deleted", swept));
        }
    }
}
