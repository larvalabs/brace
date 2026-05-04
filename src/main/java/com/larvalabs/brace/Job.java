package com.larvalabs.brace;

@FunctionalInterface
public interface Job {
    void run(Database db, JobContext ctx) throws Exception;
}
