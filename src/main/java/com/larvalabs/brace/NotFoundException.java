package com.larvalabs.brace;

public class NotFoundException extends RuntimeException {
    // Thrown as routine not-found control flow on the hot path (Result.notFoundIfNull);
    // the trace is never read — BraceHandler's catch only maps it to a 404 — so skip the
    // stack-walk that fillInStackTrace would do on every throw.
    public NotFoundException() { super("Not Found", null, false, false); }
}
