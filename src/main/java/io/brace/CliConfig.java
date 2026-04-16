package io.brace;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

public record CliConfig(String url, String keyPath, String authorizedKeysPath, String env) {

    private static final String DEFAULT_LOCAL_URL = "http://localhost:8080";
    private static final String DEFAULT_KEY_PATH = "ops-private.key";
    private static final String DEFAULT_AUTH_KEYS = "ops-authorized-keys";
    private static final String DEFAULT_ENV = "local";

    public static CliConfig load(Path projectDir, String[] cliArgs) throws IOException {
        var values = new LinkedHashMap<String, String>();

        // Layer 1: .brace (committed)
        var brace = projectDir.resolve(".brace");
        if (Files.exists(brace)) merge(values, brace);

        // Layer 2: .brace.local (gitignored)
        var local = projectDir.resolve(".brace.local");
        if (Files.exists(local)) merge(values, local);

        // Layer 3: CLI flags
        String envFlag = null, urlFlag = null, keyFlag = null;
        for (int i = 0; i < cliArgs.length - 1; i++) {
            switch (cliArgs[i]) {
                case "--env" -> envFlag = cliArgs[i + 1];
                case "--url" -> urlFlag = cliArgs[i + 1];
                case "--key" -> keyFlag = cliArgs[i + 1];
                default -> {}
            }
        }

        String env = envFlag != null ? envFlag
                   : values.getOrDefault("ops.env", DEFAULT_ENV);

        String url;
        if (urlFlag != null) {
            url = urlFlag;
        } else {
            String key = "ops." + env + ".url";
            url = values.getOrDefault(key, DEFAULT_LOCAL_URL);
        }

        String keyPath = keyFlag != null ? keyFlag
                       : values.getOrDefault("ops.key", DEFAULT_KEY_PATH);

        String authKeys = values.getOrDefault("ops.authorized_keys", DEFAULT_AUTH_KEYS);

        return new CliConfig(url, keyPath, authKeys, env);
    }

    private static void merge(Map<String, String> into, Path file) throws IOException {
        for (var line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            into.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }
}
