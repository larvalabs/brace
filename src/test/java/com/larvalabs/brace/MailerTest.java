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
    void smtpRequiresStarttls() {
        var props = new Mailer("smtp://mail.example.com:587")
            .smtpProperties("mail.example.com", 587, "smtp");
        assertEquals("true", props.get("mail.smtp.starttls.enable"));
        assertEquals("true", props.get("mail.smtp.starttls.required"));
    }

    @Test
    void smtpPlainOptsOutOfRequiredStarttls() {
        var props = new Mailer("smtp+plain://localhost:25")
            .smtpProperties("localhost", 25, "smtp+plain");
        assertEquals("true", props.get("mail.smtp.starttls.enable"));
        assertNull(props.get("mail.smtp.starttls.required"));
    }

    @Test
    void smtpsDoesNotSetStarttls() {
        var props = new Mailer("smtps://mail.example.com:465")
            .smtpProperties("mail.example.com", 465, "smtps");
        assertNull(props.get("mail.smtp.starttls.required"));
    }

    // --- M9 review fixes ---

    @Test
    void schemeIsComparedCaseInsensitively() {
        var smtps = new Mailer("SMTPS://mail.example.com:465").smtpProperties("mail.example.com", 465, "SMTPS");
        assertEquals("true", smtps.get("mail.smtp.ssl.enable"), "SMTPS:// is implicit TLS, not STARTTLS");
        assertNull(smtps.get("mail.smtp.starttls.required"));

        var plain = new Mailer("SMTP+Plain://localhost:25").smtpProperties("localhost", 25, "SMTP+Plain");
        assertNull(plain.get("mail.smtp.starttls.required"));

        var smtp = new Mailer("SMTP://mail.example.com:587").smtpProperties("mail.example.com", 587, "SMTP");
        assertEquals("true", smtp.get("mail.smtp.starttls.required"));
    }

    @Test
    void credentialsOnAPlainUrlWarnOnceWithoutTheCredentials() {
        LogTap.clear();
        new Mailer("smtp+plain://mailuser:hunter2-secret@relay.internal:25");
        var warnings = mailerWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("smtp+plain"), warnings.get(0));
        assertFalse(warnings.get(0).contains("hunter2-secret"), "never log the password");
        assertFalse(warnings.get(0).contains("mailuser"), "nor the user name");

        LogTap.clear();
        new Mailer("SMTP+PLAIN://mailuser:pw@relay.internal:25");
        assertEquals(1, mailerWarnings().size(), "case-insensitive scheme");

        LogTap.clear();
        new Mailer("smtp+plain://relay.internal:25");
        new Mailer("smtp://mailuser:pw@mail.example.com:587");
        new Mailer("smtps://mailuser:pw@mail.example.com:465");
        new Mailer(null);
        assertEquals(java.util.List.of(), mailerWarnings(), "no credentials, or TLS: no warning");
    }

    private static java.util.List<String> mailerWarnings() {
        return LogTap.snapshot().stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .map(e -> String.valueOf(e.fields().get("message")))
            .filter(m -> m.contains("cleartext"))
            .toList();
    }

    @Test
    void captureModeUnaffectedBySchemeRules() {
        var mailer = new Mailer(null);
        mailer.to("user@example.com").subject("s").text("t").send();
        assertEquals(1, mailer.sentCount());
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
    void toRejectsRecipientLists() {
        var mailer = new Mailer(null);
        for (String bad : new String[] {"a@b.c, victim@x.y", "a@b.c;victim@x.y", "a@b.c\r\nBcc: v@x.y"}) {
            assertThrows(IllegalArgumentException.class,
                () -> mailer.to(bad).subject("s").text("t").send(), bad);
        }
        assertEquals(0, mailer.sentCount());
        mailer.to("Name <ok@example.com>").subject("s").text("t").send();
        assertEquals(1, mailer.sentCount());
    }

    @Test
    void singleAddressIsNotExpandedToTwoRecipients() {
        assertThrows(IllegalArgumentException.class, () -> Mailer.singleAddress("a@b.c, victim@x.y"));
        assertEquals("a@b.c", Mailer.singleAddress("a@b.c").getAddress());
    }

    // --- L14 review fixes ---

    @Test
    void aQuotedDisplayNameWithACommaIsOneAddress() {
        var mailer = new Mailer(null);
        mailer.to("\"Doe, J\" <a@b.c>").subject("s").text("t").send();
        assertEquals(1, mailer.sentCount());
        assertEquals("a@b.c", Mailer.singleAddress("\"Doe; J\" <a@b.c>").getAddress());
    }

    @Test
    void groupsRoutesAndListsAreRefused() {
        for (String bad : new String[] {"g: a@b.c;", "undisclosed: a@b.c, d@e.f;", "g:;",
                "<@relay.example:a@b.c>", "a@b.c, d@e.f", "a@b.c d@e.f", "", "a@b.c\n", "\"x\r\n\" <a@b.c>"}) {
            assertThrows(IllegalArgumentException.class, () -> Mailer.singleAddress(bad), bad);
        }
    }

    @Test
    void ccIsASingleAddressLikeTo() {
        var mailer = new Mailer(null);
        for (String bad : new String[] {"a@b.c, victim@x.y", "g: a@b.c, victim@x.y;"}) {
            assertThrows(IllegalArgumentException.class,
                () -> mailer.to("user@example.com").cc(bad).subject("s").text("t").send(), bad);
        }
        assertEquals(0, mailer.sentCount());
        mailer.to("user@example.com").cc("\"Boss, The\" <boss@example.com>").subject("s").text("t").send();
        assertEquals(1, mailer.sentCount());
    }

    @Test
    void fromAndReplyToAreExactlyOneAddress() {
        assertThrows(IllegalArgumentException.class, () -> new Mailer(null).from("a@b.c, x@y.z"));
        assertThrows(IllegalArgumentException.class, () -> new Mailer(null).replyTo("a@b.c, x@y.z"));
        assertThrows(IllegalArgumentException.class, () -> new Mailer(null).replyTo("g: a@b.c;"));
        var mailer = new Mailer(null).from("App <noreply@example.com>").replyTo("support@example.com");
        assertThrows(IllegalArgumentException.class,
            () -> mailer.to("user@example.com").from("a@b.c, x@y.z").subject("s").text("t").send());
        assertEquals(0, mailer.sentCount());
        mailer.to("user@example.com").from("\"Team, Ops\" <ops@example.com>").subject("s").text("t").send();
        assertEquals(1, mailer.sentCount());
    }

    @Test
    void mailerNotifierSendsToEachOfSeveralRecipients() throws Exception {
        var regression = new RegressionTracker.Regression("id", "java.lang.Boom", "GET /x", "m",
            java.time.Instant.EPOCH, 1, null);

        var mailer = new Mailer(null);
        new MailerNotifier(mailer, "ops@example.com, \"On Call, Team\" <oncall@example.com>").notifyRegression(regression);
        awaitCount(() -> mailer.sentCount() == 2);
        var to = mailer.sent().stream().map(Mailer.CapturedEmail::to).sorted().toList();
        assertEquals(2, to.size(), to.toString());
        assertTrue(to.get(0).contains("oncall@example.com"), to.toString());
        assertEquals("ops@example.com", to.get(1));

        var varargs = new Mailer(null);
        new MailerNotifier(varargs, "a@example.com", "b@example.com").notifyRegression(regression);
        awaitCount(() -> varargs.sentCount() == 2);

        var list = new Mailer(null);
        new MailerNotifier(list, java.util.List.of("a@example.com", "b@example.com", "c@example.com")).notifyRegression(regression);
        awaitCount(() -> list.sentCount() == 3);
    }

    @Test
    void oneFailingRecipientDoesNotStopTheOthers() throws Exception {
        var regression = new RegressionTracker.Regression("id", "T", "GET /x", "m", java.time.Instant.EPOCH, 1, null);
        var mailer = new Mailer(null) {
            @Override public EmailBuilder to(String address) {
                if (address.startsWith("down@")) throw new RuntimeException("relay refused");
                return super.to(address);
            }
        };
        new MailerNotifier(mailer, "a@example.com", "down@example.com", "b@example.com").notifyRegression(regression);
        awaitCount(() -> mailer.sentCount() == 2);
        assertEquals(java.util.List.of("a@example.com", "b@example.com"),
            mailer.sent().stream().map(Mailer.CapturedEmail::to).toList());
    }

    @Test
    void mailerNotifierRefusesAnInvalidRecipientAtConstruction() {
        var mailer = new Mailer(null);
        assertThrows(IllegalArgumentException.class, () -> new MailerNotifier(mailer, "ops@example.com, not an address"));
        assertThrows(IllegalArgumentException.class, () -> new MailerNotifier(mailer, "ops@example.com", "g: a@b.c;"));
        assertThrows(IllegalArgumentException.class, () -> new MailerNotifier(mailer, java.util.List.of("ok@example.com", "bad@")));
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
