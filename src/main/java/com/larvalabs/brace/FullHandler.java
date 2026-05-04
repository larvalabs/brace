package com.larvalabs.brace;

@FunctionalInterface
public interface FullHandler {
    Result apply(Request request, Database database, Session session);
}
