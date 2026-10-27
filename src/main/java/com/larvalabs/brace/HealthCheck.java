package com.larvalabs.brace;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;

/**
 * Container health probe (M13): {@code java -cp app.jar com.larvalabs.brace.HealthCheck [port]}.
 * Exits 0 when {@code GET http://127.0.0.1:<port>/__brace/health} answers 200 within the timeout,
 * and 1 otherwise. The port defaults to 8080.
 *
 * <p>It exists for images with no shell, curl or wget, such as the scaffold's distroless runtime,
 * where Docker's {@code HEALTHCHECK} has nothing else to run. It connects directly, never through
 * a proxy set in {@code JAVA_TOOL_OPTIONS}, since the server is in the same container.
 */
public final class HealthCheck {

    static final int TIMEOUT_MS = 3_000;

    private HealthCheck() {}

    public static void main(String[] args) {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("usage: HealthCheck [port]");
                System.exit(2);
            }
        }
        System.exit(check(port) ? 0 : 1);
    }

    /** True if the health route on this port answers 200. */
    static boolean check(int port) {
        try {
            var url = URI.create("http://127.0.0.1:" + port + BraceHandler.HEALTH_PATH).toURL();
            var conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            try {
                return conn.getResponseCode() == 200;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            System.err.println("health check failed: " + e);
            return false;
        }
    }
}
