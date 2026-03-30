package io.brace;

import io.brace.annotation.*;
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
        assertTrue(form.valid());
        assertEquals("Hello", form.value().title());
    }

    @Test
    void requiredValidation() {
        var form = FormBinder.bind(SimpleForm.class, Map.of("title", "", "body", "This is long enough content"));
        assertFalse(form.valid());
        assertFalse(form.errors("title").isEmpty());
    }

    @Test
    void minLengthValidation() {
        var form = FormBinder.bind(SimpleForm.class, Map.of("title", "Hi", "body", "short"));
        assertFalse(form.valid());
        assertFalse(form.errors("body").isEmpty());
        assertTrue(form.errors("body").get(0).contains("10"));
    }

    @Test
    void intTypeConversion() {
        var form = FormBinder.bind(TypedForm.class, Map.of("name", "Alice", "age", "25", "email", "alice@example.com"));
        assertTrue(form.valid());
        assertEquals(25, form.value().age());
    }

    @Test
    void minMaxValidation() {
        var form = FormBinder.bind(TypedForm.class, Map.of("name", "Alice", "age", "0", "email", "alice@example.com"));
        assertFalse(form.valid());
        assertFalse(form.errors("age").isEmpty());
    }

    @Test
    void emailValidation() {
        var form = FormBinder.bind(TypedForm.class, Map.of("name", "Alice", "age", "25", "email", "notanemail"));
        assertFalse(form.valid());
        assertFalse(form.errors("email").isEmpty());
    }

    @Test
    void inValidation() {
        var form = FormBinder.bind(InForm.class, Map.of("status", "invalid"));
        assertFalse(form.valid());
        assertFalse(form.errors("status").isEmpty());

        var valid = FormBinder.bind(InForm.class, Map.of("status", "draft"));
        assertTrue(valid.valid());
    }

    @Test
    void customValidation() {
        var form = FormBinder.bind(CustomValidationForm.class,
            Map.of("password", "secret", "passwordConfirm", "different"));
        assertFalse(form.valid());
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
        assertFalse(form.valid());
        assertFalse(form.errors("title").isEmpty());
    }

    @Test
    void requestFormMethod() {
        var req = new Request("POST", "/posts", Map.of(), Map.of(),
            Map.of(), "title=Hello&body=This+is+long+enough+content");
        var form = req.form(SimpleForm.class);
        assertTrue(form.valid());
        assertEquals("Hello", form.value().title());
        assertEquals("This is long enough content", form.value().body());
    }
}
