package com.larvalabs.brace;

/**
 * Thrown when a request body exceeds {@code maxUploadSize}. Since M2 (2026-07 security
 * review) the body is read lazily — after before-middleware has had its chance to shed the
 * request — so the over-limit condition surfaces at the point of first access rather than
 * as an inline branch during request setup. {@link BraceHandler} catches it and writes the
 * 413; it is deliberately not an application-facing exception type.
 */
class PayloadTooLargeException extends RuntimeException {
    PayloadTooLargeException() {
        super("Request body exceeds the configured maximum upload size");
    }
}
