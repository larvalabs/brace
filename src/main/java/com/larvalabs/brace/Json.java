package com.larvalabs.brace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class Json extends Result {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Json(int status, String body) {
        super(status, "application/json", body);
    }

    public static Json of(Object value) {
        return of(value, 200);
    }

    public static Json of(Object value, int status) {
        try {
            return new Json(status, MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON", e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
