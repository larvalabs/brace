package io.brace;

@FunctionalInterface
public interface DbHandler {
    Result apply(Request request, Database database);
}
