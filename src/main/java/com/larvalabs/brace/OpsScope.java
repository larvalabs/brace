package com.larvalabs.brace;

/**
 * Authorization scope for ops tokens and authorized keys, ordered least- to
 * most-privileged. A request is allowed when its token's scope {@link #grants}
 * the scope the endpoint requires; {@code CONTROL} implies {@code READ}.
 *
 * <p>Read endpoints ({@code status}, {@code errors}, {@code logs}, {@code routes},
 * {@code cache} stats) require {@link #READ}; mutating endpoints
 * ({@code cache/clear}, {@code errors/{id}/resolve}) require {@link #CONTROL}.
 * This is what lets an operator hand an autonomous agent a read-only key it can
 * never escalate.
 */
public enum OpsScope {
    READ,
    CONTROL;

    /** True if this scope grants at least the privileges of {@code required}. */
    public boolean grants(OpsScope required) {
        return ordinal() >= required.ordinal();
    }

    /** The lesser of two scopes — used to cap a requested scope at a key's ceiling. */
    public OpsScope min(OpsScope other) {
        return ordinal() <= other.ordinal() ? this : other;
    }

    /** Lowercase wire name embedded in token payloads and authorized-keys files. */
    public String wire() {
        return name().toLowerCase();
    }

    /** Parse a scope name (case-insensitive); returns {@code fallback} for null/blank/unknown. */
    public static OpsScope parse(String s, OpsScope fallback) {
        if (s == null) return fallback;
        return switch (s.strip().toLowerCase()) {
            case "read" -> READ;
            case "control" -> CONTROL;
            default -> fallback;
        };
    }
}
