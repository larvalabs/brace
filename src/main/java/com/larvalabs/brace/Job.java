package com.larvalabs.brace;

@FunctionalInterface
public interface Job {
    void run(Database db) throws Exception;
}
