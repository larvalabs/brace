package com.larvalabs.brace;

/**
 * Resolves the framework version at runtime by reading
 * {@code META-INF/brace-version.txt}, which Maven fills with {@code ${project.version}}
 * during resource filtering. Works whether running from the packaged jar or from
 * {@code target/classes/} during local development.
 */
public final class BraceVersion {

    private static final String VERSION = load();

    private BraceVersion() {}

    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (var in = BraceVersion.class.getResourceAsStream("/META-INF/brace-version.txt")) {
            if (in == null) return "unknown";
            return new String(in.readAllBytes()).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
