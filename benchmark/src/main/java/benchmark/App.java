package benchmark;

import com.larvalabs.brace.*;
import benchmark.model.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class App {
    public static void main(String[] args) throws Exception {
        var db = new DatabaseFactory(
            "jdbc:postgresql://localhost:5433/hello_world",
            "benchmarkdbuser", "benchmarkdbpass",
            List.of(World.class, Fortune.class),
            256);

        var app = Brace.app()
            .port(8080)
            .database(db)
            .templates("benchmark/src/main/resources/views");

        // Server header
        app.after((req, result) -> {
            result.header("Server", "Brace");
            return result;
        });

        // 1. Plaintext
        app.get("/plaintext", req -> Result.text("Hello, World!"));

        // 2. JSON
        app.get("/json", req -> Json.of(Map.of("message", "Hello, World!")));

        // 3. Single Query (read-only, no transaction)
        app.get("/db", (ReadDbHandler) (req, dbSession) -> {
            int id = ThreadLocalRandom.current().nextInt(1, 10001);
            var world = dbSession.find(World.class, id);
            return Json.of(world);
        });

        // 4. Multiple Queries (read-only, no transaction)
        app.get("/queries", (ReadDbHandler) (req, dbSession) -> {
            int queries = parseQueries(req.param("queries"));
            var worlds = new ArrayList<World>(queries);
            for (int i = 0; i < queries; i++) {
                int id = ThreadLocalRandom.current().nextInt(1, 10001);
                worlds.add(dbSession.find(World.class, id));
            }
            return Json.of(worlds);
        });

        // 5. Fortunes (read-only, no transaction)
        app.get("/fortunes", (ReadDbHandler) (req, dbSession) -> {
            var fortunes = new ArrayList<>(dbSession.findAll(Fortune.class));
            var additional = new Fortune();
            additional.id = 0;
            additional.message = "Additional fortune added at request time.";
            fortunes.add(additional);
            fortunes.sort(Comparator.comparing(f -> f.message));
            return View.of("fortunes", "fortunes", fortunes);
        });

        // 6. Updates (read worlds, then batch update via raw JDBC)
        app.get("/updates", (DbHandler) (req, dbSession) -> {
            int queries = parseQueries(req.param("queries"));
            var worlds = new ArrayList<World>(queries);
            for (int i = 0; i < queries; i++) {
                int id = ThreadLocalRandom.current().nextInt(1, 10001);
                var world = dbSession.find(World.class, id);
                world.randomNumber = ThreadLocalRandom.current().nextInt(1, 10001);
                worlds.add(world);
            }
            worlds.sort(Comparator.comparingInt(w -> w.id));
            dbSession.jdbc(conn -> {
                try (var ps = conn.prepareStatement("UPDATE world SET randomnumber = ? WHERE id = ?")) {
                    for (var world : worlds) {
                        ps.setInt(1, world.randomNumber);
                        ps.setInt(2, world.id);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            });
            return Json.of(worlds);
        });

        app.start();
    }

    private static int parseQueries(String param) {
        if (param == null) return 1;
        try {
            int q = Integer.parseInt(param);
            return Math.max(1, Math.min(500, q));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
