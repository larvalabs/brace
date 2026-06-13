package com.larvalabs.brace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class View extends Result {

    private static TemplateEngine engine;
    // Supplier, not a materialized string (H5): the CSRF token is minted — and the
    // session cookie consequently written — only when a render (or a handler via
    // getCsrfField()) actually consumes the field, not on every matched request.
    private static final ThreadLocal<Supplier<String>> currentCsrfField = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, String>> currentFlash = new ThreadLocal<>();

    static void setCsrfField(Supplier<String> fieldSupplier) { currentCsrfField.set(fieldSupplier); }
    static void clearCsrfField() { currentCsrfField.remove(); }

    /**
     * Resolves the CSRF hidden field for the current request, minting the token on first
     * use. Null when CSRF is not active for the route ({@code .csrf(false)}, or sessions
     * not configured).
     */
    static String getCsrfField() {
        Supplier<String> supplier = currentCsrfField.get();
        return supplier != null ? supplier.get() : null;
    }
    static void setFlash(Map<String, String> flash) { currentFlash.set(flash); }
    static void clearFlash() { currentFlash.remove(); }

    private final String template;
    private final Map<String, Object> params;
    private boolean rendered;

    private View(String template, Map<String, Object> params) {
        super(200, "text/html", null);
        this.template = template;
        this.params = params;
    }

    static void setEngine(TemplateEngine engine) {
        View.engine = engine;
    }

    public static View of(String template, Object... keyValues) {
        var params = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        // Resolve the request-scoped CSRF field and flash NOW, while their ThreadLocals are still set
        // (the handler is mid-flight). getCsrfField() mints the token, so the session is marked modified
        // here — before the cookie-write decision — exactly as it was when rendering happened eagerly.
        // The template render itself is deferred to materialize() (M12); the resolved values ride along
        // in params, so it no longer depends on the ThreadLocals, which are cleared before it runs.
        String csrfField = getCsrfField();
        if (csrfField != null) {
            params.put("csrfField", csrfField);
        }
        Map<String, String> flash = currentFlash.get();
        if (flash != null) {
            params.put("flash", flash);
        }
        return new View(template, params);
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
        String html;
        if (engine != null) {
            html = engine.render(template, params);
        } else {
            html = "[Template: " + template + " | Params: " + params.keySet() + "]";
        }
        setRenderedBody(html);
    }

    public static String render(String template, Object... keyValues) {
        var params = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        if (engine != null) {
            return engine.render(template, params);
        }
        return "[Template: " + template + " | Params: " + params.keySet() + "]";
    }

    public String template() { return template; }
    public Map<String, Object> params() { return params; }
}
