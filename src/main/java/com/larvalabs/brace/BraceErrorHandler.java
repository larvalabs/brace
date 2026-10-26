package com.larvalabs.brace;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Answers requests Jetty rejects before {@link BraceHandler} runs (ambiguous URIs such as
 * {@code //x} or {@code %2e%2e}, malformed requests) with just the status's reason phrase as
 * {@code text/plain}. Jetty's default error page names the Jetty version and echoes the request
 * URI and the rejection reason; none of that belongs in a response from an app that otherwise
 * hides its server software.
 */
final class BraceErrorHandler extends ErrorHandler {

    @Override
    protected void generateResponse(Request request, Response response, int code, String message,
                                    Throwable cause, Callback callback) throws IOException {
        HttpFields.Mutable headers = response.getHeaders();
        headers.put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        headers.put("X-Content-Type-Options", "nosniff");
        Content.Sink.write(response, true, reasonPhrase(code), callback);
    }

    /** Pre-request parse failures (bad request line, oversized headers) bypass {@code handle}. */
    @Override
    public ByteBuffer badMessageError(int status, String reason, HttpFields.Mutable fields) {
        fields.put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        fields.put("X-Content-Type-Options", "nosniff");
        return StandardCharsets.UTF_8.encode(reasonPhrase(status));
    }

    private static String reasonPhrase(int code) {
        String phrase = HttpStatus.getMessage(code);
        return phrase != null ? phrase : "Error";
    }
}
