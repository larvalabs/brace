package io.brace;

@FunctionalInterface
public interface Handler {
    Result apply(Request request);
}
