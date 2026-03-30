package sample;

import io.brace.*;
import java.util.Map;

public class App {
    public static void main(String[] args) throws Exception {
        var app = Brace.app().port(8080);

        app.get("/", req -> Result.text("Welcome to Brace!"));

        app.get("/hello/{name}", req ->
            Result.text("Hello, " + req.param("name") + "!"));

        app.get("/json", req ->
            Json.of(Map.of(
                "framework", "Brace",
                "version", "0.1.0",
                "status", "running"
            )));

        app.get("/redirect", req -> Redirect.to("/"));

        app.before("/admin/*", req -> Result.unauthorized("Login required"));
        app.get("/admin/dashboard", req -> Result.text("Admin Dashboard"));

        app.after((req, result) -> {
            result.header("X-Powered-By", "Brace/0.1.0");
            return result;
        });

        app.start();
    }
}
