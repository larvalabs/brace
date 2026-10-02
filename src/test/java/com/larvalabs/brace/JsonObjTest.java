package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonObjTest {

    @Test
    void preservesInsertionOrder() throws Exception {
        var json = Json.mapper().writeValueAsString(
            Json.obj("zebra", 1, "apple", 2, "mango", 3));
        assertEquals("{\"zebra\":1,\"apple\":2,\"mango\":3}", json);
    }

    @Test
    void allowsNullValues() throws Exception {
        var json = Json.mapper().writeValueAsString(
            Json.obj("talkId", 7, "averageRating", null, "ratingCount", 0));
        assertEquals("{\"talkId\":7,\"averageRating\":null,\"ratingCount\":0}", json);
    }

    @Test
    void oddArityThrows() {
        var e = assertThrows(IllegalArgumentException.class,
            () -> Json.obj("key1", 1, "danglingKey"));
        assertTrue(e.getMessage().contains("key/value pairs"));
    }

    @Test
    void nonStringKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> Json.obj(42, "value"));
    }

    @Test
    void emptyObjIsEmptyMap() {
        assertTrue(Json.obj().isEmpty());
    }

    @Test
    void worksAsResponseBody() {
        var result = Json.of(Json.obj("count", 5));
        assertEquals(200, result.status());
        assertEquals("{\"count\":5}", result.body());
    }

    @Test
    void javaTimeValuesSerializeAsFullIso8601() {
        // The documented idiom: put java.time values in the object, never .toString() them.
        // LocalDateTime.toString() drops ":00" seconds ("2025-06-15T09:00"); Json keeps them.
        var at = java.time.LocalDateTime.of(2025, 6, 15, 9, 0);
        var result = Json.of(Json.obj(
            "at", at,
            "day", at.toLocalDate(),
            "instant", java.time.Instant.parse("2025-06-15T09:00:00Z")));
        assertEquals("{\"at\":\"2025-06-15T09:00:00\",\"day\":\"2025-06-15\","
            + "\"instant\":\"2025-06-15T09:00:00Z\"}", result.body());
        assertEquals("2025-06-15T09:00", at.toString());
    }
}
