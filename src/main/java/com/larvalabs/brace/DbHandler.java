package com.larvalabs.brace;

@FunctionalInterface
public interface DbHandler {
    Result apply(Request request, Database database);
}
