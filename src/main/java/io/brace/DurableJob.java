package io.brace;

public interface DurableJob {
    String data();
    void run(String data, Database db) throws Exception;
}
