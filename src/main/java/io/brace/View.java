package io.brace;

import java.util.LinkedHashMap;
import java.util.Map;

public class View extends Result {

    private final String template;
    private final Map<String, Object> params;

    private View(String template, Map<String, Object> params) {
        super(200, "text/html", "[Template: " + template + " | Params: " + params.keySet() + "]");
        this.template = template;
        this.params = params;
    }

    public static View of(String template, Object... keyValues) {
        var params = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new View(template, params);
    }

    public String template() { return template; }
    public Map<String, Object> params() { return params; }
}
