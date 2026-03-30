package io.brace;

public class Brace {
    private int port = 8080;

    public static Brace app() {
        return new Brace();
    }

    public Brace port(int port) {
        this.port = port;
        return this;
    }

    public int port() {
        return port;
    }
}
