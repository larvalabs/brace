package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MailerTest {

    // Jakarta Mail defaults every SMTP timeout to infinite, so a blackholed relay would hang
    // Transport.send forever — and with it the DurableJob execution slot and pooled DB connection
    // the send is holding. These assert the bound exists rather than standing up an SMTP server.

    @Test
    void smtpTimeoutsAreBoundedByDefault() {
        var props = new Mailer("smtp://mail.example.com:587")
            .smtpProperties("mail.example.com", 587, "smtp");

        assertEquals("10000", props.get("mail.smtp.connectiontimeout"));
        assertEquals("30000", props.get("mail.smtp.timeout"));
        assertEquals("30000", props.get("mail.smtp.writetimeout"));
    }

    @Test
    void smtpTimeoutsAreOverridable() {
        var props = new Mailer("smtp://mail.example.com:587")
            .connectTimeout(Duration.ofSeconds(3))
            .timeout(Duration.ofSeconds(90))
            .smtpProperties("mail.example.com", 587, "smtp");

        assertEquals("3000", props.get("mail.smtp.connectiontimeout"));
        assertEquals("90000", props.get("mail.smtp.timeout"));
        assertEquals("90000", props.get("mail.smtp.writetimeout"));
    }

    @Test
    void smtpsStillGetsTimeoutsAndTls() {
        var props = new Mailer("smtps://mail.example.com:465")
            .smtpProperties("mail.example.com", 465, "smtps");

        assertEquals("true", props.get("mail.smtp.ssl.enable"));
        assertNull(props.get("mail.smtp.starttls.enable"));
        assertEquals("10000", props.get("mail.smtp.connectiontimeout"));
    }

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

    @Test
    void failCountStartsAtZero() {
        var mailer = new Mailer(null);
        assertEquals(0, mailer.failCount());
    }

    @Test
    void failCountIncrementsOnSmtpError() {
        // Use an invalid SMTP URL so sendSmtp will fail
        var mailer = new Mailer("smtp://invalid:25");
        try {
            mailer.to("user@example.com").subject("Test").text("Hi").send();
        } catch (RuntimeException e) {
            // expected
        }
        assertEquals(1, mailer.failCount());
        // A failed send is not a sent email: it counts in failCount only, and SMTP
        // mode captures nothing (capture is dev-only).
        assertEquals(0, mailer.sentCount());
        assertTrue(mailer.sent().isEmpty());
    }

    @Test
    void sendAsyncCapturesInDevMode() throws Exception {
        var mailer = new Mailer(null);
        mailer.to("user@example.com").subject("Async").text("Hi").sendAsync();
        awaitCount(() -> mailer.sentCount() == 1);
        assertEquals("Async", mailer.last().subject());
        assertEquals(0, mailer.failCount());
    }

    @Test
    void sendAsyncDoesNotThrowOnSmtpError() throws Exception {
        var mailer = new Mailer("smtp://invalid:25");
        mailer.to("user@example.com").subject("Async").text("Hi").sendAsync(); // must not throw
        awaitCount(() -> mailer.failCount() == 1);
        assertEquals(0, mailer.sentCount());
    }

    private static void awaitCount(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("condition not met within 10s");
            Thread.sleep(10);
        }
    }

    @Test
    void captureIsBoundedDropOldest() {
        var mailer = new Mailer(null);
        for (int i = 1; i <= Mailer.CAPTURE_LIMIT + 1; i++) {
            mailer.to("user@example.com").subject("msg-" + i).text("body").send();
        }
        assertEquals(Mailer.CAPTURE_LIMIT + 1, mailer.sentCount(), "counter keeps counting past the cap");
        assertEquals(Mailer.CAPTURE_LIMIT, mailer.sent().size(), "capture stays bounded");
        assertEquals("msg-" + (Mailer.CAPTURE_LIMIT + 1), mailer.last().subject(), "newest kept");
        assertEquals("msg-2", mailer.sent().get(0).subject(), "oldest dropped");
    }

    @Test
    void drainFailCountResetsCounter() {
        var mailer = new Mailer("smtp://invalid:25");
        try {
            mailer.to("user@example.com").subject("Test").text("Hi").send();
        } catch (RuntimeException e) {
            // expected
        }
        assertEquals(1, mailer.drainFailCount());
        assertEquals(0, mailer.failCount());
    }
}
