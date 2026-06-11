package com.larvalabs.brace;

import com.larvalabs.brace.annotation.*;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonFormTest {

    public record TalkForm(
        @Required String title,
        @Min(1) @Max(480) int durationMinutes,
        @Email String contactEmail
    ) {
        public void validate(Errors errors) {
            if (title != null && title.startsWith("x")) {
                errors.add("title", "must not start with x");
            }
        }
    }

    private static Request jsonRequest(String body) {
        return new Request("POST", "/talks", Map.of(), Map.of(),
            Map.of("content-type", "application/json"), body, Map.of(), "127.0.0.1", null);
    }

    @Test
    void validJsonBindsAndValidates() {
        var form = jsonRequest("""
            {"title": "Virtual Threads", "durationMinutes": 45, "contactEmail": "a@b.com"}
            """).jsonForm(TalkForm.class);
        assertFalse(form.hasErrors());
        assertEquals("Virtual Threads", form.value().title());
        assertEquals(45, form.value().durationMinutes());
    }

    @Test
    void numericCoercionFromJsonNumberAndString() {
        var fromNumber = jsonRequest("{\"title\": \"T\", \"durationMinutes\": 30}").jsonForm(TalkForm.class);
        assertEquals(30, fromNumber.value().durationMinutes());

        var fromString = jsonRequest("{\"title\": \"T\", \"durationMinutes\": \"30\"}").jsonForm(TalkForm.class);
        assertEquals(30, fromString.value().durationMinutes());
    }

    @Test
    void missingRequiredFieldHasErrors() {
        var form = jsonRequest("{\"durationMinutes\": 30}").jsonForm(TalkForm.class);
        assertTrue(form.hasErrors());
        assertFalse(form.errors("title").isEmpty());
    }

    @Test
    void annotationValidationRuns() {
        var form = jsonRequest("""
            {"title": "T", "durationMinutes": 9999, "contactEmail": "not-an-email"}
            """).jsonForm(TalkForm.class);
        assertTrue(form.hasErrors());
        assertFalse(form.errors("durationMinutes").isEmpty());
        assertFalse(form.errors("contactEmail").isEmpty());
    }

    @Test
    void customValidateRuns() {
        var form = jsonRequest("{\"title\": \"xyz\", \"durationMinutes\": 30}").jsonForm(TalkForm.class);
        assertTrue(form.hasErrors());
        assertEquals("must not start with x", form.errors("title").get(0));
    }

    @Test
    void malformedJsonYieldsBodyErrorNotException() {
        var form = jsonRequest("{not json at all").jsonForm(TalkForm.class);
        assertTrue(form.hasErrors());
        assertFalse(form.errors("_body").isEmpty());
        assertNotNull(form.value());   // defaults-populated record, same contract as form()
    }

    @Test
    void nonObjectBodiesYieldBodyError() {
        assertTrue(jsonRequest("[1, 2, 3]").jsonForm(TalkForm.class).errors("_body").size() > 0);
        assertTrue(jsonRequest("\"hello\"").jsonForm(TalkForm.class).errors("_body").size() > 0);
        assertTrue(jsonRequest("").jsonForm(TalkForm.class).errors("_body").size() > 0);
        assertTrue(jsonRequest(null).jsonForm(TalkForm.class).errors("_body").size() > 0);
    }

    @Test
    void jsonNullTreatedAsAbsent() {
        var form = jsonRequest("{\"title\": null, \"durationMinutes\": 30}").jsonForm(TalkForm.class);
        assertTrue(form.hasErrors());
        assertFalse(form.errors("title").isEmpty());
    }

    @Test
    void nestedValuesBindAsRawJsonToStringComponents() {
        record MetaForm(@Required String meta) {}
        var form = jsonRequest("{\"meta\": {\"a\": 1}}").jsonForm(MetaForm.class);
        assertFalse(form.hasErrors());
        assertEquals("{\"a\":1}", form.value().meta());
    }

    @Test
    void malformedJsonReturns422NotFiveHundredEndToEnd() throws Exception {
        var testApp = Brace.test().start(app ->
            app.post("/talks", req -> {
                var form = req.jsonForm(TalkForm.class);
                if (form.hasErrors()) return Result.json(Map.of("errors", form.allErrors()), 422);
                return Result.json(Map.of("ok", form.value().title()), 201);
            }));
        try {
            // postJson(Object) re-serializes its argument, so send the truly malformed
            // body over a raw HTTP request.
            var client = java.net.http.HttpClient.newHttpClient();
            var raw = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + testApp.port() + "/talks"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{broken"))
                .build();
            var bad = client.send(raw, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(422, bad.statusCode());
            assertTrue(bad.body().contains("_body"));

            var good = testApp.postJson("/talks", Map.of("title", "T", "durationMinutes", 30));
            assertEquals(201, good.status());
        } finally {
            testApp.stop();
        }
    }
}
