package com.larvalabs.brace;

import java.util.LinkedHashMap;

/**
 * Records authenticated ops-endpoint access as a structured {@code ops.access} log
 * event ({@code kid} + scope + endpoint + granted), so a stolen or misused ops key
 * is visible after the fact. It rides the normal logging pipeline, so it is durable
 * wherever stdout goes and queryable via {@code brace logs} / {@code /ops/logs}
 * filtered on {@code event=ops.access} — no separate store, endpoint, or migration,
 * and it works whether or not the app has a database.
 *
 * <p>Only requests carrying a <em>valid</em> token are recorded — both granted access
 * and authenticated-but-insufficient-scope attempts (the {@code granted=false} signal
 * of an attempted privilege escalation). Unauthenticated probes are not logged here to
 * avoid drowning the signal; they already surface as 401s in the request log.
 */
class OpsAudit {

    private OpsAudit() {}

    static void record(String method, String path, String kid, OpsScope scope,
                       OpsScope required, boolean granted) {
        var data = new LinkedHashMap<String, Object>();
        data.put("method", method);
        data.put("path", path);
        // Tokens minted before scoping carried no kid; attribute them as "legacy" rather than null.
        data.put("kid", kid != null ? kid : "legacy");
        data.put("scope", scope != null ? scope.wire() : null);
        data.put("required", required.wire());
        data.put("granted", granted);
        Log.event("ops.access", data);
    }
}
