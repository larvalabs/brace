package io.brace;

import java.util.LinkedHashMap;
import java.util.Map;

public class View extends Result {

    private static TemplateEngine engine;
    private static final ThreadLocal<String> currentCsrfField = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, String>> currentFlash = new ThreadLocal<>();

    static void setCsrfField(String field) { currentCsrfField.set(field); }
    static void clearCsrfField() { currentCsrfField.remove(); }
    static void setFlash(Map<String, String> flash) { currentFlash.set(flash); }
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
        Map<String, String> flash = currentFlash.get();
        if (flash != null) {
            params.put("flash", flash);
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
