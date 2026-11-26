package benchmark;

import com.larvalabs.brace.*;
import benchmark.model.*;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

public class App {
    public static void main(String[] args) throws Exception {
        // DB_HOST / DB_PORT / DB_POOL are set by run-brace.sh (lib.sh); the defaults match the
        // tfb-postgres container in benchmark/README.md. A pool of 256 matches the TechEmpower
        // Spring implementation; Brace's own default is 10.
        var env = System.getenv();
        var db = new DatabaseFactory(
            "jdbc:postgresql://" + env.getOrDefault("DB_HOST", "localhost") + ":"
                + env.getOrDefault("DB_PORT", "5434") + "/hello_world",
            "benchmarkdbuser", "benchmarkdbpass",
            List.of(World.class, Fortune.class),
            Integer.parseInt(env.getOrDefault("DB_POOL", "256")));

        var app = Brace.app()
            .port(Integer.parseInt(env.getOrDefault("PORT", "8080")))
            .database(db)
            .templates("benchmark/src/main/resources/views")
            .defaultAccess(Access.none()); // benchmark endpoints are public

        // Server header
        app.after((req, result) -> {
            result.header("Server", "Brace");
            return result;
        });

        // Benchmark-only experiment (FAIR_LIMIT=N, off by default): admit at most N database
        // requests at a time, strictly in arrival order, before Brace opens the DB session. It
        // tests whether the DB tests' latency tail comes from HikariCP letting a newly arriving
        // request take a just-freed connection ahead of requests already waiting. Spring's
        // worker-thread queue, by contrast, is first come, first served. Before-middleware runs
        // before the session opens; after-middleware runs once it's closed, on success and on
        // error responses alike. The ThreadLocal guards against releasing a permit that wasn't
        // taken.
        int fairLimit = Integer.parseInt(env.getOrDefault("FAIR_LIMIT", "0"));
        if (fairLimit > 0) {
            var gate = new Semaphore(fairLimit, true);
            var held = new ThreadLocal<Boolean>();
            for (String path : List.of("/db", "/queries", "/fortunes", "/updates")) {
                app.before(path, req -> {
                    gate.acquireUninterruptibly();
                    held.set(Boolean.TRUE);
                    return null;
                });
                app.after(path, (req, result) -> {
                    if (held.get() != null) {
                        held.remove();
                        gate.release();
                    }
                    return result;
                });
            }
        }

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
            int queries = parseQueries(req.queryParam("queries"));
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

        // Diagnostics for the Fortunes latency tail (DIAG_ROUTES=1; not TechEmpower tests): the
        // same work with one part removed.
        //   /fortunes-json   same query and sort, JSON instead of the template (no render)
        //   /fortunes-nodb   the template over rows read once at startup (no database)
        if ("1".equals(env.get("DIAG_ROUTES"))) {
            List<Fortune> cached;
            var session = db.openSession();
            try {
                cached = List.copyOf(new Database(session).findAll(Fortune.class));
            } finally {
                session.close();
            }
            app.get("/fortunes-json", (ReadDbHandler) (req, dbSession) -> {
                var fortunes = new ArrayList<>(dbSession.findAll(Fortune.class));
                var additional = new Fortune();
                additional.id = 0;
                additional.message = "Additional fortune added at request time.";
                fortunes.add(additional);
                fortunes.sort(Comparator.comparing(f -> f.message));
                return Json.of(fortunes);
            });
            app.get("/fortunes-nodb", req -> {
                var fortunes = new ArrayList<>(cached);
                var additional = new Fortune();
                additional.id = 0;
                additional.message = "Additional fortune added at request time.";
                fortunes.add(additional);
                fortunes.sort(Comparator.comparing(f -> f.message));
                return View.of("fortunes", "fortunes", fortunes);
            });
        }

        // 6. Updates (read worlds, then batch update via raw JDBC)
        app.get("/updates", (DbHandler) (req, dbSession) -> {
            int queries = parseQueries(req.queryParam("queries"));
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
