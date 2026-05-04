package com.larvalabs.brace;

@FunctionalInterface
public interface ReadFullHandler {
    Result apply(Request request, Database database, Session session);
}
