package com.larvalabs.brace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class View extends Result {

    private static TemplateEngine engine;
    private static final ThreadLocal<String> currentCsrfField = new ThreadLocal<>();
    // Flash is consumed lazily: the source (set per-request by BraceHandler) consumes the
    // session's cookie-borne flash when — and only when — a View actually renders, so flash
    // survives requests that render nothing (redirects, JSON, polling).
    private static final ThreadLocal<Supplier<Map<String, String>>> currentFlash = new ThreadLocal<>();

    static void setCsrfField(String field) { currentCsrfField.set(field); }
    static void clearCsrfField() { currentCsrfField.remove(); }
    static String getCsrfField() { return currentCsrfField.get(); }
    static void setFlashSource(Supplier<Map<String, String>> source) { currentFlash.set(source); }
    static void clearFlash() { currentFlash.remove(); }

    private final String template;
    private final Map<String, Object> params;

    private View(String template, Map<String, Object> params, String renderedHtml) {
        super(200, "text/html", renderedHtml);
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
        String csrfField = currentCsrfField.get();
        if (csrfField != null) {
            params.put("csrfField", csrfField);
        }
        Supplier<Map<String, String>> flashSource = currentFlash.get();
        if (flashSource != null) {
            params.put("flash", flashSource.get());
        }
        String html;
        if (engine != null) {
            html = engine.render(template, params);
        } else {
            html = "[Template: " + template + " | Params: " + params.keySet() + "]";
        }
        return new View(template, params, html);
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
