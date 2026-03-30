package io.brace;

@FunctionalInterface
public interface Job {
    void run(Database db) throws Exception;
}
