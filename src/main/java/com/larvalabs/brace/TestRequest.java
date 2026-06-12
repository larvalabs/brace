package com.larvalabs.brace;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Fluent request builder for {@link TestApp} — the general escape hatch when the fixed
 * convenience methods ({@code get}, {@code post}, ...) don't fit: custom headers
 * (bearer-token APIs), unusual verbs, raw bodies.
 *
 * <pre>{@code
 * var res = testApp.request("GET", "/api/items")
 *     .header("Authorization", "Bearer " + token)
 *     .send();
 * }</pre>
 *
 * Uses the same HttpClient (shared cookie jar, HTTP/1.1, no redirect following) and URL
 * resolution as the fixed methods.
 */
public class TestRequest {

    private final TestApp app;
    private final String method;
    private final String path;
    private final List<String[]> headers = new ArrayList<>();
    private Session session;
    private String body;
    private String contentType;

    TestRequest(TestApp app, String method, String path) {
        this.app = app;
        this.method = method.toUpperCase();
        this.path = path;
    }

    /** Add a request header. Repeatable — call once per header value. */
    public TestRequest header(String name, String value) {
        headers.add(new String[]{name, value});
        return this;
    }

    /**
     * Send the request with this session's encrypted cookie. Any {@code brace_session}
     * cookie previously captured in the shared cookie jar is evicted at send time so the
     * explicit session is the one the server sees.
     */
    public TestRequest session(Session session) {
        this.session = session;
        return this;
    }

    /** Set the request body and its {@code Content-Type}. */
    public TestRequest body(String body, String contentType) {
        this.body = body;
        this.contentType = contentType;
        return this;
    }

    /** Build, send, and wrap the response. */
    public TestResponse send() {
        try {
            var builder = HttpRequest.newBuilder().uri(URI.create(app.url(path)));
            for (var h : headers) {
                builder.header(h[0], h[1]);
            }
            if (session != null) {
                app.evictSessionCookie();
                builder.header("Cookie", "brace_session=" + session.toCookie(app.sessionSecret()));
            }
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            var publisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();
            builder.method(method, publisher);
            return new TestResponse(app.httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new RuntimeException(method + " " + path + " failed", e);
        }
    }
}
