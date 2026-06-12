package benchmark;

import com.larvalabs.brace.*;

import java.util.Map;

/**
 * Session/CSRF benchmark app — separate from App so the standard TFB suite stays
 * byte-identical across checkpoints. No database: isolates session crypto + form
 * parsing (H5/M2/M3 in docs/2026-06-11-runtime-performance-review-todos.md).
 *
 * Run with: java --enable-preview -cp <jar> benchmark.SessionApp
 * Drive with: run-session.sh (primes a session cookie + CSRF token, then wrk).
 */
public class SessionApp {
    public static void main(String[] args) throws Exception {
        var app = Brace.app()
            .port(8081)
            .sessions("brace-benchmark-session-secret-0123456789abcdef");

        // Prime: establish a session with a value and a CSRF token; body = the token.
        app.get("/sess/prime", (SessionHandler) (req, session) -> {
            session.set("user", "benchmark-user");
            Csrf.ensureToken(session);
            return Result.text(Csrf.getToken(session));
        });

        // Read-only session GET: decrypts the cookie, writes nothing back.
        app.get("/sess/read", (SessionHandler) (req, session) -> {
            var user = session.get("user");
            return Result.text(user == null ? "anonymous" : user);
        });

        // CSRF-protected form POST: framework validates _csrf, handler reads a form param.
        app.post("/sess/form", (SessionHandler) (req, session) ->
            Result.text("ok:" + req.formParam("data")));

        // Bearer-token-style API POST: csrf(false), but the client still sends the
        // session cookie (a browser would on same-origin requests).
        app.post("/sess/api", req -> Json.of(Map.of("ok", true))).csrf(false);

        app.start();
    }
}
