package io.brace;

public class Redirect extends Result {

    private Redirect(int status, String location) {
        super(status, "text/plain", "");
        header("Location", location);
    }

    public static Redirect to(String location) {
        return new Redirect(302, location);
    }

    public static Redirect permanent(String location) {
        return new Redirect(301, location);
    }
}
