package io.brace;

@FunctionalInterface
public interface SessionHandler {
    Result apply(Request request, Session session);
}
