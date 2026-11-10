package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedactorTest {

    @Test
    void isSensitiveMatchesCommonCredentialNames() {
        assertTrue(Redactor.isSensitive("Authorization"));
        assertTrue(Redactor.isSensitive("Cookie"));
        assertTrue(Redactor.isSensitive("x-api-key"));
        assertTrue(Redactor.isSensitive("api_key"));
        assertTrue(Redactor.isSensitive("password"));
        assertTrue(Redactor.isSensitive("sessionId"));
        assertTrue(Redactor.isSensitive("csrf-token"));
        assertTrue(Redactor.isSensitive("X-Auth-Token"));
    }

    @Test
    void isSensitiveLeavesOrdinaryNamesAlone() {
        assertFalse(Redactor.isSensitive("username"));
        assertFalse(Redactor.isSensitive("count"));
        assertFalse(Redactor.isSensitive("path"));
        assertFalse(Redactor.isSensitive("author"));
        assertFalse(Redactor.isSensitive("monkey"));
        assertFalse(Redactor.isSensitive(null));
    }

    @Test
    void redactReplacesSensitiveValuesOnly() {
        var redacted = Redactor.redact(Map.of("password", "hunter2", "username", "bob"));
        assertEquals(Redactor.PLACEHOLDER, redacted.get("password"));
        assertEquals("bob", redacted.get("username"));
    }

    @Test
    void redactRecursesIntoMapsAndLists() {
        var nested = Map.<String, Object>of(
            "user", "bob",
            "headers", Map.of("authorization", "Bearer abc", "accept", "json"),
            "events", List.of(Map.of("token", "xyz", "kind", "login")));
        var redacted = Redactor.redact(nested);

        @SuppressWarnings("unchecked")
        var headers = (Map<String, Object>) redacted.get("headers");
        assertEquals(Redactor.PLACEHOLDER, headers.get("authorization"));
        assertEquals("json", headers.get("accept"));

        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) redacted.get("events");
        assertEquals(Redactor.PLACEHOLDER, events.get(0).get("token"));
        assertEquals("login", events.get(0).get("kind"));
    }

    @Test
    void redactQueryRedactsSensitiveParams() {
        assertEquals("a=1&token=[REDACTED]&b=2", Redactor.redactQuery("a=1&token=xyz&b=2"));
        assertEquals("password=[REDACTED]", Redactor.redactQuery("password=hunter2"));
        assertEquals("q=hello", Redactor.redactQuery("q=hello"));
        assertEquals("flag", Redactor.redactQuery("flag"));
        assertNull(Redactor.redactQuery(null));
    }

    @Test
    void logEntriesAreRedactedInTheRingBuffer() {
        // /ops/logs serves LogTap.snapshot(), so redaction at the log choke point protects it.
        LogTap.clear();
        Log.info("login attempt", Map.of("password", "secret123", "user", "bob"));
        var entry = LogTap.snapshot().stream()
            .filter(e -> "login attempt".equals(e.fields().get("message")))
            .findFirst().orElseThrow();
        assertEquals(Redactor.PLACEHOLDER, entry.fields().get("password"));
        assertEquals("bob", entry.fields().get("user"));
    }

    // ---- redactPath tests ----

    @Test
    void redactPathLeaves32HexTokenRedacted() {
        // 32-char hex token with mixed letters+digits — should be redacted
        String path = "/password-reset/a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String result = Redactor.redactPath(path);
        assertEquals("/password-reset/[redacted]", result);
    }

    @Test
    void redactPathLeavesNumericIdUntouched() {
        assertEquals("/users/42/profile", Redactor.redactPath("/users/42/profile"));
    }

    @Test
    void redactPathLeavesShortSlugUntouched() {
        assertEquals("/blog/hello-world", Redactor.redactPath("/blog/hello-world"));
    }

    @Test
    void redactPathLeavesUuidUntouched() {
        // UUIDs are identifiers, not secrets — needed for debugging
        String path = "/items/550e8400-e29b-41d4-a716-446655440000";
        assertEquals(path, Redactor.redactPath(path));
    }

    @Test
    void redactPathRedactsJwtShapedSegment() {
        // JWT: header.payload.signature
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String path = "/api/" + jwt;
        String result = Redactor.redactPath(path);
        assertEquals("/api/[redacted]", result);
    }

    @Test
    void redactPathHandlesRootAndEmptyPath() {
        assertEquals("/", Redactor.redactPath("/"));
        assertNull(Redactor.redactPath(null));
        assertEquals("", Redactor.redactPath(""));
    }

    @Test
    void redactPathRedactsBase64urlToken() {
        // 24-char base64url token (invite token style): mixed letters+digits
        String path = "/invite/abc123DEF456ghi789JKL0";
        assertEquals("/invite/[redacted]", Redactor.redactPath(path));
    }

    @Test
    void redactPathLeavesNormalWordSegmentsUntouched() {
        assertEquals("/admin/dashboard", Redactor.redactPath("/admin/dashboard"));
        assertEquals("/health", Redactor.redactPath("/health"));
    }

    // ---- redactMessage tests ----

    @Test
    void redactMessageRedactsBearerTokenInMessage() {
        // Simulated exception message containing a long token
        String token = "a3f9bc2d8ef14a5b6c7d8e9f01234567";   // 32-char hex
        String message = "Failed to validate token: " + token;
        String result = Redactor.redactMessage(message);
        assertFalse(result.contains(token), "token should be redacted");
        assertTrue(result.contains("[redacted]"));
        assertTrue(result.contains("Failed"));
    }

    @Test
    void redactMessageLeavesNormalMessageUntouched() {
        String message = "User not found for email: bob@example.com";
        // No high-entropy token here — email address contains dots and @ which are
        // outside the base64url charset, so it won't match the heuristic.
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void redactMessageRedactsJwtInMessage() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String message = "Invalid JWT: " + jwt;
        String result = Redactor.redactMessage(message);
        assertFalse(result.contains(jwt), "JWT should be redacted from message");
        assertTrue(result.contains("[redacted]"));
    }

    @Test
    void redactMessageHandlesNullAndEmpty() {
        assertNull(Redactor.redactMessage(null));
        assertEquals("", Redactor.redactMessage(""));
    }

    @Test
    void redactMessageLeavesShortTokensUntouched() {
        String message = "NullPointerException: field was null";
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void redactMessageKeepsPunctuationWhenNothingIsRedacted() {
        // "IllegalArgumentException" is long enough to be a candidate, but isn't secret-shaped
        // (no digit). The message used to come back rebuilt with single spaces, losing the
        // parentheses, commas and colon: "placeholder s but 1 path argument s".
        String message = "Too many params for pattern: /catalog/list — it has 0 placeholder(s) but "
            + "1 path argument(s) were passed. Pass Url.query(name, value, ...) instead "
            + "(IllegalArgumentException).";
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void redactMessageReplacesOnlyTheSecretAndKeepsTheRest() {
        String token = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        assertEquals("Bad token (key=[redacted], user: bob).",
            Redactor.redactMessage("Bad token (key=" + token + ", user: bob)."));
        assertEquals("  [redacted]\n[redacted]:", Redactor.redactMessage("  " + token + "\n" + token + ":"));
    }

    // ---- isSecretShaped tests ----

    @Test
    void isSecretShapedReturnsTrueForLongMixedToken() {
        assertTrue(Redactor.isSecretShaped("a3f9bc2d8ef14a5b6c7d8e9f01234567"));
    }

    @Test
    void isSecretShapedReturnsFalseForNumericId() {
        assertFalse(Redactor.isSecretShaped("1234567890123456")); // all digits
    }

    @Test
    void isSecretShapedReturnsFalseForPurelyAlphaSlug() {
        assertFalse(Redactor.isSecretShaped("thisisaverylongslugwithnumbers")); // no digits
        // actually has no digits so should return false
    }

    @Test
    void isSecretShapedReturnsFalseForUuid() {
        assertFalse(Redactor.isSecretShaped("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void isSecretShapedReturnsTrueForJwt() {
        assertTrue(Redactor.isSecretShaped(
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"));
    }

    @Test
    void isSecretShapedReturnsFalseForShortToken() {
        assertFalse(Redactor.isSecretShaped("abc123")); // below MIN_SECRET_LENGTH
    }

    // ---- redactStackTrace tests ----

    private static String traceOf(Throwable t) {
        var sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    @Test
    void redactStackTraceRedactsEveryMessageLine() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String other = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
        var suppressed = new IllegalStateException("suppressed key " + other);
        var root = new java.sql.SQLException("connect failed password=" + secret);
        var top = new RuntimeException("outer", new RuntimeException("middle", root));
        top.addSuppressed(suppressed);

        String trace = traceOf(top);
        assertTrue(trace.contains(secret) && trace.contains(other), "precondition: raw trace has both");

        String redacted = Redactor.redactStackTrace(trace);
        assertFalse(redacted.contains(secret), "Caused by: message must be redacted");
        assertFalse(redacted.contains(other), "Suppressed: message must be redacted");
        // password= is a sensitive name, so its value is replaced by name (upper case placeholder).
        assertTrue(redacted.contains("Caused by: java.sql.SQLException: connect failed password=[REDACTED]"), redacted);
        assertTrue(redacted.contains("RuntimeException: outer"));
    }

    @Test
    void redactStackTraceRedactsTheFirstLine() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String redacted = Redactor.redactStackTrace(traceOf(new RuntimeException("bad token " + secret)));
        assertFalse(redacted.contains(secret));
        assertTrue(redacted.startsWith("java.lang.RuntimeException: bad token [redacted]"), redacted);
    }

    @Test
    void redactStackTraceRedactsContinuationLinesOfAMultiLineMessage() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String redacted = Redactor.redactStackTrace(
            traceOf(new RuntimeException("line one\nsecond line " + secret + "\nthird")));
        assertFalse(redacted.contains(secret));
        assertTrue(redacted.contains("line one\nsecond line [redacted]\nthird"), redacted);
    }

    @Test
    void redactStackTraceLeavesFrameLinesUntouched() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String trace = "java.lang.RuntimeException: boom " + secret + "\n"
            + "\tat com.example.Foo.bar(Foo.java:12)\n"
            + "\tat com.example.Foo.longMethodName0123456789abcdef(Foo.java:99)\n"
            + "Caused by: java.lang.Error: root " + secret + "\n"
            + "\tat com.example.Baz.qux(Baz.java:7)\n"
            + "\t... 3 more\n";
        assertEquals(trace.replace(secret, "[redacted]"), Redactor.redactStackTrace(trace));
    }

    @Test
    void framesOfSuppressedExceptionsAndTheirCausesAreKept() {
        // Verification pass: printStackTrace indents the frames of a Suppressed: exception (and of
        // the causes nested under it) with two or more tabs; only "\tat " counted as a frame, so
        // those frames got the run rule: Http2ConnectionHandlerImpl.java became [redacted].java.
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String other = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
        var top = new RuntimeException("outer");
        var suppressed = new java.io.IOException("close failed " + secret,
            new IllegalStateException("cause under suppressed " + other));
        StackTraceElement[] frames = {
            new StackTraceElement("com.example.net.Http2ConnectionHandlerImpl", "run",
                "Http2ConnectionHandlerImpl.java", 42),
            new StackTraceElement("shop.Base64UrlDecoder2Impl", "decode", "Base64UrlDecoder2Impl.java", 9),
            new StackTraceElement("com.example.Worker$$Lambda$Aa1bB2cC3dD4eE5fF6gG7", "run", null, -1)};
        suppressed.setStackTrace(frames);
        suppressed.getCause().setStackTrace(new StackTraceElement[] {frames[1], frames[0]});
        top.addSuppressed(suppressed);

        String trace = traceOf(top);
        assertTrue(trace.contains("\t\tat com.example.net.Http2ConnectionHandlerImpl.run(Http2ConnectionHandlerImpl.java:42)"),
            "precondition: suppressed frames are indented with two tabs: " + trace);
        assertTrue(trace.contains("\tCaused by: java.lang.IllegalStateException"), "precondition: " + trace);

        String redacted = Redactor.redactStackTrace(trace);
        assertFalse(redacted.contains(secret), "Suppressed: message must be redacted: " + redacted);
        assertFalse(redacted.contains(other), "nested Caused by: message must be redacted: " + redacted);
        assertTrue(redacted.contains("\tSuppressed: java.io.IOException: close failed [redacted]"), redacted);
        assertTrue(redacted.contains("\tCaused by: java.lang.IllegalStateException: cause under suppressed [redacted]"),
            redacted);
        // Every frame line, at any indent, is kept exactly.
        for (String line : trace.split("\n")) {
            String t = line.strip();
            if (t.startsWith("at ") || t.startsWith("... ")) {
                assertTrue(redacted.contains(line), "frame line changed: " + line + "\n" + redacted);
            }
        }
        // Logback's "... N common frames omitted" is a frame line too.
        String logback = "java.lang.RuntimeException: x\n\tat a.B.c(B.java:1)\n\t\t... 12 common frames omitted\n";
        assertSame(logback, Redactor.redactStackTrace(logback));
    }

    @Test
    void redactStackTraceKeepsLineEndingsAndReturnsCleanTracesUnchanged() {
        String clean = "java.lang.RuntimeException: user not found\r\n\tat a.B.c(B.java:1)\r\n";
        assertSame(clean, Redactor.redactStackTrace(clean));
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        assertEquals("java.lang.RuntimeException: x [redacted]\r\n\tat a.B.c(B.java:1)\r\n",
            Redactor.redactStackTrace("java.lang.RuntimeException: x " + secret + "\r\n\tat a.B.c(B.java:1)\r\n"));
    }

    @Test
    void redactStackTraceHandlesNullAndEmpty() {
        assertNull(Redactor.redactStackTrace(null));
        assertEquals("", Redactor.redactStackTrace(""));
    }

    // ---- M6: value-shaped redaction of error-store headers and query strings ----

    static final String HEX32 = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
    static final String RANDOM32 = "Zx81kLm02PqR7sTuV4wXy9AbCd_eF3gH";
    static final String JWT =
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTQyIiwiZXhwIjoxOTk5OTk5OTk5fQ.c2lnbmF0dXJlLWJ5dGVzLWhlcmUtMTIz";
    static final String OAUTH_CODE = "4/0AX4XfWhq8Lp3mN7vB2cD9eF1gH5jK";
    static final String OPS_TOKEN = OpsToken.create("ops-test-secret-0123456789abcdef0123", 300);

    @Test
    void isSensitiveMatchesTheM6Names() {
        for (String name : List.of("Cf-Access-Jwt-Assertion", "client_assertion", "jwt", "X-Amz-Signature",
                "signature", "X-Auth-Key", "nonce", "code", "Code", "sig", "SIG", "otp")) {
            assertTrue(Redactor.isSensitive(name), name);
        }
    }

    @Test
    void shortM6NamesMatchOnlyTheWholeName() {
        // code/sig/otp as substrings would hit ordinary words: whole normalized name only.
        for (String name : List.of("encode", "postcode", "barcode", "zip_code", "status_code", "country-code",
                "codec", "signal", "design", "assign", "sigma", "footprint", "hotpath", "otpauth_uri")) {
            assertFalse(Redactor.isSensitive(name), name);
        }
    }

    @Test
    void opsTokenAndTwoPartShapeAreSecretShaped() {
        assertTrue(Redactor.isSecretShaped(OPS_TOKEN), OPS_TOKEN);
        assertEquals("ops auth failed for [redacted]", Redactor.redactMessage("ops auth failed for " + OPS_TOKEN));
        assertTrue(Redactor.isSecretShaped("abcdefghij0123456789xy.ZYXWVUTSRQ9876543210_-"));
        // Not two-part: a side under 20, no digit, a second dot, a char outside base64url.
        for (String v : List.of("averyveryverylonghostnamelabel.example", "abcdefghij0123456789xy.short1",
                "abcdefghijklmnopqrstuvwxyz.abcdefghijklmnopqrstuvwxyz",
                "abcdefghij0123456789xy.abcdefghij0123456789xy.com", "abcdefghij01234567+9xy.abcdefghij0123456789xy",
                "app.3f9a1c2b.js", "main-3f9a1c2b4d5e6f7a8b9c0d1e.css", "release-2024.11.05-rc1")) {
            assertFalse(Redactor.isSecretShaped(v), v);
        }
    }

    /** Header values that MUST survive unredacted: false positives make the error store useless. */
    static final List<Map.Entry<String, String>> HEADERS_THAT_SURVIVE = List.of(
        Map.entry("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"),
        Map.entry("Accept-Language", "en-US,en;q=0.9,fr-CA;q=0.8"),
        Map.entry("Accept-Encoding", "gzip, deflate, br, zstd"),
        Map.entry("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.207 Safari/537.36"),
        Map.entry("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; SM-S918B Build/UP1A.231005.007)"),
        Map.entry("User-Agent", "MyApp/4.2.1 (iPhone; iOS 17.2; build 3f9a1c2b4d5e6f7a8b9c0d1e)"),
        Map.entry("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW"),
        Map.entry("Content-Type", "application/json; charset=utf-8"),
        Map.entry("Host", "my-very-long-subdomain-label-1234567890.eu-west-1.elb.amazonaws.com:8443"),
        Map.entry("X-Forwarded-For", "203.0.113.9, 2001:db8::1, 10.0.0.1"),
        Map.entry("Forwarded", "for=203.0.113.9;proto=https;host=app.example.com"),
        Map.entry("X-Request-Id", "550e8400-e29b-41d4-a716-446655440000"),
        Map.entry("X-Request-Id", "3f9a1c2b4d5e6f7a8b9c0d1e2f3a4b5c"),
        Map.entry("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"),
        Map.entry("X-Amzn-Trace-Id", "Root=1-67891233-abcdef012345678912345678"),
        Map.entry("Cf-Ray", "8a1b2c3d4e5f6a7b-IAD"),
        Map.entry("If-None-Match", "W/\"33a64df551425fcc55e4d42a148795d7f25f89d4\""),
        Map.entry("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\""),
        Map.entry("Sec-Fetch-Site", "same-origin"),
        Map.entry("Referer", "https://example.com/posts/42?page=2&sort=-date"),
        Map.entry("Referer", "https://example.com/search?q=hello+world&page=2&id=12345&email=a@b.c&lang=en-US"),
        Map.entry("Referer", "https://example.com/items/550e8400-e29b-41d4-a716-446655440000/edit"),
        Map.entry("Referer", "https://cdn.example.com/assets/app.3f9a1c2b.js"),
        // (main-<24 hex>.css is now redacted on purpose: contentHashesInFileNamesAreRedactedDeliberately)
        Map.entry("Referer", "https://example.com/docs/getting-started#installation"),
        Map.entry("Referer", "http://localhost:8080/"),
        Map.entry("Origin", "https://app.example.com"),
        Map.entry("Origin", "null"),
        Map.entry("HX-Current-URL", "https://example.com/posts/42?tab=comments"),
        Map.entry("X-App-Version", "4.12.0-beta.3+build.20241105"),
        Map.entry("X-Theme-Colors", "#ff0000, #00ff00, #0000ff, #123abc, #abcdef"),
        Map.entry("X-Feature-Flags", "new-checkout,dark-mode,beta-search"),
        Map.entry("X-Short", "dGVzdA== abc123 v2"),
        Map.entry("X-Hostname", "averyveryverylonghostnamelabel.example.com"),
        Map.entry("X-Client-Path", "/var/app/releases/current/public"),
        Map.entry("X-Return-To", "https://example.com/orders/12345?tab=items&page=2"));

    @Test
    void ordinaryHeadersSurviveUnredacted() {
        for (var h : HEADERS_THAT_SURVIVE) {
            var out = Redactor.redactHeaders(Map.of(h.getKey(), h.getValue()));
            assertEquals(h.getValue(), out.get(h.getKey()), h.getKey() + ": " + h.getValue());
        }
    }

    @Test
    void secretBearingHeadersAreRedacted() {
        String[][] cases = {
            // name-based
            {"Authorization", "Bearer abc", "[REDACTED]"},
            {"Cookie", "s=1", "[REDACTED]"},
            {"X-Auth-Key", "k3y-" + HEX32, "[REDACTED]"},
            {"Cf-Access-Jwt-Assertion", JWT, "[REDACTED]"},
            {"X-Amz-Signature", "abc", "[REDACTED]"},
            // value-shaped, under names that say nothing
            {"X-Amzn-Oidc-Data", JWT, "[redacted]"},
            {"X-Ops", OPS_TOKEN, "[redacted]"},
            {"X-Api", "v1 " + RANDOM32, "v1 [redacted]"},
            {"Sec-WebSocket-Protocol", "bearer, " + JWT, "bearer, [redacted]"},
            {"X-Callback", "https://hooks.example.com/notify/" + HEX32, "https://hooks.example.com/notify/[redacted]"},
            {"X-Callback", "see https://hooks.example.com/notify/" + HEX32, "see https://hooks.example.com/notify/[redacted]"},
            // A free-form header holding a bare path: a standard-base64 secret can start with '/',
            // so a path that is secret-shaped as a whole is replaced whole (as redactMessage does).
            {"X-Path", "/files/" + RANDOM32 + "/raw", "[redacted]"},
            {"X-Path", "/files/" + RANDOM32 + "/raw.txt", "/files/[redacted]/raw.txt"},
            // URL-valued: query by name, query by shape, path segment, fragment, userinfo
            {"Referer", "https://app.example.com/reset?token=" + HEX32 + "&lang=en",
                "https://app.example.com/reset?token=[REDACTED]&lang=en"},
            {"Referer", "https://app.example.com/reset?t=" + RANDOM32, "https://app.example.com/reset?t=[REDACTED]"},
            {"Referer", "https://app.example.com/reset/" + HEX32, "https://app.example.com/reset/[redacted]"},
            {"Referer", "https://app.example.com/cb?code=4%2F0AX4XfWh&state=xyz", "https://app.example.com/cb?code=[REDACTED]&state=xyz"},
            {"Referer", "https://app.example.com/cb#access_token=" + RANDOM32 + "&expires_in=3600",
                "https://app.example.com/cb#access_token=[REDACTED]&expires_in=3600"},
            {"Referer", "https://user:hunter2@app.example.com/x", "https://[REDACTED]@app.example.com/x"},
            {"Referer", "https://app.example.com/v?%74oken=abc", "https://app.example.com/v?%74oken=[REDACTED]"},
            {"X-Original-URL", "/reset/" + RANDOM32 + "/confirm", "/reset/[redacted]/confirm"},
            {"X-Forwarded-Uri", "/api/keys?jwt=" + JWT, "/api/keys?jwt=[REDACTED]"},
            {"Origin", HEX32 + HEX32, "[redacted]"},
        };
        for (String[] c : cases) {
            var out = Redactor.redactHeaders(Map.of(c[0], c[1]));
            assertEquals(c[2], out.get(c[0]), c[0] + ": " + c[1]);
        }
    }

    @Test
    void redactHeadersKeepsOrderAndNonStringValues() {
        var in = new java.util.LinkedHashMap<String, Object>();
        in.put("Host", "a");
        in.put("X-N", 3);
        in.put("Cookie", "c");
        var out = Redactor.redactHeaders(in);
        assertEquals(List.of("Host", "X-N", "Cookie"), List.copyOf(out.keySet()));
        assertEquals(3, out.get("X-N"));
    }

    /** Query strings that MUST come back unchanged. */
    static final List<String> QUERIES_THAT_SURVIVE = List.of(
        "q=hello+world", "page=2", "id=12345", "email=a@b.c", "sort=-date", "lang=en-US",
        "colors=ff0000,00ff00,0000ff", "colors=%23ff0000%2C%2300ff00%2C%230000ff",
        "id=550e8400-e29b-41d4-a716-446655440000", "zip_code=94107", "status_code=404", "encode=utf8",
        "postcode=SW1A1AA", "barcode=0123456789012", "country_code=US", "design=dark", "signal=on",
        "next=/posts/42", "next=https%3A%2F%2Fexample.com%2Fposts%2F42", "file=app.3f9a1c2b.js",
        "version=4.12.0", "flag", "a=1&&b=2&", "=x", "bad=%zz%", "%=1", "name=Jos%C3%A9", "emoji=%F0%9F%98%80",
        "utm_source=newsletter&utm_campaign=black-friday-sale", "short=dGVzdA%3D%3D",
        "hash=3f9a1c2b4d5e6f7", "a&a&a&a&a=1");

    @Test
    void valuesTheShapeRuleCannotTellFromSecretsAreRedactedConsistently() {
        // Judgment calls, kept consistent with redactPath: a 16+ char run of base64url/hex
        // characters with a digit and a letter is redacted wherever it appears, unless it is a
        // name made of words (namesAndFilePathsInMessagesSurvive). A dash-joined hex list looks
        // exactly like a grouped recovery code; a 16+ hex hash looks like a hex token. Short hex
        // (< 16) and comma/#-separated colour lists survive above.
        assertEquals("colors=[REDACTED]", Redactor.redactQuery("colors=ff0000-00ff00-0000ff"));
        assertEquals("hash=[REDACTED]", Redactor.redactQuery("hash=3f9a1c2b4d5e6f70"));
        // Final review costs (documented in docs/SECURITY.md, "Known over-redaction").
        assertEquals("/assets/[redacted].js", Redactor.redactPath("/assets/ProductDetail-a1B2c3D4.js"));
        assertEquals("[redacted]", Redactor.redactHeaders(Map.of("X-Path", "/files/Ab3dE9fG/raw")).get("X-Path"));
        assertEquals("ticket_id=[REDACTED]&sig_alg=[REDACTED]", Redactor.redactQuery("ticket_id=4521&sig_alg=hs256"));
        // An email is PII, not a credential: kept, as before.
        assertEquals("email=a%40b.c", Redactor.redactQuery("email=a%40b.c"));
    }

    @Test
    void ordinaryQueriesSurviveUnredacted() {
        for (String q : QUERIES_THAT_SURVIVE) {
            assertEquals(q, Redactor.redactQuery(q), q);
        }
    }

    @Test
    void secretBearingQueriesAreRedacted() {
        String encodedCode = java.net.URLEncoder.encode(OAUTH_CODE, java.nio.charset.StandardCharsets.UTF_8);
        String[][] cases = {
            {"code=" + encodedCode + "&state=xyz", "code=[REDACTED]&state=xyz"},
            {"code=" + OAUTH_CODE, "code=[REDACTED]"},
            {"t=" + RANDOM32, "t=[REDACTED]"},
            {"sig=abc&sv=2024-11-04", "sig=[REDACTED]&sv=2024-11-04"},
            {"otp=123456", "otp=[REDACTED]"},
            {"%74oken=abc", "%74oken=[REDACTED]"},
            {"%74ok%65n=abc&page=2", "%74ok%65n=[REDACTED]&page=2"},
            {"token=abc%zz", "token=[REDACTED]"},
            {"jwt=x", "jwt=[REDACTED]"},
            {"client_assertion=x&client_id=web", "client_assertion=[REDACTED]&client_id=web"},
            {"X-Amz-Signature=abc&X-Amz-Expires=300", "X-Amz-Signature=[REDACTED]&X-Amz-Expires=300"},
            {"nonce=n-0S6_WzA2Mj", "nonce=[REDACTED]"},
            {"state=" + JWT, "state=[REDACTED]"},
            {"x=" + OPS_TOKEN, "x=[REDACTED]"},
            {"next=%2Freset%2F" + HEX32, "next=[REDACTED]"},
            {"next=https%3A%2F%2Fapp.example.com%2Freset%3Ftoken%3D" + HEX32, "next=[REDACTED]"},
            {"SAMLResponse=PHNhbWxwOlJlc3BvbnNlIHhtbG5zOnNhbWxwPSJ1cm46b2FzaXM6bmFtZXM+MjAy", "SAMLResponse=[REDACTED]"},
            {HEX32, "[REDACTED]"},
            {"a=1&" + HEX32 + "&b=2", "a=1&[REDACTED]&b=2"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactQuery(c[0]), c[0]);
        }
    }

    @Test
    void lenientDecodeNeverThrows() {
        assertEquals("token", Redactor.lenientDecode("%74oken"));
        assertEquals("a b", Redactor.lenientDecode("a+b"));
        assertEquals("%zz%", Redactor.lenientDecode("%zz%"));
        assertEquals("%4", Redactor.lenientDecode("%4"));
        assertEquals("José", Redactor.lenientDecode("Jos%C3%A9"));
        assertEquals("�", Redactor.lenientDecode("%C3"));
        assertEquals("é 😀", Redactor.lenientDecode("é%20😀"));
    }

    // ---- Final review (2026-11): runs, URLs in messages, encoded and matrix path segments ----

    /** A 24-character random token, the reviewer's T. */
    static final String T = "Tt1aB2cD3eF4gH5iJ6kL7mN8";

    @Test
    void urlsInsideMessagesGoThroughTheUrlRedactor() {
        // M3: every scheme://... in a message is redacted like a Referer: path segments, query,
        // fragment, and any user:password@ (always masked, whatever its shape).
        String[][] cases = {
            {"Server returned HTTP response code: 500 for URL: https://api.partner.example/v1/reset/Uu1aB2cD3eF4gH5iJ6kL7mN8",
                "Server returned HTTP response code: 500 for URL: https://api.partner.example/v1/reset/[redacted]"},
            {"GET https://api.example.com/v1/items?api_key=Mm1aB2cD3eF4gH5iJ6kL7mN8&expand=1 failed",
                "GET https://api.example.com/v1/items?api_key=[REDACTED]&expand=1 failed"},
            {"Connection to postgres://dbuser:Ww1aB2cD3eF4gH5iJ6kL7@db.internal:5432/app refused",
                "Connection to postgres://[REDACTED]@db.internal:5432/app refused"},
            {"bad url jdbc:postgresql://db.internal:5432/app?user=app&password=hunter2",
                "bad url jdbc:postgresql://db.internal:5432/app?user=app&password=[REDACTED]"},
            {"redis://:hunter2@cache.internal:6379 unreachable", "redis://[REDACTED]@cache.internal:6379 unreachable"},
            {"amqp://guest:guest@mq:5672/vhost", "amqp://[REDACTED]@mq:5672/vhost"},
            {"smtp://mailer:pw@smtp.example.com:587 rejected AUTH", "smtp://[REDACTED]@smtp.example.com:587 rejected AUTH"},
            {"NoSuchKey s3://bucket/exports/" + T + "/data.csv", "NoSuchKey s3://bucket/exports/[redacted]/data.csv"},
            {"failed (see https://x.example/reset/" + T + ").", "failed (see https://x.example/reset/[redacted])."},
            {"callback https://x.example/cb#access_token=abc&expires_in=3600",
                "callback https://x.example/cb#access_token=[REDACTED]&expires_in=3600"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
            // and so in every message line of a stored stack trace
            assertEquals("java.io.IOException: " + c[1] + "\n\tat a.B.c(B.java:1)\n",
                Redactor.redactStackTrace("java.io.IOException: " + c[0] + "\n\tat a.B.c(B.java:1)\n"));
        }
    }

    @Test
    void credentialsInAUrlsParameterSectionAndOddPrefixesAreRedacted() {
        // Verification pass: the authority was copied verbatim unless it held '@', a URL was found
        // only at the start of a scheme-character run, and an unencoded '/', '?' or '#' in a
        // password ended the authority before the '@'.
        String[][] cases = {
            {"jdbc:sqlserver://db.internal:1433;databaseName=app;user=sa;password=hunter2 failed",
                "jdbc:sqlserver://db.internal:1433;databaseName=app;user=sa;password=[REDACTED] failed"},
            {"-jdbc:sqlserver://db:1433;password=hunter2", "-jdbc:sqlserver://db:1433;password=[REDACTED]"},
            {"GET /proxy/https://user:hunter2@host/x failed", "GET /proxy/https://[REDACTED]@host/x failed"},
            {"-https://user:hunter2@host/x", "-https://[REDACTED]@host/x"},
            {"1https://user:hunter2@host/x", "1https://[REDACTED]@host/x"},
            {"via=mongodb+srv://app:hunter2@cluster0.example.net/db", "via=mongodb+srv://[REDACTED]@cluster0.example.net/db"},
            {"postgres://user:pa/ss@host:5432/db refused", "postgres://[REDACTED]@host:5432/db refused"},
            {"postgres://user:pa?ss@host/db?sslmode=require", "postgres://[REDACTED]@host/db?sslmode=require"},
            {"redis://:p#w@cache:6379 unreachable", "redis://[REDACTED]@cache:6379 unreachable"},
            {"https://user:pa/ss@app.example.com/x", "https://[REDACTED]@app.example.com/x"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
        }
        assertEquals("https://[REDACTED]@app.example.com/x",
            Redactor.redactHeaders(Map.of("Referer", "https://user:pa/ss@app.example.com/x")).get("Referer"));
        // An '@' in a web path or query, or in an object key, is not user info.
        for (String ok : List.of("see https://example.com/search?email=a@b.c&lang=en",
                "see https://example.com:8443/users/@bob/posts", "NoSuchKey s3://bucket/exports/a@b.csv",
                "jdbc:postgresql://db.internal:5432/app;ApplicationName=web")) {
            assertEquals(ok, Redactor.redactMessage(ok), ok);
        }
    }

    @Test
    void aSecretRunIsRedactedWhateverPunctuationSurroundsIt() {
        // M4: the shape test ran on whole delimiter-separated tokens, so one adjacent character
        // ('.', '<', '~', '!') hid the token. Now each maximal run of the secret alphabet is tested,
        // and a dotted token is tested label by label.
        assertEquals("Rejected API key [redacted].", Redactor.redactMessage("Rejected API key " + T + "."));
        assertEquals("<[redacted]>", Redactor.redactMessage("<" + T + ">"));
        assertEquals("[redacted]~x", Redactor.redactHeaders(Map.of("X-Custom", T + "~x")).get("X-Custom"));
        assertEquals("x=[REDACTED]", Redactor.redactQuery("x=" + T + "!"));
        assertEquals("x=[REDACTED]", Redactor.redactQuery("x=" + T + ".json"));
        assertEquals("next=[REDACTED]", Redactor.redactQuery("next=%252Freset%252F" + T));
        assertEquals("/dl/[redacted].pdf", Redactor.redactPath("/dl/" + T + ".pdf"));
        String sendgrid = "SG.Aa1bB2cC3dD4eE5fF6gG7.Hh7iI8jJ9kK0lL1mM2nN3oO4pP5qQ6rR7sS8tT9uU0vV1";
        assertEquals("key SG.[redacted].[redacted] revoked", Redactor.redactMessage("key " + sendgrid + " revoked"));
        assertEquals("SG.[redacted].[redacted]", Redactor.redactHeaders(Map.of("X-Key", sendgrid)).get("X-Key"));
    }

    @Test
    void percentEncodedAndMatrixPathSecretsAreRedacted() {
        // M5: a segment is tested raw and percent-decoded (up to three rounds), and ;name=value
        // matrix parameters are redacted by name and by shape.
        assertEquals("/reset/[redacted]", Redactor.redactPath("/reset/Xx1aB2cD3eF4%2BgH5iJ6kL7%3D%3D"));
        assertEquals("/reset/[redacted]", Redactor.redactPath("/reset/Xx1aB2cD3eF4%252BgH5iJ6kL7"));
        assertEquals("/reset;token=[REDACTED]", Redactor.redactPath("/reset;token=" + T));
        assertEquals("/reset;token=[REDACTED]/confirm", Redactor.redactPath("/reset;token=abc/confirm"));
        assertEquals("/app;jsessionid=[REDACTED]", Redactor.redactPath("/app;jsessionid=0123ABCD"));
        assertEquals("/x;v=[redacted];page=2", Redactor.redactPath("/x;v=" + T + ";page=2"));
        assertEquals("https://app.example.com/cart;jsessionid=[REDACTED]?step=2",
            Redactor.redactHeaders(Map.of("Referer", "https://app.example.com/cart;jsessionid=0123ABCD?step=2")).get("Referer"));
        for (String ok : List.of("/files/report%20final.pdf", "/users/Jos%C3%A9", "/a%2Fb/c", "/search;page=2",
                "/posts/2024/11/hello-world", "/assets/app.3f9a1c2b.js", "/items/550e8400-e29b-41d4-a716-446655440000",
                "/v1/users/42/orders/12345", "/%E2%9C%93/done")) {
            assertEquals(ok, Redactor.redactPath(ok), ok);
        }
    }

    @Test
    void urlsAndCredentialsInsideOtherHeadersAreRedacted() {
        // M6: Forwarded and Via are no longer kept verbatim; embedded URLs (absolute, <...>, after
        // url= or uri=) go through redactUrl, and name=value pairs are tested by name.
        String[][] cases = {
            {"Link", "<https://app.example.com/reset?token=abc123>; rel=\"prev\"",
                "<https://app.example.com/reset?token=[REDACTED]>; rel=\"prev\""},
            {"Link", "</reset?token=abc123>; rel=\"next\"", "</reset?token=[REDACTED]>; rel=\"next\""},
            {"Forwarded", "for=1.2.3.4;uri=\"/reset?token=hunter2pw\"", "for=1.2.3.4;uri=\"/reset?token=[REDACTED]\""},
            {"Forwarded", "for=1.2.3.4;by=10.0.0.1;proto=https;uri=\"/reset/" + T + "\"",
                "for=1.2.3.4;by=10.0.0.1;proto=https;uri=\"/reset/[redacted]\""},
            {"Sec-WebSocket-Protocol", "base64url.bearer.authorization.k8s.io.Zx81kLm02PqR7sTuV4wXy9AbCd",
                "base64url.bearer.authorization.k8s.io.[redacted]"},
            {"Refresh", "0; url=https://app.example.com/reset?token=hunter2pw",
                "0; url=https://app.example.com/reset?token=[REDACTED]"},
            {"Refresh", "0; url=/reset?token=hunter2pw", "0; url=/reset?token=[REDACTED]"},
            {"X-Upstream-Info", "user=bob password=hunter2Hdr", "user=bob password=[REDACTED]"},
            {"X-Upstream-Info", "user=bob; password=\"hunter 2\"; realm=x", "user=bob; password=\"[REDACTED]\"; realm=x"},
            {"Via", "1.1 proxy.example.com, 1.1 cache (" + T + ")", "1.1 proxy.example.com, 1.1 cache ([redacted])"},
            // nit: the value boundary: a redacted value no longer swallows the next parameter name
            {"X-Params", "a=" + T + "&b=2", "a=[redacted]&b=2"},
            {"X-Params", "a=" + T + ";b=2", "a=[redacted];b=2"},
        };
        for (String[] c : cases) {
            assertEquals(c[2], Redactor.redactHeaders(Map.of(c[0], c[1])).get(c[0]), c[0] + ": " + c[1]);
        }
    }

    /** Header values the final-review rules must keep (in addition to HEADERS_THAT_SURVIVE). */
    static final List<Map.Entry<String, String>> MORE_HEADERS_THAT_SURVIVE = List.of(
        Map.entry("Forwarded", "for=\"[2001:db8:cafe::17]:4711\";by=203.0.113.43;proto=https"),
        Map.entry("Forwarded", "for=192.0.2.60;proto=http;by=203.0.113.43, for=198.51.100.17"),
        Map.entry("Via", "1.1 vegur"),
        Map.entry("Via", "1.1 varnish (Varnish/6.0), 2.0 2f8c1e4a9b3d7f6e5a4c3b2a1f0e9d8c.cloudfront.net (CloudFront)"),
        Map.entry("Link", "<https://cdn.example.com/app.3f9a1c2b.css>; rel=preload; as=style"),
        Map.entry("X-Upstream-Info", "user=bob; realm=\"Example Realm\"; region=eu-west-1"),
        Map.entry("X-Lb", "my-loadbalancer-1234567890.us-west-2.elb.amazonaws.com"),
        Map.entry("X-Error-Class", "com.amazonaws.services.s3.model.AmazonS3Exception"),
        Map.entry("X-Note", "see https://example.com/docs/getting-started#install, then retry"),
        Map.entry("X-Client-Path", "/var/app/releases/20241105/public"));

    @Test
    void moreOrdinaryHeadersSurvive() {
        for (var h : MORE_HEADERS_THAT_SURVIVE) {
            var out = Redactor.redactHeaders(Map.of(h.getKey(), h.getValue()));
            assertEquals(h.getValue(), out.get(h.getKey()), h.getKey() + ": " + h.getValue());
        }
    }

    /** Messages that must come back unchanged under the run rule. */
    static final List<String> MESSAGES_THAT_SURVIVE = List.of(
        "Caused by: com.amazonaws.services.s3.model.AmazonS3Exception: Access Denied",
        "Unexpected end-of-input: com.fasterxml.jackson.core.json.UTF8StreamJsonParser",
        "UnknownHostException: my-loadbalancer-1234567890.us-west-2.elb.amazonaws.com",
        "connect timed out to db.internal:5432 after 10000ms",
        "Row 550e8400-e29b-41d4-a716-446655440000 was updated by another transaction",
        "Version 4.12.0-beta.3+build.20241105 is not supported",
        "File not found: /srv/app/uploads/2024/11/report-final.pdf",
        "For input string: \"12345678901234567890\"",
        "duplicate key value violates unique constraint \"users_email_key\"",
        "Expected 3 arguments but got 2 (see https://example.com/docs/api#limits).",
        "rate limited, 100% of quota used",
        "Template app.3f9a1c2b.js not found");

    @Test
    void ordinaryMessagesSurvive() {
        for (String m : MESSAGES_THAT_SURVIVE) {
            assertEquals(m, Redactor.redactMessage(m), m);
        }
    }

    /**
     * Verification pass: messages the run rule must keep. A file path in a message is judged
     * segment by segment, as redactPath judges it, and a name made of words (snake_case,
     * kebab-case, camelCase, with short digit groups) is not secret-shaped.
     */
    static final List<String> NAMES_AND_PATHS_THAT_SURVIVE = List.of(
        "java.nio.file.NoSuchFileException: /var/lib/app/uploads/2024/11/05/invoice-48213-final-v2.pdf",
        "Script V3__create_users_table.sql failed at db/migration/V3__create_users_table.sql",
        "Migration V19__brace_cache_used_at.sql failed",
        "NoSuchKey: exports/2024/11/05/customer-orders-2024-11-05.csv",
        "ERROR: relation \"user_sessions_archive_2024q4\" does not exist",
        "Unique index or primary key violation: \"CONSTRAINT_INDEX_4 ON PUBLIC.USERS(EMAIL)\"",
        "Connection to db-primary-7f8c9d.internal:5432 refused",
        "class com.example.Foo$$Lambda$142/0x0000000800c8a1b0 cannot be cast to class Bar",
        "class com.example.Foo$$Lambda/0x000001f0a1047000 cannot be cast to class Bar",
        "Version 2024.11.05-1423-g3f9a1c2 is not supported",
        "ERROR: relation \"audit_log_entries_2024\" does not exist",
        "Unrecognized field \"shippingAddressLine2\" (class Order), not marked as ignorable",
        "Order ORD-2024-11-000482 not found",
        "Invoice INV-20241105-000482 could not be sent",
        "No enum constant Status.PARTIALLY_REFUNDED_V2",
        "UTF8StreamJsonParser failed",
        "Http2ConnectionHandlerImpl closed the stream",
        "XMLHttpRequest2Handler failed",
        "blog post 2024-black-friday-sale not found");

    /**
     * Names the word rule still cannot tell from a secret, and so redacts: a short hex group
     * (a pod's ReplicaSet hash, a host suffix), a one-letter camelCase word, two words with digits,
     * three parts of one length (a grouped code's shape), a 40-hex commit SHA.
     */
    static final List<String> NAMES_STILL_REDACTED = List.of(
        "web-7d9f8b6c5-x2k4q", "db-primary-7f8c9d", "AmazonS3Exception", "utf8mb4_0900_ai_ci",
        "test-case-data-2024", "3f9a1c2b4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f90");

    @Test
    void namesAndFilePathsInMessagesSurvive() {
        for (String m : NAMES_AND_PATHS_THAT_SURVIVE) {
            assertEquals(m, Redactor.redactMessage(m), m);
        }
        // The same names in a request path and a query value.
        assertEquals("/files/2024/11/invoice-48213-final-v2.pdf", Redactor.redactPath("/files/2024/11/invoice-48213-final-v2.pdf"));
        assertEquals("/blog/2024-black-friday-sale", Redactor.redactPath("/blog/2024-black-friday-sale"));
        assertEquals("utm_campaign=2024-black-friday-sale", Redactor.redactQuery("utm_campaign=2024-black-friday-sale"));
        for (String v : NAMES_STILL_REDACTED) {
            assertEquals("seen [redacted] here", Redactor.redactMessage("seen " + v + " here"), v);
        }
    }

    @Test
    void aFilePathInAMessageIsRedactedSegmentBySegment() {
        String[][] cases = {
            {"java.io.FileNotFoundException: /tmp/brace-uploads/upload-8a7b6c5d4e3f2a1b.tmp",
                "java.io.FileNotFoundException: /tmp/brace-uploads/[redacted].tmp"},
            {"Failed to load /assets/main-3f9a1c2b4d5e6f7a8b9c0d1e.css", "Failed to load /assets/[redacted].css"},
            {"no such file /reset/" + T + "/confirm", "no such file /reset/[redacted]/confirm"},
            {"NoSuchKey: exports/" + T + "/data.csv", "NoSuchKey: exports/[redacted]/data.csv"},
            {"GET /v1/payment_intents/pi_3OaBcD2eFgHiJkLm failed", "GET /v1/payment_intents/[redacted] failed"},
            // A standard-base64 secret holding '/' is still replaced whole.
            {"key aB3dE/fG7hI9jK1/mN2oP4qR rejected", "key [redacted] rejected"},
            {"key /aB3dE9fG/h1JkLmN0pQrS7tUv rejected", "key [redacted] rejected"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
        }
    }

    /** Look-alikes: a word next to a secret-shaped part, and tokens that must stay redacted. */
    static final List<String> LOOK_ALIKES_THAT_ARE_REDACTED = List.of(
        "reset-8a7b6c5d4e3f2a1b9c0d", "token_Ab3dE9fGh1JkLmN0pQrS", "upload-8a7b6c5d4e3f2a1b",
        "8a7b6c5d4e3f2a1b", "3f9a1c2b4d5e6f7a8b9c0d1e", "3f9a1c2b4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f90",
        "pi_3OaBcD2eFgHiJkLm", "kqzrmw8pxtnbv7ab", "KQZR8MWPX3TNBV7A", "deadbeefcafe1234", "abcd-ef12-gh3i-4jkl",
        HEX32, RANDOM32, T, "0x" + HEX32 + HEX32);

    @Test
    void lookAlikesMixingWordsAndSecretsAreRedacted() {
        for (String v : LOOK_ALIKES_THAT_ARE_REDACTED) {
            assertEquals("seen [redacted] here", Redactor.redactMessage("seen " + v + " here"), v);
        }
        // Random tokens of every common alphabet, 16 to 40 characters, are redacted.
        var rnd = new java.util.Random(42);
        String[] alphabets = {"0123456789abcdef", "0123456789ABCDEF", "0123456789abcdefghijklmnopqrstuvwxyz",
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"};
        int kept = 0;
        var keptExamples = new java.util.ArrayList<String>();
        for (String alphabet : alphabets) {
            for (int n = 0; n < 2_000; n++) {
                int len = 16 + rnd.nextInt(25);
                var sb = new StringBuilder(len);
                for (int k = 0; k < len; k++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                String tok = sb.toString();
                if (!Redactor.isSecretShaped(tok)) continue; // all digits, all letters, a UUID: not candidates
                if (Redactor.redactMessage("x " + tok).equals("x " + tok)) {
                    kept++;
                    if (keptExamples.size() < 5) keptExamples.add(tok);
                }
            }
        }
        assertEquals(0, kept, "random tokens kept as names: " + keptExamples);
    }

    @Test
    void contentHashesInFileNamesAreRedactedDeliberately() {
        // A content hash of 16+ characters in a file name cannot be told from a download token
        // (/dl/<token>.pdf), so it is redacted; short fingerprints (Brace's own ?v=, app.3f9a1c2b.js)
        // survive. The cost is cosmetic: an error record shows /assets/[redacted].css.
        assertEquals("/assets/[redacted].css", Redactor.redactPath("/assets/main-3f9a1c2b4d5e6f7a8b9c0d1e.css"));
        assertEquals("https://cdn.example.com/assets/[redacted].css", Redactor.redactHeaders(
            Map.of("Referer", "https://cdn.example.com/assets/main-3f9a1c2b4d5e6f7a8b9c0d1e.css")).get("Referer"));
    }

    // ---- Final review (2026-11): name segments (L11) and the field rule (L12) ----

    /** Names {@link Redactor#isSensitive} (query strings, headers, pairs in transit) must match. */
    static final List<String> STRICT_NAMES_THAT_MATCH = List.of(
        // whole-name and auth-paired "code"
        "code", "Code", "X-Code", "verification_code", "verificationCode", "VerificationCode", "auth_code",
        "authCode", "authcode", "reset_code", "resetCode", "otp_code", "sms_code", "mfa_code", "2fa_code",
        "pin_code", "invite_code", "access_code", "login_code", "confirm_code", "confirmation_code",
        "activation_code", "recovery_code", "backup_code", "security_code", "magic_code", "device_code",
        "code_verifier", "user[auth_code]", "passcode", "pass_code", "Passcode",
        // short words as a whole segment
        "pin", "user_pin", "new-pin", "session", "user_session", "session_id", "sessionId", "sid", "account_sid",
        "ticket", "service_ticket", "sig", "SIG", "url_sig", "otp", "sms_otp",
        // auth as the last segment
        "X-Auth", "X-Upstream-Auth", "Proxy-Auth", "basic_auth",
        // the substring list (unchanged)
        "token", "api_key", "x-api-key", "password", "csrf-token", "X-Auth-Token", "client_assertion", "jwt", "nonce",
        // verification pass: totp alone, and the plural "codes" next to an auth word
        "totp", "TOTP", "backup_codes", "recovery_codes", "recoveryCodes", "otp_codes", "passcodes",
        // second review (L-4): one-time and second-factor names
        "onetimecode", "one_time_code", "onetime_code", "oneTimeCode", "OneTimeCode", "two_factor", "twoFactor",
        "two-factor", "two_factor_code", "twoFactorCode", "twofactorcode", "2fa", "2FA", "user_2fa", "2FA_CODE",
        "2fa_codes", "mfa", "MFA", "X-MFA", "tfa", "multi_factor", "second_factor", "verification",
        "email_verification", "Verification");

    /** Names {@link Redactor#isSensitive} must NOT match. */
    static final List<String> STRICT_NAMES_THAT_DONT = List.of(
        "zip_code", "zipcode", "status_code", "error_code", "country_code", "country-code", "currency_code",
        "postal_code", "promo_code", "language_code", "exit_code", "http_code", "response_code", "product_code",
        "code_challenge", "user_code", "postcode", "barcode", "encode", "codec", "signal", "design", "assign",
        "sigma", "footprint", "hotpath", "otpauth_uri", "pinned", "is_pinned", "spin", "pinterest", "sessions",
        "sidebar", "side", "tickets", "author", "authority", "auth_method", "auth_provider", "oauth_provider",
        "X-Auth-Request-User", "state", "username", "count", "path", "monkey",
        "zip_codes", "status_codes", "country_codes", "error_codes", "barcodes",
        // second review (L-4): the new words count only where they name the value itself
        "two_factor_enabled", "twoFactorEnabled", "mfa_enabled", "mfa_method", "2fa_required", "verification_status",
        "verification_url", "factor", "scale_factor", "load_factor", "one_time", "onetime", "time", "timecode",
        "first_time_code", "one_code_time");

    /** Names {@link Redactor#isSensitiveField} (log fields, route params) must match. */
    static final List<String> FIELD_NAMES_THAT_MATCH = List.of(
        "password", "token", "api_key", "secret", "csrf", "jwt", "sessionId", "session_id", "X-Auth-Token",
        "auth_code", "verification_code", "verificationCode", "reset_code", "pin_code", "passcode", "otp",
        "otp_code", "code_verifier", "device_code",
        "totp", "backup_codes", "recovery_codes", "recoveryCodes", "otp_codes", "passcodes",
        // second review (L-4): only the forms paired with "code"
        "onetimecode", "one_time_code", "onetime_code", "oneTimeCode", "two_factor_code", "twoFactorCode",
        "twofactorcode", "2fa_code", "2FA_CODE", "mfa_code", "mfa_codes");

    /** Names {@link Redactor#isSensitiveField} must NOT match: the short words that are ordinary data in logs and routes. */
    static final List<String> FIELD_NAMES_THAT_DONT = List.of(
        "code", "Code", "sig", "pin", "session", "sid", "ticket", "state", "X-Auth", "otp_enabled",
        "zip_code", "status_code", "error_code", "country_code", "postcode", "sessions", "count", "user",
        "codes", "zip_codes", "status_codes", "totp_enabled",
        // second review (L-4): plain words stay ordinary data in a log field or a route parameter
        "mfa", "2fa", "tfa", "two_factor", "twoFactor", "verification", "email_verification", "mfa_enabled",
        "two_factor_enabled", "factor", "onetime", "one_time", "first_time_code");

    @Test
    void sensitiveNamesMatchBySegment() {
        for (String n : STRICT_NAMES_THAT_MATCH) assertTrue(Redactor.isSensitive(n), "strict should match: " + n);
        for (String n : STRICT_NAMES_THAT_DONT) assertFalse(Redactor.isSensitive(n), "strict should not match: " + n);
        for (String n : FIELD_NAMES_THAT_MATCH) assertTrue(Redactor.isSensitiveField(n), "field should match: " + n);
        for (String n : FIELD_NAMES_THAT_DONT) assertFalse(Redactor.isSensitiveField(n), "field should not match: " + n);
    }

    @Test
    void shortSecretsUnderMissedNamesAreRedactedInTransit() {
        // L11: short secrets under names the old list missed.
        for (String name : List.of("verification_code", "auth_code", "reset_code", "passcode", "pin", "session",
                "sid", "ticket")) {
            assertEquals(name + "=[REDACTED]&page=2", Redactor.redactQuery(name + "=hunter2&page=2"), name);
        }
        assertEquals("[REDACTED]", Redactor.redactHeaders(Map.of("X-Auth", "hunter2pw")).get("X-Auth"));
        assertEquals("[REDACTED]", Redactor.redactHeaders(Map.of("X-Upstream-Auth", "password=hunter2Hdr")).get("X-Upstream-Auth"));
        // a short token nested in an encoded URL value
        assertEquals("next=[REDACTED]",
            Redactor.redactQuery("next=https%3A%2F%2Fapp.example.com%2Freset%3Ftoken%3Dhunter2pw"));
        assertEquals("next=[REDACTED]", Redactor.redactQuery("next=%2Freset%3Fcode%3Dhunter2"));
        // ordinary names and nested URLs survive
        assertEquals("zip_code=94107&status_code=404&next=%2Fposts%3Fpage%3D2",
            Redactor.redactQuery("zip_code=94107&status_code=404&next=%2Fposts%3Fpage%3D2"));
    }

    @Test
    void logFieldsAndRouteParamsUseTheFieldRule() {
        // L12: code/sig/pin/session/... are credentials in a query string, but ordinary data in a
        // structured log field or a route parameter.
        LogTap.clear();
        Log.info("charge failed", Map.of("code", "card_declined", "auth_code", "hunter2", "session", "s-42"));
        var entry = LogTap.snapshot().stream()
            .filter(e -> "charge failed".equals(e.fields().get("message")))
            .findFirst().orElseThrow();
        assertEquals("card_declined", entry.fields().get("code"));
        assertEquals("s-42", entry.fields().get("session"));
        assertEquals(Redactor.PLACEHOLDER, entry.fields().get("auth_code"));
        var countries = new Route("GET", "/countries/{code}", (Handler) r -> null, null);
        assertEquals("/countries/US", Analytics.storedPath(countries, "/countries/US"));
        var verify = new Route("GET", "/verify/{verification_code}", (Handler) r -> null, null);
        assertEquals("/verify/{verification_code}", Analytics.storedPath(verify, "/verify/123456"));
        var reset = new Route("GET", "/reset/{token}", (Handler) r -> null, null);
        assertEquals("/reset/{token}", Analytics.storedPath(reset, "/reset/abc"));
        // The strict rule still applies in transit.
        assertEquals("code=[REDACTED]", Redactor.redactQuery("code=card_declined"));
        // And messages use the field rule for name=value pairs.
        assertEquals("charge failed code=card_declined password=[REDACTED]",
            Redactor.redactMessage("charge failed code=card_declined password=hunter2"));
    }

    @Test
    void deeplyNestedLogFieldsAreBoundedNotAStackOverflow() {
        // nit: redact(Map) recursed once per level; 20,000 levels overflowed the stack.
        Map<String, Object> deep = new java.util.HashMap<>();
        Map<String, Object> cur = deep;
        for (int i = 0; i < 20_000; i++) {
            Map<String, Object> next = new java.util.HashMap<>();
            cur.put("n", i % 2 == 0 ? next : List.of(next));
            if (i % 2 != 0) cur.put("token", "x");
            cur = next;
        }
        var out = Redactor.redact(deep);
        Object level = out;
        int depth = 0;
        while (level instanceof Map<?, ?> m || level instanceof List<?>) {
            if (level instanceof List<?> l) level = l.get(0);
            else {
                var map = (Map<?, ?>) level;
                if (map.containsKey("token")) assertEquals(Redactor.PLACEHOLDER, map.get("token"));
                level = map.get("n");
            }
            depth++;
        }
        assertEquals(Redactor.TOO_DEEP, level);
        assertTrue(depth <= Redactor.MAX_NESTING + 1, "depth " + depth);
        // A map that contains itself is cut off the same way.
        Map<String, Object> self = new java.util.HashMap<>();
        self.put("me", self);
        self.put("again", self);
        assertNotNull(Redactor.redact(self));
        // A shared child under two keys at every level (2^40 paths) is bounded by the copy budget.
        Map<String, Object> shared = Map.of("leaf", 1);
        for (int i = 0; i < 40; i++) shared = Map.of("a", shared, "b", shared);
        long t0 = System.nanoTime();
        assertNotNull(Redactor.redact(shared));
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L);
    }

    @Test
    void reviewerWorstCasesStayLinear() {
        // Error path only, but attacker-sized. Each shape targets one scanner: percent runs,
        // ampersands, dots, %25 (decode rounds), '=' pairs, '<'+'/' spans, scheme scans, nested
        // URLs, quotes, name runs. 64 KB and 1 MB of each, through every entry point.
        List<String> units = List.of("%", "&", ".", "%25", "=", "</", "a:", "x://", "a=/", "\"", "a=\"",
            "https://h/?n=", "a[", "~", "$", "Ab1/", "a.b.c.", "%2", "x=%25",
            // verification pass: mid-run schemes, user info to the last '@', ';' parameters, names
            "1https://u:p@", "-https:/", "/https://", "x-jdbc:s://h;a=b", "postgres://a:b/c@", ";password=",
            "@", "a://b:c/d@", "ab_cd-", "Ab3_", "2024q4-", "0x1/",
            // second review: bracketed values with and without a closer, user info across a
            // URL-ending character (the '@' look-ahead), parameter sections, frame-like lines
            "token=[", "token={a}}", "token='a''", "password=(", "x=[", "a://b:c^", "a://b:c ", "a://b:1^",
            "a://h;p=a/", "a://u:p;", "\n\tat a.B(", "\n at a.b(C.java:1) ~[", "aB/cD1/", "Ab/Cd/",
            // any scheme inside a run, and a token for a scheme
            "a-b://", "_x://u:p@", "a_b:c_d://", "Ab1Cd2Ef3Gh4Ij5Kl6://");
        long t0 = System.nanoTime();
        for (int size : List.of(64 * 1024, 1024 * 1024)) {
            for (String unit : units) {
                String big = unit.repeat(size / unit.length());
                Redactor.redactQuery(big);
                Redactor.redactUrl("https://h/" + big);
                Redactor.redactPath("/" + big);
                Redactor.redactMessage(big);
                Redactor.redactStackTrace("java.lang.RuntimeException: " + big + "\n\tat a.B(B.java:1)\n");
                Redactor.redactHeaders(Map.of("X-Big", big, "Referer", "https://h/?" + big, "Forwarded", big));
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 20_000, "worst cases took " + ms + " ms");
    }

    @Test
    void aWebSocketBroadcastFailureIsLoggedRedacted() {
        // L13 final review: WsRegistry logged "error=" + e raw; the exception text reaches /ops/logs.
        var registry = new WsRegistry(new InProcessMessageBus(), 1 << 20);
        var ctx = new WsContext(null, null, registry) {
            @Override
            public void send(String message) {
                throw new IllegalStateException("relay wss://bot:hunter2pw@relay.example/ws?token=abc rejected " + T);
            }
        };
        registry.join("room", ctx);
        LogTap.clear();
        registry.broadcast("room", "hi");
        String logs = LogTap.snapshot().toString();
        assertTrue(logs.contains("ws-broadcast-send-failed"), logs);
        for (String secret : List.of(T, "hunter2pw", "token=abc")) {
            assertFalse(logs.contains(secret), secret + " logged: " + logs);
        }
    }

    @Test
    void longAdversarialInputStaysLinear() {
        // Error path only, but attacker-sized: a 1 MB value of near-misses must not blow up.
        String nearMiss = "a".repeat(19) + "." + "b".repeat(19);
        String big = (nearMiss + "/").repeat(25_000) + "%".repeat(10_000) + "=&".repeat(10_000);
        long t0 = System.nanoTime();
        Redactor.redactQuery(big);
        Redactor.redactUrl("https://h/" + big);
        Redactor.redactHeaders(Map.of("X-Big", big));
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L, "redaction of a 1 MB value must be fast");
    }

    // ---- Second review (2026-11): docs/2026-11-09-security-review-todos.md, M6 "Second review fixes" ----

    @Test
    void aRunAfterJdbcThatIsNotASchemeIsTextNotAUrl() {
        // H-1: "jdbc:" before a run holding _ / ~ $ % and then "://" was taken for a URL start,
        // the URL scan found no scheme there, and substring(i, -1) threw out of every entry point.
        for (String in : List.of("jdbc:my_driver://host/db", "Invalid url jdbc:_x://h", "jdbc:a/b://h",
                "jdbc:a~b://h", "jdbc:a$b://h", "jdbc:a%b://h", "jdbc:%://", "x jdbc:_://")) {
            assertEquals(in, Redactor.redactMessage(in), in);
            assertEquals(in, Redactor.redactValue(in), in);
            assertEquals(in + "\n\tat a.B.c(B.java:1)\n", Redactor.redactStackTrace(in + "\n\tat a.B.c(B.java:1)\n"), in);
            assertEquals(in, Redactor.redactHeaders(Map.of("X-Thing", in)).get("X-Thing"), in);
        }
        assertEquals("x=jdbc:_x://h", Redactor.redactQuery("x=jdbc:_x://h"));
        assertEquals("x=jdbc%3A_x%3A%2F%2Fh", Redactor.redactQuery("x=jdbc%3A_x%3A%2F%2Fh"));
        assertEquals("https://h/p?next=jdbc:_x://h", Redactor.redactUrl("https://h/p?next=jdbc:_x://h"));
        assertEquals("/jdbc%3A%5Fx%3A%2F%2Faaaaaaaaaaaa", Redactor.redactPath("/jdbc%3A%5Fx%3A%2F%2Faaaaaaaaaaaa"));
        // A real sub-scheme after a prefix is still a URL.
        assertEquals("-jdbc:sqlserver://db:1433;password=[REDACTED]",
            Redactor.redactMessage("-jdbc:sqlserver://db:1433;password=hunter2"));
    }

    static void throwsThroughReflection() {
        throw new IllegalArgumentException("reflective " + HEX32);
    }

    /** A trace with real frames of every kind the JDK prints here: lambda, virtual thread, reflection, cause, suppressed. */
    static String realTrace() throws Exception {
        Throwable[] caught = new Throwable[1];
        Runnable lambda = () -> {
            try {
                RedactorTest.class.getDeclaredMethod("throwsThroughReflection").invoke(null);
            } catch (ReflectiveOperationException e) {
                var outer = new IllegalStateException("outer " + HEX32, e);
                outer.addSuppressed(new java.io.IOException("suppressed " + RANDOM32, new RuntimeException("under " + T)));
                throw outer;
            }
        };
        Thread thread = Thread.ofVirtual().unstarted(() -> {
            try {
                lambda.run();
            } catch (Throwable e) {
                caught[0] = e;
            }
        });
        thread.start();
        thread.join();
        return traceOf(caught[0]);
    }

    @Test
    void realFrameLinesOfEveryKindAreKept() throws Exception {
        // M-1: a frame line must look like a frame. First, that real ones still do.
        String trace = realTrace();
        int frames = 0;
        for (String line : trace.split("\n")) {
            if (line.strip().startsWith("at ")) {
                frames++;
                assertTrue(Redactor.isFrameLine(line, 0, line.length()), "a real frame is not recognised: " + line);
            }
        }
        assertTrue(frames >= 6, trace);
        for (String needle : List.of("lambda$", "VirtualThread", "java.base/", "Caused by: ", "Suppressed: ", "... ")) {
            assertTrue(trace.contains(needle), "precondition, the trace has " + needle + ": " + trace);
        }
        String redacted = Redactor.redactStackTrace(trace);
        for (String secret : List.of(HEX32, RANDOM32, T)) assertFalse(redacted.contains(secret), redacted);
        for (String line : trace.split("\n")) {
            if (line.strip().startsWith("at ") || line.strip().startsWith("... ")) {
                assertTrue(redacted.contains(line + "\n"), "frame line changed: " + line);
            }
        }
        // Frame shapes this JVM did not print above, as StackTraceElement and Logback write them.
        var shapes = new java.util.ArrayList<String>();
        for (StackTraceElement e : List.of(
                new StackTraceElement("app", "my.module", "1.0-SNAPSHOT", "com.x.Y", "z", "Y.java", 1),
                new StackTraceElement("app", null, null, "com.x.Y", "z", "Y.java", 1),
                new StackTraceElement(null, "java.base", "21.0.5+11", "java.lang.Thread", "run", "Thread.java", 1583),
                new StackTraceElement("com.x.Y", "z", "Y.java", -1),
                new StackTraceElement("com.x.Y", "z", null, -1),
                new StackTraceElement("com.x.Y", "nativeCall", "Y.java", -2),
                new StackTraceElement("com.x.Y", "<init>", "Y.java", 3),
                new StackTraceElement("com.x.Y", "<clinit>", "Y.java", 3),
                new StackTraceElement("com.x.Y", "lambda$run$0", "Y.java", 12),
                new StackTraceElement("com.x.Y$$Lambda/0x000001f0a1047000", "run", null, -1),
                new StackTraceElement("com.x.Y$$Lambda$142/0x0000000800c8a1b0", "run", null, -1),
                new StackTraceElement("java.lang.invoke.LambdaForm$MH/0x0000000800c04000", "invoke", "LambdaForm$MH", -1),
                new StackTraceElement("jdk.internal.reflect.GeneratedMethodAccessor12", "invoke", null, -1),
                new StackTraceElement("com.x.Foo$$EnhancerBySpringCGLIB$$1a2b3c4d", "bar", "<generated>", -1),
                new StackTraceElement("com.x.FooKt$main$1", "invokeSuspend", "Foo.kt", 12),
                new StackTraceElement("com.x.Foo$", "$anonfun$main$1", "Foo.scala", 5),
                new StackTraceElement("com.x.Money", "plus-WZ4Q5Ns", "Money.kt", 8),
                new StackTraceElement("com.example.net.Http2ConnectionHandlerImpl", "run", "Http2ConnectionHandlerImpl.java", 42))) {
            shapes.add(e.toString());
        }
        shapes.add("com.x.Http2ConnectionHandlerImpl.z(Http2ConnectionHandlerImpl.java:1) ~[app-1.0.jar:1.0]");
        shapes.add("com.x.Y.z(Y.java:1) [classes/:na]");
        shapes.add("org.springframework.web.Foo.bar(Foo.java:9) ~[spring-web-6.1.0.jar!/:6.1.0]");
        shapes.add("java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]");
        for (String shape : shapes) {
            for (String indent : List.of("\t", "\t\t", "    ")) {
                String line = indent + "at " + shape;
                assertTrue(Redactor.isFrameLine(line, 0, line.length()), "frame not recognised: " + line);
            }
        }
        String all = "a.B: m\n\tat " + String.join("\n\tat ", shapes) + "\n\t... 3 more\n";
        assertSame(all, Redactor.redactStackTrace(all));
    }

    @Test
    void aMessageLineThatStartsWithAtIsNotAFrame() {
        // M-1: any line of whitespace then "at " was taken for a frame and left unredacted, so a
        // continuation line of a multi-line message that happened to start that way kept its
        // secrets. Jackson writes its source excerpt exactly so.
        String jackson = "com.fasterxml.jackson.core.JsonParseException: Unexpected character\n"
            + " at [Source: (String)\"{\"apiKey\":\"sk_live_4eC39HqLyjWDarjtT1zdp7dc\",\"note\":\"x\"}\"; line: 1, column: 3]\n"
            + "\tat com.x.Y.z(Y.java:1)\n";
        assertEquals(jackson.replace("sk_live_4eC39HqLyjWDarjtT1zdp7dc", "[redacted]"), Redactor.redactStackTrace(jackson));
        assertEquals("java.lang.RuntimeException: upstream said\n  at token=[REDACTED] postgres://[REDACTED]@db/x\n"
                + "\tat com.x.Y.z(Y.java:1)\n",
            Redactor.redactStackTrace("java.lang.RuntimeException: upstream said\n"
                + "  at token=" + HEX32 + " postgres://u:hunter2@db/x\n\tat com.x.Y.z(Y.java:1)\n"));
        // Near-frames: no "(source)", a source that is not a file name, text after the frame, a
        // packaging suffix that is not [jar:version].
        String[][] cases = {
            {"\tat token " + HEX32 + " (bad)", "\tat token [redacted] (bad)"},
            {"\tat " + HEX32, "\tat [redacted]"},
            {"\tat com.x.Y.z(password=hunter2)", "\tat com.x.Y.z(password=[REDACTED])"},
            {"\tat x(postgres://u:hunter2@db/x)", "\tat x(postgres://[REDACTED]@db/x)"},
            {"\tat com.x.Y.z(Y.java:1) password=hunter2", "\tat com.x.Y.z(Y.java:1) password=[REDACTED]"},
            {"\tat com.x.Y.z(Y.java:1) ~[" + HEX32 + "]", "\tat com.x.Y.z(Y.java:1) ~[[redacted]]"},
            {"\tat com.x.Y.z(Y.java:1) and " + HEX32, "\tat com.x.Y.z(Y.java:1) and [redacted]"},
            {"\tat com.x.Y.z()", "\tat com.x.Y.z()"},
            // The tighter "... N more" rule stays.
            {"\t... 3 more " + HEX32, "\t... 3 more [redacted]"},
        };
        for (String[] c : cases) {
            assertFalse(Redactor.isFrameLine(c[0], 0, c[0].length()), c[0]);
            assertEquals("a.B: m\n" + c[1] + "\n", Redactor.redactStackTrace("a.B: m\n" + c[0] + "\n"), c[0]);
        }
    }

    @Test
    void aBase64SecretHoldingASlashIsNotTakenForAPath() {
        // M-2: a run with '/' was judged piece by piece whenever no short piece mixed upper case,
        // lower case and digits, so a standard-base64 secret survived when its pieces were short
        // or held no digit (AWS's documented example key, below).
        String[][] cases = {
            {"Invalid key wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "Invalid key [redacted]"},
            {"key /wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY bad", "key [redacted] bad"},
            {"key UlEMvPsopWhuHgywaZzg/KPi1gJZ2tD8jNuxkSSL rejected", "key [redacted] rejected"},
            {"key JzoyJi/theJNjBxXcccJm/dwWU12ydOACauKMgTsU0i rejected", "key [redacted] rejected"},
            {"key /FmyLbFvatOwYUJSPtJFs/wI5fFAOpbl4oYt8s2Vr rejected", "key [redacted] rejected"},
            {"key /dl/wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY.pdf", "key [redacted].pdf"},
            // A secret piece after short pieces that are not words: one base64 value, not a path.
            {"key /IShbxYCPZpUDcmS/DPH/of7Na0GjC6bFm3ssSaxI rejected", "key [redacted] rejected"},
            {"key /1mf7eoWGdELH4mW4Gg3S/HXtglNEQZmWVL/UlJypjT rejected", "key [redacted] rejected"},
            {"open /Users/matt/MyApp/getUserById/" + T + ".tmp", "open /Users/matt/MyApp/getUserById/[redacted].tmp"},
            // Nothing says this is a path (no leading /, ./, ../ or ~/, no file extension), and one
            // piece is a secret: the whole run goes, as before the piece-by-piece rule.
            {"open exports/" + T + "/data", "open [redacted]"},
            // With that evidence the secret piece alone goes.
            {"open /exports/" + T + "/data", "open /exports/[redacted]/data"},
            {"open exports/" + T + "/data.csv", "open exports/[redacted]/data.csv"},
            {"open ./exports/" + T + "/data", "open ./exports/[redacted]/data"},
            {"open ../exports/" + T + "/data", "open ../exports/[redacted]/data"},
            {"open ~/exports/" + T + "/data", "open ~/exports/[redacted]/data"},
            {"open C:/Users/app/uploads/" + T + "/report.pdf", "open C:/Users/app/uploads/[redacted]/report.pdf"},
            {"see /var/lib/app-1.2/releases/3f9a1c2b4d5e6f7a8b9c/current", "see /var/lib/app-1.2/releases/[redacted]/current"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
        }
        // Paths of words survive with or without that evidence.
        for (String ok : List.of(
                "open ./build/libs/app-1.0.jar ../conf/settings_v2.yaml ~/projects/app/target/classes",
                "missing templates/layouts/main_layout_v2 and db/migration/V3__create_users_table",
                "cannot read var/lib/app/data/reports/quarterly-report-2024q4-final.pdf",
                "/Users/matt/code/app/target/classes/templates/layouts/main_layout_v2.jte",
                "/opt/app/classes/com/example/AdminDashboardController2/Internationalization/x",
                "GET /api/v2/users/12345/orders failed; see application/vnd.api+json")) {
            assertEquals(ok, Redactor.redactMessage(ok), ok);
        }
    }

    @Test
    void aSensitiveValueInBracketsOrSingleQuotesIsRedactedToItsCloser() {
        // M-3 (i): for a sensitive name, a value that opened with ' ( [ { < was skipped (the value
        // scan stopped on the opener), so the SQL Server {braced} password form and a toString
        // list of codes were kept.
        String[][] cases = {
            {"jdbc:sqlserver://db:1433;user=sa;password={hunter2};encrypt=true",
                "jdbc:sqlserver://db:1433;user=sa;password={[REDACTED]};encrypt=true"},
            {"jdbc:sqlserver://db:1433;user=sa;password={hun;ter2};encrypt=true",
                "jdbc:sqlserver://db:1433;user=sa;password={[REDACTED]};encrypt=true"},
            {"jdbc:sqlserver://db:1433;user=sa;password=(hunter2);encrypt=true",
                "jdbc:sqlserver://db:1433;user=sa;password=([REDACTED]);encrypt=true"},
            {"jdbc:sqlserver://h;password=\"hunter 2\";x=1", "jdbc:sqlserver://h;password=\"[REDACTED]\";x=1"},
            {"jdbc:sqlserver://h;password='hunter 2';x=1", "jdbc:sqlserver://h;password='[REDACTED]';x=1"},
            {"password='hunter2'", "password='[REDACTED]'"},
            {"password='hunter 2' next", "password='[REDACTED]' next"},
            {"password=(hunter2)", "password=([REDACTED])"},
            {"password={hunter2}", "password={[REDACTED]}"},
            {"password=<hunter2>", "password=<[REDACTED]>"},
            {"password=[hunter2] ok", "password=[[REDACTED]] ok"},
            {"User[id=1, backupCodes=[a1b2c-d3e4f, g5h6i-j7k8l]]", "User[id=1, backupCodes=[[REDACTED]]]"},
            {"recovery_codes=[abcd-1234, efgh-5678]", "recovery_codes=[[REDACTED]]"},
            // nesting of the same bracket, and the doubled closer that escapes one in {..} and '..'
            {"password=(a(b)c) x", "password=([REDACTED]) x"},
            {"password={a}}b};x=1", "password={[REDACTED]};x=1"},
            {"password='it''s' x", "password='[REDACTED]' x"},
            // no closer: to the end of the line, not beyond
            {"password={hunter2 and more\nnext line", "password={[REDACTED]\nnext line"},
            {"password=[a, b", "password=[[REDACTED]"},
        };
        for (String[] c : cases) {
            String once = Redactor.redactMessage(c[0]);
            assertEquals(c[1], once, c[0]);
            assertEquals(once, Redactor.redactMessage(once), "redacting twice changes nothing: " + c[0]);
        }
        assertEquals("user=bob; password='[REDACTED]'; realm=x",
            Redactor.redactHeaders(Map.of("X-Upstream-Info", "user=bob; password='hunter 2'; realm=x")).get("X-Upstream-Info"));
        // Unchanged: an empty value, a value already redacted, and a pair that merely sits inside brackets.
        for (String same : List.of("token=() token=[] token={} token='' token=<> ok", "password=[REDACTED] x",
                "token=[redacted], next", "password=\"[REDACTED]\"", "id={42} name='bob' tags=[a, b]")) {
            assertEquals(same, Redactor.redactMessage(same), same);
        }
        assertEquals("User(id=1, password=[REDACTED]) and Config{password=[REDACTED]} and [password=[REDACTED]]",
            Redactor.redactMessage("User(id=1, password=hunter2) and Config{password=hunter2} and [password=hunter2]"));
        // A URL that ends in a redacted value is the same URL the second time round.
        String url = Redactor.redactMessage("Failed postgres://app@db/x?password=hunter2");
        assertEquals("Failed postgres://[REDACTED]@db/x?password=[REDACTED]", url);
        assertEquals(url, Redactor.redactMessage(url));
    }

    @Test
    void userInfoWithACharacterThatEndsAUrlIsStillMasked() {
        // M-3 (ii): the URL scan stopped at ^ { } | \ ` ' < > or a space inside a password, before
        // the '@', so the URL had no user info to mask and the password stayed.
        for (String odd : List.of("^", "{", "}", "|", "\\", "`", "'", "<", ">", "{ss}", "<ss>", "^^|")) {
            String in = "Failed jdbcUrl=jdbc:postgresql://app:p" + odd + "ssw0rd@db/x now";
            assertEquals("Failed jdbcUrl=jdbc:postgresql://[REDACTED]@db/x now", Redactor.redactMessage(in), in);
        }
        String[][] cases = {
            // One space: masked when the next word holds the '@' and what follows ':' is no port.
            {"Failed postgres://app:my pass@db/x", "Failed postgres://[REDACTED]@db/x"},
            // Digits after ':' with a '/' before the '@' were taken for a port and a path.
            {"Failed https://user:12345/678@host/x", "Failed https://[REDACTED]@host/x"},
            {"Failed postgres://app:12345/678@db/x", "Failed postgres://[REDACTED]@db/x"},
            {"Failed redis://:12345?6@cache:6379/0", "Failed redis://[REDACTED]@cache:6379/0"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
        }
        assertEquals("https://[REDACTED]@host/x", Redactor.redactUrl("https://user:12345/678@host/x"));
        assertEquals("jdbc:postgresql://[REDACTED]@db/x", Redactor.redactUrl("jdbc:postgresql://app:p^ssw0rd@db/x"));
        // Not user info: a host that is evidently a host (a dot, localhost, an IPv6 literal), no
        // ':' at all, an '@' beyond whitespace, a URL in quotes next to an address.
        for (String same : List.of(
                "see https://example.com:8443/a{b}@c and https://example.com/a^b@c",
                "see http://localhost:8080/@alice and http://[::1]:8080/@alice and http://10.0.0.1:8080/@alice",
                "connect to postgres://db:5432 as bob@example.com failed",
                "postgres://app:secret host unreachable, mail bob@example.com",
                "<a href='https://example.com:8443/x'>bob@example.com</a>",
                "{\"url\":\"http://api:8080/x\",\"email\":\"bob@example.com\"}",
                "urls 'http://api:8080/x','bob@example.com'",
                "mail bob@example.com about https://example.com/x then alice@example.com")) {
            assertEquals(same, Redactor.redactMessage(same), same);
        }
    }

    @Test
    void aParameterSectionAfterTheHostCoversTheRestOfTheUrl() {
        // M-3 (iii): only the text up to the first / ? # went through the name=value pass, so a
        // password holding one of those lost its head only (password=[REDACTED]/def123).
        String[][] cases = {
            {"jdbc:sqlserver://db:1433;user=sa;password=abc/def123;encrypt=true",
                "jdbc:sqlserver://db:1433;user=sa;password=[REDACTED];encrypt=true"},
            {"jdbc:sqlserver://db:1433;user=sa;password=abc?def123;encrypt=true",
                "jdbc:sqlserver://db:1433;user=sa;password=[REDACTED];encrypt=true"},
            {"jdbc:sqlserver://db:1433;user=sa;password=abc#def123;encrypt=true",
                "jdbc:sqlserver://db:1433;user=sa;password=[REDACTED];encrypt=true"},
            {"jdbc:sqlserver://db:1433;user=sa;password=a/b?c#d;encrypt=true",
                "jdbc:sqlserver://db:1433;user=sa;password=[REDACTED];encrypt=true"},
            // An '@' in a parameter value is not user info: the host stays, the value goes.
            {"jdbc:sqlserver://db:1433;user=sa;password=p@ss;encrypt=true",
                "jdbc:sqlserver://db:1433;user=sa;password=[REDACTED];encrypt=true"},
            {"jdbc:sqlserver://db.internal:1433;user=sa;password=p@ss/w0rd;encrypt=true",
                "jdbc:sqlserver://db.internal:1433;user=sa;password=[REDACTED];encrypt=true"},
            // A ';' inside a user:password is still user info.
            {"postgres://u:p;w@host/db refused", "postgres://[REDACTED]@host/db refused"},
            {"postgres://us;er:hunter2@host/db refused", "postgres://[REDACTED]@host/db refused"},
            {"postgres://u:p;x=1/w@host/db refused", "postgres://[REDACTED]@host/db refused"},
            // The rest of such a URL is still redacted by name and by shape.
            {"jdbc:sqlserver://db;databaseName=app/" + T + "?token=abc", "jdbc:sqlserver://db;databaseName=[redacted]?token=[REDACTED]"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
        }
        for (String same : List.of("jdbc:sqlserver://db:1433;databaseName=app/main;user=sa",
                "jdbc:postgresql://db.internal:5432/app;ApplicationName=web",
                "jdbc:h2:mem:redactfinal;DB_CLOSE_DELAY=-1")) {
            assertEquals(same, Redactor.redactMessage(same), same);
        }
        // A space still ends an unquoted value (documented in docs/SECURITY.md).
        assertEquals("jdbc:sqlserver://db:1433;user=sa;password=[REDACTED] def123;encrypt=true",
            Redactor.redactMessage("jdbc:sqlserver://db:1433;user=sa;password=abc def123;encrypt=true"));
    }

    @Test
    void anAtSignBeyondAnEvidentHostIsNotUserInfo() {
        // L-2: "user info up to the last '@'" (for a password holding / ? #) applied to every
        // non-web URL whose authority held ':', so a host list or host:port followed by an '@' in
        // the query lost its hosts, path and query: mongodb://[REDACTED]@b.
        for (String same : List.of(
                "Failed mongodb://h1:27017,h2:27017/db?authSource=a@b",
                "Failed mongodb://h1:27017,h2:27017,h3:27017/db?replicaSet=rs0&appName=a@b",
                "Failed postgres://localhost:5432/db?options=user@x",
                "Failed postgres://db.internal:5432/app?email=a@b.c",
                "Failed redis://10.0.0.5:6379/0?client=a@b",
                "Failed amqp://[::1]:5672/vhost?name=a@b")) {
            assertEquals(same, Redactor.redactMessage(same), same);
        }
        String[][] cases = {
            // Real user info in front of a host list is still masked, and the list is kept.
            {"Failed mongodb://u:pw@h1:27017,h2:27017/db?authSource=a@b",
                "Failed mongodb://[REDACTED]@h1:27017,h2:27017/db?authSource=a@b"},
            // A password holding / ? # stays fully masked.
            {"Failed postgres://app:pa/ss@db/x", "Failed postgres://[REDACTED]@db/x"},
            {"Failed redis://:pa?ss@host:6379/0", "Failed redis://[REDACTED]@host:6379/0"},
            {"Failed redis://:p#w@cache:6379", "Failed redis://[REDACTED]@cache:6379"},
            // The tie: one dotless name:digits is a host and port, or a user and a numeric
            // password cut at its '/'. Redaction wins it, in every scheme.
            {"Failed postgres://app:12345/678@db/x", "Failed postgres://[REDACTED]@db/x"},
            {"Failed redis://cache:6379/0?client=a@b", "Failed redis://[REDACTED]@b"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
        }
        assertEquals("mongodb://h1:27017,h2:27017/db?authSource=a@b",
            Redactor.redactUrl("mongodb://h1:27017,h2:27017/db?authSource=a@b"));
    }

    @Test
    void aDottedUserNameWithANumericPasswordIsStillMaskedOutsideWebSchemes() {
        // The other side of L-2: "first.last:12345/678@db" reads as host.name:port/path. Before
        // L-2 every non-web URL with ':' was masked to the last '@', so this one was; keeping an
        // evident host's query must not start leaking it. For one evident host in a non-web
        // scheme an '@' inside a path segment still ends the user info; one in the query, or one
        // that starts a segment (/@handle), does not. Web schemes keep their path as before
        // (http://localhost:3000/assets/logo@2x.png).
        String[][] cases = {
            {"Failed postgres://first.last:12345/678@db.internal/x", "Failed postgres://[REDACTED]@db.internal/x"},
            {"Failed postgres://localhost:5432/678@db.internal/x?sslmode=require",
                "Failed postgres://[REDACTED]@db.internal/x?sslmode=require"},
            {"Failed redis://first.last:12345/6@cache/0?client=a@b", "Failed redis://[REDACTED]@cache/0?client=a@b"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
        }
        for (String same : List.of(
                "Failed postgres://db.internal:5432/app?email=a@b.c",
                "Failed sftp://files.example.com:22/home/@shared/x",
                "Failed mongodb://h1.internal:27017,h2.internal:27017/db/a@b",
                "see http://localhost:3000/assets/logo@2x.png",
                "see https://example.com:8443/users/@bob/posts and https://unpkg.example.com:443/react@18.2.0/x.js")) {
            assertEquals(same, Redactor.redactMessage(same), same);
        }
    }

    @Test
    void anySchemeIsFoundInsideARun() {
        // L-3: inside a run only a fixed list of schemes was looked for, so a URL of any other
        // scheme glued to a prefix kept its user info.
        String[][] cases = {
            {"backup_ldap://cn:hunter2@host/x", "backup_ldap://[REDACTED]@host/x"},
            {"my_app+ldap://cn:hunter2@host/x", "my_app+ldap://[REDACTED]@host/x"},
            {"x_y_z://u:hunter2@h/p", "x_y_z://[REDACTED]@h/p"},
            {"jdbc:my_driver://u:hunter2@host/db", "jdbc:my_driver://[REDACTED]@host/db"},
            {"GET /proxy/sftp://user:hunter2@host/x", "GET /proxy/sftp://[REDACTED]@host/x"},
            {"1sftp://user:hunter2@host/x", "1sftp://[REDACTED]@host/x"},
            {"-https://user:hunter2@host/x", "-https://[REDACTED]@host/x"},
            {"%https://user:hunter2@host/x", "%https://[REDACTED]@host/x"},
            // A token before the scheme is still redacted, glued to it or not.
            {T + "_https://user:hunter2@host/x", "[redacted]https://[REDACTED]@host/x"},
            {T + "https://user:hunter2@host/x", "[redacted]://[REDACTED]@host/x"},
            {"tok a3f9Bc2d8eF1g4h5a3f9://x", "tok [redacted]://x"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], Redactor.redactMessage(c[0]), c[0]);
        }
        for (String same : List.of("see docs://guide and file:///etc/passwd and _://x and 9://x and -://u",
                "com.example.Foo://bar", "jdbc:tc:postgresql:15.3:///db", "word_other://host/path",
                "chrome-extension://abcdefghijklmnop/page.html")) {
            assertEquals(same, Redactor.redactMessage(same), same);
        }
    }

    @Test
    void tokensThatWereTakenForNamesAreRedacted() {
        // L-1: the name rule kept two-group tokens (base32 8-8: 0.76%), long digit groups, any
        // short 0x literal and any <digits>-g<hex>.
        for (String v : List.of("ZAXVVMA5-LAHQRWYU", "OLRWUXXO_PYMGPED7", "htvmuprj-tsnyagi2", "90974875-ffaaadac",
                "deadbeef-12345678", "mgfKoexw-QMA3Jiqr", "KEBIXAGP3-WIOIIWDWS", "nxwegyb0-cxejavwa",
                "mtfohuyjhq-sybhnvezz1", "PPAGTWEDKI26-ADBNODTICPRD", "uhiyagpanv71_jsznymzluuzm",
                "reset-code-48213977", "session-abcdefghijkl-12345678", "test-user-12345678",
                "0x1234567890abcdef", "0xdeadbeefcafe1234", "0x00a3f9bc2d8ef14a", "12345678-gdeadbeefcafe",
                "99999999-gabcdef012345", "1-gabcdef0123456789", "facade-decade-accede-12345678",
                "UFVScbbyhUokwvd5", "breaPmuykrtiAkooTUT9", "RFG8TuduskKhdorfsdAqwt")) {
            assertEquals("seen [redacted] here", Redactor.redactMessage("seen " + v + " here"), v);
        }
        // Still names: words a reader would say, short digit groups, dates, a lambda's address
        // (zero-padded to 16 hex digits), a git describe suffix after a tag.
        for (String v : List.of("V3__create_users_table", "quarterly-report-2024q4-final", "users_email_address_key1",
                "notification_preferences_json2", "invoice-48213-final-v2", "ORD-2024-11-000482", "INV-20241105-000482",
                "build-number-20241105", "report-20241105-final", "customer-orders-2024-11-05", "page-12345-of-99999",
                "audit_log_entries_2024", "user_sessions_archive_2024q4", "CONSTRAINT_INDEX_4", "PARTIALLY_REFUNDED_V2",
                "shippingAddressLine2", "Http2ConnectionHandlerImpl", "UTF8StreamJsonParser", "XMLHttpRequest2Handler",
                "2024-black-friday-sale", "x86_64-linux-gnu", "ip-10-0-12-34-internal", "web-server-01-prod-east",
                "0x0000000800c8a1b0", "0x000001f0a1047000", "05-1423-g3f9a1c2", "v1-1423-g3f9a1c2beef",
                "shipping_address2", "customer-orders1", "password_hasher2", "background_worker_2",
                "monkey_dragon_letmein_2024", "the-quick-brown-fox-jumps-2")) {
            assertEquals("seen " + v + " here", Redactor.redactMessage("seen " + v + " here"), v);
        }
        for (String m : List.of("Version v0.1.11-rc.6-12-gabc1234 is not supported",
                "Version 2024.11.05-1423-g3f9a1c2 is not supported",
                "class com.example.Foo$$Lambda$142/0x0000000800c8a1b0 cannot be cast to class Bar")) {
            assertEquals(m, Redactor.redactMessage(m), m);
        }
    }

    /** One shape of random token for {@link #randomTokensOfEveryShapeAreRedacted}. */
    record TokenShape(String name, String alphabet, int length, int group, String separator, String prefix, int maxKept) {}

    static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    static final String B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    static final String B62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    static final String HEX = "0123456789abcdef";
    static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    static final String B36 = "abcdefghijklmnopqrstuvwxyz0123456789";
    static final String CROCKFORD = "0123456789abcdefghjkmnpqrstvwxyz";

    /** Tokens sampled per shape. */
    static final int SURVIVAL_SAMPLES = 100_000;

    /**
     * The measured ceiling for a shape judged by the name rule: at most 1 token in 10,000 kept
     * whole or by half (the owner's accepted residual for the name-versus-secret heuristic).
     */
    static final int ONE_IN_TEN_THOUSAND = SURVIVAL_SAMPLES / 10_000;

    @Test
    void randomTokensOfEveryShapeAreRedacted() {
        // L-1 and M-2: the survival statistics of the second review, seeded, in the repo. For each
        // shape, 100,000 random tokens that hold a digit and a letter go through redactMessage;
        // a token "survives" when half of it or more is still readable in one piece. Shapes the
        // name rule judges must stay at or under 1 in 10,000. Standard base64 can read as a path
        // of short pieces ("YGw/A0C3B/hJOQFgnBClnm"): those ceilings are what c136520, the last
        // commit before the path and name rules, measured (with margin for the seed), so the
        // rules cost no base64 secret.
        List<TokenShape> shapes = List.of(
            new TokenShape("base64 40 (AWS secret key)", B64, 40, 0, "", "", 3),
            new TokenShape("base64 43 (32 bytes)", B64, 43, 0, "", "", 3),
            new TokenShape("base64 22 (16 bytes)", B64, 22, 0, "", "", 120),
            new TokenShape("base64 27 (20 bytes)", B64, 27, 0, "", "", 35),
            new TokenShape("base64 86 (64 bytes)", B64, 86, 0, "", "", 3),
            new TokenShape("base64 40 after /", B64, 40, 0, "", "/", 3),
            new TokenShape("base64url 43", B64URL, 43, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base64url 22", B64URL, 22, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base62 32", B62, 32, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base62 20", B62, 20, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base62 16", B62, 16, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("hex 32", HEX, 32, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("hex 16", HEX, 16, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("hex 40", HEX, 40, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base32 32", B32, 32, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base32 16 (TOTP secret)", B32, 16, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base36 24", B36, 24, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base36 16", B36, 16, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("crockford 26 (ULID-like)", CROCKFORD, 26, 0, "", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("hex 32 in 8s -", HEX, 32, 8, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("hex 24 in 6s -", HEX, 24, 6, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("hex 16 in 4s -", HEX, 16, 4, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("hex 16 in 8s -", HEX, 16, 8, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("hex 20 in 10s _", HEX, 20, 10, "_", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("hex 24 in 12s -", HEX, 24, 12, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base36 20 in 5s -", B36, 20, 5, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base36 18 in 6s -", B36, 18, 6, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base36 16 in 8s -", B36, 16, 8, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base36 20 in 10s -", B36, 20, 10, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base36 24 in 12s _", B36, 24, 12, "_", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base32 16 in 4s -", B32, 16, 4, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base32 20 in 5s -", B32, 20, 5, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base32 16 in 8s -", B32, 16, 8, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base32 16 in 8s _", B32, 16, 8, "_", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base32 18 in 9s -", B32, 18, 9, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base32 24 in 12s -", B32, 24, 12, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("crockford 16 in 8s -", CROCKFORD, 16, 8, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base62 16 in 8s -", B62, 16, 8, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base62 24 in 12s -", B62, 24, 12, "-", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base62 24 in 8s _", B62, 24, 8, "_", "", ONE_IN_TEN_THOUSAND),
            new TokenShape("base62 21 in 7s -", B62, 21, 7, "-", "", ONE_IN_TEN_THOUSAND));
        var rnd = new java.util.Random(7);
        var over = new java.util.ArrayList<String>();
        var sb = new StringBuilder();
        for (TokenShape shape : shapes) {
            int kept = 0;
            String example = null;
            for (int n = 0; n < SURVIVAL_SAMPLES; n++) {
                sb.setLength(0);
                sb.append(shape.prefix());
                boolean digit = false;
                boolean letter = false;
                for (int k = 0; k < shape.length(); k++) {
                    if (shape.group() > 0 && k > 0 && k % shape.group() == 0) sb.append(shape.separator());
                    char c = shape.alphabet().charAt(rnd.nextInt(shape.alphabet().length()));
                    if (c >= '0' && c <= '9') digit = true;
                    else if (Character.isLetter(c)) letter = true;
                    sb.append(c);
                }
                if (!digit || !letter) continue; // all letters or all digits: never a candidate
                String token = sb.toString();
                String out = Redactor.redactMessage("key " + token + " rejected");
                if (out.equals("key [redacted] rejected")) continue; // the common case, cheaply
                if (longestSurvivingPiece(token, out) * 2 >= token.length()) {
                    kept++;
                    if (example == null) example = token + " => " + out;
                }
            }
            if (kept > shape.maxKept()) {
                over.add(shape.name() + ": " + kept + " of " + SURVIVAL_SAMPLES + " kept (limit " + shape.maxKept()
                    + "), e.g. " + example);
            }
        }
        assertTrue(over.isEmpty(), String.join("\n", over));
    }

    /** Length of the longest piece of {@code token} still present in {@code out}. */
    static int longestSurvivingPiece(String token, String out) {
        if (out.contains(token)) return token.length();
        int best = 0;
        for (int i = 0; i < token.length(); i++) {
            for (int j = token.length(); j > i + best; j--) {
                if (out.contains(token.substring(i, j))) {
                    best = j - i;
                    break;
                }
            }
        }
        return best;
    }

    @Test
    void oneTimeAndSecondFactorValuesAreRedactedInTransit() {
        // L-4: six-digit codes under names the transit rule did not know.
        for (String name : List.of("onetimecode", "one_time_code", "onetime_code", "two_factor", "twoFactorCode",
                "2fa", "2FA", "mfa", "verification")) {
            assertEquals(name + "=[REDACTED]&page=2", Redactor.redactQuery(name + "=123456&page=2"), name);
        }
        assertEquals("[REDACTED]", Redactor.redactHeaders(Map.of("X-MFA", "123456")).get("X-MFA"));
        assertEquals("two_factor_enabled=1&mfa_enabled=1&verification_status=ok&factor=2",
            Redactor.redactQuery("two_factor_enabled=1&mfa_enabled=1&verification_status=ok&factor=2"));
        // In a message (the field rule) only the forms paired with "code" count.
        assertEquals("mfa=on verification=pending two_factor=on one_time_code=[REDACTED] twoFactorCode=[REDACTED]",
            Redactor.redactMessage("mfa=on verification=pending two_factor=on one_time_code=123456 twoFactorCode=123456"));
    }

    @Test
    void everyEntryPointFailsClosedWhenRedactionFailsInside() {
        // H-1: the redactor runs on error paths, so a bug inside it must cost one marker, never an
        // exception (the error being recorded would be lost) and never the raw input.
        String secret = "hunter2-" + HEX32;
        String trace = "java.lang.RuntimeException: bad token " + secret + "\n\tat a.B.c(B.java:1)\n"
            + "Caused by: java.lang.Error: again " + secret + "\n\t... 3 more\n";
        var headers = new java.util.LinkedHashMap<String, Object>();
        headers.put("X-Token-Holder", secret);
        headers.put("Referer", "https://app.example.com/reset/" + secret);
        headers.put("X-Count", 3);
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("message", "login");
        fields.put("password", secret);
        Redactor.faultForTest = () -> { throw new IllegalStateException("injected"); };
        try {
            assertEquals(Redactor.FAILED, Redactor.redactMessage("bad token " + secret));
            assertEquals(Redactor.FAILED, Redactor.redactQuery("token=" + secret + "&page=2"));
            assertEquals(Redactor.FAILED, Redactor.redactQuery("x=" + secret));
            assertEquals(Redactor.FAILED, Redactor.redactUrl("https://app.example.com/reset?t=" + secret));
            assertEquals(Redactor.FAILED, Redactor.redactPath("/reset/" + secret));
            assertEquals(Redactor.FAILED, Redactor.redactValue("v1 " + secret));
            // A trace fails line by line, so its frames are kept.
            assertEquals(Redactor.FAILED + "\n\tat a.B.c(B.java:1)\n" + Redactor.FAILED + "\n\t... 3 more\n",
                Redactor.redactStackTrace(trace));
            assertTrue(Redactor.isSensitive("page"), "a name that cannot be judged is treated as sensitive");
            assertTrue(Redactor.isSensitiveField("page"));
            var h = Redactor.redactHeaders(headers);
            assertEquals(List.of("X-Token-Holder", "Referer", "X-Count"), List.copyOf(h.keySet()));
            assertEquals(Redactor.FAILED, h.get("X-Token-Holder"));
            assertEquals(Redactor.FAILED, h.get("Referer"));
            assertEquals(Redactor.FAILED, h.get("X-Count"));
            var f = Redactor.redact(fields);
            assertEquals(List.of("message", "password"), List.copyOf(f.keySet()));
            assertEquals(Redactor.FAILED, f.get("message"));
            assertEquals(Redactor.FAILED, f.get("password"));
        } finally {
            Redactor.faultForTest = null;
        }
        // The seam is off again: ordinary results.
        assertEquals("bad token [redacted]", Redactor.redactMessage("bad token " + HEX32));
        assertFalse(Redactor.isSensitive("page"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mapFormsFailClosedOnAMapThatMisbehaves() {
        // No seam needed here: a map whose iteration throws, a key that is not a String (a raw
        // map from application code), and null.
        Map<String, Object> throwing = new java.util.AbstractMap<>() {
            @Override
            public java.util.Set<Map.Entry<String, Object>> entrySet() {
                throw new java.util.ConcurrentModificationException("mutated while logging");
            }
        };
        assertEquals(Map.of("redaction", Redactor.FAILED), Redactor.redact(throwing));
        assertEquals(Map.of("redaction", Redactor.FAILED), Redactor.redactHeaders(throwing));
        Map raw = new java.util.LinkedHashMap();
        raw.put(42, "hunter2-" + HEX32);
        raw.put("ok", "fine");
        assertEquals(Map.of("42", Redactor.FAILED, "ok", Redactor.FAILED), Redactor.redact((Map<String, Object>) raw));
        assertEquals(Map.of("42", Redactor.FAILED, "ok", Redactor.FAILED), Redactor.redactHeaders((Map<String, ?>) raw));
        assertEquals(Map.of(), Redactor.redact(null));
        assertEquals(Map.of(), Redactor.redactHeaders(null));
    }
}
