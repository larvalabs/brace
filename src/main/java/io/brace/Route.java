package io.brace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class Route {

    private final String method;
    private final String pattern;
    private final Object handler;
    private final Invoker invoker;
    private final Pattern compiledPattern;
    private final List<String> paramNames;

    public Route(String method, String pattern, Object handler) {
        this(method, pattern, handler, null);
    }

    public Route(String method, String pattern, Object handler, Invoker invoker) {
        this.method = method;
        this.pattern = pattern;
        this.handler = handler;
        this.invoker = invoker;
        this.paramNames = new ArrayList<>();

        var regex = new StringBuilder("^");
        var parts = pattern.split("/");
        for (var part : parts) {
            if (part.isEmpty()) continue;
            regex.append("/");
            if (part.startsWith("{") && part.endsWith("}")) {
                paramNames.add(part.substring(1, part.length() - 1));
                regex.append("([^/]+)");
            } else {
                regex.append(Pattern.quote(part));
            }
        }
        if (regex.length() == 1) regex.append("/");
        regex.append("$");
        this.compiledPattern = Pattern.compile(regex.toString());
    }

    public String method() { return method; }
    public String pattern() { return pattern; }
    public Object handler() { return handler; }
    public Invoker invoker() { return invoker; }
    public boolean isStatic() { return paramNames.isEmpty(); }

    public Map<String, String> match(String path) {
        var matcher = compiledPattern.matcher(path);
        if (!matcher.matches()) return null;
        var params = new LinkedHashMap<String, String>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), matcher.group(i + 1));
        }
        return params;
    }
}
