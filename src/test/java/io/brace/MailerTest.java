package io.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailerTest {

    @Test
    void captureMode() {
        var mailer = new Mailer(null); // no SMTP = capture only
        mailer.from("noreply@test.com");

        mailer.to("user@example.com")
            .subject("Welcome!")
            .text("Hello there.")
            .send();

        assertEquals(1, mailer.sentCount());
        var email = mailer.last();
        assertEquals("user@example.com", email.to());
        assertEquals("Welcome!", email.subject());
        assertEquals("Hello there.", email.text());
        assertEquals("noreply@test.com", email.from());
    }

    @Test
    void htmlEmail() {
        var mailer = new Mailer(null);

        mailer.to("user@example.com")
            .subject("HTML Test")
            .html("<h1>Hello</h1>")
            .send();

        var email = mailer.last();
        assertEquals("<h1>Hello</h1>", email.html());
        assertNull(email.text());
    }

    @Test
    void ccSupport() {
        var mailer = new Mailer(null);

        mailer.to("user@example.com")
            .cc("manager@example.com")
            .subject("CC Test")
            .text("Hello")
            .send();

        assertEquals("manager@example.com", mailer.last().cc());
    }

    @Test
    void overrideFrom() {
        var mailer = new Mailer(null).from("default@test.com");

        mailer.to("user@example.com")
            .from("custom@test.com")
            .subject("From Test")
            .text("Hello")
            .send();

        assertEquals("custom@test.com", mailer.last().from());
    }

    @Test
    void clearCaptured() {
        var mailer = new Mailer(null);
        mailer.to("user@example.com").subject("Test").text("Hi").send();
        assertEquals(1, mailer.sentCount());
        mailer.clearCaptured();
        assertEquals(0, mailer.sentCount());
        assertNull(mailer.last());
    }

    @Test
    void multipleSends() {
        var mailer = new Mailer(null);
        mailer.to("a@test.com").subject("First").text("1").send();
        mailer.to("b@test.com").subject("Second").text("2").send();
        assertEquals(2, mailer.sentCount());
        assertEquals("Second", mailer.last().subject());
        assertEquals("First", mailer.sent().get(0).subject());
    }
}
