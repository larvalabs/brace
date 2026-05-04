package com.larvalabs.brace;

@FunctionalInterface
public interface ReadDbHandler {
    Result apply(Request request, Database database);
}
