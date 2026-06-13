package com.larvalabs.brace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.Entity;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public class Json extends Result {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final ConcurrentHashMap.KeySetView<Class<?>, Boolean> warnedClasses =
        ConcurrentHashMap.newKeySet();

    // M6: body held as UTF-8 bytes from writeValueAsBytes — Jackson encodes once, straight to the wire.
    private Json(int status, byte[] body) {
        super(status, "application/json", body);
    }

    public static Json of(Object value) {
        return of(value, 200);
    }

    public static Json of(Object value, int status) {
        warnIfEntity(value);
        try {
            return new Json(status, MAPPER.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON", e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Build an insertion-ordered map for one-off JSON response shapes:
     * {@code Json.obj("talkId", id, "averageRating", avg)} — one line instead of a
     * LinkedHashMap-and-put block. Unlike {@code Map.of}, preserves key order and allows
     * {@code null} values ({@code "averageRating": null} is often required output).
     * For named or reused shapes, prefer a local record — it self-documents the schema.
     */
    public static java.util.Map<String, Object> obj(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                "Json.obj takes key/value pairs — got " + keysAndValues.length + " arguments");
        }
        var map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (!(keysAndValues[i] instanceof String key)) {
                throw new IllegalArgumentException(
                    "Json.obj key at position " + i + " is not a String: " + keysAndValues[i]);
            }
            map.put(key, keysAndValues[i + 1]);
        }
        return map;
    }

    /**
     * Check if the value or its first element (if a collection) is a JPA entity, and warn once
     * per class if so. This helps catch the common mistake of returning an entity (with public
     * fields like passwordHash) instead of a DTO.
     */
    private static void warnIfEntity(Object value) {
        if (value == null) return;

        Class<?> clazz = value.getClass();

        // If it's a collection, check the first element
        if (value instanceof Collection<?> col) {
            if (!col.isEmpty()) {
                Object first = col.iterator().next();
                if (first != null) {
                    clazz = first.getClass();
                } else {
                    return;
                }
            } else {
                return;
            }
        }

        // Check if the class is annotated @Entity and warn once per class
        if (clazz.isAnnotationPresent(Entity.class)) {
            if (warnedClasses.add(clazz)) {
                Log.warn("Json.of() serializing a JPA entity (" + clazz.getSimpleName() +
                    ") — public fields like passwordHash will leak to HTTP responses. " +
                    "Use a DTO or record instead (see BRACE-AGENTS.md § Responses).");
            }
        }
    }

    /**
     * Package-private accessor for tests to inspect which classes have been warned about.
     */
    static ConcurrentHashMap.KeySetView<Class<?>, Boolean> warnedClassesForTesting() {
        return warnedClasses;
    }
}
