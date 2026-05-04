package com.larvalabs.brace;

@FunctionalInterface
public interface Handler {
    Result apply(Request request);
}
