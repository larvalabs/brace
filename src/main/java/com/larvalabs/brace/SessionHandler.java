package com.larvalabs.brace;

@FunctionalInterface
public interface SessionHandler {
    Result apply(Request request, Session session);
}
