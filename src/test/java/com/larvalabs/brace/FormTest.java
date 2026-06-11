package com.larvalabs.brace;

import com.larvalabs.brace.annotation.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FormTest {

    public record SimpleForm(
        @Required String title,
        @Required @MinLength(10) String body
    ) {}

    public record TypedForm(
        @Required String name,
        @Min(1) @Max(100) int age,
        @Email String email
    ) {}

    public record InForm(
        @In({"draft", "published"}) String status
    ) {}

    public enum Status { DRAFT, PUBLISHED }

    public record RichTypesForm(
        Status status,
        java.time.LocalDate startDate,
        java.time.Instant publishedAt,
        java.math.BigDecimal price
    ) {}

    public record CustomValidationForm(
        @Required String password,
        @Required String passwordConfirm
    ) {
        public void validate(Errors errors) {
            if (password != null && !password.equals(passwordConfirm)) {
                errors.add("passwordConfirm", "must match password");
            }
        }
    }

    @Test
    void validSimpleForm() {
        var form = FormBinder.bind(SimpleForm.class, Map.of("title", "Hello", "body", "This is long enough content"));
        assertFalse(form.hasErrors());
        assertEquals("Hello", form.value().title());
    }

    @Test
    void requiredValidation() {
        var form = FormBinder.bind(SimpleForm.class, Map.of("title", "", "body", "This is long enough content"));
        assertTrue(form.hasErrors());
        assertFalse(form.errors("title").isEmpty());
    }

    @Test
    void minLengthValidation() {
        var form = FormBinder.bind(SimpleForm.class, Map.of("title", "Hi", "body", "short"));
        assertTrue(form.hasErrors());
        assertFalse(form.errors("body").isEmpty());
        assertTrue(form.errors("body").get(0).contains("10"));
    }

    @Test
    void intTypeConversion() {
        var form = FormBinder.bind(TypedForm.class, Map.of("name", "Alice", "age", "25", "email", "alice@example.com"));
        assertFalse(form.hasErrors());
        assertEquals(25, form.value().age());
    }

    @Test
    void minMaxValidation() {
        var form = FormBinder.bind(TypedForm.class, Map.of("name", "Alice", "age", "0", "email", "alice@example.com"));
        assertTrue(form.hasErrors());
        assertFalse(form.errors("age").isEmpty());
    }

    @Test
    void emailValidation() {
        var form = FormBinder.bind(TypedForm.class, Map.of("name", "Alice", "age", "25", "email", "notanemail"));
        assertTrue(form.hasErrors());
        assertFalse(form.errors("email").isEmpty());
    }

    @Test
    void inValidation() {
        var form = FormBinder.bind(InForm.class, Map.of("status", "invalid"));
        assertTrue(form.hasErrors());
        assertFalse(form.errors("status").isEmpty());

        var valid = FormBinder.bind(InForm.class, Map.of("status", "draft"));
        assertFalse(valid.hasErrors());
    }

    @Test
    void customValidation() {
        var form = FormBinder.bind(CustomValidationForm.class,
            Map.of("password", "secret", "passwordConfirm", "different"));
        assertTrue(form.hasErrors());
        assertTrue(form.errors("passwordConfirm").get(0).contains("match"));
    }

    @Test
    void rawValues() {
        var form = FormBinder.bind(SimpleForm.class, Map.of("title", "", "body", "short"));
        assertEquals("", form.raw("title"));
        assertEquals("short", form.raw("body"));
    }

    @Test
    void missingFieldTreatedAsEmpty() {
        var form = FormBinder.bind(SimpleForm.class, Map.of("body", "This is long enough content"));
        assertTrue(form.hasErrors());
        assertFalse(form.errors("title").isEmpty());
    }

    @Test
    void requestFormMethod() {
        var req = new Request("POST", "/posts", Map.of(), Map.of(),
            Map.of(), "title=Hello&body=This+is+long+enough+content");
        var form = req.form(SimpleForm.class);
        assertFalse(form.hasErrors());
        assertEquals("Hello", form.value().title());
        assertEquals("This is long enough content", form.value().body());
    }

    @Test
    void richTypesBindFromStrings() {
        var form = FormBinder.bind(RichTypesForm.class, Map.of(
            "status", "PUBLISHED",
            "startDate", "2026-06-11",
            "publishedAt", "2026-06-11T12:00:00Z",
            "price", "19.99"));
        assertFalse(form.hasErrors());
        assertEquals(Status.PUBLISHED, form.value().status());
        assertEquals(java.time.LocalDate.of(2026, 6, 11), form.value().startDate());
        assertEquals(java.time.Instant.parse("2026-06-11T12:00:00Z"), form.value().publishedAt());
        assertEquals(new java.math.BigDecimal("19.99"), form.value().price());
    }

    @Test
    void invalidEnumIsFieldErrorListingConstants() {
        var form = FormBinder.bind(RichTypesForm.class, Map.of("status", "bogus"));
        assertTrue(form.hasErrors());
        assertEquals("must be one of: DRAFT, PUBLISHED", form.errors("status").get(0));
    }

    @Test
    void invalidDateAndTimestampAndDecimalAreFieldErrorsNot500() {
        var form = FormBinder.bind(RichTypesForm.class, Map.of(
            "startDate", "junk",
            "publishedAt", "junk",
            "price", "junk"));
        assertTrue(form.hasErrors());
        assertTrue(form.errors("startDate").get(0).contains("date"));
        assertTrue(form.errors("publishedAt").get(0).contains("ISO-8601"));
        assertEquals("invalid number", form.errors("price").get(0));
    }

    @Test
    void emptyRichTypesAreNull() {
        var form = FormBinder.bind(RichTypesForm.class, Map.of());
        assertFalse(form.hasErrors());
        assertNull(form.value().status());
        assertNull(form.value().startDate());
        assertNull(form.value().price());
    }
}
