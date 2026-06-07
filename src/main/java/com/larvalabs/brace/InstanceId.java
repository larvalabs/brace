package com.larvalabs.brace;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Stable per-process identity for multi-server deployments. Computed once at startup and
 * exposed via {@link Brace#instanceId()}. Used to tag fleet metrics ({@code ops_timeseries})
 * and anchor regression detection so a fleet of N instances behind a load balancer is
 * distinguishable in the ops layer.
 *
 * <p>Format: {@code <host>:<port>-<random>} — e.g. {@code web-3:8080-a1b2c3d4}. The host is the
 * machine hostname (best-effort; {@code "unknown"} if it can't be resolved), the port is the
 * bound HTTP port, and the random suffix disambiguates instances that share a host/port view —
 * common for containers behind a proxy that all report the same internal port.
 *
 * <p>The id is process-local and regenerated on every restart (including the random suffix), so
 * it identifies a <em>running instance</em>, not a logical slot. For grouping across restarts
 * (e.g. "all deploys of release X") use the deploy marker, not the instance id.
 */
final class InstanceId {

    private InstanceId() {}

    /** Build an instance id for a process bound to {@code port}. */
    static String generate(int port) {
        return hostname() + ":" + port + "-" + randomSuffix();
    }

    private static String hostname() {
        // Containers commonly set HOSTNAME to the container id; prefer it when present.
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        try {
            String h = InetAddress.getLocalHost().getHostName();
            if (h != null && !h.isBlank()) {
                return h;
            }
        } catch (UnknownHostException ignored) {
            // fall through to the sentinel
        }
        return "unknown";
    }

    private static String randomSuffix() {
        // 8 hex chars — enough to disambiguate co-located instances without bloating log lines.
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
