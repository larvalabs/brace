package sample;

import com.larvalabs.brace.*;
import java.util.Map;

public class App {
    public static void main(String[] args) throws Exception {
        var app = Brace.app().port(8080).ops("ops-authorized-keys");

        // Every route needs an access requirement or .publicRoute(); start() refuses otherwise.
        app.get("/", req -> Result.text("Welcome to Brace!")).publicRoute();

        app.get("/hello/{name}", req ->
            Result.text("Hello, " + req.pathParam("name") + "!")).publicRoute();

        app.get("/json", req ->
            Result.json(Map.of(
                "framework", "Brace",
                "version", "dev",
                "status", "running"
            ))).publicRoute();

        app.get("/redirect", req -> Result.redirect("/")).publicRoute();

        // An access requirement bound to the group's routes: a header check (demo only — not
        // real auth). Returning null lets the request through; a Result rejects it.
        app.group("/admin", Access.checkRequest(req ->
            "letmein".equals(req.header("X-Admin-Key"))
                ? null
                : Result.unauthorized("Send header X-Admin-Key: letmein")), admin ->
            admin.get("/dashboard", req -> Result.text("Admin Dashboard")));

        app.after((req, result) -> {
            result.header("X-Powered-By", "Brace");
            return result;
        });

        app.start();
    }
}
