package sample;

import com.larvalabs.brace.*;
import java.util.Map;

public class App {
    public static void main(String[] args) throws Exception {
        var app = Brace.app().port(8080).ops("ops-authorized-keys");

        app.get("/", req -> Result.text("Welcome to Brace!"));

        app.get("/hello/{name}", req ->
            Result.text("Hello, " + req.pathParam("name") + "!"));

        app.get("/json", req ->
            Result.json(Map.of(
                "framework", "Brace",
                "version", "dev",
                "status", "running"
            )));

        app.get("/redirect", req -> Result.redirect("/"));

        // Before middleware: guard /admin/* behind a header check (demo only — not real auth).
        // Returning null continues the chain; a Result short-circuits the request.
        app.before("/admin/*", req ->
            "letmein".equals(req.header("X-Admin-Key"))
                ? null
                : Result.unauthorized("Send header X-Admin-Key: letmein"));
        app.get("/admin/dashboard", req -> Result.text("Admin Dashboard"));

        app.after((req, result) -> {
            result.header("X-Powered-By", "Brace");
            return result;
        });

        app.start();
    }
}
