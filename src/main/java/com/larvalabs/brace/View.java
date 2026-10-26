package com.larvalabs.brace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class View extends Result {

    private static TemplateEngine engine;
    // Supplier, not a materialized value (H5): the CSRF token is minted — and the
    // session cookie consequently written — only when a render (or a handler via
    // getCsrfField()) actually consumes it, not on every matched request. It yields the
    // session holding the (now ensured) token; the field and the meta-tag token are both
    // derived from it.
    private static final ThreadLocal<Supplier<Session>> currentCsrfSession = new ThreadLocal<>();
    // Flash is consumed lazily via a supplier (R1): the source (set per-request by BraceHandler)
    // consumes the session's cookie-borne flash when — and only when — a View actually renders, so
    // flash survives requests that render nothing (redirects, JSON, polling).
    private static final ThreadLocal<Supplier<Map<String, String>>> currentFlash = new ThreadLocal<>();
    // M1: the Views created while the page cache runs a handler. The cache checks the final response
    // against all of them, since a handler can return a View's output inside another Result.
    private static final ThreadLocal<List<View>> created = new ThreadLocal<>();

    static void setCsrfSession(Supplier<Session> sessionSupplier) { currentCsrfSession.set(sessionSupplier); }
    static void clearCsrfSession() { currentCsrfSession.remove(); }

    /**
     * Resolves the CSRF hidden field for the current request, minting the token on first
     * use. Null when CSRF is not active for the route ({@code .csrf(false)}, or sessions
     * not configured).
     */
    static String getCsrfField() {
        Supplier<Session> supplier = currentCsrfSession.get();
        return supplier != null ? Csrf.hiddenField(supplier.get()) : null;
    }

    /**
     * The {@code csrfField} template param: the hidden input as {@link gg.jte.Content}, so
     * {@code ${csrfField}} writes it as markup (M3). As a plain String, JTE's HTML escaping turned
     * the documented {@code ${csrfField}} into visible text and a form that always 403'd, and the
     * only workaround was {@code $unsafe}. The field is framework-built and its token value is
     * already escaped by {@link Csrf#hiddenField}.
     */
    record CsrfField(String html) implements gg.jte.Content {
        @Override
        public void writeTo(gg.jte.TemplateOutput output) {
            output.writeContent(html);
        }

        @Override
        public String toString() {
            return html;
        }
    }
    static void setFlashSource(Supplier<Map<String, String>> source) { currentFlash.set(source); }
    static void clearFlash() { currentFlash.remove(); }

    private final String template;
    private final Map<String, Object> params;
    private boolean rendered;
    // M1: the values this render took from the visitor's session — the masked CSRF tokens it was
    // handed, and whether a non-empty flash came with them. The page cache must not store (and replay
    // to everyone) a page carrying one user's token or flash; sessionDerived() decides from these.
    private final List<String> csrfValues;
    private final boolean carriesFlash;

    private View(String template, Map<String, Object> params, List<String> csrfValues, boolean carriesFlash) {
        super(200, "text/html; charset=utf-8", (String) null);
        this.template = template;
        this.params = params;
        this.csrfValues = csrfValues;
        this.carriesFlash = carriesFlash;
    }

    static void setEngine(TemplateEngine engine) {
        View.engine = engine;
    }

    public static View of(String template, Object... keyValues) {
        var params = toParams(template, keyValues);
        // Resolve the request-scoped CSRF field and flash NOW, while their ThreadLocals are still set
        // (the handler is mid-flight). getCsrfField() mints the token, so the session is marked modified
        // here — before the cookie-write decision — exactly as it was when rendering happened eagerly.
        // The template render itself is deferred to materialize() (M12); the resolved values ride along
        // in params, so it no longer depends on the ThreadLocals, which are cleared before it runs.
        List<String> csrfValues = List.of();
        Supplier<Session> csrfSession = currentCsrfSession.get();
        if (csrfSession != null) {
            Session session = csrfSession.get();
            String fieldToken = Csrf.maskedToken(session);
            String metaToken = Csrf.maskedToken(session);
            params.put("csrfField", new CsrfField(Csrf.hiddenFieldFor(fieldToken)));
            // The bare masked token, for a <meta> tag that fetch/htmx read into X-CSRF-Token, and
            // that tag ready-made (M2): ${csrfMeta} in the layout plus /__brace/csrf-htmx.js makes
            // every non-GET htmx request carry the token.
            params.put("csrfToken", metaToken);
            params.put("csrfMeta", new CsrfField(Csrf.metaTagFor(metaToken)));
            csrfValues = List.of(fieldToken, metaToken);
        }
        boolean carriesFlash = false;
        // R1: consuming the flash source here (View.of == a render is happening) clears the
        // session's flash exactly when a View renders, not on redirects/JSON/polling.
        Supplier<Map<String, String>> flashSource = currentFlash.get();
        if (flashSource != null) {
            var flash = flashSource.get();
            params.put("flash", flash);
            carriesFlash = flash != null && !flash.isEmpty();
        }
        var view = new View(template, params, csrfValues, carriesFlash);
        List<View> recording = created.get();
        if (recording != null) recording.add(view);
        return view;
    }

    /** Runs {@code body}, adding every View it creates to {@code into} (M1, the page cache). */
    static <T> T recordingViews(List<View> into, Supplier<T> body) {
        List<View> outer = created.get();
        created.set(into);
        try {
            return body.get();
        } finally {
            if (outer == null) {
                created.remove();
            } else {
                outer.addAll(into); // a cached handler inside another one: the outer sees them too
                created.set(outer);
            }
        }
    }

    /**
     * Renders the template (M12). Deferred from {@link #of} so it runs after the request transaction
     * commits and its DB connection is released — a slow render no longer caps pool throughput. A render
     * failure here surfaces as a 500 with the transaction already committed: under StatelessSession the
     * render reads no transactional state, and every other post-handler step (after-middleware, cookie
     * write, socket write) is already post-commit, so rendering is treated as response delivery, not part
     * of the unit of work. Idempotent — guarded so a lazy {@link #body()} read and the framework's
     * explicit call don't render twice.
     */
    @Override
    void materialize() {
        if (rendered) {
            return;
        }
        rendered = true;
        if (engine != null) {
            // M6: render straight to UTF-8 bytes — the wire path reads rawBytes() with no String
            // round-trip or second encode. body() decodes lazily if a caller wants the String form.
            setRenderedBytes(engine.renderToBytes(template, params));
        } else {
            setRenderedBody("[Template: " + template + " | Params: " + params.keySet() + "]");
        }
    }

    public static String render(String template, Object... keyValues) {
        var params = toParams(template, keyValues);
        if (engine != null) {
            return engine.render(template, params);
        }
        return "[Template: " + template + " | Params: " + params.keySet() + "]";
    }

    /**
     * Build the parameter map from alternating key/value varargs, rejecting an odd count (L3).
     *
     * <p>The loop used to stop at {@code length - 1}, silently discarding a trailing key: a typo'd
     * {@code View.of("page", "a", 1, "b")} rendered a template missing {@code b} with no error
     * anywhere, and the failure surfaced as a blank spot in the page. {@code Session.of} has always
     * thrown on an odd count; this makes the two agree.
     */
    private static LinkedHashMap<String, Object> toParams(String template, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                "View params for \"" + template + "\" must be key-value pairs, but got "
                    + keyValues.length + " arguments (trailing key: " + keyValues[keyValues.length - 1] + ")");
        }
        var params = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    /**
     * Whether a response built while {@code views} were created carries values taken from the
     * visitor's session (M1): pending flash, or a CSRF token in {@code output}. Such a response is
     * per-visitor and must never be stored in a shared cache. {@code output} is the final response
     * (body and headers), not each View's own render, so a View's body wrapped in
     * {@code Result.html(...)} or a JSON field is caught too.
     */
    static boolean sessionDerived(List<View> views, String output) {
        for (View view : views) {
            if (view.carriesFlash) return true;
            // Every render on a CSRF-active route is handed tokens, but only a response that
            // contains them is per-visitor; a page with no form shows none and stays cacheable.
            // The masked tokens are random per render and base64url (unchanged by HTML, JS or JSON
            // escaping), so a substring match has no false positives and catches them anywhere.
            for (String value : view.csrfValues) {
                if (output.contains(value)) return true;
            }
        }
        return false;
    }

    public String template() { return template; }
    public Map<String, Object> params() { return params; }
}
