package com.larvalabs.brace;

/**
 * Thrown when a query string or form body has malformed percent-encoding ({@code ?q=%zz}).
 * {@link BraceHandler} catches it and writes a 400 that records no error: a bad request is a
 * client mistake, and recording it as a 500 let any client file a new error kind, and a new
 * regression notification, per path. An {@code IllegalArgumentException}, which is what
 * {@code URLDecoder} threw here before, so existing handling of that still applies.
 */
class MalformedRequestException extends IllegalArgumentException {
    MalformedRequestException(Throwable cause) {
        super("Malformed percent-encoding in the query string or form body", cause);
    }
}
