package com.larvalabs.brace;

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
    private final String staticPath;
    private boolean csrfRequired;
    private String name;
    private Analytics.Track analytics = Analytics.Track.PATH;

    public Route(String method, String pattern, Object handler, Invoker invoker) {
        this(method, pattern, handler, invoker, true);
    }

    public Route(String method, String pattern, Object handler, Invoker invoker, boolean csrfRequired) {
        this.method = method;
        this.pattern = pattern;
        this.handler = handler;
        this.invoker = invoker;
        this.csrfRequired = csrfRequired;
        this.paramNames = new ArrayList<>();

        var regex = new StringBuilder("^");
        var literal = new StringBuilder();
        var parts = pattern.split("/");
        for (var part : parts) {
            if (part.isEmpty()) continue;
            regex.append("/");
            literal.append('/').append(part);
            if (part.startsWith("{") && part.endsWith("}")) {
                paramNames.add(part.substring(1, part.length() - 1));
                regex.append("([^/]+)");
            } else {
                regex.append(Pattern.quote(part));
            }
        }
        if (regex.length() == 1) {
            regex.append("/");
            literal.append('/');
        }
        regex.append("$");
        this.compiledPattern = Pattern.compile(regex.toString());
        // Same normalization the regex applies (empty segments collapsed, trailing slash
        // dropped) so the Router's exact-match index agrees with compiledPattern.
        this.staticPath = paramNames.isEmpty() ? literal.toString() : null;
    }

    public String method() { return method; }
    public String pattern() { return pattern; }
    public Object handler() { return handler; }
    public Invoker invoker() { return invoker; }
    public boolean isStatic() { return paramNames.isEmpty(); }
    /** Path parameter names in pattern order, e.g. {@code [id]} for {@code /users/{id}}. */
    List<String> paramNames() { return paramNames; }
    /** Normalized literal path for static routes, {@code null} for parameterized ones. */
    String staticPath() { return staticPath; }
    public boolean csrfRequired() { return csrfRequired; }
    /** Route name for reverse routing ({@code Url.to(name, ...)}), or {@code null} if unnamed. */
    public String name() { return name; }

    /** How {@link Analytics} records views of this route; {@code PATH} unless configured. */
    public Analytics.Track analytics() { return analytics; }

    void setAnalytics(Analytics.Track track) {
        this.analytics = track;
    }

    void setCsrfRequired(boolean required) {
        this.csrfRequired = required;
    }

    void setName(String name) {
        this.name = name;
    }

    /**
     * Match {@code path} (the RAW, still percent-encoded request path) against this route,
     * returning the captured parameters or null.
     *
     * <p>Matching runs on the raw path and captured values are decoded <em>afterwards</em> (H3).
     * The order is the whole safety argument: decoding first would turn a {@code %2F} into a real
     * separator, so {@code /files/a%2F..%2Fb} would match a two-segment route and hand a handler
     * an escaped path. Decoding after the capture keeps {@code %2F} inside the value it was
     * written in, where it is just a character.
     */
    public Map<String, String> match(String path) {
        var matcher = compiledPattern.matcher(path);
        if (!matcher.matches()) return null;
        var params = new LinkedHashMap<String, String>();
        for (int i = 0; i < paramNames.size(); i++) {
            params.put(paramNames.get(i), decodeSegment(matcher.group(i + 1)));
        }
        return params;
    }

    /**
     * Percent-decode a matched path segment (the request path arrives still encoded), so
     * {@code /tags/red%20hat} gives {@code "red hat"} and round-trips with {@link Url#to}.
     * Path decoding, not form decoding: {@code +} stays a literal plus. A malformed escape
     * leaves the segment as it arrived.
     */
    static String decodeSegment(String segment) {
        if (segment.indexOf('%') < 0) return segment;
        var bytes = new java.io.ByteArrayOutputStream(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%') {
                if (i + 2 >= segment.length()) return segment;
                int hi = Character.digit(segment.charAt(i + 1), 16);
                int lo = Character.digit(segment.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return segment;
                bytes.write((hi << 4) | lo);
                i += 2;
            } else if (c < 0x80) {
                bytes.write(c);
            } else {
                int cp = segment.codePointAt(i);
                bytes.writeBytes(Character.toString(cp).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                i += Character.charCount(cp) - 1;
            }
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Percent-decode every segment of a {@code /}-separated path, preserving the separators.
     * Each segment goes through {@link #decodeSegment}, so a {@code %2F} decodes to a literal
     * {@code /} <em>within</em> its segment and callers must still reject {@code ..} on the
     * decoded result before touching the filesystem.
     */
    static String decodePath(String path) {
        if (path.indexOf('%') < 0) return path;
        var out = new StringBuilder(path.length());
        int start = 0;
        while (true) {
            int slash = path.indexOf('/', start);
            if (slash < 0) {
                return out.append(decodeSegment(path.substring(start))).toString();
            }
            out.append(decodeSegment(path.substring(start, slash))).append('/');
            start = slash + 1;
        }
    }
}
