package com.larvalabs.brace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class Config {

    private final Map<String, String> values;

    private Config(Map<String, String> values) {
        this.values = values;
    }

    public static Config load(Path file, String mode) throws IOException {
        var raw = new LinkedHashMap<String, String>();
        var modePrefix = mode != null ? "%" + mode + "." : null;

        for (var line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            var eqIndex = line.indexOf('=');
            if (eqIndex < 0) continue;

            var key = line.substring(0, eqIndex).trim();
            var value = line.substring(eqIndex + 1).trim();
            raw.put(key, value);
        }

        var resolved = new LinkedHashMap<String, String>();

        for (var entry : raw.entrySet()) {
            var key = entry.getKey();
            if (!key.startsWith("%")) {
                resolved.put(key, resolveValue(entry.getValue()));
            }
        }

        if (modePrefix != null) {
            for (var entry : raw.entrySet()) {
                var key = entry.getKey();
                if (key.startsWith(modePrefix)) {
                    var unprefixedKey = key.substring(modePrefix.length());
                    resolved.put(unprefixedKey, resolveValue(entry.getValue()));
                }
            }
        }

        return new Config(resolved);
    }

    private static String resolveValue(String value) {
        if (value.startsWith("${") && value.endsWith("}")) {
            var envVar = value.substring(2, value.length() - 1);
            return System.getenv(envVar);
        }
        return value;
    }

    public String get(String key) {
        var value = values.get(key);
        if (value == null) {
            var envKey = key.replace('.', '_').toUpperCase();
            return System.getenv(envKey);
        }
        return value;
    }

    public String get(String key, String defaultValue) {
        var value = get(key);
        return value != null ? value : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        var value = get(key);
        if (value == null) return defaultValue;
        return Integer.parseInt(value);
    }

    public boolean getBool(String key, boolean defaultValue) {
        var value = get(key);
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value);
    }
}
